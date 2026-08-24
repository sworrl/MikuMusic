package com.miku.settings

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.miku.settings.bluetooth.*
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Hand off to the REAL (AOSP) Settings app for screens MikuOS does not implement itself
 * (Wi-Fi picker, Bluetooth pairing, app manager, storage browser, developer options, about).
 *
 * MikuSettings declares these same intent actions (WIFI_SETTINGS, BLUETOOTH_SETTINGS, etc.)
 * in its own manifest, so firing a bare Intent(action) resolves right back to com.miku.settings
 * (an infinite self-loop). We therefore:
 *   1. Scope the intent to com.android.settings so it can never resolve to ourselves.
 *   2. Fall back to an unscoped intent only if it resolves to some OTHER package.
 *   3. Otherwise show a Toast instead of looping or silently failing.
 */
fun openAospSettings(ctx: Context, action: String, fallbackMsg: String = "Not available on this device") {
    val pm = ctx.packageManager
    val explicitComponent = when (action) {
        Settings.ACTION_BLUETOOTH_SETTINGS -> android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$ConnectedDeviceDashboardActivity")
        Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS -> android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$NetworkDashboardActivity")
        Settings.ACTION_DISPLAY_SETTINGS -> android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$DisplaySettingsActivity")
        Settings.ACTION_SOUND_SETTINGS -> android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$SoundSettingsActivity")
        Settings.ACTION_DEVICE_INFO_SETTINGS -> android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$MyDeviceInfoActivity")
        Settings.ACTION_APPLICATION_SETTINGS -> android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$ManageApplicationsActivity")
        Settings.ACTION_INTERNAL_STORAGE_SETTINGS -> android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$StorageDashboardActivity")
        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS -> android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity")
        else -> null
    }

    if (explicitComponent != null) {
        val intent = Intent(action).apply {
            component = explicitComponent
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { ctx.startActivity(intent); return } catch (_: Throwable) {}
    }

    // 1. Explicit AOSP Settings package query — find any exported activity in com.android.settings
    val scoped = Intent(action).apply {
        setPackage("com.android.settings")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val matches = pm.queryIntentActivities(scoped, 0)
    for (m in matches) {
        if (m.activityInfo.packageName == "com.android.settings") {
            val direct = Intent(action).apply {
                setClassName("com.android.settings", m.activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { ctx.startActivity(direct); return } catch (_: Throwable) {}
        }
    }

    // 2. Unscoped fallback, only if it resolves to something other than ourselves
    val generic = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    val resolved = generic.resolveActivity(pm)
    if (resolved != null && resolved.packageName != ctx.packageName) {
        try { ctx.startActivity(generic); return } catch (_: Throwable) {}
    }

    // 3. Fallback Toast
    try { Toast.makeText(ctx, fallbackMsg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
}

enum class SettingsSection(val title: String, val icon: ImageVector, val desc: String) {
    AUDIO_DAC("DAC & Audio", Icons.Default.Headphones, "Cirrus Dual CS43198 MasterHIFI, Gain, Filters & USB DAC"),
    PULSAR_RGB("Pulsar Light", Icons.Default.Lightbulb, "Dual-die front RGB LED, dynamic modes & BPM pulse"),
    FN_SWITCH("FN Switch & Keys", Icons.Default.ToggleOn, "Hardware Fn lock switch (Screen & Keys Lock default)"),
    WIRELESS("Network & ADB", Icons.Default.Wifi, "Wi-Fi, Wireless ADB, Hotspot & Network tools"),
    BLUETOOTH("Bluetooth", Icons.Default.Bluetooth, "Audio streaming codecs, LDAC, aptX & paired gear"),
    DISPLAY("Display & Light", Icons.Default.BrightnessMedium, "Brightness, ambient light sensor, screen timeout & theme"),
    BATTERY("Battery & Power", Icons.Default.BatteryChargingFull, "Live telemetry, voltage, current mA & battery health"),
    STORAGE_APPS("Apps & Storage", Icons.Default.Storage, "Internal memory, MicroSD card & application manager"),
    SYSTEM_ABOUT("About MikuOS", Icons.Default.Info, "M500 Miku Edition specs, kernel, Snapdragon 680 & root")
}

class MikuSettingsActivity : ComponentActivity() {
    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        val targetAction = intent?.action ?: ""
        val extraSec = intent?.getStringExtra("extra_section") ?: intent?.getStringExtra("section") ?: ""
        val initialSection = when {
            extraSec.equals("wireless", ignoreCase = true) || extraSec.equals("wifi", ignoreCase = true) || targetAction == Settings.ACTION_WIFI_SETTINGS || targetAction == Settings.ACTION_WIRELESS_SETTINGS -> SettingsSection.WIRELESS
            extraSec.equals("bluetooth", ignoreCase = true) || extraSec.equals("bt", ignoreCase = true) || targetAction == Settings.ACTION_BLUETOOTH_SETTINGS -> SettingsSection.BLUETOOTH
            extraSec.equals("audio_dac", ignoreCase = true) || extraSec.equals("dac", ignoreCase = true) || targetAction == "com.m500.hardware.action.USB_DAC" || targetAction == Settings.ACTION_SOUND_SETTINGS || targetAction == "android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL" -> SettingsSection.AUDIO_DAC
            extraSec.equals("pulsar", ignoreCase = true) || extraSec.equals("rgb", ignoreCase = true) || targetAction == "com.m500.hardware.action.PULSAR_SETTINGS" -> SettingsSection.PULSAR_RGB
            extraSec.equals("fn_switch", ignoreCase = true) || extraSec.equals("fn", ignoreCase = true) || targetAction == "com.m500.hardware.action.FN_SETTINGS" -> SettingsSection.FN_SWITCH
            extraSec.equals("display", ignoreCase = true) || targetAction == Settings.ACTION_DISPLAY_SETTINGS -> SettingsSection.DISPLAY
            extraSec.equals("storage_apps", ignoreCase = true) || extraSec.equals("storage", ignoreCase = true) || targetAction == Settings.ACTION_APPLICATION_SETTINGS || targetAction == Settings.ACTION_INTERNAL_STORAGE_SETTINGS -> SettingsSection.STORAGE_APPS
            extraSec.equals("battery", ignoreCase = true) || targetAction == Settings.ACTION_BATTERY_SAVER_SETTINGS -> SettingsSection.BATTERY
            extraSec.equals("system_about", ignoreCase = true) || extraSec.equals("about", ignoreCase = true) || targetAction == Settings.ACTION_DEVICE_INFO_SETTINGS -> SettingsSection.SYSTEM_ABOUT
            else -> null
        }

        setContent {
            MikuOSSettingsApp(initialSection = initialSection, onExit = { finish() })
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MikuOSSettingsApp(initialSection: SettingsSection?, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var currentSection by remember { mutableStateOf(initialSection) }
    var searchQuery by remember { mutableStateOf("") }

    val handleBack = {
        if (currentSection != null) {
            currentSection = null
        } else {
            onExit()
        }
    }

    BackHandler(enabled = true) {
        handleBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (currentSection == null) "MIKU" else currentSection!!.title,
                            color = MikuTealBright,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black
                        )
                        if (currentSection == null) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "OS SETTINGS",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MikuTeal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MikuDarkBg)
            )
        },
        containerColor = MikuDarkBg
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .mikuEdgeSwipeBack { handleBack() }
        ) {
            if (currentSection == null) {
                // AOSP / Pixel-Style Master Settings List
                val filteredSections = remember(searchQuery) {
                    if (searchQuery.isBlank()) SettingsSection.values().toList()
                    else SettingsSection.values().filter {
                        it.title.contains(searchQuery, ignoreCase = true) || it.desc.contains(searchQuery, ignoreCase = true)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Pixel-style Search Bar
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MikuSurface1)
                                .border(1.dp, MikuTeal.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = MikuTealBright,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    decorationBox = { innerTextField ->
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                "Search MikuOS settings...",
                                                color = MikuMuted,
                                                fontSize = 13.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }
                    }

                    // 2. Settings Category Tiles
                    items(filteredSections.size) { idx ->
                        val sec = filteredSections[idx]
                        val iconColors = listOf(
                            MikuTealBright,
                            MikuPurple,
                            MikuTeal,
                            Color(0xFF00FF88),
                            MikuGold,
                            Color(0xFFFF5252),
                            MikuPurpleDeep,
                            MikuPink
                        )
                        val tileAccent = iconColors[sec.ordinal % iconColors.size]

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MikuCardBg)
                                .border(1.dp, tileAccent.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                                .clickable { currentSection = sec }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(tileAccent.copy(alpha = 0.18f))
                                    .border(1.dp, tileAccent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    sec.icon,
                                    contentDescription = null,
                                    tint = tileAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(Modifier.width(14.dp))

                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = sec.title,
                                    color = Color.White,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = sec.desc,
                                    color = MikuMuted,
                                    fontSize = 11.5.sp,
                                    maxLines = 2,
                                    lineHeight = 14.sp
                                )
                            }

                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MikuMuted,
                                modifier = Modifier
                                    .size(16.dp)
                                    .graphicsLayer { rotationZ = 180f }
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            } else {
                // Section Detail Screen
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                ) {
                    when (currentSection) {
                        SettingsSection.AUDIO_DAC -> AudioDacScreen(ctx)
                        SettingsSection.PULSAR_RGB -> PulsarScreen(ctx)
                        SettingsSection.FN_SWITCH -> FnSwitchScreen(ctx)
                        SettingsSection.WIRELESS -> WirelessScreen(ctx)
                        SettingsSection.BLUETOOTH -> BluetoothScreen(ctx)
                        SettingsSection.DISPLAY -> DisplayScreen(ctx)
                        SettingsSection.BATTERY -> BatteryScreen(ctx)
                        SettingsSection.STORAGE_APPS -> StorageAppsScreen(ctx)
                        SettingsSection.SYSTEM_ABOUT -> AboutScreen(ctx)
                        null -> {}
                    }
                }
            }

            val infinitePulse = rememberInfiniteTransition(label = "verPulse")
            val verColor by infinitePulse.animateColor(
                initialValue = Color(0x9989ACA7),
                targetValue = MikuTealBright.copy(alpha = 0.85f),
                animationSpec = infiniteRepeatable(
                    animation = tween(2500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "verColor"
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = verColor,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 4.dp)
            )
        }
    }
}

// ----------------------------------------------------
// Section 1: Audio & Cirrus Logic CS43198 Direct MasterHIFI
// ----------------------------------------------------
@Composable
fun AudioDacScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(CirrusLogicManager.getDigitalFilter(ctx)) }
    var gain by remember { mutableStateOf(CirrusLogicManager.getGainMode(ctx)) }
    var dre by remember { mutableStateOf(CirrusLogicManager.isDreEnabled(ctx)) }
    var turbo by remember { mutableStateOf(CirrusLogicManager.isHighPowerEnabled(ctx)) }
    var dsdComp by remember { mutableStateOf(CirrusLogicManager.getDsdGainCompensate(ctx)) }
    var outMode by remember { mutableStateOf(CirrusLogicManager.getOutputMode(ctx)) }
    var balance by remember { mutableStateOf(CirrusLogicManager.getBalance(ctx)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status Hero Card
        item {
            Column(Modifier.mikuHeroCard().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🟢", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Dual CS43198 MasterHIFI™ & ALSA Direct Pipeline",
                        color = MikuTealBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Direct kernel sysfs writes active (/sys/devices/platform/sa_sound_setting/). Hardware filters, gain stages, dynamic range enhancement, and UAC2 bit-perfect audio engine operational.",
                    color = MikuMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }

        // SeeAudio Yume Reference IEM Profile Card
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF07242B), Color(0xFF1E0B25))
                        )
                    )
                    .border(1.2.dp, MikuTealBright.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🎧", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "SeeAudio Yume · M500 Reference Pair",
                                color = MikuTealBright,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Official Bundled 1DD + 2BA Hybrid IEMs",
                                color = MikuPinkBright,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MikuTealBright.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text("32Ω / 106dB", color = MikuTealBright, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "The SeeAudio Yume is matched specifically to the M500's CS43198 DAC output impedance (<0.3Ω). Delivers reference Harman vocal curve with sub-bass extension and zero background hiss floor.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22000000))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Rec: Low Gain (0dB)", color = MikuTealBright, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22000000))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Rec: Fast Linear Filter", color = MikuTealBright, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        filter = CirrusLogicManager.DigitalFilter.FAST_LINEAR
                        gain = CirrusLogicManager.GainMode.LOW
                        dre = true
                        balance = 0
                        scope.launch {
                            CirrusLogicManager.setDigitalFilter(ctx, CirrusLogicManager.DigitalFilter.FAST_LINEAR)
                            CirrusLogicManager.setGainMode(ctx, CirrusLogicManager.GainMode.LOW)
                            CirrusLogicManager.setDreEnabled(ctx, true)
                            CirrusLogicManager.setBalance(ctx, 0)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MikuTealBright),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "⚡ Apply Yume Reference Audio Profile",
                        color = Color(0xFF041215),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Section 1: Digital Reconstruction Filter
        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text(
                    "DIGITAL RECONSTRUCTION FILTER",
                    color = MikuTealBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))

                CirrusLogicManager.DigitalFilter.values().forEach { f ->
                    val isSel = filter == f
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) MikuTealBright.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                filter = f
                                scope.launch { CirrusLogicManager.setDigitalFilter(ctx, f) }
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = {
                                filter = f
                                scope.launch { CirrusLogicManager.setDigitalFilter(ctx, f) }
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = MikuTealBright, unselectedColor = MikuMuted)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(f.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(f.description, color = MikuMuted, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }

        // Section 2: Analog Headphone Gain (PO Gain)
        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text(
                    "ANALOG HEADPHONE GAIN (PO GAIN)",
                    color = MikuTealBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CirrusLogicManager.GainMode.values().forEach { g ->
                        val isSel = gain == g
                        Box(
                            Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSel) MikuTealBright else MikuSurface2)
                                .border(1.dp, if (isSel) MikuTealBright else MikuTeal.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .clickable {
                                    gain = g
                                    scope.launch { CirrusLogicManager.setGainMode(ctx, g) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                g.label,
                                color = if (isSel) Color(0xFF041215) else Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Section 3: Audio Output Routing & Stream Matrix
        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "AUDIO OUTPUT ROUTING MATRIX",
                            color = MikuTealBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            "Select primary active DAC output or Bluetooth audio stream",
                            color = MikuMuted,
                            fontSize = 11.sp
                        )
                    }

                    // Quick Swap Button
                    Button(
                        onClick = {
                            scope.launch {
                                outMode = CirrusLogicManager.swapOutputMode(ctx)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MikuTealBright),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            "⇄ Swap 4.4mm / BT",
                            color = MikuTealBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                CirrusLogicManager.OutputMode.values().forEach { out ->
                    val isSel = outMode == out
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) MikuTealBright.copy(alpha = 0.15f) else Color.Transparent)
                            .border(1.dp, if (isSel) MikuTealBright.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable {
                                outMode = out
                                scope.launch { CirrusLogicManager.setOutputMode(ctx, out) }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(out.icon, fontSize = 16.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(out.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                if (isSel) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MikuTealBright.copy(alpha = 0.25f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text("ACTIVE", color = MikuTealBright, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(out.description, color = MikuMuted, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                        RadioButton(
                            selected = isSel,
                            onClick = {
                                outMode = out
                                scope.launch { CirrusLogicManager.setOutputMode(ctx, out) }
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = MikuTealBright, unselectedColor = MikuMuted)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // Section 3.5: Dual Simultaneous Audio Sharing Matrix
        item {
            var audioShareEnabled by remember { mutableStateOf(CirrusLogicManager.isAudioShareEnabled(ctx)) }
            var audioShareTarget by remember { mutableStateOf(CirrusLogicManager.getAudioShareTarget(ctx)) }

            Column(Modifier.mikuCard().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "DUAL SIMULTANEOUS AUDIO MATRIX",
                            color = MikuPinkBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            "Simultaneous audio broadcast across CS43198 DAC and Bluetooth",
                            color = MikuMuted,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = audioShareEnabled,
                        onCheckedChange = {
                            audioShareEnabled = it
                            scope.launch { CirrusLogicManager.setAudioShareEnabled(ctx, it) }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MikuPinkBright, checkedTrackColor = Color(0xFF380F25))
                    )
                }

                if (audioShareEnabled) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0x22FFFFFF), thickness = 0.8.dp)
                    Spacer(Modifier.height(10.dp))

                    Text(
                        "DUAL AUDIO STREAM TARGETS",
                        color = MikuPinkBright,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))

                    CirrusLogicManager.AudioShareTarget.values().forEach { target ->
                        val isSel = audioShareTarget == target
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) MikuPinkBright.copy(alpha = 0.15f) else Color.Transparent)
                                .border(1.dp, if (isSel) MikuPinkBright.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable {
                                    audioShareTarget = target
                                    scope.launch { CirrusLogicManager.setAudioShareTarget(ctx, target) }
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(target.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(target.description, color = MikuMuted, fontSize = 11.sp, lineHeight = 14.sp)
                            }
                            RadioButton(
                                selected = isSel,
                                onClick = {
                                    audioShareTarget = target
                                    scope.launch { CirrusLogicManager.setAudioShareTarget(ctx, target) }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = MikuPinkBright, unselectedColor = MikuMuted)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // Section 4: Dynamic Range Enhancement & High Power Mode
        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text(
                    "DYNAMICS & POWER RAILS",
                    color = MikuTealBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dynamic Range Enhancement (DRE)", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Boosts signal-to-noise ratio to 130dB+ for inaudible noise floor", color = MikuMuted, fontSize = 11.5.sp)
                    }
                    Switch(
                        checked = dre,
                        onCheckedChange = {
                            dre = it
                            scope.launch { CirrusLogicManager.setDreEnabled(ctx, it) }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MikuTealBright, checkedTrackColor = Color(0xFF0F3238))
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Audio Turbo High Power Mode", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Increases operational current rails for demanding dynamic peaks", color = MikuMuted, fontSize = 11.5.sp)
                    }
                    Switch(
                        checked = turbo,
                        onCheckedChange = {
                            turbo = it
                            scope.launch { CirrusLogicManager.setHighPowerEnabled(ctx, it) }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MikuTealBright, checkedTrackColor = Color(0xFF0F3238))
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DSD Gain Compensation (+6 dB)", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Matches SACD reference levels with standard PCM playback", color = MikuMuted, fontSize = 11.5.sp)
                    }
                    Switch(
                        checked = dsdComp,
                        onCheckedChange = {
                            dsdComp = it
                            scope.launch { CirrusLogicManager.setDsdGainCompensate(ctx, it) }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MikuTealBright, checkedTrackColor = Color(0xFF0F3238))
                    )
                }
            }
        }

        // Section 5: Hardware L/R Channel Balance (with Center Detent & Haptic Feedback)
        item {
            val view = androidx.compose.ui.platform.LocalView.current
            Column(Modifier.mikuCard().padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("HARDWARE L/R CHANNEL BALANCE", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (balance == 0) "Center (0) ✓" else if (balance < 0) "Left ($balance)" else "Right (+$balance)",
                        color = if (balance == 0) MikuTealBright else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // Center Detent Tick Notch
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(MikuTealBright.copy(alpha = 0.85f))
                    )

                    Slider(
                        value = balance.toFloat(),
                        onValueChange = { raw ->
                            // Magnetically snap to center detent when near 0
                            val snapped = if (kotlin.math.abs(raw) < 0.65f) 0 else raw.roundToInt()
                            if (snapped != balance) {
                                if (snapped == 0) {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                } else {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                                balance = snapped
                                scope.launch { CirrusLogicManager.setBalance(ctx, snapped) }
                            }
                        },
                        valueRange = -10f..10f,
                        steps = 19,
                        colors = SliderDefaults.colors(
                            thumbColor = if (balance == 0) MikuTealBright else Color.White,
                            activeTrackColor = MikuTeal,
                            inactiveTrackColor = MikuSurface2
                        )
                    )
                }
            }
        }

        // Section 6: Audiophile Impedance & Headphone Compatibility Matrix
        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text(
                    "AUDIOPHILE HEADPHONE & IEM DRIVE MATRIX",
                    color = MikuTealBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "M500 Hardware Output capability: 140mW @ 32Ω (3.5mm PO) · 440mW @ 32Ω (4.4mm BAL PO). Dual CS43198 + SGM Op-Amp differential rails.",
                    color = MikuMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )

                Spacer(Modifier.height(12.dp))

                // Tier 1: 🟢 Direct Synergy
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0C241E))
                        .border(1.dp, Color(0xFF1ABC9C).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🟢", fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("100% DIRECT DRIVE · HIGH SYNERGY (< 80Ω)", color = Color(0xFF1ABC9C), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("• SeeAudio Yume / Yume II (32Ω / 106dB) · Bundled Reference", color = Color.White, fontSize = 11.5.sp)
                    Text("• Moondrop Blessing 2 / Blessing 3 / Kato (22Ω–32Ω)", color = Color.White, fontSize = 11.5.sp)
                    Text("• Sennheiser IE 200 / IE 600 / IE 900 (18Ω / 123dB)", color = Color.White, fontSize = 11.5.sp)
                    Text("• 7Hz Timeless / Dioko Planar IEMs (14.8Ω)", color = Color.White, fontSize = 11.5.sp)
                    Text("• Audio-Technica ATH-M50x / Meze 99 Classics (32–38Ω)", color = Color.White, fontSize = 11.5.sp)
                }

                Spacer(Modifier.height(10.dp))

                // Tier 2: 🟡 4.4mm Balanced Recommended
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF28230D))
                        .border(1.dp, Color(0xFFF1C40F).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🟡", fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("4.4mm BALANCED + HIGH GAIN RECOMMENDED (80–150Ω / Planar)", color = Color(0xFFF1C40F), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("• Hifiman Sundara / Edition XS (37Ω / 94dB Planar - needs 4.4mm BAL)", color = Color.White, fontSize = 11.5.sp)
                    Text("• Sennheiser HD 560S / HD 599 (120Ω / 110dB)", color = Color.White, fontSize = 11.5.sp)
                    Text("• Beyerdynamic DT 770 Pro 80Ω Edition (80Ω)", color = Color.White, fontSize = 11.5.sp)
                }

                Spacer(Modifier.height(10.dp))

                // Tier 3: 🔴 External Desktop Amp Required
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF290E14))
                        .border(1.dp, Color(0xFFE74C3C).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔴", fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("EXTERNAL AMP REQUIRED (HIGH IMPEDANCE > 250Ω)", color = Color(0xFFE74C3C), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("• Sennheiser HD 600 / HD 650 / HD 660S2 (300Ω / 97dB) · Voltage rail limited; requires 6Vrms+ external desktop amp for dynamic headroom", color = Color(0xFFF9B8B1), fontSize = 11.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("• Beyerdynamic DT 880 / DT 990 Pro 250Ω & 600Ω · Needs dedicated high-voltage OTL/solid state amplifier", color = Color(0xFFF9B8B1), fontSize = 11.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("• Hifiman HE6se / Susvara (83dB) · Severe current starvation without speaker-tap amp", color = Color(0xFFF9B8B1), fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ----------------------------------------------------
// Section 2: Pulsar RGB Light
// ----------------------------------------------------
@Composable
fun PulsarScreen(ctx: Context) {
    var mode by remember { mutableStateOf(PulsarLight.getMode(ctx)) }
    var brightness by remember { mutableStateOf(PulsarLight.getBrightness(ctx)) }
    var bpmSync by remember { mutableStateOf(PulsarLight.isBpmSyncEnabled(ctx)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(Modifier.mikuHeroCard().padding(16.dp)) {
                Text(
                    "Front Pulsar RGB Matrix (Dual-Die PWM)",
                    color = MikuTealBright,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "SGM31324 constant-current LED driver hardware controller. Dual-die red & blue PWM cross-fading for dynamic audio tier status, beats-per-minute sync, and ambient battery glow.",
                    color = MikuMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }

        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text("LIGHTING MODE", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                PulsarLight.Mode.values().forEach { m ->
                    val isSel = mode == m
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) MikuTealBright.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                mode = m
                                PulsarLight.setMode(ctx, m)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = {
                                mode = m
                                PulsarLight.setMode(ctx, m)
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = MikuTealBright, unselectedColor = MikuMuted)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(m.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(m.description, color = MikuMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("LED BRIGHTNESS LEVEL", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("$brightness / 255", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = brightness.toFloat(),
                    onValueChange = {
                        brightness = it.toInt()
                        PulsarLight.setBrightness(ctx, it.toInt())
                    },
                    valueRange = 10f..255f,
                    colors = SliderDefaults.colors(thumbColor = MikuTealBright, activeTrackColor = MikuTeal, inactiveTrackColor = MikuSurface2)
                )

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Audiophile BPM Pulse Sync", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Pulse LED brightness in rhythm with track tempo (BPM)", color = MikuMuted, fontSize = 11.5.sp)
                    }
                    Switch(
                        checked = bpmSync,
                        onCheckedChange = {
                            bpmSync = it
                            PulsarLight.setBpmSyncEnabled(ctx, it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MikuTealBright, checkedTrackColor = Color(0xFF0F3238))
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ----------------------------------------------------
// Section 2.5: Physical FN Hardware Switch & Key Lock
// ----------------------------------------------------
@Composable
fun FnSwitchScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    val cr = ctx.contentResolver
    
    val fnOptions = listOf(
        "screen_and_keys" to ("🔒 Screen & Keys Lock (Default)" to "Completely locks touchscreen, volume knob & physical side buttons to prevent accidental pocket presses"),
        "touch_lock" to ("📱 Touch Screen Lock Only" to "Disables touchscreen while keeping physical side transport buttons and volume knob active"),
        "key_lock" to ("⌨️ Physical Keys Lock Only" to "Disables physical side buttons and rotary knob while touchscreen remains unlocked"),
        "speaker_mute" to ("🔇 Instant Speaker Mute" to "Instantly mutes the internal loudspeaker when flipped"),
        "flip_vertical" to ("🔄 180° Display Flip" to "Flips screen upside down for inverted pocket cable routing"),
        "sound_record" to ("🎙️ Voice Audio Recorder" to "Instantly starts high-resolution voice memo recording")
    )

    var currentMode by remember {
        mutableStateOf(
            try {
                val mode = Settings.Global.getString(cr, "fn_settings") ?: "screen_and_keys"
                if (mode.isBlank()) "screen_and_keys" else mode
            } catch (_: Throwable) { "screen_and_keys" }
        )
    }

    var fnStatus by remember {
        mutableStateOf(
            try {
                Settings.Global.getInt(cr, "fn_status", 0) == 1
            } catch (_: Throwable) { false }
        )
    }

    // Monitor live hardware Fn switch position
    DisposableEffect(Unit) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                fnStatus = try {
                    Settings.Global.getInt(cr, "fn_status", 0) == 1
                } catch (_: Throwable) { false }
            }
        }
        cr.registerContentObserver(Settings.Global.getUriFor("fn_status"), false, observer)
        onDispose {
            cr.unregisterContentObserver(observer)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hardware Status Hero Card
        item {
            Column(Modifier.mikuHeroCard().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (fnStatus) "🔒" else "🔓", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Physical FN Switch Status",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (fnStatus) "SWITCH ACTIVE (Lock Engaged)" else "SWITCH INACTIVE (Normal Mode)",
                                color = if (fnStatus) MikuPinkBright else MikuTealBright,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (fnStatus) MikuPinkBright.copy(alpha = 0.2f) else MikuTealBright.copy(alpha = 0.2f))
                            .border(1.dp, if (fnStatus) MikuPinkBright else MikuTealBright, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (fnStatus) "LOCKED" else "READY",
                            color = if (fnStatus) MikuPinkBright else MikuTealBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Mode Selection Card
        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text(
                    "FN SWITCH FUNCTION ASSIGNMENT",
                    color = MikuTealBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(10.dp))

                fnOptions.forEach { (modeKey, info) ->
                    val (title, desc) = info
                    val isSel = currentMode == modeKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) MikuTealBright.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                currentMode = modeKey
                                scope.launch(Dispatchers.IO) {
                                    runCatching { Settings.Global.putString(cr, "fn_settings", modeKey) }
                                    runCatching { Settings.Global.putString(cr, "vendor.hiby.fn_settings", modeKey) }
                                    RootShell.execFast(
                                        "settings put global fn_settings $modeKey; " +
                                        "setprop persist.vendor.fn_mode $modeKey; " +
                                        "setprop vendor.fn_mode $modeKey; " +
                                        "setprop vendor.hiby.fn_settings $modeKey"
                                    )
                                }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = {
                                currentMode = modeKey
                                scope.launch(Dispatchers.IO) {
                                    runCatching { Settings.Global.putString(cr, "fn_settings", modeKey) }
                                    runCatching { Settings.Global.putString(cr, "vendor.hiby.fn_settings", modeKey) }
                                    RootShell.execFast(
                                        "settings put global fn_settings $modeKey; " +
                                        "setprop persist.vendor.fn_mode $modeKey; " +
                                        "setprop vendor.fn_mode $modeKey; " +
                                        "setprop vendor.hiby.fn_settings $modeKey"
                                    )
                                }
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MikuTealBright,
                                unselectedColor = MikuMuted
                            )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            Text(desc, color = MikuMuted, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }

        // Screen-Off Physical Buttons Card
        item {
            var mediaLockOff by remember {
                mutableStateOf(
                    try { Settings.System.getInt(cr, "media_lock", 0) == 0 } catch (_: Throwable) { true }
                )
            }
            var volumeLockOff by remember {
                mutableStateOf(
                    try { Settings.System.getInt(cr, "volume_lock", 0) == 0 } catch (_: Throwable) { true }
                )
            }

            Column(Modifier.mikuCard().padding(14.dp)) {
                Text(
                    "SCREEN-OFF PHYSICAL KEY CONTROLS",
                    color = MikuTealBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Allow physical transport buttons (Play/Pause, Next, Prev) and rotary knob to operate even when screen is locked or powered off.",
                    color = MikuMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
                Spacer(Modifier.height(10.dp))

                // Media buttons screen-off toggle
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MikuSurface2)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Media Transport Keys", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (mediaLockOff) "Active during screen off (0)" else "Locked during screen off (1)", color = if (mediaLockOff) MikuTealBright else MikuMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = mediaLockOff,
                        onCheckedChange = { enable ->
                            mediaLockOff = enable
                            scope.launch(Dispatchers.IO) {
                                val value = if (enable) 0 else 1
                                try { Settings.System.putInt(cr, "media_lock", value) } catch (_: Throwable) {}
                                RootShell.execFast("settings put system media_lock $value; chmod 666 /dev/input/event*")
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MikuTealBright,
                            checkedTrackColor = MikuTeal.copy(alpha = 0.5f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = MikuSurface2
                        )
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Volume wheel screen-off toggle
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MikuSurface2)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Rotary Volume Knob", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (volumeLockOff) "Active during screen off (0)" else "Locked during screen off (1)", color = if (volumeLockOff) MikuTealBright else MikuMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = volumeLockOff,
                        onCheckedChange = { enable ->
                            volumeLockOff = enable
                            scope.launch(Dispatchers.IO) {
                                val value = if (enable) 0 else 1
                                try { Settings.System.putInt(cr, "volume_lock", value) } catch (_: Throwable) {}
                                RootShell.execFast("settings put system volume_lock $value; chmod 666 /dev/input/event*")
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MikuTealBright,
                            checkedTrackColor = MikuTeal.copy(alpha = 0.5f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = MikuSurface2
                        )
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ----------------------------------------------------
// Section 3: Wireless, Networks & Wireless ADB
// ----------------------------------------------------
@Composable
fun WirelessScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    var adbEnabled by remember { mutableStateOf(WirelessAdbManager.isEnabled()) }
    val wifiIp = remember { WirelessAdbManager.getWifiIpAddress(ctx) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Wireless ADB Card
        item {
            Column(Modifier.mikuHeroCard().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeveloperMode, contentDescription = null, tint = MikuTealBright)
                    Spacer(Modifier.width(8.dp))
                    Text("Wireless ADB Debugging", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Run ADB commands wirelessly over your local network without USB cable.",
                    color = MikuMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MikuSurface2)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("ADB Port 5555 Service", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (wifiIp != null) "Connect: adb connect $wifiIp:5555" else "Connect device to Wi-Fi",
                            color = if (wifiIp != null) MikuTealBright else MikuPink,
                            fontSize = 11.5.sp
                        )
                    }
                    Switch(
                        checked = adbEnabled,
                        onCheckedChange = {
                            adbEnabled = it
                            scope.launch { WirelessAdbManager.setEnabled(it) }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = MikuTealBright, checkedTrackColor = Color(0xFF0F3238))
                    )
                }
            }
        }

        // Quick System Wi-Fi & Hotspot Links
        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text("NETWORK & CONNECTIONS", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                SettingsLinkRow(
                    icon = Icons.Default.Wifi,
                    title = "Wi-Fi Settings",
                    subtitle = "Scan and connect to 2.4GHz & 5GHz networks",
                    onClick = {
                        openAospSettings(ctx, Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings not available")
                    }
                )

                Spacer(Modifier.height(8.dp))

                SettingsLinkRow(
                    icon = Icons.Default.WifiTethering,
                    title = "Portable Hotspot & Tethering",
                    subtitle = "Share 4G LTE mobile data with other devices",
                    onClick = {
                        openAospSettings(ctx, Settings.ACTION_WIRELESS_SETTINGS, "Tethering settings not available")
                    }
                )

                Spacer(Modifier.height(8.dp))

                SettingsLinkRow(
                    icon = Icons.Default.AirplanemodeActive,
                    title = "Airplane Mode",
                    subtitle = "Disable all radio transmissions (Wi-Fi, Bluetooth, LTE)",
                    onClick = {
                        openAospSettings(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode settings not available")
                    }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ----------------------------------------------------
// Section 4: Bluetooth Audio & Device Cockpit
// ----------------------------------------------------
@Composable
fun BluetoothScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        com.miku.settings.bluetooth.MikuBluetoothController.init(ctx)
    }

    val isBtEnabled by com.miku.settings.bluetooth.MikuBluetoothController.isBluetoothEnabled.collectAsState()
    val isScanning by com.miku.settings.bluetooth.MikuBluetoothController.isScanning.collectAsState()
    val pairedDevices by com.miku.settings.bluetooth.MikuBluetoothController.pairedDevices.collectAsState()
    val discoveredDevices by com.miku.settings.bluetooth.MikuBluetoothController.discoveredDevices.collectAsState()

    val prefs = remember { ctx.getSharedPreferences("miku_bluetooth_prefs", Context.MODE_PRIVATE) }
    var ldacQuality by remember { mutableStateOf(prefs.getString("ldac_quality", "Sound Quality (990 kbps)") ?: "Sound Quality (990 kbps)") }
    var aptxEnabled by remember { mutableStateOf(prefs.getBoolean("aptx_enabled", true)) }
    var aacEnabled by remember { mutableStateOf(prefs.getBoolean("aac_enabled", true)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Master Bluetooth Radio Toggle Card
        item {
            Column(Modifier.mikuHeroCard().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isBtEnabled) MikuTeal.copy(alpha = 0.25f) else Color(0x22FFFFFF))
                                .border(1.dp, if (isBtEnabled) MikuTealBright else Color(0x33FFFFFF), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = if (isBtEnabled) MikuTealBright else MikuMuted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Bluetooth Radio",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isBtEnabled) "Active · Hi-Res Audio Ready" else "Disabled",
                                color = if (isBtEnabled) MikuTealBright else MikuMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }

                    Switch(
                        checked = isBtEnabled,
                        onCheckedChange = { enable ->
                            com.miku.settings.bluetooth.MikuBluetoothController.toggleBluetooth(enable)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MikuTealBright,
                            checkedTrackColor = Color(0xFF0F3238)
                        )
                    )
                }
            }
        }

        if (isBtEnabled) {
            // 2. Paired Gear & Audio DACs
            item {
                Column(Modifier.mikuCard().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PAIRED DEVICES (${pairedDevices.size})",
                            color = MikuTealBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = if (isScanning) "Searching..." else "+ Scan Nearby Gear",
                            color = if (isScanning) Color(0xFFFF4081) else MikuTealBright,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isScanning) Color(0x22FF4081) else Color(0x2200E5FF))
                                .clickable {
                                    if (isScanning) {
                                        com.miku.settings.bluetooth.MikuBluetoothController.stopScan()
                                    } else {
                                        com.miku.settings.bluetooth.MikuBluetoothController.startScan()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    if (pairedDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MikuSurface2)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No paired Bluetooth audio gear found.\nTap '+ Scan Nearby Gear' below to find and connect.",
                                color = MikuMuted,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            pairedDevices.forEach { devItem ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (devItem.isConnected) Color(0x2200E5FF) else MikuSurface2)
                                        .border(1.dp, if (devItem.isConnected) MikuTealBright else Color.Transparent, RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (!devItem.isConnected && !devItem.isConnecting) {
                                                com.miku.settings.bluetooth.MikuBluetoothController.connectDevice(devItem.device)
                                            }
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(MikuTeal.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = when (devItem.deviceType) {
                                                DeviceType.AUDIO_HEADSET, DeviceType.AUDIO_DAC -> Icons.Default.Headphones
                                                DeviceType.AUDIO_SPEAKER -> Icons.Default.Speaker
                                                DeviceType.PHONE_WATCH -> Icons.Default.PhoneAndroid
                                                DeviceType.INPUT_KEYBOARD_MOUSE -> Icons.Default.Computer
                                                else -> Icons.Default.Bluetooth
                                            },
                                            contentDescription = null,
                                            tint = if (devItem.isConnected) MikuTealBright else Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = devItem.name,
                                            color = Color.White,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = when {
                                                devItem.isConnected -> "🟢 Active Audio Connection"
                                                devItem.isConnecting -> "🟡 Connecting..."
                                                else -> devItem.address
                                            },
                                            color = if (devItem.isConnected) MikuTealBright else if (devItem.isConnecting) Color(0xFFFFD54F) else MikuMuted,
                                            fontSize = 10.5.sp
                                        )
                                    }

                                    Spacer(Modifier.width(6.dp))

                                    if (devItem.isConnected) {
                                        Button(
                                            onClick = { com.miku.settings.bluetooth.MikuBluetoothController.disconnectDevice(devItem.device) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF5252)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Disconnect", color = Color(0xFFFF8A80), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else if (devItem.isConnecting) {
                                        CircularProgressIndicator(
                                            color = MikuTealBright,
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Button(
                                            onClick = { com.miku.settings.bluetooth.MikuBluetoothController.connectDevice(devItem.device) },
                                            colors = ButtonDefaults.buttonColors(containerColor = MikuTealBright),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Connect", color = Color.Black, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Spacer(Modifier.width(4.dp))

                                    IconButton(
                                        onClick = { com.miku.settings.bluetooth.MikuBluetoothController.unpairDevice(devItem.device) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Forget", tint = MikuMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Live Discovered Nearby Devices
            item {
                Column(Modifier.mikuCard().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "AVAILABLE NEARBY GEAR",
                                color = MikuTealBright,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isScanning) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    color = MikuTealBright,
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (isScanning) {
                                    com.miku.settings.bluetooth.MikuBluetoothController.stopScan()
                                } else {
                                    com.miku.settings.bluetooth.MikuBluetoothController.startScan()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Color(0x33FF4081) else Color(0x3300E5FF)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = if (isScanning) "Stop" else "Scan",
                                color = if (isScanning) Color(0xFFFF80AB) else MikuTealBright,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    if (discoveredDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MikuSurface2)
                                .padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isScanning) "Scanning for headphones, DACs, and wireless gear..." else "Tap 'Scan' to search for nearby Bluetooth devices.",
                                color = MikuMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            discoveredDevices.forEach { devItem ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MikuSurface2)
                                        .clickable {
                                            com.miku.settings.bluetooth.MikuBluetoothController.pairDevice(devItem.device)
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (devItem.deviceType) {
                                            DeviceType.AUDIO_HEADSET, DeviceType.AUDIO_DAC -> Icons.Default.Headphones
                                            DeviceType.AUDIO_SPEAKER -> Icons.Default.Speaker
                                            DeviceType.PHONE_WATCH -> Icons.Default.PhoneAndroid
                                            DeviceType.INPUT_KEYBOARD_MOUSE -> Icons.Default.Computer
                                            else -> Icons.Default.Bluetooth
                                        },
                                        contentDescription = null,
                                        tint = MikuTealBright,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(devItem.name, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${devItem.address} · Signal ${devItem.rssi} dBm", color = MikuMuted, fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = { com.miku.settings.bluetooth.MikuBluetoothController.pairDevice(devItem.device) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text("Pair & Connect", color = MikuTealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Audiophile Hi-Res Bluetooth Codec Engine
            item {
                Column(Modifier.mikuCard().padding(14.dp)) {
                    Text(
                        text = "HI-RES AUDIO TRANSMISSION CODEC",
                        color = MikuTealBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "LDAC Audio Quality Bitrate",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))

                    val ldacOptions = listOf(
                        "Sound Quality (990 kbps)" to "Master Hi-Res 96kHz/24-bit Lossless",
                        "Balanced (660 kbps)" to "Standard Hi-Res Studio Transmission",
                        "Connection (330 kbps)" to "Maximum Anti-Interference Stability",
                        "Adaptive Bitrate" to "Dynamically scaled to RF packet quality"
                    )

                    ldacOptions.forEach { (opt, desc) ->
                        val isSel = ldacQuality == opt
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) MikuTealBright.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    ldacQuality = opt
                                    prefs.edit().putString("ldac_quality", opt).apply()
                                    com.miku.settings.bluetooth.MikuBluetoothController.applyCodecConfig(opt, aptxEnabled, aacEnabled)
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSel,
                                onClick = {
                                    ldacQuality = opt
                                    prefs.edit().putString("ldac_quality", opt).apply()
                                    com.miku.settings.bluetooth.MikuBluetoothController.applyCodecConfig(opt, aptxEnabled, aacEnabled)
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MikuTealBright,
                                    unselectedColor = MikuMuted
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(opt, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(desc, color = MikuMuted, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0x22FFFFFF), thickness = 0.8.dp)
                    Spacer(Modifier.height(12.dp))

                    // Qualcomm aptX & aptX HD Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Qualcomm aptX / aptX HD", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("Low-latency 24-bit audiophile streaming on supported gear", color = MikuMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = aptxEnabled,
                            onCheckedChange = {
                                aptxEnabled = it
                                prefs.edit().putBoolean("aptx_enabled", it).apply()
                                com.miku.settings.bluetooth.MikuBluetoothController.applyCodecConfig(ldacQuality, it, aacEnabled)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = MikuTealBright, checkedTrackColor = Color(0xFF0F3238))
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // AAC Stream Codec Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AAC High Definition Audio", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("Advanced Audio Coding for Apple AirPods & Sony wireless gear", color = MikuMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = aacEnabled,
                            onCheckedChange = {
                                aacEnabled = it
                                prefs.edit().putBoolean("aac_enabled", it).apply()
                                com.miku.settings.bluetooth.MikuBluetoothController.applyCodecConfig(ldacQuality, aptxEnabled, it)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = MikuTealBright, checkedTrackColor = Color(0xFF0F3238))
                        )
                    }
                }
            }

            // 5. Hardware Pipeline & RF Telemetry
            item {
                Column(Modifier.mikuCard().padding(14.dp)) {
                    Text(
                        text = "BLUETOOTH RF & CODEC ARCHITECTURE",
                        color = MikuTealBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))

                    AboutSpecRow("RF Transceiver", "Qualcomm Snapdragon 680 (WCN3988)")
                    AboutSpecRow("Bluetooth Version", "Bluetooth 5.0 Core / Low Energy (BLE)")
                    AboutSpecRow("Supported Codecs", "LDAC (96k/24b), aptX HD, aptX, AAC, SBC")
                    AboutSpecRow("Audio Hardware Bridge", "Direct MasterHIFI DAC & BT Concurrent Routing")

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            openAospSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS, "Connected devices not available")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MikuSurface2),
                        border = BorderStroke(1.dp, MikuTeal.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = MikuTealBright, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Advanced System Connected Devices", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ----------------------------------------------------
// Section 5: Display & Ambient Light
// ----------------------------------------------------
@Composable
fun DisplayScreen(ctx: Context) {
    val cr = ctx.contentResolver
    var brightness by remember {
        mutableStateOf(
            try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) { 128 }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SCREEN BRIGHTNESS", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("$brightness / 255", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = brightness.toFloat(),
                    onValueChange = {
                        brightness = it.toInt()
                        try {
                            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, it.toInt())
                        } catch (_: Throwable) {
                            RootShell.execFast("settings put system screen_brightness ${it.toInt()}")
                        }
                    },
                    valueRange = 5f..255f,
                    colors = SliderDefaults.colors(thumbColor = MikuTealBright, activeTrackColor = MikuTeal, inactiveTrackColor = MikuSurface2)
                )
            }
        }

        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text("UI SCALE & DISPLAY DENSITY", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Scale up UI elements, touch targets & buttons across MikuOS", color = MikuMuted, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))

                val currentDpi = ctx.resources.displayMetrics.densityDpi
                var selectedDpi by remember { mutableIntStateOf(currentDpi) }

                val dpiOptions = listOf(
                    320 to "320 DPI\n(Default)",
                    360 to "360 DPI\n(Large)",
                    400 to "400 DPI\n(XL UI)",
                    440 to "440 DPI\n(Huge DAP)"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dpiOptions.forEach { (dpi, label) ->
                        val isSelected = selectedDpi == dpi || (selectedDpi !in dpiOptions.map { it.first } && dpi == 320)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) MikuTealBright.copy(alpha = 0.25f) else MikuSurface2)
                                .border(
                                    1.dp,
                                    if (isSelected) MikuTealBright else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    selectedDpi = dpi
                                    RootShell.execFast("wm density $dpi")
                                    RootShell.execFast("settings put secure display_density_forced $dpi")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) MikuTealBright else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text("SYSTEM FONT SCALE", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Enlarge text across system apps and launchers", color = MikuMuted, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))

                var currentFontScale by remember {
                    mutableFloatStateOf(
                        try {
                            Settings.System.getFloat(cr, Settings.System.FONT_SCALE)
                        } catch (_: Throwable) { 1.0f }
                    )
                }

                val fontScaleOptions = listOf(
                    1.0f to "1.0x\n(Default)",
                    1.15f to "1.15x\n(Large)",
                    1.30f to "1.30x\n(XL Text)",
                    1.45f to "1.45x\n(Huge Text)"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    fontScaleOptions.forEach { (scale, label) ->
                        val isSelected = Math.abs(currentFontScale - scale) < 0.05f
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) MikuTealBright.copy(alpha = 0.25f) else MikuSurface2)
                                .border(
                                    1.dp,
                                    if (isSelected) MikuTealBright else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    currentFontScale = scale
                                    try {
                                        Settings.System.putFloat(cr, Settings.System.FONT_SCALE, scale)
                                    } catch (_: Throwable) {}
                                    RootShell.execFast("settings put system font_scale $scale")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) MikuTealBright else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text("SYSTEM NAVIGATION MODE", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Configure Pixel-style edge swipe back & navigation bar", color = MikuMuted, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))

                var isGestureNav by remember {
                    mutableStateOf(
                        try {
                            Settings.Secure.getInt(cr, "navigation_mode", 2) == 2
                        } catch (_: Throwable) { true }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Gesture Navigation Option
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isGestureNav) MikuTealBright.copy(alpha = 0.25f) else MikuSurface2)
                            .border(
                                1.dp,
                                if (isGestureNav) MikuTealBright else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                isGestureNav = true
                                RootShell.execFast(
                                    "cmd overlay enable com.android.internal.systemui.navbar.gestural; " +
                                    "cmd overlay disable com.android.internal.systemui.navbar.threebutton; " +
                                    "settings put secure navigation_mode 2; " +
                                    "settings put secure back_gesture_inset_scale_left 2; " +
                                    "settings put secure back_gesture_inset_scale_right 2"
                                )
                                Toast.makeText(ctx, "Gesture Navigation (Pixel Swipe Back) Enabled", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("GESTURE NAV", color = if (isGestureNav) MikuTealBright else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text("Pixel Edge Swipe Back", color = MikuMuted, fontSize = 9.sp, textAlign = TextAlign.Center)
                        }
                    }

                    // 3-Button Navigation Option
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isGestureNav) MikuTealBright.copy(alpha = 0.25f) else MikuSurface2)
                            .border(
                                1.dp,
                                if (!isGestureNav) MikuTealBright else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                isGestureNav = false
                                RootShell.execFast(
                                    "cmd overlay enable com.android.internal.systemui.navbar.threebutton; " +
                                    "cmd overlay disable com.android.internal.systemui.navbar.gestural; " +
                                    "settings put secure navigation_mode 0"
                                )
                                Toast.makeText(ctx, "3-Button Navigation Bar Enabled", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("3-BUTTON BAR", color = if (!isGestureNav) MikuTealBright else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text("Classic Buttons", color = MikuMuted, fontSize = 9.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.mikuCard().padding(14.dp)) {
                Text("ADVANCED SYSTEM DISPLAY", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                SettingsLinkRow(
                    icon = Icons.Default.DisplaySettings,
                    title = "AOSP Display Settings",
                    subtitle = "Screen timeout, color temperature, night light & lock screen",
                    onClick = {
                        openAospSettings(ctx, Settings.ACTION_DISPLAY_SETTINGS, "System display settings not available")
                    }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

fun Modifier.mikuEdgeSwipeBack(
    edgeZoneDp: androidx.compose.ui.unit.Dp = 32.dp,
    minSwipeDp: androidx.compose.ui.unit.Dp = 36.dp,
    onBack: () -> Unit
): Modifier = this.pointerInput(onBack) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        val startX = down.position.x
        val edgeZonePx = edgeZoneDp.toPx()
        val minSwipePx = minSwipeDp.toPx()
        val isLeft = startX <= edgeZonePx
        val isRight = startX >= (size.width - edgeZonePx)

        if (isLeft || isRight) {
            var triggered = false
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break

                val deltaX = change.position.x - startX
                if (isLeft && deltaX > minSwipePx && !triggered) {
                    triggered = true
                    change.consume()
                    onBack()
                    break
                } else if (isRight && deltaX < -minSwipePx && !triggered) {
                    triggered = true
                    change.consume()
                    onBack()
                    break
                }
            }
        }
    }
}

// ----------------------------------------------------
// Section 6: Battery & Power Observatory
// ----------------------------------------------------
@Composable
fun BatteryScreen(ctx: Context) {
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
    val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    val currentMa = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { it / 1000 } ?: 0

    Column(Modifier.mikuHeroCard().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("BATTERY TELEMETRY", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("$pct%", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
            }
            Icon(
                Icons.Default.BatteryChargingFull,
                contentDescription = null,
                tint = if (pct > 20) MikuTealBright else MikuPinkBright,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPill(label = "CURRENT", value = "${currentMa} mA")
            MetricPill(label = "HEALTH", value = "Good")
            MetricPill(label = "STATUS", value = "Optimal")
        }
    }
}

// ----------------------------------------------------
// Section 7: Apps & Storage
// ----------------------------------------------------
@Composable
fun StorageAppsScreen(ctx: Context) {
    Column(Modifier.mikuCard().padding(14.dp)) {
        Text("STORAGE & APPLICATION MANAGER", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        SettingsLinkRow(
            icon = Icons.Default.Apps,
            title = "Installed Applications",
            subtitle = "Manage app permissions, background battery & storage cache",
            onClick = {
                openAospSettings(ctx, Settings.ACTION_APPLICATION_SETTINGS, "App manager not available")
            }
        )

        Spacer(Modifier.height(8.dp))

        SettingsLinkRow(
            icon = Icons.Default.SdCard,
            title = "Storage & MicroSD Card",
            subtitle = "Internal flash memory and external MicroSD storage management",
            onClick = {
                openAospSettings(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Storage settings not available")
            }
        )
    }
}

// ----------------------------------------------------
// Section 8: About MikuOS
// ----------------------------------------------------
@Composable
fun AboutScreen(ctx: Context) {
    val mikuosVersion = try {
        val propClass = Class.forName("android.os.SystemProperties")
        val getMethod = propClass.getMethod("get", String::class.java, String::class.java)
        getMethod.invoke(null, "ro.mikuos.version", "0.1.0") as String
    } catch (_: Throwable) { "0.1.0" }
    val model = android.os.Build.MODEL

    Column(Modifier.mikuCard().padding(16.dp)) {
        Text("ABOUT MIKUOS", color = MikuTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MikuTealBright.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("39", color = MikuTealBright, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.width(14.dp))
            Column {
                Text(if (model.isNotEmpty() && !model.contains("qssi", ignoreCase = true)) model else "m500_mikuOS-v$mikuosVersion", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("MikuOS v$mikuosVersion (Android 14 GKI)", color = MikuTeal, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        AboutSpecRow("Device Identity", if (model.isNotEmpty() && !model.contains("qssi", ignoreCase = true)) model else "m500_mikuOS-v$mikuosVersion")
        AboutSpecRow("Hardware Model", "m500 Hatsune Miku Edition (M500_MIKU_4G)")
        AboutSpecRow("OS Release", "MikuOS v$mikuosVersion (Android 14 GKI)")
        AboutSpecRow("SoC Architecture", "Qualcomm Snapdragon 680 (SM6225 8-Core)")
        AboutSpecRow("DAC Hardware", "Dual Cirrus Logic CS43198 MasterHIFI™")
        AboutSpecRow("RGB Controller", "SGM31324 Pulsar TrueColor LED Engine")
        AboutSpecRow("Linux Kernel", "5.4.233-android14-gki (arm64-v8a)")
        AboutSpecRow("Root Status", if (RootShell.isAvailable()) "Magisk Privileged (uid=0)" else "Stock Unprivileged")

        Spacer(Modifier.height(14.dp))

        SettingsLinkRow(
            icon = Icons.Default.AutoFixHigh,
            title = "MikuOS Setup & Provisioning Wizard",
            subtitle = "Re-configure Google services, streaming platforms & DAC filters",
            onClick = {
                try {
                    val prefs = ctx.getSharedPreferences("miku_launcher_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("miku_onboarding_completed", false).apply()
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(homeIntent)
                } catch (_: Throwable) {}
            }
        )

        Spacer(Modifier.height(8.dp))

        SettingsLinkRow(
            icon = Icons.Default.Code,
            title = "Developer Options",
            subtitle = "USB debugging, OEM unlocking & GPU profiling",
            onClick = {
                openAospSettings(ctx, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "Developer options not available")
            }
        )
    }
}

// ----------------------------------------------------
// Helper Composables
// ----------------------------------------------------
@Composable
fun SettingsLinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MikuSurface2)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MikuTealBright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MikuMuted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = MikuMuted,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f }
        )
    }
}

@Composable
fun MetricPill(label: String, value: String) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MikuSurface2)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = MikuMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AboutSpecRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MikuMuted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
