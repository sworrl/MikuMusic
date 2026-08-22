package com.caf.fmradio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Cyber Hatsune Miku Palette Tokens
val CyberDarkBg = Color(0xFF040D12)
val CyberGlassCard = Color(0xDD0A1E26)
val CyberGlassBorder = Color(0x3300E5FF)
val MikuCyan = Color(0xFF00E5FF)
val MikuNeonPink = Color(0xFFFF4081)
val MikuPurple = Color(0xFFB388FF)
val MikuTextPrimary = Color(0xFFE0F7FA)
val MikuTextSecondary = Color(0xFF80DEEA)
fun cyber24BitColorShift(phaseDeg: Float, saturation: Float = 0.88f, brightness: Float = 1.0f, alpha: Float = 1.0f): Color {
    val normHue = (phaseDeg % 360f + 360f) % 360f
    val hsv = floatArrayOf(normHue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    val argb = android.graphics.Color.HSVToColor((alpha.coerceIn(0f, 1f) * 255).toInt(), hsv)
    return Color(argb)
}

// Extra Miku palette colors used by the FM UI (ported from the app's Model.kt).
val MikuTeal = androidx.compose.ui.graphics.Color(0xFF39C5BB)
val MikuTealBright = androidx.compose.ui.graphics.Color(0xFF7FE6DE)
val MikuPink = androidx.compose.ui.graphics.Color(0xFFFF5FA2)
