package com.caf.fmradio

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Top 40 %: live audio spectrum of the FM PCM (log-spaced 60 Hz–15 kHz bins from the engine).
 * Bottom 60 %: waterfall = history of that spectrum. The pink marker and the bottom scale are
 * the band tuning strip; tap or drag anywhere to tune, and stations found by a scan appear on
 * the strip as teal ticks alongside the user's presets in pink.
 *
 * Nothing is synthesised: with the tuner off, starting, or silent the plot is flat. That is a
 * feature, not a gap — a flat trace while the tuner claims to be on is exactly the symptom that
 * says the audio path is not carrying anything.
 */
@Composable
fun RadioWaterfallSpectrum(
    currentFreqKHz: Int,
    band: FmBandPlan,
    isPowerOn: Boolean,
    isHardwareOnline: Boolean,
    hardwareError: String?,
    spectrum: FloatArray,
    favorites: List<Int>,
    scanHits: List<Int>,
    onTuneFreq: (Int) -> Unit,
) {
    val binCount = QualcommFmHardwareEngine.SPECTRUM_BINS
    val rowCount = 20
    var rows by remember { mutableStateOf(List(rowCount) { FloatArray(binCount) }) }
    LaunchedEffect(spectrum) {
        rows = if (spectrum.size == binCount) (listOf(spectrum) + rows).take(rowCount)
        else List(rowCount) { FloatArray(binCount) }
    }
    val live = if (spectrum.size == binCount) spectrum else FloatArray(binCount)

    val low = band.lowKHz.toFloat()
    val span = (band.highKHz - band.lowKHz).toFloat()
    val step = band.stepKHz

    fun freqAt(fracX: Float): Int {
        val target = (low + fracX.coerceIn(0f, 1f) * span).roundToInt()
        return ((target + step / 2) / step) * step
    }

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF040D12))
            .border(1.2.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
            .pointerInput(band) {
                detectTapGestures { offset -> onTuneFreq(freqAt(offset.x / size.width)) }
            }
            .pointerInput(band) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onTuneFreq(freqAt(change.position.x / size.width))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val fftHeight = h * 0.40f
            val scaleHeight = 10.dp.toPx()
            val waterfallHeight = h * 0.60f - scaleHeight
            val rowHeight = waterfallHeight / rowCount
            val binWidth = w / binCount

            fun xOf(khz: Int) = ((khz - low) / span).coerceIn(0f, 1f) * w

            for (r in 0 until rowCount) {
                val y = fftHeight + r * rowHeight
                val row = rows.getOrNull(r)
                for (b in 0 until binCount) {
                    val mag = row?.getOrNull(b) ?: 0f
                    val color = when {
                        mag < 0.20f -> Color(0xFF031622)
                        mag < 0.45f -> Color(0xFF004D5A)
                        mag < 0.70f -> Color(0xFF00B4D8)
                        mag < 0.85f -> Color(0xFF00E5FF)
                        else -> Color(0xFFFF4081)
                    }
                    drawRect(color, Offset(b * binWidth, y), Size(binWidth + 0.5f, rowHeight + 0.5f))
                }
            }

            val scaleTop = fftHeight + waterfallHeight
            drawRect(Color(0xFF020A0F), topLeft = Offset(0f, scaleTop), size = Size(w, scaleHeight))
            scanHits.forEach { f ->
                drawLine(MikuTeal.copy(alpha = 0.7f), Offset(xOf(f), scaleTop + scaleHeight * 0.45f),
                    Offset(xOf(f), scaleTop + scaleHeight), strokeWidth = 1.5.dp.toPx())
            }
            favorites.forEach { f ->
                drawLine(MikuPink.copy(alpha = 0.9f), Offset(xOf(f), scaleTop),
                    Offset(xOf(f), scaleTop + scaleHeight), strokeWidth = 1.5.dp.toPx())
            }
            val tickStepMHz = if (span > 20000f) 4 else 2
            var mhzTick = ((band.lowKHz / 1000) / tickStepMHz + 1) * tickStepMHz
            while (mhzTick * 1000 <= band.highKHz) {
                val sx = xOf(mhzTick * 1000)
                drawLine(MikuTeal.copy(alpha = 0.3f), Offset(sx, scaleTop + scaleHeight * 0.55f),
                    Offset(sx, scaleTop + scaleHeight), strokeWidth = 1f)
                mhzTick += tickStepMHz
            }

            val fftPath = Path()
            val dx = w / (binCount - 1)
            for (i in 0 until binCount) {
                val x = i * dx
                val y = fftHeight - (live[i].coerceIn(0f, 1f) * (fftHeight - 6f))
                if (i == 0) fftPath.moveTo(x, y) else fftPath.lineTo(x, y)
            }
            val fillPath = Path().apply {
                addPath(fftPath); lineTo(w, fftHeight); lineTo(0f, fftHeight); close()
            }
            drawPath(
                fillPath,
                Brush.verticalGradient(listOf(Color(0x8800E5FF), Color(0x1100E5FF)), 0f, fftHeight)
            )
            drawPath(fftPath, MikuTeal, style = Stroke(width = 2.dp.toPx()))

            val markerX = xOf(currentFreqKHz)
            drawLine(MikuPink, Offset(markerX, 0f), Offset(markerX, h), strokeWidth = 2.dp.toPx())
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "LIVE AUDIO SPECTRUM · FM PCM 60 Hz–15 kHz",
                color = MikuTeal.copy(alpha = 0.85f), fontSize = 7.5.sp, fontWeight = FontWeight.Bold
            )
            Text(
                when {
                    isHardwareOnline -> "HARDWARE ACTIVE"
                    hardwareError != null -> "TUNER ERROR"
                    isPowerOn -> "STARTING…"
                    else -> "STANDBY"
                },
                color = when {
                    isHardwareOnline -> MikuPink
                    hardwareError != null -> MikuNeonPink
                    isPowerOn -> Color(0xFFFFD54F)
                    else -> Color.Gray
                },
                fontSize = 7.5.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun FmRecordButton(isRecording: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "recPulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.25f else 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(50.dp)) {
        if (isRecording) {
            Box(
                Modifier.size((40 * pulseScale).dp).clip(CircleShape)
                    .background(Color(0xFFFF1744).copy(alpha = 0.35f))
                    .border(1.5.dp, Color(0xFFFF1744), CircleShape)
            )
        }
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(
                    if (isRecording) Brush.verticalGradient(listOf(Color(0xFFFF1744), Color(0xFFB71C1C)))
                    else Brush.verticalGradient(listOf(Color(0xEE0A1E26), Color(0xFF040D12)))
                )
                .border(1.5.dp, if (isRecording) Color(0xFFFF5252) else CyberGlassBorder, CircleShape)
        ) {
            if (isRecording) {
                Icon(Icons.Default.Stop, "Stop recording", tint = Color.White, modifier = Modifier.size(18.dp))
            } else {
                Box(Modifier.size(13.dp).clip(CircleShape).background(Color(0xFFFF1744)))
            }
        }
    }
}
