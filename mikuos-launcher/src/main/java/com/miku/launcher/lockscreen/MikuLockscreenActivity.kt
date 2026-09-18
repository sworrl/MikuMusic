package com.miku.launcher.lockscreen

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.miku.launcher.haptics.MikuTactileHaptics
import com.miku.launcher.lockscreen.MikuPlayHistoryStore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import com.miku.launcher.ui.mikuPressScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.miku.launcher.AudiowideFont
import com.miku.launcher.CrashSentinel
import com.miku.launcher.MikuCyan
import com.miku.launcher.MikuNeonPink
import com.miku.launcher.R
import com.miku.launcher.RootShell
import com.miku.launcher.cyber24BitColorShift
import com.miku.launcher.quiltPatch
import com.miku.launcher.theme.MikuDiurnalTheme
import com.miku.launcher.volume.CyberVolumeBadge
import com.miku.launcher.volume.MikuCyberVolumeHudOverlay
import com.miku.launcher.volume.MikuVolumeManager
import com.miku.launcher.weather.MikuWeatherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * OS-level Hatsune Miku Custom Lockscreen (Compose UI) — ported into the MikuOS launcher and
 * decoupled from the music app:
 *  - Now-Playing is read from a media3 [MediaController] connected to the music app's
 *    MediaSession (`com.miku.player/.PlaybackService`) rather than an in-process PlayerHolder.
 *    If the controller can't connect (app not running) the clock/wallpaper lockscreen still
 *    renders with the playback card hidden.
 *  - Artwork loads via Coil from the controller's MediaMetadata.
 *
 * Brightness lifecycle state machine (user spec, all four values live-settable via
 * [MikuLockscreenPrefs]):
 *   FULL (1.0) for fullBrightMillis -> fade 1.0 -> halfBrightnessFraction over fadeToHalfMillis
 *   -> if thenScreenOff, turn the screen off. Any tap/interaction/wake resets to FULL.
 */
class MikuLockscreenActivity : ComponentActivity() {

    private var isScreenOffTransitionState by mutableStateOf(false)
    private var wakeupTriggerState by mutableIntStateOf(0)

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                wakeUpBright()
            }
        }
    }

    private fun hideSystemBars() {
        try {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        } catch (_: Throwable) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)

        MikuLockscreenManager.setLocked(true)

        // Initialize volume manager
        MikuVolumeManager.init(this)

        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenReceiver, filter)

        isScreenOffTransitionState = intent.getBooleanExtra("is_screen_off_transition", false)

        // Show over keyguard. turn-screen-on must track the launch mode: requesting it on the
        // dark pre-arm lets ATMS resume this activity while the device sleeps, with a window the
        // client never makes visible (decor GONE). That zombie then owns the focused-activity
        // slot with no focusable window anywhere (FocusedWindows: <none>), killing input
        // system-wide. Only the wake paths may light the panel.
        setShowWhenLocked(true)
        applyWakePolicy(turnScreenOn = !isScreenOffTransitionState)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Full immersive sticky fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        @Suppress("DEPRECATION")
        overridePendingTransition(com.miku.launcher.R.anim.lockscreen_curtain_down, 0)

        setContent {
            MikuKawaiiLockscreenScreen(
                wakeupTrigger = wakeupTriggerState,
                onUnlock = {
                    MikuLockscreenManager.setLocked(false)
                    // Dismissal must not hinge on a KeyguardDismissCallback ever firing — on
                    // this ROM there may be no stock keyguard showing at all, and while this
                    // activity sits waiting it still owns the resumed slot. Fire the dismiss
                    // request, then vacate unconditionally.
                    try {
                        getSystemService(KeyguardManager::class.java)
                            ?.requestDismissKeyguard(this, null)
                    } catch (_: Throwable) {}
                    finishAndRemoveTask()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, com.miku.launcher.R.anim.lockscreen_curtain_up)
                }
            )
        }
    }

    fun wakeUpBright() {
        isScreenOffTransitionState = false
        applyWakePolicy(turnScreenOn = true)
        ensureDecorVisible()
        restoreScreenOffTimeout()
        // User spec: FULL phase is 1.0 window brightness.
        setWindowBrightness(1f)
        wakeupTriggerState++
    }

    /**
     * turn-screen-on is only legal while the lockscreen should actually light the panel. Set
     * while asleep (dark pre-arm) it lets ATMS keep this activity resumed through the sleep with
     * a never-shown window — the resumed-but-GONE zombie that leaves the whole system without a
     * focused window. The pre-arm path passes false; every wake path passes true.
     */
    private fun applyWakePolicy(turnScreenOn: Boolean) {
        try {
            setTurnScreenOn(turnScreenOn)
        } catch (_: Throwable) {}
    }

    /**
     * If a sleep transition stopped this activity the framework leaves the decor GONE; when the
     * instance is then revived via onNewIntent alone (singleInstance + SINGLE_TOP) no lifecycle
     * step ever re-drives client visibility, so the GONE window would persist with the screen on
     * — invisible, unfocusable, and squatting on the resumed slot. A GONE decor never enters the
     * input pipeline, hence FocusedWindows: <none>. Re-asserting VISIBLE forces the relayout that
     * makes the window visible + focusable; no-op when already shown.
     */
    private fun ensureDecorVisible() {
        try {
            if (window.decorView.visibility != android.view.View.VISIBLE) {
                window.decorView.visibility = android.view.View.VISIBLE
            }
        } catch (_: Throwable) {}
    }

    /**
     * Set this Activity window's brightness. Per-window override; needs NO permission.
     * Pass a value in 0f..1f, or WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE (-1f)
     * to defer to the system default brightness.
     */
    fun setWindowBrightness(brightness: Float) {
        try {
            val lp = window.attributes
            lp.screenBrightness = brightness
            window.attributes = lp
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        MikuLockscreenManager.setLocked(true)
        hideSystemBars()
        @Suppress("DEPRECATION")
        overridePendingTransition(com.miku.launcher.R.anim.lockscreen_curtain_down, 0)
        // The dark pre-arm resumes once while the panel is still powering down; treating that
        // as a wake would re-request turn-screen-on and fight the in-flight sleep. Waking is
        // driven by ACTION_SCREEN_ON / a wake-mode onNewIntent / focus gain instead.
        if (!isScreenOffTransitionState) wakeUpBright()
    }

    override fun onDestroy() {
        super.onDestroy()
        restoreScreenOffTimeout()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(com.miku.launcher.R.anim.lockscreen_curtain_down, 0)
        val isOff = intent.getBooleanExtra("is_screen_off_transition", false)
        if (isOff) {
            isScreenOffTransitionState = true
            // Re-armed for a dark sleep: must not hold turn-screen-on through it.
            applyWakePolicy(turnScreenOn = false)
        } else {
            wakeUpBright()
        }
    }

    /**
     * Turn the screen off. Tries, in order, the first path that works (each wrapped in try/catch):
     *   (a) DevicePolicyManager.lockNow()  — works once a device-admin component is active.
     *   (b) PowerManager.goToSleep(...)    — needs DEVICE_POWER (privileged/platform-signed).
     *   (c) RootShell `input keyevent 26`  — only if the device is actually rooted.
     *   (d) Last resort (always available, no permission): drive this window's brightness to ~0
     *       and shorten the system screen-off timeout so it sleeps quickly on its own (the
     *       user's timeout is captured first and restored on the next wake).
     */
    fun turnScreenOff() {
        isScreenOffTransitionState = true
        // Heading into a sleep: a standing turn-screen-on request would re-light the panel
        // (or leave this activity resumed-while-sleeping). The wake paths re-request it.
        applyWakePolicy(turnScreenOn = false)

        // (a) Device-admin lock.
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            if (dpm != null) {
                dpm.lockNow()
                return
            }
        } catch (_: Throwable) {}

        // (b) PowerManager.goToSleep (hidden API, reflection; needs DEVICE_POWER).
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null) {
                val goToSleep = PowerManager::class.java.getMethod("goToSleep", Long::class.javaPrimitiveType)
                goToSleep.invoke(pm, SystemClock.uptimeMillis())
                return
            }
        } catch (_: Throwable) {}

        // (c) Root shell power keyevent.
        try {
            if (RootShell.isAvailable()) {
                CoroutineScope(Dispatchers.IO).launch {
                    RootShell.execFast("input keyevent 26")
                }
                return
            }
        } catch (_: Throwable) {}

        // (d) Last resort — always works, no privilege needed. SCREEN_OFF_TIMEOUT is a GLOBAL
        // user setting, so the shortened value must be restored on the next wake or it silently
        // becomes the device's permanent timeout.
        setWindowBrightness(0.01f)
        try {
            if (savedScreenOffTimeoutMs < 0) {
                savedScreenOffTimeoutMs =
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
            }
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 15000)
        } catch (_: Throwable) {}
    }

    /** User's SCREEN_OFF_TIMEOUT captured before (d) shortens it; -1 = nothing to restore. */
    private var savedScreenOffTimeoutMs: Int = -1

    private fun restoreScreenOffTimeout() {
        val prev = savedScreenOffTimeoutMs
        if (prev > 0) {
            savedScreenOffTimeoutMs = -1
            try {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, prev)
            } catch (_: Throwable) {}
        }
    }

    /**
     * MEDIA_* / HEADSETHOOK keys must never be consumed by the lockscreen: left unhandled they
     * fall back to MediaSessionService routing, which is what drives the hardware transport
     * buttons (gpio-keys-hiby) while the lockscreen is showing.
     */
    private fun isMediaKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_HEADSETHOOK -> true
        else -> false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Media keys are handled in dispatchKeyEvent (forwarded to the session there); nothing to do here.
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            turnScreenOff()
            return true
        }
        if (MikuVolumeManager.handleKeyDown(keyCode, this)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isMediaKey(event.keyCode)) {
            // When locked, THIS activity is the foreground window and receives the hardware
            // transport keys first. Declining them (super) and trusting the framework to fall
            // them through to the media session is unreliable with a custom always-on-top
            // lockscreen — the physical Play/Next/Prev buttons then did nothing while locked.
            // Forward the key explicitly to the active media session and consume it, so exactly
            // one delivery reaches the player regardless of focus.
            runCatching {
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.dispatchMediaKeyEvent(event)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_POWER && event.action == KeyEvent.ACTION_DOWN) {
            turnScreenOff()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            if (!isScreenOffTransitionState) wakeUpBright()
        }
    }
}

/**
 * Live Now-Playing snapshot, sourced from the music app's MediaSession via a [MediaController].
 * [hasSession] is false when no controller is connected / nothing is loaded — the UI then hides
 * all playback affordances.
 */
data class MikuNowPlaying(
    val hasSession: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val isPlaying: Boolean = false,
    val isLiked: Boolean = false,
    val artwork: Any? = null,       // ByteArray or Uri — Coil consumes either
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    // Blank / 0 = the player has not published a real format or tempo for this track.
    val format: String = "",
    val bpm: Float = 0f
)

/**
 * Builds (and releases) a media3 [MediaController] bound to the music app's PlaybackService.
 * Returns a State that is null until connected, and reverts to null if the connection can't be
 * made (app not running) — callers must null-check and degrade gracefully.
 */
@Composable
private fun rememberMusicController(): State<MikuMediaLink?> {
    val context = LocalContext.current
    val controllerState = remember { mutableStateOf<MikuMediaLink?>(null) }
    // Session-agnostic: poll the platform MediaSessionManager for the ACTIVE media app (Spotify,
    // Tidal, our player, anything) so the lockscreen surfaces and controls whatever is playing.
    LaunchedEffect(Unit) {
        while (true) {
            controllerState.value = MikuMediaLink.active(context)
            kotlinx.coroutines.delay(1000)
        }
    }
    return controllerState
}

private fun appLabelFor(ctx: android.content.Context, pkg: String): String = try {
    val pm = ctx.packageManager
    val ai = pm.getApplicationInfo(pkg, 0)
    pm.getApplicationLabel(ai).toString()
} catch (_: Throwable) { pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }

private fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

@Composable
fun MikuKawaiiLockscreenScreen(
    wakeupTrigger: Int,
    onUnlock: () -> Unit
) {
    BackHandler(enabled = true) {}

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val palette by MikuDiurnalTheme.rememberDiurnalPalette()

    // Drag offset for swipe-up-to-unlock gesture
    val dragOffsetY = remember { Animatable(0f) }

    // ================= USER-SPEC BRIGHTNESS LIFECYCLE STATE MACHINE =================
    // Full (1.0) for fullBrightMillis -> fade 1.0 -> halfBrightnessFraction over fadeToHalfMillis
    // -> if thenScreenOff, turn the screen off. Any interaction bumps lastInteractionMs (and a wake
    // bumps wakeupTrigger), both of which restart this machine at the Full phase.
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(wakeupTrigger, lastInteractionMs) {
        val act = context as? MikuLockscreenActivity
        if (!MikuLockscreenPrefs.isEnabled(context)) {
            act?.setWindowBrightness(1f)
            return@LaunchedEffect
        }

        val t1 = MikuLockscreenPrefs.getFullBrightMillis(context)          // ms at full bright
        val t2 = MikuLockscreenPrefs.getFadeToHalfMillis(context)          // ms fade duration
        val half = MikuLockscreenPrefs.getHalfBrightnessFraction(context)  // fade target fraction
        val thenOff = MikuLockscreenPrefs.getThenScreenOff(context)

        // ---- Full phase: window brightness = 1.0 for T1 ----
        act?.setWindowBrightness(1f)
        if (t1 > 0L) delay(t1)

        // ---- Fade phase: 1.0 -> half over T2 (ease-in-out) ----
        val startB = 1f
        val targetB = half.coerceIn(0.01f, 1f)
        val startTime = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            val frac = if (t2 <= 0L) 1f else (elapsed.toFloat() / t2).coerceIn(0f, 1f)
            val eased = frac * frac * (3f - 2f * frac)
            act?.setWindowBrightness(startB + (targetB - startB) * eased)
            if (frac >= 1f) break
            delay(16L)
        }

        // ---- Off phase ----
        // Only the hosting activity can turn the screen off; root injection is optional on this
        // OS and must not be a fallback here (it silently no-ops on an unrooted boot).
        if (thenOff) {
            act?.turnScreenOff()
        }
    }

    // Real-time time & date (24-Hour Military Time & MM/DD/YYYY)
    // Seeded from the real clock synchronously — the first frame is never a placeholder time/date.
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.US).format(Date())) }
    var currentDate by remember { mutableStateOf(SimpleDateFormat("MM/dd/yyyy", Locale.US).format(Date())) }
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)
        while (true) {
            val now = Date()
            currentTime = timeFormat.format(now)
            currentDate = dateFormat.format(now)
            delay(500)
        }
    }

    // Battery — seeded from the sticky broadcast synchronously so the first frame is the real
    // level, never a placeholder 100%.
    val initialBattery = remember {
        try {
            val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scl = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            // -1 = could not read. It was 0, which the badge rendered as a red, critical-looking
            // "0%" — just as fabricated as the placeholder 100% it replaced.
            val pct = if (lvl >= 0 && scl > 0) (lvl * 100 / scl.toFloat()).roundToInt() else -1
            pct to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
        } catch (_: Throwable) { -1 to false }
    }
    var batteryPercent by remember { mutableIntStateOf(initialBattery.first) }
    var isCharging by remember { mutableStateOf(initialBattery.second) }
    LaunchedEffect(Unit) {
        while (true) {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bStatus = context.registerReceiver(null, ifilter)
            val level = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = bStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (level >= 0 && scale > 0) batteryPercent = (level * 100 / scale.toFloat()).roundToInt()
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            delay(5000)
        }
    }

    // Live weather & GPS (launcher's own MikuWeatherService)
    val weatherData by MikuWeatherService.state.collectAsState()
    val weather = weatherData.weather
    val gps = weatherData.gps

    // ================= NOW PLAYING via MediaController =================
    val controller by rememberMusicController()
    var nowPlaying by remember { mutableStateOf(MikuNowPlaying()) }
    var localLikedState by remember { mutableStateOf(false) }
    var showHistoryDrawer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        MikuPlayHistoryStore.init(context)
    }

    // Real-time Like State Receiver from Miku Music LikeStore
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "com.miku.player.action.LIKE_STATE_CHANGED") {
                    localLikedState = intent.getBooleanExtra("is_liked", false)
                }
            }
        }
        val filter = IntentFilter("com.miku.player.action.LIKE_STATE_CHANGED")
        try {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Throwable) {}
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }

    // Keyed on Unit and reads `controller` inside the loop so it doesn't thrash-restart every
    // second, and falls back to what com.miku.player publishes to Settings.Global when the live
    // platform session read is momentarily empty — so the lockscreen is always aware of what's
    // playing (any app via the session, our player via the reliable Settings fallback).
    LaunchedEffect(Unit) {
        val cr = context.contentResolver
        while (true) {
            val c = controller
            nowPlaying = try {
                var title = c?.title
                var artist = c?.artist
                val album = c?.album ?: ""
                val mediaId = c?.mediaId
                var isPlaying = c?.isPlaying ?: false
                val pkg = c?.packageName ?: "com.miku.player"
                val isMiku = pkg == "com.miku.player"
                // Fallback: our player always writes these; use them when the session read is empty.
                if (title.isNullOrEmpty()) {
                    val t = try { Settings.Global.getString(cr, "miku_now_playing_title") } catch (_: Throwable) { null }
                    if (!t.isNullOrEmpty()) {
                        title = t
                        artist = try { Settings.Global.getString(cr, "miku_now_playing_artist") } catch (_: Throwable) { null }
                        isPlaying = try { Settings.Global.getInt(cr, "miku_is_playing", 0) == 1 } catch (_: Throwable) { true }
                    }
                }
                // NO fabricated artist. This used to force artist = "Hatsune Miku" for any session
                // without artist metadata — a Spotify/Tidal stream or a tag-less file was attributed
                // to her on screen AND written into the persisted play history. A blank artist stays
                // blank; the renderer shows "Unknown artist".
                // Our player publishes true format/bpm/like into Settings.Global; for a 3rd-party
                // app (Spotify etc) show its source rather than a false "DTA" claim.
                // No published format = blank (badge hidden); never a default "DTA 24/96" claim.
                val format = if (isMiku) {
                    try { Settings.Global.getString(cr, "miku_now_playing_format") ?: "" }
                    catch (_: Throwable) { "" }
                } else appLabelFor(context, pkg)
                // 0 = tempo not analysed; third-party sessions never get an invented tempo.
                val bpm = if (isMiku) {
                    try { Settings.Global.getFloat(cr, "miku_now_playing_bpm", 0f) } catch (_: Throwable) { 0f }
                } else 0f
                val isLikedFromSettings = isMiku && try {
                    Settings.Global.getString(cr, "miku_current_track_liked") == "1"
                } catch (_: Throwable) { false }
                localLikedState = isLikedFromSettings

                if (!title.isNullOrEmpty()) {
                    MikuPlayHistoryStore.recordPlay(
                        mediaId = mediaId ?: "",
                        title = title,
                        artist = artist ?: "",
                        album = album,
                        format = format,
                        durationMs = (c?.durationMs ?: 0L).coerceAtLeast(0L)
                    )
                }

                MikuNowPlaying(
                    hasSession = !title.isNullOrEmpty(),
                    mediaId = mediaId,
                    title = title,
                    artist = artist,
                    album = album,
                    isPlaying = isPlaying,
                    isLiked = isLikedFromSettings,
                    artwork = c?.artwork,
                    positionMs = (c?.positionMs ?: 0L).coerceAtLeast(0L),
                    durationMs = (c?.durationMs ?: 0L).coerceAtLeast(0L),
                    format = format,
                    bpm = bpm
                )
            } catch (_: Throwable) {
                MikuNowPlaying()
            }
            delay(1000)
        }
    }

    val unlockThreshold = with(density) { -160.dp.toPx() }
    var rawDragY by remember { mutableFloatStateOf(0f) }
    // First-show settle: clock, then date (60 ms later), then the weather capsule (120 ms).
    val settleClock = remember { Animatable(0f) }
    val settleDate = remember { Animatable(0f) }
    val settleWeather = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val fast = com.miku.launcher.ui.MikuPowerProfile.isLowPower
        launch { settleClock.animateTo(1f, if (fast) tween(120) else spring(dampingRatio = 0.8f, stiffness = 380f)) }
        launch { delay(60); settleDate.animateTo(1f, if (fast) tween(120) else spring(dampingRatio = 0.8f, stiffness = 380f)) }
        launch { delay(120); settleWeather.animateTo(1f, if (fast) tween(120) else spring(dampingRatio = 0.8f, stiffness = 380f)) }
    }
    val npAccentLock = com.miku.launcher.ui.rememberNpAccent()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffsetY.value.roundToInt()) }
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    lastInteractionMs = System.currentTimeMillis()
                })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { lastInteractionMs = System.currentTimeMillis(); rawDragY = dragOffsetY.value },
                    onDragCancel = {
                        coroutineScope.launch {
                            dragOffsetY.animateTo(
                                0f,
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    },
                    onDragEnd = {
                        lastInteractionMs = System.currentTimeMillis()
                        coroutineScope.launch {
                            if (rawDragY < unlockThreshold) {
                                // Commit: spring the curtain up and off (no wobble), then unlock.
                                com.miku.launcher.haptics.MikuHaptics.confirm(context)
                                dragOffsetY.animateTo(
                                    -2500f,
                                    spring(dampingRatio = 1f, stiffness = 520f, visibilityThreshold = 4f)
                                )
                                onUnlock()
                            } else {
                                if (rawDragY < -24f) com.miku.launcher.haptics.MikuHaptics.tick(context)
                                dragOffsetY.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    },
                    onVerticalDrag = { _, dragAmount ->
                        lastInteractionMs = System.currentTimeMillis()
                        coroutineScope.launch {
                            // Raw travel accumulates; past the unlock threshold the card rubber-bands
                            // (35 % of the extra travel) so it feels held, not slid off.
                            rawDragY = (rawDragY + dragAmount).coerceAtMost(0f)
                            val eased = if (rawDragY < unlockThreshold) unlockThreshold + (rawDragY - unlockThreshold) * 0.35f else rawDragY
                            dragOffsetY.snapTo(eased)
                        }
                    }
                )
            }
    ) {
        // 1. Fullscreen Wallpaper Backdrop (bundled Miku portrait)
        Image(
            painter = painterResource(id = R.drawable.miku_boot_splash),
            contentDescription = "Lockscreen Wallpaper",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 2. Frosted Cyber Glow Vignette
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xEE02090D),
                            Color(0x7704121A),
                            Color(0x9902090D),
                            Color(0xF802090D)
                        )
                    )
                )
        )

        // 3. Floating Sakura Blossom Petals
        MikuSakuraBlossomCanvas(
            modifier = Modifier.fillMaxSize(),
            petalCount = 38
        )

        // 4. Main Lockscreen Layout
        val density = androidx.compose.ui.platform.LocalDensity.current
        val fontScale = density.fontScale
        val effectiveScale = 1.0f + (fontScale - 1.0f) * 0.15f
        fun Number.dampedSp(): androidx.compose.ui.unit.TextUnit = (this.toFloat() / fontScale * effectiveScale).sp

        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 8.dp, bottom = 10.dp, start = 10.dp, end = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // batteryPercent < 0 = the sticky broadcast was unreadable → neutral badge + "—%".
            val batteryKnown = batteryPercent >= 0
            val batteryColor = when {
                !batteryKnown -> Color(0xFF88BAC6)
                isCharging -> com.miku.launcher.ui.MikuIdentity.Leek
                batteryPercent > 60 -> palette.primary
                batteryPercent > 20 -> com.miku.launcher.ui.MikuIdentity.Gold
                else -> com.miku.launcher.ui.MikuIdentity.Coral
            }
            val batteryGradient = when {
                !batteryKnown -> listOf(Color(0xEE0B1A20), Color(0xFF030D14))
                isCharging -> listOf(Color(0x5500E676), Color(0xFF04150E))
                batteryPercent > 60 -> listOf(Color(0xEE081F2A), Color(0xFF030D14))
                batteryPercent > 20 -> listOf(Color(0x55FFD600), Color(0xFF1E1704))
                else -> listOf(Color(0x66FF1744), Color(0xFF200508))
            }

            // Thin standard status bar (mirrors the home top bar), then the badge row under it.
            com.miku.launcher.ui.MikuStatusBar(
                state = com.miku.launcher.ui.rememberMikuStatusBarState(clock = currentTime, isPlaying = false, thermalC = 0f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            // Top telemetry bar
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: DAC Bitperfect Badge
                Box(
                    Modifier
                        .height(20.dp)
                        .clip(CutCornerShape(4.dp))
                        .background(Color(0x9904121A))
                        .border(0.8.dp, palette.accent.copy(alpha = 0.7f), CutCornerShape(4.dp))
                        .padding(horizontal = 4.5.dp, vertical = 1.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(3.5.dp)
                                .clip(CircleShape)
                                .background(palette.accent)
                        )
                        Spacer(Modifier.width(2.5.dp))
                        Text(
                            "CS43198",
                            color = palette.accent,
                            fontSize = 10.dampedSp(),
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            maxLines = 1
                        )
                    }
                }

                // Right: Brain Sentinel + Ingestion Badge + Volume Badge + Battery Badge
                Row(
                    modifier = Modifier.quiltPatch(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Box(
                        Modifier
                            .height(20.dp)
                            .clip(CutCornerShape(4.dp))
                            .background(Color(0x9904121A))
                            .border(0.8.dp, Color(0xFF00FF7F).copy(alpha = 0.7f), CutCornerShape(4.dp))
                            .padding(horizontal = 4.5.dp, vertical = 1.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(3.5.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00FF7F))
                            )
                            Spacer(Modifier.width(2.5.dp))
                            Text(
                                "BRAIN",
                                color = Color(0xFF00FF7F),
                                fontSize = 10.dampedSp(),
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                maxLines = 1
                            )
                        }
                    }

                    com.miku.launcher.ingest.MikuIngestionBadge(
                        onClick = { /* lockscreen display */ }
                    )

                    CyberVolumeBadge(
                        onClick = { MikuVolumeManager.triggerHud(context) }
                    )

                    Box(
                        Modifier
                            .height(20.dp)
                            .clip(CutCornerShape(4.dp))
                            .background(Brush.horizontalGradient(batteryGradient))
                            .border(0.8.dp, batteryColor, CutCornerShape(4.dp))
                            .padding(horizontal = 4.5.dp, vertical = 1.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isCharging) Icons.Default.Bolt else Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = batteryColor,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                if (batteryKnown) "$batteryPercent%" else "—%",
                                color = Color.White,
                                fontSize = 10.dampedSp(),
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Digital Clock & Calendar (24H military time)
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.graphicsLayer { alpha = settleClock.value.coerceIn(0f, 1f); translationY = (1f - settleClock.value) * 14.dp.toPx() },
                    text = currentTime,
                    color = Color.White,
                    fontSize = 38.dampedSp(),
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.5.sp
                )
                Text(
                    modifier = Modifier.graphicsLayer { alpha = settleDate.value.coerceIn(0f, 1f); translationY = (1f - settleDate.value) * 10.dp.toPx() },
                    text = currentDate,
                    color = palette.primary,
                    fontSize = 11.5.dampedSp(),
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    letterSpacing = 0.8.sp
                )
            }

            // Weather capsule
            Miku5HourLockscreenTrendCapsule(
                weather = weather,
                gps = gps,
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = settleWeather.value.coerceIn(0f, 1f); translationY = (1f - settleWeather.value) * 8.dp.toPx() }
            )

            Spacer(Modifier.height(2.dp))

            // ================= NOW PLAYING LOCKSCREEN MUSIC WIDGET =================
            if (nowPlaying.hasSession) {
                LockscreenNowPlayingWidget(
                    nowPlaying = nowPlaying,
                    controller = controller,
                    palette = palette,
                    npAccentLock = npAccentLock,
                    localLikedState = localLikedState,
                    onLikedChange = { localLikedState = it },
                    showHistoryDrawer = showHistoryDrawer,
                    onHistoryDrawerChange = { showHistoryDrawer = it },
                    onInteraction = { lastInteractionMs = System.currentTimeMillis() },
                )
            }

            // Gap above the bottom swipe zone
            Spacer(Modifier.height(8.dp))

            // Swipe-up prompt
            val infiniteTransition = rememberInfiniteTransition(label = "ChevronBounce")
            val chevronOffset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Chevron"
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset(y = chevronOffset.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "SWIPE UP TO UNLOCK",
                        color = Color.White,
                        fontSize = 10.5.dampedSp(),
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        // Volume HUD overlay
        MikuCyberVolumeHudOverlay(
            ctx = context,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 92.dp, end = 4.dp)
        )
    }
}

/**
 * Kawaii rolling bounce number transition for clock digits.
 */
@Composable
fun LockscreenKawaiiDigitPair(
    digits: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 72.sp,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        digits.forEachIndexed { idx, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    (slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    ) { height -> -height } + fadeIn(tween(180)) + scaleIn(
                        initialScale = 0.70f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                    )) togetherWith (slideOutVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) { height -> height } + fadeOut(tween(160)) + scaleOut(
                        targetScale = 1.15f,
                        animationSpec = tween(220)
                    ))
                },
                label = "LockKawaiiDigit_${idx}_$char"
            ) { targetChar ->
                Text(
                    text = targetChar.toString(),
                    color = color,
                    fontSize = fontSize,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = (-1.5).sp
                )
            }
        }
    }
}

/**
 * Extra-kawaii holographic plasma clock: large 72sp digits with 24-bit truecolor spectrum
 * shifting, chromatic halos, laser scanline and stardust particles.
 */
@Composable
fun MikuLockscreenMegaClock(time: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "LockClockPulse")

    val colorShiftPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Lock24BitShift"
    )

    val secondTick by produceState(initialValue = (System.currentTimeMillis() / 1000) % 2) {
        while (true) {
            val sec = (System.currentTimeMillis() / 1000)
            value = sec % 2
            val msToNextSec = 1000L - (System.currentTimeMillis() % 1000L)
            delay(msToNextSec.coerceIn(50L, 1000L))
        }
    }

    val heartbeatScale = remember { Animatable(1.0f) }
    LaunchedEffect(secondTick) {
        heartbeatScale.snapTo(1.18f)
        heartbeatScale.animateTo(1.0f, tween(320, easing = FastOutSlowInEasing))
    }

    val hoursShiftColor = cyber24BitColorShift(colorShiftPhase, saturation = 0.85f, brightness = 1.0f)
    val hoursCoreColor = cyber24BitColorShift(colorShiftPhase, saturation = 0.12f, brightness = 1.0f)

    val colonHarmonicOffset = if (secondTick == 0L) 25f else 165f
    val colonHue = colorShiftPhase + colonHarmonicOffset
    val colonColor = cyber24BitColorShift(colonHue, saturation = 0.95f, brightness = 1.0f)
    val colonHaloColor = cyber24BitColorShift(colonHue + 20f, saturation = 0.85f, brightness = 0.95f)

    val minutesShiftColor = cyber24BitColorShift(colorShiftPhase + 50f, saturation = 0.85f, brightness = 1.0f)
    val minutesCoreColor = cyber24BitColorShift(colorShiftPhase + 50f, saturation = 0.12f, brightness = 1.0f)

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.50f,
        targetValue = 0.98f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Glow"
    )

    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Reverse),
        label = "Shimmer"
    )

    val scanlineY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "Scan"
    )

    val particlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3800, easing = LinearEasing), RepeatMode.Restart),
        label = "Particle"
    )

    val timeParts = remember(time) {
        if (time.contains(":")) {
            val idx = time.indexOf(":")
            Pair(time.substring(0, idx), time.substring(idx + 1))
        } else {
            Pair(time, "")
        }
    }

    Box(contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier.size(width = 260.dp, height = 90.dp)
        ) {
            val particles = listOf(
                Triple(0.10f, 0.20f, Color(0xFF00E5FF)),
                Triple(0.25f, 0.70f, Color(0xFFFF007F)),
                Triple(0.40f, 0.30f, Color(0xFF00FFCC)),
                Triple(0.60f, 0.85f, Color(0xFFFFFFFF)),
                Triple(0.75f, 0.25f, Color(0xFF00E5FF)),
                Triple(0.90f, 0.65f, Color(0xFFFF007F)),
                Triple(0.50f, 0.50f, com.miku.launcher.ui.MikuIdentity.Gold)
            )

            particles.forEachIndexed { i, p ->
                val baseX = p.first * size.width
                val baseY = p.second * size.height
                val driftY = (baseY - (particlePhase * size.height) + (i * 15f)) % size.height
                val wobbleX = baseX + (sin((particlePhase * 6.28f) + i) * 8f)
                val pAlpha = (sin((particlePhase * 3.14f) + (i * 0.7f)).coerceIn(0.15f, 0.95f)) * glowAlpha

                drawCircle(
                    color = p.third.copy(alpha = pAlpha),
                    radius = if (i % 2 == 0) 3.0f else 1.8f,
                    center = Offset(wobbleX, driftY)
                )
            }

            val scanY = scanlineY * size.height
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        hoursShiftColor.copy(alpha = 0.5f),
                        Color.White.copy(alpha = 0.8f),
                        minutesShiftColor.copy(alpha = 0.5f),
                        Color.Transparent
                    )
                ),
                start = Offset(0f, scanY),
                end = Offset(size.width, scanY),
                strokeWidth = 1.4f
            )
        }

        // Volumetric plasma halo
        Box(
            Modifier
                .size(width = 250.dp, height = 85.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            hoursShiftColor.copy(alpha = glowAlpha * 0.55f),
                            minutesShiftColor.copy(alpha = glowAlpha * 0.25f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Chromatic offset 1
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.offset(x = (-2.0).dp + (shimmerPhase * 0.3f).dp, y = (-1.2).dp)
        ) {
            LockscreenKawaiiDigitPair(
                digits = timeParts.first,
                color = cyber24BitColorShift(colorShiftPhase + 180f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.85f),
                fontSize = 72.sp
            )
            Text(
                text = ":",
                color = colonHaloColor.copy(alpha = 0.9f),
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .scale(heartbeatScale.value)
            )
            LockscreenKawaiiDigitPair(
                digits = timeParts.second,
                color = cyber24BitColorShift(colorShiftPhase + 230f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.85f),
                fontSize = 72.sp
            )
        }

        // Chromatic offset 2
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.offset(x = (2.0).dp - (shimmerPhase * 0.3f).dp, y = (1.5).dp)
        ) {
            LockscreenKawaiiDigitPair(
                digits = timeParts.first,
                color = cyber24BitColorShift(colorShiftPhase + 290f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.75f),
                fontSize = 72.sp
            )
            Text(
                text = ":",
                color = colonHaloColor.copy(alpha = 0.85f),
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .scale(heartbeatScale.value)
            )
            LockscreenKawaiiDigitPair(
                digits = timeParts.second,
                color = cyber24BitColorShift(colorShiftPhase + 340f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.75f),
                fontSize = 72.sp
            )
        }

        // Luminous core
        Row(verticalAlignment = Alignment.CenterVertically) {
            LockscreenKawaiiDigitPair(
                digits = timeParts.first,
                color = hoursCoreColor,
                fontSize = 72.sp
            )
            Text(
                text = ":",
                color = colonColor,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .scale(heartbeatScale.value)
            )
            LockscreenKawaiiDigitPair(
                digits = timeParts.second,
                color = minutesCoreColor,
                fontSize = 72.sp
            )
        }
    }
}

/**
 * 5-Hour detailed weather & color-coded trending forecast capsule.
 * Reads from the launcher's own MikuWeatherService.
 */
@Composable
fun Miku5HourLockscreenTrendCapsule(
    weather: MikuWeatherService.WeatherCondition,
    gps: MikuWeatherService.GpsTelemetry,
    modifier: Modifier = Modifier
) {
    val palette by MikuDiurnalTheme.rememberDiurnalPalette()

    // Only the fetched hourly series is a forecast. No series = no strip/sparkline; the capsule
    // never extrapolates a trend from the current reading.
    val hasWeather = weather.lastUpdatedTime > 0L
    val next5Hours = remember(weather.hourlyMeteogram) { weather.hourlyMeteogram.take(5) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val fontScale = density.fontScale
    val effectiveScale = 1.0f + (fontScale - 1.0f) * 0.15f
    fun Number.dampedSp(): androidx.compose.ui.unit.TextUnit = (this.toFloat() / fontScale * effectiveScale).sp

    Box(
        modifier = modifier
            .clip(CutCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xEE061C26),
                        Color(0xF6020A10)
                    )
                )
            )
            .border(0.8.dp, palette.primary.copy(alpha = 0.8f), CutCornerShape(10.dp))
            .padding(6.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hasWeather) weather.icon.ifEmpty { "🌸" } else "🌐",
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        if (hasWeather) "${weather.tempF.roundToInt()}°F" else "—°F",
                        color = Color.White,
                        fontSize = 12.5.dampedSp(),
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (hasWeather) weather.summary else "No weather data yet",
                        color = palette.primary,
                        fontSize = 10.5.dampedSp(),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                if (weather.nextPrecipLabel.isNotEmpty()) {
                    Box(
                        Modifier
                            .clip(CutCornerShape(4.dp))
                            .background(Color(0x33FFD600))
                            .border(0.6.dp, com.miku.launcher.ui.MikuIdentity.Gold, CutCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            weather.nextPrecipLabel,
                            color = com.miku.launcher.ui.MikuIdentity.Gold,
                            fontSize = 9.dampedSp(),
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                    }
                }
            }

            Spacer(Modifier.height(3.dp))

            if (next5Hours.size < 2) {
                Text(
                    text = if (hasWeather) "No hourly forecast in the last fetch" else "Hourly trend appears after the first fetch",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 9.dampedSp(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                next5Hours.forEach { pt ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = pt.timeLabel,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 9.dampedSp(),
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = pt.icon,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = "${pt.tempF.roundToInt()}°",
                            color = Color.White,
                            fontSize = 10.dampedSp(),
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        if (pt.precipProbPct > 0) {
                            Text(
                                text = "${pt.precipProbPct}%",
                                color = Color(0xFF00E5FF),
                                fontSize = 8.5.dampedSp(),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(3.dp))

            // Color-coded trending sparkline
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
            ) {
                val w = size.width
                val h = size.height
                val temps = next5Hours.map { it.tempF }
                // No samples = nothing to plot. The old 60f/80f fallbacks invented a temperature
                // scale for an empty series.
                if (temps.isEmpty()) return@Canvas
                val minT = temps.min()
                val maxT = temps.max()
                val range = (maxT - minT).coerceAtLeast(3f)

                val pointsOffset = next5Hours.mapIndexed { idx, pt ->
                    val x = (idx.toFloat() / (next5Hours.size - 1).coerceAtLeast(1)) * w
                    val y = h - ((pt.tempF - minT) / range * (h - 4.dp.toPx())) - 2.dp.toPx()
                    Offset(x, y)
                }

                val fillPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pointsOffset.first().x, h)
                    pointsOffset.forEach { lineTo(it.x, it.y) }
                    lineTo(pointsOffset.last().x, h)
                    close()
                }
                drawPath(
                    fillPath,
                    Brush.verticalGradient(
                        listOf(
                            MikuCyan.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    )
                )

                val strokePath = androidx.compose.ui.graphics.Path().apply {
                    pointsOffset.forEachIndexed { i, p ->
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                }
                drawPath(
                    strokePath,
                    Brush.horizontalGradient(
                        listOf(
                            MikuCyan,
                            com.miku.launcher.ui.MikuIdentity.Leek,
                            com.miku.launcher.ui.MikuIdentity.Gold,
                            MikuNeonPink
                        )
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.6.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )

                pointsOffset.forEach { p ->
                    drawCircle(Color(0xFF04121A), radius = 2.dp.toPx(), center = p)
                    drawCircle(MikuCyan, radius = 1.2.dp.toPx(), center = p)
                }
            }
            } // end real-forecast branch
        }
    }
}


/**
 * The lockscreen's Now Playing widget (art, transport, like, history drawer, BPM/format badges).
 *
 * Extracted from [MikuKawaiiLockscreenScreen] 2026-09-13 for a concrete reason: that one composable
 * had grown to 18,785 instructions, past ART's JIT compiler limit, so it was REJECTED for compilation
 * ("Method exceeds compiler instruction limit") and ran interpreted on every recomposition — forever.
 * Keep the pieces of this screen small enough to compile; a single giant composable silently costs
 * far more than its line count suggests.
 */
@Composable
private fun LockscreenNowPlayingWidget(
    nowPlaying: MikuNowPlaying,
    controller: MikuMediaLink?,
    palette: com.miku.launcher.theme.MikuDiurnalTheme.DiurnalPalette,
    npAccentLock: com.miku.launcher.ui.NpAccent,
    localLikedState: Boolean,
    onLikedChange: (Boolean) -> Unit,
    showHistoryDrawer: Boolean,
    onHistoryDrawerChange: (Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Same font-scale damping the parent screen applies, so extracted text matches it exactly.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fontScale = density.fontScale
    val effectiveScale = 1.0f + (fontScale - 1.0f) * 0.15f
    fun Number.dampedSp(): androidx.compose.ui.unit.TextUnit = (this.toFloat() / fontScale * effectiveScale).sp
            val heartAnimScale = remember { Animatable(1f) }
            val historyList by MikuPlayHistoryStore.history.collectAsState()

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(CutCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xF00A2330), Color(0xF8051219), Color(0xFB020A0E))
                        )
                    )
                    .border(
                        1.2.dp,
                        Brush.horizontalGradient(
                            listOf(
                                if (localLikedState) Color(0xFFFF2277) else com.miku.launcher.ui.MikuNowPlayingAccent.blend(palette.primary, npAccentLock.full, if (npAccentLock.active) 0.3f else 0f).copy(alpha = 0.85f),
                                com.miku.launcher.ui.MikuNowPlayingAccent.blend(Color(0xFF39C5BB), npAccentLock.full, if (npAccentLock.active) 0.3f else 0f).copy(alpha = 0.5f),
                                MikuNeonPink.copy(alpha = 0.7f)
                            )
                        ),
                        CutCornerShape(14.dp)
                    )
                    .padding(10.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // 1. TOP HEADER: ARTWORK + TITLE + LIKE HEART + HISTORY TOGGLE
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Mini Vinyl Artwork Ring (Coil)
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF030D12))
                                .border(1.2.dp, if (localLikedState) Color(0xFFFF2277) else MikuNeonPink, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            // artwork is a fresh ByteArray/Uri object on EVERY controller poll (each
                            // progress tick), so Coil saw a new model and re-decoded -> visible
                            // flicker in time with the progress bar. Pin the model per track.
                            val artTrackKey = "${nowPlaying.mediaId}|${nowPlaying.title}|${nowPlaying.artist}|${nowPlaying.album}"
                            val stableArt = remember(artTrackKey) { mutableStateOf(nowPlaying.artwork) }
                            if (stableArt.value == null && nowPlaying.artwork != null) stableArt.value = nowPlaying.artwork
                            AsyncImage(
                                model = stableArt.value ?: R.drawable.miku_cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(34.dp).clip(CircleShape)
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // Track Title & Artist
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = nowPlaying.title ?: "Now Playing",
                                color = Color.White,
                                fontSize = 12.5.dampedSp(),
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                // Honest unknown — never an invented artist name.
                                text = nowPlaying.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
                                color = palette.primary,
                                fontSize = 10.5.dampedSp(),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Interactive Like Heart Button with Cyber Glow & Textured Haptics
                        Box(
                            Modifier
                                .scale(heartAnimScale.value)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    if (localLikedState) {
                                        Brush.radialGradient(
                                            listOf(Color(0x66FF1774), Color(0x28FF1774), Color(0xFF15040D))
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            listOf(Color(0x2800E5FF), Color(0x0C00E5FF))
                                        )
                                    }
                                )
                                .border(
                                    if (localLikedState) 1.2.dp else 0.8.dp,
                                    if (localLikedState) {
                                        Brush.linearGradient(
                                            listOf(Color(0xFFFF1774), Color(0xFFFF5288), Color(0xFFFF0055))
                                        )
                                    } else {
                                        Brush.linearGradient(
                                            listOf(Color(0x6600E5FF), Color(0x2200E5FF))
                                        )
                                    },
                                    CircleShape
                                )
                                .clickable {
                                    onInteraction()
                                    onLikedChange(!localLikedState)
                                    coroutineScope.launch {
                                        heartAnimScale.snapTo(0.65f)
                                        heartAnimScale.animateTo(1.35f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                        heartAnimScale.animateTo(1.0f, spring())
                                    }
                                    if (localLikedState) {
                                        com.miku.launcher.haptics.MikuHaptics.like(context)
                                    } else {
                                        com.miku.launcher.haptics.MikuHaptics.reject(context)
                                    }
                                    try {
                                        val i = Intent("com.miku.player.action.TOGGLE_LIKE").apply {
                                            setPackage("com.miku.player")
                                            nowPlaying.mediaId?.toLongOrNull()?.let { putExtra("track_id", it) }
                                        }
                                        context.sendBroadcast(i)
                                    } catch (_: Throwable) {}
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (localLikedState) Icons.Filled.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Like Song",
                                tint = if (localLikedState) Color(0xFFFF1774) else Color(0xFF00E5FF),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(Modifier.width(5.dp))

                        // History Drawer Pill Toggle
                        Box(
                            Modifier
                                .clip(CutCornerShape(6.dp))
                                .background(if (showHistoryDrawer) Color(0x5500E5FF) else Color(0x2200E5FF))
                                .border(0.8.dp, palette.primary.copy(alpha = 0.6f), CutCornerShape(6.dp))
                                .clickable {
                                    onInteraction()
                                    com.miku.launcher.haptics.MikuHaptics.tick(context)
                                    onHistoryDrawerChange(!showHistoryDrawer)
                                }
                                .padding(horizontal = 5.dp, vertical = 3.5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = "History",
                                    tint = palette.primary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(Modifier.width(2.5.dp))
                                Text(
                                    if (showHistoryDrawer) "HIDE" else "RECENTS",
                                    color = Color.White,
                                    fontSize = 9.5.dampedSp(),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                    }

                    // 2. TRACK METRICS TELEMETRY ROW
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quality Badge — only when the player published a real format string.
                        if (nowPlaying.format.isNotBlank()) Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x3300FF7F))
                                .border(0.6.dp, Color(0xFF00FF7F).copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                nowPlaying.format,
                                color = Color(0xFF00FF7F),
                                fontSize = 8.5.dampedSp(),
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                maxLines = 1
                            )
                        }

                        // Gamified Mini Project DIVA Rhythm Widget with Approach Rings & Haptics
                        var miniTapCount by remember { mutableIntStateOf(0) }
                        var miniTappedBpm by remember { mutableStateOf<Float?>(null) }
                        var miniJudgment by remember { mutableStateOf("TAP") }
                        val miniTapTimestamps = remember { mutableStateListOf<Long>() }
                        val miniTapAnimScale = remember { Animatable(1f) }
                        val miniApproachAnim = remember { Animatable(1.5f) }

                        val bpmDb = remember { com.miku.launcher.bpm.MikuBpmDatabase.getInstance(context) }
                        val hasTempo = nowPlaying.bpm in 40f..300f
                        val beatPeriodMs = if (hasTempo) (60_000f / nowPlaying.bpm).toLong() else 0L

                        // Continuous approach ring animation synced to BPM — only with a real tempo
                        LaunchedEffect(nowPlaying.isPlaying, nowPlaying.bpm) {
                            if (nowPlaying.isPlaying && hasTempo) {
                                while (true) {
                                    miniApproachAnim.snapTo(1.7f)
                                    miniApproachAnim.animateTo(
                                        targetValue = 1.0f,
                                        animationSpec = tween(
                                            durationMillis = beatPeriodMs.toInt().coerceIn(100, 2000),
                                            easing = LinearEasing
                                        )
                                    )
                                }
                            }
                        }

                        Box(
                            Modifier
                                .scale(miniTapAnimScale.value)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0x66FF1177), Color(0x449D4EDD))
                                    )
                                )
                                .border(0.8.dp, if (miniTapCount >= 10) Color(0xFFFFD700) else MikuNeonPink, RoundedCornerShape(6.dp))
                                .clickable {
                                    onInteraction()
                                    val now = SystemClock.elapsedRealtime()
                                    if (miniTapTimestamps.isNotEmpty() && now - miniTapTimestamps.last() > 2200L) {
                                        miniTapTimestamps.clear()
                                        miniTapCount = 0
                                    }
                                    miniTapTimestamps.add(now)
                                    miniTapCount++
                                    if (miniTapTimestamps.size > 8) miniTapTimestamps.removeAt(0)

                                    if (miniTapTimestamps.size >= 2) {
                                        val intervals = (1 until miniTapTimestamps.size).map { (miniTapTimestamps[it] - miniTapTimestamps[it - 1]).toDouble() }
                                        val avg = intervals.average()
                                        if (avg > 0) {
                                            val rawTapped = (60_000.0 / avg).toFloat().coerceIn(30f, 999f)
                                            miniTappedBpm = rawTapped
                                        }
                                    }

                                    // Rhythm Timing Offset & Accuracy Calculation.
                                    //
                                    // WAS: offsetMs = (elapsedRealtime % beatPeriod) - beatPeriod/2.
                                    // That is the phase relative to DEVICE BOOT — nothing in this
                                    // widget ever read a beat pulse or a playback position — so the
                                    // "💖 PERFECT / ✨ GOOD" judgment was a function of the boot
                                    // clock, not of the tap. Worse, with no tempo (every third-party
                                    // session, every un-analysed file) beatPeriodMs is 0, offsetMs
                                    // was 0, and EVERY tap scored PERFECT against a beat that did
                                    // not exist. Those fabricated judgments were then written into
                                    // the arcade grade, the lifetime PERFECTS badge and the tap
                                    // telemetry table that backs the displayed ACCURACY %.
                                    //
                                    // NOW: a judgment requires a real, recent beat pulse from the
                                    // detector, the offset is measured against THAT pulse, and it is
                                    // graded through the shared five-tier window model so the
                                    // lockscreen and the observatory can never disagree about what
                                    // "PERFECT" means.
                                    com.miku.launcher.bpm.MikuRhythmCalibration.init(context)
                                    val rhythmTier = com.miku.launcher.bpm.MikuBeatClickerEngine.rhythmTier.value
                                    val hitWindows = com.miku.launcher.bpm.MikuRhythmTiming.windowsFor(
                                        beatPeriodMs = beatPeriodMs,
                                        leniency = rhythmTier.leniency,
                                        bonusMs = com.miku.launcher.bpm.MikuBeatClickerEngine.timingWindowBonusMs
                                    )
                                    val offsetMs: Int? = com.miku.launcher.bpm.MikuRhythmTiming.signedOffsetToBeat(
                                        tapEpochMs = System.currentTimeMillis(),
                                        lastPulseEpochMs = com.miku.launcher.bpm.MikuBpmEngine.state.value.lastPulseEpochMs,
                                        beatPeriodMs = beatPeriodMs,
                                        calibrationMs = com.miku.launcher.bpm.MikuRhythmCalibration.offsetMs.value
                                    )
                                    val accuracy = offsetMs?.let {
                                        com.miku.launcher.bpm.MikuRhythmTiming.judge(it, hitWindows)
                                    }
                                    // Deadzone-aware display: a tap inside ~1 frame of the beat
                                    // reads as clean instead of being graded down or shown with
                                    // precision this panel cannot actually resolve.
                                    val shownOffsetMs = offsetMs?.let {
                                        com.miku.launcher.bpm.MikuRhythmTiming.displayOffsetMs(it, hitWindows)
                                    }
                                    miniJudgment = if (accuracy == null) {
                                        // No beat to judge against — the tap still counts as a tap.
                                        "🎵 FREE TAP"
                                    } else if (shownOffsetMs == 0) {
                                        "${accuracy.emoji} ${accuracy.label}"
                                    } else {
                                        // Teach the correction, not just the grade.
                                        "${accuracy.emoji} ${accuracy.label} ${if ((shownOffsetMs ?: 0) < 0) "◀" else "▶"}"
                                    }

                                    coroutineScope.launch {
                                        miniTapAnimScale.snapTo(0.78f)
                                        miniTapAnimScale.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                    }
                                    // Graded haptic: the judgment lands on the finger, and a
                                    // PERFECT feels different from an OK without looking down.
                                    com.miku.launcher.haptics.MikuHaptics.beat(context, accuracy?.hapticStrength ?: 0)
                                    val liveTapped = miniTappedBpm ?: nowPlaying.bpm

                                    // Play ascending pentatonic chime melody synchronized with combo
                                    com.miku.launcher.audio.MikuSeasonalAudioEngine.playComboMelody(miniTapCount, miniTapCount >= 20)

                                    // Record Beat Clicker & Seasons Economy — only a judgment that
                                    // was actually measured may feed the scored economy and the
                                    // accuracy statistics. A free tap gets the idle game's plain
                                    // base click and touches no statistic at all.
                                    if (accuracy != null && offsetMs != null) {
                                        com.miku.launcher.bpm.MikuRhythmCalibration.recordJudgedDeviation(offsetMs)
                                        com.miku.launcher.bpm.MikuBeatClickerEngine.tap(accuracy, liveTapped, rhythmTier.scoreScale)
                                        val comboNow = com.miku.launcher.bpm.MikuBeatClickerEngine.combo.value
                                        com.miku.launcher.bpm.MikuBpmSeasonsEngine.recordTap(
                                            accuracy = accuracy,
                                            currentCombo = comboNow,
                                            // Straight off the shared tier table, scaled by the
                                            // difficulty the player is actually being graded at.
                                            scoreEarned = ((accuracy.seasonPoints * 5L +
                                                comboNow * (if (accuracy.isGreatOrBetter) 15L else 5L)) * rhythmTier.scoreScale).toLong(),
                                            currentBpm = liveTapped
                                        )
                                    } else {
                                        com.miku.launcher.bpm.MikuBeatClickerEngine.freeTap()
                                    }

                                    // Log Calibration Telemetry to SQLite Database. A tap with no
                                    // beat reference is NOT logged: it would otherwise write a
                                    // fabricated deviationMs/accuracy into the table that backs the
                                    // displayed lifetime "ACCURACY %".
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val trackArt = nowPlaying.artist ?: "Unknown Artist"
                                        val trackTit = nowPlaying.title ?: "Unknown Track"
                                        if (offsetMs != null && accuracy != null) {
                                            bpmDb.logTapTelemetry(
                                                com.miku.launcher.bpm.MikuBpmDatabase.TapTelemetryRecord(
                                                    artist = trackArt,
                                                    title = trackTit,
                                                    tapEpochMs = System.currentTimeMillis(),
                                                    targetBeatMs = beatPeriodMs,
                                                    deviationMs = offsetMs,
                                                    accuracy = accuracy.name,
                                                    instantaneousBpm = liveTapped,
                                                    comboAtTap = miniTapCount,
                                                    isFever = miniTapCount >= 20
                                                )
                                            )
                                        }
                                        if (miniTapCount >= 6 && miniTappedBpm != null) {
                                            bpmDb.saveTrackBpm(
                                                com.miku.launcher.bpm.MikuBpmDatabase.TrackBpmRecord(
                                                    artist = trackArt,
                                                    title = trackTit,
                                                    canonicalBpm = miniTappedBpm!!,
                                                    rawDetectedBpm = nowPlaying.bpm,
                                                    userTappedBpm = miniTappedBpm!!,
                                                    // tempoMultiplier used to be 2.0f whenever
                                                    // nowPlaying.bpm was 0 (the common case), i.e.
                                                    // an octave claim out of nothing; it is only
                                                    // meaningful against a real detected tempo.
                                                    // Octave-aware and epsilon-tolerant (a detector
                                                    // estimate within a couple of BPM is the same
                                                    // tempo; half/double-time is a match with a
                                                    // known multiplier, not a disagreement).
                                                    tempoMultiplier = if (nowPlaying.bpm in 40f..300f)
                                                        (com.miku.launcher.bpm.MikuRhythmTiming
                                                            .octaveMultiplier(miniTappedBpm!!, nowPlaying.bpm) ?: 1.0f)
                                                    else 1.0f,
                                                    // Was a hardcoded 0.98 for a 6-tap tap-tempo.
                                                    // Nothing computed it, and the DB documents 0 as
                                                    // "no confidence was ever computed". Derive it
                                                    // from the actual spread of the user's taps.
                                                    confidence = run {
                                                        val iv = (1 until miniTapTimestamps.size)
                                                            .map { (miniTapTimestamps[it] - miniTapTimestamps[it - 1]).toDouble() }
                                                        if (iv.size < 2) 0f else {
                                                            val mean = iv.average()
                                                            if (mean <= 0.0) 0f else {
                                                                val sd = kotlin.math.sqrt(iv.sumOf { (it - mean) * (it - mean) } / iv.size)
                                                                (1.0 - (sd / mean)).coerceIn(0.0, 1.0).toFloat()
                                                            }
                                                        }
                                                    },
                                                    source = "USER_LOCKSCREEN_TAP",
                                                    tapCount = miniTapCount
                                                )
                                            )
                                        }
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (miniTapCount > 1) "🔥 x$miniTapCount $miniJudgment" else if (hasTempo) "⚡ ${nowPlaying.bpm.toInt()} BPM" else "⚡ — BPM",
                                    color = if (miniTapCount > 5) Color(0xFFFFD700) else MikuNeonPink,
                                    fontSize = 9.dampedSp(),
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                    }

                    // 3. SLIM SEEK BAR SLIDER & TIMESTAMPS
                    if (nowPlaying.durationMs > 0L) {
                        var scrubbing by remember { mutableStateOf(false) }
                        var scrubValue by remember { mutableFloatStateOf(0f) }
                        val liveFrac = (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
                        val posStr = formatTimeMs(if (scrubbing) (scrubValue * nowPlaying.durationMs).toLong() else nowPlaying.positionMs)
                        val durStr = formatTimeMs(nowPlaying.durationMs)

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                        ) {
                            Slider(
                                value = if (scrubbing) scrubValue else liveFrac,
                                onValueChange = {
                                    scrubbing = true
                                    scrubValue = it
                                    onInteraction()
                                },
                                onValueChangeFinished = {
                                    controller?.seekTo((scrubValue * nowPlaying.durationMs).toLong())
                                    scrubbing = false
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = if (localLikedState) Color(0xFFFF2277) else palette.primary,
                                    activeTrackColor = if (localLikedState) Color(0xFFFF2277) else palette.primary,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    posStr,
                                    color = Color(0xFFB0D0D8),
                                    fontSize = 8.5.dampedSp(),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                                Text(
                                    durStr,
                                    color = Color(0xFFB0D0D8),
                                    fontSize = 8.5.dampedSp(),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                    }

                    // 4. TRANSPORT CONTROLS ROW
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CutCornerShape(6.dp))
                                .background(Color(0x3300E5FF))
                                .mikuPressScale(pressedScale = 0.9f, glowColor = palette.primary, hapticFeedback = false)
                                .clickable {
                                    onInteraction()
                                    com.miku.launcher.haptics.MikuHaptics.tick(context)
                                    controller?.seekToPreviousMediaItem()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = palette.primary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            if (localLikedState) Color(0xFFFF2277) else palette.primary,
                                            if (localLikedState) Color(0xFFAA0044) else Color(0xFF0099AA)
                                        )
                                    )
                                )
                                .clickable {
                                    onInteraction()
                                    com.miku.launcher.haptics.MikuHaptics.tick(context)
                                    val c = controller
                                    if (c?.isPlaying == true) c.pause() else c?.play()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color(0xFF030D12),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CutCornerShape(6.dp))
                                .background(Color(0x3300E5FF))
                                .mikuPressScale(pressedScale = 0.9f, glowColor = palette.primary, hapticFeedback = false)
                                .clickable {
                                    onInteraction()
                                    com.miku.launcher.haptics.MikuHaptics.tick(context)
                                    controller?.seekToNextMediaItem()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = palette.primary, modifier = Modifier.size(16.dp))
                        }
                    }

                    // 5. EXPANDABLE PLAY HISTORY DRAWER
                    AnimatedVisibility(
                        visible = showHistoryDrawer,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xEE030D14))
                                .border(1.dp, palette.primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(6.dp)
                        ) {
                            Text(
                                "PLAYBACK HISTORY",
                                color = palette.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            Spacer(Modifier.height(4.dp))
                            if (historyList.isEmpty()) {
                                Text(
                                    "No recently played tracks",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            } else {
                                LazyColumn(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 120.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    items(historyList) { item ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0x33061C26))
                                                .clickable {
                                                    onInteraction()
                                                    com.miku.launcher.haptics.MikuHaptics.tick(context)
                                                    controller?.seekToNextMediaItem()
                                                }
                                                .padding(horizontal = 7.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    item.title,
                                                    color = Color.White,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    item.artist.ifBlank { "Unknown artist" },
                                                    color = Color(0xFF88BAC6),
                                                    fontSize = 12.sp,
                                                    maxLines = 1
                                                )
                                            }
                                            // Blank = the entry carries no recorded format; show
                                            // nothing rather than a claimed codec/bit-depth.
                                            if (item.format.isNotBlank()) {
                                                Text(
                                                    item.format.take(10),
                                                    color = Color(0xFF00FF7F),
                                                    fontSize = 11.sp,
                                                    fontFamily = AudiowideFont
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
}
