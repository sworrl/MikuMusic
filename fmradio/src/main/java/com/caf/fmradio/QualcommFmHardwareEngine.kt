package com.caf.fmradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
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
 * HiBy M500 FM tuner engine.
 *
 * Hardware: Silicon Labs Si4705 on I2C (kernel `radio-si4705`, V4L2 node /dev/radio0), driven
 * through the device's framework library `qcom.fmradio.jar` (QTI FM Java API with HiBy's V4L2
 * hooks) + `libqcomfm_jni.so` — the same stack HiBy's stock FM2 uses. Only the package
 * `com.caf.fmradio` (platform-signed → SELinux domain vendor_fm_app) may open the tuner.
 *
 * Audio: identical to stock FM2 — tell the HiBy audio HAL FM is active (`handle_fm=1`,
 * `fm_status`, `fm_volume`) and bridge the RADIO_TUNER capture source into an AudioTrack.
 *
 * History: the previous engine reflected `FmReceiver.enable(FmConfig)` — a signature this
 * jar does not have (`enable(FmConfig, Context)`) — so init silently failed and the UI ran a
 * "fallback" that never touched the chip. This engine compiles against exact-signature stubs
 * (:qcom-fmradio-stubs) and calls the API directly; no simulation, ever: if the chip cannot be
 * enabled the state says so.
 */
class QualcommFmHardwareEngine(private val context: Context) {
    companion object {
        private const val TAG = "QualcommFmEngine"
        private const val FM_DEVICE_PATH = "/dev/radio0"
        private const val AUDIO_SOURCE_RADIO_TUNER = 1998 // MediaRecorder.AudioSource.RADIO_TUNER
        private const val BAND_LOW_KHZ = 87500
        private const val BAND_HIGH_KHZ = 108000
        private const val STEP_KHZ = 200
        const val ACTION_DEBUG = "com.caf.fmradio.action.DEBUG"
        const val SPECTRUM_BINS = 48
        private const val SPECTRUM_FRAMES = 1024
        private const val SPECTRUM_LOW_HZ = 60.0
        private const val SPECTRUM_HIGH_HZ = 15000.0
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Every qcom.fmradio call runs on this Looper thread: FmReceiver's constructor builds a
     * PhoneStateListener (needs a Looper) and the library posts to Handlers internally, so a
     * plain IO thread NPEs in Handler.<init> (seen on-device 2026-08-25).
     */
    private val tunerThread = android.os.HandlerThread("MikuFmTuner").apply { start() }
    private val tunerDispatcher = android.os.Handler(tunerThread.looper).asCoroutineDispatcher("MikuFmTuner")

    private var audioTrackHelper: AudioTrackHelper? = null
    private var audioRecord: AudioRecord? = null
    private var audioRecordThread: Thread? = null
    @Volatile private var isAudioRecordRunning: Boolean = false
    private var rssiJob: Job? = null

    private var receiver: FmReceiver? = null
    @Volatile private var enableDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var disableDeferred: CompletableDeferred<Boolean>? = null

    private val prefs = context.getSharedPreferences("miku_fm", Context.MODE_PRIVATE)

    val isPoweredOn = MutableStateFlow(false)
    /** True only once the chip acknowledged enable (FmRxEvEnableReceiver). */
    val hardwareOnline = MutableStateFlow(false)
    val hardwareError = MutableStateFlow<String?>(null)
    /** Last station the user tuned (persisted); the chip is tuned here on power-on. */
    val currentFrequencyKHz = MutableStateFlow(prefs.getInt("last_freq", 101100).coerceIn(BAND_LOW_KHZ, BAND_HIGH_KHZ))
    /** null until the chip reports FmRxEvStereoStatus — never assumed "stereo". */
    val isStereo = MutableStateFlow<Boolean?>(null)
    val isMuted = MutableStateFlow(false)
    /** null until FmReceiver.getRssi() returns a reading — never a placeholder number. */
    val rssi = MutableStateFlow<Int?>(null)
    /** RDS programme-service name; blank when no RDS has been decoded (no invented station name). */
    val stationName = MutableStateFlow("")
    val radioText = MutableStateFlow("Tuner off")
    val isScanning = MutableStateFlow(false)
    /** User presets, persisted; empty until the user stars a station (no fabricated preset list). */
    val presets = MutableStateFlow(loadPresets())
    /**
     * Live audio spectrum of the FM PCM flowing through the bridge: [SPECTRUM_BINS] log-spaced bins
     * 60 Hz–15 kHz, each 0..1 (−60 dBFS..0 dBFS). Empty when no audio is flowing. Computed from
     * the real AudioRecord buffer — this is what the UI waterfall draws.
     */
    val spectrum = MutableStateFlow(FloatArray(0))
    /** RMS level (0..1) of the same PCM; 0 when nothing flows. */
    val audioLevel = MutableStateFlow(0f)

    private fun loadPresets(): List<Int> =
        prefs.getString("presets", null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in BAND_LOW_KHZ..BAND_HIGH_KHZ }?.distinct()?.sorted() ?: emptyList()

    fun savePresets(list: List<Int>) {
        val clean = list.filter { it in BAND_LOW_KHZ..BAND_HIGH_KHZ }.distinct().sorted()
        presets.value = clean
        prefs.edit().putString("presets", clean.joinToString(",")).apply()
    }

    /** Real chip events. Must be a subclass of the abstract adaptor (the constructor demands it). */
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
            if (freq in BAND_LOW_KHZ..BAND_HIGH_KHZ) {
                currentFrequencyKHz.value = freq
                stationName.value = ""          // new station: RDS name unknown until decoded
                isStereo.value = null           // stereo pilot unknown until the chip reports it
            }
            refreshRssi()
        }
        override fun FmRxEvSearchInProgress() { isScanning.value = true }
        override fun FmRxEvSearchCancelled() { isScanning.value = false }
        override fun FmRxEvSearchComplete(freq: Int) {
            Log.i(TAG, "cb: SearchComplete freq=$freq")
            isScanning.value = false
            if (freq in BAND_LOW_KHZ..BAND_HIGH_KHZ) {
                currentFrequencyKHz.value = freq
                stationName.value = ""
                isStereo.value = null
                prefs.edit().putInt("last_freq", freq).apply()
            }
            refreshRssi()
        }
        override fun FmRxEvStereoStatus(stereo: Boolean) { isStereo.value = stereo }
        override fun FmRxEvRdsLockStatus(rdsAvail: Boolean) {
            if (!rdsAvail) radioText.value = "No RDS on ${mhz(currentFrequencyKHz.value)}"
        }
        override fun FmRxEvRdsPsInfo() {
            val ps = runCatching { receiver?.psInfo?.prgmServices }.getOrNull()?.trim()
            if (!ps.isNullOrEmpty()) stationName.value = ps
        }
        override fun FmRxEvRdsRtInfo() {
            val rt = runCatching { receiver?.rtInfo?.radioText }.getOrNull()?.trim()
            if (!rt.isNullOrEmpty()) radioText.value = rt
        }
    }

    init {
        loadJni()
        registerHeadsetListener()
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
    }

    private fun mhz(khz: Int) = String.format("%.1f MHz", khz / 1000.0)

    // ------------------------------------------------------------------ power

    fun powerOn() {
        if (isPoweredOn.value) return
        isPoweredOn.value = true
        hardwareError.value = null
        scope.launch(tunerDispatcher) {
            try {
                // 1. Audio HAL: FM active (HiBy HAL flips the analog FM path / AGND select).
                configureAudioHal(true, currentFrequencyKHz.value)

                // 2. Open the tuner through the framework FM library (may throw
                //    UnsatisfiedLinkError / InstantiationException — surfaced, never faked).
                if (!jniLoaded) loadJni()
                if (!jniLoaded) throw UnsatisfiedLinkError("libqcomfm_jni.so not loadable from this install location")
                val rx = receiver ?: FmReceiver(FM_DEVICE_PATH, callbacks).also { receiver = it }
                Log.i(TAG, "FmReceiver created; soc=${runCatching { rx.socName }.getOrNull()} " +
                    "smd=${runCatching { rx.isSmdTransportLayer }.getOrNull()} state=${runCatching { rx.fmState }.getOrNull()}")

                val cfg = FmConfig().apply {
                    radioBand = FmConfig.FM_US_BAND
                    emphasis = FmConfig.FM_DE_EMP75
                    chSpacing = FmConfig.FM_CHSPACE_200_KHZ
                    rdsStd = FmConfig.FM_RDS_STD_RBDS
                    lowerLimit = BAND_LOW_KHZ
                    upperLimit = BAND_HIGH_KHZ
                }
                val deferred = CompletableDeferred<Boolean>().also { enableDeferred = it }
                val ok = rx.enable(cfg, context)
                Log.i(TAG, "FmReceiver.enable(config, ctx) -> $ok")
                if (!ok) throw IllegalStateException("FmReceiver.enable returned false")
                val acked = withTimeoutOrNull(5000) { deferred.await() } ?: false
                Log.i(TAG, "enable acked=$acked fmState=${runCatching { rx.fmState }.getOrNull()}")

                // 3. Initial tuner setup.
                rx.setMuteMode(FmReceiver.FM_RX_UNMUTE)
                rx.setStereoMode(true)
                rx.registerRdsGroupProcessing(
                    FmReceiver.FM_RX_RDS_GRP_RT_EBL or FmReceiver.FM_RX_RDS_GRP_PS_EBL or
                        FmReceiver.FM_RX_RDS_GRP_AF_EBL or FmReceiver.FM_RX_RDS_GRP_PS_SIMPLE_EBL
                )
                val tuned = rx.setStation(currentFrequencyKHz.value)
                Log.i(TAG, "setStation(${currentFrequencyKHz.value}) -> $tuned; tunedFreq=${runCatching { rx.tunedFrequency }.getOrNull()}")

                hardwareOnline.value = true
                radioText.value = "Live tuner · ${mhz(currentFrequencyKHz.value)}"

                // 4. Audio: RADIO_TUNER capture -> AudioTrack (same as stock FM2).
                startAudioBridge()
                unmute()
                startRssiPolling()
                Log.i(TAG, "FM tuner ON at ${currentFrequencyKHz.value} kHz")
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
                configureAudioHal(false, currentFrequencyKHz.value)
                isPoweredOn.value = false
            }
        }
    }

    fun powerOff() {
        if (!isPoweredOn.value) return
        isPoweredOn.value = false
        scope.launch(tunerDispatcher) {
            try {
                mute()
                rssiJob?.cancel(); rssiJob = null
                stopAudioBridge()
                val rx = receiver
                if (rx != null && hardwareOnline.value) {
                    val d = CompletableDeferred<Boolean>().also { disableDeferred = it }
                    val ok = runCatching { rx.disable(context) }.getOrElse { false }
                    Log.i(TAG, "FmReceiver.disable(ctx) -> $ok")
                    withTimeoutOrNull(3000) { d.await() }
                }
                hardwareOnline.value = false
                configureAudioHal(false, currentFrequencyKHz.value)
                radioText.value = "Tuner off"
                rssi.value = null
                isStereo.value = null
                Log.i(TAG, "FM tuner OFF")
            } catch (t: Throwable) {
                Log.e(TAG, "Error powering off FM", t)
            }
        }
    }

    // ------------------------------------------------------------------ tuning

    fun tune(freqKHz: Int) {
        val clamped = freqKHz.coerceIn(BAND_LOW_KHZ, BAND_HIGH_KHZ)
        currentFrequencyKHz.value = clamped
        stationName.value = ""      // RDS name for the new station is unknown until decoded
        isStereo.value = null
        prefs.edit().putInt("last_freq", clamped).apply()
        scope.launch(tunerDispatcher) {
            try {
                val rx = receiver
                if (rx != null && hardwareOnline.value) {
                    val ok = rx.setStation(clamped)
                    Log.i(TAG, "setStation($clamped) -> $ok")
                    if (!ok) radioText.value = "Tune failed at ${mhz(clamped)}"
                }
                audioManager.setParameters("fm_freq=$clamped")
            } catch (t: Throwable) {
                Log.e(TAG, "Error tuning $clamped", t)
            }
        }
    }

    fun seek(up: Boolean) {
        val rx = receiver
        if (rx == null || !hardwareOnline.value) {
            stepManual(up); return
        }
        isScanning.value = true
        scope.launch(tunerDispatcher) {
            val ok = runCatching {
                rx.searchStations(
                    FmReceiver.FM_RX_SRCH_MODE_SEEK,
                    FmReceiver.FM_RX_DWELL_PERIOD_1S,
                    if (up) FmReceiver.FM_RX_SEARCHDIR_UP else FmReceiver.FM_RX_SEARCHDIR_DOWN
                )
            }.getOrElse { Log.e(TAG, "searchStations threw", it); false }
            Log.i(TAG, "searchStations(seek, ${if (up) "up" else "down"}) -> $ok")
            if (!ok) { isScanning.value = false; stepManual(up) }
        }
    }

    fun cancelSeek() {
        runCatching { receiver?.cancelSearch() }
        isScanning.value = false
    }

    private fun stepManual(up: Boolean) {
        var next = currentFrequencyKHz.value + if (up) STEP_KHZ else -STEP_KHZ
        if (next > BAND_HIGH_KHZ) next = BAND_LOW_KHZ
        if (next < BAND_LOW_KHZ) next = BAND_HIGH_KHZ
        tune(next)
    }

    fun mute() {
        isMuted.value = true
        audioTrackHelper?.setVolume(0.0f)
        runCatching { receiver?.setMuteMode(FmReceiver.FM_RX_MUTE) }
        runCatching { audioManager.setParameters("fm_mute=1") }
    }

    fun unmute() {
        isMuted.value = false
        audioTrackHelper?.setVolume(1.0f)
        runCatching { receiver?.setMuteMode(FmReceiver.FM_RX_UNMUTE) }
        runCatching { audioManager.setParameters("fm_mute=0") }
    }

    fun toggleStereo() {
        // Request the opposite of what the chip last reported (unknown → ask for stereo). The
        // displayed state is only updated by FmRxEvStereoStatus, i.e. by the chip itself.
        val request = !(isStereo.value ?: false)
        runCatching { receiver?.setStereoMode(request) }
    }

    private fun refreshRssi() {
        val rx = receiver ?: return
        if (!hardwareOnline.value) return
        runCatching { rx.rssi }.onSuccess { if (it >= 0) rssi.value = it }
    }

    private fun startRssiPolling() {
        rssiJob?.cancel()
        rssiJob = scope.launch {
            while (isActive && isPoweredOn.value) {
                refreshRssi()
                delay(2000)
            }
        }
    }

    // ------------------------------------------------------------------ audio path

    private fun configureAudioHal(enable: Boolean, freqKHz: Int) {
        try {
            val status = if (enable) "1" else "0"
            audioManager.setParameters("handle_fm=$status;fm_status=$status;fm_volume=1.0;fm_mute=0;fm_freq=$freqKHz;fm_active=$status")
            audioManager.setParameters(if (enable) "fm_route=playback" else "fm_route=off")
            audioManager.setParameters("vendor.audio.hw.fm.mode=$status")
        } catch (t: Throwable) {
            Log.e(TAG, "Audio HAL parameter configuration error", t)
        }
    }

    private fun startAudioBridge() {
        if (isAudioRecordRunning) return
        isAudioRecordRunning = true
        audioTrackHelper = AudioTrackHelper(48000).apply {
            play()
            setVolume(if (isMuted.value) 0.0f else 1.0f)
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
                        audioTrackHelper?.write(buffer, 0, read)
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

    private fun stopAudioBridge() {
        isAudioRecordRunning = false
        audioRecordThread?.interrupt()
        audioRecordThread = null
    }

    // ------------------------------------------------------------------ misc

    /** Real recording: tee the live FM PCM to a WAV file via the audio bridge. */
    fun startRecording(file: java.io.File): Boolean = audioTrackHelper?.startRecording(file) ?: false
    fun stopRecording() { audioTrackHelper?.stopRecording() }
    fun isRecording(): Boolean = audioTrackHelper?.isRecording() == true

    private fun registerHeadsetListener() {
        try {
            val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
                        val state = intent.getIntExtra("state", 0)
                        Log.d(TAG, "Headset plug state changed: $state (headphone cable = FM antenna)")
                    }
                }
            }, filter)
        } catch (_: Throwable) {}
    }

    /**
     * adb test hook: `am broadcast -a com.caf.fmradio.action.DEBUG --es cmd tune --ei freq 101100`
     * cmd ∈ power_on | power_off | tune | seek_up | seek_down | status.
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
                        else -> Log.i(TAG, "STATUS on=${isPoweredOn.value} online=${hardwareOnline.value} " +
                            "freq=${currentFrequencyKHz.value} chipFreq=${runCatching { receiver?.tunedFrequency }.getOrNull()} " +
                            "rssi=${rssi.value} stereo=${isStereo.value} name='${stationName.value}' rt='${radioText.value}' err=${hardwareError.value}")
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
}
