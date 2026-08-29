package com.m500.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.log2
import kotlin.math.pow

/**
 * Poor-man's ambient light sensor.
 *
 * The M500 has NO ambient light sensor - docs/01_hardware_and_sensors_report.md
 * confirms 22 hardware sensors with zero optical/photodiode devices, and the
 * display service runs with mLightSensor=null / autoBrightness=false. This
 * derives an ambient reading from the camera instead.
 *
 * IMPORTANT - why this does not just average pixels:
 * the camera's auto-exposure normalises the frame, so a dark room and direct
 * sunlight both return roughly mid-grey luma. Instead we let AE settle, then
 * read back the exposure parameters it chose and compute EV100:
 *
 *     EV100 = log2(N^2 / t) - log2(S / 100)
 *     lux   ~= 2.5 * 2^EV100
 *
 * where N = f-number, t = exposure seconds, S = ISO. Short exposure + low ISO
 * means the scene is bright. This is a real photometric estimate and is immune
 * to AE normalisation. Mean luma is kept only as a fallback for devices that do
 * not report SENSOR_EXPOSURE_TIME / SENSOR_SENSITIVITY.
 */
object AmbientCamera {
    private const val TAG = "AmbientCamera"
    private const val OPEN_TIMEOUT_MS = 2500L
    private const val DEFAULT_APERTURE = 2.0f   // used if LENS_APERTURE is absent

    /** Result of one ambient sample. */
    data class Reading(
        val lux: Float,
        val ev100: Float,
        val meanLuma: Int,
        val fromExposure: Boolean,
    )

    /** True only if a camera is actually enumerable. Vendor VINTF declaring a
     *  camera HAL is not proof - the same BSP declares a light sensor that does
     *  not physically exist. */
    fun isAvailable(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.isNotEmpty()
    } catch (t: Throwable) {
        Log.w(TAG, "camera enumeration failed", t); false
    }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Open the camera, let AE settle, grab one frame, close. Returns null if
     * unavailable, denied, or timed out. Never throws.
     */
    suspend fun sample(ctx: Context): Reading? = withContext(Dispatchers.IO) {
        if (!isAvailable(ctx) || !hasPermission(ctx)) return@withContext null
        withTimeoutOrNull(OPEN_TIMEOUT_MS) { sampleInner(ctx) }
    }

    private suspend fun sampleInner(ctx: Context): Reading? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = pickCamera(cm) ?: return null
        val chars = cm.getCameraCharacteristics(id)

        // Smallest YUV size available - we only need photometry, not an image.
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.minByOrNull { it.width * it.height } ?: return null

        val thread = HandlerThread("AmbientCamera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val done = CompletableDeferred<Reading?>()

        try {
            device = openCamera(cm, id, handler) ?: return null
            session = createSession(device, reader, handler) ?: return null

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }

            // Let AE converge before trusting the exposure parameters.
            session.setRepeatingRequest(builder.build(), null, handler)
            Thread.sleep(450)

            var luma = -1
            reader.setOnImageAvailableListener({ r ->
                // NB: android.media.Image is AutoCloseable, not Closeable, so
                // Kotlin's use{} is not dependable here - close explicitly.
                var img: android.media.Image? = null
                try {
                    img = r.acquireLatestImage()
                    if (img != null) luma = meanLuma(img)
                } catch (_: Throwable) {
                } finally {
                    try { img?.close() } catch (_: Throwable) { }
                }
            }, handler)

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult
                ) {
                    done.complete(fromResult(result, chars, luma))
                }
                override fun onCaptureFailed(
                    s: CameraCaptureSession, req: CaptureRequest,
                    f: android.hardware.camera2.CaptureFailure
                ) { done.complete(null) }
            }, handler)

            return done.await()
        } catch (t: Throwable) {
            Log.w(TAG, "sample failed", t); return null
        } finally {
            try { session?.close() } catch (_: Throwable) {}
            try { device?.close() } catch (_: Throwable) {}
            try { reader.close() } catch (_: Throwable) {}
            thread.quitSafely()
        }
    }

    private fun fromResult(
        result: CaptureResult, chars: CameraCharacteristics, luma: Int
    ): Reading {
        val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
            ?: DEFAULT_APERTURE

        if (expNs != null && iso != null && expNs > 0 && iso > 0) {
            val t = expNs / 1_000_000_000.0f
            val ev100 = log2((aperture * aperture) / t) - log2(iso / 100.0f)
            val lux = 2.5f * 2.0f.pow(ev100)
            return Reading(lux, ev100, luma, fromExposure = true)
        }
        // Fallback: AE parameters unavailable. Mean luma is weak but better
        // than nothing; callers should treat fromExposure=false as low trust.
        val approx = if (luma < 0) 0f else (luma / 255.0f) * 2000f
        return Reading(approx, 0f, luma, fromExposure = false)
    }

    /** Mean of the Y plane, subsampled for speed. */
    private fun meanLuma(img: android.media.Image): Int {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        var sum = 0L; var n = 0
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val idx = y * rowStride + x * pixStride
                if (idx < buf.limit()) { sum += (buf.get(idx).toInt() and 0xFF); n++ }
                x += 8
            }
            y += 8
        }
        return if (n == 0) -1 else (sum / n).toInt()
    }

    private fun pickCamera(cm: CameraManager): String? {
        val ids = cm.cameraIdList
        if (ids.isEmpty()) return null
        // Prefer a back-facing lens; it is the one pointing at the world when
        // the device is pulled out and looked at.
        ids.forEach { id ->
            val facing = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return ids.first()
    }

    private suspend fun openCamera(cm: CameraManager, id: String, h: Handler): CameraDevice? {
        val d = CompletableDeferred<CameraDevice?>()
        try {
            @Suppress("MissingPermission")
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { d.complete(c) }
                override fun onDisconnected(c: CameraDevice) { c.close(); d.complete(null) }
                override fun onError(c: CameraDevice, e: Int) { c.close(); d.complete(null) }
            }, h)
        } catch (t: Throwable) { return null }
        return d.await()
    }

    @Suppress("DEPRECATION")
    private suspend fun createSession(
        dev: CameraDevice, reader: ImageReader, h: Handler
    ): CameraCaptureSession? {
        val d = CompletableDeferred<CameraCaptureSession?>()
        try {
            dev.createCaptureSession(listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { d.complete(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) { d.complete(null) }
                }, h)
        } catch (t: Throwable) { return null }
        return d.await()
    }
}
