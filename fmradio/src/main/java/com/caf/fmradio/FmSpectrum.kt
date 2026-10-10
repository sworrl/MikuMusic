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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
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

/** Labels any closer together than this are dropped rather than drawn overlapping. */
private const val MIN_LABEL_GAP_DP = 26f
/** Hard cap: a crowded band here lists hundreds of stations and none of them would be legible. */
private const val MAX_BAND_LABELS = 14

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
    signalHistory: FloatArray,
    bandProfile: FloatArray,
    bandHistory: List<FloatArray>,
    favorites: List<Int>,
    scanHits: List<Int>,
    /**
     * Stations the catalogue says transmit within reach of where the device actually is.
     *
     * This is what separates this from a bar chart: an SDR display names its peaks. The ticks
     * were anonymous before, so a strong signal at 101.9 told you only that something was
     * there. These come from FCC records plus the fused position, so the band can be read as
     * "WVAQ at 12.7 km" rather than "a peak". Best-scoring first; the strongest few get a
     * label drawn, the rest get a tick, because 281 call signs will not fit on 320 pixels.
     */
    cataloguedStations: List<FmStationCatalogue.Station> = emptyList(),
    /** Which receiver-panel convention to draw. Colour never varies; geometry does. */
    skin: SdrSkin = SdrSkin.CLASSIC,
    /** Sweep times, newest first, parallel to bandHistory. Empty when nothing has swept. */
    rowTimes: List<Long> = emptyList(),
    onCycleSkin: () -> Unit = {},
    onTuneFreq: (Int) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val binCount = QualcommFmHardwareEngine.SPECTRUM_BINS
    val rowCount = 20
    var rows by remember { mutableStateOf(List(rowCount) { FloatArray(binCount) }) }
    LaunchedEffect(spectrum) {
        rows = if (spectrum.size == binCount) (listOf(spectrum) + rows).take(rowCount)
        else List(rowCount) { FloatArray(binCount) }
    }
    val live = if (spectrum.size == binCount) spectrum else FloatArray(binCount)
    // No capture means no spectrum. The panel says so rather than drawing a flat line that
    // could be read as "the radio is silent": on a wired route this device does not expose the
    // tuner audio to the app at all, so there is nothing here to measure.
    val hasAudio = spectrum.size == binCount
    // A band sweep gives RSSI per channel across the whole band, which is the display this
    // panel wants: x is already frequency, so the sweep drops straight onto the same axis as
    // the tuning strip, the presets and the marker.
    val hasBand = bandProfile.isNotEmpty()

    val low = band.lowKHz.toFloat()
    val span = (band.highKHz - band.lowKHz).toFloat()
    val step = band.stepKHz

    // No rounding here. Snapping belongs to the engine, which anchors the grid to the band's
    // low limit; doing it here as "nearest multiple of step" rounded from zero, which on a
    // 200 kHz grid can only ever produce even tenths and made 100.1 untunable.
    fun freqAt(fracX: Float): Int = (low + fracX.coerceIn(0f, 1f) * span).roundToInt()

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
            val fftHeight = h * 0.42f
            val scaleHeight = 10.dp.toPx()
            val waterfallHeight = h * 0.60f - scaleHeight
            val rowHeight = waterfallHeight / rowCount
            val binWidth = w / binCount

            val gutter = rssiGutterWidth(skin)
            val plotW = w - gutter
            fun xOf(khz: Int) = gutter + ((khz - low) / span).coerceIn(0f, 1f) * plotW

            // Horizontal reference lines across the trace area. An SDR panel without a grid is
            // a pretty shape; with one you can compare two peaks at a glance.
            for (g in 1..3) {
                val gy = fftHeight * g / 4f
                drawLine(MikuTeal.copy(alpha = 0.10f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }

            // The tuned channel, as a block the width of one channel rather than a bare line,
            // so you can see what the receiver is actually sitting on relative to its neighbours.
            val halfCh = (step / 2f / span) * plotW
            val cx = xOf(currentFreqKHz)
            if (skin.showsPassband) {
                drawPassband(cx, halfCh, fftHeight, MikuTeal)
            } else {
                drawRect(
                    MikuTeal.copy(alpha = 0.16f),
                    Offset(cx - halfCh, 0f), Size(halfCh * 2f, fftHeight)
                )
            }

            if (hasAudio) {
                // Audio spectrum history, which only exists on Bluetooth where the capture is
                // genuinely the audio path.
                for (r in 0 until rowCount) {
                    val y = fftHeight + r * rowHeight
                    val row = rows.getOrNull(r)
                    for (b in 0 until binCount) {
                        drawRect(FmWaterfallPalette.lut(row?.getOrNull(b) ?: 0f),
                                 Offset(b * binWidth, y), Size(binWidth + 0.5f, rowHeight + 0.5f))
                    }
                }
            } else if (hasBand && skin.showsSurface) {
                drawSurfaceWaterfall(bandHistory.take(14), fftHeight, waterfallHeight, w)
            } else if (hasBand) {
                // Band waterfall: each row is one sweep, x is frequency, newest at the top.
                // Adjacent signals show up as vertical streaks you can tap straight onto.
                // Capped hard: each row is one drawRect per channel, so 24 sweeps across a US
                // band is ~2,500 rects every frame. Twelve is plenty of history to read.
                val rows = bandHistory.take(12)
                val rh = if (rows.isEmpty()) 0f else waterfallHeight / rows.size.coerceAtLeast(1)
                for (r in rows.indices) {
                    val prof = rows[r]
                    if (prof.isEmpty()) continue
                    val cw = plotW / prof.size
                    val y = fftHeight + r * rh
                    for (i in prof.indices) {
                        drawRect(FmWaterfallPalette.lut(prof[i]),
                                 Offset(gutter + i * cw, y), Size(cw + 0.5f, rh + 0.5f))
                    }
                }
                if (skin.showsTimestamps) {
                    drawWaterfallTimestamps(textMeasurer, rowTimes.take(rows.size),
                                            fftHeight, rh, MikuTeal)
                }
            } else {
                // Nothing swept yet: one column per poll of the tuned frequency's own RSSI, so
                // the panel still responds while you aim the wire.
                val cols = signalHistory.size
                if (cols > 1) {
                    val colW = w / cols
                    for (i in 0 until cols) {
                        val v = signalHistory[i].coerceIn(0f, 1f)
                        val barH = v * (waterfallHeight - 2f)
                        drawRect(
                            FmWaterfallPalette.lut(v),
                            Offset(w - (i + 1) * colW, fftHeight + (waterfallHeight - barH)),
                            Size(colW + 0.5f, barH)
                        )
                    }
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
            // Catalogue ticks sit UNDER the scan/favourite marks and are dimmer, so a station
            // the device has actually heard still reads louder than one that is merely listed.
            // Drawn before the MHz grid for the same reason: the grid is the reference, these
            // are data, and data should not look like the ruler.
            cataloguedStations.forEach { st ->
                val sx = xOf(st.khz)
                drawLine(MikuTeal.copy(alpha = 0.22f), Offset(sx, scaleTop + scaleHeight * 0.2f),
                    Offset(sx, scaleTop + scaleHeight), strokeWidth = 1f)
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
            if (hasAudio) {
                val dx = w / (binCount - 1)
                for (i in 0 until binCount) {
                    val x = i * dx
                    val y = fftHeight - (live[i].coerceIn(0f, 1f) * (fftHeight - 6f))
                    if (i == 0) fftPath.moveTo(x, y) else fftPath.lineTo(x, y)
                }
            } else if (hasBand && skin.showsSurface) {
                drawSurfaceWaterfall(bandHistory.take(14), fftHeight, waterfallHeight, w)
            } else if (hasBand) {
                // Live trace of the newest sweep: RSSI against frequency, so peaks sit directly
                // above the frequency that produced them and can be tapped.
                val n = bandProfile.size
                val dx = w / (n - 1).coerceAtLeast(1)
                for (i in 0 until n) {
                    val y = fftHeight - (bandProfile[i].coerceIn(0f, 1f) * (fftHeight - 6f))
                    if (i == 0) fftPath.moveTo(gutter, y) else fftPath.lineTo(gutter + i * dx, y)
                }
            } else {
                val n = signalHistory.size
                if (n > 1) {
                    val dx = plotW / (n - 1)
                    for (i in 0 until n) {
                        val x = w - i * dx
                        val y = fftHeight - (signalHistory[i].coerceIn(0f, 1f) * (fftHeight - 6f))
                        if (i == 0) fftPath.moveTo(x, y) else fftPath.lineTo(x, y)
                    }
                }
            }
            val fillPath = Path().apply {
                addPath(fftPath); lineTo(w, fftHeight); lineTo(gutter, fftHeight); close()
            }
            drawPath(
                fillPath,
                Brush.verticalGradient(listOf(Color(0x8800E5FF), Color(0x1100E5FF)), 0f, fftHeight)
            )
            drawPath(fftPath, MikuTeal, style = Stroke(width = 2.dp.toPx()))

            if (skin.showsAxis) {
                drawRssiAxis(textMeasurer, gutter, fftHeight,
                             QualcommFmHardwareEngine.SWEEP_RSSI_FULL_SCALE, MikuTeal)
            }

            val markerX = xOf(currentFreqKHz)
            drawLine(MikuPink, Offset(markerX, 0f), Offset(markerX, h), strokeWidth = 2.dp.toPx())
        }

        // Call signs over the band, which is the difference between a spectrum and a dial you
        // can read. Laid out in Compose rather than drawn in the Canvas above so the text gets
        // normal font handling, and capped hard: the catalogue returns a few hundred stations
        // within reach here and only the strongest handful can be legible across a 320 px band.
        // Collision is handled by refusing a label that lands within MIN_LABEL_GAP_DP of one
        // already placed, taking the better-scoring station first, so labels thin out in a
        // crowded part of the band instead of printing on top of each other.
        if (cataloguedStations.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val bandWidth = maxWidth
                val placed = remember(cataloguedStations, band, bandWidth) {
                    val out = ArrayList<Pair<FmStationCatalogue.Station, Dp>>()
                    val minGap = MIN_LABEL_GAP_DP.dp
                    for (st in cataloguedStations) {
                        if (st.khz < band.lowKHz || st.khz > band.highKHz) continue
                        val x = bandWidth * ((st.khz - band.lowKHz).toFloat() / span)
                        if (out.any { (it.second - x).value.let { d -> if (d < 0) -d else d } < minGap.value }) continue
                        out.add(st to x)
                        if (out.size >= (if (skin.labelsEverything) MAX_BAND_LABELS * 3 else MAX_BAND_LABELS)) break
                    }
                    out
                }
                for ((st, x) in placed) {
                    val tuned = kotlin.math.abs(st.khz - currentFreqKHz) <= 60
                    Text(
                        st.call,
                        color = if (tuned) MikuPink else MikuTeal.copy(alpha = 0.55f),
                        fontSize = 6.5.sp,
                        fontWeight = if (tuned) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            // Nudged left by half a label so the text straddles its own tick
                            // rather than starting at it and appearing to belong to the next one.
                            .offset(x = x - 13.dp, y = -1.dp)
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                when {
                    hasAudio -> "${skin.label} · AUDIO SPECTRUM · FM PCM 60 Hz–15 kHz"
                    hasBand -> "${skin.label} · RSSI vs FREQUENCY · TAP A PEAK TO TUNE"
                    else -> "${skin.label} · ${band.lowKHz / 1000}–${band.highKHz / 1000} MHz · SWEEP TO MAP THE BAND"
                },
                color = MikuTeal.copy(alpha = 0.85f), fontSize = 7.5.sp, fontWeight = FontWeight.Bold,
                // The header doubles as the skin control. A dedicated row of buttons would eat
                // height this panel does not have, and the label already says which one is on.
                modifier = Modifier.clickable { onCycleSkin() }
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
