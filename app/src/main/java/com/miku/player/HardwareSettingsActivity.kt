package com.miku.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.player.CyberDarkBg
import com.miku.player.CyberGlassBorder
import com.miku.player.MikuCyan
import com.miku.player.MikuNeonPink
import com.miku.player.MikuTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HardwareSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setContent {
            HardwareSettingsScreen(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRooted by remember { mutableStateOf(false) }

    // Cirrus Logic CS43131 Hardware State
    var csFilter by remember { mutableStateOf(CirrusLogicManager.DigitalFilter.NOS) }
    var csGain by remember { mutableStateOf(CirrusLogicManager.GainMode.LOW) }
    var csDre by remember { mutableStateOf(true) }
    var csTurbo by remember { mutableStateOf(false) }
    var csDsdComp by remember { mutableStateOf(true) }
    var csOutput by remember { mutableStateOf(CirrusLogicManager.OutputMode.BAL_HEADPHONE_OUT) }

    // USB DAC
    var usbDacActive by remember { mutableStateOf(UsbDacManager.isActive(ctx)) }
    var usbDacRate by remember { mutableStateOf(UsbDacManager.getSampleRate(ctx)) }
    var usbDacBits by remember { mutableStateOf(UsbDacManager.getBitDepth(ctx)) }

    // CPU Audio Priority
    var cpuGovernorOn by remember { mutableStateOf(PlayerPreferences.loadCpuPerfEnabled(ctx)) }

    // Real-Time Kernel Sysfs Hardware State Audit
    var auditState by remember { mutableStateOf(CirrusLogicManager.HardwareAuditState()) }

    // DTA (bit-perfect DIRECT-to-DAC) live status + playback behavior prefs
    var dtaStatus by remember { mutableStateOf<MikuDirectAudio.DirectStatus?>(null) }
    var pauseOnUnplug by remember { mutableStateOf(PlayerPreferences.loadPauseOnUnplug(ctx)) }

    fun refreshAudit() {
        scope.launch(Dispatchers.IO) {
            auditState = CirrusLogicManager.getLiveHardwareAudit()
            dtaStatus = MikuDirectAudio.status(ctx)
        }
    }

    // Poll the HAL's direct flags while this screen is open so the readout is live truth,
    // not a stale snapshot — start/stop playback and watch it flip.
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { dtaStatus = MikuDirectAudio.status(ctx) }
            kotlinx.coroutines.delay(2000)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            isRooted = android.os.Process.myUid() == 1000 || RootShell.isAvailable() || ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            csFilter = CirrusLogicManager.getDigitalFilter(ctx)
            csGain = CirrusLogicManager.getGainMode(ctx)
            csDre = CirrusLogicManager.isDreEnabled(ctx)
            csTurbo = CirrusLogicManager.isHighPowerEnabled(ctx)
            csDsdComp = CirrusLogicManager.getDsdGainCompensate(ctx)
            csOutput = CirrusLogicManager.getOutputMode(ctx)
            auditState = CirrusLogicManager.getLiveHardwareAudit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        // Bespoke Hatsune Miku Audiophile Stage Artwork Backdrop
        Image(
            painter = painterResource(id = R.drawable.miku_audiophile_art),
            contentDescription = "Miku Audio Controller Artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Frosted Dark Cyan Gradient Overlay
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xEE040D12),
                            Color(0x99000000),
                            Color(0xF8040D12)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Header Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.miku.player.ui.MikuBackButton(onClick = onBack)

                Text(
                    "MIKU CYBER AUDIO CONTROLLER",
                    color = MikuCyan,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )

                // DAC Chipset Badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x3300E5FF))
                        .border(1.dp, MikuCyan, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "CS43131",
                        color = MikuCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ============================================================
                // HARDWARE STATUS & LIVE KERNEL AUDIT BANNER
                // ============================================================
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xDD0A1E26))
                            .border(1.5.dp, MikuCyan, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E676))
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "CIRRUS LOGIC MASTERHIFI™ DIRECT HAL",
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                            }
                            IconButton(
                                onClick = { refreshAudit() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    painterResource(id = R.drawable.ic_settings_miku),
                                    contentDescription = "Refresh Audit",
                                    tint = MikuCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Direct register orchestration for dual CS43131 DACs, NOS analog interpolation, hardware gain stages, and bit-perfect UAC2 USB audio.",
                            color = MikuTextSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 14.5.sp
                        )
                        Spacer(Modifier.height(10.dp))

                        // Live Kernel Sysfs Audit Box
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF051014))
                                .border(1.dp, Color(0x3300E5FF), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                "LIVE KERNEL SYSFS STATE (/sys/.../sa_sound_setting/)",
                                color = MikuCyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Filter: ${auditState.kernelFilter}", color = Color.White, fontSize = 10.sp)
                                Text("Gain: ${auditState.kernelGain}", color = if (auditState.kernelGain.contains("high")) MikuNeonPink else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(3.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("High Power: ${auditState.kernelHighPower}", color = MikuTextSecondary, fontSize = 9.5.sp)
                                Text("DRE Mode: ${auditState.kernelDre}", color = MikuTextSecondary, fontSize = 9.5.sp)
                            }
                            Spacer(Modifier.height(3.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Turbo: ${auditState.kernelTurbo}", color = MikuTextSecondary, fontSize = 9.5.sp)
                                Text("Output: ${auditState.kernelOutput}", color = MikuCyan, fontSize = 9.5.sp)
                            }
                        }
                    }
                }

                // ============================================================
                // SECTION 1: CIRRUS LOGIC CS43131 DAC ARCHITECTURE
                // ============================================================
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xDD0A1E26))
                            .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            "DIGITAL INTERPOLATION FILTER",
                            color = MikuCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Best Practice: Use NOS (Non-Oversampling) for zero pre/post-ringing analog warmth. Use Fast Roll-off for pristine studio master accuracy.",
                            color = MikuTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 13.5.sp
                        )
                        Spacer(Modifier.height(8.dp))

                        CirrusLogicManager.DigitalFilter.values().forEach { filter ->
                            val isSel = csFilter == filter
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) Color(0x3300E5FF) else Color.Transparent)
                                    .clickable {
                                        csFilter = filter
                                        scope.launch {
                                            CirrusLogicManager.setDigitalFilter(ctx, filter)
                                            refreshAudit()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSel,
                                    onClick = {
                                        csFilter = filter
                                        scope.launch {
                                            CirrusLogicManager.setDigitalFilter(ctx, filter)
                                            refreshAudit()
                                        }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = MikuCyan, unselectedColor = Color.Gray)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(filter.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(filter.description, color = MikuTextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(14.dp))

                        Text(
                            "ANALOG HEADPHONE GAIN STAGE",
                            color = MikuCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Best Practice: Use Low Gain (0 dB) for sensitive IEMs to eliminate hiss. Use High Gain (+6 dB) for high-impedance planar and dynamic headphones.",
                            color = MikuTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 13.5.sp
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CirrusLogicManager.GainMode.values().forEach { gain ->
                                val isSel = csGain == gain
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSel) MikuCyan else Color(0x44040D12))
                                        .border(1.dp, if (isSel) MikuCyan else CyberGlassBorder, RoundedCornerShape(12.dp))
                                        .clickable {
                                            csGain = gain
                                            scope.launch {
                                                CirrusLogicManager.setGainMode(ctx, gain)
                                                refreshAudit()
                                            }
                                        }
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            gain.label,
                                            color = if (isSel) Color.Black else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            if (gain == CirrusLogicManager.GainMode.LOW) "0 dB (IEMs)" else "+6 dB (High-Z)",
                                            color = if (isSel) Color.Black.copy(alpha = 0.8f) else MikuTextSecondary,
                                            fontSize = 9.5.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(14.dp))

                        // Dynamic Range Enhancement & High Power Turbo
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Dynamic Range Enhancement (DRE)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Expands CS43131 dynamic range to 130 dB SNR for extreme micro-detail extraction.", color = MikuTextSecondary, fontSize = 10.sp)
                            }
                            Switch(
                                checked = csDre,
                                onCheckedChange = {
                                    csDre = it
                                    scope.launch {
                                        CirrusLogicManager.setDreEnabled(ctx, it)
                                        refreshAudit()
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = MikuCyan, checkedTrackColor = Color(0xFF00695C))
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("High-Power Output Turbo (+2 dBV)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Unlocks extra analog voltage swing on 4.4mm balanced output (4.0 Vrms max).", color = MikuTextSecondary, fontSize = 10.sp)
                            }
                            Switch(
                                checked = csTurbo,
                                onCheckedChange = {
                                    csTurbo = it
                                    scope.launch {
                                        CirrusLogicManager.setHighPowerEnabled(ctx, it)
                                        refreshAudit()
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = MikuNeonPink, checkedTrackColor = Color(0xFF880E4F))
                            )
                        }
                    }
                }

                // ============================================================
                // SECTION 1.2: AUDIO OUTPUT ROUTING MATRIX & QUICK SWAP
                // ============================================================
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xDD0A1E26))
                            .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("AUDIO OUTPUT ROUTING MATRIX", color = MikuCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                Text("Select primary DAC route or wireless Bluetooth audio stream", color = MikuTextSecondary, fontSize = 10.sp)
                            }

                            // Quick Swap Button
                            Button(
                                onClick = {
                                    scope.launch {
                                        csOutput = CirrusLogicManager.swapOutputMode(ctx)
                                        refreshAudit()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MikuCyan),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("⇄ Swap 4.4mm/BT", color = MikuCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        CirrusLogicManager.OutputMode.values().forEach { out ->
                            val isSel = csOutput == out
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) MikuCyan.copy(alpha = 0.15f) else Color.Transparent)
                                    .border(1.dp, if (isSel) MikuCyan.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(10.dp))
                                    .clickable {
                                        csOutput = out
                                        scope.launch {
                                            CirrusLogicManager.setOutputMode(ctx, out)
                                            refreshAudit()
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(out.icon, fontSize = 16.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(out.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        if (isSel) {
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MikuCyan.copy(alpha = 0.25f))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text("ACTIVE", color = MikuCyan, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    Text(out.description, color = MikuTextSecondary, fontSize = 10.sp)
                                }
                                RadioButton(
                                    selected = isSel,
                                    onClick = {
                                        csOutput = out
                                        scope.launch {
                                            CirrusLogicManager.setOutputMode(ctx, out)
                                            refreshAudit()
                                        }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = MikuCyan, unselectedColor = MikuTextSecondary)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                // ============================================================
                // SECTION 1.3: CAR & ANDROID AUTO HI-RES AUDIO POLICY
                // ============================================================
                item {
                    var allowUsbAudio by remember { mutableStateOf(PlayerPreferences.loadAllowUsbAudio(ctx)) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xDD0A1E26))
                            .border(1.dp, if (allowUsbAudio) Color(0xFFFF9100) else MikuCyan, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("CAR & ANDROID AUTO AUDIO ROUTING", color = MikuCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                Text("Enforce Hi-Res 3.5mm/4.4mm AUX output in car mode. Blocks USB audio degradation.", color = MikuTextSecondary, fontSize = 10.sp)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Block USB Audio in Car / Android Auto", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (!allowUsbAudio) "LOCKED: Audio strictly routes to 3.5mm/4.4mm AUX DAC jack for maximum sound quality (Recommended)."
                                    else "WARNING: USB Audio enabled (Audio will route to car head unit via USB).",
                                    color = if (!allowUsbAudio) MikuCyan else Color(0xFFFF9100),
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = !allowUsbAudio,
                                onCheckedChange = { blockUsb ->
                                    val allow = !blockUsb
                                    allowUsbAudio = allow
                                    PlayerPreferences.saveAllowUsbAudio(ctx, allow)
                                    MikuCarAudioRouter.ensureAnalogIfCar(ctx, "settings-toggle")
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = MikuCyan, checkedTrackColor = Color(0xFF00695C))
                            )
                        }
                    }
                }

                // ============================================================
                // SECTION 1.5: DTA — DIRECT TRANSPORT AUDIO (BIT-PERFECT PIPELINE)
                // ============================================================
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xCC07131A))
                            .border(1.dp, MikuCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text("DIRECT TRANSPORT AUDIO (DTA)", color = MikuCyan, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                        Text(
                            "Bit-perfect route: native sample rate straight to the dual CS43198 DACs, bypassing the Android 48 kHz mixer entirely. Read back live from the audio HAL — this is measured truth, not a claim.",
                            color = MikuTextSecondary, fontSize = 10.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        val st = dtaStatus
                        val direct = st?.weHoldDirect == true
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(10.dp).clip(CircleShape)
                                    .background(if (direct) Color(0xFF00E676) else if (st?.directFlagEnabled == true) Color(0xFFFFD600) else Color(0xFFFF1744))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    st == null -> "Reading HAL…"
                                    direct -> "DIRECT ● bit-perfect to DAC (this app holds the route)"
                                    st.directFlagEnabled -> "DIRECT held by: ${st.holderProcess.ifBlank { "unknown" }}"
                                    else -> "MIXED — Android 48 kHz pipeline (no direct route active)"
                                },
                                color = if (direct) Color(0xFF00E676) else Color.White,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Allow-list: " + if (st?.allowListed == true) "com.miku.player granted ✓" else "NOT in direct_support_app_list ✗",
                            color = if (st?.allowListed == true) MikuCyan else Color(0xFFFF1744),
                            fontSize = 9.5.sp
                        )
                        Text(
                            "Direct engages while music is PLAYING on a wired output (never A2DP/speaker).",
                            color = MikuTextSecondary, fontSize = 9.sp
                        )
                        if (st?.allowListed != true) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        MikuDirectAudio.ensureAllowListed(ctx)
                                        dtaStatus = MikuDirectAudio.status(ctx)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A86B)),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("GRANT DIRECT ROUTE", fontWeight = FontWeight.Black, fontFamily = AudiowideFont, fontSize = 10.sp) }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(14.dp))

                        // Playback behavior — granular, lives here + quick settings (NOT volume modal)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Pause on headphone unplug", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Stock HiBy behavior: pulling the jack pauses playback instead of blasting the speaker path.", color = MikuTextSecondary, fontSize = 10.sp)
                            }
                            Switch(
                                checked = pauseOnUnplug,
                                onCheckedChange = {
                                    pauseOnUnplug = it
                                    PlayerHolder.applyPauseOnUnplug(ctx, it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = MikuCyan, checkedTrackColor = Color(0xFF00695C))
                            )
                        }
                    }
                }

                // ============================================================
                // SECTION 1.6: AUDIO PLAYBACK ENGINE ("SWAPPABLE BONE")
                // ============================================================
                item {
                    var audioEngine by remember { mutableStateOf(PlayerPreferences.loadAudioEngine(ctx)) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xDD0A1E26))
                            .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            "AUDIO PLAYBACK CORE (SWAPPABLE BONE)",
                            color = MikuCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Select the active audio decoding pipeline. DirectPCM routes straight to the dual CS43198 DACs, while LibVLC provides the alternate OpenSL ES engine.",
                            color = MikuTextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 13.5.sp
                        )
                        Spacer(Modifier.height(8.dp))

                        // Option 1: DirectPCM (ExoPlayer)
                        val isExo = audioEngine == "exoplayer"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isExo) Color(0x3300E5FF) else Color.Transparent)
                                .clickable {
                                    audioEngine = "exoplayer"
                                    PlayerPreferences.saveAudioEngine(ctx, "exoplayer")
                                    android.widget.Toast.makeText(ctx, "⚡ DirectPCM Engine Active", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isExo,
                                onClick = {
                                    audioEngine = "exoplayer"
                                    PlayerPreferences.saveAudioEngine(ctx, "exoplayer")
                                    android.widget.Toast.makeText(ctx, "⚡ DirectPCM Engine Active", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = MikuCyan, unselectedColor = Color.Gray)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("DirectPCM Bit-Perfect DTA (ExoPlayer)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Direct hardware sink to dual CS43198 DACs (Bit-perfect, lowest latency)", color = MikuTextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Option 2: LibVLC Engine
                        val isVlc = audioEngine == "vlc"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isVlc) Color(0x3300E5FF) else Color.Transparent)
                                .clickable {
                                    audioEngine = "vlc"
                                    PlayerPreferences.saveAudioEngine(ctx, "vlc")
                                    android.widget.Toast.makeText(ctx, "⚡ LibVLC Engine Active", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isVlc,
                                onClick = {
                                    audioEngine = "vlc"
                                    PlayerPreferences.saveAudioEngine(ctx, "vlc")
                                    android.widget.Toast.makeText(ctx, "⚡ LibVLC Engine Active", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = MikuCyan, unselectedColor = Color.Gray)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("LibVLC Engine (OpenSL ES + SoX)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Modular OpenSL ES backend with high-precision soxr resampler", color = MikuTextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
                            }
                        }
                    }
                }

                // ============================================================
                // SECTION 2: BIT-PERFECT UAC2 ASYNCHRONOUS USB DAC
                // ============================================================
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xDD0A1E26))
                            .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("USB AUDIO CLASS 2.0 (UAC2)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Connect to PC / Mac / iOS for bit-perfect 32-bit/768kHz and native DSD256 decoding.", color = MikuTextSecondary, fontSize = 10.sp)
                            }
                            Switch(
                                checked = usbDacActive,
                                onCheckedChange = {
                                    usbDacActive = it
                                    scope.launch { UsbDacManager.setUsbDacMode(ctx, it) }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = MikuCyan, checkedTrackColor = Color(0xFF00695C))
                            )
                        }

                        if (usbDacActive) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Active Stream Clock", color = MikuTextSecondary, fontSize = 10.5.sp)
                                Text("${usbDacRate / 1000} kHz / ${usbDacBits}-bit", color = MikuCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
