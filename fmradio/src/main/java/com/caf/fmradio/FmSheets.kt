package com.caf.fmradio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** A scrim plus a bottom card. Written by hand rather than with ModalBottomSheet because this
 *  activity runs fully immersive with no system bars, and the sheet component wants insets the
 *  window does not have. */
@Composable
fun FmSheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Color(0xF5061219))
                .border(1.dp, MikuCyan.copy(alpha = 0.6f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(MikuCyan.copy(alpha = 0.5f))
            )
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text.uppercase(Locale.US), color = MikuCyan, fontSize = 11.sp,
        fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, letterSpacing = 1.sp
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SheetNote(text: String) {
    Text(text, color = MikuTextSecondary, fontSize = 8.sp, lineHeight = 11.sp)
}

// ---------------------------------------------------------------------------- scan results

@Composable
fun FmScanResults(st: FmState, onTuned: () -> Unit) {
    SheetTitle("Stations found · ${st.band.label}")
    if (st.scanResults.isEmpty()) {
        SheetNote(
            "Nothing yet. Tap the scan key to sweep the band; every stop is a frequency the " +
                "tuner itself locked onto, with the signal measured at that moment. With no " +
                "antenna attached a sweep will find nothing, which is the honest result."
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
        items(st.scanResults) { hit ->
            val current = hit.freqKHz == st.frequencyKHz
            val fav = st.favorites.contains(hit.freqKHz)
            Row(
                Modifier.fillMaxWidth()
                    .clickable { FmRadioManager.tune(hit.freqKHz); onTuned() }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    String.format(Locale.US, "%.1f", hit.freqKHz / 1000.0),
                    color = if (current) MikuCyan else Color.White,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont,
                    modifier = Modifier.width(62.dp)
                )
                Text("MHz", color = MikuTextSecondary, fontSize = 8.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    buildString {
                        append("RSSI ").append(hit.rssi?.toString() ?: "—")
                        hit.snr?.let { append("   SNR ").append(it) }
                    },
                    color = MikuTextSecondary, fontSize = 9.sp, fontFamily = AudiowideFont
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    if (fav) Icons.Default.Star else Icons.Default.StarBorder, "Preset",
                    tint = if (fav) MikuNeonPink else Color.Gray,
                    modifier = Modifier.size(16.dp).clickable { FmRadioManager.togglePreset(hit.freqKHz) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- settings

@Composable
fun FmSettings(st: FmState) {
    SheetTitle("Tuner settings")
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("REGION", color = MikuTextSecondary, fontSize = 7.5.sp, fontFamily = AudiowideFont)
        Spacer(Modifier.height(4.dp))
        FmBandPlan.entries.forEach { plan ->
            val sel = plan == st.band
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (sel) MikuCyan.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { FmRadioManager.setBand(plan) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    plan.label, color = if (sel) MikuCyan else Color.White,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "%.1f–%.1f MHz · %d kHz · %d µs".format(
                        plan.lowKHz / 1000.0, plan.highKHz / 1000.0, plan.stepKHz, plan.emphasisUs
                    ),
                    color = MikuTextSecondary, fontSize = 8.sp
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        SheetNote(
            "The region sets the band limits, channel spacing, de-emphasis and RDS standard, " +
                "all of which the chip only reads when it is enabled. Changing it therefore " +
                "power-cycles the tuner."
        )

        Spacer(Modifier.height(12.dp))
        Text("SEEK SENSITIVITY", color = MikuTextSecondary, fontSize = 7.5.sp, fontFamily = AudiowideFont)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            QualcommFmHardwareEngine.SENSITIVITY_LABELS.forEachIndexed { i, label ->
                val sel = i == st.seekSensitivity
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) MikuCyan else Color(0x2200E5FF))
                        .border(1.dp, if (sel) MikuCyan else CyberGlassBorder, RoundedCornerShape(8.dp))
                        .clickable { FmRadioManager.setSeekSensitivity(i) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, color = if (sel) Color.Black else MikuTextSecondary,
                        fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        SheetNote("How strong a carrier has to be before seek will stop on it.")

        Spacer(Modifier.height(10.dp))
        SettingSwitch(
            "Stereo",
            "Ask for stereo decoding. The chip drops to mono by itself on a weak signal, and the " +
                "indicator above shows what it actually did, not what was requested.",
            st.stereoRequested
        ) { FmRadioManager.setStereo(it) }

        SettingSwitch(
            "Soft mute",
            "Let the tuner attenuate between stations instead of handing you full-scale hiss.",
            st.softMute
        ) { FmRadioManager.setSoftMute(it) }

        SettingSwitch(
            "Follow alternative frequencies",
            "Use the RDS AF list to hop to a stronger transmitter carrying the same programme. " +
                "Off by default: it retunes without being asked, which is not always wanted.",
            st.afJump
        ) { FmRadioManager.setAfJump(it) }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = MikuTextSecondary, fontSize = 7.5.sp, lineHeight = 10.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = MikuCyan,
                uncheckedThumbColor = MikuTextSecondary,
                uncheckedTrackColor = Color(0x2200E5FF),
            )
        )
    }
}

// ---------------------------------------------------------------------------- diagnostics

@Composable
fun FmDiagnosticsPanel(st: FmState) {
    val d = st.diagnostics
    val s = st.signal
    SheetTitle("Tuner diagnostics")
    SheetNote(
        "Everything on this page is read back from the driver or the audio HAL. A dash means " +
            "the question was asked and nothing came back, not that the value is zero."
    )
    Spacer(Modifier.height(8.dp))
    Column(Modifier.verticalScroll(rememberScrollState())) {
        DiagRow("JNI library", if (d.jniLoaded) "loaded" else "NOT loaded")
        DiagRow("FM SoC", d.socName ?: "—")
        DiagRow("Chip state", d.fmState?.toString() ?: "—")
        DiagRow("Antenna", if (st.isHeadsetPlugged) "headphone cable attached" else "none, the cable IS the antenna")
        DiagRow("Band plan", "${st.band.label} · ${st.band.lowKHz / 1000.0}–${st.band.highKHz / 1000.0} MHz")
        DiagRow("Tuned (app)", "${st.frequencyKHz} kHz")
        DiagRow("Tuned (V4L2 read)", d.driverFreqKHz?.let { "$it kHz" } ?: "—")
        Spacer(Modifier.height(8.dp))
        DiagRow("Audio route", "${d.routeLabel} (code ${d.routeCode})")
        DiagRow("handle_fm written", d.handleFmWritten?.let { "$it (0x${it.toString(16)})" } ?: "— (stopped)")
        DiagRow("HAL fm_status", when (d.halLoopback) { true -> "1 · FM session running"; false -> "0 · not running"; null -> "—" })
        DiagRow("fm_volume", d.fmVolumeLinear?.let { String.format(Locale.US, "%.4f", it) } ?: "—")
        DiagRow("SLIMbus ack", d.slimbusStatus?.toString() ?: "—")
        DiagRow("Driver mute", when (d.driverMuted) { true -> "muted"; false -> "open"; null -> "—" })
        DiagRow("App mute", if (st.isMuted) "muted" else "open")
        DiagRow("PCM level", if (st.isPowerOn) String.format(Locale.US, "%.4f RMS", st.audioLevel) else "—")
        DiagRow("Capture framing", when (st.captureFraming) {
            true -> "vendor 6-byte frames, decoded to ${FmCaptureFrame.DECODED_RATE_HZ / 1000} kHz mono"
            false -> "plain 16-bit PCM as advertised"
            null -> "—"
        })
        DiagRow("Spectrum range", "60 Hz to ${st.spectrumTopHz} Hz")
        Spacer(Modifier.height(8.dp))
        // Two tuners, read separately. Only the one with an antenna will move with frequency.
        DiagRow("RSSI · Si4705 (radio0)", d.rssiSi4705?.toString() ?: "—")
        DiagRow("RSSI · Qualcomm (HCI)", d.rssiQualcomm?.toString() ?: "—")
        DiagRow("SINR · Qualcomm (HCI)", d.sinrQualcomm?.toString() ?: "—")
        DiagRow("RSSI in use", s.rssi?.toString() ?: st.rssi?.toString() ?: "—")
        DiagRow("SNR", s.snr?.toString() ?: "—")
        DiagRow("Multipath", s.multipath?.toString() ?: "—")
        DiagRow("Frequency offset", s.freqOffset?.toString() ?: "—")
        DiagRow("Reading valid", when (s.valid) { true -> "yes"; false -> "no"; null -> "—" })
        DiagRow("Stereo pilot", when (st.isStereo) { true -> "stereo"; false -> "mono"; null -> "—" })
        DiagRow("RDS", when (st.rdsAvailable) { true -> "locked"; false -> "none"; null -> "—" })
        DiagRow("RDS PI", st.programmeId?.let { "0x${it.toString(16).uppercase(Locale.US)}" } ?: "—")
        DiagRow("RDS PTY", FmRadioManager.programmeTypeName(st.programmeType, st.band)
            ?: st.programmeType?.toString() ?: "—")
        st.hardwareError?.let {
            Spacer(Modifier.height(8.dp))
            Text("LAST ERROR", color = MikuNeonPink, fontSize = 7.5.sp, fontFamily = AudiowideFont)
            Text(it, color = MikuNeonPink, fontSize = 8.sp, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = MikuTextSecondary, fontSize = 8.5.sp, modifier = Modifier.width(132.dp))
        Text(value, color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------- keypad

/** Direct frequency entry, because hunting 20 MHz with a step key is not a control surface. */
@Composable
fun ColumnScope.FmKeypad(st: FmState, onTuned: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    val low = st.band.lowKHz / 1000.0
    val high = st.band.highKHz / 1000.0

    fun commit() {
        val mhzValue = entry.toDoubleOrNull() ?: return
        val khz = Math.round(mhzValue * 1000).toInt()
        if (khz in st.band.lowKHz..st.band.highKHz) {
            val step = st.band.stepKHz
            FmRadioManager.tune(((khz + step / 2) / step) * step)
            onTuned()
        }
    }

    SheetTitle("Tune directly")
    Text(
        entry.ifEmpty { "—" },
        color = if (entry.isEmpty()) Color.Gray else Color.White,
        fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )
    Text(
        String.format(Locale.US, "%.1f – %.1f MHz, %d kHz steps", low, high, st.band.stepKHz),
        color = MikuTextSecondary, fontSize = 8.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )
    Spacer(Modifier.height(8.dp))

    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(Color(0x2200E5FF))
                            .border(1.dp, CyberGlassBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                entry = when {
                                    k == "⌫" -> entry.dropLast(1)
                                    k == "." && entry.contains('.') -> entry
                                    entry.length >= 6 -> entry
                                    else -> entry + k
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(k, color = MikuCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(MikuCyan).clickable { commit() }.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("TUNE", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont)
        }
    }
}
