package com.caf.fmradio

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private enum class FmSheet { NONE, SCAN, SETTINGS, DIAGNOSTICS, KEYPAD }

private fun mhz(khz: Int) = String.format(Locale.US, "%.1f", khz / 1000.0)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MikuFMRadioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by FmRadioManager.state.collectAsState()
    var sheet by remember { mutableStateOf(FmSheet.NONE) }

    Box(Modifier.fillMaxSize().background(CyberDarkBg)) {
        Image(
            painter = painterResource(id = R.drawable.miku_bg_fm),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xCC040D12), Color(0x77000000), Color(0xF5040D12)))
            )
        )

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FmHeaderBar(st, onBack) { sheet = FmSheet.SETTINGS }
            Spacer(Modifier.height(3.dp))
            FmFrequencyCard(st, onKeypad = { sheet = FmSheet.KEYPAD })
            Spacer(Modifier.height(3.dp))
            FmSignalMeter(st, Modifier.clickable { sheet = FmSheet.DIAGNOSTICS })
            Spacer(Modifier.height(3.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                RadioWaterfallSpectrum(
                    currentFreqKHz = st.frequencyKHz,
                    band = st.band,
                    isPowerOn = st.isPowerOn,
                    isHardwareOnline = st.isHardwareOnline,
                    hardwareError = st.hardwareError,
                    spectrum = st.spectrum,
                    signalHistory = st.signalHistory,
                    bandProfile = st.bandProfile,
                    bandHistory = st.bandHistory,
                    favorites = st.favorites,
                    scanHits = st.scanResults.map { it.freqKHz },
                    cataloguedStations = st.nearbyStations,
                    skin = st.sdrSkin,
                    rowTimes = st.bandHistoryTimes,
                    onCycleSkin = { FmRadioManager.cycleSdrSkin() },
                    onTuneFreq = { FmRadioManager.tune(it) }
                )
                if (st.isScanning) {
                    FmScanOverlay(st)
                }
            }

            // The terrain between here and whatever is tuned. Only shown once a profile has
            // actually been obtained: this panel exists to answer "is a hill in the way", and
            // an empty one every time the catalogue has no match would just be furniture.
            st.tunedProfile?.let { prof ->
                Spacer(Modifier.height(3.dp))
                FmFresnelProfileView(prof, Modifier.height(82.dp))
            }

            Spacer(Modifier.height(4.dp))
            FmTransportRow(st, ctx)
            Spacer(Modifier.height(3.dp))
            FmToolRow(
                st = st,
                onScan = { if (st.isScanning) FmRadioManager.cancelSeek() else FmRadioManager.scanBand() },
                onResults = { sheet = FmSheet.SCAN },
                onRecord = {
                    Toast.makeText(ctx, FmRadioManager.toggleRecording(ctx), Toast.LENGTH_SHORT).show()
                },
                onKeypad = { sheet = FmSheet.KEYPAD }
            )
            Spacer(Modifier.height(3.dp))
            FmPresetRow(st)
            Spacer(Modifier.height(2.dp))
            FmGesturePill(ctx)
        }
    }

    // Transient volume readout. The slider is gone and the wheel now drives the tuner's own
    // gain, so without this a turn of the wheel changes something invisible. It shows on change
    // and fades, which is the behaviour of every hardware volume control.
    FmVolumeHud(st.fmVolumeLevel)

    when (sheet) {
        FmSheet.NONE -> Unit
        FmSheet.SCAN -> FmSheetFrame({ sheet = FmSheet.NONE }) { FmScanResults(st) { sheet = FmSheet.NONE } }
        FmSheet.SETTINGS -> FmSheetFrame({ sheet = FmSheet.NONE }) { FmSettings(st) }
        FmSheet.DIAGNOSTICS -> FmSheetFrame({ sheet = FmSheet.NONE }) { FmDiagnosticsPanel(st) }
        FmSheet.KEYPAD -> FmSheetFrame({ sheet = FmSheet.NONE }) { FmKeypad(st) { sheet = FmSheet.NONE } }
    }
}

// ---------------------------------------------------------------------------- header

@Composable
private fun FmHeaderBar(st: FmState, onBack: () -> Unit, onSettings: () -> Unit) {
    // This board has no internal antenna, so the headphone cable is it. The chip reports what
    // is actually plugged in rather than a decorative "ANTENNA OK".
    val ok = st.isHeadsetPlugged
    val label = if (ok) "ANTENNA OK" else "PLUG HEADSET"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MikuBackButton(onClick = onBack)
        Text(
            "MIKU CYBER FM TUNER", color = MikuCyan, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, letterSpacing = 1.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            CyberChip(label, ok)
            Spacer(Modifier.width(5.dp))
            CyberIconButton(Icons.Default.Tune, "Settings", 34.dp, false, onSettings)
        }
    }
}

@Composable
fun CyberChip(text: String, ok: Boolean, accent: Color = MikuCyan) {
    val c = if (ok) accent else MikuNeonPink
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(c.copy(alpha = 0.18f))
            .border(1.dp, c, RoundedCornerShape(9.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = c, fontSize = 7.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
    }
}

@Composable
fun CyberIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (active) MikuCyan.copy(alpha = 0.28f) else Color(0x1100E5FF))
            .border(1.dp, if (active) MikuCyan else CyberGlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = if (active) MikuCyan else MikuTextSecondary, modifier = Modifier.size(size * 0.5f))
    }
}

// ---------------------------------------------------------------------------- frequency

@Composable
private fun FmFrequencyCard(st: FmState, onKeypad: () -> Unit) {
    val isFav = st.favorites.contains(st.frequencyKHz)
    val online = st.isHardwareOnline
    val statusColor = when {
        online -> MikuCyan
        st.hardwareError != null -> MikuNeonPink
        st.isPowerOn -> Color(0xFFFFD54F)
        else -> Color.Gray
    }
    val pty = FmRadioManager.programmeTypeName(st.programmeType, st.band)

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0xDD0A1E26))
            .border(1.2.dp, if (st.isPowerOn) MikuCyan else Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            online -> {
                                val mode = when (st.isStereo) {
                                    true -> "STEREO"; false -> "MONO"; null -> "FM"
                                }
                                "$mode · ${st.band.label}"
                            }
                            st.hardwareError != null -> "TUNER ERROR"
                            st.isPowerOn -> "STARTING TUNER…"
                            else -> "STANDBY"
                        },
                        color = statusColor, fontSize = 8.sp,
                        fontWeight = FontWeight.Bold, fontFamily = AudiowideFont
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Dialpad, "Enter a frequency",
                        tint = MikuTextSecondary,
                        modifier = Modifier.size(16.dp).clickable(onClick = onKeypad)
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                        "Preset",
                        tint = if (isFav) MikuNeonPink else Color.Gray,
                        modifier = Modifier.size(17.dp)
                            .clickable { FmRadioManager.togglePreset(st.frequencyKHz) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    mhz(st.frequencyKHz),
                    color = if (st.isPowerOn) Color.White else Color.Gray,
                    fontSize = 34.sp, fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont, letterSpacing = 1.sp
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "MHz", color = MikuCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont, modifier = Modifier.padding(bottom = 5.dp)
                )
            }

            // RDS name when decoded; otherwise the engine's real status line. Never an
            // invented station.
            val headline = when {
                st.stationName.isNotEmpty() && pty != null -> "${st.stationName} · $pty"
                st.stationName.isNotEmpty() -> st.stationName
                st.rdsAvailable == false -> "No RDS on this station"
                online -> "No RDS name"
                else -> "—"
            }
            // The catalogue knows what is licensed on this channel here even when RDS has
            // decoded nothing, so the dial is not blank while the antenna hunts.
            val fromCatalogue = st.tunedStation
            val shown = when {
                headline != "No RDS name" && headline != "—" -> headline
                fromCatalogue != null ->
                    "${fromCatalogue.call} · ${fromCatalogue.city ?: ""} ${fromCatalogue.state ?: ""}".trim()
                else -> headline
            }
            Text(
                shown,
                color = MikuTextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // The engine parks a "no RDS on <freq>" message in radioText when nothing decodes,
            // which is the same thing the headline already says. Only show the second line when
            // it is carrying real radio text.
            val showsRealRadioText = st.stationName.isNotEmpty() && st.radioText.isNotBlank() &&
                !st.radioText.startsWith("No RDS") && !st.radioText.startsWith("Live tuner")
            if (showsRealRadioText) {
                Text(
                    st.radioText, color = MikuTextSecondary, fontSize = 8.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- signal

@Composable
private fun FmSignalStrip(st: FmState, onDiagnostics: () -> Unit) {
    val s = st.signal
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xBB07161D))
            .border(1.dp, CyberGlassBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onDiagnostics)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // Ranges are the readable span of each metric on this tuner, used only to scale the
            // bar; the printed number is always the raw reading.
            MeterCell("RSSI", s.rssi ?: st.rssi, 0f, 90f, Modifier.weight(1f))
            MeterCell("SNR", s.snr, 0f, 30f, Modifier.weight(1f))
            // Multipath is a distortion figure: low is good. The bar is drawn the same way as
            // the others because an inverted one reads as "maxed out" when the value is zero,
            // which is the best possible reading.
            MeterCell("MPATH", s.multipath, 0f, 100f, Modifier.weight(1f))
            MeterCell("AUDIO", (st.audioLevel * 100).toInt().takeIf { st.isPowerOn }, 0f, 60f, Modifier.weight(1f))
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CyberChip(st.diagnostics.routeLabel.uppercase(Locale.US), true, MikuTeal)
            CyberChip(
                when (st.diagnostics.halLoopback) {
                    true -> "HAL FM ON"
                    false -> "HAL FM OFF"
                    null -> "HAL FM ?"
                },
                st.diagnostics.halLoopback == true
            )
            if (st.diagnostics.driverMuted == true) CyberChip("DRIVER MUTED", false)
            if (st.rdsAvailable == true) CyberChip("RDS", true, MikuPurple)
            Spacer(Modifier.weight(1f))
            Text("DIAG", color = MikuTextSecondary, fontSize = 7.sp, fontFamily = AudiowideFont)
        }
    }
}

@Composable
private fun MeterCell(label: String, value: Int?, min: Float, max: Float, modifier: Modifier) {
    val shown = value?.let { ((it - min) / (max - min)).coerceIn(0f, 1f) } ?: 0f
    Column(modifier.padding(end = 6.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = MikuTextSecondary, fontSize = 6.5.sp, fontFamily = AudiowideFont)
            Text(value?.toString() ?: "—", color = MikuTextPrimary, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
            if (value != null) {
                Box(
                    Modifier.fillMaxWidth(shown).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                        .background(if (shown > 0.6f) MikuCyan else if (shown > 0.3f) MikuTeal else MikuNeonPink)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- transport

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FmTransportRow(st: FmState, ctx: Context) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CyberIconButton(Icons.Default.FastRewind, "Seek down", 42.dp, false) { FmRadioManager.seek(false) }
        CyberIconButton(Icons.Default.KeyboardArrowLeft, "Step down", 36.dp, false) { FmRadioManager.step(false) }

        // Power. Starting the service rather than the engine directly is what keeps the tuner
        // alive after this activity goes away.
        Box(
            Modifier.size(54.dp).clip(CircleShape)
                .background(
                    if (st.isPowerOn) Brush.verticalGradient(listOf(MikuCyan, Color(0xFF006978)))
                    else Brush.verticalGradient(listOf(Color(0xEE0A1E26), Color(0xFF040D12)))
                )
                .border(1.6.dp, if (st.isPowerOn) MikuCyan else CyberGlassBorder, CircleShape)
                .clickable {
                    if (st.isPowerOn) MikuFmService.stop(ctx) else MikuFmService.start(ctx)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PowerSettingsNew, "Power",
                tint = if (st.isPowerOn) Color.Black else MikuTextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }

        CyberIconButton(Icons.Default.KeyboardArrowRight, "Step up", 36.dp, false) { FmRadioManager.step(true) }
        CyberIconButton(Icons.Default.FastForward, "Seek up", 42.dp, false) { FmRadioManager.seek(true) }
    }
}

// ---------------------------------------------------------------------------- volume

/*
 * The volume row used to live here, between the transport and the tool rows.
 *
 * Removed: the M500 has a hardware volume wheel, which is a better control than a 4 mm slider
 * on a 720 px screen and is the one you reach for anyway. The engine still tracks STREAM_MUSIC
 * and pushes the matching linear gain to the HAL on every change, so the wheel drives FM volume
 * exactly as it drives everything else. Mute stayed, as a button in the tool row.
 */

// ---------------------------------------------------------------------------- tools

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FmToolRow(
    st: FmState,
    onScan: () -> Unit,
    onResults: () -> Unit,
    onRecord: () -> Unit,
    onKeypad: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CyberIconButton(
            if (st.stereoRequested) Icons.Default.Headphones else Icons.Default.HearingDisabled,
            if (st.stereoRequested) "Stereo requested" else "Mono", 38.dp, st.stereoRequested
        ) { FmRadioManager.setStereo(!st.stereoRequested) }

        CyberIconButton(
            if (st.isScanning) Icons.Default.Close else Icons.Default.Search,
            if (st.isScanning) "Stop the scan" else "Scan the band", 38.dp, st.isScanning, onScan
        )

        FmRecordButton(isRecording = st.isRecording, onClick = onRecord)

        CyberIconButton(
            Icons.Default.FormatListBulleted, "Stations found", 38.dp,
            st.scanResults.isNotEmpty(), onResults
        )

        CyberIconButton(Icons.Default.Dialpad, "Tune directly", 38.dp, false, onKeypad)
    }
    if (st.isRecording) {
        val seconds = st.recordedBytes / (48000 * 2 * 2)
        Text(
            "REC %d:%02d · %.1f MB".format(seconds / 60, seconds % 60, st.recordedBytes / 1_048_576.0),
            color = Color(0xFFFF5252), fontSize = 7.5.sp, fontFamily = AudiowideFont
        )
    }
}

@Composable
private fun FmScanOverlay(st: FmState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xEE02090D))
                .border(1.dp, MikuCyan, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SCANNING ${st.band.label.uppercase(Locale.US)}", color = MikuCyan,
                fontSize = 10.sp, fontFamily = AudiowideFont, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { st.scanProgress },
                color = MikuCyan, trackColor = Color(0x3300E5FF),
                modifier = Modifier.width(170.dp).height(4.dp)
            )
            Spacer(Modifier.height(5.dp))
            Text("${st.scanResults.size} found · tap the scan key to stop",
                color = MikuTextSecondary, fontSize = 8.sp)
        }
    }
}

// ---------------------------------------------------------------------------- presets

/**
 * Two tiers of quick select.
 *
 * Filled chips are the user's own, locked and permanent. Outlined chips are what the station
 * catalogue says is licensed within reach of wherever the device currently is — they follow
 * you, appear without being asked for, and are gone when you move. Tap either to tune; tap the
 * star on a predicted one to lock it into the permanent row.
 *
 * The predicted tier is not a promise. It is transmitter power and height against distance,
 * with no terrain in it, which in a hollow is an upper bound rather than a forecast.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FmPresetRow(st: FmState) {
    val predicted = st.nearbyStations
        .filter { it.khz !in st.favorites }
        .distinctBy { it.khz }
        .take(14)

    if (st.favorites.isEmpty() && predicted.isEmpty()) {
        Text(
            if (st.listenerPlace == null)
                "No presets yet — tap ☆ to save a station. Local stations appear once the device knows where it is."
            else "No presets yet — tap ☆ to save the current station",
            color = MikuTextSecondary, fontSize = 8.sp, fontFamily = AudiowideFont,
            modifier = Modifier.padding(vertical = 2.dp), maxLines = 2
        )
        return
    }

    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        items(predicted) { stn ->
            val current = st.frequencyKHz == stn.khz
            Box(
                Modifier.clip(RoundedCornerShape(9.dp))
                    .background(Color(0x1100E5FF))
                    .border(1.dp, if (current) MikuCyan else MikuTeal.copy(alpha = 0.55f),
                            RoundedCornerShape(9.dp))
                    .combinedClickable(
                        onClick = { FmRadioManager.tune(stn.khz) },
                        onLongClick = { FmRadioManager.togglePreset(stn.khz) }
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Column {
                    Text(stn.call, color = if (current) MikuCyan else MikuTeal,
                         fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    Text("${mhz(stn.khz)} · ${stn.distanceKm.toInt()} km",
                         color = MikuTextSecondary, fontSize = 6.5.sp)
                }
            }
        }
        items(st.favorites) { freq ->
            val current = st.frequencyKHz == freq
            Box(
                Modifier.clip(RoundedCornerShape(9.dp))
                    .background(if (current) MikuCyan else Color(0xDD0A1E26))
                    .border(1.dp, if (current) MikuCyan else CyberGlassBorder, RoundedCornerShape(9.dp))
                    .combinedClickable(
                        onClick = { FmRadioManager.tune(freq) },
                        onLongClick = { FmRadioManager.togglePreset(freq) }
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    mhz(freq), color = if (current) Color.Black else Color.White,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- gesture pill

@Composable
private fun FmGesturePill(ctx: Context) {
    Box(
        Modifier.fillMaxWidth().height(13.dp).padding(bottom = 1.dp)
            .pointerInput(Unit) {
                var startTime = 0L
                var totalY = 0f
                detectDragGestures(
                    onDragStart = { startTime = android.os.SystemClock.elapsedRealtime(); totalY = 0f },
                    onDragEnd = {
                        val duration = android.os.SystemClock.elapsedRealtime() - startTime
                        if (totalY < -30f) {
                            if (duration >= 250L || totalY < -100f) {
                                ctx.packageManager.getLaunchIntentForPackage("com.miku.launcher")?.apply {
                                    putExtra("open_recents", true)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                                }?.let { runCatching { ctx.startActivity(it) } }
                            } else {
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    })
                                }
                            }
                        }
                    },
                    onDrag = { change, amount -> change.consume(); totalY += amount.y }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.width(60.dp).height(3.5.dp).clip(RoundedCornerShape(2.dp))
                .background(MikuCyan.copy(alpha = 0.9f))
        )
    }
}


@Composable
private fun FmVolumeHud(level: Int) {
    var visible by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(level) {
        if (first) { first = false; return@LaunchedEffect }   // do not flash on first compose
        visible = true
        kotlinx.coroutines.delay(1400)
        visible = false
    }
    if (!visible) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xEE02090D))
                .border(1.dp, MikuCyan, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TUNER VOLUME", color = MikuTextSecondary, fontSize = 7.5.sp,
                 fontFamily = AudiowideFont)
            Spacer(Modifier.height(5.dp))
            Text("$level", color = MikuCyan, fontSize = 26.sp, fontWeight = FontWeight.Black,
                 fontFamily = AudiowideFont)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(150.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color(0x3300E5FF))) {
                Box(Modifier.fillMaxWidth(level / 100f).fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp)).background(MikuCyan))
            }
            Spacer(Modifier.height(4.dp))
            Text("independent of media volume", color = MikuTextSecondary, fontSize = 7.sp)
        }
    }
}
