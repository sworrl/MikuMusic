package com.miku.launcher.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.miku.launcher.MikuCyan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Let the album playing colors extend to the OS a little as accents."
 *
 * Miku Music publishes the current album art palette as Settings.Global
 *   miku_np_accent  / miku_np_accent2  (ARGB ints as decimal strings, "0" = none)
 *   miku_np_accent_ts                 (change stamp)
 * This observer turns them into a StateFlow; [rememberNpAccent] animates them (400 ms) and
 * pre-blends them into the Miku teal so callers never over-tint: [NpAccent.subtle] is a
 * 25 % mix (seams, chips, glows), [NpAccent.full] is the raw accent (now-playing / BPM patches).
 * With no track playing everything falls back to pure Miku teal.
 */
@Immutable
data class NpAccent(
    val active: Boolean,
    val full: Color,      // raw album accent (teal when inactive)
    val second: Color,    // secondary album accent (pink-ish teal when inactive)
    val subtle: Color,    // 25 % accent into Miku teal — for seams / chips / borders
    val scrim: Color      // 8 % alpha accent for the wallpaper tint
)

object MikuNowPlayingAccent {
    private const val KEY_A = "miku_np_accent"
    private const val KEY_B = "miku_np_accent2"
    private const val KEY_TS = "miku_np_accent_ts"

    private val _colors = MutableStateFlow<Pair<Int, Int>>(0 to 0)
    val colors: StateFlow<Pair<Int, Int>> = _colors.asStateFlow()
    @Volatile private var attached = false

    fun attach(ctx: Context) {
        if (attached) return
        attached = true
        val app = ctx.applicationContext
        fun read() {
            val a = readInt(app, KEY_A); val b = readInt(app, KEY_B)
            _colors.value = a to b
        }
        read()
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = read()
        }
        try {
            val cr = app.contentResolver
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_A), false, obs)
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_B), false, obs)
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_TS), false, obs)
        } catch (_: Throwable) {}
    }

    private fun readInt(ctx: Context, key: String): Int = try {
        Settings.Global.getString(ctx.contentResolver, key)?.trim()?.toLongOrNull()?.toInt() ?: 0
    } catch (_: Throwable) { 0 }

    /** Linear mix of [accent] into [base] by [amount] (0 = base, 1 = accent). */
    fun blend(base: Color, accent: Color, amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        return Color(
            red = base.red + (accent.red - base.red) * t,
            green = base.green + (accent.green - base.green) * t,
            blue = base.blue + (accent.blue - base.blue) * t,
            alpha = base.alpha
        )
    }

    /** WCAG contrast ratio between two opaque-ish colours. */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f; val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /**
     * Text-safe variant: only tints [textBase] toward [accent] as far as the result still has
     * ≥ 4.5:1 contrast against [background]; otherwise returns [textBase] unchanged.
     */
    fun tintText(textBase: Color, accent: Color, background: Color, amount: Float = 0.3f): Color {
        val tinted = blend(textBase, accent, amount)
        return if (contrast(tinted, background) >= 4.5f) tinted else textBase
    }
}

private val NP_TWEEN = tween<Color>(400)

@Composable
fun rememberNpAccent(): NpAccent {
    val ctx = LocalContext.current
    MikuNowPlayingAccent.attach(ctx)
    val pair by MikuNowPlayingAccent.colors.collectAsState()
    val active = pair.first != 0
    val rawA = if (active) Color(pair.first).copy(alpha = 1f) else MikuCyan
    val rawB = if (pair.second != 0) Color(pair.second).copy(alpha = 1f) else if (active) rawA else Color(0xFF7FE6DE)
    val full by animateColorAsState(rawA, NP_TWEEN, label = "npAccentFull")
    val second by animateColorAsState(rawB, NP_TWEEN, label = "npAccentSecond")
    val subtle by animateColorAsState(if (active) MikuNowPlayingAccent.blend(MikuCyan, rawA, 0.25f) else MikuCyan, NP_TWEEN, label = "npAccentSubtle")
    val scrim by animateColorAsState(if (active) rawA.copy(alpha = 0.08f) else Color.Transparent, NP_TWEEN, label = "npAccentScrim")
    return NpAccent(active = active, full = full, second = second, subtle = subtle, scrim = scrim)
}

/** Non-animated read for places that can't recompose per frame (drawBehind lambdas etc.). */
@Composable
fun npAccentState(): State<Pair<Int, Int>> {
    val ctx = LocalContext.current
    MikuNowPlayingAccent.attach(ctx)
    return MikuNowPlayingAccent.colors.collectAsState()
}
