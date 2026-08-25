package com.miku.systemui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Miku Recents — the Pixel-style overview, opened by the home-pill swipe-up-and-hold gesture
 * (and every former GLOBAL_ACTION_RECENTS path in MikuNotificationShadeService).
 * Horizontal snapping carousel of live task snapshots; tap = switch, swipe up = dismiss task,
 * CLEAR ALL pill, scrim tap / back = close. Translucent, excluded from recents itself.
 */
class MikuRecentsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { MikuRecentsScreen(onClose = { finish() }) }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

@Composable
private fun MikuRecentsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val density = LocalDensity.current

    val tasks = remember { mutableStateListOf<MikuTaskStack.Entry>() }
    val snapshots = remember { mutableStateMapOf<Int, Bitmap>() }
    var loaded by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf<MikuMediaHub.Now?>(null) }
    var sparkleAt by remember { mutableStateOf<Offset?>(null) }
    var sparkleKey by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            media = withContext(Dispatchers.IO) { runCatching { MikuMediaHub.now(ctx) }.getOrNull() }
            kotlinx.coroutines.delay(1000)
        }
    }

    // Load the task list off-thread immediately; snapshots fill in per card.
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { MikuTaskStack.recents(ctx, 10) }
        tasks.clear(); tasks.addAll(list)
        loaded = true
        entered = true
        list.forEachIndexed { i, e ->
            launch(Dispatchers.IO) {
                // The task we came from is still "fresh" — ask WM to take one if missing.
                MikuTaskStack.snapshot(e.taskId, lowRes = true, takeIfNeeded = i == 0)?.let { bmp ->
                    withContext(Dispatchers.Main) { snapshots[e.taskId] = bmp }
                }
            }
        }
    }

    val enterScale by animateFloatAsState(if (entered) 1f else 0.92f, tween(140, easing = FastOutSlowInEasing), label = "s")
    val enterAlpha by animateFloatAsState(if (entered) 1f else 0f, tween(120), label = "a")

    BackHandler { onClose() }

    // 360x640dp target: 8dp grid, cards 248x396 (≈ 9:14.3 so the portrait snapshot crops top-aligned).
    val cardW = (config.screenWidthDp * 0.69f).dp
    val cardH = (config.screenHeightDp * 0.58f).dp
    val sidePad = ((config.screenWidthDp.dp - cardW) / 2)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enterAlpha }
            .background(MikuDarkBg.copy(alpha = 0.90f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
    ) {
        Column(Modifier.fillMaxSize().graphicsLayer { scaleX = enterScale; scaleY = enterScale }) {
            // Header (8dp grid, 40dp tall)
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 32.dp).height(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RECENTS", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (!loaded) "…" else "${tasks.size} ${if (tasks.size == 1) "APP" else "APPS"}",
                    color = MikuMuted, fontSize = 11.sp, letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(8.dp))

            if (loaded && tasks.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(cardH), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("♥", color = MikuTeal.copy(alpha = 0.6f), fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("No recent apps", color = MikuTextSecondary, fontSize = 13.sp)
                    }
                }
            } else {
                val listState = rememberLazyListState()
                LazyRow(
                    state = listState,
                    flingBehavior = rememberSnapFlingBehavior(listState),
                    contentPadding = PaddingValues(horizontal = sidePad),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().height(cardH + 48.dp)
                ) {
                    itemsIndexed(tasks, key = { _, e -> e.taskId }) { _, entry ->
                        RecentTaskCard(
                            entry = entry,
                            snapshot = snapshots[entry.taskId],
                            width = cardW,
                            height = cardH,
                            onOpen = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                scope.launch(Dispatchers.IO) { MikuTaskStack.switchTo(ctx, entry.taskId) }
                                onClose()
                            },
                            onDismiss = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                sparkleAt = Offset(config.screenWidthDp / 2f, config.screenHeightDp * 0.3f); sparkleKey++
                                tasks.remove(entry)
                                scope.launch(Dispatchers.IO) { MikuTaskStack.remove(entry.taskId) }
                            }
                        )
                    }
                    // Pixel-style trailing "Clear all" card at the end of the carousel
                    if (tasks.isNotEmpty()) item(key = "clear_all") {
                        Box(Modifier.width(cardW * 0.6f).height(cardH + 48.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MikuSurface2.copy(alpha = 0.95f))
                                    .border(1.dp, MikuTealBright.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        sparkleAt = Offset(config.screenWidthDp / 2f, config.screenHeightDp * 0.3f); sparkleKey++
                                        val ids = tasks.map { it.taskId }
                                        tasks.clear()
                                        scope.launch(Dispatchers.IO) { ids.forEach { MikuTaskStack.remove(it) } }
                                        scope.launch { kotlinx.coroutines.delay(260); onClose() }
                                    }
                                    .padding(horizontal = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("CLEAR ALL", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Media chip (bottom, above the home-pill zone) when something is playing.
            media?.let { m ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 40.dp)) {
                    RecentsMediaChip(m, onOpen = {
                        runCatching { ctx.packageManager.getLaunchIntentForPackage(m.pkg)?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }?.let { ctx.startActivity(it) } }
                        onClose()
                    })
                }
            }
        }
        sparkleAt?.let { SparkleBurst(it, sparkleKey) }
    }
    // Keep density referenced for future px math (avoids unused warning on some configs).
    @Suppress("UNUSED_VARIABLE") val d = density
}

@Composable
private fun RecentTaskCard(
    entry: MikuTaskStack.Entry,
    snapshot: Bitmap?,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissPx = with(density) { 120.dp.toPx() }
    val iconBitmap = remember(entry.pkg) {
        runCatching { entry.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }

    Column(
        Modifier
            .width(width)
            .graphicsLayer {
                translationY = offsetY.value
                val p = (-offsetY.value / (dismissPx * 2.2f)).coerceIn(0f, 1f)
                alpha = 1f - p * 0.85f
                scaleX = 1f - p * 0.08f; scaleY = 1f - p * 0.08f
            }
            .pointerInput(entry.taskId) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (offsetY.value < -dismissPx) {
                            scope.launch {
                                offsetY.animateTo(-size.height * 1.4f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium))
                                onDismiss()
                            }
                        } else {
                            scope.launch { offsetY.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
                        }
                    },
                    onDragCancel = { scope.launch { offsetY.animateTo(0f, tween(180)) } },
                    onVerticalDrag = { change, drag ->
                        change.consume()
                        val next = (offsetY.value + drag).coerceAtMost(40f)
                        scope.launch { offsetY.snapTo(next) }
                    }
                )
            }
    ) {
        // App identity chip — 40dp row, pill chip with icon + label (Pixel overview header)
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(MikuSurface2.copy(alpha = 0.9f))
                    .border(1.dp, MikuTeal.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(start = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconBitmap != null) {
                    Image(iconBitmap, contentDescription = null, modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)))
                } else {
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(MikuTeal.copy(alpha = 0.4f)))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.label.toString(), color = MikuTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        // Snapshot card
        Box(
            Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(20.dp))
                .background(MikuCardBg)
                .border(
                    1.2.dp,
                    Brush.verticalGradient(listOf(MikuTealBright.copy(alpha = 0.85f), MikuPurple.copy(alpha = 0.45f), MikuPinkBright.copy(alpha = 0.55f))),
                    RoundedCornerShape(20.dp)
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen() }
        ) {
            if (snapshot != null) {
                Image(
                    bitmap = snapshot.asImageBitmap(),
                    contentDescription = entry.label.toString(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Themed placeholder: big app icon on glass
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D2630), Color(0xFF07171E), Color(0xFF030B0F)))), contentAlignment = Alignment.Center) {
                    if (iconBitmap != null) {
                        Image(iconBitmap, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                    } else {
                        Text("♥", color = MikuTeal, fontSize = 40.sp)
                    }
                }
            }
            // Soft teal vignette at the bottom for depth
            Box(
                Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MikuTeal.copy(alpha = 0.18f))))
            )
        }
    }
}


/** Small Now-Playing chip under the carousel: art · title/artist · play-pause · next. */
@Composable
private fun RecentsMediaChip(m: MikuMediaHub.Now, onOpen: () -> Unit) {
    val view = LocalView.current
    val accent = remember(m.art) { Color(MikuMediaHub.dominantColor(m.art)) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.35f), MikuSurface1.copy(alpha = 0.96f))))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
            .clickable { onOpen() }
            .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MikuSurface2), contentAlignment = Alignment.Center) {
            val art = m.art
            if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title.ifBlank { m.appLabel }, color = MikuTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.artist.ifBlank { m.appLabel }, color = MikuTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(accent).clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                if (m.isPlaying) m.controller.transportControls.pause() else m.controller.transportControls.play()
            }, contentAlignment = Alignment.Center
        ) { Icon(if (m.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = MikuDarkBg, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); m.controller.transportControls.skipToNext() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.SkipNext, null, tint = MikuTextPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

/** A tiny kawaii sparkle burst (hearts + stars) at [center] (dp coords), replayed when [key] changes. */
@Composable
private fun SparkleBurst(center: Offset, key: Int) {
    val t = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { t.snapTo(0f); t.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    if (t.value >= 1f) return
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        val cx = with(density) { center.x.dp.toPx() }; val cy = with(density) { center.y.dp.toPx() }
        val p = t.value
        for (i in 0 until 10) {
            val ang = i * (Math.PI * 2 / 10) + 0.4
            val r = with(density) { (24 + 64 * p).dp.toPx() }
            val x = cx + (Math.cos(ang) * r).toFloat(); val y = cy + (Math.sin(ang) * r).toFloat() - with(density) { (20 * p).dp.toPx() }
            val col = if (i % 2 == 0) MikuTealBright else MikuPinkBright
            drawCircle(col.copy(alpha = (1f - p)), radius = with(density) { (3.5f * (1f - p * 0.5f)).dp.toPx() }, center = Offset(x, y))
        }
    }
}
