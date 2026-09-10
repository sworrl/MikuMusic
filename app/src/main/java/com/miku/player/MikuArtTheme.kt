package com.miku.player

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-wide "color of what's playing". One process-global palette extracted from the CURRENT
 * track's art (not whatever happened to be in the memory cache at first composition — that was
 * why the mini bar so often stayed default teal while the art was clearly orange). Every chrome
 * surface reads [colors] and animates toward the new palette on track change, so the whole app
 * shifts with the album instead of only the Now Playing card.
 *
 * Fallback is always the Miku teal identity palette (no art → nothing changes visibly).
 */
object MikuArtTheme {
    var palette by mutableStateOf(ArtPalette())
        private set
    var trackId by mutableStateOf(-1L)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var appCtx: Context? = null

    fun update(ctx: Context, track: Track?) {
        appCtx = ctx.applicationContext
        if (track == null) { trackId = -1L; palette = ArtPalette(); return }
        if (track.id == trackId) return
        trackId = track.id
        job?.cancel()
        val app = ctx.applicationContext
        job = scope.launch {
            val bm = AlbumArtCache.get(track.id) ?: loadArtThumb(app, track.id, track.path)
            val p = extractArtPalette(bm)
            withContext(Dispatchers.Main) { if (trackId == track.id) { palette = p; MikuAccentPublisher.onPalette(app, p) } }
        }
    }

    /** Push a palette computed elsewhere (Now Playing already has the hi-res art decoded, the HUD
     *  publisher already has the thumb). Also what lets the OS accents update when art loads late. */
    fun push(id: Long, p: ArtPalette, ctx: Context? = null) {
        val changed = id != trackId || p != palette
        trackId = id; palette = p
        val app = (ctx ?: appCtx)?.applicationContext
        if (changed && app != null) MikuAccentPublisher.onPalette(app, p)
    }

    /** Resolve + publish the palette for a track id even when no UI is up (background playback,
     *  PLAY_RANDOM from the launcher). Cheap: in-memory index lookup + the cached thumb. */
    fun updateForTrackId(ctx: Context, trackId: Long) {
        if (MikuDbg.off(ctx, "arttheme")) return
        val app = ctx.applicationContext
        if (trackId == this.trackId) return
        if (!MikuPowerGovernor.allowBackgroundWork) return   // deferred until the screen is back
        scope.launch {
            val t = FastLibraryStore.loadSync(app)?.firstOrNull { it.id == trackId } ?: return@launch
            withContext(Dispatchers.Main) { update(app, t) }
        }
    }

    /** Chrome-ready, contrast-safe derivations of the raw palette. */
    class Colors(
        /** Vibrant accent (tabs, pills, highlights). Lifted if the art's vibrant is too dark. */
        val accent: Color,
        /** Secondary accent (tape icon, secondary highlights). */
        val accent2: Color,
        /** Deep page backdrop tinted by the art's deep tone. */
        val ground: Color,
        /** Slightly raised surface tint (cards, header glass). */
        val surface: Color,
        /** Text color guaranteed readable on [accent]. */
        val onAccent: Color,
    )

    private fun lift(c: Color, minLum: Float): Color {
        val l = c.luminance()
        if (l >= minLum) return c
        // Blend toward white until it clears the floor — keeps the hue, fixes legibility on dark ground.
        val t = ((minLum - l) / (1f - l)).coerceIn(0f, 0.85f)
        return Color(
            red = c.red + (1f - c.red) * t,
            green = c.green + (1f - c.green) * t,
            blue = c.blue + (1f - c.blue) * t,
            alpha = 1f
        )
    }

    private fun mix(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f
    )

    fun derive(p: ArtPalette): Colors {
        val accent = lift(p.color1, 0.22f)
        val accent2 = lift(p.color3, 0.20f)
        // Ground: Miku's deep teal-black pulled ~35% toward the art's deep tone (kept dark).
        val deep = p.color2
        val deepDark = Color(deep.red * 0.28f, deep.green * 0.28f, deep.blue * 0.28f)
        val ground = mix(Ground, deepDark, 0.55f)
        val surface = mix(Surface1, Color(deep.red * 0.42f, deep.green * 0.42f, deep.blue * 0.42f), 0.5f)
        val onAccent = if (accent.luminance() > 0.45f) Color(0xFF07201F) else Color.White
        return Colors(accent, accent2, ground, surface, onAccent)
    }

    /** Animated, composition-aware colors — call once per screen/section and read fields. */
    @Composable
    fun colors(durationMs: Int = 650): Colors {
        val target = derive(palette)
        val accent by animateColorAsState(target.accent, tween(durationMs), label = "artAccent")
        val accent2 by animateColorAsState(target.accent2, tween(durationMs), label = "artAccent2")
        val ground by animateColorAsState(target.ground, tween(durationMs + 250), label = "artGround")
        val surface by animateColorAsState(target.surface, tween(durationMs + 250), label = "artSurface")
        val onAccent by animateColorAsState(target.onAccent, tween(durationMs), label = "artOnAccent")
        return Colors(accent, accent2, ground, surface, onAccent)
    }
}
