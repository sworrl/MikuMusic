package com.m500.hardware

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val HwMikuTeal = Color(0xFF00E5FF)
val HwMikuPink = Color(0xFFFF4081)
val HwBgDark = Color(0xFF04161A)
val HwSurface1 = Color(0xFF0B242A)
val HwSurface2 = Color(0xFF13363E)
val HwMuted = Color(0xFF8BA7AB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
    // null = not probed yet; true/false = real result of reading the DAC sysfs directory
    var sysfsReachable by remember { mutableStateOf<Boolean?>(null) }

    // Cirrus Logic CS43198 DAC States — null until READ from sysfs / Settings.Global. Nothing is
    // pre-selected from a local default (the old NOS / LOW / +6 dB defaults were shown as if real).
    var csFilter by remember { mutableStateOf<CirrusLogicManager.DigitalFilter?>(null) }
    var csGain by remember { mutableStateOf<CirrusLogicManager.GainMode?>(null) }
    var csDre by remember { mutableStateOf<Boolean?>(null) }
    var csTurbo by remember { mutableStateOf<Boolean?>(null) }
    var csDsdComp by remember { mutableStateOf<Int?>(null) }
    var csOutput by remember { mutableStateOf<CirrusLogicManager.OutputMode?>(null) }
    var csBalance by remember { mutableStateOf<Float?>(null) }
    // Pulsar: whether the diode can be driven AT ALL on this unit (probed, not assumed).
    var pulsarDrivable by remember { mutableStateOf<Boolean?>(null) }

    // Fn Switch & Pocket Lock
    var fnMode by remember {
        mutableStateOf(Settings.Global.getString(ctx.contentResolver, "fn_settings") ?: "touch_and_key_lock")
    }
    var allowVolumeWheel by remember {
        mutableStateOf(Settings.Global.getInt(ctx.contentResolver, "m500_fn_allow_volume_wheel", 0) == 1)
    }

    // Pulsar RGB
    var pulsarEnabled by remember { mutableStateOf(PulsarLight.isEnabled(ctx)) }
    var pulsarMode by remember { mutableStateOf(PulsarLight.getMode(ctx)) }
    var pulsarBrightness by remember { mutableStateOf(PulsarLight.getBrightness(ctx).toFloat()) }

    // USB DAC
    var usbDacActive by remember { mutableStateOf(UsbDacManager.isActive(ctx)) }
    // Nullable: null = the user has never picked a rate/depth. The non-null getters fall back to
    // 192000 / 32, and these chips were rendering that build default as a highlighted selection.
    var usbDacRate by remember { mutableStateOf<Int?>(UsbDacManager.getSampleRateOrNull(ctx)) }
    var usbDacBits by remember { mutableStateOf<Int?>(UsbDacManager.getBitDepthOrNull(ctx)) }

    // CPU Performance
    var cpuGovernorOn by remember { mutableStateOf(CpuPerformance.isEnabled(ctx)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            isRooted = android.os.Process.myUid() == 1000 || RootShell.isAvailable() || ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            try {
                val targetRes = ctx.packageManager.getResourcesForApplication("com.android.settings")
                val idTitle = targetRes.getIdentifier("fn_function_title", "array", "com.android.settings")
                val idValue = targetRes.getIdentifier("fn_function_value", "array", "com.android.settings")
                val titles = targetRes.getStringArray(idTitle)
                val values = targetRes.getStringArray(idValue)
                Log.i("SettingsOverlayCheck", "Titles ($idTitle): ${titles.toList()}")
                Log.i("SettingsOverlayCheck", "Values ($idValue): ${values.toList()}")
            } catch (e: Throwable) {
                Log.e("SettingsOverlayCheck", "Failed to query settings resources", e)
            }
            try {
                Log.i("PulsarTest", "Testing setSystemProperty for LED...")
                val resLed = PulsarLight.setSystemProperty("vendor.audio.hiby.hw.led", "on")
                val resQuality = PulsarLight.setSystemProperty("vendor.audio.hiby.hw.sample_quality", "mqb")
                Log.i("PulsarTest", "Result: led=$resLed, quality=$resQuality")
            } catch (e: Throwable) {
                Log.e("PulsarTest", "Error testing setSystemProperty", e)
            }
            sysfsReachable = CirrusLogicManager.isSysfsReachable()
            csFilter = CirrusLogicManager.getDigitalFilter(ctx)
            csGain = CirrusLogicManager.getGainMode(ctx)
            csDre = CirrusLogicManager.isDreEnabled(ctx)
            csTurbo = CirrusLogicManager.isHighPowerEnabled(ctx)
            csDsdComp = CirrusLogicManager.getDsdGainCompensate(ctx)
            csOutput = CirrusLogicManager.getOutputMode(ctx)
            // getBalance() substitutes 0 when nothing is readable, which the UI then printed as
            // "Center" on an enabled slider. Only show a position when a real source reported one.
            csBalance = CirrusLogicManager.getBalanceOrNull(ctx)?.toFloat()
            pulsarDrivable = PulsarLight.isHardwareWritable()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "M500 HARDWARE & DAC",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = HwMikuTeal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HwBgDark)
            )
        },
        containerColor = HwBgDark
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF07272B))
                        .border(1.dp, HwMikuTeal.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    // Status reflects what was ACTUALLY probed: the DAC sysfs directory and the
                    // process's privilege. No unconditional "online / active" claims.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (sysfsReachable) { true -> "🟢"; false -> "🟡"; null -> "⚪" }, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (sysfsReachable) {
                                null -> "Probing DAC control path…"
                                true -> if (isRooted) "CS43198 DAC sysfs reachable (privileged)" else "CS43198 DAC sysfs reachable"
                                false -> "DAC sysfs not readable — Settings.Global fallback"
                            },
                            color = if (sysfsReachable == true) HwMikuTeal else HwMuted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (sysfsReachable) {
                            true -> "Filter, gain, DRE, output and balance are read from and written to ${CirrusLogicManager.SYSFS_BASE}. Controls below show the value the kernel reports; \"unknown\" means neither the kernel nor Settings.Global answered."
                            false -> "${CirrusLogicManager.SYSFS_BASE} is not readable by this process; controls show the last value persisted in Settings.Global (vendor.audio.hiby.*) and writes go through the shell path. \"unknown\" means no value has been set."
                            null -> "Reading kernel DAC nodes and HiBy audio settings…"
                        },
                        color = HwMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // Section 1: Cirrus Logic Dual CS43198 MasterHIFI™ Audio Architecture
            item {
                HwSettingsSection("Cirrus Logic Dual CS43198 Architecture")

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwMikuTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text("DIGITAL RECONSTRUCTION FILTER", color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    CirrusLogicManager.DigitalFilter.values().forEach { filter ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    csFilter = filter
                                    scope.launch { CirrusLogicManager.setDigitalFilter(ctx, filter) }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = csFilter == filter,
                                onClick = {
                                    csFilter = filter
                                    scope.launch { CirrusLogicManager.setDigitalFilter(ctx, filter) }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = HwMikuTeal, unselectedColor = HwMuted)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(filter.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(filter.description, color = HwMuted, fontSize = 11.sp, lineHeight = 14.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("ANALOG HEADPHONE GAIN", color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CirrusLogicManager.GainMode.values().forEach { gain ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (csGain == gain) HwMikuTeal else HwSurface2)
                                    .clickable {
                                        csGain = gain
                                        scope.launch { CirrusLogicManager.setGainMode(ctx, gain) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(gain.label, color = if (csGain == gain) Color.Black else Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("OUTPUT ROUTING (3.5mm SE / 4.4mm BAL)", color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CirrusLogicManager.OutputMode.values().forEach { mode ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (csOutput == mode) HwMikuPink else HwSurface2)
                                    .clickable {
                                        csOutput = mode
                                        scope.launch { CirrusLogicManager.setOutputMode(ctx, mode) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(mode.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    HwSettingsToggleRow(
                        title = "Dynamic Range Enhancement (DRE)",
                        subtitle = if (csDre == null) "Reading state…" else "Dynamically boosts SNR to 130dB+ for inaudible noise floor",
                        checked = csDre == true
                    ) { enabled ->
                        csDre = enabled
                        scope.launch { CirrusLogicManager.setDreEnabled(ctx, enabled) }
                    }

                    HwSettingsToggleRow(
                        title = "Audio Turbo High Power",
                        subtitle = if (csTurbo == null) "Reading state…" else "Increases current rails for demanding dynamic peaks",
                        checked = csTurbo == true
                    ) { enabled ->
                        csTurbo = enabled
                        scope.launch { CirrusLogicManager.setHighPowerEnabled(ctx, enabled) }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(if (csDsdComp == null) "DSD GAIN COMPENSATION (not set)" else "DSD GAIN COMPENSATION", color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "0 dB (Direct)", 6 to "+6 dB (SACD Standard)").forEach { (db, label) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (csDsdComp == db) HwMikuTeal else HwSurface2)
                                    .clickable {
                                        csDsdComp = db
                                        scope.launch { CirrusLogicManager.setDsdGainCompensate(ctx, db) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (csDsdComp == db) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    val bal = csBalance
                    Text("HARDWARE L/R BALANCE (${when { bal == null -> "unknown"; bal.toInt() == 0 -> "Center"; bal > 0 -> "+${bal.toInt()} R"; else -> "${bal.toInt()} L" }})", color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Slider(
                        value = bal ?: 0f,
                        enabled = bal != null,
                        onValueChange = {
                            csBalance = it
                            scope.launch { CirrusLogicManager.setBalance(ctx, it.toInt()) }
                        },
                        valueRange = -10f..10f,
                        steps = 19,
                        colors = SliderDefaults.colors(thumbColor = HwMikuTeal, activeTrackColor = HwMikuTeal, inactiveTrackColor = HwSurface2)
                    )
                }
            }

            // Section 2: Hardware Fn Switch & Pocket Lock
            item {
                HwSettingsSection("Hardware Fn Switch & Touch Lock")

                val isPocket = fnMode == "touch_and_key_lock" || fnMode == "Both"

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwMikuTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    HwSettingsToggleRow(
                        title = "Pocket Lock (Touch & Key Lock)",
                        subtitle = "Hardware-inhibits touchscreen digitizer and side buttons simultaneously",
                        checked = isPocket
                    ) { enabled ->
                        val next = if (enabled) "touch_and_key_lock" else "key_lock"
                        fnMode = next
                        Settings.Global.putString(ctx.contentResolver, "fn_settings", next)
                    }

                    if (isPocket) {
                        Spacer(Modifier.height(8.dp))
                        HwSettingsToggleRow(
                            title = "Allow Volume Wheel in Pocket",
                            subtitle = "Keep physical rotary knob active while screen and side buttons are locked",
                            checked = allowVolumeWheel
                        ) { enabled ->
                            allowVolumeWheel = enabled
                            Settings.Global.putInt(ctx.contentResolver, "m500_fn_allow_volume_wheel", if (enabled) 1 else 0)
                        }
                    }
                }
            }

            // Section 3: Pulsar RGB Audiophile Engine
            item {
                HwSettingsSection("Pulsar RGB LED Engine")

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwMikuTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    // Was an unqualified "Controls front RGB notification indicator and TrueColor
                    // PWM". Every write goes to LED sysfs nodes that are SELinux-denied on this
                    // unit (isHardwareWritable() probes it), so the control stores a preference and
                    // nothing lights up. Say which of the two it is instead of implying control.
                    HwSettingsToggleRow(
                        title = "Pulsar RGB Master Control",
                        subtitle = when (pulsarDrivable) {
                            null -> "Probing whether the LED nodes are writable\u2026"
                            true -> "Front RGB indicator: LED nodes are writable on this unit"
                            false -> "Saved preference only \u2014 the LED nodes are not writable on this unit, so the indicator will not respond"
                        },
                        checked = pulsarEnabled
                    ) { enabled ->
                        pulsarEnabled = enabled
                        scope.launch { PulsarLight.setEnabled(ctx, enabled) }
                    }

                    if (pulsarEnabled) {
                        Spacer(Modifier.height(12.dp))
                        Text("LIGHTING MODE", color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))

                        PulsarLight.Mode.values().filter { it != PulsarLight.Mode.OFF }.forEach { mode ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pulsarMode = mode
                                        scope.launch { PulsarLight.setMode(ctx, mode) }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = pulsarMode == mode,
                                    onClick = {
                                        pulsarMode = mode
                                        scope.launch { PulsarLight.setMode(ctx, mode) }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = HwMikuTeal, unselectedColor = HwMuted)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(mode.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(mode.description, color = HwMuted, fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("PEAK BRIGHTNESS (${(pulsarBrightness / 255f * 100).toInt()}%)", color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = pulsarBrightness,
                            onValueChange = {
                                pulsarBrightness = it
                                scope.launch { PulsarLight.setBrightness(ctx, it.toInt()) }
                            },
                            valueRange = 10f..255f,
                            colors = SliderDefaults.colors(thumbColor = HwMikuTeal, activeTrackColor = HwMikuTeal, inactiveTrackColor = HwSurface2)
                        )
                    }
                }
            }

            // Section 4: USB DAC UAC2 Bit-Perfect Subsystem
            item {
                HwSettingsSection("USB DAC (UAC2 Desktop Receiver)")

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwMikuTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    HwSettingsToggleRow(
                        title = "UAC2 USB DAC Mode",
                        subtitle = if (usbDacActive) "uac2 is in the live USB gadget config (sys.usb.state)" else "Exposes the M500 as a USB Audio Class 2 DAC to a PC/Mac",
                        checked = usbDacActive
                    ) { enabled ->
                        scope.launch {
                            UsbDacManager.setUsbDacMode(ctx, enabled)
                            // Was "usbDacActive = enabled" straight off the tap. The setprop path
                            // needs su and normally fails, so the row reported UAC2 composed when
                            // the gadget had not changed. isActive() reads sys.usb.state.
                            usbDacActive = withContext(Dispatchers.IO) { UsbDacManager.isActive(ctx) }
                            if (usbDacActive) {
                                val intent = android.content.Intent(ctx, UsbDacActivity::class.java).apply {
                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                ctx.startActivity(intent)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (usbDacRate == null) "SAMPLE RATE  ·  NOT SET (gadget would use ${UsbDacManager.DEFAULT_SAMPLE_RATE / 1000}k)" else "SAMPLE RATE",
                        color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(44100 to "44.1k", 96000 to "96k", 192000 to "192k", 384000 to "384k").forEach { (rate, label) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (usbDacRate == rate) HwMikuTeal else HwSurface2)
                                    .clickable {
                                        usbDacRate = rate
                                        scope.launch {
                                            UsbDacManager.configureParams(ctx, rate, usbDacBits ?: UsbDacManager.DEFAULT_BIT_DEPTH)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (usbDacRate == rate) Color.Black else Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (usbDacBits == null) "BIT DEPTH  ·  NOT SET (gadget would use ${UsbDacManager.DEFAULT_BIT_DEPTH}-bit)" else "BIT DEPTH",
                        color = HwMikuTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(16 to "16-bit", 24 to "24-bit", 32 to "32-bit Float").forEach { (bits, label) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (usbDacBits == bits) HwMikuTeal else HwSurface2)
                                    .clickable {
                                        usbDacBits = bits
                                        scope.launch {
                                            UsbDacManager.configureParams(ctx, usbDacRate ?: UsbDacManager.DEFAULT_SAMPLE_RATE, bits)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (usbDacBits == bits) Color.Black else Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Section 5: Snapdragon 680 Audiophile Governor
            item {
                HwSettingsSection("Snapdragon 680 Audiophile Governor")

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwMikuTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    // Was optimistic: the switch was set from the tap and never re-checked, while
                    // CpuPerformance.setEnabled() returns immediately when there is no su (the
                    // normal state here) - so the row claimed the cores were pinned when the
                    // governor had not moved. Now re-read from cpu0/cpufreq/scaling_governor.
                    HwSettingsToggleRow(
                        title = "Peak Clock Performance Governor",
                        subtitle = "Sets every CPU core's scaling_governor to \"performance\". Needs root on this build; the switch follows the governor the kernel actually reports.",
                        checked = cpuGovernorOn
                    ) { enabled ->
                        scope.launch {
                            CpuPerformance.setEnabled(ctx, enabled)
                            cpuGovernorOn = withContext(Dispatchers.IO) { CpuPerformance.isEnabled(ctx) }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun HwSettingsSection(title: String) {
    Text(
        title.uppercase(),
        color = HwMikuTeal,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun HwSettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = HwMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HwMikuTeal,
                checkedTrackColor = HwSurface2,
                uncheckedThumbColor = HwMuted,
                uncheckedTrackColor = HwSurface1
            )
        )
    }
}
