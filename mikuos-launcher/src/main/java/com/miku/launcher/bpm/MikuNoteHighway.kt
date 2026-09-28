package com.miku.launcher.bpm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The thing you actually tap against.
 *
 * WHAT WAS MISSING. The game told you how you did AFTER the fact: a judgment word and a deviation
 * bar that moved once per tap. There was nothing to aim AT. Every rhythm game solves this the same
 * way and has since Guitar Hero's calibration screen: notes travel toward a fixed hit line, and you
 * tap when a note reaches it. Your eye leads the note in, so you are anticipating a moment instead
 * of reacting to a sound, which is the difference between guessing and playing.
 *
 * HOW IT IS DRIVEN. Entirely by the real beat clock. `lastPulseEpochMs` is the last beat the
 * detector actually heard; `beatIntervalMs` is the locked tempo. Note n sits at
 * `lastPulse + n * period`, and its position on screen is how far away that time is. Nothing here
 * invents a beat: with no pulse and no lock the highway draws empty and says so, the same way the
 * scoring refuses to judge a tap without one.
 *
 * The calibration offset is applied to the HIT LINE, not to the notes, so what you see and what
 * you are judged against are the same number. Push the offset and the line moves.
 *
 * Animated with `awaitFrame` and float state read only inside the draw lambda, so the highway
 * invalidates its own canvas per frame and never recomposes anything around it.
 */
@Composable
fun MikuNoteHighway(
    lastPulseEpochMs: Long,
    beatIntervalMs: Long,
    calibrationMs: Int,
    windows: MikuRhythmTiming.Windows,
    /** Last judged offset in ms, for the hit spark. 0 with [lastTapMs] == 0 means no tap yet. */
    lastTapMs: Long,
    lastOffsetMs: Int,
    accent: Color,
    accent2: Color = accent,
    /** Height of the lane. Set to the tap node's size when the node sits on the hit line. */
    laneHeight: androidx.compose.ui.unit.Dp = 64.dp,
    /** Where the hit line sits across the lane, 0..1. 0.5 puts it under a centred tap node. */
    hitFraction: Float = 0.74f,
    modifier: Modifier = Modifier,
    /** Drawn ON the hit line, on top of the lane. This is where the tap node goes. */
    content: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null
) {
    // Per-frame clock. Read ONLY in the draw lambda below.
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val sparkAge = remember { mutableFloatStateOf(1f) }
    val lastSeenTap = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            now.longValue = System.currentTimeMillis()
            if (lastSeenTap.longValue != lastTapMs) { lastSeenTap.longValue = lastTapMs; sparkAge.floatValue = 0f }
            if (sparkAge.floatValue < 1f) sparkAge.floatValue = (sparkAge.floatValue + 0.06f).coerceAtMost(1f)
        }
    }

    Box(modifier.fillMaxWidth().height(laneHeight), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(laneHeight)) {
            val w = size.width
            val h = size.height
            // The hit line is wherever the caller put the thing you tap. With the beat node on it,
            // the note arrives UNDER YOUR THUMB: you are not looking at one place and tapping
            // another, which is the whole reason a note highway works.
            val hitX = w * hitFraction
            val period = beatIntervalMs.toFloat()
            val live = lastPulseEpochMs > 0L && beatIntervalMs in 60..3000

            drawLane(h, w, accent, accent2)

            if (!live) {
                // No tempo lock: draw the lane and nothing else. Never a fake note.
                drawHitLine(hitX, h, accent.copy(alpha = 0.35f), 0f)
                return@Canvas
            }

            // How many ms of future the lane shows. Two beats of run-up, clamped so a very slow or
            // very fast track still reads: at 60 BPM two beats is 2s, at 200 it is 600ms.
            val lookaheadMs = (period * 2f).coerceIn(700f, 2200f)
            // Pixels per millisecond, from the hit line back to the right edge.
            val pxPerMs = (w - hitX) / lookaheadMs

            val t = now.longValue - calibrationMs
            // Index of the next beat at or after now.
            val sinceLast = (t - lastPulseEpochMs).toFloat()
            val nextIdx = kotlin.math.ceil(sinceLast / period).toInt()

            // How far through the current beat we are, 0 at the beat and 1 just before the next.
            // Drives the hit line's own pulse, so the target BREATHES on tempo and you can feel
            // the beat from the line alone even before a note reaches it.
            val phase = (sinceLast.mod(period)) / period

            // Windows as widths on the lane, so the player can SEE how much room each tier has.
            drawWindowBands(hitX, h, pxPerMs, windows, accent, accent2)
            drawHitLine(hitX, h, accent, phase)

            // Notes: the one just past the line (so a late tap still has something to point at),
            // and everything inside the lookahead.
            for (i in (nextIdx - 1)..(nextIdx + 8)) {
                val beatAt = lastPulseEpochMs + i.toLong() * beatIntervalMs
                val msAway = (beatAt - t).toFloat()
                if (msAway > lookaheadMs) break
                val x = hitX + msAway * pxPerMs
                if (x < -20f || x > w + 20f) continue
                // A downbeat every fourth note, drawn bigger. The detector does not know the bar
                // line, so this is honestly just "every fourth pulse since the lock", a visual
                // rhythm aid and not a claim about the music's meter.
                val strong = (i.mod(4)) == 0
                // Each note takes its colour from the album, walking between the two accents so a
                // run of notes reads as a gradient rather than a row of identical dots.
                val hue = ((i.mod(8)) / 8f)
                val noteColor = lerpColor(accent, accent2, hue)
                drawNote(x, h, msAway, lookaheadMs, strong, noteColor)
            }

            // Hit spark: where the last judged tap landed relative to the line.
            val age = sparkAge.floatValue
            if (lastTapMs > 0L && age < 1f) {
                val sparkX = hitX + lastOffsetMs * pxPerMs
                val a = (1f - age)
                val good = abs(lastOffsetMs) <= windows.greatMs
                val c = if (good) accent else Color(0xFFFF4D5E)
                drawCircle(c.copy(alpha = 0.55f * a), radius = h * (0.18f + 0.5f * age), center = Offset(sparkX, h / 2f))
                drawLine(c.copy(alpha = a), Offset(sparkX, h * 0.1f), Offset(sparkX, h * 0.9f), strokeWidth = 2f)
                // A second, wider ring on a good hit: the reward has to be visible in peripheral
                // vision, because on a hit your eye is already on the next note.
                if (good) {
                    drawCircle(
                        c.copy(alpha = 0.35f * a), radius = h * (0.3f + 0.9f * age),
                        center = Offset(sparkX, h / 2f), style = Stroke(width = 2f)
                    )
                }
            }
        }
        // The tap node, sitting ON the hit line: the note lands under the thumb that taps it.
        if (content != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(laneHeight)
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        // Centre the node on the hit line rather than on the lane.
                        val x = (constraints.maxWidth * hitFraction).toInt() - p.width / 2
                        val y = (constraints.maxHeight - p.height) / 2
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center,
                content = content
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

private fun DrawScope.drawLane(h: Float, w: Float, accent: Color, accent2: Color) {
    // The lane itself carries the album's two accents, darkened, so the whole strip belongs to
    // whatever is playing rather than being a grey trough with coloured dots on it.
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(
                accent2.copy(alpha = 0.05f),
                accent.copy(alpha = 0.16f),
                accent2.copy(alpha = 0.26f)
            )
        ),
        size = Size(w, h)
    )
    // Rails, tinted.
    drawLine(accent.copy(alpha = 0.35f), Offset(0f, h * 0.10f), Offset(w, h * 0.10f), strokeWidth = 1.5f)
    drawLine(accent2.copy(alpha = 0.35f), Offset(0f, h * 0.90f), Offset(w, h * 0.90f), strokeWidth = 1.5f)
}

/** The hit line: the moment. Everything else on the lane is relative to it. */
private fun DrawScope.drawHitLine(x: Float, h: Float, c: Color, phase: Float) {
    // Brightest AT the beat and decaying through it, so the target pulses on tempo.
    val beat = (1f - phase).coerceIn(0f, 1f).let { it * it }
    drawLine(c.copy(alpha = 0.55f + 0.45f * beat), Offset(x, 0f), Offset(x, h), strokeWidth = 3f + 2f * beat)
    drawCircle(c.copy(alpha = 0.10f + 0.30f * beat), radius = h * (0.30f + 0.22f * beat), center = Offset(x, h / 2f))
    // Catcher bracket, so the line reads as a target rather than a divider.
    val armW = h * (0.16f + 0.06f * beat)
    drawLine(c, Offset(x - armW, h * 0.06f), Offset(x + armW, h * 0.06f), strokeWidth = 2f)
    drawLine(c, Offset(x - armW, h * 0.94f), Offset(x + armW, h * 0.94f), strokeWidth = 2f)
}

/**
 * The real timing windows, drawn to scale either side of the hit line. These are the same numbers
 * the judgment uses, so a window that widens on an easier difficulty tier visibly widens here.
 */
private fun DrawScope.drawWindowBands(
    hitX: Float, h: Float, pxPerMs: Float,
    windows: MikuRhythmTiming.Windows, accent: Color, accent2: Color
) {
    data class Band(val ms: Int, val color: Color)
    // Widest band in the cooler accent, tightening toward the hotter one: the closer to perfect,
    // the hotter the colour, so the gradient itself teaches the scale.
    val bands = listOf(
        Band(windows.okMs, accent2.copy(alpha = 0.07f)),
        Band(windows.goodMs, accent2.copy(alpha = 0.12f)),
        Band(windows.greatMs, accent.copy(alpha = 0.20f)),
        Band(windows.perfectMs, accent.copy(alpha = 0.34f))
    )
    for (b in bands) {
        val halfPx = b.ms * pxPerMs
        if (halfPx <= 0.5f) continue
        drawRect(
            color = b.color,
            topLeft = Offset(hitX - halfPx, h * 0.12f),
            size = Size(halfPx * 2f, h * 0.76f)
        )
    }
}

/**
 * One note, approaching. It grows and brightens as it nears the line so the eye can lead it in,
 * and a note that has passed the line fades out rather than vanishing, which is what tells you you
 * were late.
 */
private fun DrawScope.drawNote(
    x: Float, h: Float, msAway: Float, lookaheadMs: Float,
    strong: Boolean, accent: Color
) {
    // 0 at the far edge, 1 at the line.
    val near = (1f - (msAway / lookaheadMs)).coerceIn(0f, 1f)
    val past = msAway < 0f
    val fade = if (past) (1f + msAway / 260f).coerceIn(0f, 1f) else 1f
    if (fade <= 0f) return

    val baseR = h * (if (strong) 0.26f else 0.18f)
    val r = baseR * (0.55f + 0.45f * near)
    val alpha = (0.25f + 0.75f * near) * fade
    val c = if (strong) accent else accent.copy(red = min(1f, accent.red + 0.25f))

    // Trail, so motion is legible at 30 fps as well as 60.
    if (!past && near > 0.15f) {
        drawLine(
            c.copy(alpha = 0.18f * alpha),
            Offset(x + r * 2.2f, h / 2f), Offset(x, h / 2f),
            strokeWidth = max(1f, r * 0.5f)
        )
    }
    drawCircle(c.copy(alpha = 0.22f * alpha), radius = r * 1.7f, center = Offset(x, h / 2f))
    drawCircle(c.copy(alpha = alpha), radius = r, center = Offset(x, h / 2f))
    drawCircle(
        Color.White.copy(alpha = 0.7f * alpha * near),
        radius = r * 0.45f, center = Offset(x, h / 2f)
    )
    if (strong) {
        drawCircle(
            c.copy(alpha = 0.5f * alpha), radius = r * 1.3f,
            center = Offset(x, h / 2f), style = Stroke(width = 1.5f)
        )
    }
}
