package com.miku.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** System gesture reserves on the M500: the launcher must never react inside these bands. */
private val EDGE_TOP = 24.dp
private val EDGE_BOTTOM = 24.dp
private val EDGE_SIDE = 16.dp
private val TRIGGER = 40.dp

/**
 * Pixel-style home-screen vertical swipes: swipe UP anywhere on the workspace opens the app
 * drawer, swipe DOWN opens the (system) notification shade. Fires once per gesture after
 * [TRIGGER] of clearly-vertical travel, and IGNORES touches that start in the top/bottom/side
 * edge bands, which belong to the MikuOS system gesture layer (shade pull, pill, edge back).
 * Runs in the Initial pass and consumes only when it fires, so taps and long-presses on
 * icons underneath keep working.
 */
fun Modifier.homeVerticalSwipe(
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit
): Modifier = pointerInput(Unit) {
    val top = EDGE_TOP.toPx(); val bottom = EDGE_BOTTOM.toPx(); val side = EDGE_SIDE.toPx(); val trig = TRIGGER.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val p = down.position
        if (p.y < top || p.y > size.height - bottom || p.x < side || p.x > size.width - side) return@awaitEachGesture
        var fired = false
        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (change.isConsumed) break // an inner scrollable/drag claimed it
            val dx = change.position.x - p.x
            val dy = change.position.y - p.y
            if (!fired && abs(dy) > trig && abs(dy) > abs(dx) * 1.4f) {
                fired = true
                change.consume()
                if (dy < 0) onSwipeUp() else onSwipeDown()
                break
            }
        }
    }
}
