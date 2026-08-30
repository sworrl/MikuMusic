package com.miku.player

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.audiofx.Visualizer
import android.provider.MediaStore
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import com.miku.player.ui.GlassTransportDeck
import com.miku.player.ui.MikuBrandMark
import com.miku.player.ui.NowPlayingLook
import com.miku.player.ui.TrackFactsStrip
import com.miku.player.ui.WavyScrubber
import com.miku.player.ui.onAccentColor
import com.miku.player.ui.rememberAnimatedPalette
import com.miku.player.visualizer.MikuShaderVisualizerView
import com.miku.player.visualizer.ShaderPreset
import com.miku.player.visualizer.VizEngine

data class ArtPalette(
    val color1: Color = MikuTeal,
    val color2: Color = Color(0xFF0C2428),
    val color3: Color = MikuPink
)

fun extractArtPalette(bitmap: ImageBitmap?): ArtPalette {
    if (bitmap == null) return ArtPalette(MikuTeal, Color(0xFF0A2528), MikuPink)
    try {
        val bm = bitmap.asAndroidBitmap()
        val w = bm.width
        val h = bm.height
        if (w <= 0 || h <= 0) return ArtPalette(MikuTeal, Color(0xFF0A2528), MikuPink)

        val colors = ArrayList<Int>(64)
        val stepX = (w / 8).coerceAtLeast(1)
        val stepY = (h / 8).coerceAtLeast(1)
        for (y in stepY / 2 until h step stepY) {
            for (x in stepX / 2 until w step stepX) {
                val pixel = bm.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 50) {
                    colors.add(pixel)
                }
            }
        }
        if (colors.isEmpty()) return ArtPalette(MikuTeal, Color(0xFF0A2528), MikuPink)

        val sortedBySat = colors.sortedByDescending { c ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c, hsv)
            hsv[1] * (if (hsv[2] in 0.25f..0.95f) 1.5f else 0.5f)
        }

        val topVibrant = sortedBySat.firstOrNull() ?: colors.first()
        val deepTone = colors.minByOrNull { c ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c, hsv)
            hsv[2]
        } ?: colors[colors.size / 2]

        val accentTone = sortedBySat.drop(sortedBySat.size / 3).firstOrNull { c ->
            val hsv1 = FloatArray(3)
            val hsv2 = FloatArray(3)
            android.graphics.Color.colorToHSV(topVibrant, hsv1)
            android.graphics.Color.colorToHSV(c, hsv2)
            Math.abs(hsv1[0] - hsv2[0]) > 30f
        } ?: sortedBySat.getOrNull(sortedBySat.size / 2) ?: colors.last()

        return ArtPalette(
            color1 = legibleAccent(Color(topVibrant), MikuTeal),
            color2 = Color(deepTone),
            color3 = legibleAccent(Color(accentTone), MikuPink)
        )
    } catch (_: Throwable) {
        return ArtPalette(MikuTeal, Color(0xFF0A2528), MikuPink)
    }
}

/**
 * Accent colors are used for TEXT and ICONS on a near-black ground, so they must stay legible:
 * a black-cover album (Metallica, AC/DC…) used to hand back a near-black "vibrant" color and the
 * whole chrome — artist line, dice, tape icon, progress — went invisible. Grey/desaturated art
 * falls back to the Miku identity accent; dark-but-colorful accents are lifted toward white until
 * they clear a luminance floor, keeping their hue.
 */
fun legibleAccent(c: Color, fallback: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    if (hsv[1] < 0.22f) return fallback                       // grey / monochrome art → Miku accent
    val l = c.luminance()
    val minLum = 0.30f
    if (l >= minLum) return c
    val t = ((minLum - l) / (1f - l)).coerceIn(0f, 0.8f)
    return Color(
        red = c.red + (1f - c.red) * t,
        green = c.green + (1f - c.green) * t,
        blue = c.blue + (1f - c.blue) * t,
        alpha = 1f
    )
}

private data class QueueItemInfo(
    val queueIdx: Int,
    val id: Long?,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val mime: String,
    val bitrateKbps: Int,
    val durationMs: Long,
    val bits: Int?
)

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
fun NowPlayingScreen(
    track: Track,
    player: ExoPlayer,
    onClose: () -> Unit,
    onTape: () -> Unit = {},
    tracks: List<Track> = emptyList(),
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String, String) -> Unit = { _, _ -> }
) {
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    NowPlayingLook.load(ctx)
    // Instant art: the cached thumb shows the same frame the screen opens, then the lossless
    // hi-res decode swaps in underneath — never a blank wait.
    val art by produceState<ImageBitmap?>(initialValue = AlbumArtCache.getHi(track.id), track.id) {
        if (value == null) value = loadArtThumb(ctx, track.id, track.path)
        value = loadArtHiRes(ctx, track.id, track.path) ?: value
    }

    // Viz is ALWAYS on while full Now Playing is open (the old 15s art→viz cross-fade is gone);
    // the manual button can still turn it off when auto-viz is disabled in settings.
    var showViz by remember { mutableStateOf(true) }
    var currentPresetIndex by remember { mutableStateOf(PlayerPreferences.loadProjectMPreset(ctx)) }
    val presets = ProjectMPreset.entries
    val currentPreset = presets[currentPresetIndex.coerceIn(0, presets.size - 1)]

    // ---- Visualizer engine: native projectM or the GLES2 "Miku Shaders" (visualizer/) ----
    var engine by remember { mutableStateOf(VizEngine.fromKey(PlayerPreferences.loadVizEngine(ctx))) }
    var shaderPresetIdx by remember { mutableStateOf(PlayerPreferences.loadShaderPreset(ctx)) }
    val shaderPresets = ShaderPreset.entries
    val shaderPreset = shaderPresets[shaderPresetIdx.coerceIn(0, shaderPresets.size - 1)]
    // projectM only when the user chose it AND the native lib actually loaded; else the shader engine.
    val effectiveEngine = if (engine == VizEngine.PROJECTM && ProjectMNative.available) VizEngine.PROJECTM else VizEngine.SHADER

    var autoViz by remember { mutableStateOf(PlayerPreferences.loadAutoViz(ctx)) }
    LaunchedEffect(track.id) { if (autoViz) showViz = true }   // re-arm on track change if user toggled it off

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    // Keyed on track.id — without it, skipping to a track of very different length kept showing
    // the PREVIOUS track's pos/dur (so the seekbar fraction pos/dur was briefly wrong) until the
    // polling LaunchedEffect below got its first tick, since that's async and this remember isn't.
    var pos by remember(track.id) { mutableStateOf(0L) }
    var dur by remember(track.id) { mutableStateOf(track.durationMs.coerceAtLeast(1L)) }
    var dragging by remember { mutableStateOf(false) }
    // Play/pause glyph updates instantly via the listener (not the 300ms poll).
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        isPlaying = player.isPlaying
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(track.id) {
        var tick = 0
        while (true) {
            try {
                // Loop TIMING/cadence stays untouched (the ~15s hardware re-check below rides on
                // it) — only the position/duration STATE WRITE is skipped once the real screen is
                // ambient-covered or physically dark, since that write's only purpose is redrawing
                // a seek bar nobody can see. Still updates through DIMMED (screen stays visible).
                if (!dragging && !IdleController.visuallyIdle) {
                    pos = player.currentPosition
                    if (player.duration > 0) dur = player.duration
                }
            } catch (_: Throwable) {}   // a transient player error must not kill the update loop
            // Periodic hardware re-check (~15s), independent of the ones in MainActivity/PlayerHolder
            // — a different field combo, buried in an unrelated poll loop rather than its own
            // named function, so bypassing the startup gate alone doesn't keep this screen playing.
            if (++tick >= 50) {
                tick = 0
                val fp = android.os.Build.FINGERPRINT.lowercase()
                val hw = android.os.Build.HARDWARE.lowercase()
                val ok = fp.contains("hiby") && hw != "goldfish" && hw != "ranchu"
                if (!ok) try { player.pause() } catch (_: Throwable) {}
            }
            delay(300)
        }
    }
    // Seeded from the LIVE player, not the persisted preference — the preference is only a record
    // of what the user last tapped, and re-applying it unconditionally on every screen mount is
    // what caused shuffle to silently outlive the session that turned it on, corrupting the "next
    // track" order of every later album/list play. The player's own state is ground truth.
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }
    var repeat by remember { mutableStateOf(PlayerPreferences.loadRepeat(ctx)) }
    LaunchedEffect(Unit) {
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    var isFullscreenVisualizer by remember { mutableStateOf(false) }
    var showOverlayControls by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showConnectModal by remember { mutableStateOf(false) }
    var pinControls by remember { mutableStateOf(false) }
    var presetToast by remember { mutableStateOf("") }
    var factsExpanded by remember { mutableStateOf(PlayerPreferences.loadTrackFactsExpanded(ctx)) }
    val scope = rememberCoroutineScope()
    fun showPresetName(prefix: String) {
        presetToast = prefix
        scope.launch { delay(280); presetToast = ProjectMNative.presetName().ifBlank { prefix } }
    }
    // Preset stepping is engine-aware: projectM walks its .milk playlist on the GL thread, the
    // shader engine just rotates the GLSL preset list (and remembers it).
    fun nextPreset() {
        if (effectiveEngine == VizEngine.SHADER) {
            shaderPresetIdx = (shaderPresetIdx + 1) % shaderPresets.size
            PlayerPreferences.saveShaderPreset(ctx, shaderPresetIdx)
            presetToast = shaderPresets[shaderPresetIdx].title
        } else { ProjectMNative.requestNext(); showPresetName("Next ▸") }
    }
    fun prevPreset() {
        if (effectiveEngine == VizEngine.SHADER) {
            shaderPresetIdx = (shaderPresetIdx - 1 + shaderPresets.size) % shaderPresets.size
            PlayerPreferences.saveShaderPreset(ctx, shaderPresetIdx)
            presetToast = shaderPresets[shaderPresetIdx].title
        } else { ProjectMNative.requestPrev(); showPresetName("◂ Prev") }
    }
    fun toggleEngine() {
        val next = if (engine == VizEngine.PROJECTM) VizEngine.SHADER else VizEngine.PROJECTM
        engine = next
        PlayerPreferences.saveVizEngine(ctx, next.key)
        presetToast = if (next == VizEngine.PROJECTM && !ProjectMNative.available) "projectM unavailable · Miku Shaders" else next.title
    }
    LaunchedEffect(presetToast) { if (presetToast.isNotEmpty()) { delay(2000); presetToast = "" } }
    LaunchedEffect(showOverlayControls, pinControls) { if (showOverlayControls && !pinControls) { delay(4500); showOverlayControls = false } }

    var dragOffsetY by remember { mutableStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(targetValue = dragOffsetY, label = "dragOffsetY")

    // Next-up peek: the single item after the current one (the full list lives in the queue overlay).
    val nextUp = remember(track.id, player.currentMediaItemIndex, player.mediaItemCount, shuffle) {
        runCatching {
            val i = player.nextMediaItemIndex
            if (i < 0 || i >= player.mediaItemCount) null else {
                val mi = player.getMediaItemAt(i)
                val id = mi.mediaId.toLongOrNull()
                val tr = if (id != null) tracks.find { it.id == id } else null
                val title = mi.mediaMetadata.title?.toString().orEmpty().ifBlank { tr?.title ?: "Unknown Title" }
                val artist = mi.mediaMetadata.artist?.toString().orEmpty().ifBlank { tr?.artist ?: "" }
                Triple(id, "$title${if (artist.isNotBlank()) "  ·  $artist" else ""}", tr?.path ?: "")
            }
        }.getOrNull()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ground)
            .offset { IntOffset(0, animatedOffsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragOffsetY > 180f) {
                            onClose()
                        }
                        dragOffsetY = 0f
                    },
                    onDragCancel = { dragOffsetY = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount > 0 || dragOffsetY > 0) {
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    }
                )
            }
    ) {
        val rawPalette = remember(art) { extractArtPalette(art) }
        // Feed the app-wide dynamic theme from the SAME hi-res art this screen decoded.
        LaunchedEffect(rawPalette, track.id) { MikuArtTheme.push(track.id, rawPalette) }
        // Every surface below reads THIS: art-extracted when "album-art dynamic color" is on
        // (Miku identity palette when off), gliding between tracks instead of hard-cutting.
        val palette = rememberAnimatedPalette(rawPalette, NowPlayingLook.dynamicColor)
        val accent = palette.color1
        val accent2 = palette.color3

        // Branded 3-color dynamic blended background extracted directly from album artwork
        art?.let {
            androidx.compose.foundation.Image(
                it, null,
                Modifier.matchParentSize().blur(45.dp),
                contentScale = ContentScale.Crop, alpha = 0.35f
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(
                        palette.color1.copy(alpha = 0.38f),
                        palette.color2.copy(alpha = 0.65f),
                        palette.color3.copy(alpha = 0.30f),
                        Ground
                    )
                )
            )
        )
        // Ambient Miku watermark: a different official wallpaper per track
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(remember(track.id) { MikuArt.forTrack(track.id) }),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(148.dp).alpha(0.07f)
        )

        if (isFullscreenVisualizer) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ground)
                .pointerInput(effectiveEngine) {
                    detectTapGestures(
                        onTap = { showOverlayControls = !showOverlayControls },
                        onDoubleTap = { nextPreset() }
                    )
                }
                // Swipe left/right anywhere on the stage = next/previous preset (both engines).
                .pointerInput(effectiveEngine) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (dx < -70f) { Haptics.tick(ctx); nextPreset() } else if (dx > 70f) { Haptics.tick(ctx); prevPreset() }; dx = 0f },
                        onDragCancel = { dx = 0f }
                    ) { change, amount -> change.consume(); dx += amount }
                }
        ) {
            StageVisualizer(
                engine = effectiveEngine,
                sessionId = player.audioSessionId,
                pmPreset = currentPreset,
                shPreset = shaderPreset,
                palette = palette,
                modifier = Modifier.fillMaxSize()
            )

            // Pretty transient preset-name toast (real .milk name / GLSL preset title / engine).
            androidx.compose.animation.AnimatedVisibility(
                visible = presetToast.isNotEmpty(),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(24.dp))
                        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = .92f), accent2.copy(alpha = .92f))))
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color(0xFF04161A), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(presetToast, color = Color(0xFF04161A), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Top-right floating chips: pin + engine + exit (always available, tiny, glassy — minimal vis blocking).
            androidx.compose.animation.AnimatedVisibility(
                visible = showOverlayControls || pinControls,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIcon(if (pinControls) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        "Pin controls", if (pinControls) MikuGold else Color.White) { pinControls = !pinControls }
                    Spacer(Modifier.width(8.dp))
                    GlassIcon(Icons.Default.AutoAwesome, "Visualizer engine: ${effectiveEngine.title}",
                        if (effectiveEngine == VizEngine.SHADER) accent2 else MikuGold) { toggleEngine() }
                    Spacer(Modifier.width(8.dp))
                    GlassIcon(Icons.Default.FullscreenExit, "Exit fullscreen", MikuTealBright) { isFullscreenVisualizer = false }
                }
            }

            // Bottom floating GLASS control card — compact & translucent so the vis stays visible.
            androidx.compose.animation.AnimatedVisibility(
                visible = showOverlayControls || pinControls,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xC01A2E32), Color(0xE0071115))))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val vizName = if (effectiveEngine == VizEngine.SHADER) shaderPreset.title
                                else presetToast.ifBlank { ProjectMNative.presetName() }.ifBlank { "visualizer" }
                            Text(track.artist + "   ·   " + vizName,
                                color = accent.copy(alpha = .9f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        NowPlayingHeart(track, size = 50.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (NowPlayingLook.wavyBar) {
                        WavyScrubber(
                            pos = pos, dur = dur, playing = isPlaying, accent = accent, accent2 = accent2, sessionId = player.audioSessionId,
                            onSeekPreview = { dragging = true; pos = it },
                            onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
                        )
                    } else {
                        EmbossedScrubber(
                            pos = pos, dur = dur, playing = isPlaying,
                            onSeekPreview = { dragging = true; pos = it },
                            onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        HapticIconButton(onClick = { prevPreset() }) {
                            Icon(Icons.Default.GraphicEq, "Prev preset", tint = MikuGold, modifier = Modifier.size(22.dp))
                        }
                        HapticIconButton(onClick = { player.seekToPreviousMediaItem() }) {
                            Icon(Icons.Default.SkipPrevious, "Prev", tint = accent, modifier = Modifier.size(34.dp))
                        }
                        HapticIconButton(onClick = { if (player.isPlaying) player.pause() else player.play() },
                            face = accent, modifier = Modifier.size(width = 78.dp, height = 58.dp)) {
                            PlayPauseGlyph(isPlaying, tint = onAccentColor(accent), size = 34.dp)
                        }
                        HapticIconButton(onClick = { player.seekToNextMediaItem() }) {
                            Icon(Icons.Default.SkipNext, "Next", tint = accent, modifier = Modifier.size(34.dp))
                        }
                        HapticIconButton(onClick = { nextPreset() }) {
                            Icon(Icons.Default.GraphicEq, "Next preset", tint = MikuGold, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            // 24dp clear under the transport keys — the system gesture pill lives there.
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
    ) {
        // Top Nav Bar — the shared Miku Music brand mark (ui/MikuTopBar.kt) signs the screen.
        Row(verticalAlignment = Alignment.CenterVertically) {
            HapticIconButton(onClick = onClose, flat = true) {
                Icon(Icons.Default.KeyboardArrowDown, "Close", tint = accent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MikuBrandMark(tint = accent, fontSize = 11.sp, glyphSize = 18.dp, letterSpacing = 1.2.sp)
                Text(track.album.ifBlank { "Now Playing" }, color = Color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = Baloo2Font, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            HapticIconButton(onClick = { InstantRandom.start(ctx) }, flat = true) {
                Icon(
                    Icons.Default.Casino, "Random — play anything",
                    tint = if (InstantRandom.active) accent2 else accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            HapticIconButton(onClick = { showConnectModal = !showConnectModal }, flat = true) {
                Icon(Icons.Default.Tv, "Miku Connect (TV & Remote)", tint = if (showConnectModal) accent2 else accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(4.dp))
            HapticIconButton(onClick = { showQueue = !showQueue }, flat = true) {
                Icon(Icons.Default.QueueMusic, "Up Next Queue", tint = if (showQueue) accent2 else accent, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(4.dp))
            HapticIconButton(onClick = onTape, flat = true) { TapeIcon(tint = accent2, modifier = Modifier.size(26.dp)) }
        }

        Spacer(Modifier.height(8.dp))

        // Stage & Track Info Container — stage gets the lion's share (~59/41) normally; when the
        // Track Facts ledger is open the info card grows (animated weight) and the stage shrinks.
        val infoWeight by animateFloatAsState(if (factsExpanded) 2.6f else 1f, tween(320, easing = FastOutSlowInEasing), label = "infoWeight")
        // Parallax drive for the art stage: the swipe-to-skip drag nudges the art sideways and the
        // pull-down-to-close offset nudges it vertically; both spring back.
        var totalDragX by remember { mutableStateOf(0f) }
        val parallaxX by animateFloatAsState(totalDragX * 0.35f, spring(dampingRatio = 0.75f, stiffness = 300f), label = "parallaxX")
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Stage (Art / Visualizer)
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1.45f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF08181B))
                    .border(
                        1.2.dp,
                        Brush.horizontalGradient(
                            listOf(
                                accent.copy(alpha = 0.55f),
                                accent2.copy(alpha = 0.45f)
                            )
                        ),
                        RoundedCornerShape(22.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (showViz) {
                    StageVisualizer(
                        engine = effectiveEngine,
                        sessionId = player.audioSessionId,
                        pmPreset = currentPreset,
                        shPreset = shaderPreset,
                        palette = palette,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (art != null) {
                    androidx.compose.foundation.Image(
                        art!!, "Album art",
                        Modifier.fillMaxSize().graphicsLayer {
                            // Slight over-scale so the parallax shift never exposes the card edge.
                            scaleX = 1.08f; scaleY = 1.08f
                            translationX = parallaxX
                            translationY = animatedOffsetY * 0.12f
                        },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Album, null, tint = accent.copy(alpha = .5f), modifier = Modifier.size(100.dp))
                }

                // Interactive Gestures on Stage (Visualizer or Album Art)
                if (showViz) {
                    Box(
                        Modifier.matchParentSize().pointerInput(effectiveEngine) {
                            detectTapGestures(
                                onTap = { isFullscreenVisualizer = true },
                                onDoubleTap = { nextPreset() },
                                onLongPress = { showViz = false }
                            )
                        }
                    )
                } else {
                    Box(
                        Modifier.matchParentSize()
                            .padding(horizontal = 32.dp)
                            .pointerInput(track.id) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (totalDragX < -60f) {
                                            Haptics.tick(ctx)
                                            player.seekToNextMediaItem()
                                        } else if (totalDragX > 60f) {
                                            Haptics.tick(ctx)
                                            player.seekToPreviousMediaItem()
                                        }
                                        totalDragX = 0f
                                    },
                                    onDragCancel = { totalDragX = 0f },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        totalDragX += dragAmount
                                    }
                                )
                            }
                            .pointerInput(track.id) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        Haptics.tick(ctx)
                                        LikeStore.toggle(ctx, track)
                                    },
                                    onLongPress = {
                                        Haptics.tick(ctx)
                                        showViz = true
                                    }
                                )
                            }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Track Info Card: 100% Full Album Art with Text Overlain & Vignette — compact split
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(infoWeight)
                    .drawBehind {
                        val rr = CornerRadius(22.dp.toPx(), 22.dp.toPx())
                        drawRoundRect(Color(0x66000000), topLeft = Offset(0f, 4.dp.toPx()), size = size, cornerRadius = rr)
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .border(
                        1.5.dp,
                        Brush.horizontalGradient(
                            listOf(
                                accent.copy(alpha = 0.85f),
                                accent2.copy(alpha = 0.75f)
                            )
                        ),
                        RoundedCornerShape(22.dp)
                    )
            ) {
                // 1. ALL Album Art filling 100% of the entire card
                if (art != null) {
                    androidx.compose.foundation.Image(
                        bitmap = art!!,
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D2529)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Album,
                            null,
                            tint = accent.copy(alpha = 0.4f),
                            modifier = Modifier.size(90.dp)
                        )
                    }
                }

                // 2. High-legibility Vignetting Scrim (protects text contrast over any bright/busy artwork)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color(0x80000000),
                                0.35f to Color(0xA0020B0E),
                                0.70f to Color(0xDD010709),
                                1.0f to Color(0xF4010405)
                            )
                        )
                )

                // 3. Track info text taking up the ENTIRE card over top the art
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Row: Title + Like Heart
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 18.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = RighteousFont,
                            letterSpacing = 0.4.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                        )
                        Spacer(Modifier.width(8.dp))
                        NowPlayingHeart(track, size = 40.dp)
                    }

                    // Middle Section: Artist & Album with Year (occupies middle of card)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val displayYear = TrackYear.yearFor(ctx, track)?.takeIf { it > 0 } ?: track.year.takeIf { it > 0 }
                        val yearTag = if (displayYear != null) "  ·  $displayYear" else ""
                        Text(
                            text = "${track.artist}$yearTag",
                            color = Color(0xFFF2FBF9),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = Baloo2Font,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                                .clickable {
                                    onClose()
                                    onOpenArtist(track.artist)
                                }
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = track.album.ifBlank { track.artist },
                            color = accent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = Baloo2Font,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                                .clickable(enabled = track.album.isNotBlank()) {
                                    onClose()
                                    onOpenAlbum(track.albumArtist.ifBlank { track.artist }, track.album)
                                }
                        )
                    }

                    // Data-verbose strip: real file / decoder / route / DAC facts (ui/TrackFactsStrip.kt).
                    TrackFactsStrip(
                        track = track,
                        player = player,
                        expanded = factsExpanded,
                        accent = accent2,
                        onToggle = { factsExpanded = !factsExpanded; PlayerPreferences.saveTrackFactsExpanded(ctx, factsExpanded) },
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Bottom Row: Audio Quality & Format Pills taking full width across the bottom of the card
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row { TechBadgeRow(ctx, track, fontSize = 10.5.sp, spacing = 4.dp, includeFormat = true) }
                        if (track.bitrateKbps > 0) DataChip("${track.bitrateKbps} kbps", bitrateColor(track.bitrateKbps))
                        DataChip(qualityTier(track), bitrateColor(track.bitrateKbps))
                        releaseTag(track.album)?.let { DataChip(it, ReleaseTagColor) }
                        if (track.durationMs > 0) DataChip(fmtTime(track.durationMs), Color(0xFFD4ECE9))
                        if (track.sizeBytes > 0) DataChip("${"%.1f".format(track.sizeBytes / 1e6)} MB", Color(0xFFD4ECE9))
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Seek Bar — wavy waveform ribbon (default) or the embossed physical scrubber.
        if (NowPlayingLook.wavyBar) {
            WavyScrubber(
                pos = pos, dur = dur, playing = isPlaying, accent = accent, accent2 = accent2, sessionId = player.audioSessionId,
                onSeekPreview = { dragging = true; pos = it },
                onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
            )
        } else {
            EmbossedScrubber(
                pos = pos, dur = dur, playing = isPlaying,
                onSeekPreview = { dragging = true; pos = it },
                onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(pos), color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont, letterSpacing = 0.5.sp)
            Spacer(Modifier.weight(1f))
            // Queue peek: what's next, one tap from the full queue.
            Row(
                Modifier
                    .weight(6f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { Haptics.tick(ctx); showQueue = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.QueueMusic, "Up next", tint = accent2, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                if (nextUp != null) {
                    if (nextUp.first != null) {
                        AlbumArtImage(nextUp.first!!, Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)), trackPath = nextUp.third)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        nextUp.second, color = Color(0xFFD4ECE9), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1200, initialDelayMillis = 900)
                    )
                } else {
                    Text("End of queue", color = Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(fmtTime(dur), color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont, letterSpacing = 0.5.sp)
        }

        Spacer(Modifier.height(4.dp))

        // Transport deck — molded ControlAssembly + 3D keys, palette-tinted hero play key in a
        // bass-breathing glass halo, LED-lit shuffle/repeat (ui/TransportDeck.kt).
        GlassTransportDeck(
            isPlaying = isPlaying,
            shuffle = shuffle,
            repeat = repeat,
            accent = accent,
            accent2 = accent2,
            onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
            onPrev = { player.seekToPreviousMediaItem() },
            onNext = { player.seekToNextMediaItem() },
            onShuffle = {
                shuffle = !shuffle
                player.shuffleModeEnabled = shuffle
                PlayerPreferences.saveShuffle(ctx, shuffle)
            },
            onRepeat = {
                repeat = !repeat
                player.repeatMode = if (repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                PlayerPreferences.saveRepeat(ctx, repeat)
            }
        )

        Spacer(Modifier.height(6.dp))
    }

    // Fullsize Vertical Next-Up Track List Overlay (Top of entire screen, 60fps smooth animation)
    androidx.compose.animation.AnimatedVisibility(
        visible = showQueue,
        enter = androidx.compose.animation.slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(280, easing = FastOutSlowInEasing)
        ) + androidx.compose.animation.fadeIn(tween(200)),
        exit = androidx.compose.animation.slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(220, easing = FastOutLinearInEasing)
        ) + androidx.compose.animation.fadeOut(tween(160))
    ) {
        val upcomingItems = remember(showQueue, player.currentMediaItemIndex, player.mediaItemCount, tracks) {
            if (!showQueue) emptyList()
            else {
                val total = player.mediaItemCount
                val cur = player.currentMediaItemIndex
                val list = ArrayList<QueueItemInfo>()
                for (i in (cur + 1) until minOf(cur + 60, total)) {
                    val mediaItem = player.getMediaItemAt(i)
                    val id = mediaItem.mediaId.toLongOrNull()
                    val tr = if (id != null) tracks.find { it.id == id } else null
                    val meta = mediaItem.mediaMetadata
                    val title = meta.title?.toString().orEmpty().ifBlank { tr?.title ?: "Unknown Title" }
                    val artist = meta.artist?.toString().orEmpty().ifBlank { tr?.artist ?: "Unknown Artist" }
                    val album = meta.albumTitle?.toString().orEmpty().ifBlank { tr?.album ?: "" }
                    val path = tr?.path ?: ""
                    val mime = tr?.mime ?: ""
                    val bitrateKbps = tr?.bitrateKbps ?: 0
                    val durationMs = tr?.durationMs ?: 0L
                    val bits = if (tr != null) TrackTech.bitsFor(ctx, tr) else null
                    list.add(QueueItemInfo(i, id, path, title, artist, album, mime, bitrateKbps, durationMs, bits))
                }
                list
            }
        }

        Box(
            Modifier.fillMaxSize()
                .background(Color(0xF204100F))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0C2B2E), Color(0xFF04161A))))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QueueMusic, null, tint = MikuPink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("UP NEXT QUEUE", color = MikuPink, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, fontFamily = AudiowideFont)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val count = player.mediaItemCount
                        val curIdx = player.currentMediaItemIndex
                        val remaining = (count - curIdx - 1).coerceAtLeast(0)
                        Text("$remaining tracks left", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        HapticIconButton(onClick = { showQueue = false }, flat = true) {
                            Icon(Icons.Default.Close, "Close Queue", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Currently Playing Header Card
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF14403D), Color(0xFF0F2B2E))))
                        .border(1.dp, MikuTeal.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        if (art != null) androidx.compose.foundation.Image(art!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Default.MusicNote, null, tint = MikuTeal, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("NOW PLAYING", color = MikuTealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.GraphicEq, null, tint = MikuTealBright, modifier = Modifier.size(12.dp))
                        }
                        Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist + "  ·  " + track.album, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Upcoming Queue Track List
                if (upcomingItems.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.MusicNote, null, tint = Muted.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("End of Queue", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Add more songs from Songs or Albums tab", color = Muted.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    ) {
                        upcomingItems.forEachIndexed { itemIndex, item ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        player.seekTo(item.queueIdx, 0L)
                                        showQueue = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Track Queue Index (#1, #2, #3...)
                                Text(
                                    "#${itemIndex + 1}",
                                    color = MikuTeal.copy(alpha = 0.8f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(32.dp)
                                )

                                // Track Art Thumbnail
                                Box(
                                    Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F2B2E)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.id != null) {
                                        AlbumArtImage(item.id, Modifier.fillMaxSize(), trackPath = item.path)
                                    } else {
                                        Icon(Icons.Default.MusicNote, null, tint = MikuTeal.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                                    }
                                }

                                Spacer(Modifier.width(10.dp))

                                // Title & Artist + Album Details
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        color = Color(0xFFE8F4F2),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${item.artist}${if (item.album.isNotBlank()) "  ·  ${item.album}" else ""}",
                                        color = Muted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(Modifier.width(6.dp))

                                // Tech Metrics & Duration Pills
                                Column(horizontalAlignment = Alignment.End) {
                                    if (item.durationMs > 0) {
                                        Text(
                                            fmtTime(item.durationMs),
                                            color = Muted,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (item.mime.isNotBlank()) {
                                            val fmt = item.mime.substringAfterLast('/').uppercase()
                                            Text(
                                                fmt,
                                                color = formatColor(item.mime),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (item.bits != null && item.bits > 0) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "${item.bits}-BIT",
                                                color = TrackTech.color(item.bits),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        } else if (item.bitrateKbps > 0) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "${item.bitrateKbps}k",
                                                color = bitrateColor(item.bitrateKbps),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
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

    if (showConnectModal) {
        MikuConnectModal(
            context = ctx,
            onClose = { showConnectModal = false }
        )
    }
    }
}

/**
 * The one place that decides which GPU engine draws the stage. projectM = the native Milkdrop
 * engine (ProjectMVisualizer.kt, NDK); SHADER = the GLES2 GLSL presets (visualizer/). A future
 * backend (projectM 4, a preset importer…) is one more branch here.
 */
@Composable
private fun StageVisualizer(
    engine: VizEngine,
    sessionId: Int,
    pmPreset: ProjectMPreset,
    shPreset: ShaderPreset,
    palette: ArtPalette,
    modifier: Modifier = Modifier
) {
    when (engine) {
        VizEngine.PROJECTM -> ProjectMVisualizerView(sessionId = sessionId, preset = pmPreset, modifier = modifier)
        VizEngine.SHADER -> MikuShaderVisualizerView(
            sessionId = sessionId, preset = shPreset,
            accent = palette.color1, accent2 = palette.color3, modifier = modifier
        )
    }
}

@Composable
fun MikuConnectModal(context: android.content.Context, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val ip = remember {
        try {
            val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val raw = wm?.connectionInfo?.ipAddress ?: 0
            if (raw != 0) {
                String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    raw and 0xff,
                    raw shr 8 and 0xff,
                    raw shr 16 and 0xff,
                    raw shr 24 and 0xff
                )
            } else "192.168.13.184"
        } catch (_: Throwable) { "192.168.13.184" }
    }

    val remoteUrl = "http://$ip:8765"
    val tvUrl = "http://$ip:8765/tv"

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF041017))
                .border(
                    1.5.dp,
                    Brush.horizontalGradient(listOf(MikuTeal, MikuPink)),
                    RoundedCornerShape(20.dp)
                )
                .clickable(enabled = false) {}
                .padding(18.dp)
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00FF88))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "MIKU CONNECT",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont,
                        letterSpacing = 1.sp
                    )
                }
                HapticIconButton(onClick = onClose, flat = true) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            Text(
                "Spotify-Connect style wireless control & TV playback.",
                color = Muted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            // Card 1: Mobile Remote
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF08202D))
                    .border(1.dp, MikuTeal.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, null, tint = MikuTeal, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("PHONE / WEB REMOTE", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
                Spacer(Modifier.height(4.dp))
                Text(remoteUrl, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Open on your phone or laptop browser to control playback, adjust volume, and view queue.", color = Muted, fontSize = 11.sp)
            }

            Spacer(Modifier.height(10.dp))

            // Card 2: TV Big Screen
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF08202D))
                    .border(1.dp, MikuPink.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tv, null, tint = MikuPink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("TV BIG SCREEN STAGE", color = MikuPink, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
                Spacer(Modifier.height(4.dp))
                Text(tvUrl, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Open on your Smart TV browser to see giant album art, live spectrum, and stream audio to TV sound system.", color = Muted, fontSize = 11.sp)
            }

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    "Port 8765 · Native DTA Server Active",
                    color = MikuTeal.copy(alpha = 0.7f),
                    fontSize = 10.5.sp,
                    fontFamily = AudiowideFont
                )
            }
        }
    }
}

/**
 * A physical-feeling seek control: a recessed (embossed) groove with a raised, glossy fill whose
 * gradient subtly shifts hue with progress (teal → warmer) and dims when paused, plus a soft shimmer
 * that travels while playing, and a raised knob with a highlight/shadow. Drag or tap to seek.
 */
@Composable
fun EmbossedScrubber(
    pos: Long, dur: Long, playing: Boolean,
    onSeekPreview: (Long) -> Unit, onSeekCommit: (Long) -> Unit
) {
    val d = dur.coerceAtLeast(1L)
    val prog = (pos.toFloat() / d).coerceIn(0f, 1f)   // raw (works with device animations off)
    // Manual shimmer loop (ignores animator_duration_scale = 0).
    var shimmer by remember { mutableStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        // Decorative shimmer, same idle gate as the heart pulses — pointless with the screen off.
        while (true) {
            if (IdleController.screenActive) { shimmer = (shimmer + 0.02f) % 1f; delay(32) } else delay(500)
        }
    }

    // Subtle color story: hue drifts teal→pink as the track progresses; paused desaturates.
    val base = lerp(MikuTeal, MikuPink, prog * 0.55f)
    val active = if (playing) base else lerp(base, Muted, 0.55f)
    val brighter = lerp(active, Color.White, 0.32f)
    val darker = lerp(active, Color.Black, 0.36f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(d) {
                var frac = 0f
                detectHorizontalDragGestures(
                    onDragStart = { o -> frac = (o.x / size.width).coerceIn(0f, 1f); onSeekPreview((frac * d).toLong()) },
                    onDragEnd = { onSeekCommit((frac * d).toLong()) },
                    onDragCancel = { onSeekCommit((frac * d).toLong()) }
                ) { change, _ -> frac = (change.position.x / size.width).coerceIn(0f, 1f); onSeekPreview((frac * d).toLong()) }
            }
            .pointerInput(d) {
                detectTapGestures { o -> onSeekCommit(((o.x / size.width).coerceIn(0f, 1f) * d).toLong()) }
            }
    ) {
        val w = size.width; val cy = size.height / 2f
        val th = 14.dp.toPx(); val r = th / 2f
        val fillW = (w * prog).coerceIn(0f, w)

        // Recessed groove (embossed inward): dark base + top inner shadow + bottom light edge.
        drawRoundRect(Color(0xFF06201F), topLeft = Offset(0f, cy - r), size = Size(w, th), cornerRadius = CornerRadius(r, r))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0x77000000), Color(0x00000000)), startY = cy - r, endY = cy + r * 0.4f),
            topLeft = Offset(0f, cy - r), size = Size(w, th), cornerRadius = CornerRadius(r, r)
        )
        drawLine(Color(0x1FFFFFFF), Offset(r, cy + r - 1.2f), Offset(w - r, cy + r - 1.2f), 1.4f, cap = StrokeCap.Round)

        // Raised glossy fill: vertical gradient (top highlight → base → bottom shadow).
        if (fillW > r) {
            drawRoundRect(
                Brush.verticalGradient(listOf(brighter, active, darker), startY = cy - r, endY = cy + r),
                topLeft = Offset(0f, cy - r), size = Size(fillW, th), cornerRadius = CornerRadius(r, r)
            )
            drawLine(brighter.copy(alpha = 0.75f), Offset(r, cy - r + 2f), Offset(fillW - r, cy - r + 2f), 1.4f, cap = StrokeCap.Round)
            if (playing) {   // soft travelling shimmer
                val sx = shimmer * fillW
                drawCircle(Color.White.copy(alpha = 0.12f), r * 1.4f, Offset(sx.coerceIn(0f, fillW), cy))
            }
        }

        // Raised knob with drop shadow + rim light + highlight.
        val tx = (w * prog).coerceIn(r, w - r)
        val kr = 11.dp.toPx()
        drawCircle(Color(0x66000000), kr + 2.5f, Offset(tx, cy + 2f))
        drawCircle(Brush.verticalGradient(listOf(brighter, active, darker), startY = cy - kr, endY = cy + kr), kr, Offset(tx, cy))
        drawCircle(Color.White.copy(alpha = 0.55f), kr, Offset(tx, cy), style = Stroke(1.2f))
        drawCircle(Color.White.copy(alpha = 0.6f), kr * 0.30f, Offset(tx - kr * 0.32f, cy - kr * 0.32f))
    }
}

@Composable private fun GlassIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color, onClick: () -> Unit
) {
    val ctx = LocalContext.current
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
            .clickable { Haptics.tick(ctx); onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp)) }
}

// Embossed 3D metric pill: raised tinted face lit from the top, specular hairline, drop shadow.
@Composable fun DataChip(text: String, color: Color) = Text(
    text, color = color, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont, letterSpacing = 0.5.sp,
    modifier = Modifier
        .drawBehind {
            val rr = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            drawRoundRect(Color(0x4D000000), topLeft = androidx.compose.ui.geometry.Offset(0f, 1.8f), size = size, cornerRadius = rr)
            drawRoundRect(
                Brush.verticalGradient(listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0.10f))),
                cornerRadius = rr)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x00FFFFFF)), endY = size.height * 0.65f),
                cornerRadius = rr, style = Stroke(1.1f))
        }
        .padding(horizontal = 9.dp, vertical = 4.dp)
)

private fun qualityTier(t: Track): String {
    val fmt = t.mime.substringAfterLast('/').lowercase()
    return when {
        fmt in setOf("dsd", "dsf", "dff") -> "DSD"
        t.bitrateKbps >= 2500 -> "HI-RES"
        t.bitrateKbps >= 900 || fmt in setOf("flac", "x-flac", "wav", "x-wav", "alac") -> "LOSSLESS"
        t.bitrateKbps in 1 until 900 -> "LOSSY"
        else -> "AUDIO"
    }
}

private fun heartPath(w: Float, h: Float): androidx.compose.ui.graphics.Path =
    androidx.compose.ui.graphics.Path().apply {
        moveTo(0.5f * w, 0.86f * h)
        cubicTo(0.34f * w, 0.72f * h, 0.06f * w, 0.54f * h, 0.06f * w, 0.31f * h)
        cubicTo(0.06f * w, 0.11f * h, 0.33f * w, 0.06f * h, 0.5f * w, 0.27f * h)
        cubicTo(0.67f * w, 0.06f * h, 0.94f * w, 0.11f * h, 0.94f * w, 0.31f * h)
        cubicTo(0.94f * w, 0.54f * h, 0.66f * w, 0.72f * h, 0.5f * w, 0.86f * h)
        close()
    }

/** Subtle "lub-dub" heartbeat envelope (two soft thumps per cycle), returns ~0..1. */
private fun heartbeat(x: Float): Float {
    fun bump(c: Float, wdt: Float) = kotlin.math.exp((-((x - c) * (x - c)) / (2 * wdt * wdt)).toDouble()).toFloat()
    return bump(0.10f, 0.040f) + 0.6f * bump(0.26f, 0.05f)
}

/** The three like tiers (LikeStore) — each gets a visually distinct heart so "I liked this song"
 *  and "I liked this whole album/artist" never look identical at a glance, even though they're all
 *  the same underlying rainbow-heart animation. TRACK is the original glyph, unchanged. */
enum class LikeTier { TRACK, ALBUM, ARTIST }

@Composable
fun RainbowHeart(liked: Boolean, size: androidx.compose.ui.unit.Dp = 42.dp, onToggle: () -> Unit) =
    TieredRainbowHeart(LikeTier.TRACK, liked, size, onToggle)

/** Ring-wrapped heart — "the whole collection wrapped together," a step up from a single track. */
@Composable
fun AlbumRainbowHeart(liked: Boolean, size: androidx.compose.ui.unit.Dp = 42.dp, onToggle: () -> Unit) =
    TieredRainbowHeart(LikeTier.ALBUM, liked, size, onToggle)

/** Heart with a spark accent — "everything this person makes," the widest tier. */
@Composable
fun ArtistRainbowHeart(liked: Boolean, size: androidx.compose.ui.unit.Dp = 42.dp, onToggle: () -> Unit) =
    TieredRainbowHeart(LikeTier.ARTIST, liked, size, onToggle)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TieredRainbowHeart(
    tier: LikeTier,
    liked: Boolean,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    onToggle: () -> Unit,
    earnable: Boolean = false,
    badgeCount: Int = 0,
    onLongPress: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    var phase by remember { mutableStateOf(0f) }
    var beat by remember { mutableStateOf(0f) }
    LaunchedEffect(liked) {
        if (!liked) return@LaunchedEffect
        while (true) {
            if (IdleController.screenActive) {
                phase = (phase + 2.4f) % 360f; beat = (beat + 0.014f) % 1f
                delay(16)
            } else delay(500)
        }
    }
    val pulse = if (liked) 1f + 0.065f * heartbeat(beat) else 1f
    val label = when (tier) { LikeTier.TRACK -> "song"; LikeTier.ALBUM -> "album"; LikeTier.ARTIST -> "artist" }
    Box(
        Modifier
            .size(size)
            .scale(pulse)
            .semantics { contentDescription = "${if (liked) "Unlike" else "Like"} $label"; role = Role.Checkbox; toggleableState = ToggleableState(liked) }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onLongClick = onLongPress?.let { { Haptics.tick(ctx); it() } },
                onClick = { Haptics.tick(ctx); onToggle() }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Earnable-this-play ring: a soft green glow telling the user a heart is ready to spend.
        if (earnable) {
            Canvas(Modifier.size(size)) {
                drawCircle(Color(0x5500E676), radius = this.size.minDimension * 0.5f)
                drawCircle(Color(0xCC00E676), radius = this.size.minDimension * 0.48f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f))
            }
        }
        Canvas(Modifier.size(size * 0.84f)) {
            val w = this.size.width
            val h = this.size.height
            val p = heartPath(w, h)

            if (liked) {
                // 1. 3D Physical Drop Shadow underneath the heart
                val shadowPath = heartPath(w, h)
                drawContext.canvas.save()
                drawContext.canvas.translate(0f, 3.5f)
                drawPath(shadowPath, Color(0x99000000))
                drawContext.canvas.restore()

                // 2. Ambient Chromatic Bloom / Aura behind the heart
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color.hsv((phase + 120f) % 360f, 0.9f, 1f, 0.45f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.5f, h * 0.45f),
                        radius = w * 0.75f
                    )
                )

                // 3. Dynamic Rotating Rainbow Chromatic Core
                val cols = when (tier) {
                    LikeTier.TRACK -> (0..6).map { Color.hsv(((it * 52) + phase) % 360f, 0.88f, 1f) }
                    LikeTier.ALBUM -> (0..6).map { Color.hsv(((it * 52) + phase * 1.4f + 40f) % 360f, 0.80f, 0.98f) }
                    LikeTier.ARTIST -> (0..7).map { Color.hsv(((it * 46) + phase * 0.7f + 200f) % 360f, 0.92f, 1f) }
                }
                val brush = when (tier) {
                    LikeTier.TRACK -> Brush.linearGradient(cols, Offset(0f, h), Offset(w, 0f))
                    LikeTier.ALBUM -> Brush.linearGradient(cols, Offset(w, h), Offset(0f, 0f))
                    LikeTier.ARTIST -> Brush.radialGradient(cols, center = Offset(w * 0.5f, h * 0.42f), radius = w * 0.75f)
                }
                drawPath(p, brush)

                // 4. 3D Embossed Convex Shading & Specular Dome (Top-Left Light Source)
                // Top-left specular highlight rim (raised 3D crest)
                val highlightBrush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.92f),
                        Color.White.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                )
                drawPath(p, highlightBrush, style = Stroke(width = 2.4f))

                // Bottom-right shadow rim (sunken 3D bottom bevel)
                val shadowRimBrush = Brush.linearGradient(
                    listOf(
                        Color.Transparent,
                        Color(0x80000000)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                )
                drawPath(p, shadowRimBrush, style = Stroke(width = 2.2f))

                // Upper dual-lobe 3D gloss gleams
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.65f), Color.Transparent),
                        center = Offset(w * 0.28f, h * 0.26f),
                        radius = w * 0.20f
                    ),
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.24f, h * 0.18f)
                )
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.50f), Color.Transparent),
                        center = Offset(w * 0.70f, h * 0.26f),
                        radius = w * 0.18f
                    ),
                    topLeft = Offset(w * 0.60f, h * 0.16f),
                    size = Size(w * 0.20f, h * 0.16f)
                )

                // Distinct tier accents
                val minDim = minOf(w, h)
                when (tier) {
                    LikeTier.ALBUM -> {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.75f),
                            radius = minDim * 0.64f,
                            center = Offset(w / 2f, h / 2f),
                            style = Stroke(width = 1.6f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                        )
                    }
                    LikeTier.ARTIST -> {
                        val cx = w * 0.84f
                        val cy = h * 0.14f
                        val r = minDim * 0.16f
                        val spark = androidx.compose.ui.graphics.Path().apply {
                            moveTo(cx, cy - r)
                            lineTo(cx + r * 0.28f, cy - r * 0.28f)
                            lineTo(cx + r, cy)
                            lineTo(cx + r * 0.28f, cy + r * 0.28f)
                            lineTo(cx, cy + r)
                            lineTo(cx - r * 0.28f, cy + r * 0.28f)
                            lineTo(cx - r, cy)
                            lineTo(cx - r * 0.28f, cy - r * 0.28f)
                            close()
                        }
                        drawPath(spark, Color.White.copy(alpha = 0.95f))
                    }
                    LikeTier.TRACK -> {}
                }
            } else {
                // 3D Debossed Engraved Cavity when unliked
                // Top inset shadow
                val insetShadowPath = heartPath(w, h)
                drawContext.canvas.save()
                drawContext.canvas.translate(0f, 1.8f)
                drawPath(insetShadowPath, Color(0x95000000))
                drawContext.canvas.restore()

                // Soft teal halo so the un-liked heart still reads as a heart against the
                // dark card (the old #1B2F33-on-#040D12 deboss was near-invisible until tapped).
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF39C5BB).copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.48f),
                        radius = w * 0.72f
                    )
                )

                // Satin metallic fill - lifted a few stops so it separates from the background
                val debossFill = Brush.verticalGradient(
                    listOf(
                        Color(0xFF2E4F55),
                        Color(0xFF16292E)
                    )
                )
                drawPath(p, debossFill)

                // Bottom bevel highlight (light catching the bottom rim of the engraved cavity)
                val bottomChamfer = Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.45f)
                    )
                )
                drawPath(p, bottomChamfer, style = Stroke(width = 2.0f))

                // Crisp Miku-teal outline - the main visibility cue
                drawPath(p, Color(0xFF39C5BB).copy(alpha = 0.95f), style = Stroke(width = 2.8f))
            }
        }
        // Cumulative heart-score badge — appears once a track has more than one heart (played
        // through and hearted multiple times). The count is what feeds TasteEngine.
        if (badgeCount > 1) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-2).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF2277)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (badgeCount > 99) "99+" else badgeCount.toString(),
                    color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black, maxLines = 1
                )
            }
        }
    }
}

/**
 * Now-Playing heart with the earn-per-play model: a heart is earnable only after the current play
 * passes 94% without skipping (MikuPlayQualifier). Tap when earnable = +1 to the cumulative heart
 * score; long-press = clear all hearts. Shows the count badge and an earnable glow ring.
 */
@Composable
fun NowPlayingHeart(track: Track, size: androidx.compose.ui.unit.Dp = 42.dp) {
    val ctx = LocalContext.current
    var count by remember(track.id) { mutableStateOf(LikeStore.heartCount(ctx, track.id)) }
    var earnable by remember(track.id) { mutableStateOf(MikuPlayQualifier.isHeartable(track.id)) }
    // Cheap 1s poll (same cadence as the progress bar) keeps earnable/count fresh across the play.
    LaunchedEffect(track.id) {
        while (true) {
            earnable = MikuPlayQualifier.isHeartable(track.id)
            count = LikeStore.heartCount(ctx, track.id)
            delay(1000)
        }
    }
    TieredRainbowHeart(
        tier = LikeTier.TRACK,
        liked = count > 0,
        size = size,
        earnable = earnable,
        badgeCount = count,
        onToggle = {
            // Always allowed — like anytime. The play fraction is recorded as a WEIGHT, not a gate.
            count = LikeStore.heart(ctx, track); earnable = MikuPlayQualifier.isHeartable(track.id)
        },
        onLongPress = {
            LikeStore.clearHearts(ctx, track); count = 0; earnable = MikuPlayQualifier.isHeartable(track.id)
            android.widget.Toast.makeText(ctx, "Hearts cleared", android.widget.Toast.LENGTH_SHORT).show()
        }
    )
}

/** Live FFT visualizer via the Android Visualizer API, drawn as a Miku-teal mirrored bar spectrum. */
@Composable
private fun VisualizerView(sessionId: Int, modifier: Modifier = Modifier) {
    val bars = remember { mutableStateOf(FloatArray(48)) }
    DisposableEffect(sessionId) {
        var viz: Visualizer? = null
        try {
            viz = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wf: ByteArray?, sr: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, sr: Int) {
                        if (fft == null) return
                        val n = bars.value.size
                        val out = FloatArray(n)
                        val step = (fft.size / 2) / n
                        for (i in 0 until n) {
                            val idx = i * step * 2 + 2
                            val re = if (idx < fft.size) fft[idx].toInt() else 0
                            val im = if (idx + 1 < fft.size) fft[idx + 1].toInt() else 0
                            out[i] = (hypot(re.toFloat(), im.toFloat()) / 90f).coerceIn(0f, 1f)
                        }
                        bars.value = out
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (_: Throwable) {}
        onDispose { try { viz?.enabled = false; viz?.release() } catch (_: Throwable) {} }
    }
    val brush = Brush.verticalGradient(listOf(MikuPink, MikuTealBright, MikuTeal))
    Canvas(modifier.background(Color(0xFF06171A))) {
        val n = bars.value.size
        val gap = 3.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val midY = size.height / 2f
        for (i in 0 until n) {
            val h = (bars.value[i] * size.height * 0.9f).coerceAtLeast(2f)
            val x = i * (bw + gap)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, midY - h / 2f),
                size = androidx.compose.ui.geometry.Size(bw, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2, bw / 2)
            )
        }
    }
}


private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
