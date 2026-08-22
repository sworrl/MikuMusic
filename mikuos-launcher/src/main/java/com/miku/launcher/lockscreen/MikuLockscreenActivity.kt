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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
                    overridePendingTransition(0, android.R.anim.fade_out)
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
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val artwork: Any? = null,       // ByteArray or Uri — Coil consumes either
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

/**
 * Builds (and releases) a media3 [MediaController] bound to the music app's PlaybackService.
 * Returns a State that is null until connected, and reverts to null if the connection can't be
 * made (app not running) — callers must null-check and degrade gracefully.
 */
@Composable
private fun rememberMusicController(): State<MediaController?> {
    val context = LocalContext.current
    val controllerState = remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        try {
            val token = SessionToken(
                context,
                ComponentName("com.miku.player", "com.miku.player.PlaybackService")
            )
            val f = MediaController.Builder(context, token).buildAsync()
            future = f
            f.addListener({
                controllerState.value = try {
                    f.get()
                } catch (_: Throwable) {
                    null
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (_: Throwable) {
            controllerState.value = null
        }
        onDispose {
            controllerState.value = null
            try {
                future?.let { MediaController.releaseFuture(it) }
            } catch (_: Throwable) {}
        }
    }
    return controllerState
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
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }
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

    // Battery
    var batteryPercent by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bStatus = context.registerReceiver(null, ifilter)
            val level = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 100
            val scale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
            val status = bStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            batteryPercent = (level * 100 / scale.toFloat()).roundToInt()
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
    LaunchedEffect(controller) {
        val c = controller
        if (c == null) {
            nowPlaying = MikuNowPlaying()
            return@LaunchedEffect
        }
        while (true) {
            nowPlaying = try {
                val md = c.mediaMetadata
                val title = md.title?.toString()
                MikuNowPlaying(
                    hasSession = !title.isNullOrEmpty(),
                    title = title,
                    artist = (md.artist ?: md.albumArtist)?.toString(),
                    isPlaying = c.isPlaying,
                    artwork = md.artworkData ?: md.artworkUri,
                    positionMs = c.currentPosition.coerceAtLeast(0L),
                    durationMs = c.duration.coerceAtLeast(0L)
                )
            } catch (_: Throwable) {
                MikuNowPlaying()
            }
            delay(1000)
        }
    }

    val unlockThreshold = with(density) { -160.dp.toPx() }

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
                    onDragStart = { lastInteractionMs = System.currentTimeMillis() },
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
                            if (dragOffsetY.value < unlockThreshold) {
                                onUnlock()
                            } else {
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
                            val newY = (dragOffsetY.value + dragAmount).coerceAtMost(0f)
                            dragOffsetY.snapTo(newY)
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 10.dp, bottom = 12.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            val batteryColor = when {
                isCharging -> Color(0xFF00E676)
                batteryPercent > 60 -> palette.primary
                batteryPercent > 20 -> Color(0xFFFFD600)
                else -> Color(0xFFFF1744)
            }
            val batteryGradient = when {
                isCharging -> listOf(Color(0x5500E676), Color(0xFF04150E))
                batteryPercent > 60 -> listOf(Color(0xEE081F2A), Color(0xFF030D14))
                batteryPercent > 20 -> listOf(Color(0x55FFD600), Color(0xFF1E1704))
                else -> listOf(Color(0x66FF1744), Color(0xFF200508))
            }

            // Top telemetry bar
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: DAC Bitperfect Badge
                Box(
                    Modifier
                        .clip(CutCornerShape(6.dp))
                        .background(Color(0x9904121A))
                        .border(0.8.dp, palette.accent.copy(alpha = 0.7f), CutCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(palette.accent)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "CS43131",
                            color = palette.accent,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }

                // Right: Brain Sentinel + Volume Badge + Battery Badge — sewn into the SAME quilt
                // patch used on the home header, so the two top bars are normalized/identical.
                Row(
                    modifier = Modifier.quiltPatch(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .clip(CutCornerShape(6.dp))
                            .background(Color(0x9904121A))
                            .border(0.8.dp, Color(0xFF00FF7F).copy(alpha = 0.7f), CutCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00FF7F))
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "BRAIN",
                                color = Color(0xFF00FF7F),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                        }
                    }

                    CyberVolumeBadge(
                        onClick = { MikuVolumeManager.triggerHud(context) }
                    )

                    Box(
                        Modifier
                            .clip(CutCornerShape(8.dp))
                            .background(Brush.horizontalGradient(batteryGradient))
                            .border(1.dp, batteryColor, CutCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isCharging) Icons.Default.Bolt else Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = batteryColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$batteryPercent%",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Clock & Date
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                MikuLockscreenMegaClock(time = currentTime)

                Spacer(Modifier.height(4.dp))

                Box(
                    Modifier
                        .clip(CutCornerShape(8.dp))
                        .background(Color(0xAA071A24))
                        .border(1.dp, palette.primary.copy(alpha = 0.7f), CutCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "⟦ $currentDate ⟧",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Weather capsule
            Miku5HourLockscreenTrendCapsule(
                weather = weather,
                gps = gps,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(6.dp))

            // Now Playing card (only when a session with a real title is connected)
            if (nowPlaying.hasSession) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(CutCornerShape(12.dp))
                        .background(Color(0xDD09202A))
                        .border(1.dp, palette.primary.copy(alpha = 0.8f), CutCornerShape(12.dp))
                        .padding(8.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
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
                                    .border(1.dp, MikuNeonPink, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = nowPlaying.artwork ?: R.drawable.miku_cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(34.dp).clip(CircleShape)
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = nowPlaying.title ?: "Now Playing",
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = nowPlaying.artist ?: "Hatsune Miku",
                                    color = palette.primary,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Transport buttons via MediaController
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CutCornerShape(6.dp))
                                        .background(Color(0x3300E5FF))
                                        .clickable {
                                            lastInteractionMs = System.currentTimeMillis()
                                            controller?.seekToPreviousMediaItem()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = palette.primary, modifier = Modifier.size(15.dp))
                                }
                                Spacer(Modifier.width(5.dp))
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(palette.primary)
                                        .clickable {
                                            lastInteractionMs = System.currentTimeMillis()
                                            val c = controller
                                            if (c?.isPlaying == true) c.pause() else c?.play()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color(0xFF030D12),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(5.dp))
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CutCornerShape(6.dp))
                                        .background(Color(0x3300E5FF))
                                        .clickable {
                                            lastInteractionMs = System.currentTimeMillis()
                                            controller?.seekToNextMediaItem()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = palette.primary, modifier = Modifier.size(15.dp))
                                }
                            }
                        }

                        // Slim seek bar (goes through the MediaController)
                        if (nowPlaying.durationMs > 0L) {
                            var scrubbing by remember { mutableStateOf(false) }
                            var scrubValue by remember { mutableFloatStateOf(0f) }
                            val liveFrac = (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
                            Slider(
                                value = if (scrubbing) scrubValue else liveFrac,
                                onValueChange = {
                                    scrubbing = true
                                    scrubValue = it
                                    lastInteractionMs = System.currentTimeMillis()
                                },
                                onValueChangeFinished = {
                                    controller?.seekTo((scrubValue * nowPlaying.durationMs).toLong())
                                    scrubbing = false
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = palette.primary,
                                    activeTrackColor = palette.primary,
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                            )
                        }
                    }
                }
            }

            // Large gap so the now-playing card floats well above the bottom edge / swipe zone
            // instead of hugging the unlock hint (which stays pinned at the very bottom).
            Spacer(Modifier.height(96.dp))

            // Swipe-up prompt
            val infiniteTransition = rememberInfiniteTransition(label = "ChevronBounce")
            val chevronOffset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Chevron"
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
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
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "SWIPE UP TO UNLOCK",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 1.5.sp
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
                Triple(0.50f, 0.50f, Color(0xFFFFD600))
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

    val next5Hours = remember(weather.hourlyMeteogram, weather.tempF) {
        if (weather.hourlyMeteogram.isNotEmpty()) {
            weather.hourlyMeteogram.take(5)
        } else {
            val cal = Calendar.getInstance()
            val currentH = cal.get(Calendar.HOUR_OF_DAY)
            (0..4).map { i ->
                val h = (currentH + i) % 24
                val isDay = h in 6..19
                val (sum, ic) = MikuWeatherService.mapWeatherCode(weather.code, isDay)
                MikuWeatherService.HourlyMeteogramPoint(
                    timeLabel = if (i == 0) "Now" else String.format(Locale.US, "%02d:00", h),
                    tempF = weather.tempF - (i * 1.2f),
                    precipProbPct = (weather.precipitationProbPct - i * 3).coerceIn(0, 100),
                    summary = sum,
                    icon = ic,
                    isDay = isDay
                )
            }
        }
    }

    Box(
        modifier = modifier
            .clip(CutCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xEE061C26),
                        Color(0xF6020A10)
                    )
                )
            )
            .border(1.dp, palette.primary.copy(alpha = 0.8f), CutCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        weather.icon.ifEmpty { "🌸" },
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${weather.tempF.roundToInt()}°F",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        weather.summary,
                        color = palette.primary,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                if (weather.nextPrecipLabel.isNotEmpty()) {
                    Box(
                        Modifier
                            .clip(CutCornerShape(4.dp))
                            .background(Color(0x33FFD600))
                            .border(0.6.dp, Color(0xFFFFD600), CutCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            weather.nextPrecipLabel,
                            color = Color(0xFFFFD600),
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                    }
                }
            }

            Spacer(Modifier.height(5.dp))

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
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = pt.icon,
                            fontSize = 10.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${pt.tempF.roundToInt()}°",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        if (pt.precipProbPct > 0) {
                            Text(
                                text = "${pt.precipProbPct}%",
                                color = Color(0xFF00E5FF),
                                fontSize = 6.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(5.dp))

            // Color-coded trending sparkline
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
            ) {
                val w = size.width
                val h = size.height
                val temps = next5Hours.map { it.tempF }
                val minT = temps.minOrNull() ?: 60f
                val maxT = temps.maxOrNull() ?: 80f
                val range = (maxT - minT).coerceAtLeast(3f)

                val pointsOffset = next5Hours.mapIndexed { idx, pt ->
                    val x = (idx.toFloat() / (next5Hours.size - 1).coerceAtLeast(1)) * w
                    val y = h - ((pt.tempF - minT) / range * (h - 6.dp.toPx())) - 3.dp.toPx()
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
                            Color(0xFF76FF03),
                            Color(0xFFFFD600),
                            MikuNeonPink
                        )
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )

                pointsOffset.forEach { p ->
                    drawCircle(Color(0xFF04121A), radius = 2.5.dp.toPx(), center = p)
                    drawCircle(MikuCyan, radius = 1.5.dp.toPx(), center = p)
                }
            }
        }
    }
}
