package com.miku.player.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.miku.player.ArtPalette
import com.miku.player.PlayerPreferences

/**
 * Process-wide flags for the Now Playing look (backed by PlayerPreferences, loaded once). Kept as
 * Compose state so a toggle flipped in Settings restyles an already-open Now Playing screen live.
 */
object NowPlayingLook {
    var dynamicColor by mutableStateOf(true)
        private set
    var wavyBar by mutableStateOf(true)
        private set
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        dynamicColor = PlayerPreferences.loadDynamicColor(ctx)
        wavyBar = PlayerPreferences.loadWavyBar(ctx)
    }

    fun setDynamicColor(ctx: Context, on: Boolean) { dynamicColor = on; PlayerPreferences.saveDynamicColor(ctx, on) }
    fun setWavyBar(ctx: Context, on: Boolean) { wavyBar = on; PlayerPreferences.saveWavyBar(ctx, on) }
}

/**
 * The palette every Now Playing surface (background gradient, wavy bar, transport deck, chips)
 * reads: the art-extracted palette when dynamic color is on, the Miku identity palette otherwise,
 * and in both cases ANIMATED — the old `remember(art)` palette hard-cut to the new colors the frame
 * the next track's art decoded, which read as a flash. 700 ms color glide instead.
 */
@Composable
fun rememberAnimatedPalette(target: ArtPalette, dynamic: Boolean, durationMs: Int = 700): ArtPalette {
    val t = if (dynamic) target else ArtPalette()
    val c1 by animateColorAsState(t.color1, tween(durationMs), label = "npC1")
    val c2 by animateColorAsState(t.color2, tween(durationMs + 200), label = "npC2")
    val c3 by animateColorAsState(t.color3, tween(durationMs), label = "npC3")
    return ArtPalette(c1, c2, c3)
}

/** Text/glyph color that stays readable on top of [accent] (dark ink on bright faces, white on deep ones). */
fun onAccentColor(accent: Color): Color = if (accent.luminance() > 0.45f) Color(0xFF07201F) else Color.White
