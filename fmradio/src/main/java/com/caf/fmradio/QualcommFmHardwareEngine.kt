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
import android.os.SystemClock
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
    /**
     * North America. Channels sit on ODD tenths: 87.9, 88.1 ... 107.9, 200 kHz apart.
     *
     * The BAND LIMITS here are what the chip is given in FmConfig, and they stay at the values
     * stock uses. The channel GRID is a separate thing, anchored at 87.9 by [gridBase]. I
     * briefly conflated the two and narrowed the band to 87.9-107.9 to fix the grid, which
     * changed what the tuner was configured with as a side effect of a UI bug fix. Two
     * different concerns; keep them apart.
     */
    US("US / Canada / Mexico", FmConfig.FM_US_BAND, 87500, 108000, 200,
        FmConfig.FM_CHSPACE_200_KHZ, 75, FmConfig.FM_DE_EMP75, FmConfig.FM_RDS_STD_RBDS),
    /** ITU Region 1. Every tenth is a channel, 100 kHz apart, 50 us de-emphasis. */
    EUROPE("Europe", FmConfig.FM_EU_BAND, 87500, 108000, 100,
        FmConfig.FM_CHSPACE_100_KHZ, 50, FmConfig.FM_DE_EMP50, FmConfig.FM_RDS_STD_RDS),
    JAPAN("Japan", FmConfig.FM_JAPAN_STANDARD_BAND, 76000, 95000, 100,
        FmConfig.FM_CHSPACE_100_KHZ, 50, FmConfig.FM_DE_EMP50, FmConfig.FM_RDS_STD_RDS),
    JAPAN_WIDE("Japan wide", FmConfig.FM_JAPAN_WIDE_BAND, 76000, 108000, 100,
        FmConfig.FM_CHSPACE_100_KHZ, 50, FmConfig.FM_DE_EMP50, FmConfig.FM_RDS_STD_RDS),
}

/**
 * How the tuning dial snaps.
 *
 * Worth being explicit about because the regions genuinely differ and a wrong grid makes real
 * stations unreachable rather than merely inconvenient. North America puts every FM channel on
 * an odd tenth 200 kHz apart; most of the rest of the world uses every tenth, 100 kHz apart.
 */
enum class FmChannelGrid(val label: String, val detail: String) {
    AUTO("Automatic", "Follow the selected region's channel plan"),
    ODD_TENTHS("Odd tenths", "87.9, 88.1, 88.3 … — North America"),
    EVEN_TENTHS("Even tenths", "88.0, 88.2, 88.4 … — rare, for odd allocations"),
    EVERY_TENTH("Every tenth", "87.5, 87.6, 87.7 … — Europe, Japan, 100 kHz"),
    FREE("Off", "Tune anywhere the chip will go, no snapping"),
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
    /**
     * The two FM paths read separately, because this board has two and they are not the same
     * part. [rssiSi4705] comes from FmReceiverJNI.getV4L2RadioFmSignal(), i.e. /dev/radio0,
     * which is the Si4705 at i2c-2 0x63. [rssiQualcomm] and [sinrQualcomm] come from
     * FmReceiver.getRssi()/getSINR(), which speak HCI to the Qualcomm WCN FM core.
     *
     * If only one of them moves as the frequency changes, only one of them has an antenna, and
     * that settles whether the second path is usable for anything.
     */
    val rssiSi4705: Int? = null,
    val rssiQualcomm: Int? = null,
    val sinrQualcomm: Int? = null,
)

/**
 * HiBy M500 FM tuner engine.
 *
 * HARDWARE. There are two FM-capable paths on this board and they are not the same chip.
 * `/dev/radio0` is bound to i2c-2 address 0x63, which is a Silicon Labs Si4705, and the kernel
 * logs `si4705_i2c_interrupt` continuously while the tuner runs. That is the part that actually
 * receives: its RSSI moves across the band. Separately the Qualcomm WCN FM stack is present and
 * answers (`fm_hci` / `radio_helium` in logcat, and `getSocName()` returns "cherokee", which is
 * the Bluetooth SoC name read out of bt_configstore). HiBy's V4L2 hooks on `FmReceiverJNI` drive
 * the Si4705; the `FmReceiver` HCI API drives the Qualcomm side. The framework glue for both is
 * the device's `qcom.fmradio.jar` plus `libqcomfm_jni.so`. Only the package `com.caf.fmradio`
 * (platform-signed, SELinux domain vendor_fm_app) may open the tuner.
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

        /** How many signal samples the time plot keeps. */
        const val SIGNAL_HISTORY = 64

        /**
         * Signal poll interval. Every I2C read on this tuner is a transaction on a chip that is
         * demodulating, so this is as slow as the meter can be while still feeling live in the
         * hand when aiming the antenna.
         */
        const val SIGNAL_POLL_MS = 750L

        /**
         * Settle time per channel during a band sweep. The Si4705 needs a moment after a tune
         * before its signal meter means anything; 60 ms across ~103 US channels is about six
         * seconds of silence, which is the honest cost of seeing the band on a part with no
         * spectrum analyser.
         */
        const val SWEEP_SETTLE_MS = 60L

        /** Sweeps kept for the waterfall underneath the live trace. */
        const val SWEEP_HISTORY = 24

        /** Observed RSSI ceiling on this tuner; only scales the plot, never the printed number. */
        const val SWEEP_RSSI_FULL_SCALE = 40f

        /** Si4705 RX volume is a 6-bit field, so full scale is 63. */
        private const val SI4705_MAX_VOLUME = 63

        /** Re-arm RDS (poll the node) after this long without a group. */
        private const val RDS_REARM_MS = 1000L
        /** Tuned this long without one clean group: report no RDS, while still listening. */
        private const val RDS_ABSENT_MS = 10_000L

        /**
         * The HAL gain stock FM2 sends on this hardware, captured from a live stock session
         * on 2026-10-09: `fm_volume=0.052481`, which is -25.6 dB.
         *
         * It is the only gain this device has ever been heard playing music at, so it is the
         * default rather than a round number off the taper. On the 48 dB taper used by
         * applyFmVolume(), level 47 gives 10^((47-100)*0.48/20) = 0.0537, within a quarter of
         * a dB of it; exact enough that the difference is inaudible and the arithmetic stays
         * legible in one expression.
         */
        private const val STOCK_FM_VOLUME = 0.052481f
        private const val STOCK_EQUIVALENT_LEVEL = 47

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

    /**
     * Stations the catalogue says are within reach of where the device currently is.
     *
     * This is the dynamic half of the quick selector: it follows you, and it is populated from
     * public record data rather than from anything heard, so it is useful before the antenna
     * has found a single thing. A station here is one whose transmitter is close enough and
     * strong enough to be plausible, NOT a promise that it will come in; terrain is not
     * modelled and this is a hollow.
     */
    val nearbyStations = MutableStateFlow<List<FmStationCatalogue.Station>>(emptyList())
    /** Null until something has placed the device. The UI says so rather than showing nothing. */
    val listenerPlace = MutableStateFlow<Pair<Double, Double>?>(null)
    val catalogueSize = MutableStateFlow(0)

    /** Terrain between here and whatever is tuned. Null until a profile has been obtained. */
    val tunedProfile = MutableStateFlow<FmFresnel.Profile?>(null)
    @Volatile private var profileJob: Job? = null

    /** The station the catalogue believes we are tuned to, for the big readout. */
    val tunedStation = MutableStateFlow<FmStationCatalogue.Station?>(null)

    /**
     * Refresh the local station list from wherever the device now is.
     *
     * Cheap and bounded: the query is index-bounded by a lat/lon box, and it only runs when the
     * position has actually moved more than a kilometre, so standing still costs nothing.
     */
    fun refreshNearby(force: Boolean = false) {
        scope.launch {
            val pos = FmStationCatalogue.listenerPosition(context)
            if (pos == null) {
                listenerPlace.value = null
                if (nearbyStations.value.isNotEmpty()) nearbyStations.value = emptyList()
                return@launch
            }
            val prev = listenerPlace.value
            val moved = prev == null ||
                Math.abs(prev.first - pos.first) > 0.009 || Math.abs(prev.second - pos.second) > 0.012
            if (!force && !moved) return@launch
            listenerPlace.value = pos
            catalogueSize.value = FmStationCatalogue.size()
            val svc = if (band.value == FmBandPlan.US) listOf("FM", "FX", "FL") else listOf("FM")
            val list = FmStationCatalogue.near(pos.first, pos.second, services = svc)
            nearbyStations.value = list
            Log.i(TAG, "catalogue: ${list.size} station(s) within reach of " +
                "%.4f,%.4f".format(pos.first, pos.second))
            updateTunedStation()
        }
    }

    private fun updateTunedStation() {
        val f = currentFrequencyKHz.value
        val st = nearbyStations.value
            .filter { Math.abs(it.khz - f) <= 60 }
            .maxByOrNull { it.score }
        val changed = st?.call != tunedStation.value?.call
        tunedStation.value = st
        if (changed) refreshTerrain(st)
    }

    /**
     * Work out whether the ground is in the way, for the station now tuned.
     *
     * One job at a time and cancelled on retune, because this can mean a network round trip
     * and spinning the dial should not queue sixty of them. The profile is cached on disk per
     * (rounded position, station), so coming back to a station is free.
     */
    private fun refreshTerrain(st: FmStationCatalogue.Station?) {
        profileJob?.cancel()
        if (st == null) { tunedProfile.value = null; return }
        val pos = FmStationCatalogue.listenerPosition(context)
        if (pos == null) { tunedProfile.value = null; return }
        profileJob = scope.launch {
            val p = runCatching { FmFresnel.profile(context, pos.first, pos.second, st) }
                .onFailure { Log.w(TAG, "terrain profile failed: $it") }
                .getOrNull()
            if (isActive) tunedProfile.value = p
        }
    }

    /** How the dial snaps. AUTO follows the band plan, which is the right answer almost always. */
    val channelGrid = MutableStateFlow(
        runCatching { FmChannelGrid.valueOf(prefs.getString("grid", FmChannelGrid.AUTO.name)!!) }
            .getOrDefault(FmChannelGrid.AUTO)
    )

    val afJumpEnabled = MutableStateFlow(prefs.getBoolean("af_jump", false))
    val softMuteEnabled = MutableStateFlow(prefs.getBoolean("soft_mute", true))
    val seekSensitivity = MutableStateFlow(prefs.getInt("sensitivity", 1).coerceIn(0, 3))

    /**
     * The tuner's own volume level, 0..100, persisted and deliberately NOT the media stream.
     * Defaults high: a radio you have just switched on should be audible.
     */
    /**
     * The tuner's own level, 0..100. Default [STOCK_EQUIVALENT_LEVEL], not something round.
     *
     * 80 was the default until 2026-10-09 and it was a guess off the taper curve. It put
     * fm_volume at 0.331 against the 0.0525 that stock FM2 sends and that this device was
     * measured audible at: 16 dB hotter, with the tuner chip driven to full scale at the same
     * time, which clips a marginal signal into something that sounds like louder static. The
     * default is now the measured number, and the volume keys go up from there.
     */
    val fmVolumeLevel = MutableStateFlow(anchoredStartingLevel())

    /**
     * The starting level, correcting the bad default exactly once.
     *
     * A new default is not enough on a device that has already run the old build: 80 is sitting
     * in prefs and would survive the fix. So the first time this runs after the change it
     * overwrites the stored level and records that it has done so. A level the user sets
     * afterwards is theirs and is never touched again.
     */
    private fun anchoredStartingLevel(): Int {
        if (!prefs.getBoolean("fm_volume_anchored", false)) {
            val had = prefs.getInt("fm_volume", -1)
            prefs.edit()
                .putInt("fm_volume", STOCK_EQUIVALENT_LEVEL)
                .putBoolean("fm_volume_anchored", true)
                .apply()
            if (had >= 0) {
                Log.i(TAG, "tuner level reset $had -> $STOCK_EQUIVALENT_LEVEL " +
                    "(the old default drove the HAL 16 dB past the gain this device was " +
                    "measured audible at)")
            }
            return STOCK_EQUIVALENT_LEVEL
        }
        return prefs.getInt("fm_volume", STOCK_EQUIVALENT_LEVEL).coerceIn(0, 100)
    }

    /**
     * Live audio spectrum of the FM PCM flowing through the bridge: [SPECTRUM_BINS] log-spaced
     * bins 60 Hz–15 kHz, each 0..1 (−60 dBFS..0 dBFS). Empty when no audio is flowing. Computed
     * from the real AudioRecord buffer — this is what the UI waterfall draws, and it is also the
     * honest answer to "is there actually audio": a flat plot means silence, not a dead widget.
     */
    val spectrum = MutableStateFlow(FloatArray(0))
    /** RMS level (0..1) of the same PCM; 0 when nothing flows. */
    val audioLevel = MutableStateFlow(0f)
    /**
     * True when the capture turned out to be the vendor's six-byte framing rather than the
     * 16-bit PCM it advertises. Surfaced so the diagnostics panel can say which one it got
     * instead of leaving the question open.
     */
    val captureFraming = MutableStateFlow<Boolean?>(null)
    /** Highest frequency the spectrum can actually represent, given the decoded rate. */
    val spectrumTopHz = MutableStateFlow(SPECTRUM_HIGH_HZ.toInt())

    /**
     * Rolling history of the real signal, newest first, each entry 0..1.
     *
     * This is what the waterfall draws on a wired route now. The audio spectrum it used to draw
     * was an artifact of a capture stream that is not the tuner audio on this device, and it
     * looked identical at the strongest and the deadest frequency in the band. RSSI does not:
     * it reads 19 at 88.3 and 2 at 107.9. So the panel plots something that actually moves when
     * the radio does.
     */
    val signalHistory = MutableStateFlow(FloatArray(0))
    private val histBuf = ArrayDeque<Float>()

    /**
     * RSSI across the whole band, one entry per tunable channel, newest sweep.
     *
     * This is what makes the panel a band display rather than a time plot. The Si4705 has no
     * spectrum analyser, so the only way to see the band is to tune across it and read the
     * signal meter at each channel. That costs audio: the chip can only be on one frequency at
     * a time, so a sweep interrupts whatever you were listening to for its duration. The engine
     * mutes for the sweep and returns to the station it started on.
     */
    val bandProfile = MutableStateFlow(FloatArray(0))

    /** Previous sweeps, newest first, for the waterfall underneath the live trace. */
    val bandHistory = MutableStateFlow<List<FloatArray>>(emptyList())
    private val sweepHistory = ArrayDeque<FloatArray>()
    /** When each sweep in [bandHistory] finished, same order, so the waterfall can be dated. */
    private val sweepTimes = ArrayDeque<Long>()
    val bandHistoryTimes = MutableStateFlow<List<Long>>(emptyList())

    /** Raw RSSI per channel from the newest sweep, for the station list and tooltips. */
    val bandRssi = MutableStateFlow<IntArray>(IntArray(0))

    /** Rate the decoded capture really runs at, so a recording is not written at the wrong speed. */
    @Volatile private var capturedRateHz: Int = 48000

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
        // The next two come from the Qualcomm WCN core, which has no antenna on this board, so
        // they always report mono and no RDS whatever the Si4705 is receiving. They were the
        // source of a permanent MONO pill and "No RDS on this station". Stereo now comes from
        // the Si4705's own pilot report (refreshSignal) and RDS from FmRdsReader.
        override fun FmRxEvStereoStatus(stereo: Boolean) {
            Log.d(TAG, "cb: StereoStatus $stereo (WCN core, ignored)")
        }
        override fun FmRxEvRdsLockStatus(rdsAvail: Boolean) {
            Log.d(TAG, "cb: RdsLockStatus $rdsAvail (WCN core, ignored)")
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
        updateTunedStation()
        FmRdsReader.newStation()
        rdsTunedAt = SystemClock.elapsedRealtime()
        rdsAvailable.value = null
        rdsArtist.value = null
        rdsTitle.value = null
        rdsGroups.value = 0
        stationName.value = ""
        isStereo.value = null
        programmeType.value = null
        programmeId.value = null
        rdsAvailable.value = null
    }

    init {
        loadJni()
        FmStationCatalogue.prepare(context)
        registerRouteWatcher()
        registerDebugReceiver()
        refreshNearby(force = true)
        watchPosition()
    }

    /**
     * Re-read the local station list when the device's position changes.
     *
     * This matters more than it sounds. refreshNearby() used to run once, from init, and the
     * position is published by a separate daemon that needs a Wi-Fi fix - which lands seconds to
     * minutes AFTER this app has started. So the one call almost always found nothing, set the
     * list empty, and never looked again: the quick selector stayed blank forever on a device
     * that had a full catalogue and a known position sitting right there.
     *
     * A content observer on the published keys is the cheap fix. It costs nothing while the
     * device stands still, because the daemon only writes when it has a better fix, and
     * refreshNearby() itself ignores a change of less than about a kilometre.
     *
     * Not unregistered: this engine is a process-lifetime singleton, so there is no later point
     * at which it would be correct to stop caring where we are.
     */
    private fun watchPosition() {
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) = refreshNearby()
        }
        runCatching {
            for (key in listOf("miku_loc_lat", "miku_loc_lon")) {
                context.contentResolver.registerContentObserver(
                    android.provider.Settings.Global.getUriFor(key), false, observer)
            }
            Log.i(TAG, "watching the published position for catalogue refreshes")
        }.onFailure { Log.w(TAG, "could not watch the position: $it") }
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

                var rx = receiver ?: FmReceiver(FM_DEVICE_PATH, callbacks).also { receiver = it }
                // If a previous session left the chip's state machine somewhere enable() will
                // not accept, start over with a new one instead of failing the power-on.
                val priorState = runCatching { rx.fmState }.getOrNull()
                if (priorState != null && priorState != 0) {
                    Log.w(TAG, "receiver came back in state $priorState, rebuilding it")
                    runCatching { rx.disable(context) }
                    rx = FmReceiver(FM_DEVICE_PATH, callbacks).also { receiver = it }
                }
                // Is HiBy's own driver control surface reachable from this domain? Logged once
                // per power-on so a session log answers it without opening the UI.
                FmDriverSysfs.logProbe()
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

                // 3. Start the HAL's FM session BEFORE enabling the chip, which is the order
                //    stock uses: fmTurnOnSequence() calls startFM() and only then enable().
                //    The ADSP loopback latches onto the FM backend when it is opened, so
                //    opening it after the source is already running is not the same thing.
                startHalAudio()

                // 4. Open the tuner.
                val plan = band.value
                val cfg = FmConfig().apply {
                    radioBand = plan.configBand
                    emphasis = plan.emphasis
                    chSpacing = plan.chSpacing
                    rdsStd = plan.rdsStd
                    lowerLimit = plan.lowKHz
                    upperLimit = plan.highKHz
                }
                enableDeferred = CompletableDeferred()
                var ok = rx.enable(cfg, context)
                Log.i(TAG, "FmReceiver.enable(config, ctx) -> $ok  [${plan.label}]")
                if (!ok) {
                    // One retry on a brand-new receiver. This is the failure that made the
                    // tuner unrestartable after a power cycle, and it clears on a fresh object.
                    Log.w(TAG, "enable refused; retrying with a fresh receiver")
                    runCatching { rx.disable(context) }
                    rx = FmReceiver(FM_DEVICE_PATH, callbacks).also { receiver = it }
                    enableDeferred = CompletableDeferred()
                    ok = rx.enable(cfg, context)
                    Log.i(TAG, "FmReceiver.enable retry -> $ok")
                }
                if (!ok) throw IllegalStateException("FmReceiver.enable returned false twice")
                val acked = withTimeoutOrNull(5000) { enableDeferred!!.await() } ?: false
                Log.i(TAG, "enable acked=$acked fmState=${runCatching { rx.fmState }.getOrNull()}")

                // 5. Tuner setup, in stock's order.
                runCatching { rx.setRawRdsGrpMask() }
                // Normal power mode. Stock calls setLowPowerMode(false) here and we never did;
                // the chip does not necessarily come up in it, and on QTI low power mode is
                // what switches RDS reception off.
                runCatching { rx.setPowerMode(FmReceiver.FM_RX_NORMAL_POWER_MODE) }
                    .onSuccess { Log.i(TAG, "setPowerMode(normal) -> $it") }
                    .onFailure { Log.w(TAG, "setPowerMode threw: $it") }
                rx.setMuteMode(FmReceiver.FM_RX_UNMUTE)
                rx.setStereoMode(stereoRequested.value)
                runCatching { rx.EnableSoftMute(if (softMuteEnabled.value) 1 else 0) }
                runCatching { rx.enableAFjump(afJumpEnabled.value) }
                runCatching { rx.setSignalThreshold(seekSensitivity.value) }
                // THE DIGITAL OUTPUT FORMAT. This is the one that makes the difference
                // between music and noise, and it was missing.
                //
                // The Si4705 driver's start sequence writes DIGITAL_OUTPUT_FORMAT = 0x0006,
                // which by AN332 is I2S, OMONO=1, OSIZE=24-bit. Nothing else writes it. The
                // only thing that changes it is VIDIOC_S_TUNER, which the driver turns into
                // 0x0000 for stereo or 0x0004 for mono - both 16-bit.
                //
                // PAL consumes this backend as 48000 Hz, 16-bit, 2 channel
                // (resourcemanager_bengal_idp.xml, PAL_DEVICE_IN_FM_TUNER on
                // MI2S-LPAIF_WSA-TX-PRIMARY). So with the format left at its start value the
                // chip emits 24-bit mono into something reading 16-bit stereo: every sample
                // misaligned, which is not quiet audio, it is white noise. The tuner locks,
                // RSSI moves with the antenna, the HAL reports a healthy loopback, and what
                // comes out is static - which is exactly the symptom we have been chasing.
                //
                // Stock calls setV4L2RadioChannelMode immediately after enable and re-issues
                // the frequency straight afterwards, so do both.
                val modeOk = FmV4L2.setChannelMode(if (stereoRequested.value) 1 else 0)
                Log.i(TAG, "setV4L2RadioChannelMode(${if (stereoRequested.value) 1 else 0}) " +
                    "accepted=$modeOk readback=${FmV4L2.channelMode()} " +
                    "(0x0000=16-bit stereo, 0x0004=16-bit mono, 0x0006=24-bit mono = static)")

                // The part's own output volume is deliberately NOT set here. It used to be
                // written as SI4705_MAX_VOLUME=63, which looked like "turn it up" and is the
                // opposite: the driver registers V4L2_CID_AUDIO_VOLUME with a range of 0..15
                // against a chip whose RX_VOLUME default is 63, so the v4l2 core clamps the
                // write to 15 and ATTENUATES the tuner by roughly 12 dB. Stock never touches
                // this control at all. The sweep knob on the debug receiver still can.
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

                startRdsReader()
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
                stopRdsReader()
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
                // Drop the receiver rather than reuse it. FmTransceiver refuses enable() unless
                // its state machine is back at Turned_Off, and after a disable it does not
                // always get there: the second power-on then failed with
                // "FmReceiver.enable returned false" and the tuner could not be restarted
                // without force-stopping the app. A fresh object costs one constructor.
                runCatching { receiver = null }
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
        val written = FmAudioRoute.start(audioManager, route.code, currentFmGain())
        activeRouteCode = route.code
        FmAudioRoute.setMuted(audioManager, isMuted.value)
        diagnostics.value = diagnostics.value.copy(
            routeCode = route.code,
            routeLabel = route.label,
            handleFmWritten = written,
            halLoopback = FmAudioRoute.loopbackActive(audioManager),
        )
        applyFmVolume()
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
        // Re-assert the tuner's own level on the new backend. This used to re-read
        // STREAM_MUSIC, so plugging headphones in could silently re-gain the radio from
        // whatever the music volume happened to be.
        val gain = currentFmGain()
        FmAudioRoute.setGain(audioManager, gain)
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

    // The old setVolumeIndex lived here and drove STREAM_MUSIC. Removed: the tuner must not
    // move the system media volume, and must not read it either. See setFmVolumeLevel.

    /**
     * The tuner's own volume, 0..100, independent of STREAM_MUSIC.
     *
     * It used to follow the media stream, and that was wrong in a way that is obvious in
     * hindsight: turning the music down made the radio inaudible, and there is no reason the
     * two should share a level. Worse, it failed silently — the media stream sat at 21 of 100,
     * the radio was 12 dB quieter than when it had last worked, and nothing on screen said so.
     *
     * Now it is its own persisted value with its own taper, and nothing the rest of the system
     * does to its volume moves it.
     */
    fun setFmVolumeLevel(level: Int) {
        val l = level.coerceIn(0, 100)
        fmVolumeLevel.value = l
        prefs.edit().putInt("fm_volume", l).apply()
        applyFmVolume()
    }

    fun nudgeFmVolume(up: Boolean) = setFmVolumeLevel(fmVolumeLevel.value + if (up) 5 else -5)

    /** Which receiver-panel style the band display draws in. Persisted; purely cosmetic. */
    val sdrSkin = MutableStateFlow(
        runCatching { SdrSkin.valueOf(prefs.getString("sdr_skin", null) ?: "") }
            .getOrDefault(SdrSkin.CLASSIC))

    fun setSdrSkin(s: SdrSkin) {
        sdrSkin.value = s
        prefs.edit().putString("sdr_skin", s.name).apply()
    }

    fun cycleSdrSkin() {
        val all = SdrSkin.entries
        setSdrSkin(all[(all.indexOf(sdrSkin.value) + 1) % all.size])
    }

    /** The linear HAL gain for the current level. 48 dB taper: full scale at 100, silent at 0. */
    private fun currentFmGain(): Float {
        val l = fmVolumeLevel.value
        return if (l <= 0) 0f else Math.pow(10.0, ((l - 100) * 48.0 / 100.0) / 20.0).toFloat()
    }

    private fun applyFmVolume() {
        if (activeRouteCode == null) return
        val gain = currentFmGain()
        FmAudioRoute.setGain(audioManager, gain)
        diagnostics.value = diagnostics.value.copy(fmVolumeLinear = gain)
        Log.i(TAG, "fm volume level ${fmVolumeLevel.value} -> fm_volume $gain")
    }

    // ------------------------------------------------------------------ tuning

    fun tune(freqKHz: Int) {
        val plan = band.value
        val clamped = snapToGrid(freqKHz).coerceIn(plan.lowKHz, plan.highKHz)
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
        val cur = snapToGrid(currentFrequencyKHz.value)
        var next = cur + if (up) gridStep() else -gridStep()
        if (next > plan.highKHz) next = plan.lowKHz
        if (next < plan.lowKHz) next = plan.highKHz
        tune(next)
    }

    /**
     * Snap a frequency onto the channel grid.
     *
     * The grid is anchored to the band's LOW LIMIT, not to zero. Rounding to a multiple of the
     * step from zero is what made 100.1 untunable: with 200 kHz spacing the only reachable
     * frequencies were even tenths, and in North America every station is on an odd one.
     */
    fun snapToGrid(freqKHz: Int): Int {
        val plan = band.value
        val st = gridStep()
        if (st <= 0) return freqKHz.coerceIn(plan.lowKHz, plan.highKHz)   // free tuning
        val base = gridBase()
        val n = Math.round((freqKHz - base).toDouble() / st).toInt()
        return (base + n * st).coerceIn(plan.lowKHz, plan.highKHz)
    }

    /** Effective step, after the user's grid preference. */
    fun gridStep(): Int = when (channelGrid.value) {
        FmChannelGrid.AUTO -> band.value.stepKHz
        FmChannelGrid.ODD_TENTHS, FmChannelGrid.EVEN_TENTHS -> 200
        FmChannelGrid.EVERY_TENTH -> 100
        FmChannelGrid.FREE -> 0
    }

    /** Where the grid starts, which is what decides odd versus even tenths. */
    private fun gridBase(): Int = when (channelGrid.value) {
        // Anchor on the first real channel of the plan, not on the band's lower edge. For
        // North America that is 87.9 (channel 200); the band itself starts at 87.5, and
        // anchoring there would put the whole grid on even tenths where no stations exist.
        FmChannelGrid.AUTO -> if (band.value == FmBandPlan.US) 87900 else band.value.lowKHz
        // 87.9 is odd-tenth, 88.0 is even-tenth; everything else follows from the 200 kHz step.
        FmChannelGrid.ODD_TENTHS -> 87900
        FmChannelGrid.EVEN_TENTHS -> 88000
        FmChannelGrid.EVERY_TENTH -> 87500
        FmChannelGrid.FREE -> band.value.lowKHz
    }

    fun setChannelGrid(g: FmChannelGrid) {
        channelGrid.value = g
        prefs.edit().putString("grid", g.name).apply()
        // Re-snap where we are, so the setting takes effect visibly rather than at the next tune.
        if (g != FmChannelGrid.FREE) tune(snapToGrid(currentFrequencyKHz.value))
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
    /**
     * Sweep the band, measuring RSSI at every channel, and collect the ones the chip calls real.
     *
     * This replaced a scan built out of repeated hardware seeks. Seeks only report where they
     * stopped, which gives a list of frequencies and nothing to draw; and on this device the
     * HCI seek drives the Qualcomm core, which has no antenna, so it found nothing. Tuning the
     * Si4705 across the band and reading its own signal meter gives both the station list and a
     * picture of the band, from the part that is actually receiving.
     *
     * A station is one the chip validates: `valid=1`, or SNR above zero. Those are the Si4705's
     * own thresholds rather than a number picked here, which is why a sweep over an unconnected
     * antenna correctly returns nothing instead of a list of noise peaks.
     */
    fun scanBand() {
        val rx = receiver
        if (rx == null || !hardwareOnline.value || scanJob?.isActive == true) return
        scanJob = scope.launch(tunerDispatcher) {
            val plan = band.value
            val startFreq = currentFrequencyKHz.value
            val channels = ((plan.highKHz - plan.lowKHz) / plan.stepKHz) + 1
            val profile = FloatArray(channels)
            val raw = IntArray(channels)
            val hits = ArrayList<FmScanHit>()
            val wasMuted = isMuted.value
            sweeping = true
            isScanning.value = true
            scanResults.value = emptyList()
            scanProgress.value = 0f
            try {
                // The chip clicks its way across the band; muting keeps that out of your ears.
                if (!wasMuted) FmV4L2.setMute(true)
                for (i in 0 until channels) {
                    if (!isActive || !isPoweredOn.value) break
                    val f = plan.lowKHz + i * plan.stepKHz
                    FmV4L2.setTunedKHz(f)
                    delay(SWEEP_SETTLE_MS)
                    val sig = FmV4L2.signal()
                    val rssi = sig?.rssi ?: 0
                    raw[i] = rssi
                    profile[i] = (rssi / SWEEP_RSSI_FULL_SCALE).coerceIn(0f, 1f)
                    if (sig?.valid == true || (sig?.snr ?: 0) > 0) {
                        hits += FmScanHit(f, rssi, sig?.snr)
                    }
                    if (i % 4 == 0) {
                        bandProfile.value = profile.copyOf()
                        bandRssi.value = raw.copyOf()
                        scanResults.value = hits.toList()
                    }
                    scanProgress.value = (i + 1).toFloat() / channels
                }
            } catch (t: Throwable) {
                Log.w(TAG, "band sweep ended early: $t")
            } finally {
                bandProfile.value = profile
                bandRssi.value = raw
                scanResults.value = hits.toList()
                sweepHistory.addFirst(profile)
                sweepTimes.addFirst(System.currentTimeMillis())
                while (sweepHistory.size > SWEEP_HISTORY) sweepHistory.removeLast()
                while (sweepTimes.size > SWEEP_HISTORY) sweepTimes.removeLast()
                bandHistory.value = sweepHistory.toList()
                bandHistoryTimes.value = sweepTimes.toList()
                sweeping = false
                isScanning.value = false
                scanProgress.value = 0f
                // Back where we started, and unmuted only if it was unmuted before.
                FmV4L2.setTunedKHz(startFreq)
                currentFrequencyKHz.value = startFreq
                runCatching { rx.setStation(startFreq) }
                if (!wasMuted) FmV4L2.setMute(false)
                Log.i(TAG, "band sweep: $channels channels, ${hits.size} validated station(s)")
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
        // setStereoMode is the WCN core's blend setting and does not touch the part that
        // carries our audio. The Si4705's own stereo/mono IS its digital output format, so it
        // has to be set through V4L2 as well or this switch changes nothing audible.
        runCatching { receiver?.setStereoMode(enabled) }
        if (isPoweredOn.value) {
            scope.launch(tunerDispatcher) {
                val ok = FmV4L2.setChannelMode(if (enabled) 1 else 0)
                // Stock always re-issues the frequency straight after a mode change; the
                // driver rewrites DIGITAL_OUTPUT_FORMAT inside VIDIOC_S_TUNER and the chip
                // wants retuning behind it.
                FmV4L2.setTunedKHz(currentFrequencyKHz.value)
                Log.i(TAG, "stereo=$enabled -> channel mode accepted=$ok " +
                    "readback=${FmV4L2.channelMode()}")
            }
        }
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

    /**
     * One I2C read per tick, and nothing else.
     *
     * This used to do six blocking calls a second: three V4L2 reads on the Si4705, an HCI RSSI
     * and SINR round trip to the Qualcomm core, and an AudioManager.getParameters binder call
     * into the audio HAL. That cost real CPU on three threads and, worse, it degraded
     * reception: hammering a Si47xx's status registers over I2C while it is demodulating is a
     * documented way to put artefacts in the audio, and the tuner audibly got dirtier.
     *
     * So: the signal read is the only thing on the hot path. The Qualcomm core is read ONCE at
     * power-on, because it has no antenna and polling a deaf chip every second was pure waste
     * that happened to share a mutex with the FM HCI transport. Mute state, driver frequency
     * and the HAL loopback flag moved to [refreshDiagnostics], which runs only when someone is
     * looking at the diagnostics sheet.
     */
    private fun refreshSignal() {
        if (!hardwareOnline.value) return
        val s = FmV4L2.signal() ?: return
        signal.value = s
        s.rssi?.let { rssi.value = it }
        s.stereo?.let { isStereo.value = it }
        diagnostics.value = diagnostics.value.copy(rssiSi4705 = s.rssi)
        val now = (s.rssi ?: 0).let { (it / SWEEP_RSSI_FULL_SCALE).coerceIn(0f, 1f) }
        histBuf.addFirst(now)
        while (histBuf.size > SIGNAL_HISTORY) histBuf.removeLast()
        signalHistory.value = histBuf.toFloatArray()
    }

    /** The Qualcomm side, read once. It is deaf here; the value is for the comparison, not a poll. */
    private fun readQualcommOnce() {
        val hciRssi = runCatching { receiver?.rssi }.getOrNull()?.takeIf { it >= 0 }
        val hciSinr = runCatching { receiver?.getSINR() }.getOrNull()
        diagnostics.value = diagnostics.value.copy(rssiQualcomm = hciRssi, sinrQualcomm = hciSinr)
    }

    /** Everything expensive, on demand only: the UI calls this when the diagnostics sheet opens. */
    fun refreshDiagnostics() {
        if (!hardwareOnline.value) return
        scope.launch(tunerDispatcher) {
            diagnostics.value = diagnostics.value.copy(
                fmState = runCatching { receiver?.fmState }.getOrNull(),
                halLoopback = FmAudioRoute.loopbackActive(audioManager),
                driverMuted = FmV4L2.isMuted(),
                driverFreqKHz = FmV4L2.tunedKHz(),
            )
            readQualcommOnce()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            readQualcommOnce()
            var tick = 0
            while (isActive && isPoweredOn.value) {
                // Only meter while someone is looking. Every tick is an I2C transaction on a
                // chip that is demodulating, and in the background nothing reads the result:
                // the radio plays through the hardware loopback whether we poll or not.
                if (uiVisible) {
                    refreshSignal()
                    tick++
                    delay(SIGNAL_POLL_MS)
                } else {
                    delay(4000)
                }
            }
        }
    }

    // ------------------------------------------------------------------ audio bridge

    /**
     * Decide whether to hold the RADIO_TUNER capture open at all.
     *
     * It used to run whenever the tuner screen was visible, to feed the spectrum. It does not
     * any more, because on this device the capture is not the radio. Recorded at the strongest
     * frequency in the band with the antenna attached and at a dead frequency with no antenna,
     * the two captures are indistinguishable: RMS -26.6 vs -27.1 dBFS, crest 4.26 vs 4.36, and
     * the same spectral shape. Meanwhile RSSI moves from 0 to 19 across those same frequencies,
     * so the tuner is receiving and this stream is not carrying what it receives. The HAL opens
     * it as usecase 20 "audio-record" with no FM calibration (ACDB reports no matching module
     * ids), which is consistent with an unrouted buffer rather than FM audio.
     *
     * Drawing a spectrum of it would be presenting a constant artifact as a measurement, so the
     * capture now runs only where stock runs it: A2DP, where it genuinely is the audio path,
     * and while recording, where the user asked for whatever is there.
     */
    private fun captureWanted(): Boolean =
        isPoweredOn.value && hardwareOnline.value &&
            (activeRouteCode == FmAudioRoute.DEV_A2DP || wav.isRecording())

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
            // Decoded mono, which is never longer than one sample per 4 bytes of input.
            val mono = ShortArray(minBuf / 4 + 2)
            var framePhase = FmCaptureFrame.PHASE_NONE
            var phaseChecked = false
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
                        // The stream claims to be 48 kHz 16-bit stereo and is not; see
                        // FmCaptureFrame. Everything that treats it as a measurement has to go
                        // through the decoder, or it is measuring mis-framed bytes.
                        if (!phaseChecked) {
                            framePhase = FmCaptureFrame.detectPhase(buffer, read)
                            phaseChecked = true
                            captureFraming.value = framePhase != FmCaptureFrame.PHASE_NONE
                            capturedRateHz = FmCaptureFrame.decodedRate(framePhase, sampleRate)
                        }
                        val n = FmCaptureFrame.decode(buffer, read, framePhase, mono)
                        val rate = FmCaptureFrame.decodedRate(framePhase, sampleRate)
                        // The AudioTrack bridge is only the audio path on A2DP, and it is fed
                        // the decoded samples for the same reason: the raw buffer is noise.
                        if (bridgeToTrack) audioTrackHelper?.writeMono(mono, n)
                        wav.writeMono(mono, n)
                        updateSpectrum(mono, n, rate)
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
            captureFraming.value = null
            Log.d(TAG, "AudioRecord bridge stopped")
        }, "MikuFmAudioBridgeThread").apply { start() }
    }

    // ------------------------------------------------------------------ RDS

    private var rdsThread: Thread? = null
    @Volatile private var rdsRunning = false

    /** True/false once known, null before the first attempt. Surfaced in diagnostics. */
    val rdsSupported = MutableStateFlow<Boolean?>(null)
    val rdsArtist = MutableStateFlow<String?>(null)
    val rdsTitle = MutableStateFlow<String?>(null)
    val rdsGroups = MutableStateFlow(0)

    /**
     * Read RDS straight off /dev/radio0, the way HiBy's driver actually serves it.
     *
     * The HCI RDS callbacks belong to the Qualcomm core, which has no antenna on this board, so
     * they were never going to deliver anything. The Si4705 is the part receiving. Its driver
     * only switches RDS on from poll(), hands out one whole group per read(), and returns 0 from
     * read() either way; FmRdsReader has the details. So the loop is: arm, then read until the
     * chip has nothing, sleep a little, and re-arm whenever groups stop for a second (a retune or
     * a fade loses sync, and only poll() turns reception back on).
     *
     * It runs for as long as the tuner is on. A station with no RDS, or a signal too weak to
     * carry it, just costs one I2C status read per 80 ms.
     */
    /** When the current station was tuned, so "no RDS" is timed from the tune, not from power-on. */
    @Volatile private var rdsTunedAt = 0L

    private fun startRdsReader() {
        if (rdsRunning) return
        rdsRunning = true
        FmRdsReader.newStation()
        rdsThread = Thread({
            val fd = FmRdsReader.open()
            if (fd == null) {
                rdsSupported.value = false
                rdsRunning = false
                return@Thread
            }
            rdsSupported.value = true
            val buf = ByteArray(FmRdsReader.GROUP_BYTES)
            rdsTunedAt = SystemClock.elapsedRealtime()
            var lastGroupAt = 0L
            var lastArmAt = 0L
            try {
                while (rdsRunning && isPoweredOn.value) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastGroupAt > RDS_REARM_MS && now - lastArmAt > RDS_REARM_MS) {
                        FmRdsReader.arm(fd)
                        lastArmAt = now
                    }
                    var changed = false
                    var drained = 0
                    // The chip's FIFO holds a handful of groups; empty it, then rest.
                    while (drained < 16 && FmRdsReader.readGroup(fd, buf)) {
                        drained++
                        if (FmRdsReader.offerGroup(buf)) changed = true
                    }
                    if (drained > 0) {
                        lastGroupAt = now
                        rdsGroups.value = FmRdsReader.state.groups
                        rdsAvailable.value = true
                    } else if (now - maxOf(lastGroupAt, rdsTunedAt) > RDS_ABSENT_MS &&
                        rdsAvailable.value != false && FmRdsReader.state.groups == 0) {
                        // Ten seconds tuned with not one clean group: say so, but keep listening,
                        // because the signal can come up.
                        rdsAvailable.value = false
                    }
                    if (changed) publishRds()
                    Thread.sleep(if (drained > 0) 40 else 80)
                }
            } catch (_: InterruptedException) {
            } catch (e: android.system.ErrnoException) {
                Log.w(TAG, "RDS read failed: ${android.system.OsConstants.errnoName(e.errno)}")
            } finally {
                FmRdsReader.close(fd)
                rdsRunning = false
                Log.i(TAG, "RDS reader stopped (${FmRdsReader.state.groups} group(s), " +
                    "whole=${FmRdsReader.wholeGroups})")
            }
        }, "MikuFmRdsReader").apply { isDaemon = true; start() }
    }

    private fun publishRds() {
        val r = FmRdsReader.state
        when {
            r.ps.isNotEmpty() -> stationName.value = r.ps
            // No PS yet (or a driver that cannot deliver it): the call letters are in the PI
            // code itself, so the station can still be named by what it actually transmits.
            r.callSign != null && stationName.value.isEmpty() -> stationName.value = r.callSign
        }
        if (r.rt.isNotEmpty()) radioText.value = r.rt
        r.pty?.let { if (it > 0) programmeType.value = it }
        r.pi?.let { if (it != 0) programmeId.value = it }
        rdsArtist.value = r.rtPlusArtist
        rdsTitle.value = r.rtPlusTitle
    }

    private fun stopRdsReader() {
        rdsRunning = false
        rdsThread?.interrupt()
        rdsThread = null
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
    private fun updateSpectrum(samples: ShortArray, count: Int, sampleRate: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSpectrumMs < 80) return
        val frames = minOf(count, SPECTRUM_FRAMES)
        if (frames < 256) return
        lastSpectrumMs = now
        var sumSq = 0.0
        for (i in 0 until frames) {
            val m = samples[i] / 32768f
            monoScratch[i] = m; sumSq += (m * m).toDouble()
        }
        // Bins above Nyquist cannot be measured, so they are not drawn rather than drawn as
        // zero: the UI reads spectrumTopHz to know how far the plot is real.
        val nyquist = sampleRate / 2
        spectrumTopHz.value = minOf(SPECTRUM_HIGH_HZ, nyquist.toDouble()).toInt()
        val out = FloatArray(SPECTRUM_BINS)
        for (b in 0 until SPECTRUM_BINS) {
            if (binFreqs[b] >= nyquist) { out[b] = 0f; continue }
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
        wav.setSampleRate(capturedRateHz)
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
                        // Power the tuner from adb. The foreground service is not exported, so
                        // without this the only way to switch the radio on is a finger on the
                        // screen - which is why every audio question this session has needed the
                        // user present. The receiver is already reachable; this just uses it.
                        "power" -> {
                            val on = intent.getIntExtra("on", 1) == 1
                            Log.i(TAG, "debug: power ${if (on) "on" else "off"}")
                            if (on) powerOn() else powerOff()
                        }
                        "state" -> Log.i(TAG, "STATE poweredOn=${isPoweredOn.value} " +
                            "muted=${isMuted.value} driverMuted=${FmV4L2.isMuted()} " +
                            "freq=${currentFrequencyKHz.value} driverFreq=${FmV4L2.tunedKHz()} " +
                            "channelMode=${FmV4L2.channelMode()} " +
                            "level=${fmVolumeLevel.value} route=$activeRouteCode " +
                            "halLoopback=${FmAudioRoute.loopbackActive(audioManager)}")
                        "mute" -> mute()
                        "unmute" -> unmute()
                        "vol" -> setFmVolumeLevel(intent.getIntExtra("level", fmVolumeLevel.value))
                        // Sweep the tuner part's own volume to find both the working range and
                        // whether it is the thing standing between a correct route and silence.
                        "sysfs" -> FmDriverSysfs.logProbe()
                        "nearby" -> {
                            refreshNearby(force = true)
                            Log.i(TAG, "catalogue size=${FmStationCatalogue.size()} " +
                                "pos=${FmStationCatalogue.listenerPosition(context)}")
                        }
                        // Live knobs, so the remaining options can be swept over adb while
                        // someone listens, instead of costing a flash per guess.
                        "v4l2mode" -> {
                            val m = intent.getIntExtra("m", 0)
                            val ok = FmV4L2.setChannelMode(m)
                            // Stock always re-issues the frequency straight after this.
                            FmV4L2.setTunedKHz(currentFrequencyKHz.value)
                            Log.i(TAG, "setV4L2RadioChannelMode($m) accepted=$ok readback=${FmV4L2.channelMode()}")
                        }
                        "retune" -> {
                            val f = currentFrequencyKHz.value
                            Log.i(TAG, "retune: v4l2=${FmV4L2.setTunedKHz(f)} driverFreq=${FmV4L2.tunedKHz()}")
                        }
                        "v4l2vol" -> {
                            val v = intent.getIntExtra("v", 63)
                            val ok = FmV4L2.setRadioVolume(v)
                            Log.i(TAG, "setV4L2RadioFmVolume($v) accepted=$ok")
                        }
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
            "si4705[rssi=${s.rssi} snr=${s.snr} mpath=${s.multipath} valid=${s.valid}] " +
            "qcom[rssi=${d.rssiQualcomm} sinr=${d.sinrQualcomm}] " +
            "audioLevel=${audioLevel.value} framing=${captureFraming.value} " +
            "stereo=${isStereo.value} rds=${rdsAvailable.value} " +
            "name='${stationName.value}' rt='${radioText.value}' err=${hardwareError.value}")
    }
}
