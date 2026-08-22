package com.miku.player

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale

private val MikuColors = darkColorScheme(
    primary = MikuTeal, onPrimary = Color(0xFF00201D), secondary = MikuPink,
    background = Ground, surface = Surface1, onBackground = Color(0xFFE8F4F2), onSurface = Color(0xFFE8F4F2),
)

// Every M3 type-scale role mapped to the bundled Miku font kit instead of the default Typography()
// falling back to the platform's stock system font wherever a Text() doesn't set its own
// fontFamily explicitly — which was most of the app's body/list text. Reuses the same fonts
// already reserved for these exact roles (see Fonts.kt): Audiowide for the biggest brand
// moments, Righteous for headers, Baloo 2 as the readable UI/body workhorse, Orbitron for
// labels/metrics (matches the existing DataChip numeric badges). Sizes/line-heights/spacing are
// kept at Material3's own defaults — only the typeface changes.
private val MikuTypography = Typography().let { d ->
    d.copy(
        displayLarge = d.displayLarge.copy(fontFamily = AudiowideFont),
        displayMedium = d.displayMedium.copy(fontFamily = AudiowideFont),
        displaySmall = d.displaySmall.copy(fontFamily = AudiowideFont),
        headlineLarge = d.headlineLarge.copy(fontFamily = RighteousFont),
        headlineMedium = d.headlineMedium.copy(fontFamily = RighteousFont),
        headlineSmall = d.headlineSmall.copy(fontFamily = RighteousFont),
        titleLarge = d.titleLarge.copy(fontFamily = RighteousFont),
        titleMedium = d.titleMedium.copy(fontFamily = RighteousFont),
        titleSmall = d.titleSmall.copy(fontFamily = RighteousFont),
        bodyLarge = d.bodyLarge.copy(fontFamily = Baloo2Font),
        bodyMedium = d.bodyMedium.copy(fontFamily = Baloo2Font),
        bodySmall = d.bodySmall.copy(fontFamily = Baloo2Font),
        labelLarge = d.labelLarge.copy(fontFamily = OrbitronFont),
        labelMedium = d.labelMedium.copy(fontFamily = OrbitronFont),
        labelSmall = d.labelSmall.copy(fontFamily = OrbitronFont),
    )
}

/**
 * Shared "frosted glass" card material for static surfaces — translucent tinted fill, soft drop
 * shadow, thin top-lit specular border. Rows/tiles/chips don't have meaningful content directly
 * behind them to blur through (they ARE the content, over a flat page background), so they get
 * this consistent glass-look MATERIAL instead of literal backdrop blur — Header/TabBar and the
 * detail hero panels use real Haze blur separately, since scrolling content/art actually passes
 * behind those.
 */
fun Modifier.glassCard(corner: androidx.compose.ui.unit.Dp = 14.dp, tint: Color = MikuTeal): Modifier = this
    .clip(RoundedCornerShape(corner))
    .drawBehind {
        val rr = CornerRadius(corner.toPx(), corner.toPx())
        drawRoundRect(Color(0x33000000), topLeft = Offset(0f, 1.6.dp.toPx()), size = size, cornerRadius = rr)
        drawRoundRect(
            Brush.verticalGradient(listOf(tint.copy(alpha = 0.14f), Color(0xFF0A2022).copy(alpha = 0.5f))),
            cornerRadius = rr
        )
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0x3AFFFFFF), Color(0x00FFFFFF)), endY = size.height * 0.55f),
            cornerRadius = rr, style = Stroke(1.dp.toPx())
        )
    }

// Order here is the visual tab-bar order (Home, Artists, Albums, Library) — Songs is no longer
// its own top-level tab (merged into Library, reachable via an "All Songs" row there), but the
// enum value stays for the existing `tab == Tab.SONGS -> SongList(...)` route; TabBar filters it
// out of what it actually renders rather than the enum controlling bar order/membership directly.
private enum class Tab(val label: String) { HOME("Home"), ARTISTS("Artists"), ALBUMS("Albums"), GENRES("Library"), SONGS("Songs") }

/** Observable "what's playing" state so any track row can show a live indicator without prop-drilling. */
object NowPlayingState {
    var currentId by mutableStateOf(-1L)
    var playing by mutableStateOf(false)
}


private val ALBUM_ART_URI = android.net.Uri.parse("content://media/external/audio/albumart")

/** MediaItem carrying full metadata + album art so the lockscreen/notification shows real info.
 *  Non-private (used by [[AlarmRingService]] too, to build the alarm's playback queue). */
fun mediaItemFor(t: Track): MediaItem {
    val meta = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(t.title)
        .setArtist(t.artist)
        .setAlbumTitle(t.album)
        .setArtworkUri(if (t.albumId > 0) ContentUris.withAppendedId(ALBUM_ART_URI, t.albumId) else null)
        .build()
    return MediaItem.Builder()
        .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id))
        .setMediaId(t.id.toString())
        .setMediaMetadata(meta)
        .build()
}

object QueueManager {
    fun playNext(context: Context, player: ExoPlayer, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val items = tracks.map { mediaItemFor(it) }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items, 0, 0L)
            player.prepare()
            player.play()
        } else {
            val nextIndex = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
            player.addMediaItems(nextIndex, items)
        }
        val msg = if (tracks.size == 1) "✓ Playing next: ${tracks[0].title}" else "✓ ${tracks.size} tracks added to play next"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        Haptics.tick(context)
        UpdateManager.saveQueueSnapshot(context, player)
    }

    fun playLast(context: Context, player: ExoPlayer, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val items = tracks.map { mediaItemFor(it) }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items, 0, 0L)
            player.prepare()
            player.play()
        } else {
            player.addMediaItems(items)
        }
        val msg = if (tracks.size == 1) "✓ Added to queue: ${tracks[0].title}" else "✓ ${tracks.size} tracks added to queue"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        Haptics.tick(context)
        UpdateManager.saveQueueSnapshot(context, player)
    }
}

private const val LIKED_KEY = " LIKED"

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var sustainedPerfListener: androidx.media3.common.Player.Listener? = null
    // Dev workflow today, future in-app OTA installer tomorrow: whoever is about to trigger an
    // actual package install broadcasts ACTION_UPDATE_STARTING first, so the overlay + full-queue
    // save happen BEFORE the process dies, not after — see UpdateHandler.kt's doc comment.
    private val updateStartingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            UpdateManager.beginGracefulUpdate(context, player, intent.getStringExtra(UpdateManager.EXTRA_TARGET_VERSION) ?: "")
        }
    }

    // Restores the real system screen-off timeout the INSTANT the display actually goes dark
    // (ScreenOffHelper shrinks it to force an early sleep at the end of the idle pipeline) — not
    // waiting for the app to be reopened. Without this, the shrunk timeout would keep applying
    // system-wide (every app, not just this one) until next launch. ACTION_SCREEN_OFF is a
    // protected broadcast — only deliverable to a dynamically-registered receiver, never manifest.
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            ScreenOffHelper.restore(context)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        try {
            overridePendingTransition(R.anim.magic_lamp_expand, R.anim.magic_lamp_fade_out)
        } catch (_: Throwable) {}
        if (!isSupportedDevice()) {
            setContent { MaterialTheme(colorScheme = MikuColors, typography = MikuTypography) { UnsupportedDeviceScreen() } }
            return
        }
        if (intent?.getBooleanExtra(UpdateManager.EXTRA_JUST_UPDATED, false) == true) {
            UpdateOverlay.mode.value = UpdateOverlayMode.RESUMING
        }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        player = PlayerHolder.ensure(this)
        PlayerHolder.ensureSession(this)                                  // branded lockscreen/notification control
        try {
            com.miku.player.api.MikuApiServer.start(this)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to start MikuApiServer", e)
        }
        val updateFilter = android.content.IntentFilter(UpdateManager.ACTION_UPDATE_STARTING)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateStartingReceiver, updateFilter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(updateStartingReceiver, updateFilter)
        }
        registerReceiver(screenOffReceiver, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
        AlarmScheduler.rescheduleAll(this)                                 // re-arm exact alarms every launch (idempotent; also covers "somehow got cleared")
        // Tell the platform to hold a stable, un-throttled CPU/GPU clock state (no governor
        // hunting/lag) exactly while we're actually busy — playing audio, which is also when the
        // projectM visualizer is doing its heaviest continuous GL work. This is a hint, not a
        // wake lock: it's automatically inert the instant this Window isn't visible (screen off,
        // backgrounded), so idle/screen-off keeps normal Android power behavior (deep sleep, no
        // CPU pegging) — see the GLSurfaceView lifecycle fix in ProjectMVisualizerView for the
        // other half of that. Toggled purely off player.isPlaying so "paused while browsing" also
        // falls back to normal scaling instead of needlessly holding a high clock state.
        window.setSustainedPerformanceMode(player.isPlaying)
        sustainedPerfListener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                window.setSustainedPerformanceMode(isPlaying)
            }
        }.also { player.addListener(it) }
        LibraryDaemonService.start(this)
        // Wire app-wide hardware/UX managers once at startup (previously implemented but never started).
        try { MikuPocketLockManager.init(this) } catch (t: Throwable) { android.util.Log.e("MainActivity", "MikuPocketLockManager.init failed", t) }
        try { com.miku.player.volume.MikuVolumeManager.init(this) } catch (t: Throwable) { android.util.Log.e("MainActivity", "MikuVolumeManager.init failed", t) }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        // NOTE: do NOT set FLAG_FULLSCREEN here — it force-hides the status bar, which makes a
        // swipe from the top edge reveal only a transient bar instead of pulling down the system
        // notification shade / quick settings. We keep the layout edge-to-edge (NO_LIMITS + the
        // transparent status bar set above) but leave the status bar itself available.
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            // Re-hide only the navigation bar when it reappears; the status bar stays available so
            // the notification shade can be pulled down from the top edge.
            if ((visibility and android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                hideSystemBars()
            }
        }
        hideSystemBars()
        setContent {
            MaterialTheme(colorScheme = MikuColors, typography = MikuTypography) {
                var granted by remember { mutableStateOf(hasAudioPermission()) }
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result -> granted = result[audioPermission()] ?: hasAudioPermission() }
                LaunchedEffect(Unit) { launcher.launch(permissionsToRequest()) }
                Surface(color = Ground, modifier = Modifier.fillMaxSize()) {
                    if (granted) {
                        var refresh by remember { mutableStateOf(0) }
                        // LibraryScanService now drives the actual scan (see rescan()'s doc
                        // comment) independently of whether this Activity is even in the
                        // foreground, so the UI can't rely solely on a completion callback out of
                        // scan code anymore — it polls ScanProgress.generation instead, the same
                        // counter the service bumps on every live-progress refresh and on finish.
                        var lastSeenGen by remember { mutableStateOf(ScanProgress.generation.get()) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                val g = ScanProgress.generation.get()
                                if (g != lastSeenGen) { lastSeenGen = g; refresh++ }
                                delay(500)
                            }
                        }
                        // Instant binary cache for 0ms cold start (<10ms for 20k tracks)
                        val cachedTracks = remember { FastLibraryStore.loadSync(this@MainActivity) ?: emptyList() }
                        var hasLoadedOnce by remember { mutableStateOf(cachedTracks.isNotEmpty()) }
                        val tracks by produceState(initialValue = cachedTracks, refresh) {
                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { queryTracks() }
                            hasLoadedOnce = true
                            if (value.isEmpty() || result.size != value.size || result.firstOrNull()?.id != value.firstOrNull()?.id || result.lastOrNull()?.id != value.lastOrNull()?.id) {
                                value = result
                                FastLibraryStore.saveAsync(this@MainActivity, result)
                            }
                        }
                        IdleWatcher()
                        val idleTier = IdleController.tier
                        LaunchedEffect(idleTier) { applyIdleBrightness(window, idleTier) }
                        Box(Modifier.fillMaxSize()) {
                            App(tracks, player, loading = !hasLoadedOnce, onScan = { rescan {} }) { list, i -> play(list, i) }
                            if (idleTier == IdleTier.AMBIENT || idleTier == IdleTier.OFF) AmbientOverlay(player, Modifier.fillMaxSize())
                            UpdateOverlayScreen(Modifier.fillMaxSize())
                        }
                    } else PermissionPrompt { launcher.launch(permissionsToRequest()) }
                }
            }
        }
    }

    // Player/session lifecycle is owned by PlayerHolder + PlaybackService (so playback and the
    // lockscreen control survive the Activity); don't release the player here. DO remove our own
    // listener though — PlayerHolder.player outlives this Activity, so leaving it attached would
    // leak this (and every future recreated) Activity/Window and keep firing setSustainedPerformanceMode
    // against a dead window.
    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        try {
            overridePendingTransition(R.anim.magic_lamp_fade_in, R.anim.magic_lamp_collapse)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        sustainedPerfListener?.let { player.removeListener(it) }
        runCatching { unregisterReceiver(updateStartingReceiver) }
        runCatching { unregisterReceiver(screenOffReceiver) }
        // Safety net alongside onPause below — never leave every core pinned at max clock if the
        // process is going away, root or not (RootShell/CpuPerformance no-op cleanly without root).
        kotlinx.coroutines.MainScope().launch { CpuPerformance.onBackground(this@MainActivity) }
        super.onDestroy()
    }

    // Releases the CPU-performance pin (if the root-gated Settings toggle has it on) the instant
    // the app leaves the foreground — pinning every core at max clock while backgrounded is pure
    // battery/thermal waste with zero benefit. onResume below reapplies it if still enabled.
    override fun onPause() {
        super.onPause()
        lifecycleScope.launch { CpuPerformance.onBackground(this@MainActivity) }
        lifecycleScope.launch(Dispatchers.IO) { TrackTech.flushPending() }
    }

    // Real system memory pressure, confirmed live: lowmemorykiller reaping this whole process
    // (audio playback included) rather than negotiating — "critical pressure, device is low on
    // memory" while the projectM visualizer alone was tracking ~185MB of native/GL/EGL memory.
    // React BEFORE that happens: tear the GL surface down ourselves so the OS doesn't have to kill
    // the process to reclaim it. RUNNING_LOW/RUNNING_CRITICAL are level 10/15 (foreground process
    // trim scale) — exactly the range this device was hitting while actively being looked at.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            VisualizerMemoryGuard.suppress()
        }
    }

    private fun hideSystemBars() {
        // Draw edge-to-edge behind TRANSPARENT system bars, but DO NOT hide the
        // navigation bar. On this device the OS uses gesture navigation (mode 2),
        // whose swipe-back / swipe-up-home live in the navigation-bar inset region —
        // hiding that bar (the old IMMERSIVE_STICKY + hide(navigationBars())) removed
        // all OS navigation from Miku Music (user couldn't get back/home). Keep the
        // bars present (they're just invisible gesture areas in gesture mode) so nav
        // works, while content still renders full-bleed via setDecorFitsSystemWindows(false).
        try {
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        VisualizerMemoryGuard.release()
        IdleController.loadPrefs(this)
        IdleController.poke(this)
        // Defensive, redundant with screenOffReceiver: if the process was killed/crashed between
        // ScreenOffHelper shrinking the timeout and the screen actually turning off, this is the
        // self-heal — never leave the user's real system screen-off timeout wrong indefinitely.
        ScreenOffHelper.restore(this)
        // Reapply the CPU-performance pin if the user has it on (onPause released it while
        // backgrounded); no-op without root either way.
        lifecycleScope.launch {
            CpuPerformance.applyIfEnabled(this@MainActivity)
            CpuPerformance.restoreIfStranded(this@MainActivity)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Fires for ANY user input system-wide — touches, key presses, trackball, the physical
    // transport keys — without needing to wire a listener into every single composable/screen.
    // Exactly the "was the user just here" signal the idle dim/ambient timers need.
    override fun onUserInteraction() {
        super.onUserInteraction()
        IdleController.poke(this)
    }

    // Hardware transport keys (M500 side buttons / headset)
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        IdleController.poke(this)
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                if (player.isPlaying) player.pause() else player.play()
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                player.play()
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                player.pause()
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                player.seekToNextMediaItem()
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                player.seekToPreviousMediaItem()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isSupportedDevice(): Boolean {
        val realHardware = android.os.Build.MANUFACTURER.equals("HiBy", ignoreCase = true) &&
            android.os.Build.MODEL.contains("M500", ignoreCase = true)
        // Emulator/VM screen — a different field set than PlayerHolder's own VM check on purpose.
        val product = android.os.Build.PRODUCT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val looksVirtual = product.contains("sdk") || product.contains("vbox") ||
            model.contains("emulator") || model.contains("android sdk built for")
        return realHardware && !looksVirtual
    }

    private fun play(list: List<Track>, index: Int) {
        ensurePlaybackService()
        // Every caller here passes an EXPLICIT, deliberately-ordered list (an album in disc order,
        // a search result, a pre-shuffled list from the Shuffle button, etc.) — ExoPlayer's own
        // shuffleModeEnabled reordering the timeline on top of that is never correct, only ever a
        // stale leftover from a PREVIOUS session's shuffle toggle. Confirmed live: exactly this —
        // shuffle left on from an earlier NowPlaying/Tape session got silently re-applied by those
        // screens' own "reapply persisted shuffle" effect (now removed), so tapping a track inside
        // an album view could jump to a completely unrelated next track. Album-style playback is
        // meant to run front-to-back like a CD/vinyl side — force it off on every explicit play.
        player.shuffleModeEnabled = false
        PlayerPreferences.saveShuffle(this, false)
        player.setMediaItems(list.map { mediaItemFor(it) }, index, 0L)
        player.prepare(); player.play()
        PlayerPreferences.saveQueue(this, list.map { it.id }, index)
        list.getOrNull(index)?.let { PlayerPreferences.saveLastPlayback(this, it.id, 0L) }
    }

    private fun ensurePlaybackService() {
        try {
            PlayerHolder.ensureSession(this)
            // Foreground start + a live MediaController connection. Plain startService() left the
            // service un-promoted, so the session/notification died on screen-off/background and
            // hardware+BT media keys only worked with the app foregrounded and the screen on.
            PlayerHolder.ensureControllerConnected(this)
        } catch (_: Throwable) {}
    }

    /** Force a MediaStore rescan of every storage volume, then refresh the query. */
    /**
     * Full library rescan — now a real foreground service ([[LibraryScanService]]), not a plain
     * background Thread owned by this Activity. Confirmed live (user report, 2026-08-17): a bare
     * Thread with no foreground service or wake lock is exactly what Android's background-
     * execution limits target — it would stall/silently stop making progress once the screen went
     * to sleep or the app left the foreground, with zero error surfaced. This just triggers the
     * service and gets out of the way; the walk/tag-read/pipelining logic itself is unchanged,
     * only WHERE it runs changed. [[ScanProgress]] stays the shared state ScannerPill/
     * ScanProgressDialog already poll — the `onDone` callback here is no longer how the UI learns
     * to refresh (see App()'s ScanProgress.generation poll below); it's kept only so existing call
     * sites that want an immediate "trigger acknowledged" tick still get one.
     */
    private fun rescan(onDone: () -> Unit) {
        if (ScanProgress.active) { onDone(); return }
        ContextCompat.startForegroundService(this, android.content.Intent(this, LibraryScanService::class.java))
        onDone()
    }

    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    /** Read-audio (gates the library) + RECORD_AUDIO (the visualizer) + notifications + video. */
    private fun permissionsToRequest(): Array<String> {
        val p = mutableListOf(audioPermission(), Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.POST_NOTIFICATIONS)
            p.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        return p.toTypedArray()
    }

    private fun queryTracks(): List<Track> {
        val out = ArrayList<Track>()
        val hasBitrate = Build.VERSION.SDK_INT >= 30
        val proj = arrayListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DATE_ADDED,
        ).apply { if (hasBitrate) add(MediaStore.Audio.Media.BITRATE) }.toTypedArray()
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iS = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iM = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iPath = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val iYear = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val iAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val iTrackNo = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val iAlbumArtist = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val iDateAdded = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val iBr = if (hasBitrate) c.getColumnIndex(MediaStore.Audio.Media.BITRATE) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val rawTrackNo = if (iTrackNo >= 0 && !c.isNull(iTrackNo)) c.getInt(iTrackNo) else 0
                var parsedTrackNo = if (rawTrackNo >= 1000) rawTrackNo % 1000 else rawTrackNo
                val path = if (iPath >= 0 && !c.isNull(iPath)) c.getString(iPath) ?: "" else ""

                // MediaStore rows can outlive the actual file — deletes/moves done outside the
                // scanner (adb, PC file transfer, the ingest pipeline replacing a release) leave a
                // ghost row behind until the next full media rescan notices. Art can still resolve
                // for a ghost row (thumbnail cache, or a shared album id with a surviving track),
                // which is exactly the "art but no media" bug this skips: art belongs to the media,
                // never the other way around — no readable file, no track, full stop. Only verified
                // via File I/O on primary storage though — see mediaStoreRowLikelyValid()'s doc
                // comment for why blindly applying this to SD-card paths was mass-dropping a real
                // library down to a fraction of its actual size.
                if (!mediaStoreRowLikelyValid(path)) continue

                // 1. Fallback to local persistent database
                if (parsedTrackNo <= 0) {
                    parsedTrackNo = PlayerPreferences.loadTrackNumber(this@MainActivity, id)
                }

                // 2. Infer from leading filename digits if missing (e.g., "01 - Track.flac", "04. Title.mp3")
                if (parsedTrackNo <= 0 && path.isNotBlank()) {
                    val fname = java.io.File(path).nameWithoutExtension
                    val match = Regex("^(\\d{1,3})[\\s.\\-_]").find(fname)
                    val inferred = match?.groupValues?.get(1)?.toIntOrNull()
                    if (inferred != null && inferred > 0) {
                        parsedTrackNo = inferred
                    }
                }

                // 3. Save into local persistent database
                if (parsedTrackNo > 0) {
                    PlayerPreferences.saveTrackNumber(this@MainActivity, id, parsedTrackNo)
                }

                val albumArtist = if (iAlbumArtist >= 0 && !c.isNull(iAlbumArtist)) c.getString(iAlbumArtist) ?: "" else ""
                out.add(Track(
                    id, c.getString(iT) ?: "Unknown", c.getString(iA) ?: "Unknown artist",
                    c.getString(iAl) ?: "", c.getLong(iD), c.getLong(iS),
                    if (iBr >= 0 && !c.isNull(iBr)) c.getInt(iBr) / 1000 else 0, c.getString(iM) ?: "",
                    path,
                    if (iYear >= 0 && !c.isNull(iYear)) c.getInt(iYear) else 0,
                    if (iAlbumId >= 0 && !c.isNull(iAlbumId)) c.getLong(iAlbumId) else 0L,
                    parsedTrackNo,
                    albumArtist,
                    if (iDateAdded >= 0 && !c.isNull(iDateAdded)) c.getLong(iDateAdded) else 0L
                ))
            }
        }
        PlayerPreferences.flushTrackNumbers(this) // one batched write for every saveTrackNumber() call made during this pass, not one per track
        return out
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun App(tracks: List<Track>, player: ExoPlayer, loading: Boolean = false, onScan: () -> Unit, onPlay: (List<Track>, Int) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { LikeStore.init(ctx) }
    val savedTabName = remember { PlayerPreferences.loadTab(ctx) }
    var tab by remember { mutableStateOf(runCatching { Tab.valueOf(savedTabName) }.getOrDefault(Tab.HOME)) }
    var artistSel by remember { mutableStateOf<ArtistGroup?>(null) }
    var albumSel by remember { mutableStateOf<AlbumGroup?>(null) }
    var libSel by remember { mutableStateOf<String?>(null) }
    var showVideoLibrary by remember { mutableStateOf(false) }
    var videoSel by remember { mutableStateOf<VideoItem?>(null) }
    val videos by produceState(initialValue = emptyList<VideoItem>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { queryVideos(ctx) }
    }

    val homeListState = rememberLazyListState()
    val songsListState = rememberLazyListState()
    val artistsListState = rememberLazyListState()
    val albumsGridState = rememberLazyGridState()
    val libraryListState = rememberLazyListState()
    val artistDetailListState = rememberLazyListState()

    var currentTrack by remember { mutableStateOf<Track?>(null) }
    // Views are sticky: relaunching drops you back into whatever you were looking at last
    // (tape deck / full now playing / the lists), as soon as the track restores.
    val initialTape = (ctx as? MainActivity)?.intent?.getBooleanExtra("open_tape_mode", false) == true
    var showFullNowPlaying by remember { mutableStateOf(false) }
    var showTape by remember { mutableStateOf(initialTape) }
    var showSettings by remember { mutableStateOf(false) }
    // Tape mode is a full-bleed deck skin — the Android status bar (clock, icons, etc.) sitting on
    // top of it reads as a stray overlay rather than part of the "device". Go immersive for as
    // long as tape mode is up, restore the system bars the moment it closes.
    LaunchedEffect(showTape) {
        val window = (ctx as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (showTape) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    val appScope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var historyTick by remember { mutableStateOf(0) }

    // Warm the art cache for the whole library the moment it's known: first few hundred decode
    // straight into memory, the rest materialize as lossless WebP on disk — art is instant
    // everywhere, never extracted-on-scroll.
    LaunchedEffect(tracks) { if (tracks.isNotEmpty()) AlbumArtCache.prewarm(ctx, tracks) }

    var sortIgnoreThe by remember { mutableStateOf(PlayerPreferences.loadSortIgnoreThe(ctx)) }
    var displayTheMode by remember { mutableStateOf(PlayerPreferences.loadDisplayTheMode(ctx)) }

    // Group once per library change or preference update — off the main thread. This used to run
    // synchronously inside `remember{}` during composition; grouping+sorting ~3000 tracks with the
    // full normalization pipeline (regex passes, Unicode NFD folding) was slow enough there to trip
    // Android's ANR watchdog and make the app fail to launch outright on a real library. Keep the
    // previous list showing while a recompute is in flight rather than flashing empty.
    // `loading` (passed in) only covers the FIRST gap — before queryTracks() itself has resolved
    // at all. There's a SECOND gap after that: tracks is non-empty but this grouping pass hasn't
    // finished yet (real time on a big library — canonicalArtistKey's regex/NFD pipeline per
    // track), during which artistGroups/albumGroups are still the initial emptyList(). That
    // second gap is what was still flashing "NO ARTISTS/ALBUMS FOUND" even after the first fix —
    // confirmed live, not theoretical. hasGroupedOnce tracks "grouping has produced a real result
    // for the CURRENT non-empty tracks at least once", separately from hasAlbumGroupedOnce since
    // they're two independent LaunchedEffects that can finish at different times.
    var hasGroupedOnce by remember { mutableStateOf(false) }
    var artistGroups by remember { mutableStateOf(emptyList<ArtistGroup>()) }
    LaunchedEffect(tracks, sortIgnoreThe, displayTheMode) {
        // Defense in depth on top of the Normalizer fixes in Model.kt (root cause of the
        // live-confirmed "Crystal Method missing from Artists" bug: one bad artist string could
        // throw uncaught out of this whole eager grouping pass, silently freezing artistGroups on
        // a stale result forever with no error surfaced anywhere). Even with those fixed, nothing
        // else in this chain should be able to take the whole library's grouping down with it —
        // catch, log, and keep the previous result rather than get permanently stuck either way.
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                tracks.artists(ctx, ignoreThe = sortIgnoreThe, displayMode = displayTheMode)
            }
        }.onSuccess { artistGroups = it; hasGroupedOnce = true }
         .onFailure { android.util.Log.e("MikuPlayer", "artist grouping failed, keeping previous result", it) }
    }
    var hasAlbumGroupedOnce by remember { mutableStateOf(false) }
    var albumGroups by remember { mutableStateOf(emptyList<AlbumGroup>()) }
    LaunchedEffect(tracks, sortIgnoreThe) {
        val result = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                tracks.albums(ctx, ignoreThe = sortIgnoreThe)
            }
        }.onFailure { android.util.Log.e("MikuPlayer", "album grouping failed, keeping previous result", it) }
         .getOrNull() ?: return@LaunchedEffect
        albumGroups = result
        hasAlbumGroupedOnce = true
    }
    val recentlyPlayed = remember(tracks, historyTick) {
        val byId = tracks.associateBy { it.id }
        PlayerPreferences.loadHistory(ctx).mapNotNull { byId[it] }.take(30)
    }

    // Restore last played track & position on launch. Keyed on `tracks` because it needs a
    // non-empty library to work with — but a library rescan ALSO changes `tracks` (even one that
    // finds nothing new can return a differently-ordered list), which used to re-run this whole
    // block on every rescan and could yank live playback back to a stale saved track/position.
    // Split accordingly: the re-sync branch below is read-only (safe to re-run any number of
    // times), the actual restore-from-prefs branch is guarded to run exactly once per process via
    // PlayerHolder.sessionRestored — see its definition for the full story.
    LaunchedEffect(tracks) {
        if (tracks.isEmpty()) return@LaunchedEffect
        if (player.mediaItemCount > 0) {
            // Activity was recreated (rotation/config change) while a queue was live — reflect the
            // current item, DON'T rebuild the queue (that would replace the album with the whole
            // library and seek backwards). Read-only: never touches playback, safe every time.
            if (currentTrack == null) {
                val id = player.currentMediaItem?.mediaId?.toLongOrNull()
                currentTrack = tracks.find { it.id == id }
            }
            return@LaunchedEffect
        }
        if (PlayerHolder.sessionRestored) return@LaunchedEffect
        if (currentTrack == null) {
            PlayerHolder.markSessionRestored()
            val lastPos = PlayerPreferences.loadLastPositionMs(ctx)
            val lastTrackId = PlayerPreferences.loadLastTrackId(ctx)
            val queueIds = PlayerPreferences.loadQueueIds(ctx)
            val byId = if (queueIds.isNotEmpty()) tracks.associateBy { it.id } else emptyMap()
            val restoredQueue = queueIds.mapNotNull { byId[it] }

            if (restoredQueue.isNotEmpty()) {
                // Find exact index of the last track in the queue, falling back to saved index
                val queueIdx = PlayerPreferences.loadQueueIndex(ctx).coerceIn(0, restoredQueue.size - 1)
                val targetIdx = if (lastTrackId > 0) {
                    val matchingIdx = restoredQueue.indexOfFirst { it.id == lastTrackId }
                    if (matchingIdx >= 0) matchingIdx else queueIdx
                } else queueIdx

                val restoredTrack = restoredQueue[targetIdx]
                currentTrack = restoredTrack
                val safePos = if (lastPos in 0..(restoredTrack.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) lastPos else 0L
                player.setMediaItems(restoredQueue.map { mediaItemFor(it) }, targetIdx, safePos)
                player.prepare()
                // Restore queue + position ready-to-play but PAUSED. Auto-calling play() here
                // meant every process restart (any app reinstall/update, a cached-process kill)
                // resumed audio unprompted — the "music starts randomly on update" glitch.
                // Pressing play continues exactly where it left off.
            } else {
                val exact = tracks.find { it.id == lastTrackId } ?: tracks.firstOrNull()
                if (exact != null) {
                    currentTrack = exact
                    val idx = tracks.indexOf(exact).coerceAtLeast(0)
                    val safePos = if (lastPos in 0..(exact.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) lastPos else 0L
                    player.setMediaItems(tracks.map { mediaItemFor(it) }, idx, safePos)
                    player.prepare()
                    // Restore ready-to-play but PAUSED — see note above; never auto-resume on
                    // a process restart, which is what an app update looks like from here.
                }
            }
            UpdateOverlay.mode.value = UpdateOverlayMode.NONE
        }
    }

    // Persist exact track & timestamp continuously while playing
    LaunchedEffect(currentTrack, isPlaying) {
        while (isPlaying && currentTrack != null) {
            PlayerPreferences.saveLastPlayback(ctx, currentTrack!!.id, player.currentPosition)
            PlayerPreferences.saveQueueIndex(ctx, player.currentMediaItemIndex)
            kotlinx.coroutines.delay(1000)
        }
    }

    // The listener below is registered ONCE (keyed on player) but must always see the CURRENT
    // library — capturing the `tracks` parameter directly froze the first (empty) list in the
    // closure, so transitions stopped resolving to Tracks and Now Playing never updated live.
    val tracksNow by rememberUpdatedState(tracks)
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) {
                isPlaying = p
                PlayerPreferences.saveWasPlaying(ctx, p)
                if (currentTrack != null) {
                    PlayerPreferences.saveLastPlayback(ctx, currentTrack!!.id, player.currentPosition)
                    PlayerPreferences.saveQueueIndex(ctx, player.currentMediaItemIndex)
                }
                // Root-gated, no-op without root/opt-in — see PulsarLight's doc comment.
                kotlinx.coroutines.MainScope().launch { PulsarLight.updateForPlayback(ctx, currentTrack, p) }
            }
            override fun onEvents(pl: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) {
                    val id = pl.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                    val itemIdx = pl.currentMediaItemIndex
                    PlayerPreferences.saveQueueIndex(ctx, itemIdx)
                    if (currentTrack?.id != id) {
                        tracksNow.find { it.id == id }?.let {
                            currentTrack = it
                            PlayerPreferences.saveLastPlayback(ctx, it.id, pl.currentPosition)
                            PlayerPreferences.recordPlay(ctx, it.id, System.currentTimeMillis())   // history + play count
                            historyTick++
                            kotlinx.coroutines.MainScope().launch { PulsarLight.updateForPlayback(ctx, it, pl.isPlaying) }
                        }
                    }
                }
            }
        }
        isPlaying = player.isPlaying   // seed from ongoing playback (e.g. after Activity recreation)
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // Broadcast the current track + play state to the list rows' now-playing indicator.
    LaunchedEffect(currentTrack?.id, isPlaying) {
        NowPlayingState.currentId = currentTrack?.id ?: -1L
        NowPlayingState.playing = isPlaying
    }

    // Location: a single coarse fix, only once a track has actually been playing ~6s (skips/seeks
    // don't trigger it), so it costs almost nothing. Cancelled automatically if the track changes.
    LaunchedEffect(currentTrack?.id) {
        val t = currentTrack ?: return@LaunchedEffect
        kotlinx.coroutines.delay(6000)
        if (currentTrack?.id == t.id && isPlaying) LocationLogger.logForTrack(ctx, t.id, System.currentTimeMillis())
    }

    // Screen orientation is locked strictly to PORTRAIT mode across the entire app lifecycle.
    // Tape Mode renders rotated 90° CW in portrait mode so the user holds the DAP sideways while Android OS stays in portrait.

    fun startPlay(list: List<Track>, i: Int) {
        currentTrack = list[i]
        PlayerPreferences.saveLastPlayback(ctx, list[i].id, 0L)
        onPlay(list, i)
    }

    val openArtistByName: (String) -> Unit = { name ->
        // Match by the SAME canonical key the Artists tab groups by, not a raw exact-string
        // compare — a tap can arrive with a raw per-track artist string (e.g. from an album
        // header, a track's metadata line) that differs from whatever display name the group
        // ended up with (casing, "The" placement, a collab string that only canonicalizes down
        // to the real artist). An exact-equals match would miss the real group and fall through
        // to a synthetic one built from a raw-string filter that has the exact same problem.
        val nameKey = canonicalArtistKey(name, ctx)
        val found = artistGroups.find { canonicalArtistKey(it.name, ctx) == nameKey }
            ?: ArtistGroup(name, tracks.filter { canonicalArtistKey(normalizeArtistName(it.artist), ctx) == nameKey })
        artistSel = found
        albumSel = null
        libSel = null
        showFullNowPlaying = false
        showTape = false
    }

    // Mirrors openArtistByName above, keyed on canonicalAlbumKey instead — album name alone isn't
    // unique (confirmed live: two different albums can format to the identical display string,
    // see the Gorillaz LazyGrid-crash fix), so this needs BOTH artist and album, not just a title.
    val openAlbumByName: (String, String) -> Unit = { artist, album ->
        val albumKey = canonicalAlbumKey(artist, album, ctx)
        val found = albumGroups.find { canonicalAlbumKey(it.artist, it.name, ctx) == albumKey }
            ?: AlbumGroup(album, artist, tracks.filter { canonicalAlbumKey(it.albumArtist.ifBlank { it.artist }, it.album, ctx) == albumKey })
        albumSel = found
        artistSel = null
        libSel = null
        showFullNowPlaying = false
        showTape = false
    }

    var showScanDialog by remember { mutableStateOf(false) }
    var showMikuMonitorDialog by remember { mutableStateOf(false) }

    // Pop the same priority chain the `when` below already renders in (deepest overlay first),
    // one level at a time — shared by the system-back handler and the bottom-bar back glyph so
    // there's exactly one definition of "what does back mean right now".
    val canGoBack = showSettings || showTape || showFullNowPlaying || videoSel != null || albumSel != null || artistSel != null || libSel != null || tab == Tab.SONGS
    val performBack: () -> Unit = {
        when {
            showSettings -> showSettings = false
            showTape -> showTape = false
            showFullNowPlaying -> showFullNowPlaying = false
            videoSel != null -> videoSel = null
            albumSel != null -> albumSel = null
            artistSel != null -> artistSel = null
            libSel != null -> libSel = null
            // All Songs isn't its own bar tab anymore — it's reached from Library, so back from it
            // returns to Library rather than falling through to app-exit.
            tab == Tab.SONGS -> tab = Tab.GENRES
        }
    }
    // System back — hardware key AND the Pixel-style edge-swipe gesture — previously fell straight
    // through to the Activity default (minimize/exit) from every one of these nested screens, since
    // navigation here is plain state, not a NavController with its own back stack.
    // (showScanDialog isn't listed — Compose's Dialog already intercepts back on its own.)
    androidx.activity.compose.BackHandler(enabled = canGoBack, onBack = performBack)
    androidx.activity.compose.BackHandler(enabled = !canGoBack) {
        val act = ctx as? android.app.Activity
        if (act != null) {
            if (!act.moveTaskToBack(false)) {
                act.finish()
            }
        }
    }

    // Header + tab bar now float over the scrolling content as a real frosted-glass panel
    // (genuine Haze backdrop blur of whatever's currently scrolled underneath them) instead of
    // sitting in normal document flow above it — the authentic "Pixel glass app bar" look needs
    // content to actually pass behind the bar for the blur to show anything. Content gets top
    // padding equal to the bar's own measured height (it changes — the tab row only shows on the
    // top-level tabs, not in Artist/Album detail) so nothing starts out hidden underneath it.
    val hazeState = remember { HazeState() }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).haze(hazeState).padding(top = with(density) { headerHeightPx.toDp() })) {
                when {
                    albumSel != null -> DetailList(albumSel!!.name, albumSel!!.tracks, { albumSel = null }, ::startPlay, likeAlbum = albumSel!!.name, onOpenArtist = openArtistByName)
                    artistSel != null -> ArtistDetail(artistSel!!, { artistSel = null }, { albumSel = it }, ::startPlay, listState = artistDetailListState)
                    libSel != null -> {
                        val isLiked = libSel == LIKED_KEY
                        val libTracks = if (isLiked) LikeStore.resolveLiked(ctx, tracks)
                                        else { val ids = PlayerPreferences.loadPlaylists(ctx)[libSel]?.toSet() ?: emptySet(); tracks.filter { it.id in ids } }
                        DetailList(if (isLiked) "Liked Songs" else libSel!!, libTracks, { libSel = null }, ::startPlay, onOpenArtist = openArtistByName)
                    }
                    showVideoLibrary -> VideoLibraryScreen(videos, onOpen = { videoSel = it }, onClose = { showVideoLibrary = false })
                    tab == Tab.HOME -> HomeScreen(
                        tracks, recentlyPlayed, { tab = Tab.ARTISTS }, ::startPlay, listState = homeListState,
                        albumGroups = albumGroups, artistGroups = artistGroups,
                        onOpenAlbum = { albumSel = it }, onOpenArtist = openArtistByName
                    )
                    tab == Tab.SONGS -> SongList(tracks, sortIgnoreThe = sortIgnoreThe, onPlay = ::startPlay, listState = songsListState)
                    tab == Tab.ARTISTS -> ArtistList(
                        artists = artistGroups,
                        loading = loading || (tracks.isNotEmpty() && !hasGroupedOnce),
                        sortIgnoreThe = sortIgnoreThe,
                        displayTheMode = displayTheMode,
                        onUpdateSort = { ignore ->
                            sortIgnoreThe = ignore
                            PlayerPreferences.saveSortIgnoreThe(ctx, ignore)
                        },
                        onUpdateDisplay = { mode ->
                            displayTheMode = mode
                            PlayerPreferences.saveDisplayTheMode(ctx, mode)
                        },
                        onOpen = { artistSel = it },
                        onShuffle = { if (it.tracks.isNotEmpty()) startPlay(it.tracks.shuffled(), 0) },
                        listState = artistsListState
                    )
                    tab == Tab.ALBUMS -> AlbumGrid(albumGroups, loading = loading || (tracks.isNotEmpty() && !hasAlbumGroupedOnce), onOpen = { albumSel = it }, onShuffle = { if (it.tracks.isNotEmpty()) startPlay(it.tracks.shuffled(), 0) }, gridState = albumsGridState)
                    tab == Tab.GENRES -> LibraryScreen(
                        ctx, listState = libraryListState, trackCount = tracks.size, tracks = tracks,
                        artistCount = artistGroups.size, albumCount = albumGroups.size,
                        loading = loading, videoCount = videos.size,
                        onAllSongs = { tab = Tab.SONGS },
                        onVideos = { showVideoLibrary = true }
                    ) { libSel = it }
                }
            }
            if (currentTrack != null) NowPlayingBar(
                track = currentTrack!!,
                player = player,
                isPlaying = isPlaying,
                onBarClick = {
                    showTape = false
                    showFullNowPlaying = true
                },
                onToggle = { if (player.isPlaying) player.pause() else player.play() },
                onArtistClick = openArtistByName,
                onAlbumClick = openAlbumByName
            )

            // Bottom gesture handle: swiping up from bottom returns home on ANY system (stock M500 or MikuOS)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(Unit) {
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = { totalY = 0f },
                            onDragEnd = {
                                if (totalY < -20f) {
                                    val act = ctx as? android.app.Activity
                                    if (act != null) {
                                        if (!act.moveTaskToBack(false)) {
                                            act.finish()
                                        }
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalY += dragAmount.y
                            }
                        )
                    }
            )
        }
        // Top gesture zone: swiping down from top edge pulls down notification shade / quick settings
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(Unit) {
                    var totalY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalY = 0f },
                        onDragEnd = {
                            if (totalY > 15f) {
                                expandNotificationShade(ctx)
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            if (dragAmount > 0) {
                                totalY += dragAmount
                                if (totalY > 20f) {
                                    change.consume()
                                    expandNotificationShade(ctx)
                                    totalY = 0f
                                }
                            }
                        }
                    )
                }
        )
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .onSizeChanged { headerHeightPx = it.height }
                .hazeChild(state = hazeState, style = HazeMaterials.thick(Ground))
        ) {
            Header(
                count = tracks.size,
                onScan = {
                    if (ScanProgress.active) {
                        showScanDialog = true
                    } else {
                        showMikuMonitorDialog = true
                    }
                },
                onTape = {
                    showFullNowPlaying = false
                    if (currentTrack == null && tracks.isNotEmpty()) {
                        startPlay(tracks, 0)
                    }
                    showTape = true
                },
                onSettings = { showSettings = true },
                onHome = {
                    val act = ctx as? android.app.Activity
                    if (act != null) {
                        if (!act.moveTaskToBack(false)) {
                            act.finish()
                        }
                    }
                },
                canGoBack = canGoBack,
                onBack = performBack
            )
            if (artistSel == null && albumSel == null) TabBar(tab) {
                tab = it
                PlayerPreferences.saveTab(ctx, it.name)
                artistSel = null
                albumSel = null
            }
        }
        if (showSettings) {
            SettingsScreen(ctx = ctx, tracks = tracks, onClose = { showSettings = false })
        }
        if (showFullNowPlaying && currentTrack != null && !showTape) {
            NowPlayingScreen(track = currentTrack!!, player = player, onClose = { showFullNowPlaying = false },
                onTape = { showTape = true }, tracks = tracks, onOpenArtist = openArtistByName, onOpenAlbum = openAlbumByName)
        }
        if (showTape && currentTrack != null) {
            TapeScreen(track = currentTrack!!, player = player, onExit = { showTape = false })
        }
        if (videoSel != null) {
            VideoPlayerScreen(video = videoSel!!, musicPlayer = player, onClose = { videoSel = null })
        }
        if (showMikuMonitorDialog) {
            MikuMonitorModal(
                onDismissRequest = { showMikuMonitorDialog = false }
            )
        }
        if (showScanDialog) {
            ScanProgressDialog(hazeState = hazeState, onDismissRequest = { showScanDialog = false })
        }
        // Persistent, unobtrusive version tag — now the bottom-RIGHT corner (was sharing the
        // left corner with the back glyph, which crowded a control right next to plain status
        // text). Hidden entirely in tape mode along with the back glyph below — tape mode is its
        // own full-bleed deck UI with its own exit control, this corner strip doesn't belong there.
        if (!showTape) {
            val infinitePulse = rememberInfiniteTransition(label = "verPulse")
            val verColor by infinitePulse.animateColor(
                initialValue = Color(0x9989ACA7),
                targetValue = MikuTealBright.copy(alpha = 0.85f),
                animationSpec = infiniteRepeatable(
                    animation = tween(2500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "verColor"
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = verColor,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 2.dp)
            )
        }
        var showSplash by remember { mutableStateOf(!hasShownColdBootSplash) }
        if (showSplash) {
            MikuAnimatedBootSplash(onFinished = {
                hasShownColdBootSplash = true
                showSplash = false
            })
        }

        FnTouchGuard()
    }
}

private var hasShownColdBootSplash = false

@Composable
private fun MikuAnimatedBootSplash(onFinished: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var currentPose by remember { mutableStateOf(1) } // 1: Cheering Welcome, 2: Peace Sign Wink

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(700, easing = LinearOutSlowInEasing),
        label = "splashAlpha",
        finishedListener = { if (it == 0f) onFinished() }
    )

    // Breathing pulse scale for the background artwork
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scalePulse"
    )

    // Animated particle drift offset
    val particleY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleDrift"
    )

    // Pulsing neon equalizer bars
    val eqBar1 by infiniteTransition.animateFloat(0.3f, 0.9f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "eq1")
    val eqBar2 by infiniteTransition.animateFloat(0.85f, 0.2f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "eq2")
    val eqBar3 by infiniteTransition.animateFloat(0.4f, 1.0f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "eq3")
    val eqBar4 by infiniteTransition.animateFloat(0.9f, 0.35f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "eq4")
    val eqBar5 by infiniteTransition.animateFloat(0.25f, 0.88f, infiniteRepeatable(tween(580), RepeatMode.Reverse), label = "eq5")

    LaunchedEffect(Unit) {
        delay(1100)
        currentPose = 2 // Keyframe transition to Peace Sign Wink pose!
        delay(1600)
        visible = false // Smooth fade out
    }

    if (alpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alpha)
                .background(Color(0xFF041416))
        ) {
            // Fullscreen Bespoke Kawaii Miku Artwork with Crossfade Pose Animation
            Crossfade(
                targetState = currentPose,
                animationSpec = tween(600, easing = FastOutSlowInEasing),
                label = "poseCrossfade"
            ) { pose ->
                Image(
                    painter = painterResource(
                        id = if (pose == 1) R.drawable.miku_boot_splash else R.drawable.miku_boot_splash_pose2
                    ),
                    contentDescription = "Miku Boot Splash Pose",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(scale)
                )
            }

            // Dark gradient overlay for text legibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x77041416),
                                Color(0x11041416),
                                Color(0x88041416),
                                Color(0xF5041416)
                            )
                        )
                    )
            )

            // Animated drifting sparkles, hearts & neon musical note icons
            Box(Modifier.fillMaxSize()) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MikuTealBright.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 28.dp)
                        .offset(y = particleY.dp)
                        .size(30.dp)
                )
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MikuPink.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 36.dp)
                        .offset(y = (particleY * 1.3f).dp)
                        .size(28.dp)
                )
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MikuGold.copy(alpha = 0.8f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 90.dp, end = 50.dp)
                        .offset(y = (particleY * 0.8f).dp)
                        .size(24.dp)
                )
            }

            // Bottom Kawaii Branding & Pulsing Equalizer Loading Bar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 36.dp)
            ) {
                // Neon Pulsing Title Card
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xE60A2B2E), Color(0xE61C081A))))
                        .border(1.5.dp, Brush.horizontalGradient(listOf(MikuTealBright, MikuPink)), RoundedCornerShape(20.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "MIKU MUSIC PLAYER",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (currentPose == 1) "初音ミク · AUDIO ENGINE ACTIVE" else "✌️ KAWAII MODE MAX · READY! ✌️",
                            color = if (currentPose == 1) MikuTealBright else MikuPink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Prominent Large Glanceable Version Number for DAP Startup
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = MikuTealBright,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    letterSpacing = 3.sp
                )

                Spacer(Modifier.height(14.dp))

                // Kawaii Animated Equalizer Wave
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.height(24.dp)
                ) {
                    listOf(eqBar1, eqBar2, eqBar3, eqBar4, eqBar5).forEachIndexed { index, heightFactor ->
                        Box(
                            modifier = Modifier
                                .width(5.dp)
                                .fillMaxHeight(heightFactor)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (index % 2 == 0) MikuTealBright else MikuPink)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Wide "triple chevron" back glyph — three "<" arrowheads of different sizes, heavily overlapped
 * around a shared center so they read as ONE bold, wide arrow with depth/motion to it, not three
 * separate back-icons sitting in a row. The two outer chevrons are smaller and fixed teal, fanned
 * out vertically behind the center one; the center chevron is bigger and carries the live pulsing
 * color, drawn last so it sits on top and anchors the whole shape.
 */
@Composable private fun TripleChevronBackGlyph(centerColor: Color, outerColor: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun chevron(cx: Float, cy: Float, armW: Float, armH: Float, strokeW: Float, color: Color) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx + armW, cy - armH)
                lineTo(cx - armW, cy)
                lineTo(cx + armW, cy + armH)
            }
            drawPath(
                path, color,
                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        val strokeW = h * 0.22f
        // Outer pair — smaller, fixed teal, fanned above/below and trailing right of center to
        // give the whole glyph width and a sense of layered motion.
        chevron(w * 0.66f, h * 0.22f, w * 0.30f, h * 0.30f, strokeW * 0.75f, outerColor.copy(alpha = 0.85f))
        chevron(w * 0.66f, h * 0.78f, w * 0.30f, h * 0.30f, strokeW * 0.75f, outerColor.copy(alpha = 0.85f))
        // Center — larger, the live pulsing color, drawn on top so it reads as the "main" arrow.
        chevron(w * 0.56f, h * 0.5f, w * 0.42f, h * 0.46f, strokeW, centerColor)
    }
}

/**
 * In-app pocket lock workaround: watches the M500's Fn toggle (Settings.Global "fn_status") and,
 * while it's on, swallows touch events within this app.
 * NOTE: This is a temporary application-level workaround. The true fix is the system-level OS mod
 * to HiBy's AOSP framework (services.jar / PhoneWindowManager), which will natively intercept and
 * block BOTH touch events AND physical keys system-wide across all apps.
 */
@Composable private fun FnTouchGuard() {
    val ctx = LocalContext.current
    var fnLocked by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val cr = ctx.contentResolver
        fun read() = try { android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1 } catch (_: Throwable) { false }
        fnLocked = read()
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { fnLocked = read() }
        }
        try { cr.registerContentObserver(android.provider.Settings.Global.getUriFor("fn_status"), false, obs) } catch (_: Throwable) {}
        onDispose { try { cr.unregisterContentObserver(obs) } catch (_: Throwable) {} }
    }
    // HiBy's handler sometimes writes fn_status through a path the observer never hears about, so
    // unlocking looked laggy. While LOCKED we poll every 150ms (a Settings read is sub-ms cheap):
    // release is now effectively instant; a lazy 1s poll while unlocked catches silent locks too.
    LaunchedEffect(fnLocked) {
        val cr = ctx.contentResolver
        while (true) {
            kotlinx.coroutines.delay(if (fnLocked) 150 else 1000)
            val v = try { android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1 } catch (_: Throwable) { false }
            if (v != fnLocked) fnLocked = v
        }
    }
    // Ephemeral fullscreen glow on every lock/unlock TRANSITION — the lock instantly swallows
    // every touch with only a small top pill as explanation, which from the user's seat looks
    // exactly like the app hung. A loud, momentary, self-fading glow right when it happens (and
    // again when it releases) makes it read as "the device just did something" instead of
    // "the app died". Skips the very first composition so app launch itself doesn't flash.
    val flash = remember { Animatable(0f) }
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(fnLocked) {
        if (isFirstComposition) { isFirstComposition = false; return@LaunchedEffect }
        flash.snapTo(1f)
        flash.animateTo(0f, animationSpec = tween(1400, easing = FastOutSlowInEasing))
    }
    if (flash.value > 0f) {
        val glowColor = if (fnLocked) MikuPink else MikuTealBright
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = flash.value }
                .drawBehind {
                    val w = size.width; val h = size.height
                    val edge = 110.dp.toPx()
                    drawRect(Brush.verticalGradient(listOf(glowColor.copy(alpha = 0.6f), Color.Transparent), startY = 0f, endY = edge))
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, glowColor.copy(alpha = 0.6f)), startY = h - edge, endY = h),
                        topLeft = Offset(0f, h - edge), size = Size(w, edge)
                    )
                    drawRect(Brush.horizontalGradient(listOf(glowColor.copy(alpha = 0.6f), Color.Transparent), startX = 0f, endX = edge))
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, glowColor.copy(alpha = 0.6f)), startX = w - edge, endX = w),
                        topLeft = Offset(w - edge, 0f), size = Size(edge, h)
                    )
                }
        ) {
            // Center caption rides along with the glow so the FIRST lock (before anyone's learned
            // the pill) is unambiguous — fades out with everything else, not a persistent nag.
            Row(
                Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp)).background(Color(0xCC061417))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (fnLocked) Icons.Default.Lock else Icons.Default.LockOpen, null, tint = glowColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (fnLocked) "Fn LOCK ENGAGED" else "Fn LOCK RELEASED",
                    color = glowColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
        }
    }
    if (fnLocked) {
        val cr = ctx.contentResolver
        val isKernelLock = PlayerPreferences.loadRootEnabled(ctx) &&
            (android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock") == "touch_and_key_lock"
        if (!isKernelLock) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
                }
            ) {
                Row(
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Color(0xB3061417))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, null, tint = MikuTeal, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fn LOCK", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
            }
        }
    }
}

@Composable private fun ScannerPill(
    count: Int,
    onScan: () -> Unit
) {
    var deltaBadge by remember { mutableStateOf<Int?>(null) }

    // Poll the cross-thread ScanProgress instead of a fake fixed-duration spinner — the walk
    // phase alone can run from under a second (nothing new) to minutes (a freshly-dropped 2TB
    // card), so the UI has to reflect the REAL state, not a guessed timeout.
    var isScanning by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var visited by remember { mutableIntStateOf(0) }
    var newFound by remember { mutableIntStateOf(0) }
    var tagsScanned by remember { mutableIntStateOf(0) }
    var tagsTotal by remember { mutableIntStateOf(0) }
    var wasScanning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            isScanning = ScanProgress.active
            phase = ScanProgress.phase
            visited = ScanProgress.visited.get()
            newFound = ScanProgress.newFound.get()
            tagsScanned = ScanProgress.tagsScanned.get()
            tagsTotal = ScanProgress.tagsTotal
            // Show the "+N" result badge exactly once, on the falling edge of an actual scan —
            // driven by ScanProgress's own authoritative resultDelta, not by watching the raw
            // library `count` drift (that badge used to key off `count` directly, which could
            // change for reasons that have nothing to do with a scan — e.g. an external ingest
            // pipeline dropping files onto the card between our own scans — and a plain library-
            // reload afterward. That read as "stuck showing +3397 forever": the LaunchedEffect kept
            // restarting its 4s dismiss timer every time `count` so much as twitched, so it never
            // got a clean run to actually clear).
            if (wasScanning && !isScanning && ScanProgress.resultDelta != 0) {
                deltaBadge = ScanProgress.resultDelta
            }
            wasScanning = isScanning
            // Header (and this pill) stay mounted for the app's entire foreground lifetime — a
            // fixed 120ms poll ran continuously even on a totally idle screen, burning CPU/battery
            // for no reason. Only tighten to 120ms while a scan is actually active (or juuust
            // finished, so the falling-edge +N badge above still lands within one tick); back off
            // to 1.5s otherwise. The ~1.4s worst-case delay before noticing a fresh scan start is
            // imperceptible — scans are only ever started by the user's own tap on this pill.
            delay(if (isScanning) 120L else 1500L)
        }
    }
    LaunchedEffect(deltaBadge) {
        if (deltaBadge != null) { delay(4000); deltaBadge = null }
    }

    val rotationTransition = rememberInfiniteTransition(label = "spin")
    val spinAngle by rotationTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinAngle"
    )

    val auraAlpha by rotationTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auraAlpha"
    )

    // Same 3D-embossed-key language as HapticIconButton/DataChip: raised face lit from the top +
    // drop shadow at rest, springy scale + inverted sunk lighting while held, haptic tick on tap
    // — every other control in the app already gets this, the scan pill was the odd one out.
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(), label = "scanPillScale")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hapticCtx = androidx.compose.ui.platform.LocalContext.current

    val syncState by MikuSyncTransceiver.state.collectAsState()

    // Compact by default ("N Tracks"); while a scan is running AND its modal has been dismissed,
    // show a live abbreviated status here. Also displays live rsync ingress speeds when actively syncing.
    fun abbrev(n: Int): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f).removeSuffix(".0M") + "M"
        n >= 10_000 -> "${n / 1000}k"
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000f).removeSuffix(".0k") + "k"
        else -> n.toString()
    }
    val statusText = when {
        syncState.isTransferring -> "⚡ ${String.format(java.util.Locale.US, "%.1f", syncState.transferRateMBs)} MB/s"
        !isScanning -> "${abbrev(count)} Tracks"
        phase == "Reading tags…" && tagsTotal > 0 -> "${abbrev(tagsScanned)}/${abbrev(tagsTotal)} tagged"
        else -> "${abbrev(visited)} chk · $newFound new"
    }

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(RoundedCornerShape(20.dp))
            .drawBehind {
                val rr = CornerRadius(20.dp.toPx(), 20.dp.toPx())
                val topC = if (isScanning) Color(0xFF1C5A57) else Color(0xFF123634)
                val botC = if (isScanning) Color(0xFF4A1638) else Color(0xFF1C0D18)
                if (!pressed) {
                    drawRoundRect(Color(0x66000000), topLeft = Offset(0f, 2.2f), size = size, cornerRadius = rr)
                    drawRoundRect(Brush.verticalGradient(listOf(topC, botC)), cornerRadius = rr)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0x73FFFFFF), Color(0x00FFFFFF)), endY = size.height * 0.6f),
                        cornerRadius = rr, style = Stroke(1.2f)
                    )
                } else {
                    drawRoundRect(Brush.verticalGradient(listOf(botC, topC)), cornerRadius = rr)
                    drawRoundRect(Color(0x73000000), topLeft = Offset(1f, 1f),
                        size = androidx.compose.ui.geometry.Size(size.width - 2f, size.height - 2f),
                        cornerRadius = rr, style = Stroke(2.2f))
                }
                drawRoundRect(
                    Brush.horizontalGradient(
                        if (isScanning) listOf(MikuTealBright.copy(alpha = auraAlpha), MikuPink.copy(alpha = auraAlpha))
                        else listOf(MikuTeal.copy(alpha = 0.6f), MikuPink.copy(alpha = 0.4f))
                    ),
                    cornerRadius = rr, style = Stroke(1.2.dp.toPx())
                )
            }
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                Haptics.tick(hapticCtx)
                onScan()
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Scan library",
                tint = if (isScanning) MikuTealBright else MikuTeal,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (isScanning) spinAngle else 0f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                statusText,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            if (deltaBadge != null && deltaBadge != 0) {
                val d = deltaBadge!!
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (d > 0) Color(0xFF0E4035) else Color(0xFF4A1024))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (d > 0) "+$d" else "$d",
                        color = if (d > 0) MikuTealBright else MikuPink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable private fun MikuMonitorDialog(
    hazeState: HazeState,
    tracks: List<Track>,
    onStartFullScan: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val syncState by MikuSyncTransceiver.state.collectAsState()
    var isScanningIngress by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(onBack = onDismissRequest)

    Box(
        Modifier.fillMaxSize()
            .background(Color(0x73000000))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.92f)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
                .clip(RoundedCornerShape(22.dp))
                .hazeChild(state = hazeState, style = HazeMaterials.thick(Ground))
                .background(Brush.verticalGradient(listOf(Color(0xE6072328), Color(0xF2031114))))
                .border(1.dp, MikuTealBright.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                .padding(18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_miku_monitor_status),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Miku Monitor",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (syncState.isTransferring) MikuPink.copy(alpha = 0.3f) else MikuTeal.copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (syncState.isTransferring) "⚡ INGRESS ACTIVE" else "🟢 DAEMON LIVE",
                        color = if (syncState.isTransferring) MikuPink else MikuTealBright,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Storage and Library Stats
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(10.dp)
                ) {
                    Column {
                        Text("TRACKS", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("${tracks.size}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(10.dp)
                ) {
                    Column {
                        Text("ARTISTS", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("${tracks.map { it.artist }.distinct().size}", color = MikuTealBright, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(10.dp)
                ) {
                    Column {
                        Text("ALBUMS", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("${tracks.map { it.album }.distinct().size}", color = MikuPink, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ============================================================
            // PC Daemon Reconciliation Telemetry (m500d)
            // ============================================================
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, if (syncState.daemon.online) MikuTealBright.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("PC MEDIA ENGINE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Text(
                            if (syncState.daemon.online) "🟢 CONNECTED (${syncState.daemon.host})" else "🔴 OFFLINE",
                            color = if (syncState.daemon.online) MikuTealBright else Color.Gray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    if (syncState.daemon.online) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Server Library", color = Muted, fontSize = 11.sp)
                            Text("${syncState.daemon.serverAudioCount} tracks", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Card Storage", color = Muted, fontSize = 11.sp)
                            Text("${syncState.daemon.cardAudioCount} tracks", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        if (syncState.daemon.stagingAudioCount > 0) {
                            Spacer(Modifier.height(2.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("In-Flight Staged", color = Muted, fontSize = 11.sp)
                                Text("${syncState.daemon.stagingAudioCount} files", color = MikuPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (syncState.daemon.isTransferring) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "⚡ Pushing: ${syncState.daemon.currentArtist} - ${syncState.daemon.currentAlbum}",
                                color = MikuPink,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            "m500d daemon listening on port 8787",
                            color = Muted.copy(alpha = 0.7f),
                            fontSize = 10.5.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Transport & rsyncd Information
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Transport Link", color = Muted, fontSize = 12.sp)
                Text(syncState.transport.badge, color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Target Module", color = Muted, fontSize = 12.sp)
                Text("rsync://${syncState.ipAddress}:${MikuSyncTransceiver.RSYNC_PORT}/music", color = Color.White, fontSize = 11.5.sp, fontFamily = AudiowideFont)
            }

            if (syncState.isTransferring) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Live Ingress Rate", color = MikuPink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${String.format("%.1f", syncState.transferRateMBs)} MB/s", color = MikuPink, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action Buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val isStarting = !syncState.daemon.isTransferring
                        MikuSyncTransceiver.triggerDaemonSync(isStarting) { success, msg ->
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (syncState.daemon.isTransferring) MikuPink.copy(alpha = 0.35f) else MikuTeal.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (syncState.daemon.isTransferring) "⏹ Stop Sync" else "⚡ Start Sync",
                        color = if (syncState.daemon.isTransferring) MikuPink else MikuTealBright,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        MikuSyncTransceiver.runSpeedTest { res ->
                            android.widget.Toast.makeText(
                                ctx,
                                "${res.transportTier}\n⚡ ${String.format("%.1f", res.speedMBs)} MB/s (Latency: ${res.latencyMs}ms)",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.12f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "🚀 Speedtest",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (syncState.speedTest != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MikuTeal.copy(alpha = 0.15f))
                        .border(1.dp, MikuTealBright.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(syncState.speedTest?.transportTier ?: "", color = MikuTealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${String.format("%.1f", syncState.speedTest?.speedMBs ?: 0f)} MB/s (${syncState.speedTest?.latencyMs}ms)",
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    isScanningIngress = true
                    MikuSyncTransceiver.scanAndIntegrateDirectory(ctx, MikuSyncTransceiver.getSdMusicPath(ctx)) { count ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            isScanningIngress = false
                            android.widget.Toast.makeText(ctx, "✓ Ingested $count tracks into library", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (isScanningIngress) "Ingesting…" else "🔄 Quick Scan SD Card", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Verbose live scan readout as a real dismissable modal — this is the actual point of exposing
 * [ScanProgress] at all: no more guessing whether a big rescan is still working or has quietly
 * stalled. Floats over the content and can be closed without stopping the scan (it keeps running
 * in the background regardless; this dialog is just a window onto it). Previously this lived as
 * an inline panel under the header pill, which stretched the title bar taller while scanning.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable private fun ScanProgressDialog(hazeState: HazeState, onDismissRequest: () -> Unit) {
    var active by remember { mutableStateOf(ScanProgress.active) }
    var phase by remember { mutableStateOf(ScanProgress.phase) }
    var visited by remember { mutableIntStateOf(0) }
    var newFound by remember { mutableIntStateOf(0) }
    var tagsScanned by remember { mutableIntStateOf(0) }
    var tagsTotal by remember { mutableIntStateOf(0) }
    var resultDelta by remember { mutableIntStateOf(0) }
    var resultAlbums by remember { mutableIntStateOf(0) }
    var resultArtists by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            active = ScanProgress.active
            phase = ScanProgress.phase
            visited = ScanProgress.visited.get()
            newFound = ScanProgress.newFound.get()
            tagsScanned = ScanProgress.tagsScanned.get()
            tagsTotal = ScanProgress.tagsTotal
            resultDelta = ScanProgress.resultDelta
            resultAlbums = ScanProgress.resultAlbums
            resultArtists = ScanProgress.resultArtists
            delay(120)
        }
    }
    // Once the scan finishes, leave the result up briefly so it actually gets read, then close
    // itself — still closeable early via the X at any point.
    LaunchedEffect(active) { if (!active) { delay(2500); onDismissRequest() } }
    // Was a system Dialog(), which draws to its own separate Android window — Haze can't blur
    // "through" to another window's content, only within the same composition. Now a plain
    // in-app overlay sharing App()'s own hazeState, so the card behind it is REAL blur of
    // whatever's on screen, not a system Dialog's default dim scrim. Back press still dismisses.
    androidx.activity.compose.BackHandler(onBack = onDismissRequest)
    Box(
        Modifier.fillMaxSize()
            .background(Color(0x59000000))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismissRequest() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}   // swallow taps so they don't fall through to the dismiss-scrim below
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp)
                .hazeChild(
                    state = hazeState,
                    shape = RoundedCornerShape(20.dp),
                    style = HazeMaterials.thick(Color(0xFF14122A))
                )
                .border(1.2.dp, Brush.horizontalGradient(listOf(MikuTealBright.copy(alpha = 0.6f), MikuPink.copy(alpha = 0.5f))), RoundedCornerShape(20.dp))
        ) {
            // Mascot as a semi-transparent texture baked INTO the card background, not a foreground
            // sticker bolted on top — legible (art itself, not just a silhouette) but low-alpha
            // enough that it never competes with the actual scan status text drawn over it. Card
            // now has a floor on its height (was collapsing to fit its short text content, which
            // made a very wide/short crop window that chopped the square art's centered subject
            // down to an off-looking strip) and the art is top-anchored, not center-cropped, so her
            // face/upper body always survives the crop regardless of how short the card gets.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(MikuArt.djMegaphone),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alignment = Alignment.TopCenter,
                alpha = 0.26f,
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(20.dp))
            )
            Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (active) Icons.Default.Refresh else Icons.Default.Check,
                    contentDescription = null, tint = MikuTealBright, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (active) "Scanning Library" else "Scan Complete",
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                HapticIconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            if (!active) {
                Text(
                    if (resultDelta > 0) "✓ Found $resultDelta new track${if (resultDelta == 1) "" else "s"}" else "✓ Library up to date",
                    color = MikuTealBright, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                if (resultAlbums > 0 || resultArtists > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "$resultAlbums album${if (resultAlbums == 1) "" else "s"} · $resultArtists artist${if (resultArtists == 1) "" else "s"}",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                    )
                }
            } else {
                Text(phase, color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(8.dp))
                if (phase == "Reading tags…" && tagsTotal > 0) {
                    LinearProgressIndicator(
                        progress = { tagsScanned.toFloat() / tagsTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MikuTealBright,
                        trackColor = Color(0xFF12383A)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$tagsScanned / $tagsTotal tracks tagged", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MikuTealBright,
                        trackColor = Color(0xFF12383A)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$visited files checked", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                    Text("$newFound new tracks found so far", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Runs in the background — closing this doesn't stop it.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
            }
        }
        }
    }
}

fun expandNotificationShade(ctx: Context) {
    try {
        val intent = Intent().setClassName("com.miku.systemui", "com.miku.systemui.MikuShadeActivity").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        ctx.startActivity(intent)
    } catch (_: Throwable) {
        try {
            val sbservice = ctx.getSystemService("statusbar")
            val statusbarManager = Class.forName("android.app.StatusBarManager")
            val expand = statusbarManager.getMethod("expandNotificationsPanel")
            expand.invoke(sbservice)
        } catch (_: Throwable) {}
    }
}

@Composable private fun Header(
    count: Int,
    onScan: () -> Unit,
    onTape: () -> Unit,
    onSettings: () -> Unit = {},
    onHome: () -> Unit = {},
    canGoBack: Boolean = false,
    onBack: () -> Unit = {}
) {
    val ctx = LocalContext.current
    // No background fill of its own anymore — this now sits inside the shared hazeChild glass
    // panel (see App()), which supplies the frosted tint. A second opaque fill here would just
    // hide the blur entirely.
    Box(
        Modifier.fillMaxWidth()
            .clipToBounds()
            .pointerInput(Unit) {
                var totalY = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalY = 0f },
                    onDragEnd = {
                        if (totalY > 15f) {
                            expandNotificationShade(ctx)
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0) {
                            totalY += dragAmount
                            if (totalY > 20f) {
                                change.consume()
                                expandNotificationShade(ctx)
                                totalY = 0f
                            }
                        }
                    }
                )
            }
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(MikuArt.bgWide),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alpha = 0.14f,
            modifier = Modifier.matchParentSize()
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Show Back button ONLY when there is a sub-page to go back from
            if (canGoBack) {
                com.miku.player.ui.MikuBackButton(onClick = onBack)
                Spacer(Modifier.width(8.dp))
            }

            // Standalone Miku Music brand header
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(MikuArt.chibiHearts),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Miku Music",
                    color = MikuTealBright,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }
            Spacer(Modifier.weight(1f))

            ScannerPill(
                count = count,
                onScan = onScan
            )
            Spacer(Modifier.width(6.dp))
            HapticIconButton(
                onClick = onTape,
                modifier = Modifier.semantics { contentDescription = "Tape mode" }
            ) { TapeIcon(tint = MikuPink, modifier = Modifier.size(24.dp)) }
        }
    }
}

// Slim tabs: Material3's default Tab is 48dp tall, which wasted a band of dead space between the
// tab labels and the content — cap the row at 36dp.
// The four tabs actually shown in the bar, in display order — Songs lives inside Library now.
private val VISIBLE_TABS = listOf(Tab.HOME, Tab.ARTISTS, Tab.ALBUMS, Tab.GENRES)
// Each tab gets its own fixed Miku-palette color (not just selected/unselected teal-or-muted) so
// the bar itself reads as colorful/branded rather than four identical grey words.
private fun tabColor(t: Tab): Color = when (t) {
    Tab.HOME -> MikuTealBright
    Tab.ARTISTS -> MikuPink
    Tab.ALBUMS -> MikuGold
    Tab.GENRES -> Color(0xFFB388FF) // violet — same family used for the "ultra hi-res" tech badge tier
    Tab.SONGS -> MikuTeal
}

@Composable private fun TabBar(sel: Tab, onSel: (Tab) -> Unit) = ScrollableTabRow(
    // Transparent container — same reasoning as Header: this rides on the shared glass panel now.
    // Songs isn't ever the active tab from the bar's own perspective (it's not clickable here), but
    // App() can still land `tab` on it internally (Library's "All Songs" row) — fall back to
    // highlighting Library in that case rather than an out-of-range/no selection.
    selectedTabIndex = VISIBLE_TABS.indexOf(sel).let { if (it >= 0) it else VISIBLE_TABS.indexOf(Tab.GENRES) },
    containerColor = Color.Transparent, contentColor = MikuTeal, edgePadding = 12.dp) {
    VISIBLE_TABS.forEach { t ->
        val base = tabColor(t)
        androidx.compose.material3.Tab(selected = t == sel, onClick = { onSel(t) },
            modifier = Modifier.height(36.dp),
            text = { Text(t.label, color = if (t == sel) base else base.copy(alpha = 0.55f), fontWeight = if (t == sel) FontWeight.Bold else FontWeight.Normal, fontFamily = RighteousFont, fontSize = 13.5.sp, letterSpacing = 0.5.sp) })
    }
}

// Top-level tab bar always visible for non-home too:
@Composable private fun AlwaysTabs(sel: Tab, onSel: (Tab) -> Unit) = TabBar(sel, onSel)

@Composable private fun HomeScreen(
    tracks: List<Track>,
    recentlyPlayed: List<Track>,
    goArtists: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    albumGroups: List<AlbumGroup> = emptyList(),
    artistGroups: List<ArtistGroup> = emptyList(),
    onOpenAlbum: (AlbumGroup) -> Unit = {},
    onOpenArtist: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val likedIds = LikeStore.liked.toList()
    // Self-healing resolution (LikeStore.resolveLiked) — a liked track's MediaStore _id can drift
    // across a rescan; a plain id-membership filter would silently drop it from every "liked"
    // shelf with no explanation. See its doc comment for the confirmed real-world failure mode.
    val likedTracks = remember(likedIds, tracks) { LikeStore.resolveLiked(ctx, tracks) }

    // Was a synchronous `remember(tracks, likedIds) { ... }` — generateDailyHighlight does two
    // SharedPreferences reads PER TRACK (play count + last-played) across the whole library to
    // score everything, then sorts/picks the mix. On a few thousand tracks that's genuinely
    // expensive, and because it was keyed on `likedIds`, tapping ANY heart ANYWHERE re-ran the
    // whole thing right there on the UI thread — this was the "heart button lags the UI for 2-3
    // seconds" bug. Off the main thread now; keeps showing the previous mix (not a blank/empty
    // state) while a fresh one computes in the background.
    var highlight by remember { mutableStateOf(DailyHighlightEngine.HighlightResult(emptyList(), 0, 0, 0)) }
    LaunchedEffect(tracks, likedIds) {
        // Debounced: LaunchedEffect cancels+restarts this coroutine on every `likedIds` change, so
        // a burst of rapid heart-taps (liking a whole album quickly) used to spawn one full
        // library rescore PER tap, all running concurrently on Dispatchers.Default. The delay lets
        // a fast burst collapse into a single rescore of the FINAL liked-set once taps settle,
        // rather than doing (and discarding) N redundant full passes.
        delay(500)
        highlight = withContext(Dispatchers.Default) {
            DailyHighlightEngine.generateDailyHighlight(ctx, tracks, targetCount = 40)
        }
    }

    // "Newly Added" — artists/albums/tracks that landed in the library within Newness's own
    // 7-day window (same cutoff the badge dots use elsewhere), newest first. Tracks only show up
    // here if they're a single (no real album, or MediaStore's own albumId groups them with no
    // other track) — a track that's part of a real multi-track album is already covered by that
    // album showing up in the row above it, so listing it again as an individual track would just
    // be the same arrival duplicated twice.
    val nowSec = remember { System.currentTimeMillis() / 1000 }
    val albumTrackCounts = remember(tracks) { tracks.groupingBy { it.albumId }.eachCount() }
    val newArtists = remember(artistGroups, nowSec) {
        artistGroups.filter { Newness.isNew(it.dateAddedSec, nowSec) }
            .sortedByDescending { it.dateAddedSec }.take(12)
    }
    val newAlbums = remember(albumGroups, nowSec) {
        albumGroups.filter { Newness.isNew(it.dateAddedSec, nowSec) }
            .sortedByDescending { it.dateAddedSec }.take(12)
    }
    val newSingles = remember(tracks, nowSec, albumTrackCounts) {
        tracks.filter { t ->
            Newness.isNew(t.dateAddedSec, nowSec) &&
                (t.albumId <= 0L || t.album.isBlank() || (albumTrackCounts[t.albumId] ?: 1) <= 1)
        }.sortedByDescending { it.dateAddedSec }.take(12)
    }
    val hasNewSection = newArtists.isNotEmpty() || newAlbums.isNotEmpty() || newSingles.isNotEmpty()

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        // 1. Wallpaper of the day Banner + Smart Daily Highlight Action
        item {
            Box(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    .fillMaxWidth().height(120.dp)
                    .clip(RoundedCornerShape(18.dp))
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(MikuArt.bannerCyberStage),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    // The banner's aspect ratio crops the SIDES off this source, and dead-center
                    // both axes clipped her head/hair off the top — she sits in the upper portion
                    // of the source scene (lots of crowd/lights fill the lower two-thirds), so a
                    // vertically-centered crop window landed mostly on empty crowd below her while
                    // still cutting her hairline above. Nudged left (off-axis face position) and
                    // well up (keep her whole head in frame).
                    alignment = androidx.compose.ui.BiasAlignment(-0.18f, -0.65f),
                    modifier = Modifier.matchParentSize()
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Ground.copy(alpha = 0.90f)))
                    )
                )
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("ミク · DAILY HIGHLIGHT", color = MikuTealBright, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    val subText = if (highlight.favCount > 0 || highlight.gemCount > 0) {
                        "${highlight.tracks.size} tracks · ${highlight.favCount} Favs · ${highlight.discCount} Fresh · ${highlight.gemCount} Gems"
                    } else {
                        "${highlight.tracks.size} tracks · Daily Curated Discovery"
                    }
                    Text(subText, color = Color.White.copy(alpha = 0.92f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Play Mix — moved up to the top-left corner (was bottom-right, sitting right on
                // top of her hand/the DAP prop in the art). Own spot now, out of the text's way.
                PlayMixButton(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    onClick = { if (highlight.tracks.isNotEmpty()) onPlay(highlight.tracks, 0) }
                )
            }
        }

        // 2. Daily Highlight Tracks Carousel
        if (highlight.tracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Today's Highlight Mix (${highlight.tracks.size})", color = MikuTealBright, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Play Next Mix Button
                        Text(
                            "+ Play Next",
                            color = MikuTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                PlayerHolder.player?.let { p -> QueueManager.playNext(ctx, p, highlight.tracks) }
                            }
                        )
                        Spacer(Modifier.width(12.dp))
                        // Add Mix to Queue Button
                        Text(
                            "+ Queue",
                            color = MikuPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                PlayerHolder.player?.let { p -> QueueManager.playLast(ctx, p, highlight.tracks) }
                            }
                        )
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(highlight.tracks.size, key = { highlight.tracks[it].id }, contentType = { "track" }) { i ->
                        val t = highlight.tracks[i]
                        Column(
                            Modifier.width(118.dp).padding(4.dp).clickable { onPlay(highlight.tracks, i) }
                        ) {
                            Box(Modifier.size(110.dp).clip(RoundedCornerShape(14.dp))) {
                                AlbumArtImage(trackId = t.id, modifier = Modifier.fillMaxSize(), trackPath = t.path)
                                // Bit-depth / sample rate quality badges overlaid directly on album art
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp)
                                ) {
                                    TechBadgeRow(ctx, t, fontSize = 7.5.sp, spacing = 2.dp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(t.title, color = Color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // 1b. Newly Added — artists/albums/singles that landed within the last 7 days. Whichever
        // sub-rows actually have anything are shown; the whole section is skipped if nothing in
        // the library is that fresh.
        if (hasNewSection) {
            item {
                Text("✨ Newly Added", color = MikuTealBright, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
            }
            if (newArtists.isNotEmpty()) {
                item {
                    Text("Artists", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(newArtists.size, key = { newArtists[it].name }) { i ->
                            val a = newArtists[i]
                            val reprTrack = a.coverTrack(ctx)
                            NewItemCard(
                                artOf = reprTrack?.id, artPath = reprTrack?.path,
                                title = a.name, subtitle = "${a.tracks.size} tracks",
                                prominence = Newness.prominence(a.dateAddedSec, nowSec, a.tracks.any { PlayerPreferences.loadPlayCount(ctx, it.id) > 0 }),
                                shape = RoundedCornerShape(50),
                                onClick = { onOpenArtist(a.name) }
                            )
                        }
                    }
                }
            }
            if (newAlbums.isNotEmpty()) {
                item {
                    Text("Albums", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(newAlbums.size, key = { "${newAlbums[it].name}#${newAlbums[it].artist}#${newAlbums[it].tracks.firstOrNull()?.id ?: it}" }) { i ->
                            val al = newAlbums[i]
                            val reprTrack = al.tracks.firstOrNull()
                            NewItemCard(
                                artOf = reprTrack?.id, artPath = reprTrack?.path,
                                title = al.name, subtitle = al.artist,
                                prominence = Newness.prominence(al.dateAddedSec, nowSec, al.tracks.any { PlayerPreferences.loadPlayCount(ctx, it.id) > 0 }),
                                shape = RoundedCornerShape(14.dp),
                                onClick = { onOpenAlbum(al) }
                            )
                        }
                    }
                }
            }
            if (newSingles.isNotEmpty()) {
                item {
                    Text("Singles", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(newSingles.size, key = { newSingles[it].id }) { i ->
                            val t = newSingles[i]
                            NewItemCard(
                                artOf = t.id, artPath = t.path,
                                title = t.title, subtitle = t.artist,
                                prominence = Newness.prominence(t.dateAddedSec, nowSec, PlayerPreferences.loadPlayCount(ctx, t.id) > 0),
                                shape = RoundedCornerShape(14.dp),
                                onClick = { onPlay(newSingles, i) }
                            )
                        }
                    }
                }
            }
        }

        // 2. Liked Songs Section (Dedicated Carousel + Play All)
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RainbowHeart(true) {}
                    Spacer(Modifier.width(8.dp))
                    Text("Liked Songs", color = MikuTealBright, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("(${likedTracks.size})", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                if (likedTracks.isNotEmpty()) {
                    Text(
                        "Play All ▸",
                        color = MikuPink,
modifier = Modifier.clickable { onPlay(likedTracks, 0) }
                    )
                }
            }

            if (likedTracks.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(likedTracks.size, key = { likedTracks[it].id }, contentType = { "track" }) { i ->
                        val t = likedTracks[i]
                        Column(
                            Modifier.width(120.dp).padding(4.dp).clickable { onPlay(likedTracks, i) }
                        ) {
                            Box(Modifier.size(112.dp).clip(RoundedCornerShape(14.dp))) {
                                AlbumArtImage(trackId = t.id, modifier = Modifier.fillMaxSize(), trackPath = t.path)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(t.title, color = Color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF092124))
                        .border(1.dp, MikuTeal.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF041416))
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(MikuArt.chibiHearts),
                            contentDescription = "Miku",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("NO FAVORITES YET", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Tap ♥ on any song to collect your favorite tracks and build your personalized Miku mix!",
                            color = Muted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 3. Recently Played Section
        if (recentlyPlayed.isNotEmpty()) {
            item {
                SectionHeader("Recently played")
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(recentlyPlayed.size, key = { recentlyPlayed[it].id }) { i ->
                        val t = recentlyPlayed[i]
                        Column(Modifier.width(116.dp).padding(4.dp).clickable { onPlay(recentlyPlayed, i) }) {
                            AlbumArtImage(t.id, Modifier.size(108.dp).clip(RoundedCornerShape(14.dp)), trackPath = t.path)
                            Spacer(Modifier.height(6.dp))
                            Text(t.title, color = Color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // 4. Recently Added Songs Section
        item { SectionHeader("Recently added") }
        val recent = tracks.takeLast(30).reversed()
        itemsIndexed(recent) { i, t -> TrackRow(t) { onPlay(recent, i) } }
    }
}

/** Live 4-bar equalizer: bars bounce while playing, hold steady when paused. */
@Composable private fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier) {
    val n = 4
    // Manual time loop so the bars still bounce with device animations disabled (animator scale 0).
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) { phase += 0.20f; delay(45) }
    }
    Canvas(modifier) {
        val gap = size.width / (n * 2f - 1)
        for (i in 0 until n) {
            val hf = if (playing) (0.55f + 0.42f * kotlin.math.sin((phase + i * 0.9f).toDouble()).toFloat()) else 0.4f
            val bh = size.height * hf.coerceIn(0.15f, 1f)
            drawRoundRect(
                MikuTealBright,
                topLeft = Offset(i * 2 * gap, size.height - bh),
                size = Size(gap, bh),
                cornerRadius = CornerRadius(gap / 2, gap / 2)
            )
        }
    }
}

@Composable private fun SectionHeader(text: String) = Text(
    text, color = MikuTealBright, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = RighteousFont, letterSpacing = 0.5.sp,
    modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 8.dp)
)

// helper because LazyListScope.itemsIndexed needs the foundation import via lambda
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    list: List<Track>, row: @Composable (Int, Track) -> Unit
) = items(list.size, key = { list[it].id }, contentType = { "track" }) { i -> row(i, list[i]) }

@Composable
private fun <T> AlphabetFastScroller(
    items: List<T>,
    getItemName: (T) -> String,
    onScrollTo: suspend (Int) -> Unit,
    firstVisibleIndex: () -> Int = { 0 },
    modifier: Modifier = Modifier
) {
    if (items.size < 6) return

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val alphabet = remember { listOf('#') + ('A'..'Z').toList() }

    val letterMap = remember(items) {
        val map = mutableMapOf<Char, Int>()
        items.forEachIndexed { index, item ->
            val name = getItemName(item).trim()
            val sortKey = formatArtistSortKey(name, true, ctx)
            val firstChar = sortKey.firstOrNull()?.uppercaseChar() ?: '#'
            val letter = if (firstChar in 'A'..'Z') firstChar else '#'
            if (!map.containsKey(letter)) {
                map[letter] = index
            }
        }
        map
    }

    var isDragging by remember { mutableStateOf(false) }
    var activeLetter by remember { mutableStateOf<Char?>('#') }
    var touchY by remember { mutableFloatStateOf(0f) }
    var containerHeight by remember { mutableIntStateOf(1) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Sync active letter with normal list scrolling when not dragging
    val visibleIdx by remember { derivedStateOf { firstVisibleIndex() } }
    LaunchedEffect(visibleIdx, isDragging) {
        if (!isDragging && visibleIdx in items.indices) {
            val name = getItemName(items[visibleIdx]).trim()
            val sortKey = formatArtistSortKey(name, true, ctx)
            val firstChar = sortKey.firstOrNull()?.uppercaseChar() ?: '#'
            activeLetter = if (firstChar in 'A'..'Z') firstChar else '#'
        }
    }

    val railAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1.0f else 0.45f,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "railAlpha"
    )

    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.4f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scrollerScale"
    )

    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(48.dp) // Wide touch target for leather cases!
            .padding(end = 4.dp, top = 8.dp, bottom = 8.dp)
            .onSizeChanged { containerHeight = it.height.coerceAtLeast(1) }
            .pointerInput(items) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        touchY = offset.y
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onVerticalDrag = { change, _ ->
                        touchY = change.position.y
                    }
                )
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        // Ephemeral Letter Column (NO background rail lines or boxes!)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .alpha(railAlpha)
                .padding(end = 4.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            alphabet.forEach { char ->
                val hasItems = letterMap.containsKey(char)
                val isSelected = char == activeLetter
                Text(
                    text = char.toString(),
                    fontSize = if (isSelected) 9.5.sp else 8.sp,
                    fontWeight = if (isSelected || hasItems) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MikuTealBright else if (hasItems) MikuTeal.copy(alpha = if (isDragging) 0.9f else 0.55f) else Color.White.copy(alpha = 0.12f)
                )
            }
        }

        // Active letter drag handling & throttled scroll execution
        LaunchedEffect(touchY, isDragging) {
            if (isDragging && containerHeight > 0) {
                val fraction = (touchY / containerHeight.toFloat()).coerceIn(0f, 1f)
                val targetIndex = (fraction * (alphabet.size - 1)).let { Math.round(it) }.coerceIn(0, alphabet.size - 1)
                val selectedChar = alphabet[targetIndex]

                if (selectedChar != activeLetter) {
                    activeLetter = selectedChar
                    Haptics.tick(ctx)

                    val scrollToIdx = letterMap[selectedChar] ?: run {
                        val availableLetters = alphabet.filter { letterMap.containsKey(it) }
                        if (availableLetters.isEmpty()) null
                        else {
                            val closest = availableLetters.minByOrNull { Math.abs(alphabet.indexOf(it) - targetIndex) }
                            closest?.let { letterMap[it] }
                        }
                    }

                    scrollToIdx?.let { target ->
                        scrollJob?.cancel()
                        scrollJob = scope.launch {
                            onScrollTo(target)
                        }
                    }
                }
            }
        }

        // Prominent Reticle Position Indicator Pill (Fluid liquid-glide animation!)
        val currentLetterChar = activeLetter ?: '#'
        val letterIdx = alphabet.indexOf(currentLetterChar).coerceAtLeast(0)
        val reticleFraction = letterIdx.toFloat() / (alphabet.size - 1).coerceAtLeast(1)
        val targetReticleYOffsetDp = with(density) { ((if (isDragging) touchY else reticleFraction * containerHeight) - (containerHeight / 2f)).toDp() }

        val animatedReticleYOffsetDp by animateDpAsState(
            targetValue = targetReticleYOffsetDp,
            animationSpec = if (isDragging) snap() else spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
            label = "reticleYOffset"
        )

        if (!isDragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-1).dp, y = animatedReticleYOffsetDp)
                    .size(width = 22.dp, height = 13.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF072428))
                    .border(1.2.dp, MikuTealBright, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentLetterChar.toString(),
                    color = MikuTealBright,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // Bulging Magnifier Bubble (Google Photos / iOS Scroller style)
        if (scale > 0.01f && activeLetter != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-52).dp, y = animatedReticleYOffsetDp)
                    .scale(scale)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF0F3E42), Color(0xFF041618))
                        )
                    )
                    .border(1.8.dp, MikuTealBright, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = activeLetter.toString(),
                    color = MikuTealBright,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }
        }
    }
}

@Composable
private fun MikuSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search library...",
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .height(40.dp)
            .glassCard(corner = 20.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, "Search", tint = MikuTeal, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color(0xFFE8F4F2),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MikuTealBright),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(placeholder, color = Muted.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                innerTextField()
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close,
                "Clear",
                tint = Muted,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

@Composable
fun MikuEmptyState(
    title: String,
    subtitle: String,
    imageRes: Int = MikuArt.chibiHearts,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF071B1E))
                .border(1.5.dp, Brush.horizontalGradient(listOf(MikuTeal, MikuPink)), RoundedCornerShape(24.dp))
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(imageRes),
                contentDescription = "Miku",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            title,
            color = MikuTealBright,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            color = Muted,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable private fun SongList(
    tracks: List<Track>,
    sortIgnoreThe: Boolean = true,
    onPlay: (List<Track>, Int) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val ctx = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    // Was a synchronous remember{} — filter+sortedBy(formatArtistSortKey) over up to the WHOLE
    // library ran on the main thread on every single keystroke (an early keystroke like "b" can
    // still match thousands of tracks before the query narrows), no debounce. Confirmed live: typing
    // into this search box on a 6800-track library triggered a real ANR ("Input dispatching timed
    // out... waited 5005ms"), the app pegged at 118% CPU. Same fix shape as generateDailyHighlight's
    // fix earlier this session — debounce + off the main thread — applied here too.
    var sortedTracks by remember { mutableStateOf(tracks) }
    LaunchedEffect(tracks, sortIgnoreThe, searchQuery) {
        if (searchQuery.isNotBlank()) delay(250) // debounce fast typing; skip the delay when the query is being cleared back to blank
        sortedTracks = withContext(Dispatchers.Default) {
            val base = if (searchQuery.isBlank()) tracks
            else {
                val q = searchQuery.trim().lowercase()
                tracks.filter {
                    it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
                }
            }
            base.sortedBy { formatArtistSortKey(it.title, sortIgnoreThe, ctx) }
        }
    }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        MikuSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search ${tracks.size} songs by title, artist, album..."
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (sortedTracks.isEmpty()) {
                MikuEmptyState(
                    title = "NO TRACKS FOUND",
                    subtitle = if (searchQuery.isNotBlank()) "No songs match \"$searchQuery\"" else "No songs in library",
                    imageRes = MikuArt.chibiHearts,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(sortedTracks.size, key = { sortedTracks[it].id }, contentType = { "track" }) { i ->
                        TrackRow(sortedTracks[i]) { onPlay(sortedTracks, i) }
                    }
                }
                if (searchQuery.isBlank()) {
                    AlphabetFastScroller(
                        items = sortedTracks,
                        getItemName = { it.title },
                        onScrollTo = { idx -> listState.scrollToItem(idx) },
                        firstVisibleIndex = { listState.firstVisibleItemIndex },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

@Composable private fun ArtistList(
    artists: List<ArtistGroup>,
    loading: Boolean = false,
    sortIgnoreThe: Boolean = true,
    displayTheMode: String = "PREFIX",
    onUpdateSort: (Boolean) -> Unit = {},
    onUpdateDisplay: (String) -> Unit = {},
    onOpen: (ArtistGroup) -> Unit,
    onShuffle: (ArtistGroup) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    var showSettingsModal by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredArtists = remember(artists, searchQuery) {
        if (searchQuery.isBlank()) artists
        else {
            val q = searchQuery.trim().lowercase()
            artists.filter { it.name.lowercase().contains(q) }
        }
    }
    val scope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }

    Column(Modifier.fillMaxSize()) {
        MikuSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search ${artists.size} artists..."
        )
        Box(Modifier.weight(1f).fillMaxWidth().haze(hazeState)) {
            if (loading && filteredArtists.isEmpty()) {
                // Still waiting on the first queryTracks() to resolve — a real "0 artists" empty
                // state would be misleading here; this isn't a verdict on the library yet.
                androidx.compose.material3.CircularProgressIndicator(
                    color = MikuTealBright,
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
            } else if (filteredArtists.isEmpty()) {
                MikuEmptyState(
                    title = "NO ARTISTS FOUND",
                    subtitle = if (searchQuery.isNotBlank()) "No artists match \"$searchQuery\"" else "No artists in library",
                    imageRes = MikuArt.chibiHearts,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 36.dp)
                ) {
                    item(contentType = "header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${filteredArtists.size} Artists",
                                color = MikuTealBright,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                Modifier
                                    .glassCard(corner = 16.dp)
                                    .clickable { showSettingsModal = true }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Tune, "Rule Options", tint = MikuTealBright, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (sortIgnoreThe) "Sorted under 'C' (Ignore 'The')" else "Sorted under 'T' (Include 'The')",
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Guaranteed-unique even in the rare case two distinct canonical-key groups
                    // format to the identical display name (Compose throws "Key ... was already
                    // used" on a real duplicate, which silently drops that row from view) —
                    // appending a representative track id as a tiebreaker costs nothing when names
                    // are already unique (the common case) and fixes it when they aren't.
                    items(filteredArtists.size, key = { "${filteredArtists[it].name}#${filteredArtists[it].tracks.firstOrNull()?.id ?: it}" }, contentType = { "artist" }) { i ->
                        val a = filteredArtists[i]
                        val rowCtx = LocalContext.current
                        val reprTrack = a.coverTrack(rowCtx)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)
                                .glassCard()
                                .clickable { onOpen(a) }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (reprTrack != null) {
                                AlbumArtImage(trackId = reprTrack.id, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(50)), trackPath = reprTrack.path)
                            } else {
                                CircleArt()
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, color = if (LikeStore.isArtistLiked(a.name, rowCtx)) MikuTeal else Color(0xFFE8F4F2), fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontFamily = Baloo2Font)
                                Text("${a.tracks.size} tracks · ${a.albumCount} albums", color = Muted, fontSize = 12.5.sp, fontFamily = Baloo2Font)
                            }
                            QualityGauge(TrackTech.weightedQuality(rowCtx, a.tracks), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            HapticIconButton(onClick = { onShuffle(a) }) {
                                Icon(Icons.Default.Shuffle, "Shuffle Artist", tint = MikuTealBright, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            ArtistRainbowHeart(LikeStore.isArtistLiked(a.name, rowCtx)) { LikeStore.toggleArtist(rowCtx, a.name) }
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }

                if (searchQuery.isBlank()) {
                    AlphabetFastScroller(
                        items = filteredArtists,
                        getItemName = { it.name },
                        onScrollTo = { idx ->
                            scope.launch { listState.scrollToItem((idx + 1).coerceAtMost(filteredArtists.size)) }
                        },
                        firstVisibleIndex = { (listState.firstVisibleItemIndex - 1).coerceAtLeast(0) },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(bottom = 12.dp)
                    )
                }
            }
        }

        if (showSettingsModal) {
            ArtistSortSettingsModal(
                hazeState = hazeState,
                sortIgnoreThe = sortIgnoreThe,
                displayTheMode = displayTheMode,
                onUpdateSort = onUpdateSort,
                onUpdateDisplay = onUpdateDisplay,
                onDismiss = { showSettingsModal = false }
            )
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ArtistSortSettingsModal(
    hazeState: HazeState,
    sortIgnoreThe: Boolean,
    displayTheMode: String,
    onUpdateSort: (Boolean) -> Unit,
    onUpdateDisplay: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x59000000))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hazeChild(
                    state = hazeState,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    style = HazeMaterials.thick(Color(0xFF07191C))
                )
                .border(1.dp, MikuTeal.copy(alpha = 0.5f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    enabled = false
                ) {}
                .padding(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Artist 'The' Prefix Rules",
                    color = MikuTealBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                HapticIconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
            }

            Spacer(Modifier.height(14.dp))

            Text("ALPHABETICAL SORTING RULE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(8.dp))

            // Option 1: Ignore "The" (Default)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sortIgnoreThe) Color(0xFF0E383B) else Color(0xFF041416))
                    .clickable { onUpdateSort(true) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = sortIgnoreThe, onClick = { onUpdateSort(true) })
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Sort under 'C' (Ignore 'The')", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("e.g., 'The Crystal Method' appears under 'C'", color = Muted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Option 2: Include "The"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (!sortIgnoreThe) Color(0xFF0E383B) else Color(0xFF041416))
                    .clickable { onUpdateSort(false) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = !sortIgnoreThe, onClick = { onUpdateSort(false) })
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Sort under 'T' (Include 'The')", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("e.g., 'The Crystal Method' appears under 'T'", color = Muted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("DISPLAY FORMAT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(8.dp))

            listOf(
                "PREFIX" to ("'The Crystal Method'" to "Keep 'The' at the beginning"),
                "SUFFIX" to ("'Crystal Method, The'" to "Move 'The' to the end"),
                "STRIP" to ("'Crystal Method'" to "Hide 'The' prefix entirely")
            ).forEach { (modeKey, titleDesc) ->
                val selected = displayTheMode == modeKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Color(0xFF0E383B) else Color(0xFF041416))
                        .clickable { onUpdateDisplay(modeKey) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = { onUpdateDisplay(modeKey) })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(titleDesc.first, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(titleDesc.second, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Best-effort "show the whole title" text: shrinks one point at a time until it fits on one
 *  line instead of ellipsizing. A long messy tag (a real one: "Fear, and Loathing in Las Vegas")
 *  used to just get cut off — this reads the full name a little smaller rather than hiding it. */
@Composable private fun ShrinkToFitTitle(
    text: String,
    color: Color,
    maxFontSize: androidx.compose.ui.unit.TextUnit,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight = FontWeight.Black,
    fontFamily: FontFamily? = null,
    modifier: Modifier = Modifier
) {
    var fontSize by remember(text) { mutableStateOf(maxFontSize) }
    Text(
        text,
        color = color,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize.value > minFontSize.value) {
                fontSize = (fontSize.value - 1f).sp
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalHazeMaterialsApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun ArtistDetail(
    a: ArtistGroup,
    back: () -> Unit,
    onAlbum: (AlbumGroup) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val ctx = LocalContext.current
    // Keyed on canonicalArtistKey(), not the raw display name — see coverTrack()'s doc comment in
    // Model.kt for why: `a.name` is display-mode-formatted and mutable (PREFIX/SUFFIX/STRIP "The").
    val artistKey = remember(a.name) { canonicalArtistKey(a.name, ctx) }
    var coverOverride by remember(artistKey) { mutableStateOf(PlayerPreferences.loadArtistCoverTrack(ctx, artistKey)) }
    val reprTrack = remember(artistKey, coverOverride, a.tracks) {
        coverOverride?.let { id -> a.tracks.firstOrNull { it.id == id } } ?: a.tracks.firstOrNull()
    }
    // Keyed on a.tracks (not just a.name) — a rescan that adds/removes this artist's tracks
    // produces a new ArtistGroup with the same name but a different track list; keying on name
    // alone froze "Albums (N)"/"▶ N plays" at whatever they were when the screen first opened.
    val albums = remember(a.tracks) { a.tracks.albums() }
    val totalPlays = remember(a.tracks) { a.tracks.sumOf { PlayerPreferences.loadPlayCount(ctx, it.id) } }
    val hazeState = remember { HazeState() }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // 1. Fullscreen Hero Header Banner (Spotify Style)
        item {
            Box(
                Modifier.fillMaxWidth().height(210.dp)
            ) {
                // Background Cover Artwork — marked as the haze blur SOURCE so the text panel
                // below can show a real frosted-glass view of it instead of a flat dark scrim.
                if (reprTrack != null) {
                    AlbumArtImage(
                        trackId = reprTrack.id,
                        modifier = Modifier.fillMaxSize().haze(hazeState),
                        trackPath = reprTrack.path
                    )
                } else {
                    Box(Modifier.fillMaxSize().haze(hazeState).background(Color(0xFF0C2B2E)))
                }

                // Light top scrim only, for status-bar legibility — the text block below now
                // sits on its own real glass panel instead of needing a heavy full-height scrim.
                Box(
                    Modifier.fillMaxWidth().height(90.dp).align(Alignment.TopStart).background(
                        Brush.verticalGradient(listOf(Color(0x66041416), Color.Transparent))
                    )
                )

                // Artist Header Text & Actions — real frosted glass over the blurred art behind it.
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .hazeChild(
                            state = hazeState,
                            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                            style = HazeMaterials.thick(Color(0xFF041416))
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ARTIST", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        if (totalPlays > 0) {
                            Text("  ·  ▶ $totalPlays plays", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    ShrinkToFitTitle(
                        a.name,
                        color = Color.White,
                        maxFontSize = 24.sp,
                        minFontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        "${albums.size} Albums  ·  ${a.tracks.size} Songs",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(Modifier.height(10.dp))

                    // Action Row: Play All, Shuffle, Rainbow Heart
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Brush.horizontalGradient(listOf(MikuTeal, MikuPink)))
                                .clickable { if (a.tracks.isNotEmpty()) onPlay(a.tracks, 0) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF04161A), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play All", color = Color(0xFF04161A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFF0F2B2E))
                                .border(1.dp, MikuTeal.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                                .clickable { if (a.tracks.isNotEmpty()) onPlay(a.tracks.shuffled(), 0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shuffle, "Shuffle", tint = MikuTealBright, modifier = Modifier.size(18.dp))
                        }

                        Spacer(Modifier.width(10.dp))

                        ArtistRainbowHeart(LikeStore.isArtistLiked(a.name, ctx)) { LikeStore.toggleArtist(ctx, a.name) }
                    }
                }
            }
        }

        // 2. Albums Section — wrapping grid (no left/right off-screen scroll)
        if (albums.isNotEmpty()) {
            item {
                SectionHeader("Albums (${albums.size})")
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    albums.forEachIndexed { i, al ->
                        val albumRepr = al.tracks.firstOrNull()
                        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                        Column(
                            Modifier.width(112.dp).padding(vertical = 2.dp).combinedClickable(
                                onClick = { onAlbum(al) },
                                // Long-press to pin THIS album's art as the artist's cover — the
                                // default (whatever track sorts first) is often not the album the
                                // user actually associates with the artist.
                                onLongClick = {
                                    if (albumRepr != null) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        PlayerPreferences.saveArtistCoverTrack(ctx, a.name, albumRepr.id)
                                        coverOverride = albumRepr.id
                                        android.widget.Toast.makeText(ctx, "Set as ${a.name}'s cover", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        ) {
                            Box(Modifier.size(112.dp).clip(RoundedCornerShape(14.dp))) {
                                if (albumRepr != null) {
                                    AlbumArtImage(trackId = albumRepr.id, modifier = Modifier.fillMaxSize(), trackPath = albumRepr.path)
                                } else {
                                    Box(Modifier.fillMaxSize().background(Color(0xFF123438)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Album, null, tint = MikuTeal.copy(alpha = .6f), modifier = Modifier.size(42.dp))
                                    }
                                }
                                if (albumRepr != null && coverOverride == albumRepr.id) {
                                    Icon(Icons.Default.PushPin, "Pinned as cover", tint = MikuPink,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(al.name, color = Color(0xFFE8F4F2), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val yr = al.tracks.firstOrNull { it.year > 0 }?.year ?: 0
                            Text("${al.tracks.size} tracks${if (yr > 0) " · $yr" else ""}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // 3. Songs Section
        item { SectionHeader("All Songs (${a.tracks.size})") }
        itemsIndexed(a.tracks) { i, t ->
            TrackRow(t) { onPlay(a.tracks, i) }
        }
    }
}

@Composable private fun AlbumGrid(
    albums: List<AlbumGroup>,
    loading: Boolean = false,
    onOpen: (AlbumGroup) -> Unit,
    onShuffle: (AlbumGroup) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState()
) {
    val ctx = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val filteredAlbums = remember(albums, searchQuery) {
        if (searchQuery.isBlank()) albums
        else {
            val q = searchQuery.trim().lowercase()
            albums.filter { it.name.lowercase().contains(q) || it.artist.lowercase().contains(q) }
        }
    }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        MikuSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search ${albums.size} albums by title or artist..."
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading && filteredAlbums.isEmpty()) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = MikuTealBright,
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
            } else if (filteredAlbums.isEmpty()) {
                MikuEmptyState(
                    title = "NO ALBUMS FOUND",
                    subtitle = if (searchQuery.isNotBlank()) "No albums match \"$searchQuery\"" else "No albums in library",
                    imageRes = MikuArt.chibiHearts,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                    // name+artist alone isn't guaranteed unique — confirmed live crash: two
                    // distinct album groups both computed to "Humanz (Japanese Edition) [CD-02]" /
                    // "Gorillaz" (duplicate disc rip under a different folder, same shape as the
                    // earlier Crystal Method duplicate-untagged-folder finding), which crashed the
                    // whole grid with "Key ... was already used." Tiebreak with a representative
                    // track id, same fix already applied to the Artists list's key.
                    items(filteredAlbums.size, key = { "${filteredAlbums[it].name}#${filteredAlbums[it].artist}#${filteredAlbums[it].tracks.firstOrNull()?.id ?: it}" }, contentType = { "album" }) { idx ->
                        val al = filteredAlbums[idx]
                        val reprTrack = al.tracks.firstOrNull()
                        val albumPlays = remember(al.tracks) { al.tracks.sumOf { PlayerPreferences.loadPlayCount(ctx, it.id) } }
                        Column(
                            Modifier.padding(6.dp)
                                .glassCard(corner = 16.dp)
                                .clickable { onOpen(al) }
                                .padding(8.dp)
                        ) {
                            val bestBitsTrack = remember(al.tracks) { al.tracks.maxByOrNull { TrackTech.bitsFor(ctx, it) ?: -1 } }
                            val bestSrTrack = remember(al.tracks) { al.tracks.maxByOrNull { TrackTech.sampleRateFor(ctx, it) ?: -1 } }
                            val tileBits = bestBitsTrack?.let { TrackTech.bitsFor(ctx, it) }
                            val tileSr = bestSrTrack?.let { TrackTech.sampleRateFor(ctx, it) }

                            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
                                if (reprTrack != null) {
                                    AlbumArtImage(trackId = reprTrack.id, modifier = Modifier.fillMaxSize(), trackPath = reprTrack.path)
                                } else {
                                    Box(Modifier.fillMaxSize().background(Color(0xFF123438)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Album, null, tint = MikuTeal.copy(alpha = .6f), modifier = Modifier.size(46.dp))
                                    }
                                }

                                // Bit-depth / sample-rate badges overlaid directly in the bottom-start of the album art to save space
                                if ((tileBits != null && tileBits > 0) || (tileSr != null && tileSr > 0)) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (tileBits != null && tileBits > 0) {
                                            val tier = TrackTech.bitTier(tileBits)
                                            val isRainbow = tier >= 3
                                            TechBadgeChip(
                                                text = "$tileBits-BIT",
                                                tier = tier,
                                                glyph = TrackTech.glyphForBitTier(tier),
                                                brush = TrackTech.bitBrush(tileBits),
                                                glowColor = TrackTech.color(tileBits),
                                                shape = TrackTech.bitShape(tileBits),
                                                fontSize = 7.5.sp,
                                                isRainbow = isRainbow
                                            )
                                        }
                                        if (tileSr != null && tileSr > 0) {
                                            if (tileBits != null && tileBits > 0) Spacer(Modifier.width(3.dp))
                                            val tier = TrackTech.rateTier(tileSr)
                                            val isRainbow = tier >= 3
                                            TechBadgeChip(
                                                text = TrackTech.formatSampleRate(tileSr).uppercase(),
                                                tier = tier,
                                                glyph = TrackTech.glyphForRateTier(tier),
                                                brush = TrackTech.rateBrush(tileSr),
                                                glowColor = TrackTech.rateColor(tileSr),
                                                shape = TrackTech.rateShape(tileSr),
                                                fontSize = 7.5.sp,
                                                isRainbow = isRainbow
                                            )
                                        }
                                    }
                                }

                                // Quick Shuffle Button Overlay
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color(0xDD041416))
                                        .border(1.dp, MikuTeal.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                        .clickable { onShuffle(al) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Shuffle, "Shuffle Album", tint = MikuTealBright, modifier = Modifier.size(16.dp))
                                }
                            }
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(al.name, color = Color(0xFFE8F4F2), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(4.dp))
                                QualityGauge(TrackTech.weightedQuality(ctx, al.tracks), modifier = Modifier.size(16.dp))
                            }
                            val yr = remember(al.tracks) {
                                al.tracks.firstNotNullOfOrNull { TrackYear.yearFor(ctx, it)?.takeIf { y -> y > 0 } ?: it.year.takeIf { y -> y > 0 } } ?: 0
                            }
                            val playsTag = if (albumPlays > 0) "  ·  ▶ $albumPlays" else ""
                            Text(al.artist + (if (yr > 0) "  ·  $yr" else "") + playsTag, color = if (yr > 0) Color(0xFFC0DDD9) else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Pressing/edition tag when the album title carries one
                            releaseTag(al.name)?.let { tag ->
                                DataChip(tag, ReleaseTagColor)
                            }
                            val fmt = al.tracks.mapNotNull { it.mime.substringAfterLast('/').uppercase().ifBlank { null } }
                                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
                            val maxBr = al.tracks.maxOfOrNull { it.bitrateKbps } ?: 0
                            if (fmt.isNotEmpty()) Text(
                                fmt + if (maxBr > 0) "  ·  ${maxBr}k" else "",
                                color = formatColor(al.tracks.first().mime), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                            )
                        }
                    }
                }

                if (searchQuery.isBlank()) {
                    AlphabetFastScroller(
                        items = filteredAlbums,
                        getItemName = { it.name },
                        onScrollTo = { idx -> gridState.scrollToItem(idx) },
                        firstVisibleIndex = { (gridState.firstVisibleItemIndex * 2).coerceIn(0, (filteredAlbums.size - 1).coerceAtLeast(0)) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable private fun DetailList(
    title: String,
    tracks: List<Track>,
    back: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    likeAlbum: String? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState()
) {
    val ctx = LocalContext.current
    val sortedTracks = remember(tracks) { sortAlbumTracks(tracks) }
    val reprTrack = sortedTracks.firstOrNull()
    val albumArtist = reprTrack?.albumArtist?.ifBlank { reprTrack.artist } ?: reprTrack?.artist ?: "Various Artists"
    val year = remember(sortedTracks) {
        sortedTracks.firstNotNullOfOrNull { TrackYear.yearFor(ctx, it)?.takeIf { y -> y > 0 } ?: it.year.takeIf { y -> y > 0 } } ?: 0
    }
    val totalPlays = remember(sortedTracks) { sortedTracks.sumOf { PlayerPreferences.loadPlayCount(ctx, it.id) } }
    val hazeState = remember { HazeState() }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // Hero Album Banner Header (Spotify Style) — full-bleed blurred album art as the backdrop
        // (was a flat two-tone gradient, unrelated to what album you were actually looking at),
        // real glass panel behind the title block instead of sitting straight on the gradient.
        item {
            Box(Modifier.fillMaxWidth()) {
                if (reprTrack != null) {
                    AlbumArtImage(
                        trackId = reprTrack.id,
                        modifier = Modifier.matchParentSize().haze(hazeState),
                        trackPath = reprTrack.path
                    )
                } else {
                    Box(Modifier.matchParentSize().haze(hazeState).background(Color(0xFF123F44)))
                }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x99123F44), Color(0xCC041416)))))

                // Like heart
                if (likeAlbum != null) {
                    Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                        AlbumRainbowHeart(LikeStore.isAlbumLiked(albumArtist, likeAlbum, ctx)) { LikeStore.toggleAlbum(ctx, albumArtist, likeAlbum) }
                    }
                }
                Column(
                    Modifier.fillMaxWidth()
                        .hazeChild(state = hazeState, style = HazeMaterials.regular(Color(0xFF041416)))
                        .statusBarsPadding().padding(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // Large Cover Artwork (120x120dp)
                        Box(Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0C2B2E))) {
                            if (reprTrack != null) {
                                AlbumArtImage(trackId = reprTrack.id, modifier = Modifier.fillMaxSize(), trackPath = reprTrack.path)
                            } else {
                                Icon(Icons.Default.Album, null, tint = MikuTeal, modifier = Modifier.size(50.dp).align(Alignment.Center))
                            }
                        }

                        Spacer(Modifier.width(16.dp))

                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("ALBUM", color = MikuTealBright, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                releaseTag(title)?.let { tag ->
                                    Spacer(Modifier.width(6.dp))
                                    DataChip(tag, ReleaseTagColor)
                                }
                            }
                            ShrinkToFitTitle(title, color = Color.White, maxFontSize = 19.sp, minFontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                            
                            // Clickable Artist Link
                            Text(
                                albumArtist,
                                color = MikuPink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onOpenArtist?.invoke(albumArtist) }
                            )

                            Spacer(Modifier.height(4.dp))
                            val yearText = if (year > 0) "$year  ·  " else ""
                            Text("$yearText${sortedTracks.size} tracks${if (totalPlays > 0) "  ·  ▶ $totalPlays plays" else ""}", color = Muted, fontSize = 12.sp)

                            // Tech Spec Pills — format now uses the same neon hexagon badge as
                            // bit-depth/sample-rate instead of a plain colored Text, so this row
                            // doesn't mix one flat-text metric in with two badge-styled ones.
                            if (reprTrack != null) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TechBadgeRow(ctx, reprTrack, fontSize = 9.sp, spacing = 4.dp, includeFormat = true)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Play & Shuffle Action Row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Brush.horizontalGradient(listOf(MikuTeal, MikuPink)))
                                .clickable { if (sortedTracks.isNotEmpty()) onPlay(sortedTracks, 0) }
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF04161A), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play Album", color = Color(0xFF04161A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(Color(0xFF0F2B2E))
                                .border(1.dp, MikuTeal.copy(alpha = 0.5f), RoundedCornerShape(19.dp))
                                .clickable { if (sortedTracks.isNotEmpty()) onPlay(sortedTracks.shuffled(), 0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shuffle, "Shuffle", tint = MikuTealBright, modifier = Modifier.size(18.dp))
                        }

                        Spacer(Modifier.width(10.dp))

                        // Play Next Album
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(Color(0xFF0F2B2E))
                                .border(1.dp, MikuTeal.copy(alpha = 0.5f), RoundedCornerShape(19.dp))
                                .clickable {
                                    PlayerHolder.player?.let { p -> QueueManager.playNext(ctx, p, sortedTracks) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlaylistPlay, "Play Album Next", tint = MikuTealBright, modifier = Modifier.size(20.dp))
                        }

                        Spacer(Modifier.width(10.dp))

                        // Add Album to Queue
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(Color(0xFF1C081A))
                                .border(1.dp, MikuPink.copy(alpha = 0.5f), RoundedCornerShape(19.dp))
                                .clickable {
                                    PlayerHolder.player?.let { p -> QueueManager.playLast(ctx, p, sortedTracks) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlaylistAdd, "Add Album to Queue", tint = MikuPink, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // Track List
        items(sortedTracks.size, key = { sortedTracks[it].id }, contentType = { "track" }) { i ->
            TrackRow(sortedTracks[i], showTrackNumber = true) { onPlay(sortedTracks, i) }
        }
    }
}


/** Tiny bar-chart glyph — 1/2/3 bars, taller each tier — riding inside the bit-depth badge as its
 *  "more than a pill" identity mark. Cheap Canvas draw, no bitmaps, safe to repeat down a long list. */
@Composable fun BitTierGlyph(tier: Int, color: Color, size: androidx.compose.ui.unit.Dp = 9.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val bars = tier.coerceIn(1, 3)
        val barW = this.size.width / (bars * 1.7f)
        val gap = barW * 0.55f
        val heights = floatArrayOf(0.42f, 0.72f, 1.0f)
        val totalW = bars * barW + (bars - 1) * gap
        var x = (this.size.width - totalW) / 2f
        for (i in 0 until bars) {
            val h = this.size.height * heights[i]
            drawRoundRect(
                color, topLeft = Offset(x, this.size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = CornerRadius(barW * 0.35f, barW * 0.35f)
            )
            x += barW + gap
        }
    }
}

/** Tiny concentric-ring glyph — 1..4 rings, one per sample-rate tier — the sample-rate badge's
 *  own identity mark, deliberately a different silhouette language than the bit-depth bars. */
@Composable fun RateTierGlyph(tier: Int, color: Color, size: androidx.compose.ui.unit.Dp = 9.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val rings = tier.coerceIn(1, 4)
        val cx = this.size.width / 2f; val cy = this.size.height / 2f
        val maxR = this.size.minDimension / 2f
        for (i in 0 until rings) {
            val r = maxR * (0.34f + 0.22f * i)
            drawCircle(color, radius = r, center = Offset(cx, cy), style = Stroke(1.1.dp.toPx()))
        }
    }
}

/**
 * Kawaii Miku Audio Quality Badge Chip:
 *  - Tier 1 (16-bit / 44.1k-48k): Frosted Miku Cyan-Mint Glass Capsule with subtle ambient glow and '✧' starlet
 *  - Tier 2 (24-bit / 88.2k-96k): Radiant Gold & Cyan Crystal Gem with specular reflection and '✦' star
 *  - Tier 3 (176.4k-192k+ / 32-bit / DSD): Iridescent Rainbow Chromatic Heart-Crest with holographic prism sheen and '♥' heart
 *  - Format (FLAC / WAV / ALAC): Kawaii Miku Ribbon Hex-Gem
 */
@Composable fun TechBadgeChip(
    text: String,
    tier: Int = 1,
    glyph: String = "",
    brush: Brush? = null,
    glowColor: Color = MikuTeal,
    shape: Shape = BadgeShapes.kawaiiCapsule,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.sp,
    isRainbow: Boolean = false
) {
    val density = LocalDensity.current
    val effectiveBrush = brush ?: Brush.linearGradient(listOf(glowColor, glowColor))
    // Top-tier badges get a living holographic shimmer sweeping across them — the "more kawaii and
    // colorful, ESPECIALLY for the top tier" ask. The infinite transition itself is only ever
    // created when isRainbow is true (conditional composable call, not a conditional read of an
    // always-running one) — most badges in a real library aren't top-tier, so this keeps every
    // other badge's animation cost at exactly zero, not just "unused".
    val shimmerPos = if (isRainbow) {
        val shimmerTransition = rememberInfiniteTransition(label = "badgeShimmer")
        val pos by shimmerTransition.animateFloat(
            initialValue = -0.3f, targetValue = 1.3f,
            animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing)),
            label = "shimmerPos"
        )
        pos
    } else 0f

    Box(
        modifier = Modifier
            .drawBehind {
                val outline = shape.createOutline(size, layoutDirection, density)
                val path = Path().apply { addOutline(outline) }

                // 1. Soft depth drop shadow
                translate(top = 1.4f) { drawPath(path, Color(0x66000000)) }

                // 2. Crystal glass core background
                val glassFill = if (isRainbow) {
                    Brush.verticalGradient(listOf(Color(0xF2190B26), Color(0xF8090312)))
                } else if (tier >= 2) {
                    Brush.verticalGradient(listOf(Color(0xF220190A), Color(0xF80A0702)))
                } else {
                    Brush.verticalGradient(listOf(Color(0xF20B1D20), Color(0xF8040F11)))
                }
                drawPath(path, glassFill)

                // 3. Ambient inner radial glow in tier color — bigger/hotter for the top tier
                if (isRainbow) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0x55FF3399), Color(0x4400F5D4), Color(0x229D4EDD), Color.Transparent),
                            radius = size.maxDimension * 1.05f
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.5f)
                    )
                } else {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(glowColor.copy(alpha = 0.20f), Color.Transparent),
                            radius = size.maxDimension * 0.75f
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.5f)
                    )
                }

                // 4. Layered glowing neon strokes — top tier runs hotter/thicker across all three
                // layers, so the rainbow tier reads as visibly more "turned up" than gold or teal,
                // not just a different hue at the same intensity.
                val haloW = if (isRainbow) 7.5f else 5.2f
                val midW = if (isRainbow) 3.6f else 2.8f
                val coreW = if (isRainbow) 1.7.dp.toPx() else 1.3.dp.toPx()
                // Outer soft halo
                drawPath(path, effectiveBrush, alpha = if (isRainbow) 0.24f else 0.16f, style = Stroke(haloW, cap = StrokeCap.Round))
                // Mid glow
                drawPath(path, effectiveBrush, alpha = if (isRainbow) 0.5f else 0.38f, style = Stroke(midW, cap = StrokeCap.Round))
                // Crisp bright core rim
                drawPath(path, effectiveBrush, alpha = 0.95f, style = Stroke(coreW, cap = StrokeCap.Round))

                // 5. Specular curved glass sheen / glint
                clipPath(path) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0x55FFFFFF), Color(0x10FFFFFF), Color.Transparent),
                            radius = size.minDimension * 0.65f
                        ),
                        radius = size.minDimension * 0.55f,
                        center = Offset(size.width * 0.5f, size.height * 0.12f)
                    )

                    // Prismatic rainbow shimmer streak for top tier — sweeps left-to-right on a
                    // loop instead of sitting static, a little holographic-card glint.
                    if (isRainbow) {
                        val sx = size.width * shimmerPos
                        drawLine(
                            Brush.linearGradient(
                                listOf(Color(0x00FFFFFF), Color(0xAAFFFFFF), Color(0x00FFFFFF)),
                                start = Offset(sx - size.width * 0.18f, 0f),
                                end = Offset(sx + size.width * 0.18f, size.height)
                            ),
                            start = Offset(sx - size.width * 0.18f, 0f),
                            end = Offset(sx + size.width * 0.18f, size.height),
                            strokeWidth = 1.6.dp.toPx()
                        )
                    }

                    // 6. Cut-gem facet texture — thin lines radiating from the center out toward
                    // the shape's corners, like light catching the internal facets of an actual
                    // cut stone. Same diamond/pentagon/hexagon silhouette throughout (design is
                    // untouched); this only enriches the SURFACE for higher tiers, so a better
                    // value visibly looks like a more elaborately cut gem, not just a brighter one.
                    val facetCount = when { isRainbow -> 8; tier >= 2 -> 5; else -> 3 }
                    val cx = size.width * 0.5f
                    val cy = size.height * 0.5f
                    val facetReach = size.maxDimension * 0.62f
                    for (i in 0 until facetCount) {
                        val a = (2.0 * Math.PI * i / facetCount) + (Math.PI / 6)
                        val fx = cx + (facetReach * kotlin.math.cos(a)).toFloat()
                        val fy = cy + (facetReach * kotlin.math.sin(a)).toFloat()
                        drawLine(Color.White.copy(alpha = if (isRainbow) 0.22f else 0.14f), Offset(cx, cy), Offset(fx, fy), 0.6.dp.toPx())
                    }
                    // Tiny sparkle points at a couple of facet junctions — only the top tier earns
                    // actual glints, not just lines, so it reads as visibly more "finished".
                    if (isRainbow) {
                        val sparkleAngles = listOf(0.4, 2.6, 4.3)
                        for (a in sparkleAngles) {
                            val px = cx + (facetReach * 0.72f * kotlin.math.cos(a)).toFloat()
                            val py = cy + (facetReach * 0.72f * kotlin.math.sin(a)).toFloat()
                            drawCircle(Color.White.copy(alpha = 0.85f), radius = 0.9.dp.toPx(), center = Offset(px, py))
                        }
                    }
                }
            }
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        // The ✧/✦/♥ glyph that used to sit left of the text is gone — it was pulling the actual
        // text off dead-center (same issue as the old icon-prefix design), and the shape+color
        // escalation already carries the tier signal on its own. Text alone, truly centered now.
        Text(
            text,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            fontFamily = OrbitronFont,
            letterSpacing = 0.35.sp
        )
    }
}

/** Legacy / Overload wrapper for single color calls */
@Composable fun TechBadgeChip(
    text: String,
    color: Color,
    shape: Shape,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.5.sp
) {
    val tier = when {
        color == MikuPink || color == Color(0xFFFF5C5C) || color == Color(0xFFB388FF) -> 3
        color == MikuGold || color == Color(0xFF4FC3F7) -> 2
        else -> 1
    }
    val isRainbow = tier >= 3
    val brush = if (isRainbow) MikuBadgePalette.RainbowBrush else if (tier == 2) MikuBadgePalette.HiResBrush else Brush.linearGradient(listOf(color, color))
    val glyph = if (tier >= 3) "♥" else if (tier == 2) "✦" else "✧"
    TechBadgeChip(
        text = text,
        tier = tier,
        glyph = glyph,
        brush = brush,
        glowColor = color,
        shape = shape,
        fontSize = fontSize,
        isRainbow = isRainbow
    )
}

/** Sample-rate + bit-depth chips together with full Kawaii Miku tiered styling. */
@Composable fun TechBadgeRow(
    ctx: android.content.Context,
    track: Track,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.5.sp,
    spacing: androidx.compose.ui.unit.Dp = 5.dp,
    includeFormat: Boolean = false
) {
    val bits = TrackTech.bitsFor(ctx, track)
    val sr = TrackTech.sampleRateFor(ctx, track)
    val fmt = track.mime.substringAfterLast('/').uppercase().ifBlank { null }
    var shown = false

    if (includeFormat && fmt != null) {
        // Same tier-escalation treatment as the bit/rate badges now (see TrackTech.formatTier) —
        // a lossless FLAC/WAV/ALAC/DSD file gets the full rainbow/gem treatment just like a 32-bit
        // or 192kHz file does, instead of always sitting flat at the plain tier-1 look regardless
        // of whether it's an MP3 or a lossless master.
        val fmtTier = TrackTech.formatTier(track.mime)
        TechBadgeChip(
            text = fmt,
            tier = fmtTier,
            brush = TrackTech.formatBrush(track.mime),
            glowColor = formatColor(track.mime),
            shape = BadgeShapes.kawaiiHexGem,
            fontSize = fontSize,
            isRainbow = fmtTier >= 3
        )
        shown = true
    }

    if (bits != null && bits > 0) {
        if (shown) Spacer(Modifier.width(spacing))
        val tier = TrackTech.bitTier(bits)
        val isRainbow = tier >= 3
        TechBadgeChip(
            text = "$bits-BIT",
            tier = tier,
            glyph = TrackTech.glyphForBitTier(tier),
            brush = TrackTech.bitBrush(bits),
            glowColor = TrackTech.color(bits),
            shape = TrackTech.bitShape(bits),
            fontSize = fontSize,
            isRainbow = isRainbow
        )
        shown = true
    }

    if (sr != null && sr > 0) {
        if (shown) Spacer(Modifier.width(spacing))
        val tier = TrackTech.rateTier(sr)
        val isRainbow = tier >= 3
        TechBadgeChip(
            text = TrackTech.formatSampleRate(sr).uppercase(),
            tier = tier,
            glyph = TrackTech.glyphForRateTier(tier),
            brush = TrackTech.rateBrush(sr),
            glowColor = TrackTech.rateColor(sr),
            shape = TrackTech.rateShape(sr),
            fontSize = fontSize,
            isRainbow = isRainbow
        )
    }
}

/** Compact "at a glance" quality meter for an artist/album list row — a speedometer-style arc,
 *  no text, colored teal→gold→pink by tier (same hotter-is-better language as every other quality
 *  signal in the app) so you can eyeball a whole artist/album's general recording quality without
 *  reading a single badge. Renders nothing while the sample hasn't resolved yet (no guessed score). */
@Composable fun QualityGauge(score: Float?, modifier: Modifier = Modifier.size(22.dp)) {
    if (score == null) return
    val color = when {
        score >= 0.72f -> MikuPink
        score >= 0.38f -> MikuGold
        else -> MikuTeal
    }
    val animatedScore by animateFloatAsState(score, tween(500), label = "qualityGauge")
    Canvas(modifier) {
        val stroke = size.minDimension * 0.16f
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        // 270° speedometer sweep, gap at the bottom — background track, then the filled portion.
        drawArc(Color(0xFF1A2C2E), startAngle = 135f, sweepAngle = 270f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(color, startAngle = 135f, sweepAngle = 270f * animatedScore, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun TrackRow(t: Track, showTrackNumber: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    val playCount = PlayerPreferences.loadPlayCount(ctx, t.id)
    val displayYear = remember(t.id) { TrackYear.yearFor(ctx, t)?.takeIf { it > 0 } ?: t.year.takeIf { it > 0 } }
    // Build the styled metric lines once per track, not on every recomposition.
    val meta = remember(t, displayYear) { trackMetaSpan(t, displayYear) }
    val metrics = remember(t) { metricSpans(t) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .glassCard(tint = if (NowPlayingState.currentId == t.id) MikuTealBright else MikuTeal)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { Haptics.tick(ctx); showSheet = true }   // long-press → full queue actions & metrics
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isCurrent = NowPlayingState.currentId == t.id

        if (showTrackNumber) {
            val numStr = if (t.trackNumber > 0) t.trackNumber.toString().padStart(2, '0') else "—"
            Text(
                numStr,
                color = if (isCurrent) MikuTealBright else Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                modifier = Modifier.width(26.dp)
            )
            Spacer(Modifier.width(6.dp))
        }

        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            AlbumArtImage(trackId = t.id, modifier = Modifier.fillMaxSize(), trackPath = t.path)
            if (isCurrent) {
                Box(Modifier.matchParentSize().background(Color(0xAA04161A)), contentAlignment = Alignment.Center) {
                    EqualizerBars(NowPlayingState.playing, Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                color = if (isCurrent) MikuTeal else Color(0xFFE8F4F2),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Baloo2Font
            )
            Spacer(Modifier.height(1.dp))
            Text(meta, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Baloo2Font)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metrics, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = OrbitronFont, modifier = Modifier.weight(1f, fill = false))
                // Bit-depth + sample-rate chips, each with their own bespoke color/shape — resolves
                // async from the header cache, appears when known.
                Spacer(Modifier.width(6.dp))
                TechBadgeRow(ctx, t)
                if (playCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("▶ $playCount", color = MikuTealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        RainbowHeart(LikeStore.isLiked(t.id)) { LikeStore.toggle(ctx, t) }
    }
    if (showSheet) TrackActionModalSheet(t) { showSheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TrackActionModalSheet(t: Track, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val playCount = PlayerPreferences.loadPlayCount(ctx, t.id) // see TrackRow — deliberately not remembered
    val lastPlayed = remember(t.id) { PlayerPreferences.loadLastPlayedAt(ctx, t.id) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArtImage(t.id, Modifier.size(74.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = MikuTealBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = Color(0xFFD4E8E5), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.album.isNotBlank()) Text(
                        t.album + if (t.year > 0) "  ·  ${t.year}" else "",
                        color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RainbowHeart(LikeStore.isLiked(t.id)) { LikeStore.toggle(ctx, t) }
            }

            Spacer(Modifier.height(14.dp))

            // Quick Queue Action Row (YouTube / Spotify Style)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Play Next Button
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F2B2E))
                        .border(1.2.dp, MikuTeal, RoundedCornerShape(14.dp))
                        .clickable {
                            PlayerHolder.player?.let { p -> QueueManager.playNext(ctx, p, listOf(t)) }
                            onDismiss()
                        }
                        .padding(vertical = 11.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlaylistPlay, "Play Next", tint = MikuTealBright, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play Next", color = MikuTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                // Add to Queue / Play Last Button
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C081A))
                        .border(1.2.dp, MikuPink, RoundedCornerShape(14.dp))
                        .clickable {
                            PlayerHolder.player?.let { p -> QueueManager.playLast(ctx, p, listOf(t)) }
                            onDismiss()
                        }
                        .padding(vertical = 11.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlaylistAdd, "Add to Queue", tint = MikuPink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add to Queue", color = MikuPink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
            if (t.trackNumber > 0) MetricRow("Track #", "#${t.trackNumber}", MikuTealBright)
            MetricRow("Format", t.mime.substringAfterLast('/').uppercase().ifBlank { "—" }, formatColor(t.mime))
            TrackTech.bitsFor(ctx, t)?.takeIf { it > 0 }?.let { MetricRow("Bit depth", "$it-bit", TrackTech.color(it)) }
            MetricRow("Bitrate", if (t.bitrateKbps > 0) "${t.bitrateKbps} kbps" else "—", bitrateColor(t.bitrateKbps))
            MetricRow("Duration", fmtDuration(t.durationMs))
            MetricRow("Size", if (t.sizeBytes > 0) "${"%.1f".format(t.sizeBytes / 1e6)} MB" else "—")
            if (t.year > 0) MetricRow("Year", "${t.year}", MikuGold)
            MetricRow("Play count", "$playCount", if (playCount > 0) MikuTeal else Muted)
            if (lastPlayed > 0) MetricRow("Last played", relTime(lastPlayed))
            if (t.path.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("PATH", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(t.path, color = Color(0xFFB8D4D0), fontSize = 11.sp)
            }
        }
    }
}

@Composable private fun MetricRow(label: String, value: String, valueColor: Color = Color(0xFFD4E8E5)) = Row(
    Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically
) {
    Text(label.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
    Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

private fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun relTime(then: Long): String {
    val d = System.currentTimeMillis() - then
    if (d < 0) return "just now"
    val min = d / 60000
    val hr = min / 60
    val day = hr / 24
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        hr < 24 -> "${hr}h ago"
        day < 30 -> "${day}d ago"
        else -> "${day / 30}mo ago"
    }
}

/** Compact art+title+subtitle tile for Home's "Newly Added" rows, with a small pulsing "NEW"
 *  corner tag whose glow strength follows the item's own Newness prominence (louder for
 *  brand-new/unplayed, quieter once it's been played or is near the end of its 7-day window). */
@Composable private fun NewItemCard(
    artOf: Long?,
    artPath: String?,
    title: String,
    subtitle: String,
    prominence: Float,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit
) {
    Column(Modifier.width(104.dp).padding(4.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(96.dp).clip(shape)) {
            if (artOf != null) AlbumArtImage(trackId = artOf, modifier = Modifier.fillMaxSize(), trackPath = artPath ?: "")
            else Box(Modifier.fillMaxSize().background(Color(0xFF123438)))
            if (prominence > 0f) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MikuPink.copy(alpha = 0.35f + 0.5f * prominence))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text("NEW", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(title, color = Color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Home banner's "Play Mix" button — embossed (raised face, drop shadow, top specular hairline,
 *  springy press-scale, haptic tick) like every other hardware-style control in the app, PLUS a
 *  "chasing lights" animated border: a Miku-palette sweep gradient continuously rotating around
 *  the pill outline, the classic marquee-border effect. Was a flat two-color gradient fill with
 *  no shadow/bevel at all — read as inert next to the rest of the app's raised-key language. */
@Composable private fun PlayMixButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(), label = "playMixScale")

    val rgbTransition = rememberInfiniteTransition(label = "playMixRgb")
    val colorPhase by rgbTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(4000, easing = LinearEasing)),
        label = "colorPhase"
    )

    val glowPulse by rgbTransition.animateFloat(
        initialValue = 0.55f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(animation = tween(2000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "glowPulse"
    )

    val currentRgb1 = remember(colorPhase) {
        val colors = listOf(MikuTeal, MikuPink, MikuGold, MikuTealBright, MikuPurple, MikuTeal)
        val pos = colorPhase * (colors.size - 1)
        val idx = pos.toInt().coerceIn(0, colors.size - 2)
        val fraction = pos - idx
        androidx.compose.ui.graphics.lerp(colors[idx], colors[idx + 1], fraction)
    }

    val currentRgb2 = remember(colorPhase) {
        val colors = listOf(MikuPink, MikuGold, MikuTealBright, MikuPurple, MikuTeal, MikuPink)
        val pos = colorPhase * (colors.size - 1)
        val idx = pos.toInt().coerceIn(0, colors.size - 2)
        val fraction = pos - idx
        androidx.compose.ui.graphics.lerp(colors[idx], colors[idx + 1], fraction)
    }

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .drawBehind {
                val rr = CornerRadius(20.dp.toPx(), 20.dp.toPx())
                val borderThickness = 2.2.dp.toPx()

                // 1. Glowing RGB Aura Ring behind button
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(currentRgb1.copy(alpha = 0.45f * glowPulse), Color.Transparent),
                        center = center,
                        radius = size.width * 0.75f
                    ),
                    cornerRadius = rr
                )

                // 2. Smooth Static Pill RGB Border Ring (Color-cycling in place)
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(currentRgb1, currentRgb2),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    ),
                    cornerRadius = rr,
                    style = Stroke(borderThickness)
                )

                // 3. Raised key face inset within the RGB ring
                val pad = borderThickness
                val tl = Offset(pad, pad)
                val sz = androidx.compose.ui.geometry.Size(size.width - 2 * pad, size.height - 2 * pad)
                val faceRR = CornerRadius(20.dp.toPx() - pad, 20.dp.toPx() - pad)
                drawRoundRect(Color(0x59000000), topLeft = Offset(pad, pad + 2f), size = sz, cornerRadius = faceRR)
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(MikuTealBright, MikuPink),
                        startY = pad,
                        endY = pad + sz.height
                    ),
                    topLeft = tl,
                    size = sz,
                    cornerRadius = faceRR
                )
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color(0x8CFFFFFF), Color(0x00FFFFFF)),
                        startY = pad,
                        endY = pad + sz.height * 0.6f
                    ),
                    topLeft = tl,
                    size = sz,
                    cornerRadius = faceRR,
                    style = Stroke(1.2f)
                )
            }
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                Haptics.tick(ctx)
                onClick()
            }
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF04161A), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Play Mix", color = Color(0xFF04161A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * App settings — a real screen (not a modal), reached by tapping the header's Miku logo/wordmark.
 * Consolidates what used to be scattered one-off toggles (Artists sort order, auto-viz) into one
 * place. Every toggle here is backed by an existing PlayerPreferences key, so nothing about how
 * these settings are STORED changed — this is purely giving them a real front door.
 */
enum class SettingsCategory(val title: String, val icon: String) {
    GENERAL("General", "⚙️"),
    DISPLAY("Display", "🌙"),
    ALARMS("Alarms", "⏰"),
    ABOUT("About", "ℹ️")
}

@Composable private fun SettingsScreen(ctx: android.content.Context, tracks: List<Track>, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    var selectedCategory by remember { mutableStateOf(SettingsCategory.GENERAL) }
    var sortIgnoreThe by remember { mutableStateOf(PlayerPreferences.loadSortIgnoreThe(ctx)) }
    var autoViz by remember { mutableStateOf(PlayerPreferences.loadAutoViz(ctx)) }
    var idleDim by remember { mutableStateOf(IdleController.enabled) }
    var ambient by remember { mutableStateOf(IdleController.ambientEnabled) }
    var idleActiveSec by remember { mutableStateOf(IdleController.activeSec) }
    var idleDimSec by remember { mutableStateOf(IdleController.dimSec) }
    var idleAmbientSec by remember { mutableStateOf(IdleController.ambientSec) }

    Box(Modifier.fillMaxSize().background(Ground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                com.miku.player.ui.MikuBackButton(onClick = onClose)
                Spacer(Modifier.width(8.dp))
                Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            }

            // Tab Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF071B20))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SettingsCategory.values().forEach { cat ->
                    val isSel = cat == selectedCategory
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) MikuTealBright.copy(alpha = 0.25f) else Color.Transparent)
                            .border(1.dp, if (isSel) MikuTealBright else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable {
                                Haptics.tick(ctx)
                                selectedCategory = cat
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${cat.icon} ${cat.title}",
                            color = if (isSel) Color.White else Muted,
                            fontSize = 11.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                when (selectedCategory) {
                    SettingsCategory.GENERAL -> {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Brush.horizontalGradient(listOf(Color(0xFF0F3238), Color(0xFF071B20))))
                                    .border(1.dp, MikuTealBright.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                    .clickable {
                                        ctx.startActivity(android.content.Intent(ctx, HardwareSettingsActivity::class.java))
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🎛️", fontSize = 22.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("M500 Hardware & DAC Settings", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("Dual CS43198 DAC, Filters, Gain, Fn Switch, USB DAC", color = MikuTealBright, fontSize = 11.5.sp)
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MikuTealBright, modifier = Modifier.size(18.dp).rotate(180f))
                            }
                        }
                        item { SettingsSection("Library") }
                        item { StorageAccessCard(ctx) }
                        item { MikuSyncCard(ctx) }
                        item {
                            SettingsToggleRow(
                                title = "Sort artists ignoring \"The\"",
                                subtitle = "\"The Beatles\" files under B, not T",
                                checked = sortIgnoreThe
                            ) { sortIgnoreThe = it; PlayerPreferences.saveSortIgnoreThe(ctx, it) }
                        }
                        item { SettingsSection("Playback") }
                        item {
                            SettingsToggleRow(
                                title = "Auto-start visualizer",
                                subtitle = "Now Playing opens straight into the ProjectM visualizer",
                                checked = autoViz
                            ) { autoViz = it; PlayerPreferences.saveAutoViz(ctx, it) }
                        }
                        item { SettingsSection("Last.fm") }
                        item { LastFmCard(ctx) }
                    }
                    SettingsCategory.DISPLAY -> {
                        item { SettingsSection("Idle Screen Pipeline") }
                        item {
                            SettingsToggleRow(
                                title = "Dim when idle",
                                subtitle = "Runs the whole stage pipeline below; off = always full brightness",
                                checked = idleDim
                            ) { idleDim = it; IdleController.enabled = it; PlayerPreferences.saveIdleDimEnabled(ctx, it) }
                        }
                        item {
                            SettingsToggleRow(
                                title = "Ambient display (AOD)",
                                subtitle = "Minimal clock + track screen as its own stage before sleep",
                                checked = ambient
                            ) { ambient = it; IdleController.ambientEnabled = it; PlayerPreferences.saveAmbientEnabled(ctx, it) }
                        }
                        item {
                            IdleTimingRow("Full brightness", "How long after the last touch before dimming", idleActiveSec, 10f..300f) {
                                idleActiveSec = it; IdleController.activeSec = it; PlayerPreferences.saveIdleActiveSec(ctx, it)
                            }
                        }
                        item {
                            IdleTimingRow("Dimmed", "How long dimmed-but-visible lasts before ambient", idleDimSec, 10f..300f) {
                                idleDimSec = it; IdleController.dimSec = it; PlayerPreferences.saveIdleDimSec(ctx, it)
                            }
                        }
                        item {
                            IdleTimingRow("Ambient (AOD)", "How long the clock screen lasts before the display sleeps", idleAmbientSec, 10f..180f) {
                                idleAmbientSec = it; IdleController.ambientSec = it; PlayerPreferences.saveIdleAmbientSec(ctx, it)
                            }
                        }
                        item { ScreenOffPermissionCard(ctx) }
                    }
                    SettingsCategory.ALARMS -> {
                        item { SettingsSection("Alarms") }
                        item { AlarmsCard(ctx, tracks) }
                    }
                    SettingsCategory.ABOUT -> {
                        item { SettingsSection("About") }
                        item { AboutCard(ctx) }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

/** Settings' About card — publisher mark, contact, and copyright. The logo opens
 *  falcontechnix.com in the device's own browser (a real ACTION_VIEW intent, not an in-app/AMP
 *  view), and the email opens a mail composer via mailto:. */
@Composable private fun AboutCard(ctx: android.content.Context) {
    val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Surface1, Color(0xFF0A2528))))
            .border(1.dp, MikuTealBright.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text("Miku Music Player", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
        Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Muted, fontSize = 12.5.sp)

        Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(MikuArt.falconTechnixLogo),
            contentDescription = "Falcon Technix — opens falcontechnix.com",
            modifier = Modifier
                .height(56.dp)
                .clickable {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://falcontechnix.com"))
                        )
                    }
                }
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Justin@FalconTechnix.com",
            color = MikuTealBright,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:Justin@FalconTechnix.com"))
                    )
                }
            }
        )
        Spacer(Modifier.height(10.dp))
        Text("© $year Falcon Technix. All rights reserved.", color = Muted, fontSize = 11.5.sp)
    }
}

/** Settings' storage-access card — surfaces MANAGE_EXTERNAL_STORAGE status and a one-tap request.
 *  Without it, the scanner's own SD-card ghost-row check silently falls back to trusting MediaStore
 *  rather than verifying (see mediaStoreRowLikelyValid()'s doc comment in Model.kt for the concrete
 *  live-confirmed bug this exists to fix — a real library was undercounted by ~2000 tracks because
 *  java.io.File can't see removable storage on API 33+ without this permission). Android forces
 *  this through a dedicated Settings screen (no normal runtime dialog), so this card is that
 *  request's front door rather than something that could be silently auto-granted. */
@Composable private fun StorageAccessCard(ctx: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < 30) return // MANAGE_EXTERNAL_STORAGE doesn't exist before API 30 — scoped storage's SD-card restriction doesn't either
    var granted by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = android.os.Environment.isExternalStorageManager()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    if (granted) return // nothing to show once it's granted — nothing to nag about

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFF6B6B).copy(alpha = 0.12f))
            .clickable {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, "Warning", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Grant \"All files access\" for a 100% accurate scan on SD cards — tap to open Settings.",
            color = Color(0xFFFF6B6B), fontSize = 11.5.sp, lineHeight = 15.sp
        )
    }
    Spacer(Modifier.height(8.dp))
}

/** One row of the idle pipeline's configurable stage durations — label, live value, a stepped
 *  (10s) slider. Reused for all three stages so they read as one consistent control set. */
@Composable private fun IdleTimingRow(title: String, subtitle: String, valueSec: Int, range: ClosedFloatingPointRange<Float>, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color(0xFFE8F4F2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Baloo2Font)
            Text("${valueSec}s", color = MikuTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Baloo2Font)
        }
        Text(subtitle, color = Muted, fontSize = 11.sp, fontFamily = Baloo2Font)
        androidx.compose.material3.Slider(
            value = valueSec.toFloat(),
            onValueChange = { onChange(((it / 10f).toInt() * 10).coerceAtLeast(10)) },
            valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = MikuTealBright, activeTrackColor = MikuTeal, inactiveTrackColor = Surface1)
        )
    }
}

/** The final idle stage (real display sleep) needs WRITE_SETTINGS — special app access, never
 *  silently granted (same family as StorageAccessCard above). Without it the pipeline just stops
 *  at Ambient forever; this banner is the only thing that explains why and offers the fix. */
@Composable private fun ScreenOffPermissionCard(ctx: android.content.Context) {
    var granted by remember { mutableStateOf(ScreenOffHelper.hasPermission(ctx)) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = ScreenOffHelper.hasPermission(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    if (granted) return

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFF6B6B).copy(alpha = 0.12f))
            .clickable { ScreenOffHelper.requestPermission(ctx) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, "Warning", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Grant \"Modify system settings\" so the idle screen can actually sleep after Ambient, not just stop there — tap to open Settings.",
            color = Color(0xFFFF6B6B), fontSize = 11.5.sp, lineHeight = 15.sp
        )
    }
    Spacer(Modifier.height(8.dp))
}

/** Entirely hidden when root isn't available — these are pure bonus features on top of an app
 *  that's fully functional without them (RootShell's whole design point), not something to nag a
 *  non-rooted user about. Checked async (root-shell calls are blocking I/O) and simply renders
 *  nothing until/unless that check comes back true. */
/** Sudo/Root features section — master toggle defaulted to OFF with a fullscreen Miku consent modal. */
private fun gracefulAppRestart(ctx: android.content.Context) {
    try {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (intent != null) {
            ctx.startActivity(intent)
        }
        (ctx as? android.app.Activity)?.finishAffinity()
        Runtime.getRuntime().exit(0)
    } catch (_: Throwable) {
        (ctx as? android.app.Activity)?.recreate()
    }
}

/** Sudo/Root features section — master toggle defaulted to OFF with confirmation modals and graceful restart. */
@Composable private fun RootFeaturesSection(ctx: android.content.Context) {
    val scope = rememberCoroutineScope()
    var rootMasterEnabled by remember { mutableStateOf(PlayerPreferences.loadRootEnabled(ctx)) }
    var showConsentModal by remember { mutableStateOf(false) }
    var showDisableModal by remember { mutableStateOf(false) }
    var pulsarOn by remember { mutableStateOf(PlayerPreferences.loadPulsarEnabled(ctx)) }
    var cpuPerfOn by remember { mutableStateOf(PlayerPreferences.loadCpuPerfEnabled(ctx)) }

    SettingsSection("Hardware & System")

    SettingsToggleRow(
        title = "Elevated Hardware Access (Root)",
        subtitle = if (rootMasterEnabled) "Active · Direct kernel LED and CPU scaling enabled" else "Disabled · Tap to grant privileged hardware access",
        checked = rootMasterEnabled
    ) { enabled ->
        if (enabled) {
            scope.launch {
                val available = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    RootShell.recheck()
                }
                if (available) {
                    PlayerPreferences.saveRootEnabled(ctx, true)
                    rootMasterEnabled = true
                    android.widget.Toast.makeText(ctx, "✓ Privileged hardware access enabled", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(ctx, "⚠ Root request denied or unavailable", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            scope.launch {
                PlayerPreferences.saveRootEnabled(ctx, false)
                PlayerPreferences.savePulsarEnabled(ctx, false)
                PlayerPreferences.saveCpuPerfEnabled(ctx, false)
                pulsarOn = false
                cpuPerfOn = false
                rootMasterEnabled = false
                PulsarLight.setEnabled(ctx, false)
                CpuPerformance.setEnabled(ctx, false)
                RootShell.close()
                android.widget.Toast.makeText(ctx, "✓ Standard permissions restored", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (rootMasterEnabled) {
        SettingsToggleRow(
            title = "Pulsar RGB Master Control",
            subtitle = "Direct TrueColor RGB PWM & silicon hardware driver control",
            checked = pulsarOn
        ) { pulsarOn = it; scope.launch { PulsarLight.setEnabled(ctx, it) } }

        if (pulsarOn) {
            PulsarSettingsCard(ctx)
            Spacer(Modifier.height(10.dp))
        }

        SettingsToggleRow(
            title = "Performance Governor",
            subtitle = "Locks CPU cores at peak clock while player is open for zero-latency DSD audio",
            checked = cpuPerfOn
        ) { cpuPerfOn = it; scope.launch { CpuPerformance.setEnabled(ctx, it) } }
    }
}

@Composable private fun PulsarSettingsCard(ctx: android.content.Context) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(PulsarLight.getMode(ctx)) }
    var brightness by remember { mutableStateOf(PulsarLight.getBrightness(ctx)) }
    var bpmSync by remember { mutableStateOf(PulsarLight.isBpmSyncEnabled(ctx)) }
    var animSpeed by remember { mutableStateOf(PulsarLight.getAnimationSpeed(ctx)) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF072428), Color(0xFF041417))))
            .border(1.dp, MikuTealBright.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Pulsar Cyber RGB Engine", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
            Text("✨ ACTIVE", color = MikuTealBright, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(6.dp))
        Text(mode.description, color = Muted, fontSize = 11.sp)

        Spacer(Modifier.height(10.dp))
        // Mode Selector Chips
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(PulsarLight.Mode.values().filter { it != PulsarLight.Mode.OFF }) { m ->
                val isSel = m == mode
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) MikuTealBright.copy(alpha = 0.3f) else Color(0xFF0A3036))
                        .border(1.dp, if (isSel) MikuTealBright else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable {
                            mode = m
                            scope.launch { PulsarLight.setMode(ctx, m, brightness) }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(m.label, color = if (isSel) Color.White else Muted, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Peak Brightness", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("$brightness / 255", color = MikuTealBright, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }
        androidx.compose.material3.Slider(
            value = brightness.toFloat(),
            onValueChange = {
                brightness = it.toInt()
                scope.launch { PulsarLight.setMode(ctx, mode, brightness) }
            },
            valueRange = 10f..255f,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = MikuTealBright, activeTrackColor = MikuTeal, inactiveTrackColor = Surface1)
        )

        if (mode == PulsarLight.Mode.CHROMA_RAINBOW) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Spectrum Cycle Speed", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("${String.format("%.1f", animSpeed)}x", color = MikuPink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
            androidx.compose.material3.Slider(
                value = animSpeed,
                onValueChange = {
                    animSpeed = it
                    scope.launch { PulsarLight.setAnimationSpeed(ctx, it) }
                },
                valueRange = 0.5f..3.0f,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = MikuPink, activeTrackColor = MikuPink, inactiveTrackColor = Surface1)
            )
        }

        if (mode == PulsarLight.Mode.AUDIOPHILE_AUTO) {
            SettingsToggleRow(
                title = "Live BPM Rhythm Sync",
                subtitle = "Cosine modulation in sync with playing track tempo",
                checked = bpmSync
            ) {
                bpmSync = it
                scope.launch { PulsarLight.setBpmSyncEnabled(ctx, it) }
            }
        }
    }
}

@Composable private fun MikuSyncCard(ctx: android.content.Context) {
    val syncState by MikuSyncTransceiver.state.collectAsState()
    var isManualScanning by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF07272B), Color(0xFF041417))))
            .border(1.dp, MikuTealBright.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎵", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text("Miku Media & Rsync Monitor", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (syncState.isTransferring) MikuPink.copy(alpha = 0.3f) else MikuTeal.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    if (syncState.isTransferring) "⚡ SYNCING" else "🟢 DAEMON LIVE",
                    color = if (syncState.isTransferring) MikuPink else MikuTealBright,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Transport Link", color = Muted, fontSize = 12.sp)
            Text(syncState.transport.badge, color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Target Ingress", color = Muted, fontSize = 12.sp)
            Text("rsync://${syncState.ipAddress}:${MikuSyncTransceiver.RSYNC_PORT}/music", color = Color.White, fontSize = 11.5.sp, fontFamily = AudiowideFont)
        }

        if (syncState.isTransferring) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Live Transfer Speed", color = MikuPink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${String.format("%.1f", syncState.transferRateMBs)} MB/s", color = MikuPink, fontSize = 13.sp, fontWeight = FontWeight.Black)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MikuTeal.copy(alpha = 0.25f))
                .border(1.dp, MikuTealBright.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .clickable {
                    isManualScanning = true
                    MikuSyncTransceiver.scanAndIntegrateDirectory(ctx, MikuSyncTransceiver.getSdMusicPath(ctx)) { count ->
                        isManualScanning = false
                        android.widget.Toast.makeText(ctx, "✓ Ingested $count audio files into library", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (isManualScanning) "Scanning SD Ingress..." else "⚡ Rescan SD Staging & MUSIC", color = MikuTealBright, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun FnSwitchSettingsSection(ctx: android.content.Context) {
    val cr = ctx.contentResolver
    var fnMode by remember {
        mutableStateOf(android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock")
    }
    var allowVolume by remember {
        mutableStateOf(android.provider.Settings.Global.getInt(cr, "m500_fn_allow_volume_wheel", 0) == 1)
    }
    var lockPower by remember {
        mutableStateOf(android.provider.Settings.Global.getInt(cr, "m500_fn_lock_power_button", 1) == 1)
    }

    val isRooted = RootShell.isAllowed(ctx)
    val isPocket = fnMode == "touch_and_key_lock" || fnMode == "Both"

    SettingsSection("Hardware Fn Switch")

    // Driver Status Card
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isRooted) Color(0xFF07272B) else Color(0xFF2B2407))
            .border(1.dp, if (isRooted) MikuTealBright.copy(alpha = 0.3f) else Color(0xFFFFD166).copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (isRooted) "🟢" else "🟡", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (isRooted) "Kernel Hardware Lock Active" else "Fallback In-App Touch Guard Active",
                color = if (isRooted) MikuTealBright else Color(0xFFFFD166),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (isRooted) "Direct Goodix digitizer inhibition (/sys/class/input/input3/inhibited) and hardware DAC safety enabled."
            else "Standard mode. Enable Root Access in settings below to activate direct kernel digitizer locking.",
            color = Muted,
            fontSize = 11.5.sp,
            lineHeight = 15.sp
        )
    }

    Spacer(Modifier.height(10.dp))

    SettingsToggleRow(
        title = "Pocket Lock (Touch & Key Lock)",
        subtitle = if (isPocket) "Locks touchscreen digitizer and side buttons simultaneously" else "Disabled · Standard key lock only",
        checked = isPocket
    ) { enabled ->
        val newMode = if (enabled) "touch_and_key_lock" else "key_lock"
        fnMode = newMode
        android.provider.Settings.Global.putString(cr, "fn_settings", newMode)
    }

    if (isPocket) {
        SettingsToggleRow(
            title = "Allow Volume Wheel during Lock",
            subtitle = "Keep physical rotary volume knob active while touch/buttons are locked",
            checked = allowVolume
        ) { enabled ->
            allowVolume = enabled
            android.provider.Settings.Global.putInt(cr, "m500_fn_allow_volume_wheel", if (enabled) 1 else 0)
        }

        SettingsToggleRow(
            title = "Lock Power Button in Pocket",
            subtitle = "Prevent screen from waking up when power button is bumped",
            checked = lockPower
        ) { enabled ->
            lockPower = enabled
            android.provider.Settings.Global.putInt(cr, "m500_fn_lock_power_button", if (enabled) 1 else 0)
        }
    }
}

@Composable private fun RootDisableConfirmModal(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD904161A))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Surface1)
                .border(1.dp, MikuTealBright.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("DISABLE PRIVILEGED ACCESS?", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            Spacer(Modifier.height(10.dp))
            Text(
                "Direct hardware LED sync, CPU scaling, and kernel touchlocks will be deactivated and return to standard unprivileged mode.",
                color = Muted,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF6B6B), Color(0xFFFF8E53))))
                    .clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center
            ) {
                Text("DISABLE PRIVILEGED ACCESS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text("CANCEL", color = Color.White.copy(alpha = 0.8f), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun RootConsentModal(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF04161A))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))

                // Miku 01 Cyberpunk Badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MikuPink.copy(alpha = 0.2f))
                        .border(1.dp, MikuPink, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("01 · ELEVATED HARDWARE ACCESS", color = MikuPink, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }

                Spacer(Modifier.height(16.dp))
                Text("SUPERUSER GATEWAY", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                Spacer(Modifier.height(6.dp))
                Text("HiBy M500 Hatsune Miku Edition", color = MikuTealBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                Spacer(Modifier.height(28.dp))

                // Feature Highlights Card
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface1)
                        .border(1.dp, MikuTealBright.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    ConsentFeatureRow("⚡", "Pulsar RGB LED Synchronization", "Writes directly to the front notification LED kernel nodes to pulse signature Miku colors to audio FFT.")
                    Spacer(Modifier.height(14.dp))
                    ConsentFeatureRow("🏎️", "Qualcomm High-Performance Governor", "Pins Snapdragon 680 performance cores to eliminate buffer underruns during bit-perfect DSD256 decoding.")
                    Spacer(Modifier.height(14.dp))
                    ConsentFeatureRow("🛡️", "Hardware Pocket Lock Controls", "Controls touchscreen digitizer inhibition and button routing when the Fn physical switch is toggled.")
                }

                Spacer(Modifier.height(20.dp))

                // Notice
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F2B2E))
                        .padding(14.dp)
                ) {
                    Text(
                        "Music playback operates normally with standard permissions. Enabling this option connects to Magisk / KernelSU for direct hardware control.",
                        color = Muted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                // Confirm Button
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(25.dp))
                        .background(Brush.horizontalGradient(listOf(MikuTealBright, Color(0xFF00E5FF))))
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center
                ) {
                    Text("ENABLE PRIVILEGED ACCESS", color = Color(0xFF04161A), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
                }

                Spacer(Modifier.height(10.dp))

                // Cancel Button
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text("CANCEL", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable private fun ConsentFeatureRow(icon: String, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 18.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(description, color = Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
    }
}

private val DAY_LABELS = listOf(1 to "Su", 2 to "Mo", 3 to "Tu", 4 to "We", 5 to "Th", 6 to "Fr", 7 to "Sa")

private fun alarmSummary(a: Alarm): String {
    val time = when (a.mode) {
        AlarmTriggerMode.CLOCK_TIME -> String.format("%02d:%02d", a.hour, a.minute)
        AlarmTriggerMode.SUNRISE -> "Sunrise"
        AlarmTriggerMode.SUNSET -> "Sunset"
        AlarmTriggerMode.SUNRISE_OR_SUNSET -> "Sunrise/Sunset"
    }
    val days = if (a.repeatDays.isEmpty()) "One-time" else DAY_LABELS.filter { it.first in a.repeatDays }.joinToString(" ") { it.second }
    return "$time · $days"
}

/** Settings' Alarms card — list of configured alarms (music-based, fade-in, snooze, sunrise/sunset
 *  or clock-time trigger) plus an add/edit dialog. Scheduling itself lives in [[AlarmScheduler]];
 *  this is purely the editor UI over the JSON list in [[AlarmPreferences]]. Every write goes through
 *  AlarmPreferences' atomic update helpers (fresh read-modify-write against disk) rather than a
 *  locally `remember`ed snapshot, so an edit made here can never clobber a concurrent write from
 *  AlarmRingService (e.g. a one-shot alarm auto-disabling itself right after it fires). */
@Composable private fun AlarmsCard(ctx: android.content.Context, tracks: List<Track>) {
    var alarms by remember { mutableStateOf(AlarmPreferences.loadAlarms(ctx)) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val activeSnoozeId by remember { mutableStateOf(AlarmPreferences.loadActiveSnooze(ctx)) }
    val exactAlarmsAllowed = remember {
        (ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
    }

    fun refresh() { alarms = AlarmPreferences.loadAlarms(ctx) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface1)
            .padding(16.dp)
    ) {
        if (!exactAlarmsAllowed) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFF6B6B).copy(alpha = 0.15f))
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, "Warning", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Exact alarms aren't permitted — alarms won't fire reliably. Tap to fix in Settings.", color = Color(0xFFFF6B6B), fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        if (alarms.isEmpty()) {
            Text("No alarms yet — music-based, fade-in, snooze, and sunrise/sunset triggers all live here.", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(12.dp))
        } else {
            alarms.sortedBy { it.hour * 60 + it.minute }.forEachIndexed { idx, a ->
                if (idx > 0) Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { editing = a; showEditor = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label.ifBlank { "Alarm" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (activeSnoozeId == a.id) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "SNOOZED", color = MikuGold, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MikuGold.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(alarmSummary(a), color = Muted, fontSize = 11.5.sp)
                    }
                    Icon(
                        Icons.Default.Close, "Delete",
                        tint = Muted, modifier = Modifier.size(16.dp).clickable {
                            AlarmScheduler.cancel(ctx, a.id)
                            AlarmPreferences.removeAlarm(ctx, a.id)
                            refresh()
                        }
                    )
                    Spacer(Modifier.width(14.dp))
                    HeartToggle(checked = a.enabled, onCheckedChange = { on ->
                        AlarmPreferences.updateAlarm(ctx, a.id) { it.copy(enabled = on) }
                        refresh()
                        AlarmScheduler.rescheduleAll(ctx)
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MikuTealBright.copy(alpha = 0.15f))
                .clickable { editing = null; showEditor = true }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text("+ Add alarm", color = MikuTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (showEditor) {
        AlarmEditorDialog(
            ctx = ctx,
            tracks = tracks,
            initial = editing,
            onDismissRequest = { showEditor = false },
            onSave = { a ->
                AlarmPreferences.upsertAlarm(ctx, a)
                refresh()
                AlarmScheduler.rescheduleAll(ctx)
                showEditor = false
            }
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun AlarmEditorDialog(ctx: android.content.Context, tracks: List<Track>, initial: Alarm?, onDismissRequest: () -> Unit, onSave: (Alarm) -> Unit) {
    var hour by remember { mutableStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }
    var mode by remember { mutableStateOf(initial?.mode ?: AlarmTriggerMode.CLOCK_TIME) }
    var repeatDays by remember { mutableStateOf(initial?.repeatDays ?: emptySet()) }
    var label by remember { mutableStateOf(initial?.label ?: "Alarm") }
    var fadeInSeconds by remember { mutableStateOf(initial?.fadeInSeconds ?: 30) }
    var snoozeMinutes by remember { mutableStateOf(initial?.snoozeMinutes ?: 9) }
    var source by remember { mutableStateOf(initial?.source ?: AlarmSource.SHUFFLE_ALL) }
    var sourceRef by remember { mutableStateOf(initial?.sourceRef) }
    var showSourcePicker by remember { mutableStateOf(false) }
    val artistNames = remember(tracks) { tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().sorted() }
    val albumNames = remember(tracks) { tracks.map { it.album }.filter { it.isNotBlank() }.distinct().sorted() }

    // A real platform Dialog rather than this file's usual in-app Box-overlay pattern: AlarmsCard
    // (and therefore this) is invoked from inside SettingsScreen's LazyColumn, which hands every
    // item() an unbounded max height — a Box(fillMaxSize()) there inherits that infinity straight
    // through to the Column().verticalScroll() below and Compose hard-crashes ("infinity maximum
    // height constraints"). A Dialog draws in its own window with real bounded constraints, so the
    // scrollable content inside measures correctly regardless of what's mounted it.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Surface1)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(if (initial == null) "New Alarm" else "Edit Alarm", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            Spacer(Modifier.height(16.dp))

            Text("Trigger", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AlarmTriggerMode.CLOCK_TIME to "Time",
                    AlarmTriggerMode.SUNRISE to "Sunrise",
                    AlarmTriggerMode.SUNSET to "Sunset",
                    AlarmTriggerMode.SUNRISE_OR_SUNSET to "Both"
                ).forEach { (m, txt) -> AlarmChip(txt, mode == m) { mode = m } }
            }

            if (mode == AlarmTriggerMode.CLOCK_TIME) {
                Spacer(Modifier.height(16.dp))
                Text("Time (24h)", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                // Intentionally 24-hour only, no AM/PM anywhere in the alarm editor — hour steps
                // 0..23 directly and alarmSummary() formats with %02d:%02d, so there's no 12/24
                // ambiguity to get wrong at 10:00 vs 22:00.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeStepper(hour, 0, 23) { hour = it }
                    Text(":", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                    TimeStepper(minute, 0, 59) { minute = it }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Needs a coarse location fix (opportunistic, low-power) to compute — falls back silently if none is available yet.", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
            }

            Spacer(Modifier.height(16.dp))
            Text("Repeat", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DAY_LABELS.forEach { (d, txt) ->
                    AlarmChip(txt, d in repeatDays) {
                        repeatDays = if (d in repeatDays) repeatDays - d else repeatDays + d
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Plays", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlarmChip("Any Random Track", source == AlarmSource.SHUFFLE_ALL) { source = AlarmSource.SHUFFLE_ALL; sourceRef = null }
                AlarmChip("Liked Songs", source == AlarmSource.LIKED_SONGS) { source = AlarmSource.LIKED_SONGS; sourceRef = null }
                AlarmChip("Artist" + (sourceRef?.takeIf { source == AlarmSource.ARTIST }?.let { ": $it" } ?: ""), source == AlarmSource.ARTIST) {
                    source = AlarmSource.ARTIST; showSourcePicker = true
                }
                AlarmChip("Album" + (sourceRef?.takeIf { source == AlarmSource.ALBUM }?.let { ": $it" } ?: ""), source == AlarmSource.ALBUM) {
                    source = AlarmSource.ALBUM; showSourcePicker = true
                }
            }
            if (showSourcePicker && (source == AlarmSource.ARTIST || source == AlarmSource.ALBUM)) {
                Spacer(Modifier.height(8.dp))
                val options = if (source == AlarmSource.ARTIST) artistNames else albumNames
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp)
                ) {
                    if (options.isEmpty()) {
                        Text("Nothing in your library yet.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                    }
                    options.forEach { name ->
                        Text(
                            name,
                            color = if (sourceRef == name) MikuTealBright else Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = if (sourceRef == name) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sourceRef = name; showSourcePicker = false }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Label", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LastFmField(value = label, onValueChange = { label = it }, placeholder = "Alarm")

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Fade-in", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeStepper(fadeInSeconds, 0, 300, step = 5) { fadeInSeconds = it }
                        Text("s", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Column {
                    Text("Snooze", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeStepper(snoozeMinutes, 1, 30) { snoozeMinutes = it }
                        Text("m", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MikuPink)
                    .clickable {
                        AlarmLocation.refreshIfStale(ctx)
                        onSave(
                            Alarm(
                                id = initial?.id ?: AlarmPreferences.nextAlarmId(ctx),
                                enabled = initial?.enabled ?: true,
                                hour = hour, minute = minute, mode = mode, repeatDays = repeatDays,
                                label = label.ifBlank { "Alarm" }, fadeInSeconds = fadeInSeconds,
                                snoozeMinutes = snoozeMinutes, source = source,
                                sourceRef = if (source == AlarmSource.ARTIST || source == AlarmSource.ALBUM) sourceRef else null
                            )
                        )
                    }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("Save", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun AlarmChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.White else Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MikuTealBright.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable private fun TimeStepper(value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("−", color = MikuTealBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
            onChange(if (value - step < min) max else value - step)
        }.padding(horizontal = 12.dp, vertical = 6.dp))
        Text(value.toString().padStart(2, '0'), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Text("+", color = MikuTealBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
            onChange(if (value + step > max) min else value + step)
        }.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** Settings' Last.fm card — username/password sign-in (auth.getMobileSession), connection status,
 *  disconnect. The password field never gets persisted anywhere — [[LastFm.login]] trades it for a
 *  session key in one round trip, and only that key is saved (encrypted, see [[LastFmPreferences]]).
 *  Once connected, [[LastFmScrobbler]] (attached to the shared player in PlayerHolder) drives
 *  now-playing pings and scrobbles automatically — nothing else to wire up from here. */
@Composable private fun LastFmCard(ctx: android.content.Context) {
    val lastFmRed = Color(0xFFD51007)
    var username by remember { mutableStateOf(LastFmPreferences.loadUsername(ctx) ?: "") }
    var password by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(LastFmPreferences.isConnected(ctx)) }
    var connectedAs by remember { mutableStateOf(LastFmPreferences.loadUsername(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Surface1, Color(0xFF2A0A10))))
            .border(1.dp, lastFmRed.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Podcasts, "Last.fm", tint = lastFmRed, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Last.fm Scrobbling", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !LastFm.isConfigured -> "Not configured on this build — no API key set."
                connected -> "Connected as ${connectedAs ?: username}. Every track scrobbles automatically."
                else -> "Sign in to scrobble every track you play to your Last.fm profile."
            },
            color = Muted, fontSize = 12.sp, lineHeight = 16.sp
        )

        if (LastFm.isConfigured) {
            Spacer(Modifier.height(14.dp))
            if (connected) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable {
                            LastFmPreferences.disconnect(ctx)
                            connected = false; connectedAs = null; username = ""; password = ""; error = null
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LinkOff, "Disconnect", tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect", color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                LastFmField(value = username, onValueChange = { username = it; error = null }, placeholder = "Username")
                Spacer(Modifier.height(8.dp))
                LastFmField(value = password, onValueChange = { password = it; error = null }, placeholder = "Password", isPassword = true)
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 11.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (loading) lastFmRed.copy(alpha = 0.35f) else lastFmRed)
                        .clickable(enabled = !loading) {
                            loading = true; error = null
                            scope.launch {
                                when (val r = LastFm.login(username, password)) {
                                    is LastFm.LoginResult.Success -> {
                                        LastFmPreferences.saveSession(ctx, r.username, r.sessionKey)
                                        connected = true; connectedAs = r.username; password = ""
                                    }
                                    is LastFm.LoginResult.Failure -> error = r.message
                                }
                                loading = false
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(if (loading) "Connecting…" else "Connect", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable private fun LastFmField(value: String, onValueChange: (String) -> Unit, placeholder: String, isPassword: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFFE8F4F2), fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFD51007)),
            visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (isPassword) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
                autoCorrect = false
            ),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) Text(placeholder, color = Muted.copy(alpha = 0.7f), fontSize = 13.sp)
                innerTextField()
            }
        )
    }
}

@Composable private fun SettingsSection(title: String) = Text(
    title, color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
)

@Composable private fun SettingsToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) = Row(
    Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .clickable { onCheckedChange(!checked) }
        .padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Column(Modifier.weight(1f)) {
        Text(title, color = Color(0xFFE8F4F2), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
    Spacer(Modifier.width(8.dp))
    HeartToggle(checked = checked, onCheckedChange = null)
}

/** A toggle switch with a little beating heart for a thumb instead of the stock plain circle —
 *  slides between a hollow grey heart (off) and a filled Miku-pink one (on), same track-pill
 *  shape as a normal Switch so it still reads as "a toggle" at a glance. */
@Composable private fun HeartToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val ctx = LocalContext.current
    val thumbOffset by animateDpAsState(if (checked) 24.dp else 2.dp, spring(dampingRatio = 0.55f), label = "heartToggleOffset")
    val trackColor by animateColorAsState(if (checked) MikuTealBright.copy(alpha = 0.28f) else Color(0xFF123438), label = "heartToggleTrack")
    val heartColor by animateColorAsState(if (checked) MikuPink else Muted, label = "heartToggleColor")
    val heartScale by animateFloatAsState(if (checked) 1.08f else 0.9f, spring(dampingRatio = 0.5f), label = "heartToggleScale")
    Box(
        Modifier
            .size(width = 54.dp, height = 30.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .border(1.dp, if (checked) MikuTealBright.copy(alpha = 0.55f) else Color(0x2AFFFFFF), RoundedCornerShape(50))
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        Haptics.tick(ctx)
                        onCheckedChange(!checked)
                    }
                } else Modifier
            )
    ) {
        Icon(
            if (checked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            null,
            tint = heartColor,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(24.dp)
                .scale(heartScale)
        )
    }
}

@Composable private fun CircleArt() = Box(Modifier.size(46.dp).clip(RoundedCornerShape(50)).background(Color(0xFF123438)),
    contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = MikuTeal.copy(alpha = .6f)) }

/**
 * Bespoke key silhouettes for the transport ControlAssembly — prev/next lean toward the play key
 * with an angled hex "wing" cut instead of a plain circle, and play itself is a bigger faceted
 * hero shield, so the cluster reads as one molded, artistically-designed assembly (each key still
 * its own distinct pressable silhouette) rather than identical circles sharing a trough.
 */
object TransportShapes {
    /** Leans right toward center — a shallow point on the play-facing side, square-ish on the far side. */
    val prevWing: Shape = GenericShape { size, _ ->
        val w = size.width; val h = size.height
        val cutNear = h * 0.30f; val cutFar = w * 0.10f
        moveTo(cutFar, 0f)
        lineTo(w - cutNear, 0f)
        lineTo(w, h / 2f)
        lineTo(w - cutNear, h)
        lineTo(cutFar, h)
        lineTo(0f, h * 0.85f)
        lineTo(0f, h * 0.15f)
        close()
    }
    /** Mirror of prevWing — leans left toward center. */
    val nextWing: Shape = GenericShape { size, _ ->
        val w = size.width; val h = size.height
        val cutNear = h * 0.30f; val cutFar = w * 0.10f
        moveTo(cutNear, 0f)
        lineTo(w - cutFar, 0f)
        lineTo(w, h * 0.15f)
        lineTo(w, h * 0.85f)
        lineTo(w - cutFar, h)
        lineTo(cutNear, h)
        lineTo(0f, h / 2f)
        close()
    }
    /** Play/pause hero — a faceted shield, bigger presence than the wings flanking it. */
    val hero: Shape = GenericShape { size, _ ->
        val w = size.width; val h = size.height
        val cutX = w * 0.15f; val cutY = h * 0.28f
        moveTo(cutX, 0f)
        lineTo(w - cutX, 0f)
        lineTo(w, cutY)
        lineTo(w, h - cutY)
        lineTo(w - cutX, h)
        lineTo(cutX, h)
        lineTo(0f, h - cutY)
        lineTo(0f, cutY)
        close()
    }
}

/**
 * Shared "hardware deck" housing for a transport-button cluster — one continuous engraved trough
 * (dark recessed gradient, inset shadow along the top edge, a thin highlight along the bottom) that
 * shuffle/prev/play/next sit inset into, like a real DAP's molded button assembly, instead of each
 * button floating separately on the bar's own background. The buttons themselves are untouched
 * HapticIconButtons — still fully distinct, individually raised/pressable keys — this is only the
 * shared shell that visually merges them into one assembly.
 */
@Composable fun ControlAssembly(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 26.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .drawBehind {
                val rr = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF030C10), Color(0xFF0B2226))), cornerRadius = rr)
                // inset shadow biting in from the top edge (recessed, not raised)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0x8A000000), Color.Transparent), endY = size.height * 0.55f),
                    cornerRadius = rr
                )
                // faint highlight along the bottom edge, like light catching the well's lower lip
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0x1FFFFFFF)), startY = size.height * 0.65f),
                    topLeft = Offset(0f, size.height * 0.65f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.35f),
                    cornerRadius = CornerRadius(rr.x * 0.5f, rr.y * 0.5f)
                )
            }
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(cornerRadius))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // IconButton's own built-in 48dp minimum-touch-target padding was quietly padding each
        // key out past its declared .size(), pushing the wing shapes apart from their neighbours
        // just enough that the "one molded assembly" look fell apart into separate floating keys
        // at the mini bar's smaller button sizes — only really visible once you compared it side
        // by side with the full Now Playing screen's bigger (closer-to-48dp) buttons, which didn't
        // show the gap. Zeroing it here lets keys sit exactly at their declared size, flush against
        // each other, so the interlocking silhouettes actually touch.
        CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
            content()
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun NowPlayingBar(
    track: Track,
    player: ExoPlayer,
    isPlaying: Boolean,
    onBarClick: () -> Unit,
    onToggle: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String, String) -> Unit = { _, _ -> }
) {
    val ctx = LocalContext.current
    val displayYear = remember(track.id) { TrackYear.yearFor(ctx, track)?.takeIf { it > 0 } ?: track.year.takeIf { it > 0 } }
    val albumArtist = remember(track.id) { track.albumArtist.ifBlank { track.artist } }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(track.durationMs.coerceAtLeast(1L)) }
    // Mirrors the same prefs-backed shuffle flag the full Now Playing screen uses — toggling from
    // either place stays in sync since both just read/write PlayerPreferences + player state.
    var shuffle by remember { mutableStateOf(runCatching { player.shuffleModeEnabled }.getOrDefault(PlayerPreferences.loadShuffle(ctx))) }
    LaunchedEffect(track.id) {
        while (true) {
            // This is the most pervasively-mounted poll in the app — alive on every screen the
            // instant a track is queued, not just NowPlaying/Tape. Pause the state write once the
            // real screen is ambient-covered or physically dark (same cutoff as those two).
            try {
                if (!IdleController.visuallyIdle) { pos = player.currentPosition; if (player.duration > 0) dur = player.duration }
            } catch (_: Throwable) {}   // never let a transient player error kill the update loop
            delay(500)
        }
    }
    val progress = (pos.toFloat() / dur.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    // Flush-to-bottom cyber docked NowPlayingBar
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                // Raised panel, not a flat fill: drop shadow below + a top specular hairline, the
                // same emboss language as the scan pill / DataChip / every hardware-style control.
                .drawBehind {
                    val rr = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                    drawRoundRect(Color(0x66000000), topLeft = Offset(0f, 3.dp.toPx()), size = size, cornerRadius = rr)
                }
                .border(1.dp, Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x00FFFFFF)), endY = 0.5f), RoundedCornerShape(16.dp))
                .clickable { onBarClick() }
        ) {
            Box {
                // Themed inlay background: the current track's own album art, blurred + scrimmed —
                // was a flat two-tone gradient regardless of what was playing, reads as inert chrome
                // rather than a "now playing" surface tied to the track.
                AlbumArtImage(
                    track.id,
                    Modifier.matchParentSize().blur(28.dp),
                    trackPath = track.path,
                    contentScale = ContentScale.Crop
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.horizontalGradient(listOf(Color(0xE6124247), Color(0xE60B2A2D)))
                    )
                )
                Column(Modifier.fillMaxWidth()) {
                    // live progress line
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF07201F))) {
                        Box(Modifier.fillMaxWidth(progress).height(3.dp)
                            .background(Brush.horizontalGradient(listOf(MikuTeal, MikuTealBright))))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArtImage(track.id, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).clickable { onBarClick() }, trackPath = track.path)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).clickable { onBarClick() }) {
                            Text(
                                track.title, color = Color(0xFFEAF6F4), fontSize = 14.sp, maxLines = 1,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                            )
                            Text(
                                track.artist,
                                color = MikuTealBright,
                                fontSize = 12.sp,
                                maxLines = 1,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0).clickable { onArtistClick(track.artist) }
                            )
                            if (track.album.isNotBlank()) {
                                Text(
                                    if (displayYear != null) "${track.album}  ·  $displayYear" else track.album,
                                    color = Muted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                                        .clickable { onAlbumClick(albumArtist, track.album) }
                                )
                            }
                    // Bit depth/sample rate chips, each with their own bespoke color/shape —
                    // resolves async off TrackTech's cache like every other badge. Height is
                    // reserved up front (fixed, not wrap-content) so the bar doesn't visibly
                    // grow/shrink every time skip-prev/next swaps to a track whose tech data
                    // hasn't resolved yet — was the "resizes badly when a control is pressed" bug.
                    // Bit-depth/sample-rate badges only here — the mini bar is too width-constrained
                    // to also fit the release year without it overflowing behind the heart/transport
                    // controls (found live: the year text had nowhere to go and drew UNDER the
                    // heart icon). Year has plenty of room and shows clearly in the full Now Playing
                    // screen, the track list, and the album hero instead.
                    // Taller than before: the diamond/pentagon badge shapes (see BadgeShapes) need
                    // more vertical room than the old flatter hex/pill did — 21dp was clipping the
                    // bottom off every badge's text.
                    Row(Modifier.height(28.dp).clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                        TechBadgeRow(ctx, track, fontSize = 10.sp, spacing = 4.dp)
                    }
                }
                RainbowHeart(LikeStore.isLiked(track.id)) { LikeStore.toggle(ctx, track) }
                Spacer(Modifier.width(2.dp))
                ControlAssembly {
                    HapticIconButton(onClick = {
                        shuffle = !shuffle
                        player.shuffleModeEnabled = shuffle
                        PlayerPreferences.saveShuffle(ctx, shuffle)
                    }, flat = true, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) MikuPink else Muted, modifier = Modifier.size(16.dp))
                    }
                    HapticIconButton(onClick = { player.seekToPreviousMediaItem() }, keyShape = TransportShapes.prevWing, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.SkipPrevious, "Prev", tint = Muted, modifier = Modifier.size(22.dp))
                    }
                    HapticIconButton(onClick = onToggle, face = MikuTeal, keyShape = TransportShapes.hero, modifier = Modifier.size(width = 56.dp, height = 42.dp)) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color(0xFF00201D), modifier = Modifier.size(26.dp))
                    }
                    HapticIconButton(onClick = { player.seekToNextMediaItem() }, keyShape = TransportShapes.nextWing, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.SkipNext, "Next", tint = Muted, modifier = Modifier.size(24.dp))
                    }
                }
                    }
                }
            }
        }
    }
}



/** Live-updating number: smoothly counts to new value + flashes Miku-bright on change. */
@Composable private fun AnimatedNumber(
    value: Int,
    modifier: Modifier = Modifier,
    baseColor: Color = MikuTeal,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    suffix: String = ""
) {
    val animated by animateIntAsState(targetValue = value, animationSpec = tween(700, easing = FastOutSlowInEasing), label = "num")
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(value) { flash = true; kotlinx.coroutines.delay(450); flash = false }
    val color by animateColorAsState(if (flash) MikuTealBright else baseColor, tween(400), label = "numColor")
    val scale by animateFloatAsState(if (flash) 1.12f else 1f, spring(dampingRatio = 0.4f), label = "numScale")
    Text("$animated$suffix", color = color, fontSize = fontSize, fontWeight = fontWeight,
        modifier = modifier.scale(scale))
}

@Composable private fun LibraryScreen(
    ctx: android.content.Context,
    listState: LazyListState = rememberLazyListState(),
    trackCount: Int = 0,
    tracks: List<Track> = emptyList(),
    artistCount: Int = 0,
    albumCount: Int = 0,
    loading: Boolean = false,
    videoCount: Int = 0,
    onAllSongs: () -> Unit = {},
    onVideos: () -> Unit = {},
    onOpen: (String) -> Unit
) {
    // LikeStore.liked directly (not a one-shot remember{} of the prefs snapshot, which never
    // updated again after first composition) — this count needs to track live like/unlike taps.
    val liked = LikeStore.liked
    val playlists = remember { PlayerPreferences.loadPlaylists(ctx) }
    // Cheap aggregate pass over what's already in memory — no per-track disk/pref reads, so this
    // is safe to recompute whenever the track list itself changes (a scan finishing, etc).
    val stats = remember(tracks) {
        val totalMs = tracks.sumOf { it.durationMs }
        val totalBytes = tracks.sumOf { it.sizeBytes }
        val fmtCounts = tracks.groupingBy { it.mime.substringAfterLast('/').uppercase().ifBlank { "?" } }.eachCount()
        // Drop anything that would just display as a misleading "0%" (a handful of stray files
        // out of thousands rounds down to nothing and reads like a bug, not real data) — keep a
        // format only once it's at least 1% of the library.
        val fmtTotal = fmtCounts.values.sum().coerceAtLeast(1)
        val topFormats = fmtCounts.entries
            .filter { it.value * 100 / fmtTotal >= 1 }
            .sortedByDescending { it.value }
            .take(4)
        val avgBitrate = tracks.filter { it.bitrateKbps > 0 }.let { l -> if (l.isEmpty()) 0 else l.sumOf { it.bitrateKbps } / l.size }
        val (usedDevice, totalDevice) = deviceStorageStats(tracks)
        LibraryStats(totalMs, totalBytes, topFormats, avgBitrate, usedDevice, totalDevice)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Text("  Your Library", color = MikuTealBright, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 8.dp))
        }
        // Detailed library metrics — a real data dashboard, not just a track count. All derived
        // from the tracks list already resident in memory, so this costs nothing to recompute.
        item { LibraryStatsCard(trackCount, artistCount, albumCount, stats, loading, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        // Songs used to be its own top-level tab — now it lives here, first in the list, since
        // "browse everything" is the most-used entry point into a library screen.
        item { LibRow(Icons.Default.MusicNote, "All Songs", "$trackCount tracks", MikuTealBright, onAllSongs) }
        item { LibRow(Icons.Default.Favorite, "Liked Songs", "${liked.size} songs", MikuPink) { onOpen(LIKED_KEY) } }
        if (videoCount > 0) item { LibRow(Icons.Default.Movie, "Videos", "$videoCount videos", MikuGold, onVideos) }
        items(playlists.keys.toList(), key = { it }) { name ->
            LibRow(Icons.Default.QueueMusic, name, "${playlists[name]?.size ?: 0} songs", MikuTeal) { onOpen(name) }
        }
        if (playlists.isEmpty()) item {
            Text("  No playlists yet — add songs to a playlist from any track.", color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(16.dp, 8.dp))
        }
    }
}

private data class LibraryStats(
    val totalDurationMs: Long,
    val totalBytes: Long,
    val topFormats: List<Map.Entry<String, Int>>,
    val avgBitrateKbps: Int,
    val usedDeviceBytes: Long,
    val totalDeviceBytes: Long
)

/** Real device storage (total/used) for whichever volume(s) the library actually lives on — NOT
 *  just the library's own footprint. Sums StatFs across every distinct `/storage/<volume>` root
 *  seen in the track paths (primary emulated storage + any SD card), so a library split across
 *  both still reports accurate combined figures instead of only ever reflecting one volume. */
private fun deviceStorageStats(tracks: List<Track>): Pair<Long, Long> {
    val roots = tracks.mapNotNullTo(LinkedHashSet()) { STORAGE_ROOT_RE.find(it.path)?.groupValues?.get(1) }
    var total = 0L
    var used = 0L
    roots.forEach { root ->
        runCatching {
            val stat = android.os.StatFs(root)
            val t = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            total += t
            used += (t - avail)
        }
    }
    return used to total
}
private val STORAGE_ROOT_RE = Regex("^(/storage/[^/]+)")

/** Detailed library dashboard, redesigned per an explicit ask for real graphics instead of plain
 *  number+label pairs — a storage donut (device used/total, with the library's own footprint
 *  highlighted as a distinct inner arc), a format-mix stacked bar, and a quality gauge reusing the
 *  same speedometer-arc widget artist/album rows already use elsewhere — plus icon-tagged count
 *  tiles. Deliberately a fixed, compact non-scrolling Column (not LazyColumn) sized to read as one
 *  glanceable dashboard, not something you scroll through to see all the metrics. */
@Composable private fun LibraryStatsCard(trackCount: Int, artistCount: Int, albumCount: Int, stats: LibraryStats, loading: Boolean = false, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Surface1, Color(0xFF0A2528))))
            .border(1.dp, MikuTealBright.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("LIBRARY STATS", color = MikuTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))

        if (loading) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = MikuTealBright, modifier = Modifier.size(28.dp))
            }
            return@Column
        }

        // Row 1: storage donut (real device used/total + library footprint) + format-mix bar.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StorageDonut(
                usedBytes = stats.usedDeviceBytes,
                totalBytes = stats.totalDeviceBytes,
                libraryBytes = stats.totalBytes,
                modifier = Modifier.size(92.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("FORMAT MIX", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                FormatMixBar(stats.topFormats, Modifier.fillMaxWidth().height(12.dp))
                Spacer(Modifier.height(6.dp))
                Column {
                    stats.topFormats.take(3).forEach { (fmt, count) ->
                        val total = stats.topFormats.sumOf { it.value }.coerceAtLeast(1)
                        val pct = count * 100 / total
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(formatColor("audio/${fmt.lowercase()}")))
                            Spacer(Modifier.width(5.dp))
                            Text("$fmt $pct%", color = Muted, fontSize = 9.5.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Row 2: quality gauge (avg bitrate, reusing the same speedometer widget artist/album rows
        // use) + the count/listening tiles, each with its own small bespoke icon glyph.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 1500kbps is a reasonable "top of the scale" for a mixed FLAC/lossy library —
                // this is a relative-quality dial, not a claim of an absolute universal ceiling.
                QualityGauge(
                    score = if (stats.avgBitrateKbps > 0) (stats.avgBitrateKbps / 1500f).coerceIn(0f, 1f) else null,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(if (stats.avgBitrateKbps > 0) "${stats.avgBitrateKbps} kbps" else "—", color = Muted, fontSize = 9.sp)
            }
            LibraryMetricTile(Icons.Default.MusicNote, "$trackCount", "Tracks", MikuTealBright, Modifier.weight(1f))
            LibraryMetricTile(Icons.Default.Person, "$artistCount", "Artists", MikuPink, Modifier.weight(1f))
            LibraryMetricTile(Icons.Default.Album, "$albumCount", "Albums", MikuGold, Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, null, tint = Color(0xFFB388FF), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(fmtDurationLong(stats.totalDurationMs), color = Color(0xFFB388FF), fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = OrbitronFont)
            Spacer(Modifier.width(5.dp))
            Text("total listening time", color = Muted, fontSize = 10.5.sp)
        }
    }
}

/** Compact icon+number+label tile — each library-count metric gets its own icon so the three read
 *  as visually distinct at a glance, not three identical number blocks. */
@Composable private fun LibraryMetricTile(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = OrbitronFont)
        Text(label, color = Muted, fontSize = 9.sp)
    }
}

/** Device storage ring — outer dim track = total capacity, bright arc = used, and a thinner
 *  highlighted inner arc = this library's own footprint within that used space, so it's visible
 *  at a glance how much of "used" is actually Miku Music's doing vs everything else on the device. */
@Composable private fun StorageDonut(usedBytes: Long, totalBytes: Long, libraryBytes: Long, modifier: Modifier = Modifier) {
    val usedFrac = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val libFrac = if (totalBytes > 0) (libraryBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, usedFrac) else 0f
    val animUsed by animateFloatAsState(usedFrac, tween(600), label = "storageUsed")
    val animLib by animateFloatAsState(libFrac, tween(600), label = "storageLib")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.14f
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(Color(0xFF12292C), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Color(0xFF4FC3F7), -90f, animUsed * 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (animLib > 0.002f) {
                val libInset = stroke * 1.1f
                drawArc(
                    MikuPink, -90f, animLib * 360f, false,
                    Offset(libInset, libInset),
                    androidx.compose.ui.geometry.Size(size.width - libInset * 2, size.height - libInset * 2),
                    style = Stroke(stroke * 0.4f, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(usedBytes / 1e9).let { "%.0f".format(it) }}", color = Color(0xFF4FC3F7), fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = OrbitronFont)
            Text("/${(totalBytes / 1e9).let { "%.0f".format(it) }} GB", color = Muted, fontSize = 8.5.sp)
        }
    }
}

/** Proportional stacked bar — one segment per format, width = share of the library, colored to
 *  match formatColor() so it lines up with the format badges used everywhere else in the app. */
@Composable private fun FormatMixBar(topFormats: List<Map.Entry<String, Int>>, modifier: Modifier = Modifier) {
    val total = topFormats.sumOf { it.value }.coerceAtLeast(1)
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.06f))) {
        topFormats.forEach { (fmt, count) ->
            val frac = (count.toFloat() / total).coerceAtLeast(0.01f)
            Box(
                Modifier
                    .weight(frac)
                    .fillMaxHeight()
                    .background(formatColor("audio/${fmt.lowercase()}"))
            )
        }
    }
}

/** "3d 4h 12m" / "6h 20m" / "42m" — total listening time, human scale (fmtDuration elsewhere is
 *  per-track mm:ss, not the right shape for a library-wide total). */
private fun fmtDurationLong(ms: Long): String {
    val totalMin = ms / 60_000
    val days = totalMin / (60 * 24)
    val hours = (totalMin % (60 * 24)) / 60
    val mins = totalMin % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins}m"
    }
}

@Composable private fun LibRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: Color, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF123438)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint)
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Text(title, color = Color(0xFFE8F4F2), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(sub, color = Muted, fontSize = 12.sp)
    }
    Icon(Icons.Default.ChevronRight, null, tint = Muted)
}

@Composable private fun Centered(msg: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(msg, color = Muted, fontSize = 15.sp)
}

@Composable private fun PermissionPrompt(onGrant: () -> Unit) = Column(
    Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Default.MusicNote, null, tint = MikuTeal, modifier = Modifier.size(64.dp))
    Spacer(Modifier.height(16.dp))
    Text("Miku Music needs access to your audio", color = Color(0xFFE8F4F2), fontSize = 16.sp)
    Spacer(Modifier.height(20.dp))
    Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = MikuTeal)) { Text("Grant access", color = Color(0xFF00201D)) }
}

/** Shown instead of the whole app on anything that isn't the HiBy M500 — see isSupportedDevice(). */
@Composable private fun UnsupportedDeviceScreen() = Box(Modifier.fillMaxSize().background(Ground), contentAlignment = Alignment.Center) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.MusicNote, null, tint = MikuTeal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Miku Music is built for the HiBy M500", color = Color(0xFFE8F4F2), fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "It relies on this device's own hardware (DAC/amp controls, side transport keys, on-device library) and won't run anywhere else.",
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center
        )
    }
}

