package com.miku.launcher

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Themable top status bar. The top bar used to be a fixed teal→pink "quilt" wash; this makes its
 * BACKGROUND user-selectable — a gallery of gradient presets plus a photo option — while the live
 * status content (clock, signal, Wi-Fi, battery, badges) is drawn ON TOP unchanged. Every theme
 * lays a dark bottom-weighted scrim under the content so white glyphs stay legible over any
 * gradient or photo. Selection persists in the launcher prefs and cycles on a top-bar long-press.
 */
enum class MikuTopBarTheme(val displayName: String) {
    MIKU("Miku"),
    AURORA("Aurora"),
    SUNSET("Sunset"),
    OCEAN("Ocean"),
    MIDNIGHT("Midnight"),
    PHOTO("Photo"),
    MINIMAL("Minimal");

    companion object {
        private const val KEY = "top_bar_theme"

        fun load(ctx: Context): MikuTopBarTheme {
            val name = ctx.getSharedPreferences("miku_launcher_prefs", Context.MODE_PRIVATE)
                .getString(KEY, MIKU.name) ?: MIKU.name
            return runCatching { valueOf(name) }.getOrDefault(MIKU)
        }

        fun save(ctx: Context, theme: MikuTopBarTheme) {
            ctx.getSharedPreferences("miku_launcher_prefs", Context.MODE_PRIVATE)
                .edit().putString(KEY, theme.name).apply()
        }
    }
}

/**
 * Next theme in the ring, SKIPPING anything still locked behind the BPM game.
 *
 * AURORA and MIDNIGHT are earned cosmetics (see MikuUnlocks). The cycle is a blind long-press with
 * no picker, so a locked theme in the ring would just hand it over. Falls back to the plain ring if
 * every remaining theme is locked, which cannot happen while MIKU is unconditional.
 */
fun MikuTopBarTheme.next(ctx: Context): MikuTopBarTheme {
    val locked = mapOf(
        MikuTopBarTheme.AURORA to com.miku.launcher.bpm.MikuUnlocks.OS_TOPBAR_AURORA,
        MikuTopBarTheme.MIDNIGHT to com.miku.launcher.bpm.MikuUnlocks.OS_TOPBAR_MIDNIGHT
    ).filterValues { !com.miku.launcher.bpm.MikuUnlocks.isUnlocked(ctx, it) }.keys
    val n = MikuTopBarTheme.entries.size
    for (step in 1..n) {
        val cand = MikuTopBarTheme.entries[(ordinal + step) % n]
        if (cand !in locked) return cand
    }
    return MikuTopBarTheme.entries[(ordinal + 1) % n]
}

/** The gradient stops for the gradient-based themes (PHOTO/MINIMAL handled separately). */
private fun MikuTopBarTheme.gradientColors(): List<Color> = when (this) {
    MikuTopBarTheme.MIKU     -> listOf(Color(0xFF0EE7DD), Color(0xFF1B8FB0), Color(0xFFFF4FA3))
    MikuTopBarTheme.AURORA   -> listOf(com.miku.launcher.ui.MikuIdentity.Leek, Color(0xFF00E5FF), Color(0xFFB388FF))
    MikuTopBarTheme.SUNSET   -> listOf(Color(0xFFFFB74D), Color(0xFFFF5C8A), Color(0xFF7C4DFF))
    MikuTopBarTheme.OCEAN    -> listOf(Color(0xFF0277BD), Color(0xFF00B8D4), Color(0xFF1DE9B6))
    MikuTopBarTheme.MIDNIGHT -> listOf(Color(0xFF0A1024), Color(0xFF1A2350), Color(0xFF3B2E63))
    MikuTopBarTheme.MINIMAL  -> listOf(Color(0xFF0B0F14), Color(0xFF0B0F14))
    MikuTopBarTheme.PHOTO    -> listOf(Color(0xFF0B0F14), Color(0xFF0B0F14)) // scrim only; image drawn over
}

/**
 * Themable background for the top-bar container. Draw this on the Box/Column that holds the status
 * rows. Rounds the corners, paints the theme, and lays a legibility scrim. PHOTO renders the
 * launcher wallpaper cropped to the bar; all others are gradients.
 */
@Composable
fun MikuTopBarBackground(theme: MikuTopBarTheme, modifier: Modifier = Modifier, cornerDp: Int = 14) {
    val shape = RoundedCornerShape(cornerDp.dp)
    Box(modifier.clip(shape)) {
        if (theme == MikuTopBarTheme.PHOTO) {
            Image(
                painter = painterResource(R.drawable.miku_bg_wide),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val cols = theme.gradientColors()
            // horizontal wash, matching the reference gallery's left→right themed strips
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.horizontalGradient(cols)) })
        }
        // Bottom-weighted dark scrim so white status glyphs stay legible over any theme/photo.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0x22000000), Color(0x55000000), Color(0x77000000))
                    )
                )
            }
        )
    }
}
