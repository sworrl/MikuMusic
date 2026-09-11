package com.miku.launcher.recents

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.miku.launcher.*
import kotlin.math.absoluteValue

/**
 * Hatsune Miku Beryl / Compiz-Enhanced Multi-Tasking Recents Carousel.
 * Provides a 1:1 Pixel-style horizontal multitasking switcher featuring:
 * - Styled task cards (icon + label + package; NOT screen snapshots — no TaskSnapshot is read)
 * - Beryl 3D Perspective Tilt & Coverflow Curve
 * - Beryl Disintegration / Particle Dissolve Animation on dismiss
 * - Quick "Clear All" with holographic shockwave
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MikuRecentsOverviewCarousel(
    tasks: List<RecentTaskItem>,
    onLaunchTask: (RecentTaskItem) -> Unit,
    onDismissTask: (RecentTaskItem) -> Unit,
    onClearAll: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val currentTasks = remember { mutableStateListOf(*tasks.toTypedArray()) }
    val pagerState = rememberPagerState(
        initialPage = (currentTasks.size - 1).coerceAtLeast(0),
        pageCount = { currentTasks.size }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE030D14))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest
            )
    ) {
        if (currentTasks.isEmpty()) {
            // Empty State
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "NO RECENT TASKS",
                    color = MikuCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tap anywhere to return home",
                    color = MikuTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = AudiowideFont
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, start = 20.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ACTIVE APPS",
                            color = MikuCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MikuNeonPink.copy(alpha = 0.25f))
                                .border(1.dp, MikuNeonPink, RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${currentTasks.size}",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Clear All Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0x33FF1744), Color(0x3300E5FF))))
                            .border(1.dp, MikuCyan.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .clickable {
                                currentTasks.clear()
                                onClearAll()
                                onDismissRequest()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "CLEAR ALL",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                    }
                }

                // Horizontal Pager Carousel
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    pageSpacing = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    if (page < currentTasks.size) {
                        val task = currentTasks[page]
                        val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue

                        // Beryl 3D perspective transformation
                        val scale = (1f - (pageOffset * 0.12f)).coerceIn(0.85f, 1f)
                        val rotationY = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction) * 15f

                        var isDismissing by remember { mutableStateOf(false) }
                        var dismissOffsetY by remember { mutableFloatStateOf(0f) }

                        val animatedDismissOffsetY by animateFloatAsState(
                            targetValue = if (isDismissing) -1200f else dismissOffsetY,
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                            label = "BerylDismiss"
                        )
                        val animatedAlpha by animateFloatAsState(
                            targetValue = if (isDismissing) 0f else 1f,
                            animationSpec = tween(300),
                            label = "BerylAlpha"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.82f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.rotationY = rotationY.coerceIn(-30f, 30f)
                                    translationY = animatedDismissOffsetY
                                    alpha = animatedAlpha
                                    cameraDistance = 12f * density
                                }
                                .pointerInput(task.taskId) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (dismissOffsetY < -120f) {
                                                isDismissing = true
                                                onDismissTask(task)
                                                currentTasks.remove(task)
                                            } else {
                                                dismissOffsetY = 0f
                                            }
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            if (dragAmount < 0 || dismissOffsetY < 0) {
                                                dismissOffsetY += dragAmount
                                                change.consume()
                                            }
                                        }
                                    )
                                }
                                .clickable {
                                    onLaunchTask(task)
                                    onDismissRequest()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            LiveAppSnapshotCard(task = task)
                        }
                    }
                }

                // Bottom Hint
                Text(
                    text = "▲ Swipe up to dismiss · Tap to switch",
                    color = MikuTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}

/**
 * Styled task card with Beryl holographic glow. It shows the task's real icon, label and package —
 * it is NOT a live screen preview, and must never imitate the app's current UI.
 */
@Composable
fun LiveAppSnapshotCard(task: RecentTaskItem) {
    val brandColor = remember(task.packageName) {
        val p = task.packageName.lowercase()
        when {
            p.contains("player") || p.contains("music") -> Color(0xFF00E5FF)
            p.contains("settings") -> Color(0xFF7C4DFF)
            p.contains("camera") -> Color(0xFFFF4081)
            p.contains("gallery") || p.contains("photo") -> com.miku.launcher.ui.MikuIdentity.Gold
            p.contains("firefox") || p.contains("chrome") || p.contains("browser") -> Color(0xFFFF6D00)
            p.contains("radio") -> com.miku.launcher.ui.MikuIdentity.Leek
            else -> Color(0xFF00E5FF)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF071924))
            .border(
                BorderStroke(
                    1.5.dp,
                    Brush.verticalGradient(
                        listOf(
                            brandColor.copy(alpha = 0.9f),
                            CyberGlassBorder.copy(alpha = 0.35f),
                            brandColor.copy(alpha = 0.6f)
                        )
                    )
                ),
                RoundedCornerShape(20.dp)
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Card Top Bar with Icon & Label
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B2433))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (task.iconRes != null) {
                    Image(
                        painter = painterResource(id = task.iconRes),
                        contentDescription = task.label,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (task.systemIcon != null) {
                    val bmp = remember(task.systemIcon) { task.systemIcon.toBitmap(64, 64).asImageBitmap() }
                    Image(
                        bitmap = bmp,
                        contentDescription = task.label,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Apps,
                        contentDescription = task.label,
                        tint = brandColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Text(
                    text = task.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Task card body. NOT a live screen snapshot — no TaskSnapshot/thumbnail is read here, so
            // nothing may imitate the app's actual current UI (the "settings list" branch used to draw
            // four fabricated skeleton rows that looked like the real Settings screen).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF041017),
                                Color(0xFF02090D)
                            )
                        )
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                val p = task.packageName.lowercase()
                // Real playback state — "NOW PLAYING" used to show for any package whose name merely
                // contained "player"/"music", playing or not.
                val audioPlaying by com.miku.launcher.bpm.MikuBpmEngine.state.collectAsState()
                when {
                    p.contains("player") || p.contains("music") -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(brandColor.copy(alpha = 0.2f))
                                    .border(1.5.dp, brandColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = brandColor, modifier = Modifier.size(36.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                if (audioPlaying.isPlaying) "NOW PLAYING" else "AUDIO APP",
                                color = MikuCyan, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont
                            )
                            Spacer(Modifier.height(4.dp))
                            // Stylised task card, not telemetry — no chip/format claim here.
                            Text(task.label, color = MikuTextSecondary, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                    else -> {
                        // Generic App View Mockup
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (task.iconRes != null) {
                                Image(painter = painterResource(id = task.iconRes), contentDescription = null, modifier = Modifier.size(64.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(task.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            Spacer(Modifier.height(4.dp))
                            Text(task.packageName, color = MikuTextSecondary, fontSize = 9.5.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
