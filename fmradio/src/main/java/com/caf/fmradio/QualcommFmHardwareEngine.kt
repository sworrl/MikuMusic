package com.caf.fmradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import qcom.fmradio.FmConfig
import qcom.fmradio.FmReceiver
import qcom.fmradio.FmRxEvCallbacksAdaptor

/**
 * Region band plans.
 *
 * The chip is told the band through [FmConfig]; the limits and step are also what the UI's
 * tuning strip and the manual step buttons use, so they have to agree with what the chip was
 * given or the strip would point at frequencies seek can never land on.
 */
enum class FmBandPlan(
    val label: String,
    val configBand: Int,
    val lowKHz: Int,
    val highKHz: Int,
    val stepKHz: Int,
    val chSpacing: Int,
    val emphasisUs: Int,
    val emphasis: Int,
    val rdsStd: Int,
) {
    US("US / Canada", FmConfig.FM_US_BAND, 87500, 108000, 200,
        FmConfig.FM_CHSPACE_200_KHZ, 75, FmConfig.FM_DE_EMP75, FmConfig.FM_RDS_STD_RBDS),
    EUROPE("Europe", FmConfig.FM_EU_BAND, 87500, 108000, 100,
        FmConfig.FM_CHSPACE_100_KHZ, 50, FmConfig.FM_DE_EMP50, FmConfig.FM_RDS_STD_RDS),
    JAPAN("Japan", FmConfig.FM_JAPAN_STANDARD_BAND, 76000, 90000, 100,
        FmConfig.FM_CHSPACE_100_KHZ, 50, FmConfig.FM_DE_EMP50, FmConfig.FM_RDS_STD_RDS),
    JAPAN_WIDE("Japan wide", FmConfig.FM_JAPAN_WIDE_BAND, 76000, 108000, 100,
        FmConfig.FM_CHSPACE_100_KHZ, 50, FmConfig.FM_DE_EMP50, FmConfig.FM_RDS_STD_RDS),
}

/** One station found by a band sweep, with the signal that was measured when it was found. */
data class FmScanHit(val freqKHz: Int, val rssi: Int?, val snr: Int?)

/**
 * What the HAL and driver actually report back, as opposed to what we asked for. Every field
 * here is a read, never an echo of a write; anything unreadable stays null and the UI says
 * "unknown" rather than inventing a value.
 */
data class FmDiagnostics(
    val jniLoaded: Boolean = false,
    val socName: String? = null,
    val fmState: Int? = null,
    val routeCode: Int = FmAudioRoute.DEV_SPEAKER,
    val routeLabel: String = FmAudioRoute.describe(FmAudioRoute.DEV_SPEAKER),
    val handleFmWritten: Int? = null,
    /** Read back from the HAL's `fm_status`; null when the HAL answered nothing. */
    val halLoopback: Boolean? = null,
    val fmVolumeLinear: Float? = null,
    val slimbusStatus: Int? = null,
    val driverMuted: Boolean? = null,
    val driverFreqKHz: Int? = null,
)

/**
 * HiBy M500 FM tuner engine.
 *
 * HARDWARE. Not a discrete tuner: this is the Qualcomm WCN SoC's FM core, reached over the
 * shared Bluetooth HCI transport (`fm_hci` / `radio_helium` in logcat, `getSocName()` =
 * "cherokee") and exposed as a V4L2 node at /dev/radio0. The framework side is the device's
 * `qcom.fmradio.jar` plus `libqcomfm_jni.so`, the same stack stock FM2 uses. Only the package
 * `com.caf.fmradio` (platform-signed, SELinux domain vendor_fm_app) may open the tuner.
 *
 * AUDIO. Two things have to happen and the engine used to do neither. The HAL has to be told to
 * start its FM session with the output device bitmask in `handle_fm` (see [FmAudioRoute]), and
 * the driver's own V4L2 mute has to be cleared (see [FmV4L2]). On top of that, HiBy's build
 * carries the PCM to the app as a RADIO_TUNER capture stream which is bridged into an
 * AudioTrack, exactly as stock does. Until the HAL session is running that capture stream
 * delivers digital silence, which is what "the tuner works but there is no sound" looked like.
 *
 * Nothing here simulates. If the chip cannot be enabled, or a reading cannot be taken, the state
 * says so.
 */
class QualcommFmHardwareEngine(private val context: Context) {
    companion object {
        private const val TAG = "QualcommFmEngine"
        private const val FM_DEVICE_PATH = "/dev/radio0"
        private const val AUDIO_SOURCE_RADIO_TUNER = 1998 // MediaRecorder.AudioSource.RADIO_TUNER
        const val ACTION_DEBUG = "com.caf.fmradio.action.DEBUG"
        const val SPECTRUM_BINS = 48
        private const val SPECTRUM_FRAMES = 1024
        private const val SPECTRUM_LOW_HZ = 60.0
        private const val SPECTRUM_HIGH_HZ = 15000.0

        /** Stock FM2 waits this long after building the route before clearing the driver mute. */
        private const val UNMUTE_SETTLE_MS = 300L

        /** Seek sensitivity, CAF's FM_RX_SIGNAL_STRENGTH_* scale. */
        val SENSITIVITY_LABELS = listOf("Weakest", "Weak", "Strong", "Strongest")
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())

    /**
     * Every qcom.fmradio call runs on this Looper thread: FmReceiver's constructor builds a
     * PhoneStateListener (needs a Looper) and the library posts to Handlers internally, so a
     * plain IO thread NPEs in Handler.<init> (seen on-device 2026-08-25).
     */
    private val tunerThread = android.os.HandlerThread("MikuFmTuner").apply { start() }
    private val tunerDispatcher = Handler(tunerThread.looper).asCoroutineDispatcher("MikuFmTuner")

    private var audioTrackHelper: AudioTrackHelper? = null
    private var audioRecord: AudioRecord? = null
    private var audioRecordThread: Thread? = null
    @Volatile private var isAudioRecordRunning: Boolean = false
    private val wav = FmWavWriter(48000, 2)

    /**
     * Whether the captured PCM is the audio path or only something we are looking at.
     *
     * On every output except Bluetooth the HAL's hardware loopback carries FM to the DAC on its
     * own, and stock FM2's updateFmOutput() explicitly STOPS its AudioRecord bridge on those
     * routes. Writing a second, buffer-delayed copy through an AudioTrack on top of the
     * loopback would not be louder, it would be an echo. A2DP is the exception: the ADSP cannot
     * loop FM into a Bluetooth encoder, so there the bridge IS the audio path.
     */
    @Volatile private var bridgeToTrack: Boolean = false

    /** True while the tuner screen is actually in front of the user. */
    @Volatile private var uiVisible: Boolean = false
    private var pollJob: Job? = null
    private var scanJob: Job? = null

    private var receiver: FmReceiver? = null
    @Volatile private var enableDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var disableDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var slimbusDeferred: CompletableDeferred<Int>? = null
    @Volatile private var seekDeferred: CompletableDeferred<Int>? = null

    /**
     * True for the whole duration of a band sweep. Each individual seek inside it completes and
     * clears the per-seek search state, which would otherwise flash the scanning overlay off
     * and on at every station found.
     */
    @Volatile private var sweeping: Boolean = false

    /** The device code the HAL session was started with, so teardown can clear the same one. */
    @Volatile private var activeRouteCode: Int? = null
    @Volatile private var lastVolumeIndex: Int = -1

    private val prefs = context.getSharedPreferences("miku_fm", Context.MODE_PRIVATE)

    val band = MutableStateFlow(
        runCatching { FmBandPlan.valueOf(prefs.getString("band", FmBandPlan.US.name)!!) }
            .getOrDefault(FmBandPlan.US)
    )

    val isPoweredOn = MutableStateFlow(false)
    /** True only once the chip acknowledged enable (FmRxEvEnableReceiver). */
    val hardwareOnline = MutableStateFlow(false)
    val hardwareError = MutableStateFlow<String?>(null)
    /** Last station the user tuned (persisted); the chip is tuned here on power-on. */
    val currentFrequencyKHz = MutableStateFlow(
        prefs.getInt("last_freq", 101100).coerceIn(band.value.lowKHz, band.value.highKHz)
    )
    /** null until the chip reports FmRxEvStereoStatus — never assumed "stereo". */
    val isStereo = MutableStateFlow<Boolean?>(null)
    /** What the user asked for, which is not the same thing as what the chip achieved. */
    val stereoRequested = MutableStateFlow(prefs.getBoolean("stereo", true))
    val isMuted = MutableStateFlow(false)
    /** null until a reading comes back — never a placeholder number. */
    val rssi = MutableStateFlow<Int?>(null)
    val signal = MutableStateFlow(FmV4L2.Reading.EMPTY)
    /** RDS programme-service name; blank when no RDS has been decoded. */
    val stationName = MutableStateFlow("")
    val radioText = MutableStateFlow("Tuner off")
    /** RDS programme type code, null until an RDS group carrying one is decoded. */
    val programmeType = MutableStateFlow<Int?>(null)
    val programmeId = MutableStateFlow<Int?>(null)
    val rdsAvailable = MutableStateFlow<Boolean?>(null)
    val isScanning = MutableStateFlow(false)
    val scanProgress = MutableStateFlow(0f)
    val scanResults = MutableStateFlow<List<FmScanHit>>(emptyList())
    /** User presets, persisted; empty until the user stars a station. */
    val presets = MutableStateFlow(loadPresets())
    val diagnostics = MutableStateFlow(FmDiagnostics())

    val afJumpEnabled = MutableStateFlow(prefs.getBoolean("af_jump", false))
    val softMuteEnabled = MutableStateFlow(prefs.getBoolean("soft_mute", true))
    val seekSensitivity = MutableStateFlow(prefs.getInt("sensitivity", 1).coerceIn(0, 3))

    /** Current STREAM_MUSIC index and its maximum, so the UI can own the volume slider. */
    val volumeIndex = MutableStateFlow(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    val volumeMax = MutableStateFlow(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

    /**
     * Live audio spectrum of the FM PCM flowing through the bridge: [SPECTRUM_BINS] log-spaced
     * bins 60 Hz–15 kHz, each 0..1 (−60 dBFS..0 dBFS). Empty when no audio is flowing. Computed
     * from the real AudioRecord buffer — this is what the UI waterfall draws, and it is also the
     * honest answer to "is there actually audio": a flat plot means silence, not a dead widget.
     */
    val spectrum = MutableStateFlow(FloatArray(0))
    /** RMS level (0..1) of the same PCM; 0 when nothing flows. */
    val audioLevel = MutableStateFlow(0f)

    // ------------------------------------------------------------------ presets

    private fun loadPresets(): List<Int> =
        prefs.getString("presets", null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.distinct()?.sorted() ?: emptyList()

    fun savePresets(list: List<Int>) {
        val clean = list.distinct().sorted()
        presets.value = clean
        prefs.edit().putString("presets", clean.joinToString(",")).apply()
    }

    fun togglePreset(freqKHz: Int) {
        val cur = presets.value.toMutableList()
        if (!cur.remove(freqKHz)) cur.add(freqKHz)
        savePresets(cur)
    }

    // ------------------------------------------------------------------ chip callbacks

    private val callbacks = object : FmRxEvCallbacksAdaptor() {
        override fun FmRxEvEnableReceiver() {
            Log.i(TAG, "cb: EnableReceiver")
            enableDeferred?.complete(true)
        }
        override fun FmRxEvDisableReceiver() {
            Log.i(TAG, "cb: DisableReceiver")
            disableDeferred?.complete(true)
        }
        override fun FmRxEvRadioReset() {
            Log.w(TAG, "cb: RadioReset (chip reset)")
            hardwareOnline.value = false
            hardwareError.value = "Tuner reset by driver"
        }
        override fun FmRxEvRadioTuneStatus(freq: Int) {
            Log.i(TAG, "cb: TuneStatus freq=$freq")
            if (freq in band.value.lowKHz..band.value.highKHz) {
                currentFrequencyKHz.value = freq
                onNewStation()
            }
            refreshSignal()
        }
        override fun FmRxEvSearchInProgress() { isScanning.value = true }
        override fun FmRxEvSearchCancelled() {
            if (!sweeping) isScanning.value = false
            seekDeferred?.complete(-1)
        }
        override fun FmRxEvSearchComplete(freq: Int) {
            Log.i(TAG, "cb: SearchComplete freq=$freq")
            if (!sweeping) isScanning.value = false
            if (freq in band.value.lowKHz..band.value.highKHz) {
                currentFrequencyKHz.value = freq
                onNewStation()
                // The search moved the HCI side; the V4L2 side is what the audio path follows,
                // so it has to be told where we landed.
                FmV4L2.setTunedKHz(freq)
                prefs.edit().putInt("last_freq", freq).apply()
            }
            refreshSignal()
            seekDeferred?.complete(freq)
        }
        override fun FmRxEvSearchListComplete() {
            Log.i(TAG, "cb: SearchListComplete")
        }
        override fun FmRxEvStereoStatus(stereo: Boolean) { isStereo.value = stereo }
        override fun FmRxEvRdsLockStatus(rdsAvail: Boolean) {
            rdsAvailable.value = rdsAvail
            if (!rdsAvail) radioText.value = "No RDS on ${mhz(currentFrequencyKHz.value)}"
        }
        override fun FmRxEvRdsPsInfo() {
            val ps = runCatching { receiver?.psInfo }.getOrNull() ?: return
            runCatching { ps.prgmServices }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { stationName.value = it }
            runCatching { ps.prgmType }.getOrNull()?.let { if (it > 0) programmeType.value = it }
            runCatching { ps.prgmId }.getOrNull()?.let { if (it != 0) programmeId.value = it }
        }
        override fun FmRxEvRdsRtInfo() {
            val rt = runCatching { receiver?.rtInfo?.radioText }.getOrNull()?.trim()
            if (!rt.isNullOrEmpty()) radioText.value = rt
        }
        override fun FmRxEvEnableSlimbus(status: Int) {
            Log.i(TAG, "cb: EnableSlimbus status=$status")
            diagnostics.value = diagnostics.value.copy(slimbusStatus = status)
            slimbusDeferred?.complete(status)
        }
        override fun FmRxEvEnableSoftMute(status: Int) {
            Log.i(TAG, "cb: EnableSoftMute status=$status")
        }
    }

    /** Everything that is only true of the station we just left. */
    private fun onNewStation() {
        stationName.value = ""
        isStereo.value = null
        programmeType.value = null
        programmeId.value = null
        rdsAvailable.value = null
    }

    init {
        loadJni()
        registerRouteWatcher()
        registerDebugReceiver()
    }

    /**
     * HiBy's qcom.fmradio.jar does NOT load its own JNI library — the app must (stock FM2's
     * FMAdapterApp logs "Loading FM-JNI Library"). libqcomfm_jni.so is bundled in this APK
     * (copied from /system_ext/app/FM2/lib/arm64) and registers qcom/fmradio/FmReceiverJNI in
     * JNI_OnLoad. Its deps (libandroid_runtime, libcutils, libbtconfigstore) are only reachable
     * from the SHARED linker namespace, i.e. when this app is BUNDLED in the system image — an
     * adb-installed update in /data gets an isolated namespace and this load fails (surfaced
     * to the UI as a real error, never faked).
     */
    @Volatile private var jniLoaded = false
    private fun loadJni() {
        try {
            System.loadLibrary("qcomfm_jni")
            jniLoaded = true
            Log.i(TAG, "FM JNI library loaded")
        } catch (t: Throwable) {
            Log.e(TAG, "FM JNI library NOT loadable (needs bundled/system-image install): $t")
            hardwareError.value = "FM driver library not loadable: ${t.message}"
        }
        diagnostics.value = diagnostics.value.copy(jniLoaded = jniLoaded)
    }

    private fun mhz(khz: Int) = String.format(java.util.Locale.US, "%.1f MHz", khz / 1000.0)

    // ------------------------------------------------------------------ audio focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "audio focus lost permanently, powering the tuner down")
                powerOff()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!isMuted.value) { duckedByFocus = true; applyMute(true) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (duckedByFocus) { duckedByFocus = false; applyMute(false) }
            }
        }
    }
    @Volatile private var duckedByFocus = false
    private var focusRequest: AudioFocusRequest? = null

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focusRequest = req
        val res = runCatching { audioManager.requestAudioFocus(req) }.getOrNull()
        val granted = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "audio focus ${if (granted) "granted" else "NOT granted (res=$res)"}")
        return granted
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
        duckedByFocus = false
    }

    // ------------------------------------------------------------------ power

    fun powerOn() {
        if (isPoweredOn.value) return
        isPoweredOn.value = true
        hardwareError.value = null
        scope.launch(tunerDispatcher) {
            try {
                // 0. Take focus first, the way stock does: starting the HAL's FM session under
                //    another app's active stream is how you get two things fighting for the DAC.
                requestFocus()

                if (!jniLoaded) loadJni()
                if (!jniLoaded) throw UnsatisfiedLinkError("libqcomfm_jni.so not loadable from this install location")

                val rx = receiver ?: FmReceiver(FM_DEVICE_PATH, callbacks).also { receiver = it }
                val soc = runCatching { rx.socName }.getOrNull()
                Log.i(TAG, "FmReceiver created; soc=$soc smd=${runCatching { rx.isSmdTransportLayer }.getOrNull()} " +
                    "state=${runCatching { rx.fmState }.getOrNull()}")
                diagnostics.value = diagnostics.value.copy(socName = soc)

                // 1. Mute at the driver before anything routes, so the HAL bringing its backend
                //    up does not pop into the user's ears. Stock does exactly this.
                FmV4L2.setMute(true)

                // 2. SLIMbus carries the FM core's audio to the codec. Stock enables it BEFORE
                //    enable() and waits for the firmware's acknowledgement; without it the HAL
                //    has a session with nothing arriving on it.
                enableSlimbus(true)

                // 3. Open the tuner.
                val plan = band.value
                val cfg = FmConfig().apply {
                    radioBand = plan.configBand
                    emphasis = plan.emphasis
                    chSpacing = plan.chSpacing
                    rdsStd = plan.rdsStd
                    lowerLimit = plan.lowKHz
                    upperLimit = plan.highKHz
                }
                val deferred = CompletableDeferred<Boolean>().also { enableDeferred = it }
                val ok = rx.enable(cfg, context)
                Log.i(TAG, "FmReceiver.enable(config, ctx) -> $ok  [${plan.label}]")
                if (!ok) throw IllegalStateException("FmReceiver.enable returned false")
                val acked = withTimeoutOrNull(5000) { deferred.await() } ?: false
                Log.i(TAG, "enable acked=$acked fmState=${runCatching { rx.fmState }.getOrNull()}")

                // 4. Tuner setup.
                rx.setMuteMode(FmReceiver.FM_RX_UNMUTE)
                rx.setStereoMode(stereoRequested.value)
                runCatching { rx.EnableSoftMute(if (softMuteEnabled.value) 1 else 0) }
                runCatching { rx.enableAFjump(afJumpEnabled.value) }
                runCatching { rx.setSignalThreshold(seekSensitivity.value) }
                rx.registerRdsGroupProcessing(
                    FmReceiver.FM_RX_RDS_GRP_RT_EBL or FmReceiver.FM_RX_RDS_GRP_PS_EBL or
                        FmReceiver.FM_RX_RDS_GRP_AF_EBL or FmReceiver.FM_RX_RDS_GRP_PS_SIMPLE_EBL or
                        FmReceiver.FM_RX_RDS_GRP_PTYN_EBL or FmReceiver.FM_RX_RDS_GRP_RT_PLUS_EBL
                )
                val freq = currentFrequencyKHz.value.coerceIn(plan.lowKHz, plan.highKHz)
                currentFrequencyKHz.value = freq
                val v4l2Tuned = FmV4L2.setTunedKHz(freq)
                val tuned = rx.setStation(freq)
                Log.i(TAG, "tune($freq) v4l2=$v4l2Tuned setStation=$tuned; " +
                    "driverFreq=${FmV4L2.tunedKHz()} tunedFreq=${runCatching { rx.tunedFrequency }.getOrNull()}")

                hardwareOnline.value = true
                radioText.value = "Live tuner · ${mhz(freq)}"

                // 5. Start the HAL's FM session. This is the step that was missing: without the
                //    AUDIO_DEVICE_OUT_FM bit in handle_fm the HAL treats the call as a stop.
                startHalAudio()

                // 6. SLIMbus again now that the HAL has a session, which is the second place
                //    stock enables it (requestFocusImpl and onRebind both do startFM then
                //    enableSlimbus(1)).
                enableSlimbus(true)

                // 7. Open the capture if anything wants it: on A2DP it IS the audio path, and
                //    otherwise it only feeds the spectrum and the recorder.
                reconcileCapture()

                // 8. Let the route settle, then lift the driver mute.
                main.postDelayed({
                    if (isPoweredOn.value && !isMuted.value) {
                        FmV4L2.setMute(false)
                        diagnostics.value = diagnostics.value.copy(driverMuted = FmV4L2.isMuted())
                    }
                }, UNMUTE_SETTLE_MS)

                startPolling()
                Log.i(TAG, "FM tuner ON at $freq kHz")
            } catch (t: Throwable) {
                Log.e(TAG, "FM power-on FAILED", t)
                hardwareOnline.value = false
                hardwareError.value = t.toString()
                stationName.value = ""
                rssi.value = null
                isStereo.value = null
                radioText.value = when (t) {
                    is UnsatisfiedLinkError, is NoClassDefFoundError, is ExceptionInInitializerError ->
                        "FM driver library not loadable in this install (needs the system image build)"
                    else -> "Tuner error: ${t.message ?: t.javaClass.simpleName}"
                }
                stopAudioBridge()
                stopHalAudio()
                abandonFocus()
                isPoweredOn.value = false
            }
        }
    }

    fun powerOff() {
        if (!isPoweredOn.value) return
        isPoweredOn.value = false
        sweeping = false
        scanJob?.cancel(); scanJob = null
        isScanning.value = false
        scope.launch(tunerDispatcher) {
            try {
                FmV4L2.setMute(true)
                pollJob?.cancel(); pollJob = null
                wav.stop()
                stopAudioBridge()
                stopHalAudio()
                val rx = receiver
                if (rx != null && hardwareOnline.value) {
                    enableSlimbus(false)
                    val d = CompletableDeferred<Boolean>().also { disableDeferred = it }
                    val ok = runCatching { rx.disable(context) }.getOrElse { false }
                    Log.i(TAG, "FmReceiver.disable(ctx) -> $ok")
                    withTimeoutOrNull(3000) { d.await() }
                }
                hardwareOnline.value = false
                abandonFocus()
                radioText.value = "Tuner off"
                rssi.value = null
                signal.value = FmV4L2.Reading.EMPTY
                isStereo.value = null
                rdsAvailable.value = null
                Log.i(TAG, "FM tuner OFF")
            } catch (t: Throwable) {
                Log.e(TAG, "Error powering off FM", t)
            }
        }
    }

    fun togglePower() { if (isPoweredOn.value) powerOff() else powerOn() }

    /**
     * The firmware answers EnableSlimbus asynchronously; stock blocks on that answer before it
     * goes on to enable(). We wait too, but with a ceiling, because a missing acknowledgement
     * should not wedge the power-on path forever.
     */
    private suspend fun enableSlimbus(enable: Boolean) {
        val rx = receiver ?: return
        val d = CompletableDeferred<Int>().also { slimbusDeferred = it }
        runCatching { rx.EnableSlimbus(if (enable) 1 else 0) }
            .onFailure { Log.w(TAG, "EnableSlimbus(${if (enable) 1 else 0}) threw: $it"); return }
        val status = withTimeoutOrNull(2000) { d.await() }
        Log.i(TAG, "EnableSlimbus(${if (enable) 1 else 0}) ack=$status")
        slimbusDeferred = null
    }

    // ------------------------------------------------------------------ HAL audio session

    private fun startHalAudio() {
        val route = FmAudioRoute.current(audioManager)
        val written = FmAudioRoute.start(audioManager, route.code)
        activeRouteCode = route.code
        FmAudioRoute.setMuted(audioManager, isMuted.value)
        val gain = FmAudioRoute.applyVolume(audioManager, route.code)
        lastVolumeIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        diagnostics.value = diagnostics.value.copy(
            routeCode = route.code,
            routeLabel = route.label,
            handleFmWritten = written,
            halLoopback = FmAudioRoute.loopbackActive(audioManager),
            fmVolumeLinear = gain,
        )
    }

    private fun stopHalAudio() {
        val code = activeRouteCode ?: return
        runCatching { FmAudioRoute.stop(audioManager, code) }
        activeRouteCode = null
        diagnostics.value = diagnostics.value.copy(
            handleFmWritten = null,
            halLoopback = FmAudioRoute.loopbackActive(audioManager),
        )
    }

    /** Output changed under a running session: move it rather than restarting the tuner. */
    private fun onRouteChanged() {
        if (!isPoweredOn.value || activeRouteCode == null) return
        val route = FmAudioRoute.current(audioManager)
        if (route.code == activeRouteCode) return
        val written = FmAudioRoute.reroute(audioManager, route.code)
        activeRouteCode = route.code
        val gain = FmAudioRoute.applyVolume(audioManager, route.code)
        diagnostics.value = diagnostics.value.copy(
            routeCode = route.code,
            routeLabel = route.label,
            handleFmWritten = written,
            halLoopback = FmAudioRoute.loopbackActive(audioManager),
            fmVolumeLinear = gain,
        )
        // Moving to or from Bluetooth changes whether the capture is the audio path.
        reconcileCapture()
    }

    private fun registerRouteWatcher() {
        runCatching {
            audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = onRouteChanged()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = onRouteChanged()
            }, main)
        }.onFailure { Log.w(TAG, "route watcher not registered: $it") }

        runCatching {
            val rx = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val state = intent?.getIntExtra("state", -1) ?: -1
                    Log.d(TAG, "headset plug state=$state (the cable is the FM antenna)")
                    onRouteChanged()
                }
            }
            context.registerReceiver(rx, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        }.onFailure { Log.w(TAG, "headset receiver not registered: $it") }
    }

    // ------------------------------------------------------------------ volume

    fun setVolumeIndex(index: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = index.coerceIn(0, max)
        // setStreamVolume rather than adjustStreamVolume: HiBy's framework gates the ADJUST path
        // behind a per-jack raise lock, which silently swallows increases (see the volume-knob
        // work in the player). The absolute setter is not gated.
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0) }
        volumeIndex.value = clamped
        activeRouteCode?.let { code ->
            val gain = FmAudioRoute.applyVolume(audioManager, code)
            diagnostics.value = diagnostics.value.copy(fmVolumeLinear = gain)
        }
        lastVolumeIndex = clamped
    }

    private fun followSystemVolume() {
        val idx = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeIndex.value = idx
        volumeMax.value = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (idx != lastVolumeIndex) {
            lastVolumeIndex = idx
            activeRouteCode?.let { code ->
                val gain = FmAudioRoute.applyVolume(audioManager, code)
                diagnostics.value = diagnostics.value.copy(fmVolumeLinear = gain)
            }
        }
    }

    // ------------------------------------------------------------------ tuning

    fun tune(freqKHz: Int) {
        val plan = band.value
        val clamped = freqKHz.coerceIn(plan.lowKHz, plan.highKHz)
        currentFrequencyKHz.value = clamped
        onNewStation()
        prefs.edit().putInt("last_freq", clamped).apply()
        scope.launch(tunerDispatcher) {
            try {
                val rx = receiver
                if (rx != null && hardwareOnline.value) {
                    // Both paths, deliberately. Stock takes the V4L2 one on this device and
                    // only falls back to setStation() on hardware without HiBy's hooks.
                    val v4l2 = FmV4L2.setTunedKHz(clamped)
                    val ok = rx.setStation(clamped)
                    Log.i(TAG, "tune($clamped) v4l2=$v4l2 setStation=$ok")
                    if (!ok && !v4l2) radioText.value = "Tune failed at ${mhz(clamped)}"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error tuning $clamped", t)
            }
        }
    }

    fun step(up: Boolean) {
        val plan = band.value
        var next = currentFrequencyKHz.value + if (up) plan.stepKHz else -plan.stepKHz
        if (next > plan.highKHz) next = plan.lowKHz
        if (next < plan.lowKHz) next = plan.highKHz
        tune(next)
    }

    fun seek(up: Boolean) {
        val rx = receiver
        if (rx == null || !hardwareOnline.value) { step(up); return }
        isScanning.value = true
        scope.launch(tunerDispatcher) {
            val found = seekOnce(rx, up)
            if (found == null) { isScanning.value = false; step(up) }
        }
    }

    /** One hardware seek, resolved when the chip reports where it landed. Null means it failed. */
    private suspend fun seekOnce(rx: FmReceiver, up: Boolean, timeoutMs: Long = 12_000): Int? {
        val d = CompletableDeferred<Int>().also { seekDeferred = it }
        val ok = runCatching {
            rx.searchStations(
                FmReceiver.FM_RX_SRCH_MODE_SEEK,
                FmReceiver.FM_RX_DWELL_PERIOD_1S,
                if (up) FmReceiver.FM_RX_SEARCHDIR_UP else FmReceiver.FM_RX_SEARCHDIR_DOWN
            )
        }.getOrElse { Log.e(TAG, "searchStations threw", it); false }
        if (!ok) { seekDeferred = null; return null }
        val freq = withTimeoutOrNull(timeoutMs) { d.await() }
        seekDeferred = null
        return freq?.takeIf { it > 0 }
    }

    fun cancelSeek() {
        sweeping = false
        scanJob?.cancel(); scanJob = null
        runCatching { receiver?.cancelSearch() }
        isScanning.value = false
        scanProgress.value = 0f
    }

    /**
     * Sweep the whole band and collect what is actually receivable.
     *
     * Implemented as repeated hardware seeks rather than `searchStationList`, because the jar
     * has no way to read a station list back out: `FmReceiverJNI.srchListCallback` parses one
     * internally and nothing public exposes the result. Seeking round the band and recording
     * where the chip stops uses only calls this device is known to answer, and it gives a real
     * signal reading per hit instead of a bare frequency.
     */
    fun scanBand() {
        val rx = receiver
        if (rx == null || !hardwareOnline.value || scanJob?.isActive == true) return
        scanJob = scope.launch(tunerDispatcher) {
            val plan = band.value
            val startFreq = currentFrequencyKHz.value
            val hits = LinkedHashMap<Int, FmScanHit>()
            scanResults.value = emptyList()
            scanProgress.value = 0f
            sweeping = true
            isScanning.value = true
            try {
                var guard = 0
                val maxStops = ((plan.highKHz - plan.lowKHz) / plan.stepKHz) + 1
                var wrappedPast = false
                var previous = startFreq
                while (isActive && guard++ < maxStops) {
                    val freq = seekOnce(rx, true) ?: break
                    // Seek wraps at the top of the band; stop once it comes back round.
                    if (freq < previous) {
                        if (wrappedPast) break
                        wrappedPast = true
                    }
                    previous = freq
                    if (hits.containsKey(freq)) break
                    delay(350)                       // let the chip settle before reading signal
                    val s = FmV4L2.signal()
                    hits[freq] = FmScanHit(freq, s?.rssi ?: runCatching { rx.rssi }.getOrNull(), s?.snr)
                    scanResults.value = hits.values.sortedBy { it.freqKHz }
                    scanProgress.value =
                        ((freq - plan.lowKHz).toFloat() / (plan.highKHz - plan.lowKHz)).coerceIn(0f, 1f)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "band scan ended early: $t")
            } finally {
                sweeping = false
                isScanning.value = false
                scanProgress.value = 0f
                scanResults.value = hits.values.sortedBy { it.freqKHz }
                Log.i(TAG, "band scan found ${hits.size} station(s)")
                if (hits.isNotEmpty()) tune(hits.keys.minByOrNull { kotlin.math.abs(it - startFreq) } ?: startFreq)
                else tune(startFreq)
            }
        }
    }

    // ------------------------------------------------------------------ mute / stereo / options

    private fun applyMute(muted: Boolean) {
        isMuted.value = muted
        audioTrackHelper?.setVolume(if (muted) 0f else 1f)
        FmV4L2.setMute(muted)
        runCatching { receiver?.setMuteMode(if (muted) FmReceiver.FM_RX_MUTE else FmReceiver.FM_RX_UNMUTE) }
        runCatching { FmAudioRoute.setMuted(audioManager, muted) }
        diagnostics.value = diagnostics.value.copy(driverMuted = FmV4L2.isMuted())
    }

    fun mute() = applyMute(true)
    fun unmute() = applyMute(false)
    fun toggleMute() = applyMute(!isMuted.value)

    fun setStereo(enabled: Boolean) {
        stereoRequested.value = enabled
        prefs.edit().putBoolean("stereo", enabled).apply()
        runCatching { receiver?.setStereoMode(enabled) }
    }

    fun toggleStereo() = setStereo(!stereoRequested.value)

    fun setAfJump(enabled: Boolean) {
        afJumpEnabled.value = enabled
        prefs.edit().putBoolean("af_jump", enabled).apply()
        scope.launch(tunerDispatcher) { runCatching { receiver?.enableAFjump(enabled) } }
    }

    fun setSoftMute(enabled: Boolean) {
        softMuteEnabled.value = enabled
        prefs.edit().putBoolean("soft_mute", enabled).apply()
        scope.launch(tunerDispatcher) { runCatching { receiver?.EnableSoftMute(if (enabled) 1 else 0) } }
    }

    fun setSeekSensitivity(level: Int) {
        val l = level.coerceIn(0, 3)
        seekSensitivity.value = l
        prefs.edit().putInt("sensitivity", l).apply()
        scope.launch(tunerDispatcher) { runCatching { receiver?.setSignalThreshold(l) } }
    }

    /**
     * Band plans are given to the chip in FmConfig at enable() time, so changing one on a live
     * tuner means a power cycle. Doing that silently is better than pretending the new plan took
     * effect while the chip is still on the old one.
     */
    fun setBand(plan: FmBandPlan) {
        if (plan == band.value) return
        band.value = plan
        prefs.edit().putString("band", plan.name).apply()
        currentFrequencyKHz.value = currentFrequencyKHz.value.coerceIn(plan.lowKHz, plan.highKHz)
        scanResults.value = emptyList()
        if (isPoweredOn.value) {
            scope.launch {
                powerOff()
                delay(600)
                powerOn()
            }
        }
    }

    // ------------------------------------------------------------------ polling

    private fun refreshSignal() {
        if (!hardwareOnline.value) return
        val s = FmV4L2.signal()
        if (s != null) {
            signal.value = s
            s.rssi?.let { rssi.value = it }
        } else {
            // No V4L2 hook on this build: fall back to the HCI read, which costs an HCI
            // round trip and is why it is not the default.
            runCatching { receiver?.rssi }.getOrNull()?.let { if (it >= 0) rssi.value = it }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && isPoweredOn.value) {
                refreshSignal()
                followSystemVolume()
                if (hardwareOnline.value) {
                    diagnostics.value = diagnostics.value.copy(
                        fmState = runCatching { receiver?.fmState }.getOrNull(),
                        halLoopback = FmAudioRoute.loopbackActive(audioManager),
                        driverMuted = FmV4L2.isMuted(),
                        driverFreqKHz = FmV4L2.tunedKHz(),
                    )
                }
                delay(1000)
            }
        }
    }

    // ------------------------------------------------------------------ audio bridge

    /**
     * Decide whether to hold the RADIO_TUNER capture open at all.
     *
     * Capture is cheap but it is not free, and on Android 14 it lights the microphone privacy
     * indicator — correctly, because the app really is recording. So it runs when it is the
     * audio path (A2DP), when the user is recording, and when the spectrum is on screen and
     * therefore being looked at. In the background on a wired route it does not run, and the
     * radio plays through the loopback with nothing captured.
     */
    private fun captureWanted(): Boolean =
        isPoweredOn.value && hardwareOnline.value &&
            (activeRouteCode == FmAudioRoute.DEV_A2DP || uiVisible || wav.isRecording())

    fun setUiVisible(visible: Boolean) {
        if (uiVisible == visible) return
        uiVisible = visible
        reconcileCapture()
    }

    private fun reconcileCapture() {
        if (captureWanted()) startAudioBridge() else stopAudioBridge()
    }

    private fun startAudioBridge() {
        bridgeToTrack = activeRouteCode == FmAudioRoute.DEV_A2DP
        if (isAudioRecordRunning) {
            // Already capturing; only the question of where it goes may have changed.
            if (bridgeToTrack && audioTrackHelper == null) {
                audioTrackHelper = AudioTrackHelper(48000).apply {
                    play(); setVolume(if (isMuted.value) 0.0f else 1.0f)
                }
            } else if (!bridgeToTrack) {
                audioTrackHelper?.release(); audioTrackHelper = null
            }
            return
        }
        isAudioRecordRunning = true
        if (bridgeToTrack) {
            audioTrackHelper = AudioTrackHelper(48000).apply {
                play()
                setVolume(if (isMuted.value) 0.0f else 1.0f)
            }
        }
        audioRecordThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sampleRate = 48000
            val channelIn = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding).coerceAtLeast(4096)
            val buffer = ByteArray(minBuf)
            try {
                @Suppress("MissingPermission")
                audioRecord = AudioRecord(AUDIO_SOURCE_RADIO_TUNER, sampleRate, channelIn, encoding, minBuf).apply {
                    if (state == AudioRecord.STATE_INITIALIZED) {
                        startRecording()
                        Log.d(TAG, "AudioRecord started on RADIO_TUNER source (48000Hz stereo)")
                    } else {
                        Log.w(TAG, "AudioRecord init returned state=$state")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed creating AudioRecord for FM", t)
            }
            while (isAudioRecordRunning && isPoweredOn.value) {
                val record = audioRecord
                if (record != null && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        if (bridgeToTrack) audioTrackHelper?.write(buffer, 0, read)
                        wav.write(buffer, 0, read)
                        updateSpectrum(buffer, read, sampleRate)
                    }
                } else {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                }
            }
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (_: Throwable) {}
            audioTrackHelper?.release()
            audioTrackHelper = null
            spectrum.value = FloatArray(0)
            audioLevel.value = 0f
            Log.d(TAG, "AudioRecord bridge stopped")
        }, "MikuFmAudioBridgeThread").apply { start() }
    }

    private fun stopAudioBridge() {
        if (!isAudioRecordRunning) return
        isAudioRecordRunning = false
        audioRecordThread?.interrupt()
        audioRecordThread = null
    }

    // ------------------------------------------------------------------ live spectrum (real PCM)

    private var lastSpectrumMs = 0L
    private val binFreqs = FloatArray(SPECTRUM_BINS) { i ->
        (SPECTRUM_LOW_HZ * Math.pow((SPECTRUM_HIGH_HZ / SPECTRUM_LOW_HZ), i.toDouble() / (SPECTRUM_BINS - 1))).toFloat()
    }
    private val monoScratch = FloatArray(SPECTRUM_FRAMES)

    /**
     * Goertzel magnitude at [SPECTRUM_BINS] log-spaced frequencies over the first
     * [SPECTRUM_FRAMES] frames of the 16-bit stereo buffer, ~12 updates/s. Cheap (48 × 1024 MACs)
     * and computed from the audio that is really being played — nothing synthetic.
     */
    private fun updateSpectrum(buf: ByteArray, bytes: Int, sampleRate: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSpectrumMs < 80) return
        val frames = minOf(bytes / 4, SPECTRUM_FRAMES)
        if (frames < 256) return
        lastSpectrumMs = now
        var sumSq = 0.0
        for (i in 0 until frames) {
            val l = ((buf[i * 4 + 1].toInt() shl 8) or (buf[i * 4].toInt() and 0xFF)).toShort().toFloat()
            val r = ((buf[i * 4 + 3].toInt() shl 8) or (buf[i * 4 + 2].toInt() and 0xFF)).toShort().toFloat()
            val m = (l + r) * 0.5f / 32768f
            monoScratch[i] = m; sumSq += (m * m).toDouble()
        }
        val out = FloatArray(SPECTRUM_BINS)
        for (b in 0 until SPECTRUM_BINS) {
            val w = 2.0 * Math.PI * binFreqs[b] / sampleRate
            val coeff = 2.0 * Math.cos(w)
            var s1 = 0.0; var s2 = 0.0
            for (i in 0 until frames) { val s0 = monoScratch[i] + coeff * s1 - s2; s2 = s1; s1 = s0 }
            val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
            val mag = Math.sqrt(maxOf(power, 0.0)) * 2.0 / frames        // ≈ amplitude, 0..1
            val db = 20.0 * Math.log10(maxOf(mag, 1e-6))
            out[b] = ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)     // −60 dBFS..0 dBFS → 0..1
        }
        spectrum.value = out
        audioLevel.value = Math.sqrt(sumSq / frames).toFloat().coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ recording

    /**
     * Real recording: tee the live FM PCM to a WAV file.
     *
     * This needs the capture open, which on a wired route it may not be, so recording turns it
     * on rather than failing. It still refuses when the tuner is not actually receiving: a
     * recording of a stream we know is silent is worse than none.
     */
    fun startRecording(file: java.io.File): Boolean {
        if (!isPoweredOn.value || !hardwareOnline.value) return false
        if (!wav.start(file)) return false
        reconcileCapture()
        return true
    }

    fun stopRecording(): java.io.File? {
        val f = wav.stop()
        reconcileCapture()
        return f
    }

    fun isRecording(): Boolean = wav.isRecording()
    fun recordedBytes(): Long = wav.bytesWritten()

    // ------------------------------------------------------------------ adb hook

    /**
     * adb test hook:
     *   am broadcast -a com.caf.fmradio.action.DEBUG --es cmd tune --ei freq 101100
     * cmd ∈ power_on | power_off | tune | seek_up | seek_down | scan | mute | unmute |
     *       vol (--ei index N) | status
     */
    private fun registerDebugReceiver() {
        try {
            val rx = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.getStringExtra("cmd")) {
                        "power_on" -> powerOn()
                        "power_off" -> powerOff()
                        "tune" -> tune(intent.getIntExtra("freq", currentFrequencyKHz.value))
                        "seek_up" -> seek(true)
                        "seek_down" -> seek(false)
                        "scan" -> scanBand()
                        "mute" -> mute()
                        "unmute" -> unmute()
                        "vol" -> setVolumeIndex(intent.getIntExtra("index", volumeIndex.value))
                        else -> logStatus()
                    }
                }
            }
            val f = IntentFilter(ACTION_DEBUG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(rx, f)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "debug receiver not registered: $t")
        }
    }

    private fun logStatus() {
        val d = diagnostics.value
        val s = signal.value
        Log.i(TAG, "STATUS on=${isPoweredOn.value} online=${hardwareOnline.value} " +
            "band=${band.value.label} freq=${currentFrequencyKHz.value} driverFreq=${d.driverFreqKHz} " +
            "muted=${isMuted.value} driverMuted=${d.driverMuted} " +
            "route=${d.routeLabel}(${d.routeCode}) handle_fm=${d.handleFmWritten} " +
            "halLoopback=${d.halLoopback} fmVolume=${d.fmVolumeLinear} slimbus=${d.slimbusStatus} " +
            "rssi=${s.rssi} snr=${s.snr} multipath=${s.multipath} valid=${s.valid} " +
            "audioLevel=${audioLevel.value} stereo=${isStereo.value} rds=${rdsAvailable.value} " +
            "name='${stationName.value}' rt='${radioText.value}' err=${hardwareError.value}")
    }
}
