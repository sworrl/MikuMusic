package com.miku.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import android.graphics.RuntimeShader
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Layout languages amalgamated from ~20 real branded/artist cassettes:
 * CLEAR   — clear-shell Memorex/GNR-transparent: mechanism on show.
 * STUDIO  — blank-tape engineering (TDK SA × Maxell XLII): paper strip, grade mark, bias line.
 * MAJOR84 — 80s major-label pressing (Epic/Warner/EMI): text-only strip, rules, logo block.
 * NEON    — black shell + one screaming ink (Wu-Tang/NWA logo inversion).
 * MERCH   — modern one-colour merch object (Swift/Eilish): tone-on-tone, hairline hub rings.
 */
private enum class TapeLayout { CLEAR, STUDIO, MAJOR84, NEON, MERCH }

/**
 * Bespoke physical plastic material types:
 * CLEAR_POLYCARBONATE — crystal acrylic with specular gleam and optical clarity (MEMOREX, NAKAMICHI).
 * SMOKED_ACRYLIC      — smoke-tinted dark transparent with subtle frosted diffusion (MIKU STUDIO, TDK SA-X).
 * MATTE_COMPOSITE     — dense micro-pebble stippled ABS composite with light absorption (MAXELL XLII-S, SONY METAL, NEON WU, NEON MIKU).
 * SATIN_POLYSTYRENE   — eggshell translucent molded styrene with subsurface light diffusion (MAJOR '84, BASF CHROME, DENON DX, EVERMORE, PASTEL MIKU, SAKURA).
 * METALLIC_FLAKE      — pearlescent metallic luster with micro-flake shimmer (GOLD, AMPEX 456).
 * PHOSPHOR_GLOW       — subsurface radioactive phosphor luminescence (GLOWWORM, VAPORWAVE).
 */
private enum class PlasticType {
    CLEAR_POLYCARBONATE,
    SMOKED_ACRYLIC,
    MATTE_COMPOSITE,
    SATIN_POLYSTYRENE,
    METALLIC_FLAKE,
    PHOSPHOR_GLOW
}

/**
 * Bespoke brand typography and printing texture styles:
 * DEBOSSED_ENAMEL    — Recessed into plastic with glossy colored enamel infill
 * SCREENPRINT_NEON   — Raised tactile silk-screened ink with sharp pad-print edges
 * HOT_STAMP_GOLD     — Reflective metallic 24K gold foil hot-stamped with micro-glint
 * HOT_STAMP_CHROME   — Mirror chrome foil stamp with anisotropic metallic luster
 * DOT_MATRIX_IMPRINT — Japanese industrial dot matrix ink jet printing
 * DISCO_INLINE_NEON  — Multi-line glowing neon sign typography
 * JAPANESE_KAWAII    — Bubbly pastel Japanese pop offset print
 * MINIMAL_LASER_ETCH — Precision frosted laser etching directly into acrylic
 */
private enum class BrandTexture {
    DEBOSSED_ENAMEL,
    SCREENPRINT_NEON,
    OFFSET_PRINT_INK,
    HOT_STAMP_GOLD,
    HOT_STAMP_CHROME,
    DOT_MATRIX_IMPRINT,
    DISCO_INLINE_NEON,
    JAPANESE_KAWAII,
    MINIMAL_LASER_ETCH
}

/** Cassette theme: shell plastic + engraving + hub palette + branded layout language + bespoke material texture + brand typography. */
private data class TapeTheme(
    val name: String,
    val shellHi: Color, val shellLo: Color, val engrave: Color, val bevelHi: Color, val bevelLo: Color,
    val hubHi: Color, val hubLo: Color, val holes: Color, val ink: Color,
    val layout: TapeLayout = TapeLayout.CLEAR,
    val plasticType: PlasticType = PlasticType.CLEAR_POLYCARBONATE,
    val brandText: String = "MIKU MUSIC",
    val brandSub: String = "STEREO CASSETTE TAPE",
    val brandFont: FontFamily = AudiowideFont,
    val brandColor: Color = Color(0xFF39C5BB),
    val brandSubColor: Color = Color(0xFF9AECE6),
    val brandTexture: BrandTexture = BrandTexture.SCREENPRINT_NEON,
    val accent: Color = Color(0xFF39C5BB),
    val wallAlpha: Float = 0.88f, val cavityAlpha: Float = 0.30f,
    val welded: Boolean = false   // premium shells are screwed together; budget shells are melt-welded
)
private val TAPE_THEMES = listOf(
    TapeTheme("MIKU STUDIO",
        shellHi = Color(0xFF262633), shellLo = Color(0xFF14141B), engrave = Color(0x5539C5BB),
        bevelHi = Color(0xFF9AECE6), bevelLo = Color(0xFF0B0B10),
        hubHi = Color(0xFFF5FFFE), hubLo = Color(0xFF2FA39B), holes = Color(0xFF0F3D39), ink = Color(0xFFE8FBF8),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.SMOKED_ACRYLIC,
        brandText = "MIKU STUDIO 90", brandSub = "TYPE II HIGH BIAS (CrO2) · 70µs EQ",
        brandFont = AudiowideFont, brandColor = Color(0xFF39C5BB), brandSubColor = Color(0xFF9AECE6), brandTexture = BrandTexture.DEBOSSED_ENAMEL,
        accent = Color(0xFF39C5BB), wallAlpha = 0.62f, cavityAlpha = 0.22f),
    TapeTheme("MEMOREX",
        shellHi = Color(0xFF2A4A4E), shellLo = Color(0xFF0E2226), engrave = Color(0x5539C5BB),
        bevelHi = Color(0xFF7FE6DE), bevelLo = Color(0xFF08181A),
        hubHi = Color(0xFFFFDD3A), hubLo = Color(0xFFB38F00), holes = Color(0xFF3A2E00), ink = Color(0xFFE8FBF8),
        plasticType = PlasticType.CLEAR_POLYCARBONATE,
        brandText = "MIKU dBS-90", brandSub = "HIGH OUTPUT · LOW NOISE · WIDE DYNAMIC",
        brandFont = RighteousFont, brandColor = Color(0xFFFFD200), brandSubColor = Color(0xFFE8FBF8), brandTexture = BrandTexture.SCREENPRINT_NEON),
    TapeTheme("GOLD",
        shellHi = Color(0xFFE8C878), shellLo = Color(0xFF8A6A21), engrave = Color(0x55704E12),
        bevelHi = Color(0xFFFCEFB8), bevelLo = Color(0xFF5A410E),
        hubHi = Color(0xFFF3ECD6), hubLo = Color(0xFF9A8A5E), holes = Color(0xFF241B08), ink = Color(0xFFFDF4D8),
        plasticType = PlasticType.METALLIC_FLAKE,
        brandText = "MIKU 24K GOLD MASTER", brandSub = "AUDIOPHILE REFERENCE · SPECIAL EDITION",
        brandFont = AudiowideFont, brandColor = Color(0xFF2C1A04), brandSubColor = Color(0xFF4E320A), brandTexture = BrandTexture.HOT_STAMP_GOLD),
    TapeTheme("MAJOR '84",
        shellHi = Color(0xFFF2E9D7), shellLo = Color(0xFFD9C9A8), engrave = Color(0x334A3520),
        bevelHi = Color(0xFFFFF9EC), bevelLo = Color(0xFF8A7A56),
        hubHi = Color(0xFFFFFFFF), hubLo = Color(0xFFB9AB8C), holes = Color(0xFF3A2F1C), ink = Color(0xFF2B2B2B),
        layout = TapeLayout.MAJOR84, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "MIKU RECORDS 1984", brandSub = "ORIGINAL STEREO CASSETTE · DOLBY SYSTEM",
        brandFont = RighteousFont, brandColor = Color(0xFF23160C), brandSubColor = Color(0xFF4A3520), brandTexture = BrandTexture.OFFSET_PRINT_INK,
        accent = Color(0xFFC9A227), wallAlpha = 0.96f, cavityAlpha = 0.55f),
    TapeTheme("NEON WU",
        shellHi = Color(0xFF1B1B20), shellLo = Color(0xFF0E0E12), engrave = Color(0x44FFD500),
        bevelHi = Color(0xFFFFE566), bevelLo = Color(0xFF060608),
        hubHi = Color(0xFFFFD500), hubLo = Color(0xFFB89A00), holes = Color(0xFF3A3000), ink = Color(0xFFFFD500),
        layout = TapeLayout.NEON, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "MIKU 36 CHAMBERS", brandSub = "ENTER THE SOUND · HI-ENERGY FLUX 120µs",
        brandFont = OrbitronFont, brandColor = Color(0xFFFFD500), brandSubColor = Color(0xFFFFF066), brandTexture = BrandTexture.SCREENPRINT_NEON,
        accent = Color(0xFFFFD500), wallAlpha = 0.94f, cavityAlpha = 0.48f, welded = true),
    TapeTheme("NEON MIKU",
        shellHi = Color(0xFF1B1B20), shellLo = Color(0xFF0E0E12), engrave = Color(0x4439C5BB),
        bevelHi = Color(0xFF9AECE6), bevelLo = Color(0xFF060608),
        hubHi = Color(0xFF39C5BB), hubLo = Color(0xFF1F8079), holes = Color(0xFF0A2C29), ink = Color(0xFF39C5BB),
        layout = TapeLayout.NEON, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "MIKU CYBER DECK", brandSub = "VOCALOID01 · DIGITAL ACCELERATION",
        brandFont = OrbitronFont, brandColor = Color(0xFF39C5BB), brandSubColor = Color(0xFF8CF0E8), brandTexture = BrandTexture.SCREENPRINT_NEON,
        accent = Color(0xFF39C5BB), wallAlpha = 0.94f, cavityAlpha = 0.48f, welded = true),
    TapeTheme("EVERMORE",
        shellHi = Color(0xFFB3ADA4), shellLo = Color(0xFF8E887F), engrave = Color(0x333E3A35),
        bevelHi = Color(0xFFD8D3CA), bevelLo = Color(0xFF57524B),
        hubHi = Color(0xFF9C968D), hubLo = Color(0xFF6E6961), holes = Color(0xFF3E3A35), ink = Color(0xFF3E3A35),
        layout = TapeLayout.MERCH, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "miku music · tape", brandSub = "intimate acoustic series · studio b",
        brandFont = Baloo2Font, brandColor = Color(0xFF181512), brandSubColor = Color(0xFF35302B), brandTexture = BrandTexture.OFFSET_PRINT_INK,
        accent = Color(0xFF3E3A35), wallAlpha = 0.95f, cavityAlpha = 0.55f, welded = true),
    TapeTheme("PASTEL MIKU",
        shellHi = Color(0xFFBDEEE8), shellLo = Color(0xFF8FD8D0), engrave = Color(0x330F6E66),
        bevelHi = Color(0xFFE4FAF7), bevelLo = Color(0xFF4FA69D),
        hubHi = Color(0xFF9ADFD8), hubLo = Color(0xFF63B8AF), holes = Color(0xFF0F6E66), ink = Color(0xFF0F6E66),
        layout = TapeLayout.MERCH, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "初音ミク · MIKU POP", brandSub = "LO-FI CHILL BIAS · SWEET COMPACT CASSETTE",
        brandFont = MochiyPopFont, brandColor = Color(0xFF083B36), brandSubColor = Color(0xFF0E5E56), brandTexture = BrandTexture.JAPANESE_KAWAII,
        accent = Color(0xFF0F6E66), wallAlpha = 0.93f, cavityAlpha = 0.50f, welded = true),

    // ── Deeply real: legendary decks of the golden age ──
    TapeTheme("TDK SA-X",   // the Type II reference: smoke-black precision shell, red grade slash
        shellHi = Color(0xFF2E2E34), shellLo = Color(0xFF17171B), engrave = Color(0x44D0D0D6),
        bevelHi = Color(0xFFB8B8C2), bevelLo = Color(0xFF0B0B0E),
        hubHi = Color(0xFFE8E8EC), hubLo = Color(0xFF8A8A94), holes = Color(0xFF1E1E24), ink = Color(0xFFE9E9EE),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.SMOKED_ACRYLIC,
        brandText = "MIKU SA-X 90", brandSub = "HIGH BIAS 70µs EQ · SUPER AVILYN DUAL COATING",
        brandFont = OrbitronFont, brandColor = Color(0xFFF2F2F5), brandSubColor = Color(0xFFB8B8C6), brandTexture = BrandTexture.SCREENPRINT_NEON,
        accent = Color(0xFFD62828), wallAlpha = 0.58f, cavityAlpha = 0.24f),
    TapeTheme("MAXELL XLII-S",   // black + gold, the premium epitaph
        shellHi = Color(0xFF232320), shellLo = Color(0xFF121210), engrave = Color(0x55C9A227),
        bevelHi = Color(0xFFE9CD7A), bevelLo = Color(0xFF0A0A08),
        hubHi = Color(0xFF2A2A26), hubLo = Color(0xFF171714), holes = Color(0xFFC9A227), ink = Color(0xFFEFD98F),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "MIKU XLII-S 90", brandSub = "BLACK MAGNETITE · HIGH RESONANCE-DAMPING MECHANISM",
        brandFont = AudiowideFont, brandColor = Color(0xFFFFD866), brandSubColor = Color(0xFFD8B84E), brandTexture = BrandTexture.HOT_STAMP_GOLD,
        accent = Color(0xFFC9A227), wallAlpha = 0.92f, cavityAlpha = 0.45f),
    TapeTheme("BASF CHROME",   // white shell, green chevron, CrO2 pedigree
        shellHi = Color(0xFFF4F4EF), shellLo = Color(0xFFD8D8CF), engrave = Color(0x33006B44),
        bevelHi = Color(0xFFFFFFFA), bevelLo = Color(0xFF8F8F85),
        hubHi = Color(0xFFFFFFFF), hubLo = Color(0xFFB9B9AF), holes = Color(0xFF00552F), ink = Color(0xFF00552F),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "MIKU CR-E II 90", brandSub = "CHROMDIOXID EXTRA II · HIGH OUTPUT / IEC II",
        brandFont = RighteousFont, brandColor = Color(0xFF00452A), brandSubColor = Color(0xFF00633C), brandTexture = BrandTexture.OFFSET_PRINT_INK,
        accent = Color(0xFF00814F), wallAlpha = 0.96f, cavityAlpha = 0.55f),
    TapeTheme("SONY METAL",   // gunmetal Type IV, orange wedge
        shellHi = Color(0xFF4A4E54), shellLo = Color(0xFF23262B), engrave = Color(0x44FF6A00),
        bevelHi = Color(0xFFB9BFC7), bevelLo = Color(0xFF14161A),
        hubHi = Color(0xFF6A7078), hubLo = Color(0xFF3A3E44), holes = Color(0xFF16181C), ink = Color(0xFFE8ECF1),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "MIKU METAL MASTER 90", brandSub = "CERAMIC GUIDE / TYPE IV (METAL) POSITION 70µs",
        brandFont = OrbitronFont, brandColor = Color(0xFFFF6600), brandSubColor = Color(0xFFFFA866), brandTexture = BrandTexture.HOT_STAMP_CHROME,
        accent = Color(0xFFFF6A00), wallAlpha = 0.93f, cavityAlpha = 0.42f),
    TapeTheme("DENON DX",   // white shell, blue bands, red tick
        shellHi = Color(0xFFF2F3F5), shellLo = Color(0xFFD4D8DE), engrave = Color(0x331F5FBF),
        bevelHi = Color(0xFFFFFFFF), bevelLo = Color(0xFF8B919B),
        hubHi = Color(0xFFFFFFFF), hubLo = Color(0xFFAAB2BD), holes = Color(0xFF16407F), ink = Color(0xFF1F5FBF),
        layout = TapeLayout.MAJOR84, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "MIKU DX-7 90", brandSub = "DYNAMIC ACOUSTIC CASSETTE · HIGH FIDELITY",
        brandFont = RighteousFont, brandColor = Color(0xFF0B2F6E), brandSubColor = Color(0xFF1A4B9E), brandTexture = BrandTexture.OFFSET_PRINT_INK,
        accent = Color(0xFFCC2222), wallAlpha = 0.96f, cavityAlpha = 0.55f),
    TapeTheme("AMPEX 456",   // Grand Master studio-reel warm grey + gold
        shellHi = Color(0xFF8A8378), shellLo = Color(0xFF5C574E), engrave = Color(0x40332E26),
        bevelHi = Color(0xFFC9C2B4), bevelLo = Color(0xFF2E2B25),
        hubHi = Color(0xFFB8B0A0), hubLo = Color(0xFF7A7365), holes = Color(0xFF2A261F), ink = Color(0xFFEFE6D2),
        layout = TapeLayout.MAJOR84, plasticType = PlasticType.METALLIC_FLAKE,
        brandText = "MIKU 456 GRAND MASTER", brandSub = "PROFESSIONAL STUDIO MASTERING TAPE · HIGH COERCIVITY",
        brandFont = AudiowideFont, brandColor = Color(0xFF1C1712), brandSubColor = Color(0xFF3D3328), brandTexture = BrandTexture.HOT_STAMP_GOLD,
        accent = Color(0xFFB98A2F), wallAlpha = 0.95f, cavityAlpha = 0.52f),
    TapeTheme("NAKAMICHI",   // audiophile smoke-clear, everything on show
        shellHi = Color(0xFF3A3F42), shellLo = Color(0xFF1C1F21), engrave = Color(0x44AEB6BA),
        bevelHi = Color(0xFFCED6DA), bevelLo = Color(0xFF0E1011),
        hubHi = Color(0xFFDDE3E6), hubLo = Color(0xFF878E92), holes = Color(0xFF24282A), ink = Color(0xFFEFF4F6),
        layout = TapeLayout.CLEAR, plasticType = PlasticType.CLEAR_POLYCARBONATE,
        brandText = "MIKU ZX REFERENCE", brandSub = "DISCRETE 3-HEAD PRECISION CALIBRATION · CRYSTALLOY",
        brandFont = OrbitronFont, brandColor = Color(0xFFE8ECEF), brandSubColor = Color(0xFF90A4AE), brandTexture = BrandTexture.MINIMAL_LASER_ETCH,
        accent = Color(0xFFB0B8BC), wallAlpha = 0.42f, cavityAlpha = 0.16f),
    // ── For fun ──
    TapeTheme("SAKURA",   // cherry-blossom merch object
        shellHi = Color(0xFFF9D5E0), shellLo = Color(0xFFEFA9C2), engrave = Color(0x338E3557),
        bevelHi = Color(0xFFFFF0F5), bevelLo = Color(0xFFB86787),
        hubHi = Color(0xFFF3BCCE), hubLo = Color(0xFFCE8AA5), holes = Color(0xFF8E3557), ink = Color(0xFF8E3557),
        layout = TapeLayout.MERCH, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "初音ミク · 桜音 SAKURA", brandSub = "SPRING HARMONIC EDITION · 48kHz ANALOG MASTER",
        brandFont = MochiyPopFont, brandColor = Color(0xFF5E122E), brandSubColor = Color(0xFF8C2048), brandTexture = BrandTexture.JAPANESE_KAWAII,
        accent = Color(0xFFD1477E), wallAlpha = 0.93f, cavityAlpha = 0.48f, welded = true),
    TapeTheme("VAPORWAVE",   // magenta ink on black, mall-at-midnight
        shellHi = Color(0xFF201826), shellLo = Color(0xFF100C14), engrave = Color(0x44FF3FD8),
        bevelHi = Color(0xFFFF9BE8), bevelLo = Color(0xFF080609),
        hubHi = Color(0xFFFF3FD8), hubLo = Color(0xFFA3238B), holes = Color(0xFF32082A), ink = Color(0xFFFF3FD8),
        layout = TapeLayout.NEON, plasticType = PlasticType.PHOSPHOR_GLOW,
        brandText = "ＭＩＫＵ  ＭＵＳＩＣ", brandSub = "ＡＥＳＴＨＥＴＩＣ  ＮＩＧＨＴ  ＣＡＳＳＥＴＴＥ  ８０ｓ",
        brandFont = MonotonFont, brandColor = Color(0xFFFF3FD8), brandSubColor = Color(0xFFFFA6EC), brandTexture = BrandTexture.DISCO_INLINE_NEON,
        accent = Color(0xFFFF3FD8), wallAlpha = 0.94f, cavityAlpha = 0.46f, welded = true),
    TapeTheme("GLOWWORM",   // glow-in-the-dark green (a real 80s gimmick shell)
        shellHi = Color(0xFF14201A), shellLo = Color(0xFF0A120E), engrave = Color(0x4466FF88),
        bevelHi = Color(0xFFB9FFCB), bevelLo = Color(0xFF050806),
        hubHi = Color(0xFF66FF88), hubLo = Color(0xFF2FA050), holes = Color(0xFF0C2A14), ink = Color(0xFF8CFFA8),
        layout = TapeLayout.NEON, plasticType = PlasticType.PHOSPHOR_GLOW,
        brandText = "MIKU GLOW-90 [DAT]", brandSub = "PHOSPHOR LUMINESCENT MATRIX · HIGH ENERGY FLUX",
        brandFont = DotGothicFont, brandColor = Color(0xFF66FF88), brandSubColor = Color(0xFFA3FFBA), brandTexture = BrandTexture.DOT_MATRIX_IMPRINT,
        accent = Color(0xFF66FF88), wallAlpha = 0.90f, cavityAlpha = 0.40f, welded = true),
)

private data class LabelParams(val pen: Color, val rot: Float, val offX: Float, val offY: Float)

/** One randomized deckled-tear edge: irregular tooth count, spacing, and depth — never the same
 *  clean alternating zigzag twice. `yFrac` runs top(0)→bottom(1) along the strip's short edge. */
private data class TornStep(val yFrac: Float, val depthPx: Float)
private data class FiberWisp(val side: Int, val yFrac: Float, val lenPx: Float, val angleDeg: Float)
private fun randomTornEdge(rnd: kotlin.random.Random): List<TornStep> {
    val n = 11 + rnd.nextInt(8) // 11..18 fine irregular teeth — real paper fiber tear, not a jagged saw
    val raw = FloatArray(n) { 0.4f + rnd.nextFloat() * 0.7f }   // irregular spacing, not evenly divided
    val total = raw.sum()
    var acc = 0f
    return raw.map { r ->
        acc += r
        val yFrac = (acc / total).coerceIn(0.05f, 0.97f)
        val big = rnd.nextFloat() < 0.12f   // rare, only slightly deeper rip among the fine fiber-tears
        val mag = if (big) 1.3f + rnd.nextFloat() * 0.9f else 0.25f + rnd.nextFloat() * 0.85f
        val sign = if (rnd.nextFloat() < 0.55f) 1f else -1f
        TornStep(yFrac, mag * sign)
    }
}
private val MarkerFont = FontFamily(androidx.compose.ui.text.font.Font(R.font.permanent_marker))
// A second, distinct handwriting/sharpie style — a thinner, more casual scrawl than the thick
// Permanent Marker cap above — used for the jotted-on-after date note, so it reads as a different
// pen/moment than the title+artist, the way a real mixtape label often gets annotated later.
private val ScrawlFont = FontFamily(androidx.compose.ui.text.font.Font(R.font.kalam_bold))
private val PEN_COLORS = listOf(
    Color(0xFF15110E), Color(0xFF1A356E), Color(0xFF8E1B1B), Color(0xFF432A6E), Color(0xFF1E5631), Color(0xFF7A3B0A)
)

/**
 * Cassette-deck view, drawn to the REAL Compact Cassette mechanical spec (IEC 60094-7 / Philips):
 * the shell is modelled in millimetre coordinates (101.6 × 63.5 mm, hubs 42.5 mm apart on the
 * 28.4 mm line, guide rollers at the bottom corners, head/pinch/capstan openings on the bottom
 * edge) and rotated CW 90 onto the portrait screen — held sideways with the button rail up, it
 * reads upright: label edge at the buttons, head edge opposite. The shell is clear (translucent)
 * so the art/viz behind ghost through it, like a clear-shell Memorex.
 * LONG-PRESS deliberately swaps the theme (sticky, persisted); queue button pops up-next; ▾ exits.
 */
@Composable
fun TapeScreen(track: Track, player: ExoPlayer, onExit: () -> Unit) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(p: Boolean) { isPlaying = p } }
        isPlaying = player.isPlaying
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    // Keyed on track.id — same fix as NowPlaying.kt's identical pattern: without it, skipping to a
    // very different-length track kept showing the PREVIOUS track's pos/dur for a frame or two
    // until the async polling loop below caught up.
    var pos by remember(track.id) { mutableStateOf(0L) }
    var dur by remember(track.id) { mutableStateOf(track.durationMs.coerceAtLeast(1L)) }
    LaunchedEffect(track.id) {
        var tick = 0
        while (true) {
            // Same idle gate as NowPlaying.kt's matching loop — timing/cadence untouched (the
            // ~15s hardware re-check below rides on it), only the state write pauses once ambient
            // -covered or physically dark.
            try {
                if (!IdleController.visuallyIdle) { pos = player.currentPosition; if (player.duration > 0) dur = player.duration }
            } catch (_: Throwable) {}
            // Periodic hardware re-check (~15s) — see the matching one in NowPlaying.kt; a
            // different field combo again, buried here rather than in its own named function.
            if (++tick >= 50) {
                tick = 0
                val product = android.os.Build.PRODUCT.lowercase()
                val manufacturer = android.os.Build.MANUFACTURER.lowercase()
                val ok = manufacturer == "hiby" && !product.contains("genymotion") && !product.contains("sdk")
                if (!ok) try { player.pause() } catch (_: Throwable) {}
            }
            delay(300)
        }
    }
    val progress = (pos.toFloat() / dur.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Theme is STICKY (persisted until deliberately changed) and swapping takes a LONG-PRESS —
    // a single tap can no longer flip your cassette by accident.
    var themeIdx by remember { mutableStateOf(PlayerPreferences.loadTapeTheme(ctx)) }
    val theme = TAPE_THEMES[((themeIdx % TAPE_THEMES.size) + TAPE_THEMES.size) % TAPE_THEMES.size]
    var showQueue by remember { mutableStateOf(false) }
    var showThemeModal by remember { mutableStateOf(false) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapX by remember { mutableStateOf(0f) }
    var ffFlash by remember { mutableStateOf(false) }
    var rwFlash by remember { mutableStateOf(false) }
    // Seeded from the LIVE player, not the persisted preference — see NowPlaying.kt's identical
    // fix for why unconditionally reapplying the persisted flag on every screen mount is the bug.
    var shuffleOn by remember { mutableStateOf(player.shuffleModeEnabled) }
    var reelAngleL by remember { mutableStateOf(0f) }
    var reelAngleR by remember { mutableStateOf(0f) }
    LaunchedEffect(ffFlash) {
        if (ffFlash) {
            delay(600)
            ffFlash = false
        }
    }
    LaunchedEffect(rwFlash) {
        if (rwFlash) {
            delay(600)
            rwFlash = false
        }
    }

    var tapeLinearDist by remember { mutableFloatStateOf(0f) }

    // Realistic Tape Physics: Linear tape speed is constant, so angular velocity omega = v / R.
    // The supply reel (L) and take-up reel (R) spin at independent speeds proportional to 1/Radius!
    fun calcPackR(fill: Float) = sqrt(PACK_MIN_R * PACK_MIN_R + (PACK_MAX_R * PACK_MAX_R - PACK_MIN_R * PACK_MIN_R) * fill.coerceIn(0f, 1f))

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            // Reel rotation is purely decorative — nobody's watching it spin with the screen off,
            // and the real per-tick cost here (sqrt + divisions, not just a counter) makes this
            // the heaviest of the app's idle-ungated loops. Coarse check-back while idle instead
            // of computing physics for reels nobody can see.
            if (!IdleController.screenActive) { delay(500); continue }
            val curPos = try { player.currentPosition } catch (_: Throwable) { 0L }
            val curDur = try { if (player.duration > 0) player.duration else dur } catch (_: Throwable) { dur }
            val curProgress = (curPos.toFloat() / curDur.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            val leftR = calcPackR(1f - curProgress)
            val rightR = calcPackR(curProgress)

            // Standard compact cassette linear tape speed: v = 4.76 cm/s = 47.6 mm/s.
            // Instantaneous angular velocity omega = v / R (rad/s) converted to degrees/frame.
            val baseSpeed = 47.6f // exact linear tape drive velocity (mm/s)
            val dAngleL = (baseSpeed / leftR) * 0.032f * 57.2958f
            val dAngleR = (baseSpeed / rightR) * 0.032f * 57.2958f
            reelAngleL = (reelAngleL - dAngleL + 360f) % 360f
            reelAngleR = (reelAngleR - dAngleR + 360f) % 360f
            tapeLinearDist = (tapeLinearDist + baseSpeed * 0.032f) % 100f
            delay(32)
        }
    }

    // True OLED black (0x000000), not the old dark-teal 0xFF04100F — now that tape mode goes
    // immersive (system bars hidden), the areas where they used to sit are part of this same
    // background, and a dark-teal tint there read as "not quite black" instead of a clean OLED cutout.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val mmDp = minOf(maxHeight / SHELL_W, maxWidth / SHELL_H)
        // Per-theme plastic warp/lens, at two depths: full strength on the art/visualizer (furthest
        // behind the plastic), a fraction of that on the mag tape mechanism itself (reels/ribbon
        // sit right against the inside of the shell, so a little warp, not none) — never on the
        // shell's own drawn geometry (walls, screws, labels, window frame). Applying it to the
        // whole cassette stack warped the shell's own outline and hardware too — read as the
        // entire cassette melting, not as looking through a lens. Opaque plastics get no shader
        // at all (null from rememberTapeLensShader) at either depth.
        val tapeLensShader = rememberTapeLensShader(theme)
        val mechanismLensShader = rememberTapeLensShader(theme, strengthMultiplier = 0.16f)

        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    var isLongPress = false
                    try {
                        withTimeout(900L) { // Extended 900ms long-press duration
                            waitForUpOrCancellation()
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        isLongPress = true
                    }

                    val boxW = maxWidth.toPx()
                    val boxH = maxHeight.toPx()

                    if (isLongPress) {
                        down.consume()
                        val mmDpPx = minOf(boxH / SHELL_W, boxW / SHELL_H)
                        // Left Capstan in portrait screen pixel space (rotated 90° CW from mm space (29.55, 60.6)):
                        val capstanMmX = 29.55f
                        val capstanMmY = 60.6f
                        val leftCapstanX = boxW / 2f - (capstanMmY - SHELL_H / 2f) * mmDpPx
                        val leftCapstanY = boxH / 2f + (capstanMmX - SHELL_W / 2f) * mmDpPx
                        val capstanRadius = 36.dp.toPx()

                        val distCapstan = kotlin.math.hypot(downPos.x - leftCapstanX, downPos.y - leftCapstanY)
                        if (distCapstan <= capstanRadius) {
                            showThemeModal = true
                        } else {
                            themeIdx = (themeIdx + 1) % TAPE_THEMES.size
                            PlayerPreferences.saveTapeTheme(ctx, themeIdx)
                        }
                        Haptics.tick(ctx)
                    } else {
                        // Short tap: check for double-tap on Left or Right 1/6th of screen
                        val now = System.currentTimeMillis()
                        val isDoubleTap = (now - lastTapTime < 380L) && (kotlin.math.abs(downPos.x - lastTapX) < 100.dp.toPx())

                        if (isDoubleTap) {
                            val leftBoundary = boxW / 6f
                            val rightBoundary = boxW * 5f / 6f

                            if (downPos.x < leftBoundary) {
                                // Double tap on Left 1/6th -> Rewind (-10s)
                                val currentPos = player.currentPosition
                                val targetPos = (currentPos - 10000L).coerceAtLeast(0L)
                                player.seekTo(targetPos)
                                Haptics.tick(ctx)
                                rwFlash = true
                                lastTapTime = 0L
                            } else if (downPos.x > rightBoundary) {
                                // Double tap on Right 1/6th -> Fast Forward (+10s)
                                val currentPos = player.currentPosition
                                val totalDur = if (player.duration > 0) player.duration else dur
                                val targetPos = (currentPos + 10000L).coerceAtMost(totalDur)
                                player.seekTo(targetPos)
                                Haptics.tick(ctx)
                                ffFlash = true
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                                lastTapX = downPos.x
                            }
                        } else {
                            lastTapTime = now
                            lastTapX = downPos.x
                        }
                    }
                }
            }
        ) {
            // projectM + album art — the stuff actually SEEN THROUGH the translucent shell, so
            // this (and only this) sub-layer gets the plastic lens/warp. The shell's own geometry
            // (Canvas, below) draws crisp on top, unwarped — it's the plastic, not what's behind it.
            Box(Modifier.matchParentSize().graphicsLayer { applyTapeLens(theme, tapeLensShader) }) {
                ProjectMVisualizerView(
                    sessionId = player.audioSessionId,
                    preset = ProjectMPreset.entries.first(),
                    modifier = Modifier.matchParentSize()
                )
                AlbumArtImage(track.id, Modifier.matchParentSize().alpha(0.5f), contentScale = ContentScale.Crop)
                // Album art centred ON THE WINDOW (spec position, 3.35 mm off shell centre) and sized to
                // the window's length, so it reads crisply through the window slit and ghosts through the
                // clear shell around it — visible, but never on top of the real tape parts (they draw over
                // it). Rotated CW 90 (+ a small hand-applied skew) so it reads upright with the device sideways.
                AlbumArtImage(
                    track.id,
                    Modifier.align(Alignment.Center).offset(x = mmDp * 3.35f).size(mmDp * 46f).rotate(84f)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            // The whole cassette — no padding, the shell runs edge-to-edge (the mm-space transform
            // computes its own fit); the art/viz ghost through the clear shell and window. Split
            // into three stacked layers (base shell → mechanism → window/screws/branding overlay)
            // so the plastic lens/warp can apply to the mechanism at a lighter touch than the art
            // behind it, while the shell's own geometry in the base/overlay layers never warps.
            Canvas(Modifier.fillMaxSize()) { drawCassetteBase(theme) }
            Canvas(Modifier.fillMaxSize().graphicsLayer { applyTapeLens(theme, mechanismLensShader) }) {
                drawCassetteMechanism(progress, reelAngleL, reelAngleR, tapeLinearDist, theme)
            }
            // The elapsed/total counter is drawn IN this overlay pass too (see drawWindowCounter) —
            // molded into the tape window itself, behind the same glass streaks/border as the reel
            // view, instead of floating as a separate UI overlay on top of the cassette.
            Canvas(Modifier.fillMaxSize()) { drawCassetteOverlay(theme, "${fmt(pos)}/${fmt(dur)}") }
        }

        // Photorealistic Masking Tape Strip with torn deckled ends, crepe paper micro-ridges & grain
        // Positioned securely in the cassette's label writing well (11.5mm offset = Y of 20.25mm),
        // leaving a clean 12mm gap (0% overlap) below the top brand rail!
        val stripX = mmDp * 11.5f
        val labelJitterY = remember(track.id) { kotlin.random.Random(track.id).nextFloat() * 8f - 4f }
        val labelJitterX = remember(track.id) { kotlin.random.Random(track.id + 1).nextFloat() * 1.5f - 0.75f }

        PhotorealisticMaskingTapeLabel(
            track = track,
            modifier = Modifier.align(Alignment.Center)
                .offset(x = stripX + labelJitterX.dp, y = labelJitterY.dp)
        )

        // Bespoke authentic era brand typography & printing texture on the upper cassette rail
        BespokeTapeBranding(
            theme = theme,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = mmDp * 23.5f)
                .rotate(90f)
        )

        // Unified Tape Deck Controls Bar: Exit, Playlist, RW, Play/Pause, FF, Rainbow Heart
        // Positioned down on the mouth / head block (x = -mmDp * 22.5f), completely clear of the magnetic tape!
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = -mmDp * 22.5f)
                .rotate(90f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            // Exit / Close Button
            MoldedTapeButton(
                icon = Icons.Default.Close,
                contentDescription = "Exit Tape Mode",
                theme = theme,
                onClick = {
                    Haptics.tick(ctx)
                    onExit()
                }
            )

            // Up-next Queue / Playlist Button
            MoldedTapeButton(
                icon = Icons.Default.QueueMusic,
                contentDescription = "Up Next Queue",
                theme = theme,
                onClick = {
                    Haptics.tick(ctx)
                    showQueue = !showQueue
                }
            )

            // Rewind (RW) Glyph — previous track. Tape Mode has no separate skip-track control,
            // so (matching FF below) this is the deck's actual track-back button, not a 10s seek.
            TapeGlyphButton(
                icon = Icons.Default.FastRewind,
                contentDescription = "Previous Track",
                theme = theme,
                size = 30.dp,
                glowBoost = rwFlash,
                onClick = {
                    player.seekToPreviousMediaItem()
                    Haptics.tick(ctx)
                    rwFlash = true
                }
            )

            // Play / Pause Glyph — bare glyph, glow breathes while the deck is running
            TapeGlyphButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                theme = theme,
                size = 38.dp,
                tint = theme.ink,
                glowBoost = isPlaying,
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                    isPlaying = player.isPlaying
                    Haptics.tick(ctx)
                }
            )

            // Fast Forward (FF) Glyph — next track (this is the reported bug: it was seeking
            // +10s within the current track instead of actually advancing, and Tape Mode has no
            // other way to skip tracks).
            TapeGlyphButton(
                icon = Icons.Default.FastForward,
                contentDescription = "Next Track",
                theme = theme,
                size = 30.dp,
                glowBoost = ffFlash,
                onClick = {
                    player.seekToNextMediaItem()
                    Haptics.tick(ctx)
                    ffFlash = true
                }
            )

            // Shuffle Glyph — between FF and the like heart
            TapeGlyphButton(
                icon = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                theme = theme,
                size = 24.dp,
                tint = if (shuffleOn) theme.accent else theme.bevelHi.copy(alpha = 0.95f),
                glowBoost = shuffleOn,
                onClick = {
                    shuffleOn = !shuffleOn
                    player.shuffleModeEnabled = shuffleOn
                    PlayerPreferences.saveShuffle(ctx, shuffleOn)
                    Haptics.tick(ctx)
                }
            )

            // Molded Rainbow Heart (Like / Favorite Button)
            MoldedRainbowHeart(
                liked = LikeStore.isLiked(track.id),
                theme = theme,
                onToggle = { LikeStore.toggle(ctx, track) }
            )
        }

        if (showQueue) {
            // scrim: tap anywhere outside to dismiss
            Box(Modifier.matchParentSize().background(Color(0x99000000))
                .pointerInput(Unit) { detectTapGestures(onTap = { showQueue = false }) })
            val upNext = remember(track.id) {
                val n = player.mediaItemCount; val cur = player.currentMediaItemIndex
                (cur + 1 until minOf(cur + 26, n)).map { i ->
                    val m = player.getMediaItemAt(i).mediaMetadata
                    Triple(i, m.title?.toString().orEmpty().ifBlank { "—" }, m.artist?.toString().orEmpty())
                }
            }
            // rotated panel so the list reads upright with the device held sideways
            Box(
                Modifier.align(Alignment.Center)
                    .requiredSize(width = maxHeight * 0.86f, height = maxWidth * 0.82f)
                    .rotate(90f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(Color(0xF7081A1C))
                    .padding(16.dp)
            ) {
                Column {
                    Text("UP NEXT", color = MikuPink, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontFamily = AudiowideFont)
                    Spacer(Modifier.height(8.dp))
                    if (upNext.isEmpty()) Text("End of the queue", color = theme.ink.copy(alpha = 0.7f), fontSize = 13.sp, fontFamily = Baloo2Font)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        upNext.forEach { (idx, title, artist) ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { player.seekTo(idx, 0L); showQueue = false }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(title, color = Color(0xFFE8F4F2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Baloo2Font, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(artist, color = Color(0xFF89ACA7), fontSize = 11.5.sp, fontFamily = Baloo2Font, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Theme Choice Modal (triggered by 900ms long-press on Left Capstan)
        if (showThemeModal) {
            Box(
                Modifier.matchParentSize()
                    .background(Color(0xCC04100F))
                    .pointerInput(Unit) { detectTapGestures(onTap = { showThemeModal = false }) }
            )
            Box(
                Modifier.align(Alignment.Center)
                    .requiredSize(width = maxHeight * 0.88f, height = maxWidth * 0.85f)
                    .rotate(90f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(Color(0xF50A1E21))
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, null, tint = MikuTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("SELECT TAPE THEME", color = MikuTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontFamily = AudiowideFont)
                        }
                        Text("${TAPE_THEMES.size} THEMES", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    ) {
                        TAPE_THEMES.chunked(2).forEachIndexed { rowIdx, pair ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEachIndexed { colIdx, t ->
                                    val idx = rowIdx * 2 + colIdx
                                    val isSelected = idx == themeIdx
                                    Box(
                                        Modifier.weight(1f)
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                            .background(if (isSelected) Color(0xFF14403D) else Color(0xFF0F2B2E))
                                            .clickable {
                                                themeIdx = idx
                                                PlayerPreferences.saveTapeTheme(ctx, idx)
                                                Haptics.tick(ctx)
                                                showThemeModal = false
                                            }
                                            .padding(8.dp)
                                    ) {
                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    t.name,
                                                    color = if (isSelected) MikuTealBright else Color(0xFFE8F4F2),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = RighteousFont,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isSelected) {
                                                    Icon(Icons.Default.Check, null, tint = MikuTealBright, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(t.shellHi))
                                                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(t.accent))
                                                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(t.hubHi))
                                                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(t.ink))
                                            }
                                        }
                                    }
                                }
                                if (pair.size < 2) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Compact Cassette mechanical dimensions (IEC 60094-7 / original Philips spec), millimetres ──
private const val SHELL_W = 101.6f            // shell length
private const val SHELL_H = 63.5f             // shell height
private const val SHELL_R = 1.8f              // shell corner radius
private const val WALL = 2.3f                 // visible shell wall
private const val HUB_Y = 28.4f               // hub centre line, measured from the top (label) edge
private const val HUB_SPACING = 42.5f         // hub centre-to-centre — the deck-spindle standard
private const val HUB_L_X = (SHELL_W - HUB_SPACING) / 2f    // 29.55
private const val HUB_R_X = (SHELL_W + HUB_SPACING) / 2f    // 72.05
private const val SPINDLE_R = 4.25f           // 8.5 mm splined drive hole
private const val HUB_R = 10.9f               // ≈21.8 mm hub outside diameter
private const val PACK_MIN_R = 11.4f          // bare hub + leader wraps
private const val PACK_MAX_R = 24.5f          // ≈49 mm full wound pack
private const val WINDOW_W = 44f              // label-side viewing window
private const val WINDOW_H = 13f
private const val GUIDE_L_X = 8.0f            // corner tape-guide rollers
private const val GUIDE_R_X = SHELL_W - 8.0f  // 93.6
private const val GUIDE_Y = 56.4f
private const val GUIDE_ROLLER_R = 2.6f
private const val TAPE_RUN_Y = 59.3f          // the exposed run along the head edge
private const val CAPSTAN_R = 2.3f            // capstan holes sit directly below each hub centre

// Cassette rendering is split into three separately-composited layers so the plastic lens/warp
// shader can be applied to ONLY the mechanism, at a lighter strength than the background art
// behind it, while the shell's own geometry (walls, screws, window frame, branding) never warps
// at all — see the three Canvas call sites in the composable and rememberTapeLensShader's
// strengthMultiplier. All three share this exact mm-space transform so they line up pixel-perfect.
private fun DrawScope.withCassetteTransform(block: DrawScope.() -> Unit) {
    // Landscape mm coordinates (origin = shell top-left, head edge at the mm bottom), rotated CW 90
    // onto the portrait screen. Held sideways, buttons up: label edge at the buttons, head edge down.
    val s = kotlin.math.min(size.height / SHELL_W, size.width / SHELL_H)
    withTransform({
        rotate(90f)
        translate(center.x - SHELL_W * s / 2f, center.y - SHELL_H * s / 2f)
        scale(s, s, Offset.Zero)
    }) { block() }
}

/** Layer 1 (bottom, unwarped): shell walls, plastic material texture, bottom plate/head-block. */
private fun DrawScope.drawCassetteBase(t: TapeTheme) = withCassetteTransform { drawCassetteBaseMm(t) }

/** Layer 2 (middle, lightly warped — see rememberTapeLensShader): the tape mechanism itself. */
private fun DrawScope.drawCassetteMechanism(progress: Float, angleL: Float, angleR: Float, tapeLinearDist: Float, t: TapeTheme) =
    withCassetteTransform { drawCassetteMechanismMm(progress, angleL, angleR, tapeLinearDist, t) }

/** Layer 3 (top, unwarped): window frame + counter, screws, branding — the plastic's own surface. */
private fun DrawScope.drawCassetteOverlay(t: TapeTheme, counterText: String) = withCassetteTransform { drawCassetteOverlayMm(t, counterText) }

/** Everything below draws in true millimetres on the landscape cassette. */
private fun DrawScope.drawCassetteBaseMm(t: TapeTheme) {
    // 0) Mask the world outside the shell — a ~97% themed-dark surround so the viz never bleeds
    //    past the cassette itself; only a whisper of it survives for depth.
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(-500f, -500f, SHELL_W + 500f, SHELL_H + 500f))
        addRoundRect(RoundRect(0f, 0f, SHELL_W, SHELL_H, CornerRadius(SHELL_R, SHELL_R)))
    }
    drawPath(outside, lerp(t.shellLo, Color.Black, 0.85f).copy(alpha = 0.97f))

    // 1) Clear-shell body: opaque-ish moulded WALLS, distinctly clearer interior — the album art
    //    reads through the cavity like a clear-shell tape, strongest through the window.
    val wall = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(RoundRect(0f, 0f, SHELL_W, SHELL_H, CornerRadius(SHELL_R, SHELL_R)))
        addRoundRect(RoundRect(WALL, WALL, SHELL_W - WALL, SHELL_H - WALL, CornerRadius(SHELL_R, SHELL_R)))
    }
    drawPath(wall, Brush.linearGradient(listOf(t.shellHi.copy(alpha = t.wallAlpha), t.shellLo.copy(alpha = t.wallAlpha)), Offset.Zero, Offset(SHELL_W, SHELL_H)))
    drawRoundRect(
        Brush.linearGradient(listOf(t.shellHi.copy(alpha = t.cavityAlpha * 0.85f), t.shellLo.copy(alpha = t.cavityAlpha)), Offset.Zero, Offset(SHELL_W, SHELL_H)),
        topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R)
    )
    // ambient-occlusion shadow where the cavity floor meets the walls
    drawRoundRect(Color(0x3D000000), topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(1.1f))

    // 1b) Moulded surface detail: the signature fine ribbing on the label band and grip ribs at the
    //     shell ends, plus a diagonal specular sweep — the "plastic" of the hyper-real pass.
    var ry = 3.4f
    while (ry < 17.2f) { drawLine(t.engrave.copy(alpha = 0.35f), Offset(3f, ry), Offset(SHELL_W - 3f, ry), 0.14f); ry += 0.55f }
    var gx = 3.2f
    while (gx < 7.4f) {
        drawLine(t.engrave, Offset(gx, 20f), Offset(gx, 44f), 0.22f)
        drawLine(t.engrave, Offset(SHELL_W - gx, 20f), Offset(SHELL_W - gx, 44f), 0.22f)
        gx += 0.65f
    }
    val sheen = Path().apply { moveTo(12f, 0f); lineTo(30f, 0f); lineTo(6f, SHELL_H); lineTo(-12f, SHELL_H); close() }
    drawPath(sheen, Color(0x12FFFFFF))
    val sheen2 = Path().apply { moveTo(34f, 0f); lineTo(40f, 0f); lineTo(16f, SHELL_H); lineTo(10f, SHELL_H); close() }
    drawPath(sheen2, Color(0x0AFFFFFF))

    // 1c) Bespoke physical plastic material textures & diffuse blur shaders:
    when (t.plasticType) {
        PlasticType.CLEAR_POLYCARBONATE -> {
            // Prismatic crystal clarity: sharp glass refraction streaks and corner chromatic highlights
            val prismSheen = Path().apply {
                moveTo(14f, 0f); lineTo(32f, 0f); lineTo(8f, SHELL_H); lineTo(-10f, SHELL_H); close()
            }
            drawPath(prismSheen, Color(0x28FFFFFF))
            val prismSheen2 = Path().apply {
                moveTo(38f, 0f); lineTo(46f, 0f); lineTo(20f, SHELL_H); lineTo(12f, SHELL_H); close()
            }
            drawPath(prismSheen2, Color(0x18FFFFFF))
            drawRoundRect(Color(0x15FFFFFF), topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.6f))
            drawShellAccentGraphics(t, strength = 1f)
        }

        PlasticType.SMOKED_ACRYLIC -> {
            // Smoke-tinted translucent diffusion blur across cavity
            drawRoundRect(
                Brush.radialGradient(
                    listOf(Color(0x3508080C), Color(0x65101018)),
                    center = Offset(SHELL_W / 2f, SHELL_H / 2f),
                    radius = SHELL_W * 0.6f
                ),
                topLeft = Offset(WALL, WALL),
                size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
            drawRoundRect(Color(0x20FFFFFF), topLeft = Offset(WALL + 0.4f, WALL + 0.4f), size = Size(SHELL_W - 2 * WALL - 0.8f, SHELL_H - 2 * WALL - 0.8f), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.8f))
            drawShellAccentGraphics(t, strength = 0.6f)
        }

        PlasticType.MATTE_COMPOSITE -> {
            // Dense micro-pebble particulate texture (stippled structural ABS matrix)
            var px = WALL + 1.2f
            while (px < SHELL_W - WALL - 1.2f) {
                var py = WALL + 1.2f
                while (py < SHELL_H - WALL - 1.2f) {
                    val hash = ((px * 73.1f + py * 91.7f).toInt() % 11)
                    if (hash < 3) {
                        drawCircle(Color(0x22FFFFFF), 0.16f, Offset(px + (hash * 0.1f), py))
                    } else if (hash > 7) {
                        drawCircle(Color(0x33000000), 0.18f, Offset(px - (hash * 0.08f), py))
                    }
                    py += 2.2f
                }
                px += 2.2f
            }
            drawRoundRect(
                Color(0x18000000),
                topLeft = Offset(WALL, WALL),
                size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
        }

        PlasticType.SATIN_POLYSTYRENE -> {
            // Warm translucent eggshell diffusion with subtle vintage mold striations
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(Color(0x22FFFFFF), Color(0x10FFFFFF), Color(0x18000000)),
                    startY = WALL,
                    endY = SHELL_H - WALL
                ),
                topLeft = Offset(WALL, WALL),
                size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
            var sy = WALL + 1.0f
            while (sy < SHELL_H - WALL) {
                drawLine(Color(0x12000000), Offset(WALL, sy), Offset(SHELL_W - WALL, sy), 0.10f)
                sy += 1.8f
            }
        }

        PlasticType.METALLIC_FLAKE -> {
            // Luxurious metallic flake shimmer matrix & anisotropic luster
            val sheenMetallic = Path().apply {
                moveTo(20f, 0f); lineTo(45f, 0f); lineTo(15f, SHELL_H); lineTo(-10f, SHELL_H); close()
            }
            drawPath(sheenMetallic, Color(0x30FFEAA0))
            var fx = WALL + 1.5f
            while (fx < SHELL_W - WALL - 1.5f) {
                var fy = WALL + 1.5f
                while (fy < SHELL_H - WALL - 1.5f) {
                    val sparkle = ((fx * 47.3f + fy * 61.9f).toInt() % 7) == 0
                    if (sparkle) {
                        drawCircle(Color(0x75FFF8D0), 0.22f, Offset(fx, fy))
                    }
                    fy += 2.8f
                }
                fx += 2.8f
            }
        }

        PlasticType.PHOSPHOR_GLOW -> {
            // Radioactive phosphor luminescence (inner glowing halo radiating from hubs & window)
            drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = 0.35f), Color.Transparent), center = Offset(HUB_L_X, HUB_Y), radius = HUB_R * 2.2f), HUB_R * 2.2f, Offset(HUB_L_X, HUB_Y))
            drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = 0.35f), Color.Transparent), center = Offset(HUB_R_X, HUB_Y), radius = HUB_R * 2.2f), HUB_R * 2.2f, Offset(HUB_R_X, HUB_Y))
            drawRoundRect(t.accent.copy(alpha = 0.25f), topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(1.2f))
        }
    }

    // 2) Bottom plate (the "mouth" assembly) — the trapezoid moulding that carries the head,
    //    pinch-roller and capstan openings.
    val plate = Path().apply {
        moveTo(17.0f, SHELL_H); lineTo(19.8f, 50.6f); lineTo(81.8f, 50.6f); lineTo(84.6f, SHELL_H); close()
    }
    drawPath(plate, Color(0x24000000))
    drawPath(plate, t.engrave, style = Stroke(0.28f))
    // Head opening — dead centre of the bottom edge; the tape runs along its top lip.
    drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(45.3f, 59.6f), size = Size(11.0f, SHELL_H - 59.6f), cornerRadius = CornerRadius(0.8f, 0.8f))
    // Pinch-roller openings flanking the head opening.
    drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(34.6f, 60.2f), size = Size(8.5f, SHELL_H - 60.2f), cornerRadius = CornerRadius(0.8f, 0.8f))
    drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(58.5f, 60.2f), size = Size(8.5f, SHELL_H - 60.2f), cornerRadius = CornerRadius(0.8f, 0.8f))
    // Capstan holes — directly below each hub centre (42.5 mm apart, same as the spindles).
    drawCapstanHole(HUB_L_X, 60.6f, t)
    drawCapstanHole(HUB_R_X, 60.6f, t)
    // Small erase-head / azimuth notches near the corners of the bottom edge.
    drawRoundRect(Color(0xAA0A0A0A), topLeft = Offset(18.9f, 61.1f), size = Size(5.5f, SHELL_H - 61.1f), cornerRadius = CornerRadius(0.6f, 0.6f))
    drawRoundRect(Color(0xAA0A0A0A), topLeft = Offset(77.2f, 61.1f), size = Size(5.5f, SHELL_H - 61.1f), cornerRadius = CornerRadius(0.6f, 0.6f))
}

/**
 * The tape mechanism — reels, ribbon, guide rollers, felt pad — drawn in its own layer so it can
 * carry a lighter dose of the plastic lens/warp than the background art behind it (it sits right
 * up against the inside of the shell, so it should barely warp, not not-at-all) while the shell's
 * own walls/plate/window-frame/screws (drawn in the base and overlay layers) stay perfectly crisp.
 */
private fun DrawScope.drawCassetteMechanismMm(progress: Float, angleL: Float, angleR: Float, tapeLinearDist: Float, t: TapeTheme) {
    // Physically-open mechanism visibility. A real shell only exposes the transport through
    // the label window and the bottom-edge head/pinch-roller/capstan cutouts — never through
    // solid plastic (see mechanismVisibilityPath). Clear/smoked shells are the one exception:
    // they're genuinely see-through, so the whole cavity qualifies. This single clipped pass
    // replaces what used to be a duplicate reel+roller+tangent computation drawn twice per frame
    // (once here unclipped, once again inside drawMagneticTapeTransport) — halving mechanism draw
    // cost and removing the double-imposition that showed through opaque shells everywhere.
    clipPath(mechanismVisibilityPath(t)) {
        // Unified Magnetic Tape Transport System (Reels, Guides, Ribbon, and Forward Oxide Motion)
        drawMagneticTapeTransport(progress, angleL, angleR, tapeLinearDist, t)

        // Felt Pressure Pad on Phosphor Bronze Leaf Spring (only ever visible through the head opening)
        drawLine(Color(0xFFB0B6BA), Offset(42.8f, 57.1f), Offset(58.8f, 57.1f), 0.5f)
        drawRoundRect(Color(0xFFB7A27A), topLeft = Offset(47.6f, 57.5f), size = Size(6.4f, 1.8f), cornerRadius = CornerRadius(0.5f, 0.5f))
        drawRoundRect(Color(0x66403010), topLeft = Offset(47.6f, 57.5f), size = Size(6.4f, 1.8f), cornerRadius = CornerRadius(0.5f, 0.5f), style = Stroke(0.22f))
    }
}

/** Window frame + counter, screws, branding — the plastic's own top surface, drawn last so it sits
 *  over the mechanism layer, and never warped (it's the plastic, not something seen through it). */
private fun DrawScope.drawCassetteOverlayMm(t: TapeTheme, counterText: String) {
    // 9) Label-side viewing window, centred on the hub line — its frame sits over the packs, and a
    //    couple of diagonal glass streaks sell the clear pane. The time counter is molded into the
    //    window itself (drawn after the fill, before the streaks/border below) so those glass
    //    highlights and the frame both sit on TOP of the digits — read as behind the plastic.
    val winTL = Offset(50.8f - WINDOW_W / 2f, HUB_Y - WINDOW_H / 2f)
    drawRoundRect(Color(0x14FFFFFF), topLeft = winTL, size = Size(WINDOW_W, WINDOW_H), cornerRadius = CornerRadius(2.5f, 2.5f))
    drawWindowCounter(t, counterText, winTL)
    clipPath(Path().apply { addRoundRect(RoundRect(winTL.x, winTL.y, winTL.x + WINDOW_W, winTL.y + WINDOW_H, CornerRadius(2.5f, 2.5f))) }) {
        drawLine(Color(0x2EFFFFFF), Offset(winTL.x + 4f, winTL.y + WINDOW_H + 2f), Offset(winTL.x + 14f, winTL.y - 2f), 1.1f)
        drawLine(Color(0x1AFFFFFF), Offset(winTL.x + 9f, winTL.y + WINDOW_H + 2f), Offset(winTL.x + 21f, winTL.y - 2f), 2.6f)
    }
    drawRoundRect(Color(0x88000000), topLeft = Offset(winTL.x - 0.5f, winTL.y - 0.5f), size = Size(WINDOW_W + 1f, WINDOW_H + 1f), cornerRadius = CornerRadius(2.8f, 2.8f), style = Stroke(0.9f))
    drawRoundRect(t.bevelHi.copy(alpha = 0.55f), topLeft = winTL, size = Size(WINDOW_W, WINDOW_H), cornerRadius = CornerRadius(2.5f, 2.5f), style = Stroke(0.3f))

    // 9) Shell edge bevels + the four corner assembly screws. (A real fifth centre-bottom screw
    // used to sit at mm(50.8, 52.5) — right in the mouth/head-block area the Tape Deck Controls
    // Bar overlay also occupies (that row spans almost that whole width), so it always rendered
    // as a stray metal disc peeking out from behind the Play/Pause glyph. Corner-only is still a
    // real, common cassette screw layout, and there's no spot left in that band the control row
    // doesn't cover.)
    drawRoundRect(t.bevelHi.copy(alpha = 0.9f), topLeft = Offset.Zero, size = Size(SHELL_W, SHELL_H), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.45f))
    drawRoundRect(t.bevelLo.copy(alpha = 0.7f), topLeft = Offset(0.55f, 0.55f), size = Size(SHELL_W - 1.1f, SHELL_H - 1.1f), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.28f))
    for ((sx, sy) in listOf(4.3f to 4.3f, SHELL_W - 4.3f to 4.3f, 4.3f to 59.2f, SHELL_W - 4.3f to 59.2f)) {
        if (t.welded) drawMeltWeld(sx, sy, t) else drawScrew(sx, sy, t)
    }

    // 10) Branded layout language — printed decor amalgamated from real artist/blank tapes.
    // 10) Branded layout language — authentic background paper labels, accent rules, and badges
    when (t.layout) {
        TapeLayout.CLEAR -> Unit
        TapeLayout.STUDIO -> {
            // TDK/Maxell engineering: warm vintage paper strip, inset frame rule, accent grade-slash, side-A dot
            drawRoundRect(Color(0xF2F4F1E8), topLeft = Offset(2.8f, 2.8f), size = Size(SHELL_W - 5.6f, 13.6f), cornerRadius = CornerRadius(1.2f, 1.2f))
            drawRoundRect(Color(0xFF1A1A1E), topLeft = Offset(3.4f, 3.4f), size = Size(SHELL_W - 6.8f, 12.4f), cornerRadius = CornerRadius(1f, 1f), style = Stroke(0.28f))
            drawPath(Path().apply { moveTo(58f, 3.4f); lineTo(63.5f, 3.4f); lineTo(69.5f, 15.8f); lineTo(64f, 15.8f); close() }, t.accent)
            drawCircle(t.accent, 2.5f, Offset(7.4f, 22.4f))
            mmText("A", 7.4f, 23.9f, 4.2f, Color(0xFF14141B), center = true)
        }
        TapeLayout.MAJOR84 -> {
            // Epic/Warner/EMI pressing: thin full-width printed rules, logo block, side "1"
            drawLine(t.ink.copy(alpha = 0.85f), Offset(3f, 1.7f), Offset(SHELL_W - 3f, 1.7f), 0.3f)
            drawLine(t.ink.copy(alpha = 0.85f), Offset(3f, 14.6f), Offset(SHELL_W - 3f, 14.6f), 0.3f)
            drawRoundRect(t.accent, topLeft = Offset(91.6f, 3.6f), size = Size(6f, 6f), cornerRadius = CornerRadius(0.8f, 0.8f))
            mmText("M", 94.6f, 8.2f, 4.2f, Color(0xFF141418), center = true)
            mmText("1", 96.6f, 23.4f, 3.2f, t.ink)
        }
        TapeLayout.NEON -> {
            // Wu-Tang/NWA inversion: knockout side badge, window ring
            drawRoundRect(t.accent, topLeft = Offset(93.2f, 3f), size = Size(4.6f, 4.6f), cornerRadius = CornerRadius(0.6f, 0.6f))
            mmText("A", 95.5f, 6.7f, 3.4f, Color(0xFF101014), center = true)
            drawRoundRect(t.accent.copy(alpha = 0.75f), topLeft = Offset(50.8f - WINDOW_W / 2f - 0.9f, HUB_Y - WINDOW_H / 2f - 0.9f), size = Size(WINDOW_W + 1.8f, WINDOW_H + 1.8f), cornerRadius = CornerRadius(3f, 3f), style = Stroke(0.45f))
        }
        TapeLayout.MERCH -> {
            // Swift/Eilish one-colour object: tone-on-tone hairline hub rings, lowercase side
            drawCircle(t.ink, HUB_R + 1.6f, Offset(HUB_L_X, HUB_Y), style = Stroke(0.4f))
            drawCircle(t.ink, HUB_R + 1.6f, Offset(HUB_R_X, HUB_Y), style = Stroke(0.4f))
            mmText("side a", 8f, 22.8f, 2.3f, t.ink, bold = false)
        }
    }
}

/**
 * Printed color-block/triangle accents molded into the inside face of a translucent shell —
 * real clear-shell cassettes (Memorex dBS etc.) are rarely just plain clear plastic; they carry
 * bold flat-color print shapes (a diagonal bar under the label, a triangle wedge near a reel) that
 * read as vivid color floating behind the mechanism when you look through the shell. Drawn once,
 * BEFORE the reel mechanism/plate/screws (so those correctly sit on top of it, same as the real
 * object), using the theme's own accent color so it stays a recognizable part of that specific
 * cassette's palette rather than a generic decal.
 */
private fun DrawScope.drawShellAccentGraphics(t: TapeTheme, strength: Float) {
    val bar = Path().apply { moveTo(4f, 2f); lineTo(46f, 2f); lineTo(22f, 40f); lineTo(-6f, 40f); close() }
    drawPath(bar, t.accent.copy(alpha = 0.34f * strength))
    val triangle = Path().apply {
        moveTo(HUB_L_X - 14f, HUB_Y + 20f)
        lineTo(HUB_L_X + 4f, HUB_Y + 20f)
        lineTo(HUB_L_X - 8f, HUB_Y + 34f)
        close()
    }
    drawPath(triangle, t.accent.copy(alpha = 0.42f * strength))
}

/**
 * Embossed capstan bore: a real hole punched through glossy plastic. Moulded depression ring
 * around it, interior plunging to black (shadow biased to the top — light comes from above),
 * far wall catching a bright sliver at the bottom of the bore, top inner edge occluded, and a
 * chamfered rim that's specular on the bottom lip and shadowed on top.
 */
private fun DrawScope.drawCapstanHole(cx: Float, cy: Float, t: TapeTheme) {
    val c = Offset(cx, cy)
    fun arc(color: Color, start: Float, sweep: Float, r: Float, w: Float) =
        drawArc(color, start, sweep, false, topLeft = Offset(cx - r, cy - r), size = Size(r * 2f, r * 2f), style = Stroke(w))
    // moulded depression around the bore: dark sink ring + a light lip below it
    drawCircle(Brush.radialGradient(listOf(Color(0x00000000), Color(0x4D000000)), center = c, radius = CAPSTAN_R * 1.6f), CAPSTAN_R * 1.6f, c)
    arc(t.bevelHi.copy(alpha = 0.5f), 25f, 130f, CAPSTAN_R * 1.62f, 0.3f)
    // the bore: interior falls to pure black, darker toward the top wall
    drawCircle(Brush.radialGradient(listOf(Color(0xFF000000), Color(0xFF16100A)), center = Offset(cx - 0.4f, cy - 1.0f), radius = CAPSTAN_R * 1.5f), CAPSTAN_R, c)
    // far (bottom) inner wall catching light down inside the bore
    arc(Color(0x59FFFFFF), 45f, 90f, CAPSTAN_R - 0.55f, 0.7f)
    arc(Color(0x26FFFFFF), 30f, 120f, CAPSTAN_R - 1.0f, 0.5f)
    // top inner edge: hard occlusion where the rim shades the bore
    arc(Color(0xC7000000), 195f, 150f, CAPSTAN_R - 0.35f, 0.65f)
    // chamfered rim: bright specular lip on the bottom, shadowed crest on top
    arc(t.bevelHi.copy(alpha = 0.95f), 20f, 140f, CAPSTAN_R, 0.4f)
    arc(Color(0x8C000000), 190f, 160f, CAPSTAN_R, 0.4f)
    // tiny specular glints at the 4-5 o'clock chamfer
    arc(Color(0xB3FFFFFF), 55f, 28f, CAPSTAN_R, 0.5f)
}

/** Premium-shell assembly screw: moulded boss, countersunk well, domed Phillips head with specular. */
private fun DrawScope.drawScrew(sx: Float, sy: Float, t: TapeTheme) {
    val c = Offset(sx, sy)
    drawCircle(t.engrave, 2.2f, c, style = Stroke(0.25f))                          // moulded boss ring
    drawCircle(Brush.radialGradient(listOf(Color(0x66000000), Color(0x22000000)), center = c, radius = 2.0f), 1.9f, c)   // countersink well
    // domed metal head, lit upper-left
    drawCircle(Brush.radialGradient(listOf(Color(0xFFEFF2F4), Color(0xFF9AA2A8), Color(0xFF565C60)), center = Offset(sx - 0.5f, sy - 0.5f), radius = 2.1f), 1.45f, c)
    drawCircle(Color(0xAA30363A), 1.45f, c, style = Stroke(0.18f))
    rotate(37f, c) {   // Phillips cross with depth: dark slot + lower-right light catch
        drawLine(Color(0xE0101314), Offset(sx - 1.0f, sy), Offset(sx + 1.0f, sy), 0.34f)
        drawLine(Color(0xE0101314), Offset(sx, sy - 1.0f), Offset(sx, sy + 1.0f), 0.34f)
        drawLine(Color(0x66FFFFFF), Offset(sx + 0.12f, sy + 0.12f), Offset(sx + 0.95f, sy + 0.12f), 0.12f)
    }
    drawArc(Color(0xB3FFFFFF), 205f, 70f, false, topLeft = Offset(sx - 1.15f, sy - 1.15f), size = Size(2.3f, 2.3f), style = Stroke(0.22f))  // specular crescent
}

/** Budget-shell melt weld: an irregular remelted dimple in the plastic instead of a screw. */
private fun DrawScope.drawMeltWeld(sx: Float, sy: Float, t: TapeTheme) {
    val c = Offset(sx, sy)
    // slightly raised, discolored remelt pool (two offset blobs = irregular)
    drawCircle(Brush.radialGradient(listOf(t.shellLo.copy(alpha = 0.95f), t.shellHi.copy(alpha = 0.4f)), center = Offset(sx - 0.3f, sy - 0.3f), radius = 1.7f), 1.45f, c)
    drawCircle(t.shellLo.copy(alpha = 0.55f), 1.0f, Offset(sx + 0.35f, sy + 0.25f))
    drawCircle(Color(0x59000000), 1.45f, c, style = Stroke(0.2f))                  // sink line around the pool
    drawCircle(Color(0x40000000), 0.45f, Offset(sx + 0.2f, sy + 0.15f))            // shrink pit
    drawArc(Color(0x4DFFFFFF), 200f, 85f, false, topLeft = Offset(sx - 1.05f, sy - 1.05f), size = Size(2.1f, 2.1f), style = Stroke(0.22f))  // gloss catch
}

/**
 * Text in mm-space via the native canvas. GOTCHA: Paint.textSize of ~2 units gets hinted/collapsed
 * by the glyph rasterizer before the canvas scale applies, which garbled every small label into
 * overlapping mush — so render at 10× size and scale back down around the anchor point.
 */
private fun DrawScope.mmText(
    s: String, x: Float, y: Float, sizeMm: Float, color: Color,
    bold: Boolean = true, center: Boolean = false, alignRight: Boolean = false,
    glowColor: Color? = null, glowRadiusMm: Float = 0.6f
) {
    val up = 10f
    val p = android.graphics.Paint().apply {
        this.color = color.toArgb(); textSize = sizeMm * up; isAntiAlias = true; isLinearText = true
        typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        textAlign = when { center -> android.graphics.Paint.Align.CENTER; alignRight -> android.graphics.Paint.Align.RIGHT; else -> android.graphics.Paint.Align.LEFT }
        if (glowColor != null) setShadowLayer(glowRadiusMm * up, 0f, 0f, glowColor.toArgb())
    }
    drawContext.canvas.nativeCanvas.apply {
        save(); translate(x, y); scale(1f / up, 1f / up); drawText(s, 0f, 0f, p); restore()
    }
}

/**
 * The elapsed/total counter, molded right into the tape window as a small inset LCD/LED strip —
 * behind the same clear plastic (glass streaks + frame draw over it, see the caller) as the reel
 * view, styled per the deck's own material: a warm glowing readout for clear/phosphor/neon shells,
 * a plain amber LCD-style readout for everything opaque.
 */
private fun DrawScope.drawWindowCounter(t: TapeTheme, text: String, winTL: Offset) {
    val glows = t.plasticType == PlasticType.PHOSPHOR_GLOW || t.plasticType == PlasticType.CLEAR_POLYCARBONATE || t.layout == TapeLayout.NEON
    val stripH = 4.0f
    val stripTL = Offset(winTL.x + 3f, winTL.y + WINDOW_H - stripH - 0.7f)
    val stripSize = Size(WINDOW_W - 6f, stripH)
    // Dark LCD/LED backing — a real small digital readout inset within the clear window pane.
    drawRoundRect(Color(0xD90A0A0A), topLeft = stripTL, size = stripSize, cornerRadius = CornerRadius(0.8f, 0.8f))
    drawRoundRect(Color(0x4D000000), topLeft = stripTL, size = stripSize, cornerRadius = CornerRadius(0.8f, 0.8f), style = Stroke(0.18f))
    val textColor = if (glows) t.accent else Color(0xFFE8C878)
    mmText(
        text,
        stripTL.x + stripSize.width / 2f,
        stripTL.y + stripSize.height * 0.74f,
        2.5f,
        textColor,
        center = true,
        glowColor = if (glows) t.accent else null
    )
}

/**
 * Where the transport mechanism is actually allowed to show through the shell. Clear/smoked
 * plastics are genuinely see-through so the whole cavity qualifies; every opaque shell has only
 * two real openings onto the tape path — the label window and the bottom-edge head/pinch-roller/
 * capstan/erase-notch cutouts (identical rects to the "bottom plate" pass in drawCassetteMm) — so
 * reel packs and ribbon can never bleed across solid plastic, screw bosses, or printed labels.
 */
private fun mechanismVisibilityPath(t: TapeTheme): Path {
    if (t.plasticType == PlasticType.CLEAR_POLYCARBONATE || t.plasticType == PlasticType.SMOKED_ACRYLIC) {
        return Path().apply {
            addRoundRect(RoundRect(WALL, WALL, SHELL_W - WALL, SHELL_H - WALL, CornerRadius(SHELL_R, SHELL_R)))
        }
    }
    val winTL = Offset(50.8f - WINDOW_W / 2f, HUB_Y - WINDOW_H / 2f)
    return Path().apply {
        addRoundRect(RoundRect(winTL.x, winTL.y, winTL.x + WINDOW_W, winTL.y + WINDOW_H, CornerRadius(2.5f, 2.5f)))  // label window
        addRoundRect(RoundRect(45.3f, 59.6f, 56.3f, SHELL_H, CornerRadius(0.8f, 0.8f)))   // head opening
        addRoundRect(RoundRect(34.6f, 60.2f, 43.1f, SHELL_H, CornerRadius(0.8f, 0.8f)))   // pinch roller L
        addRoundRect(RoundRect(58.5f, 60.2f, 67.0f, SHELL_H, CornerRadius(0.8f, 0.8f)))   // pinch roller R
        addOval(Rect(HUB_L_X - CAPSTAN_R, 60.6f - CAPSTAN_R, HUB_L_X + CAPSTAN_R, 60.6f + CAPSTAN_R))  // capstan L
        addOval(Rect(HUB_R_X - CAPSTAN_R, 60.6f - CAPSTAN_R, HUB_R_X + CAPSTAN_R, 60.6f + CAPSTAN_R))  // capstan R
        addRoundRect(RoundRect(18.9f, 61.1f, 24.4f, SHELL_H, CornerRadius(0.6f, 0.6f)))   // erase/azimuth notch L
        addRoundRect(RoundRect(77.2f, 61.1f, 82.7f, SHELL_H, CornerRadius(0.6f, 0.6f)))   // erase/azimuth notch R
    }
}

/**
 * Photorealistic Magnetic Tape Transport System:
 * Supply Reel (L), Take-up Reel (R), corner guide rollers, and an unbroken
 * contiguous magnetic ribbon in transit with procedural non-repeating oxide particles flowing
 * in the physically correct forward direction (Left Supply -> Head Mouth -> Right Take-up).
 */
private fun DrawScope.drawMagneticTapeTransport(
    progress: Float,
    angleL: Float,
    angleR: Float,
    tapeLinearDist: Float,
    t: TapeTheme
) {
    // 1) Physical tape volume conservation (C-90 spec: A_L + A_R = Constant)
    fun calcPackRadius(fill: Float): Float {
        val f = fill.coerceIn(0f, 1f)
        return sqrt(PACK_MIN_R * PACK_MIN_R + (PACK_MAX_R * PACK_MAX_R - PACK_MIN_R * PACK_MIN_R) * f)
    }
    val leftR = calcPackRadius(1f - progress)
    val rightR = calcPackRadius(progress)

    // 2) Guide roller centers and head mouth run
    val gL = Offset(GUIDE_L_X, GUIDE_Y)
    val gR = Offset(GUIDE_R_X, GUIDE_Y)
    val gL_bot = Offset(GUIDE_L_X, TAPE_RUN_Y)
    val gR_bot = Offset(GUIDE_R_X, TAPE_RUN_Y)

    // 3) Analytical external tangent calculations (Circle-to-Circle contact)
    val dxL = (GUIDE_L_X - HUB_L_X).toDouble()
    val dyL = (GUIDE_Y - HUB_Y).toDouble()
    val distL = sqrt(dxL * dxL + dyL * dyL)
    val uLx = dxL / distL
    val uLy = dyL / distL
    val nLx = -uLy
    val nLy = uLx
    val sinGammaL = ((leftR - GUIDE_ROLLER_R).toDouble() / distL).coerceIn(-1.0, 1.0)
    val cosGammaL = sqrt(1.0 - sinGammaL * sinGammaL)
    val tanNLx = cosGammaL * nLx + sinGammaL * uLx
    val tanNLy = cosGammaL * nLy + sinGammaL * uLy

    val pL = Offset((HUB_L_X + leftR * tanNLx).toFloat(), (HUB_Y + leftR * tanNLy).toFloat())
    val gL_tan = Offset((GUIDE_L_X + GUIDE_ROLLER_R * tanNLx).toFloat(), (GUIDE_Y + GUIDE_ROLLER_R * tanNLy).toFloat())

    val dxR = (GUIDE_R_X - HUB_R_X).toDouble()
    val dyR = (GUIDE_Y - HUB_Y).toDouble()
    val distR = sqrt(dxR * dxR + dyR * dyR)
    val uRx = dxR / distR
    val uRy = dyR / distR
    val nRx = uRy
    val nRy = -uRx
    val sinGammaR = ((rightR - GUIDE_ROLLER_R).toDouble() / distR).coerceIn(-1.0, 1.0)
    val cosGammaR = sqrt(1.0 - sinGammaR * sinGammaR)
    val tanNRx = cosGammaR * nRx + sinGammaR * uRx
    val tanNRy = cosGammaR * nRy + sinGammaR * uRy

    val pR = Offset((HUB_R_X + rightR * tanNRx).toFloat(), (HUB_Y + rightR * tanNRy).toFloat())
    val gR_tan = Offset((GUIDE_R_X + GUIDE_ROLLER_R * tanNRx).toFloat(), (GUIDE_Y + GUIDE_ROLLER_R * tanNRy).toFloat())

    // 4) Corner tape-guide roller base flanges
    for (g in listOf(gL, gR)) {
        drawCircle(Color(0x44000000), GUIDE_ROLLER_R + 0.4f, Offset(g.x + 0.25f, g.y + 0.35f))
        drawCircle(Brush.radialGradient(listOf(t.hubHi, t.hubLo), center = Offset(g.x - 0.8f, g.y - 0.8f), radius = GUIDE_ROLLER_R * 1.6f), GUIDE_ROLLER_R, g)
        drawCircle(t.hubLo, GUIDE_ROLLER_R, g, style = Stroke(0.25f))
    }

    // 5) In-Transit Magnetic Ribbon Path (drawn BEFORE the reels, not after — see the reel pass
    // below). The ribbon's drawn ends actually dive a bit past pL/pR, back INTO the reel pack
    // (pLu/pRu, pulled inward along the same tangent), so once the reels paint on top of this,
    // their own edge naturally buries that stroke's square line-cap instead of it sitting exposed
    // on top of the pack — no more visible "squared ending" where transit tape meets the spool.
    val reelDive = 1.8f
    val pLu = Offset((HUB_L_X + (leftR - reelDive).coerceAtLeast(2f) * tanNLx).toFloat(), (HUB_Y + (leftR - reelDive).coerceAtLeast(2f) * tanNLy).toFloat())
    val pRu = Offset((HUB_R_X + (rightR - reelDive).coerceAtLeast(2f) * tanNRx).toFloat(), (HUB_Y + (rightR - reelDive).coerceAtLeast(2f) * tanNRy).toFloat())
    val ribbonPath = Path().apply {
        moveTo(pLu.x, pLu.y)
        lineTo(gL_tan.x, gL_tan.y)
        quadraticBezierTo(GUIDE_L_X - GUIDE_ROLLER_R, TAPE_RUN_Y, gL_bot.x, gL_bot.y)
        lineTo(gR_bot.x, gR_bot.y)
        quadraticBezierTo(GUIDE_R_X + GUIDE_ROLLER_R, TAPE_RUN_Y, gR_tan.x, gR_tan.y)
        lineTo(pRu.x, pRu.y)
    }

    // Pass 1: Underneath ambient shadow for real 3D ribbon depth
    drawPath(ribbonPath, Color(0x8A000000), style = Stroke(1.6f))

    // Pass 2: Base Ferric/Chrome Oxide Magnetic Ribbon. Lifted noticeably brighter than the reel's
    // own darkest gradient stop — a real oxide ribbon under any ambient light reads as a lighter,
    // warmer strip than the shadowed wound pack, and needs that contrast to stay legible against
    // busy backdrops (clear-shell themes ghost album art straight through). Alpha ramps from 0 near
    // both ends (the 0f/1f stops) up to full within the first ~5% — a soft fade "stretch" so the
    // ribbon tapers into invisibility as it dives under the reel (reelDive above) instead of ending
    // on a hard, realism-breaking cap, even if the reel's own edge doesn't fully cover it.
    val ribbonEdgeTone = Color(0xFF6B4426)
    val ribbonMidTone = Color(0xFF7A4F2C)
    val ribbonBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to ribbonEdgeTone.copy(alpha = 0f),
            0.05f to ribbonEdgeTone,
            0.5f to ribbonMidTone,
            0.95f to ribbonEdgeTone,
            1f to ribbonEdgeTone.copy(alpha = 0f)
        ),
        start = pL, end = pR
    )
    drawPath(ribbonPath, ribbonBrush, style = Stroke(1.15f))
    drawPath(ribbonPath, Color(0x80281808), style = Stroke(0.8f))

    // Pass 3: Longitudinal Specular Edge Sheen Highlight
    val sheenPath = Path().apply {
        moveTo(pLu.x - 0.12f, pLu.y)
        lineTo(gL_tan.x - 0.12f, gL_tan.y)
        quadraticBezierTo(GUIDE_L_X - GUIDE_ROLLER_R - 0.12f, TAPE_RUN_Y - 0.12f, gL_bot.x, TAPE_RUN_Y - 0.12f)
        lineTo(gR_bot.x, TAPE_RUN_Y - 0.12f)
        quadraticBezierTo(GUIDE_R_X + GUIDE_ROLLER_R + 0.12f, TAPE_RUN_Y - 0.12f, gR_tan.x + 0.12f, gR_tan.y)
        lineTo(pRu.x + 0.12f, pRu.y)
    }
    val sheenBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color(0x00FFECC8),
            0.05f to Color(0x8CFFECC8),
            0.95f to Color(0x8CFFECC8),
            1f to Color(0x00FFECC8)
        ),
        start = pL, end = pR
    )
    drawPath(sheenPath, sheenBrush, style = Stroke(0.28f))

    // Pass 4: Forward-Flowing Procedural Non-Repeating Oxide Particles. Uses the SAME tapeHash()
    // the reel's oxide flecks are seeded from (not a locally-redefined lookalike) so the grain
    // reads as one continuous material's noise field crossing the reel/ribbon join, not two
    // different generators that happen to look similar.
    //
    // Walks the ACTUAL ribbonPath via PathMeasure instead of three hand-rolled straight-line
    // segments. The old three-segment version treated the corners at each guide roller as sharp
    // straight-to-straight joins, but the real path rounds them with a quadraticBezierTo — so the
    // dust, placed on the straight-line approximation, visibly drifted off the true (curved)
    // stroke right where it wraps the rollers. Measuring the real path guarantees the dust can
    // never separate from the tape it's sitting on, at the corners or anywhere else.
    val deterministicHash = ::tapeHash
    val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(ribbonPath, false) }
    val totalLen = measure.length
    var s = 0.4f
    while (s < totalLen - 0.4f) {
        // Forward travel: subtract tapeLinearDist so points on tape move along increasing s
        val globalPos = s - tapeLinearDist
        val bucket = kotlin.math.floor(globalPos / 1.35f)
        val hLength = deterministicHash(bucket, 3.14f)
        val hLateral = deterministicHash(bucket, 7.89f)
        val hAlpha = deterministicHash(bucket, 13.57f)
        val hColor = deterministicHash(bucket, 23.41f)

        val macroWave = 0.5f + 0.5f * kotlin.math.sin((globalPos * 0.18f).toDouble()).toFloat()
        val streakLen = 0.4f + hLength * 1.6f
        val latOffset = (hLateral - 0.5f) * 0.45f
        val alpha = (0.12f + 0.45f * hAlpha) * (0.5f + 0.5f * macroWave)
        val color = if (hColor > 0.6f) Color(0xFFFFECC8) else if (hColor > 0.3f) Color(0xFFE8DAC4) else Color(0xFFCCA878)

        val pos = measure.getPosition(s)
        val tan = measure.getTangent(s)
        val dirX = tan.x; val dirY = tan.y
        val normX = -dirY; val normY = dirX
        val cx = pos.x + normX * latOffset
        val cy = pos.y + normY * latOffset
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(cx - dirX * (streakLen / 2f), cy - dirY * (streakLen / 2f)),
            end = Offset(cx + dirX * (streakLen / 2f), cy + dirY * (streakLen / 2f)),
            strokeWidth = 0.18f + 0.10f * hAlpha
        )
        s += 1.1f + hLength * 0.4f
    }

    // 6) Guide Roller Center Steel Pins (drawn directly over the wrapped tape for precision mechanical look)
    for (g in listOf(gL, gR)) {
        drawCircle(Color(0x33000000), 1.1f, g)
        drawCircle(Color(0xFF1A1510), 0.75f, g)
        drawCircle(Color(0x66FFFFFF), 0.35f, Offset(g.x - 0.2f, g.y - 0.2f))
    }

    // 7) Reels drawn LAST, on top of the ribbon's buried ends (see reelDive above) — the pack's
    // own edge covers that overlap so the transit tape visibly disappears into the wound pack
    // instead of butting up against it with a hard-edged seam.
    drawReel(HUB_L_X, HUB_Y, leftR, angleL, t)
    drawReel(HUB_R_X, HUB_Y, rightR, angleR, t)
}

/** Cheap deterministic hash shared by the reel pack and the in-transit ribbon so both read as one
 *  continuous wound material instead of two different noise "dialects" meeting at a seam. */
private fun tapeHash(u: Float, seed: Float): Float {
    val x = u * 12.9898f + seed * 78.233f
    val sinVal = kotlin.math.sin(x.toDouble()).toFloat() * 43758.5453f
    return sinVal - kotlin.math.floor(sinVal)
}

/**
 * Cached, angle-independent reel-pack geometry (the spirals + oxide flecks). The wound-pack shape
 * only depends on cx/cy/tapeR — `angle` is applied afterward as a cheap matrix rotate() around it,
 * never baked into the points — so re-deriving ~1000+ trig'd path points and ~150-250 hashed fleck
 * positions from scratch every single animation frame (30fps × 2 reels) was pure wasted CPU/battery
 * for output that's IDENTICAL to the previous frame's. Cached per (reel, tapeR bucket) instead;
 * tapeR only actually changes while the user seeks, so in steady playback this is built once and
 * reused every frame after. Pure perf win — same geometry, same colors/alphas/widths, unchanged.
 */
private data class FleckSpec(val start: Offset, val end: Offset, val alpha: Float, val width: Float)
private data class ReelGeometry(val spiral1: Path, val spiral2: Path, val flecks: List<FleckSpec>)
private val reelGeometryCache = HashMap<Pair<Float, Int>, ReelGeometry>()

private fun buildReelGeometry(cx: Float, cy: Float, tapeR: Float, packDepth: Float): ReelGeometry {
    val numTurns = (packDepth * 2.6f).coerceIn(4f, 36f)
    val totalRad = numTurns * 2f * Math.PI.toFloat()
    val steps = (numTurns * 28).toInt()

    // Primary continuous spiral winding groove. Real wound tape isn't a mathematically perfect
    // spiral — winding tension varies turn to turn, so the pitch/radius gets a slow, non-repeating
    // wobble (two incommensurate frequencies, seeded per-reel by cx/cy so the two reels never
    // wobble in lockstep) instead of a pure r = a + bθ curve.
    val wobbleSeed = cx * 0.37f + cy * 0.71f
    fun woundR(t: Float, rad: Float): Float {
        val base = HUB_R + 0.35f + t * (packDepth - 0.35f)
        val wobble = 0.06f * packDepth * (
            kotlin.math.sin(rad * 0.9f + wobbleSeed) * kotlin.math.sin(rad * 0.211f + wobbleSeed * 1.7f) +
            0.4f * kotlin.math.sin(rad * 2.03f - wobbleSeed)
        )
        return base + wobble
    }
    val spiral1 = Path()
    var first = true
    for (i in 0..steps) {
        val ft = i.toFloat() / steps.toFloat()
        val rad = ft * totalRad
        val curR = woundR(ft, rad)
        val px = cx + curR * cos(rad)
        val py = cy + curR * sin(rad)
        if (first) { spiral1.moveTo(px, py); first = false } else spiral1.lineTo(px, py)
    }

    // Secondary interleaved shadow groove. Offset by a hashed phase per reel (not an exact π
    // half-turn) so the pack doesn't read as a perfectly 2-fold-symmetric print.
    val phase2 = Math.PI.toFloat() * (0.85f + 0.3f * tapeHash(wobbleSeed, 5.2f))
    val spiral2 = Path()
    first = true
    for (i in 0..steps) {
        val ft = i.toFloat() / steps.toFloat()
        val rad = phase2 + ft * totalRad
        val curR = woundR(ft, rad)
        val px = cx + curR * cos(rad)
        val py = cy + curR * sin(rad)
        if (first) { spiral2.moveTo(px, py); first = false } else spiral2.lineTo(px, py)
    }

    // Procedural oxide flecks across the wound tape face. Angle, radius, size and alpha are all
    // hash-jittered per fleck (seeded on ring+k, so it's stable frame to frame, just not laid out
    // on a perfectly even grid) to avoid the "printed texture" look a uniform ring of identical
    // marks gives when it spins as one rigid pattern.
    val flecks = ArrayList<FleckSpec>()
    for (ring in 1..6) {
        val ringR = HUB_R + 0.8f + (packDepth - 1.2f) * (ring.toFloat() / 7f)
        val numFlecks = (ringR * 1.8f).toInt()
        for (k in 0 until numFlecks) {
            val seed = ring * 17.3f + k * 3.11f
            val jitterA = (tapeHash(seed, 1.1f) - 0.5f) * (2f * Math.PI.toFloat() / numFlecks) * 0.9f
            val fAngle = (k.toFloat() / numFlecks) * 2f * Math.PI.toFloat() + (ring * 1.618f) + jitterA
            val rJitter = (tapeHash(seed, 9.4f) - 0.5f) * 0.5f
            val fx = cx + (ringR + rJitter) * cos(fAngle)
            val fy = cy + (ringR + rJitter) * sin(fAngle)
            val fleckLen = 0.22f + tapeHash(seed, 4.6f) * 0.45f
            val fAlpha = 0.08f + tapeHash(seed, 12.2f) * 0.24f
            flecks.add(
                FleckSpec(
                    Offset(fx - sin(fAngle) * (fleckLen / 2f), fy + cos(fAngle) * (fleckLen / 2f)),
                    Offset(fx + sin(fAngle) * (fleckLen / 2f), fy - cos(fAngle) * (fleckLen / 2f)),
                    fAlpha, 0.14f + tapeHash(seed, 17.8f) * 0.08f
                )
            )
        }
    }
    return ReelGeometry(spiral1, spiral2, flecks)
}

/** One reel at spec size: wound pack with continuous Archimedean spirals, hub with hole ring, splined spindle. */
private fun DrawScope.drawReel(cx: Float, cy: Float, tapeR: Float, angle: Float, t: TapeTheme) {
    val c = Offset(cx, cy)
    // 1) Soft ambient drop shadow under the winding pack
    drawCircle(Color(0x66000000), tapeR + 0.6f, Offset(cx + 0.45f, cy + 0.65f))

    // 2) Photorealistic magnetic ferric/chrome oxide tape pancake
    if (tapeR > HUB_R) {
        // Base deep magnetic tape body
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF332014), Color(0xFF1E130B), Color(0xFF0F0A06)),
                center = Offset(cx - tapeR * 0.25f, cy - tapeR * 0.25f),
                radius = tapeR * 1.35f
            ),
            tapeR,
            c
        )

        // Real Archimedean spiral tape winding layers: r(theta) = HUB_R + k * theta
        // Rotated smoothly via GPU matrix transformation without vertex snapping or jumps.
        // Geometry itself is cached (buildReelGeometry) — angle-independent, so it's only rebuilt
        // when tapeR actually moves (seeking), not every frame.
        val packDepth = tapeR - HUB_R
        if (packDepth > 0.4f) {
            val bucket = Math.round(tapeR * 20f)  // ~0.05mm cache resolution
            val geo = reelGeometryCache.getOrPut(cx to bucket) { buildReelGeometry(cx, cy, tapeR, packDepth) }
            rotate(angle, c) {
                drawPath(geo.spiral1, Color(0x38E0D4C0), style = Stroke(0.18f))
                drawPath(geo.spiral2, Color(0x44000000), style = Stroke(0.16f))
                for (f in geo.flecks) {
                    drawLine(Color(0xFFFFECC8).copy(alpha = f.alpha), f.start, f.end, f.width)
                }
            }
        }

        // Physically accurate Anisotropic Specular Sheen (Stationary Overhead Light Field).
        // Wound tape oxide is a matte/semi-diffuse surface, not a mirror — it shouldn't strobe
        // sharply twice per revolution the way a single clean sin(2·angle) term does. `angle` wraps
        // mod 360°, so only INTEGER harmonics of it stay continuous through that wrap (a fractional
        // multiplier would pop at every wrap — the exact "jumping" artifact this is fixing); summing
        // three low-amplitude integer harmonics at different phases breaks up the one hard flash into
        // a softer, less mechanically-obvious shimmer while staying perfectly smooth through the wrap.
        val angleRad = Math.toRadians(angle.toDouble()).toFloat()
        val shimmer = 1.0f + 0.035f * kotlin.math.sin(angleRad) + 0.025f * kotlin.math.sin(2f * angleRad + 1.3f) + 0.02f * kotlin.math.sin(3f * angleRad - 0.7f)
        val sheenR = (HUB_R + tapeR) / 2f

        // Primary top-left ambient specular reflection lobe
        drawArc(
            Color(0x28FFECC8).copy(alpha = 0.18f * shimmer),
            startAngle = 125f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(cx - sheenR, cy - sheenR),
            size = Size(sheenR * 2f, sheenR * 2f),
            style = Stroke(width = packDepth * 0.85f)
        )

        // Secondary bottom-right fill reflection lobe
        drawArc(
            Color(0x18FFFFFF).copy(alpha = 0.12f * shimmer),
            startAngle = 305f,
            sweepAngle = 65f,
            useCenter = false,
            topLeft = Offset(cx - sheenR, cy - sheenR),
            size = Size(sheenR * 2f, sheenR * 2f),
            style = Stroke(width = packDepth * 0.70f)
        )
    }

    // 3) Hub itself (moulded plastic reel core)
    rotate(angle, c) {
        // Hub body with molded 3D radial lighting
        drawCircle(
            Brush.radialGradient(
                listOf(t.hubHi, t.hubLo),
                center = Offset(cx - HUB_R * 0.35f, cy - HUB_R * 0.35f),
                radius = HUB_R * 1.5f
            ),
            HUB_R,
            c
        )
        drawCircle(t.hubLo, HUB_R, c, style = Stroke(0.3f))

        // Ring of 14 friction/reduction holes around hub face
        for (k in 0 until 14) {
            val a = Math.toRadians(k * (360.0 / 14))
            val hc = Offset(cx + (8.1f * cos(a)).toFloat(), cy + (8.1f * sin(a)).toFloat())
            drawCircle(Color(0x40000000), 0.72f, hc + Offset(0.1f, 0.1f))
            drawCircle(t.holes, 0.62f, hc)
        }

        // Splined spindle opening: the 8.5 mm drive hole with 6 reinforced teeth
        drawCircle(Color(0xFF0D0A08), SPINDLE_R + 1.2f, c)
        drawCircle(Color(0xFFE8E5DC), SPINDLE_R, c)
        drawCircle(Color(0x40000000), SPINDLE_R, c, style = Stroke(0.3f))
        for (tooth in 0 until 6) {
            val a = Math.toRadians((tooth * 60).toDouble())
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(Color(0xFF0D0A08), c + dir * (SPINDLE_R - 1.6f), c + dir * SPINDLE_R, strokeWidth = 1.6f)
            drawLine(Color(0x44FFFFFF), c + dir * (SPINDLE_R - 1.4f) + Offset(0.1f, 0.1f), c + dir * (SPINDLE_R - 0.2f), strokeWidth = 0.6f)
        }
    }
}

private fun fmt(ms: Long): String { val sec = (ms / 1000).coerceAtLeast(0); return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}" }

@Composable
private fun BespokeTapeBranding(theme: TapeTheme, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.wrapContentWidth(unbounded = true),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (theme.brandTexture) {
            BrandTexture.DEBOSSED_ENAMEL -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = Color(0xAA000000),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.8.dp, y = 0.8.dp)
                    )
                    Text(
                        theme.brandText,
                        color = theme.brandColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            BrandTexture.OFFSET_PRINT_INK -> {
                // Crisp offset printed ink directly on paper sticker / shell (no plastic deboss)
                Text(
                    theme.brandText,
                    color = theme.brandColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    fontFamily = theme.brandFont
                )
            }
            BrandTexture.HOT_STAMP_GOLD -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = Color(0x66000000),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.5.dp, y = 0.5.dp)
                    )
                    Text(
                        theme.brandText,
                        color = theme.brandColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            BrandTexture.HOT_STAMP_CHROME -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = Color(0x55000000),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.5.dp, y = 0.5.dp)
                    )
                    Text(
                        theme.brandText,
                        color = theme.brandColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            BrandTexture.DISCO_INLINE_NEON -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = theme.brandColor.copy(alpha = 0.45f),
                        fontSize = 13.5.sp,
                        letterSpacing = 3.sp,
                        fontFamily = theme.brandFont
                    )
                    Text(
                        theme.brandText,
                        color = theme.brandColor,
                        fontSize = 12.5.sp,
                        letterSpacing = 3.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            BrandTexture.DOT_MATRIX_IMPRINT -> {
                Text(
                    theme.brandText,
                    color = theme.brandColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = theme.brandFont
                )
            }
            BrandTexture.JAPANESE_KAWAII -> {
                Text(
                    theme.brandText,
                    color = theme.brandColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = theme.brandFont
                )
            }
            BrandTexture.MINIMAL_LASER_ETCH -> {
                Text(
                    theme.brandText,
                    color = theme.brandColor.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    fontFamily = theme.brandFont
                )
            }
            BrandTexture.SCREENPRINT_NEON -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = Color(0x40000000),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.5.dp, y = 0.5.dp)
                    )
                    Text(
                        theme.brandText,
                        color = theme.brandColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
        }
        Text(
            theme.brandSub,
            color = theme.brandSubColor,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            fontFamily = if (theme.brandTexture == BrandTexture.DOT_MATRIX_IMPRINT) DotGothicFont else FontFamily.Monospace,
            modifier = Modifier.padding(top = 1.5.dp)
        )
    }
}

/**
 * Bare transport glyph (rewind/play/fast-forward/shuffle) — deliberately NO circular button
 * chrome and no default ripple indication behind it; instead a soft radial glow in the deck's own
 * bevel/accent color sits behind the icon, ambient at rest and warming up while `glowBoost` is
 * true (a flash tap, or steady while playing/shuffle is on) — a bespoke, per-theme affectation
 * instead of a generic circular tap target.
 */
@Composable
private fun TapeGlyphButton(
    icon: ImageVector,
    contentDescription: String,
    theme: TapeTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 30.dp,
    tint: Color = theme.bevelHi.copy(alpha = 0.95f),
    glowBoost: Boolean = false
) {
    val glowAlpha by animateFloatAsState(if (glowBoost) 0.55f else 0.18f, animationSpec = tween(280), label = "glyphGlow")
    val glowColor = theme.accent
    Box(
        modifier = modifier
            .size(size * 1.5f)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(listOf(glowColor.copy(alpha = glowAlpha), Color.Transparent)),
                    radius = this.size.minDimension / 2f
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size))
    }
}

/**
 * Ambient per-theme "affectation" glow that sits behind the molded circular buttons (exit,
 * playlist, heart) without touching their existing melted-into-plastic look — just a soft halo
 * in the deck's accent color, tuned by the theme's actual material so it reads as bespoke rather
 * than one generic effect on every deck: phosphor shells breathe like real subsurface phosphor
 * glow, metallic-flake shells get a slower, tighter glint, everything else a faint static hint.
 */
@Composable
private fun rememberMoldedGlowAlpha(theme: TapeTheme): Float {
    val transition = rememberInfiniteTransition(label = "moldedGlow")
    val periodMs = if (theme.plasticType == PlasticType.METALLIC_FLAKE) 4200 else 2600
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "moldedGlowPulse"
    )
    return when (theme.plasticType) {
        PlasticType.PHOSPHOR_GLOW -> 0.22f + 0.18f * pulse
        PlasticType.METALLIC_FLAKE -> 0.09f + 0.07f * pulse
        else -> 0.07f + 0.04f * pulse
    }
}

/**
 * Molded cassette shell control button: flush circular depression that seamlessly melts into
 * the cassette's textured plastic casing, with debossed engraving and subtle bevel lighting.
 */
@Composable
private fun MoldedTapeButton(
    icon: ImageVector,
    contentDescription: String,
    theme: TapeTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glowAlpha = rememberMoldedGlowAlpha(theme)
    Box(
        modifier = modifier
            .size(42.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(listOf(theme.accent.copy(alpha = glowAlpha), Color.Transparent)),
                    radius = this.size.minDimension / 2f
                )
            },
        contentAlignment = Alignment.Center
    ) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(
                Brush.radialGradient(
                    listOf(
                        theme.shellLo.copy(alpha = 0.95f),
                        theme.shellHi.copy(alpha = 0.40f)
                    )
                )
            )
            .border(
                width = 0.75.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        theme.bevelLo.copy(alpha = 0.85f),
                        theme.bevelHi.copy(alpha = 0.55f)
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Debossed icon shadow for 3D recessed molded plastic look
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0x99000000),
            modifier = Modifier
                .size(16.dp)
                .offset(x = 0.6.dp, y = 0.6.dp)
        )
        // Main molded engraved icon
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = theme.engrave.copy(alpha = 0.95f),
            modifier = Modifier
                .size(16.dp)
        )
    }
    }
}

/**
 * Molded rainbow heart button for Tape Mode:
 * Flushed into a circular depression molded directly into the cassette shell.
 * When unliked: shows a subtle debossed engraved heart outline in the plastic casing.
 * When liked: illuminates with a liquid flowing chromatic rainbow gradient and subtle organic pulse.
 */
@Composable
private fun MoldedRainbowHeart(
    liked: Boolean,
    theme: TapeTheme,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var phase by remember { mutableStateOf(0f) }
    var beat by remember { mutableStateOf(0f) }

    LaunchedEffect(liked) {
        if (!liked) return@LaunchedEffect
        // Same idle gate as NowPlaying.kt's TieredRainbowHeart — pure decorative pulse, not worth
        // running while the screen isn't actively shown.
        while (true) {
            if (IdleController.screenActive) {
                phase = (phase + 2.5f) % 360f
                beat = (beat + 0.014f) % 1f
                delay(16)
            } else delay(500)
        }
    }

    fun heartbeat(x: Float): Float {
        fun bump(c: Float, wdt: Float) = kotlin.math.exp((-((x - c) * (x - c)) / (2 * wdt * wdt)).toDouble()).toFloat()
        return bump(0.10f, 0.040f) + 0.6f * bump(0.26f, 0.05f)
    }

    val pulse = if (liked) 1f + 0.06f * heartbeat(beat) else 1f
    // The liked state already has its own vivid rainbow affectation — the ambient per-theme glow
    // only kicks in while unliked, so it complements rather than competes with the rainbow pulse.
    val glowAlpha = rememberMoldedGlowAlpha(theme)

    Box(
        modifier = modifier
            .size(42.dp)
            .drawBehind {
                if (!liked) drawCircle(
                    brush = Brush.radialGradient(listOf(theme.accent.copy(alpha = glowAlpha), Color.Transparent)),
                    radius = this.size.minDimension / 2f
                )
            },
        contentAlignment = Alignment.Center
    ) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = if (liked) "Unlike" else "Like"
                role = Role.Checkbox
                toggleableState = androidx.compose.ui.state.ToggleableState(liked)
            }
            .clickable {
                Haptics.tick(ctx)
                onToggle()
            }
            .background(
                Brush.radialGradient(
                    listOf(
                        theme.shellLo.copy(alpha = 0.95f),
                        theme.shellHi.copy(alpha = 0.40f)
                    )
                )
            )
            .border(
                width = 0.75.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        theme.bevelLo.copy(alpha = 0.85f),
                        theme.bevelHi.copy(alpha = 0.55f)
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(17.dp).scale(pulse)) {
            val w = size.width
            val h = size.height
            val hp = Path().apply {
                moveTo(0.5f * w, 0.86f * h)
                cubicTo(0.34f * w, 0.72f * h, 0.06f * w, 0.54f * h, 0.06f * w, 0.31f * h)
                cubicTo(0.06f * w, 0.11f * h, 0.33f * w, 0.06f * h, 0.5f * w, 0.27f * h)
                cubicTo(0.67f * w, 0.06f * h, 0.94f * w, 0.11f * h, 0.94f * w, 0.31f * h)
                cubicTo(0.94f * w, 0.54f * h, 0.66f * w, 0.72f * h, 0.5f * w, 0.86f * h)
                close()
            }

            if (liked) {
                // Drop shadow inside the recessed mold well
                drawPath(hp, Color(0x66000000), style = Stroke(2.0f))

                // Flowing multi-stop rainbow spectrum
                val cols = (0..6).map { Color.hsv(((it * 52) + phase) % 360f, 0.90f, 1f) }
                drawPath(hp, Brush.linearGradient(cols, Offset(0f, h), Offset(w, 0f)))

                // Glossy surface specular reflection
                drawPath(hp, Color.White.copy(alpha = 0.20f))
                drawPath(hp, Color.White.copy(alpha = 0.50f), style = Stroke(0.8f))
            } else {
                // Debossed engraved unliked heart outline melted into plastic
                drawPath(hp, Color(0x66000000), style = Stroke(1.2f))
                drawPath(hp, theme.engrave.copy(alpha = 0.65f), style = Stroke(0.85f))
            }
        }
    }
    }
}

// ── Photorealistic Masking Tape Textures ──
private data class MaskingTapeStyle(
    val name: String,
    val paperBaseHi: Color,
    val paperBaseLo: Color,
    val ridgeColor: Color,
    val penColor: Color,
)

// A soft round grime blotch (thumb grease, general age/dust) on the tape's paper surface.
private data class GrimeSpot(val xFrac: Float, val yFrac: Float, val radiusPx: Float, val alpha: Float)
// A thin directional rub/scuff mark — the kind of faint streak a tape picks up sliding in and out
// of a case for years.
private data class SmudgeStreak(val yFrac: Float, val angleDeg: Float, val lenFrac: Float, val alpha: Float, val widthPx: Float)
// An ink smear sitting on top of the handwriting itself — a thumb dragged across still-wet marker,
// or just decades of handling. Drawn last, over the text, not just the paper underneath it.
private data class InkSmear(val xFrac: Float, val yFrac: Float, val wPx: Float, val hPx: Float, val angleDeg: Float, val alpha: Float)

private val MASKING_TAPE_STYLES = listOf(
    MaskingTapeStyle("Scotch Tan", Color(0xFFF7EED8), Color(0xFFE5D5B2), Color(0x228A6E3D), Color(0xFF14141E)),
    MaskingTapeStyle("Vintage Ivory", Color(0xFFFAF2E4), Color(0xFFDDD0B5), Color(0x25705A32), Color(0xFF1E2838)),
    MaskingTapeStyle("Off-White Draft", Color(0xFFFAFAFA), Color(0xFFEAE6DF), Color(0x1C606060), Color(0xFF0F1E36)),
    MaskingTapeStyle("Aqua Painter", Color(0xFFDCF5F2), Color(0xFFB8E8E3), Color(0x2224857D), Color(0xFF0C2B2E)),
    MaskingTapeStyle("Artist Cream", Color(0xFFFFF8EA), Color(0xFFF0E2C6), Color(0x208A744A), Color(0xFF281C10))
)

@Composable
private fun PhotorealisticMaskingTapeLabel(
    track: Track,
    modifier: Modifier = Modifier
) {
    val seed = remember(track.id) { kotlin.random.Random(track.id) }
    val style = remember(track.id) { MASKING_TAPE_STYLES[seed.nextInt(MASKING_TAPE_STYLES.size)] }
    val tearJitter = remember(track.id) {
        val r = kotlin.random.Random(track.id + 100)
        FloatArray(16) { r.nextFloat() * 4f - 2f }
    }
    // Each end torn independently, with its own random tooth count/spacing/depth — real deckled
    // tears never match each other or repeat a clean zigzag.
    val rightEdge = remember(track.id) { randomTornEdge(kotlin.random.Random(track.id + 300)) }
    val leftEdge = remember(track.id) { randomTornEdge(kotlin.random.Random(track.id + 400)) }
    // A handful of stray paper-fiber wisps poking out past the tear — the fuzzy give-away of torn
    // (not cut) paper.
    val fiberWisps = remember(track.id) {
        val r = kotlin.random.Random(track.id + 500)
        List(6) { FiberWisp(side = r.nextInt(2), yFrac = r.nextFloat(), lenPx = 0.5f + r.nextFloat() * 1.1f, angleDeg = r.nextFloat() * 50f - 25f) }
    }
    val rotation = remember(track.id) { seed.nextFloat() * 5f - 2.5f }

    // Not every tape is fresh — real masking tape picks up thumb grease, dust, rub marks from
    // sliding in and out of a case, and an ink smear where someone's hand dragged across the
    // marker before it fully dried. Rolled once per track (its own seed offset, independent of
    // style/tear/rotation) so it's a per-track coin flip, not tied to any other visual choice.
    val dirty = remember(track.id) { kotlin.random.Random(track.id + 700).nextFloat() < 0.24f }
    val grimeSpots = remember(track.id) {
        if (!dirty) emptyList() else {
            val r = kotlin.random.Random(track.id + 800)
            List(2 + r.nextInt(3)) {
                GrimeSpot(
                    xFrac = 0.08f + r.nextFloat() * 0.84f,
                    yFrac = 0.1f + r.nextFloat() * 0.8f,
                    radiusPx = 7f + r.nextFloat() * 16f,
                    alpha = 0.08f + r.nextFloat() * 0.14f
                )
            }
        }
    }
    val smudgeStreaks = remember(track.id) {
        if (!dirty) emptyList() else {
            val r = kotlin.random.Random(track.id + 900)
            List(1 + r.nextInt(2)) {
                SmudgeStreak(
                    yFrac = 0.15f + r.nextFloat() * 0.7f,
                    angleDeg = r.nextFloat() * 26f - 13f,
                    lenFrac = 0.3f + r.nextFloat() * 0.4f,
                    alpha = 0.07f + r.nextFloat() * 0.09f,
                    widthPx = 2.5f + r.nextFloat() * 3f
                )
            }
        }
    }
    // One smear sitting on TOP of the handwriting itself, not just the paper underneath it — the
    // "thumb dragged across wet marker" mark. Roughly targets where the title line sits.
    val inkSmear = remember(track.id) {
        if (!dirty) null else {
            val r = kotlin.random.Random(track.id + 950)
            if (r.nextFloat() < 0.55f) null else InkSmear(
                xFrac = 0.2f + r.nextFloat() * 0.5f,
                yFrac = 0.28f + r.nextFloat() * 0.2f,
                wPx = 40f + r.nextFloat() * 50f,
                hPx = 10f + r.nextFloat() * 8f,
                angleDeg = r.nextFloat() * 20f - 10f,
                alpha = 0.10f + r.nextFloat() * 0.10f
            )
        }
    }

    Box(
        modifier = modifier
            .rotate(90f + rotation)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            if (w <= 0f || h <= 0f) return@Canvas

            // Generate deckled torn tape path with ripped paper fiber ends — each end its own
            // randomized tooth count/spacing/depth (see randomTornEdge), never a clean zigzag.
            val tapePath = Path().apply {
                moveTo(6f + tearJitter[0], 0f)
                lineTo(w - 6f + tearJitter[1], 0f)

                // Right torn end (deckled jagged tear, top -> bottom)
                for (step in rightEdge) lineTo(w - 3f + step.depthPx, h * step.yFrac)

                lineTo(w - 6f + tearJitter[2], h)
                lineTo(6f + tearJitter[3], h)

                // Left torn end (deckled jagged tear, bottom -> top)
                for (step in leftEdge.asReversed()) lineTo(3f + step.depthPx, h * step.yFrac)
                close()
            }

            // 1. Ambient Drop Shadow underneath tape
            val shadowPath = Path().apply {
                addPath(tapePath, Offset(1.8f, 2.8f))
            }
            drawPath(shadowPath, Color(0x3B000000))

            // 2. Translucent Crepe Paper Base Fill
            val paperBrush = Brush.linearGradient(
                colors = listOf(style.paperBaseHi.copy(alpha = 0.95f), style.paperBaseLo.copy(alpha = 0.93f)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            drawPath(tapePath, paperBrush)

            // 3. Crepe Paper Micro-Ridges (horizontal texture lines)
            var ry = 2.5f
            while (ry < h - 2f) {
                drawLine(
                    color = style.ridgeColor,
                    start = Offset(4f, ry),
                    end = Offset(w - 4f, ry),
                    strokeWidth = 0.65f
                )
                ry += 1.8f
            }

            // 4. Paper Fibers & Surface Grain Flecks
            for (f in 0 until 10) {
                val fx = 8f + (w - 16f) * ((f * 37) % 100 / 100f)
                val fy = 3f + (h - 6f) * ((f * 53) % 100 / 100f)
                drawCircle(style.ridgeColor.copy(alpha = 0.18f), 0.8f, Offset(fx, fy))
            }

            // 5. Highlights along top edge and torn fiber tips
            drawLine(Color(0x35FFFFFF), Offset(4f, 0.8f), Offset(w - 4f, 0.8f), 0.8f)
            drawPath(tapePath, style.ridgeColor.copy(alpha = 0.35f), style = Stroke(0.5f))

            // 6. Stray paper-fiber wisps poking out past the tear line — torn paper fuzzes at the
            // edge in a way a clean-cut edge never does; a few short random hairs sell it.
            for (wisp in fiberWisps) {
                val edge = if (wisp.side == 0) leftEdge else rightEdge
                val nearest = edge.minByOrNull { kotlin.math.abs(it.yFrac - wisp.yFrac) } ?: continue
                val baseX = if (wisp.side == 0) 3f + nearest.depthPx else w - 3f + nearest.depthPx
                val baseY = h * wisp.yFrac
                val rad = Math.toRadians(wisp.angleDeg.toDouble() + if (wisp.side == 0) 180.0 else 0.0)
                val tip = Offset(baseX + (kotlin.math.cos(rad) * wisp.lenPx).toFloat(), baseY + (kotlin.math.sin(rad) * wisp.lenPx).toFloat())
                drawLine(style.ridgeColor.copy(alpha = 0.4f), Offset(baseX, baseY), tip, 0.35f)
            }

            // 7. Grime & rub marks — only rolled on some tapes (see `dirty` above). Soft round
            // blotches (thumb grease/dust) plus a couple of thin directional scuffs, both drawn in
            // a dark neutral tone so they read as age/dirt regardless of the tape's own paper color.
            if (dirty) {
                for (spot in grimeSpots) {
                    val c = Offset(w * spot.xFrac, h * spot.yFrac)
                    drawCircle(
                        Brush.radialGradient(listOf(Color.Black.copy(alpha = spot.alpha), Color.Transparent), center = c, radius = spot.radiusPx),
                        radius = spot.radiusPx, center = c
                    )
                }
                for (streak in smudgeStreaks) {
                    val cy = h * streak.yFrac
                    val cx = w * 0.5f
                    val rad = Math.toRadians(streak.angleDeg.toDouble())
                    val halfLen = w * streak.lenFrac / 2f
                    val dx = (kotlin.math.cos(rad) * halfLen).toFloat()
                    val dy = (kotlin.math.sin(rad) * halfLen).toFloat()
                    drawLine(Color.Black.copy(alpha = streak.alpha), Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), streak.widthPx, cap = StrokeCap.Round)
                }
            }
        }

        // Handwritten marker text on top of the photorealistic tape. Shrinks to fit instead of
        // truncating — it's meant to read as handwriting, and a mid-word "…" cut reads as broken
        // rather than a real hand just writing smaller to fit the strip.
        // Reserve real bottom clearance for the corner date (below) ONLY when there is one, so it
        // never fights the last line of text for the same pixels — that was the "date is half the
        // time covered by other text" bug: both were anchored to the same bottom-right corner with
        // no dedicated space carved out for either.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = if (track.year > 0) 15.dp else 5.dp)
        ) {
            ShrinkToFitText(
                track.title,
                color = style.penColor,
                fontFamily = MarkerFont,
                fontWeight = FontWeight.SemiBold,
                maxFontSize = 17.sp,
                minFontSize = 10.sp
            )
            ShrinkToFitText(
                track.artist,
                color = style.penColor.copy(alpha = 0.85f),
                fontFamily = MarkerFont,
                maxFontSize = 11.5.sp,
                minFontSize = 7.5.sp
            )
            if (track.album.isNotBlank()) {
                ShrinkToFitText(
                    track.album,
                    color = style.penColor.copy(alpha = 0.65f),
                    fontFamily = MarkerFont,
                    maxFontSize = 9.5.sp,
                    minFontSize = 6.5.sp
                )
            }
        }

        // Clean marker handwriting rendering without text-obscuring smudges

        // A date jotted in the corner afterward, in a different pen — the way a real mixtape
        // label often picks up a second hand's note. Only when we actually know the year.
        if (track.year > 0) {
            Text(
                "'${(track.year % 100).toString().padStart(2, '0')}",
                color = style.penColor.copy(alpha = 0.8f),
                fontFamily = ScrawlFont,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 2.dp)
                    .rotate(-7f)
            )
        }
    }
}

/**
 * Single-line text that shrinks font size (never truncates) until it fits its constraints —
 * handwriting that got cut off mid-word reads as broken; handwriting that got a little smaller
 * to fit the strip reads as authentic.
 */
@Composable
private fun ShrinkToFitText(
    text: String,
    color: Color,
    fontFamily: FontFamily,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier
) {
    var fontSize by remember(text) { mutableStateOf(maxFontSize) }
    Text(
        text,
        color = color,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > minFontSize) {
                fontSize = (fontSize.value - 1f).sp
            }
        },
        modifier = modifier
    )
}

// ── Per-theme plastic warping/lensing for the whole rendered cassette texture ──
// Real molded plastic (especially anything translucent enough to see the mechanism through) is
// never a perfectly flat optical surface. The first version of this shader was pure organic noise
// (uniform strength everywhere) — it read as generic shimmer/heat-haze, not "looking through
// plastic", because a real lens/curved shell has a distinct SIGNATURE a uniform wobble doesn't:
// it's closest to optically flat in the middle (thin, low curvature) and bends light increasingly
// toward the edges (thicker, more curved) — and chromatic fringing follows that exact same
// falloff, strongest at the edges, negligible dead center. That radial coherence (not the noise
// amplitude) is what actually reads as "glass/acrylic" instead of "shader effect". The mold-flow
// organic wobble is still here, just demoted to a secondary texture on top of that dominant term.
//
// Requires RuntimeShader (API 33+). This build is device-gated to the M500 (Android 14 in the
// field, see MainActivity.isSupportedDevice), so that's never actually a runtime concern here —
// the SDK_INT guard below just keeps this safe to compile/verify against the library's broader
// minSdk without crashing older API surfaces that will never load it.
private const val LENS_AGSL = """
    uniform shader content;
    uniform float2 resolution;
    uniform float distortAmount;
    uniform float chromaAmount;
    uniform float phase;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / resolution;
        float2 fromCenter = uv - float2(0.5, 0.5);
        float dist = length(fromCenter);

        // Dominant term: a coherent radial bulge, ~flat at the center, ramping up toward the
        // edges (smoothstep, not a hard-edged formula) — the actual optical signature of a lens.
        float radialStrength = smoothstep(0.0, 0.9, dist);
        float2 radialOffset = fromCenter * radialStrength * radialStrength * distortAmount * 3.2;

        // Secondary term: gentle organic mold-flow waviness, much smaller than the lens bulge —
        // texture, not the main event.
        float n1 = sin(uv.x * 9.1 + uv.y * 5.3 + phase) * cos(uv.y * 7.4 - uv.x * 3.2 - phase * 0.7);
        float n2 = sin(uv.x * 3.7 - uv.y * 11.3 + 1.7 + phase * 0.5);
        float2 wobble = float2(n1, n2) * distortAmount * 0.45;

        float2 offset = radialOffset + wobble;

        // Chromatic aberration follows the SAME radial falloff as the lens bulge — real lens CA
        // is strongest at the edges, close to invisible at the center.
        float chroma = chromaAmount * (0.15 + radialStrength * 0.85);

        float2 uvR = (uv + offset * (1.0 + chroma)) * resolution;
        float2 uvG = (uv + offset) * resolution;
        float2 uvB = (uv + offset * (1.0 - chroma)) * resolution;

        half4 cR = content.eval(uvR);
        half4 cG = content.eval(uvG);
        half4 cB = content.eval(uvB);

        return half4(cR.r, cG.g, cB.b, cG.a);
    }
"""

/** How strongly a theme's own plastic should warp/lens what's rendered through it. Opaque shells
 *  get null — there's no optical depth to distort when nothing shows through them. */
private data class LensSpec(val distort: Float, val chroma: Float)
private fun lensSpecFor(t: TapeTheme): LensSpec? = when (t.plasticType) {
    // Boosted well past "physically modest" — scoping the shader to just the art/visualizer layer
    // (not the shell's own geometry, see applyTapeLens's call site) means it's only ever visible
    // through a fraction of the screen to begin with, so the per-pixel displacement needs to be
    // large enough to actually read at a glance, not just survive close inspection.
    PlasticType.CLEAR_POLYCARBONATE -> LensSpec(distort = 0.032f, chroma = 0.24f)   // crisp, strongly see-through
    PlasticType.SMOKED_ACRYLIC -> LensSpec(distort = 0.020f, chroma = 0.16f)        // tinted, softer clarity
    PlasticType.SATIN_POLYSTYRENE -> LensSpec(distort = 0.009f, chroma = 0.08f)     // frosted, barely see-through
    PlasticType.MATTE_COMPOSITE, PlasticType.METALLIC_FLAKE, PlasticType.PHOSPHOR_GLOW -> null // opaque: no lensing
}

/**
 * Applies the theme's plastic lens/warp to whatever this graphicsLayer renders (drop-in for the
 * cassette's own Box). Sets the shader's resolution uniform to the layer's actual pixel size each
 * time it's (re)placed, and forces offscreen compositing — required for a renderEffect to combine
 * correctly with the translucent content it's distorting (mechanism art, glass streaks, etc.).
 */
private fun GraphicsLayerScope.applyTapeLens(theme: TapeTheme, shaderHolder: RuntimeShader?) {
    if (shaderHolder == null) return
    shaderHolder.setFloatUniform("resolution", size.width, size.height)
    compositingStrategy = CompositingStrategy.Offscreen
    renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shaderHolder, "content").asComposeRenderEffect()
}

/**
 * The RuntimeShader instance itself, remembered per (theme, strength) so applyTapeLens can update
 * its resolution uniform every layout pass without rebuilding the shader from source each time.
 *
 * [strengthMultiplier] lets the SAME plastic warp be reused at different intensities for different
 * depths in the cassette stack: full strength for the background art/visualizer (furthest behind
 * the plastic), a fraction of that for the mag tape mechanism itself (reels/ribbon sit right up
 * against the inside of the shell, so they should barely warp, not not-at-all), and the shell's
 * own geometry never gets a shader at all (drawn in a separate, unwrapped layer).
 */
@Composable
private fun rememberTapeLensShader(theme: TapeTheme, strengthMultiplier: Float = 1f): RuntimeShader? {
    val spec = lensSpecFor(theme) ?: return null
    if (android.os.Build.VERSION.SDK_INT < 33) return null
    return remember(theme.name, strengthMultiplier) {
        RuntimeShader(LENS_AGSL).apply {
            setFloatUniform("distortAmount", spec.distort * strengthMultiplier)
            setFloatUniform("chromaAmount", spec.chroma * strengthMultiplier)
            setFloatUniform("phase", (theme.name.hashCode() % 100) / 100f * 6.28f)
        }
    }
}
