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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
    var pinControls by remember { mutableStateOf(false) }
    var presetToast by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun showPresetName(prefix: String) {
        presetToast = prefix
        scope.launch { delay(280); presetToast = ProjectMNative.presetName().ifBlank { prefix } }
    }
    LaunchedEffect(presetToast) { if (presetToast.isNotEmpty()) { delay(2000); presetToast = "" } }
    LaunchedEffect(showOverlayControls, pinControls) { if (showOverlayControls && !pinControls) { delay(4500); showOverlayControls = false } }
    val scrollState = rememberScrollState()

    var dragOffsetY by remember { mutableStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(targetValue = dragOffsetY, label = "dragOffsetY")

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
        // Branded background: blurred album art + a faint Miku behind the whole screen (normal mode;
        // the fullscreen viz draws its own opaque surface over this).
        art?.let {
            androidx.compose.foundation.Image(
                it, null,
                Modifier.matchParentSize().blur(40.dp),
                contentScale = ContentScale.Crop, alpha = 0.30f
            )
        }
        Box(Modifier.matchParentSize().background(
            Brush.verticalGradient(listOf(Ground.copy(alpha = 0.60f), Ground.copy(alpha = 0.88f), Ground))
        ))
        // Ambient Miku watermark: a different official wallpaper per track (never the same art as
        // the Home backdrop) — every bespoke collab image gets its moment.
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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showOverlayControls = !showOverlayControls },
                        onDoubleTap = { ProjectMNative.requestNext(); showPresetName("Next ▸") }
                    )
                }
        ) {
            ProjectMVisualizerView(
                sessionId = player.audioSessionId,
                preset = currentPreset,
                modifier = Modifier.fillMaxSize()
            )

            // Pretty transient preset-name toast (real .milk name).
            androidx.compose.animation.AnimatedVisibility(
                visible = presetToast.isNotEmpty(),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(24.dp))
                        .background(Brush.horizontalGradient(listOf(MikuTeal.copy(alpha = .92f), MikuPink.copy(alpha = .92f))))
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color(0xFF04161A), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(presetToast, color = Color(0xFF04161A), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Top-right floating chips: pin + exit (always available, tiny, glassy — minimal vis blocking).
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
                            Text(track.artist + "   ·   " + presetToast.ifBlank { ProjectMNative.presetName() }.ifBlank { "visualizer" },
                                color = MikuTeal.copy(alpha = .9f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        RainbowHeart(LikeStore.isLiked(track.id)) { LikeStore.toggle(ctx, track) }
                    }
                    Spacer(Modifier.height(8.dp))
                    EmbossedScrubber(
                        pos = pos, dur = dur, playing = isPlaying,
                        onSeekPreview = { dragging = true; pos = it },
                        onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        HapticIconButton(onClick = { ProjectMNative.requestPrev(); showPresetName("◂ Prev") }) {
                            Icon(Icons.Default.GraphicEq, "Prev preset", tint = MikuGold, modifier = Modifier.size(22.dp))
                        }
                        HapticIconButton(onClick = { player.seekToPreviousMediaItem() }) {
                            Icon(Icons.Default.SkipPrevious, "Prev", tint = MikuTeal, modifier = Modifier.size(34.dp))
                        }
                        HapticIconButton(onClick = { if (player.isPlaying) player.pause() else player.play() },
                            face = MikuTeal, modifier = Modifier.size(width = 78.dp, height = 58.dp)) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color(0xFF00201D), modifier = Modifier.size(34.dp))
                        }
                        HapticIconButton(onClick = { player.seekToNextMediaItem() }) {
                            Icon(Icons.Default.SkipNext, "Next", tint = MikuTeal, modifier = Modifier.size(34.dp))
                        }
                        HapticIconButton(onClick = { ProjectMNative.requestNext(); showPresetName("Next ▸") }) {
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
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // Top Nav Bar. `flat = true` on these — a slim glassy nav row, not hardware transport
        // keys, so no embossed key-face pill behind them (that read as a stray dark blob next to
        // the chrome-free heart icon).
        Row(verticalAlignment = Alignment.CenterVertically) {
            HapticIconButton(onClick = onClose, flat = true) {
                Icon(Icons.Default.KeyboardArrowDown, "Close", tint = MikuTeal, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MIKU MUSIC PLAYER", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontFamily = AudiowideFont)
                Text(track.album.ifBlank { "Miku Player" }, color = Color(0xFFE8F4F2), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Baloo2Font, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            HapticIconButton(onClick = { showQueue = !showQueue }, flat = true) {
                Icon(Icons.Default.QueueMusic, "Up Next Queue", tint = if (showQueue) MikuPink else MikuTeal, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(4.dp))
            HapticIconButton(onClick = onTape, flat = true) { TapeIcon(tint = MikuPink, modifier = Modifier.size(26.dp)) }
        }

        Spacer(Modifier.height(10.dp))

        // Stage (Art / ProjectM Visualizer) — flexible height: it yields to the fixed elements
        // below (art panel, seek, transport) so nothing ever clips off-screen.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0A2528)),
            contentAlignment = Alignment.Center
        ) {
            if (showViz) {
                ProjectMVisualizerView(
                    sessionId = player.audioSessionId,
                    preset = currentPreset,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (art != null) {
                androidx.compose.foundation.Image(
                    art!!, "Album art", Modifier.fillMaxSize(), contentScale = ContentScale.Fit
                )
            } else {
                Icon(Icons.Default.Album, null, tint = MikuTeal.copy(alpha = .5f), modifier = Modifier.size(120.dp))
            }

            // Interactive Gestures on Stage (Visualizer or Album Art)
            if (showViz) {
                Box(
                    Modifier.matchParentSize().pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { isFullscreenVisualizer = true },
                            onLongPress = { showViz = false }
                        )
                    }
                )
            } else {
                var totalDragX by remember { mutableStateOf(0f) }
                Box(
                    // Inset the full-screen swipe-to-change-track area away from the
                    // horizontal edges so it doesn't swallow the OS gesture-nav back
                    // swipe (which lives in the ~24dp edge inset). Track-change still
                    // works across the whole middle; the edges are left to the system.
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

        Spacer(Modifier.height(10.dp))

        // Artistic album-art + data panel — the visualizer now gets ALL the space freed up by
        // removing the preset-switch button row and the standalone title above it (title lives in
        // this card now, as its own prominent first line). Styling enhanced to match: a real
        // gradient border + drop shadow instead of a flat fill, so it reads as its own elevated
        // "now playing" card rather than an inert info strip.
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val rr = CornerRadius(20.dp.toPx(), 20.dp.toPx())
                    drawRoundRect(Color(0x59000000), topLeft = Offset(0f, 3.dp.toPx()), size = size, cornerRadius = rr)
                }
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(Surface1, Color(0xFF0A2528))))
                .border(1.2.dp, Brush.horizontalGradient(listOf(MikuTealBright.copy(alpha = 0.5f), MikuPink.copy(alpha = 0.4f))), RoundedCornerShape(20.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    if (art != null) androidx.compose.foundation.Image(art!!, "Album art", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxSize().background(Color(0xFF123438)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Album, null, tint = MikuTeal.copy(alpha = .6f), modifier = Modifier.size(40.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    // Title moved here from its old standalone spot above the vis — now the
                    // card's own headline, largest text in the block.
                    Text(
                        track.title,
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = RighteousFont,
                        letterSpacing = 0.3.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        track.album.ifBlank { track.artist },
                        color = MikuTealBright,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = Baloo2Font,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                            .clickable(enabled = track.album.isNotBlank()) {
                                onClose()
                                onOpenAlbum(track.albumArtist.ifBlank { track.artist }, track.album)
                            }
                    )
                    Spacer(Modifier.height(2.dp))
                    val displayYear = TrackYear.yearFor(ctx, track)?.takeIf { it > 0 } ?: track.year.takeIf { it > 0 }
                    Text(
                        track.artist + if (displayYear != null) "   ·   $displayYear" else "",
                        color = Muted,
                        fontSize = 12.5.sp,
                        fontFamily = Baloo2Font,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            onClose()
                            onOpenArtist(track.artist)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    // All the exacting metrics as pills, in one place (they wrap to fit).
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Same bespoke neon diamond/pentagon/hexagon chips as everywhere else (mini
                        // bar, track rows, album hero) — format included here (this panel has the
                        // room), not just bit-depth/sample-rate. Wrapped in a Row so FlowRow treats
                        // the trio as one flow item (keeps them from wrapping apart mid-group).
                        Row { TechBadgeRow(ctx, track, fontSize = 11.sp, spacing = 6.dp, includeFormat = true) }
                        if (track.bitrateKbps > 0) DataChip("${track.bitrateKbps} kbps", bitrateColor(track.bitrateKbps))
                        DataChip(qualityTier(track), bitrateColor(track.bitrateKbps))
                        if (track.durationMs > 0) DataChip(fmtTime(track.durationMs), Muted)
                        if (track.sizeBytes > 0) DataChip("${"%.1f".format(track.sizeBytes / 1e6)} MB", Muted)
                    }
                }
                // Persistent like control — was only reachable via the auto-hiding overlay controls
                // (tap-to-reveal, vanishes after ~4.5s), so it read as "gone" from the main screen.
                Spacer(Modifier.width(8.dp))
                RainbowHeart(LikeStore.isLiked(track.id)) { LikeStore.toggle(ctx, track) }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Seek Bar — custom embossed, color-shifting physical control
        EmbossedScrubber(
            pos = pos, dur = dur, playing = isPlaying,
            onSeekPreview = { dragging = true; pos = it },
            onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(pos), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont, letterSpacing = 0.5.sp)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(dur), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont, letterSpacing = 0.5.sp)
        }

        Spacer(Modifier.height(6.dp))

        // Transport Controls with Shuffle & Repeat — one merged "hardware deck" assembly (see
        // ControlAssembly) instead of five buttons floating independently edge-to-edge across the
        // full width; still five fully distinct, individually-pressable buttons, just housed
        // together like a real DAP's molded button cluster.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            ControlAssembly(cornerRadius = 34.dp) {
                HapticIconButton(onClick = {
                    shuffle = !shuffle
                    player.shuffleModeEnabled = shuffle
                    PlayerPreferences.saveShuffle(ctx, shuffle)
                }, flat = true, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) MikuPink else Muted, modifier = Modifier.size(20.dp))
                }
                HapticIconButton(onClick = { player.seekToPreviousMediaItem() }, keyShape = TransportShapes.prevWing, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Prev", tint = MikuTeal, modifier = Modifier.size(30.dp))
                }
                // The play key is the hero: larger than its neighbours and a little oblong-wide.
                HapticIconButton(onClick = { if (player.isPlaying) player.pause() else player.play() },
                    face = MikuTeal, keyShape = TransportShapes.hero, modifier = Modifier.size(width = 84.dp, height = 62.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Play/Pause",
                        tint = Color(0xFF00201D),
                        modifier = Modifier.size(36.dp)
                    )
                }
                HapticIconButton(onClick = { player.seekToNextMediaItem() }, keyShape = TransportShapes.nextWing, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", tint = MikuTeal, modifier = Modifier.size(30.dp))
                }
                HapticIconButton(onClick = {
                    repeat = !repeat
                    player.repeatMode = if (repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                    PlayerPreferences.saveRepeat(ctx, repeat)
                }, flat = true, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Repeat, "Repeat", tint = if (repeat) MikuPink else Muted, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
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
    text, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont, letterSpacing = 0.5.sp,
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
        .padding(horizontal = 8.dp, vertical = 3.dp)
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
fun RainbowHeart(liked: Boolean, onToggle: () -> Unit) = TieredRainbowHeart(LikeTier.TRACK, liked, onToggle)

/** Ring-wrapped heart — "the whole collection wrapped together," a step up from a single track. */
@Composable
fun AlbumRainbowHeart(liked: Boolean, onToggle: () -> Unit) = TieredRainbowHeart(LikeTier.ALBUM, liked, onToggle)

/** Heart with a spark accent — "everything this person makes," the widest tier. */
@Composable
fun ArtistRainbowHeart(liked: Boolean, onToggle: () -> Unit) = TieredRainbowHeart(LikeTier.ARTIST, liked, onToggle)

@Composable
fun TieredRainbowHeart(tier: LikeTier, liked: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    // Manual time loops (device animations are disabled). A flowing multi-stop rainbow gradient fills
    // the heart (many hues at once), with a subtle detailed lub-dub beat rather than one big throb.
    var phase by remember { mutableStateOf(0f) }
    var beat by remember { mutableStateOf(0f) }
    LaunchedEffect(liked) {
        if (!liked) return@LaunchedEffect
        // Highest wake-frequency loop in the app (16ms) — pure decorative pulse, so it's not worth
        // running at all once the screen isn't actively shown (dimmed/ambient/off all count, same
        // cutoff the visualizer already uses). Coarse 500ms check-back while idle so it resumes
        // smoothly the instant the screen comes back, instead of a hard cliff.
        while (true) {
            if (IdleController.screenActive) {
                phase = (phase + 2.4f) % 360f; beat = (beat + 0.014f) % 1f
                delay(16)
            } else delay(500)
        }
    }
    val pulse = if (liked) 1f + 0.055f * heartbeat(beat) else 1f
    val label = when (tier) { LikeTier.TRACK -> "song"; LikeTier.ALBUM -> "album"; LikeTier.ARTIST -> "artist" }
    Box(
        Modifier.size(30.dp).scale(pulse)
            .semantics { contentDescription = "${if (liked) "Unlike" else "Like"} $label"; role = Role.Checkbox; toggleableState = ToggleableState(liked) }
            .clickable { Haptics.tick(ctx); onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            val p = heartPath(size.width, size.height)
            if (liked) {
                // Each tier gets its own hue-stepping/rotation-speed formula — a "slightly
                // different rainbow pattern," not just a recolor — plus its own gradient axis.
                val cols = when (tier) {
                    LikeTier.TRACK -> (0..6).map { Color.hsv(((it * 52) + phase) % 360f, 0.85f, 1f) }
                    LikeTier.ALBUM -> (0..6).map { Color.hsv(((it * 52) + phase * 1.4f + 40f) % 360f, 0.78f, 0.95f) }
                    LikeTier.ARTIST -> (0..7).map { Color.hsv(((it * 46) + phase * 0.7f + 200f) % 360f, 0.9f, 1f) }
                }
                val brush = when (tier) {
                    LikeTier.TRACK -> Brush.linearGradient(cols, Offset(0f, size.height), Offset(size.width, 0f))
                    LikeTier.ALBUM -> Brush.linearGradient(cols, Offset(size.width, size.height), Offset(0f, 0f))
                    LikeTier.ARTIST -> Brush.radialGradient(cols, center = Offset(size.width * 0.5f, size.height * 0.42f), radius = size.width * 0.75f)
                }
                drawPath(p, brush)
                drawPath(p, Color.White.copy(alpha = 0.10f))                                    // soft sheen
                drawPath(p, Color.White.copy(alpha = 0.35f), style = Stroke(1.2f))              // crisp edge
                when (tier) {
                    LikeTier.ALBUM -> {
                        // Orbit ring — reads as "a whole collection," distinguishes at a glance
                        // from the plain track heart without changing the silhouette.
                        drawCircle(
                            Color.White.copy(alpha = 0.55f),
                            radius = size.minDimension * 0.62f,
                            center = Offset(size.width / 2f, size.height / 2f),
                            style = Stroke(width = 1.1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
                        )
                    }
                    LikeTier.ARTIST -> {
                        // Small 4-point spark at the top-right notch — "spotlight on this person."
                        val cx = size.width * 0.82f; val cy = size.height * 0.14f; val r = size.minDimension * 0.14f
                        val spark = androidx.compose.ui.graphics.Path().apply {
                            moveTo(cx, cy - r); lineTo(cx + r * 0.28f, cy - r * 0.28f)
                            lineTo(cx + r, cy); lineTo(cx + r * 0.28f, cy + r * 0.28f)
                            lineTo(cx, cy + r); lineTo(cx - r * 0.28f, cy + r * 0.28f)
                            lineTo(cx - r, cy); lineTo(cx - r * 0.28f, cy - r * 0.28f)
                            close()
                        }
                        drawPath(spark, Color.White.copy(alpha = 0.85f))
                    }
                    LikeTier.TRACK -> {}
                }
            } else {
                drawPath(p, Color(0xFF54706B))
            }
        }
    }
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
