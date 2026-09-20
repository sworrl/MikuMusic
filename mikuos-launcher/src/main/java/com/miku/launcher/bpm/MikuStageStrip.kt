package com.miku.launcher.bpm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The show, on screen: the crowd you can lose, Miku reacting to you, and whatever chaos is running.
 *
 * Extracted into its own file and kept to small composables on purpose. The observatory modal is
 * already the biggest composable in the launcher and ART refuses to JIT a method over 16384 dex
 * instructions (see tools/scan_jit_limit.sh), so new UI goes beside it, not inside it.
 */
@Composable
fun MikuStageStrip(modifier: Modifier = Modifier) {
    val crowd by MikuStagePerformance.crowd.collectAsState()
    val mood by MikuStagePerformance.mood.collectAsState()
    val saying by MikuStagePerformance.saying.collectAsState()
    val chaos by MikuStagePerformance.chaos.collectAsState()
    val heckler by MikuStagePerformance.heckler.collectAsState()
    val setOver by MikuStagePerformance.setOver.collectAsState()

    // A line stays up long enough to read and then gets out of the way.
    LaunchedEffect(saying) { if (saying != null) { delay(3200); MikuStagePerformance.clearSaying() } }

    Column(modifier.fillMaxWidth()) {
        CrowdMeter(crowd, mood)
        AnimatedVisibility(chaos != MikuStagePerformance.Chaos.NONE, enter = fadeIn(), exit = fadeOut()) {
            ChaosBanner(chaos)
        }
        AnimatedVisibility(saying != null, enter = fadeIn(tween(140)), exit = fadeOut(tween(300))) {
            MikuSays(mood, saying.orEmpty())
        }
        AnimatedVisibility(heckler != null, enter = fadeIn(), exit = fadeOut()) {
            HecklerLine(heckler.orEmpty())
        }
        AnimatedVisibility(setOver, enter = fadeIn()) { SetOverCard() }
    }
}

/** The room. Green when full, red when it is emptying, and it is a real fail state at zero. */
@Composable
private fun CrowdMeter(crowd: Float, mood: MikuStagePerformance.Mood) {
    val animated by animateFloatAsState(crowd, tween(260), label = "crowd")
    val tone = when {
        animated > 78f -> Color(0xFF39C5BB)
        animated > 45f -> Color(0xFF7FE6DE)
        animated > 22f -> Color(0xFFFFCF6B)
        else -> Color(0xFFFF4D5E)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(mood.face, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient(listOf(tone.copy(alpha = 0.65f), tone)))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${animated.toInt()}% CROWD",
            color = tone,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun ChaosBanner(chaos: MikuStagePerformance.Chaos) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x33FF2277))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(chaos.title, color = Color(0xFFFF6FA6), fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(
            chaos.blurb,
            color = Color(0xFFE8F4F2),
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MikuSays(mood: MikuStagePerformance.Mood, line: String) {
    val tone = when (mood) {
        MikuStagePerformance.Mood.UNHINGED, MikuStagePerformance.Mood.HYPED -> Color(0xFF7FE6DE)
        MikuStagePerformance.Mood.DEVASTATED, MikuStagePerformance.Mood.WORRIED -> Color(0xFFFFCF6B)
        else -> Color(0xFFE0F7FA)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x2239C5BB))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text("MIKU", color = Color(0xFF39C5BB), fontSize = 9.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Text(line, color = tone, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HecklerLine(line: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22FF4D5E))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text("SOME GUY", color = Color(0xFFFF6B6B), fontSize = 9.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Text(
            "\"$line\"   (4 clean hits to shut them up)",
            color = Color(0xFFFFC9CE),
            fontSize = 10.5.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The fail state. You lose the run, not your leeks: the punishment is the walk back on stage. */
@Composable
private fun SetOverCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC1A0410))
            .padding(14.dp)
    ) {
        Text("THE ROOM EMPTIED", color = Color(0xFFFF4D5E), fontSize = 14.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text(
            "Peak crowd this set: ${MikuStagePerformance.peakCrowd.toInt()}%. " +
                "You keep every leek you earned. What you lost was the room.",
            color = Color(0xFFE8F4F2), fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "GO BACK ON",
            color = Color(0xFF04161A),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF39C5BB))
                .clickable { MikuStagePerformance.encore() }
                .padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}
