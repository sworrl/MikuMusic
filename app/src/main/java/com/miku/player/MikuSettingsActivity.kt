package com.miku.player

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.miku.player.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.miku.player.*
import com.miku.player.network.MikuNetworkObservatoryModal
import com.miku.player.ui.MikuBackButton
import com.miku.player.ui.MikuTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MikuSettingsActivity : ComponentActivity() {

    private fun setupSystemBars() {
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        } catch (_: Throwable) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setupSystemBars()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = MikuCyan,
                    secondary = MikuNeonPink,
                    background = CyberDarkBg,
                    surface = Color(0xF5040D12),
                    surfaceVariant = Color(0xFF071E28),
                    onPrimary = Color.Black,
                    onSecondary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                MikuSettingsScreen(onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupSystemBars()
    }
}

data class SettingsCategoryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val badge: String? = null,
    val accentColor: Color = MikuCyan,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MikuSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    var searchQuery by remember { mutableStateOf("") }
    var chibiReaction by remember { mutableStateOf("💙 Cyber OS Ready") }

    var isBrainModalOpen by remember { mutableStateOf(false) }
    var isMonitorModalOpen by remember { mutableStateOf(false) }
    var isWifiStationOpen by remember { mutableStateOf(false) }
    var isAboutDeviceOpen by remember { mutableStateOf(activity?.intent?.getBooleanExtra("open_about_device", false) == true) }
    var isDevOptionsOpen by remember { mutableStateOf(activity?.intent?.getBooleanExtra("open_dev_options", false) == true) }
    var isDisplayModalOpen by remember { mutableStateOf(false) }
    var isBluetoothModalOpen by remember { mutableStateOf(false) }
    var isStorageModalOpen by remember { mutableStateOf(false) }
    var isAppsModalOpen by remember { mutableStateOf(false) }
    var isGmsModalOpen by remember { mutableStateOf(false) }
    var isSecurityModalOpen by remember { mutableStateOf(false) }

    val chibiReactions = listOf(
        "🎵 Hi Master!",
        "💙 Dual CS43131 Active",
        "⚡ 384kHz DSD256 Mode",
        "✨ Soundstage Max",
        "🎧 Lossless Engine Online",
        "🌸 Pocket Lock Ready",
        "🛡️ Data SIM Shield Active",
        "🛠️ Developer Mode Ready"
    )

    val categories = remember {
        listOf(
            SettingsCategoryItem(
                id = "network",
                title = "Network & internet",
                subtitle = "Mobile, Wi-Fi, hotspot & radio diagnostics",
                icon = Icons.Default.Wifi,
                badge = "Wi-Fi 5G",
                accentColor = MikuCyan,
                onClick = { isWifiStationOpen = true }
            ),
            SettingsCategoryItem(
                id = "bluetooth",
                title = "Bluetooth",
                subtitle = "Bluetooth, pairing, LDAC, aptX HD & AAC codecs",
                icon = Icons.Default.Bluetooth,
                badge = "LDAC",
                accentColor = Color(0xFF2979FF),
                onClick = { isBluetoothModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "usb",
                title = "USB Preferences",
                subtitle = "USB DAC UAC2 direct bypass, OTG & file transport",
                icon = Icons.Default.Usb,
                badge = "UAC2",
                accentColor = Color(0xFF00E5FF),
                onClick = { ctx.startActivity(Intent(ctx, HardwareSettingsActivity::class.java)) }
            ),
            SettingsCategoryItem(
                id = "audio",
                title = "Audio settings",
                subtitle = "Digital Filter (NOS/Fast), CS43131 Gain (+6dB), DRE Mode & Direct ALSA",
                icon = Icons.Default.Headphones,
                badge = "CS43131",
                accentColor = Color(0xFF7C4DFF),
                onClick = { ctx.startActivity(Intent(ctx, HardwareSettingsActivity::class.java)) }
            ),
            SettingsCategoryItem(
                id = "pulsar",
                title = "Pulsar RGB Lighting",
                subtitle = "SGM31324 PWM Breathing, Bitrate BPM Sync & Charging FX",
                icon = Icons.Default.Lightbulb,
                badge = "RGB PWM",
                accentColor = MikuNeonPink,
                onClick = { ctx.startActivity(Intent(ctx, PulsarSettingsActivity::class.java)) }
            ),
            SettingsCategoryItem(
                id = "fn_lock",
                title = "Hardware Keys & Wheel",
                subtitle = "Physical Fn Switch, Rotary Volume Knob & Key Routing",
                icon = Icons.Default.Tune,
                badge = "Hardware",
                accentColor = Color(0xFF00E676),
                onClick = { ctx.startActivity(Intent(ctx, FnLockSettingsActivity::class.java)) }
            ),
            SettingsCategoryItem(
                id = "apps",
                title = "Apps & Permissions",
                subtitle = "Installed applications, default app roles & storage",
                icon = Icons.Default.Apps,
                accentColor = Color(0xFFFF9100),
                onClick = { isAppsModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "battery",
                title = "Battery & Power Core",
                subtitle = "CellWise CW2015 Fuel Gauge & MP2731 PMIC Telemetry",
                icon = Icons.Default.BatteryChargingFull,
                badge = "CW2015",
                accentColor = Color(0xFF00E676),
                onClick = {
                    val intent = ctx.packageManager.getLaunchIntentForPackage("com.miku.launcher")?.apply {
                        putExtra("open_battery", true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                    if (intent != null) try { ctx.startActivity(intent) } catch (_: Throwable) {}
                }
            ),
            SettingsCategoryItem(
                id = "storage",
                title = "Storage",
                subtitle = "MicroSD /storage/EAFF-98FE & Internal Flash Management",
                icon = Icons.Default.SdCard,
                accentColor = Color(0xFF00E5FF),
                onClick = { isStorageModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "sound",
                title = "Sound & Volume",
                subtitle = "Hardware volume HUD, output routing & sound profiles",
                icon = Icons.Default.VolumeUp,
                accentColor = MikuCyan,
                onClick = { com.miku.player.volume.MikuVolumeManager.triggerHud(ctx) }
            ),
            SettingsCategoryItem(
                id = "display",
                title = "Display",
                subtitle = "Brightness, screen timeout, dark theme & refresh",
                icon = Icons.Default.BrightnessMedium,
                accentColor = Color(0xFFFFD600),
                onClick = { isDisplayModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "security",
                title = "Security & Screen Lock",
                subtitle = "Lockscreen particle physics, device credentials & keys",
                icon = Icons.Default.Lock,
                accentColor = Color(0xFFE040FB),
                onClick = { isSecurityModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "gms_manager",
                title = "Google Services (GMS)",
                subtitle = "Granular switches for Play Services, Store, Chrome & Pixel Apps",
                icon = Icons.Default.CloudSync,
                badge = "GMS Controls",
                accentColor = Color(0xFF4285F4),
                onClick = { isGmsModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "dev_options",
                title = "Developer Options & ADB",
                subtitle = "Wireless ADB Port 5555, USB Debugging, Root & Scales",
                icon = Icons.Default.DeveloperMode,
                badge = "ADB 5555",
                accentColor = Color(0xFF00E676),
                onClick = { isDevOptionsOpen = true }
            ),
            SettingsCategoryItem(
                id = "brain",
                title = "Sentinel Watchdog & Brain",
                subtitle = "Real-time process health, daemon monitors & memory integrity",
                icon = Icons.Default.Shield,
                badge = "Bones: 8",
                accentColor = Color(0xFF00E676),
                onClick = { isBrainModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "ingress",
                title = "Ingress Staging Telemetry",
                subtitle = "Multi-Transport Rsync streaming, worker queues & card sync",
                icon = Icons.Default.Sync,
                badge = "Port 8787",
                accentColor = MikuNeonPink,
                onClick = { isMonitorModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "device_info",
                title = "About HiBy M500 DAP",
                subtitle = "Snapdragon 665 · Dual CS43131 · Android 13 Core · Kernel",
                icon = Icons.Default.Info,
                badge = "M500 DAP",
                accentColor = MikuCyan,
                onClick = { isAboutDeviceOpen = true }
            )
        )
    }

    val filteredCategories = remember(searchQuery) {
        if (searchQuery.isBlank()) categories
        else categories.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.subtitle.contains(searchQuery, ignoreCase = true) ||
            (it.badge?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        Image(
            painter = painterResource(id = R.drawable.miku_bg_page1),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xEE040D12),
                            Color(0xCC040D12),
                            Color(0xF0040D12)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(top = 6.dp)
        ) {
            // ============================================================
            // TOP HEADER BAR (STOCK HIBY DISPLAY STYLE)
            // ============================================================
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikuBackButton(onClick = onBack)

                Spacer(Modifier.width(10.dp))

                Text(
                    text = "Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.weight(1f)
                )

                // Interactive Animated Chibi Miku Companion Header Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x4400E5FF))
                        .border(1.dp, MikuCyan, RoundedCornerShape(16.dp))
                        .clickable {
                            chibiReaction = chibiReactions.random()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_search_head_miku),
                        contentDescription = "Chibi Companion",
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = chibiReaction,
                        color = MikuCyan,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ============================================================
            // STOCK HIBY SEARCH SETTINGS PILL BAR
            // ============================================================
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xEE0D222A))
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(22.dp))
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MikuCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search settings", color = MikuTextSecondary, fontSize = 13.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = MikuCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ============================================================
            // STOCK HIBY SQUARE HARDWARE BOXES / CATEGORIES LIST
            // ============================================================
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredCategories, key = { it.id }) { item ->
                    Cyber3dSettingsCard(item = item)
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val legacyIntent = ctx.packageManager.getLaunchIntentForPackage("com.android.settings")
                                ?: Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings")
                            try { ctx.startActivity(legacyIntent) } catch (_: Throwable) {
                                Toast.makeText(ctx, "Legacy Settings not found", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, CyberGlassBorder),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Open Stock Android Settings (com.android.settings)", color = MikuTextSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    // Bottom spacing padding for OS-wide Miku gesture overlay
                    Spacer(Modifier.height(18.dp))
                }
            }

        }

        // Modals
        if (isAboutDeviceOpen) {
            MikuAboutDeviceModal(
                onDismissRequest = { isAboutDeviceOpen = false },
                onOpenDevOptions = {
                    isAboutDeviceOpen = false
                    isDevOptionsOpen = true
                }
            )
        }
        if (isDevOptionsOpen) {
            MikuDeveloperOptionsModal(
                onDismissRequest = { isDevOptionsOpen = false }
            )
        }
        if (isDisplayModalOpen) {
            MikuDisplaySettingsModal(onDismissRequest = { isDisplayModalOpen = false })
        }
        if (isBluetoothModalOpen) {
            MikuBluetoothSettingsModal(onDismissRequest = { isBluetoothModalOpen = false })
        }
        if (isStorageModalOpen) {
            MikuStorageSettingsModal(onDismissRequest = { isStorageModalOpen = false })
        }
        if (isAppsModalOpen) {
            MikuAppsSettingsModal(onDismissRequest = { isAppsModalOpen = false })
        }
        if (isGmsModalOpen) {
            com.miku.player.settings.MikuGmsManagerModal(onDismissRequest = { isGmsModalOpen = false })
        }
        if (isSecurityModalOpen) {
            MikuSecuritySettingsModal(onDismissRequest = { isSecurityModalOpen = false })
        }
        if (isBrainModalOpen) {
            MikuBrainModal(onDismissRequest = { isBrainModalOpen = false })
        }
        if (isMonitorModalOpen) {
            MikuMonitorModal(onDismissRequest = { isMonitorModalOpen = false })
        }
        if (isWifiStationOpen) {
            MikuNetworkObservatoryModal(onDismissRequest = { isWifiStationOpen = false })
        }
    }
}

/**
 * HiBy-Style Square Hardware Box Setting Card.
 * Features a distinct square icon box on the left, high-contrast title & subtitle,
 * and clean cyber glass borders.
 */
@Composable
fun Cyber3dSettingsCard(item: SettingsCategoryItem) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xEE091D26),
                        Color(0xFF041218)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            item.accentColor.copy(alpha = 0.5f),
                            CyberGlassBorder.copy(alpha = 0.25f)
                        )
                    )
                ),
                RoundedCornerShape(12.dp)
            )
            .clickable { item.onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Square Hardware Icon Box (HiBy Style)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(item.accentColor.copy(alpha = 0.16f))
                    .border(1.dp, item.accentColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.title,
                    tint = item.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // Title & Subtitle
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.badge != null) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(item.accentColor.copy(alpha = 0.2f))
                                .border(0.5.dp, item.accentColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = item.badge,
                                color = item.accentColor,
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    color = MikuTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(6.dp))

            // Subtle Right Chevron Indicator
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MikuCyan.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/* ========================================================================= */
/* 1. MIKU DISPLAY SETTINGS MODAL                                            */
/* ========================================================================= */

@Composable
fun MikuDisplaySettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    var brightness by remember {
        mutableFloatStateOf(
            try {
                val b = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                b / 255f
            } catch (_: Throwable) { 0.5f }
        )
    }
    var autoBrightness by remember {
        mutableStateOf(
            try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } catch (_: Throwable) { false }
        )
    }
    var screenTimeoutMs by remember {
        mutableIntStateOf(
            try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60000)
            } catch (_: Throwable) { 60000 }
        )
    }

    val timeoutOptions = listOf(
        15000 to "15s",
        30000 to "30s",
        60000 to "1m",
        120000 to "2m",
        300000 to "5m",
        600000 to "10m",
        -1 to "Never"
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF0081C24), Color(0xF5040D12))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFFFD600), MikuCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                MikuTopBar(title = "Display & Screen", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Brightness Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Display Brightness", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Text("${(brightness * 100).toInt()}%", color = Color(0xFFFFD600), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = brightness,
                                onValueChange = { next ->
                                    brightness = next
                                    val intVal = (next * 255).toInt().coerceIn(1, 255)
                                    try {
                                        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, intVal)
                                    } catch (_: Throwable) {}
                                    RootShell.execFast("settings put system screen_brightness $intVal")
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFFD600),
                                    activeTrackColor = Color(0xFFFFD600),
                                    inactiveTrackColor = Color(0x33FFD600)
                                )
                            )
                        }
                    }

                    // Auto Brightness Switch
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            CyberSwitchRow(
                                title = "Adaptive Brightness",
                                subtitle = "Adjust screen brightness dynamically via ambient light sensor",
                                checked = autoBrightness,
                                onCheckedChange = {
                                    val hasCamera = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                    if (it && !hasCamera) {
                                        // The M500 has no hardware ALS; adaptive brightness is driven by the
                                        // camera-luma sensor, which needs CAMERA. Don't flip the toggle on until granted.
                                        Toast.makeText(ctx, "Camera permission needed for adaptive brightness", Toast.LENGTH_LONG).show()
                                        (ctx as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 4021)
                                    } else {
                                        autoBrightness = it
                                        // Drive the real camera-luma auto-brightness engine (SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                                        // alone is a no-op on this device — there's no hardware ambient light sensor).
                                        com.miku.player.brightness.MikuAmbientLightService.setEnabled(ctx, it)
                                        val mode = if (it) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                        try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode) } catch (_: Throwable) {}
                                        RootShell.execFast("settings put system screen_brightness_mode $mode")
                                    }
                                }
                            )
                        }
                    }

                    // Screen Off Timeout
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Text("Screen Sleep Timeout", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                timeoutOptions.take(4).forEach { (ms, label) ->
                                    val isSel = screenTimeoutMs == ms
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSel) Color(0xFFFFD600) else Color.White.copy(alpha = 0.08f))
                                            .border(1.dp, if (isSel) Color(0xFFFFD600) else CyberGlassBorder, RoundedCornerShape(8.dp))
                                            .clickable {
                                                screenTimeoutMs = ms
                                                val v = if (ms == -1) Int.MAX_VALUE else ms
                                                try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, v) } catch (_: Throwable) {}
                                                RootShell.execFast("settings put system screen_off_timeout $v")
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, color = if (isSel) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                timeoutOptions.drop(4).forEach { (ms, label) ->
                                    val isSel = screenTimeoutMs == ms
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSel) Color(0xFFFFD600) else Color.White.copy(alpha = 0.08f))
                                            .border(1.dp, if (isSel) Color(0xFFFFD600) else CyberGlassBorder, RoundedCornerShape(8.dp))
                                            .clickable {
                                                screenTimeoutMs = ms
                                                val v = if (ms == -1) 2147483647 else ms
                                                try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, v) } catch (_: Throwable) {}
                                                RootShell.execFast("settings put system screen_off_timeout $v")
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, color = if (isSel) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Display Specs Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CyberInfoRow("Display Panel", "4.0\" IPS Retina (1080 x 540)")
                            CyberInfoRow("Pixel Density", "300 PPI / 60 Hz")
                            CyberInfoRow("Theme Mode", "Cyber Hatsune Miku Dark Mode")
                            CyberInfoRow("Hardware Acceleration", "Vulkan & OpenGLES 3.2 Active")
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 2. MIKU BLUETOOTH SETTINGS MODAL                                          */
/* ========================================================================= */

@Composable
fun MikuBluetoothSettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        com.miku.player.bluetooth.MikuBluetoothController.init(ctx)
    }

    val isBtEnabled by com.miku.player.bluetooth.MikuBluetoothController.isBluetoothEnabled.collectAsState()
    val isScanning by com.miku.player.bluetooth.MikuBluetoothController.isScanning.collectAsState()
    val pairedDevices by com.miku.player.bluetooth.MikuBluetoothController.pairedDevices.collectAsState()
    val discoveredDevices by com.miku.player.bluetooth.MikuBluetoothController.discoveredDevices.collectAsState()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF0081C24), Color(0xF5040D12))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFF2979FF), MikuCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                MikuTopBar(title = "Bluetooth & Codecs", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 1. Master Bluetooth Switch Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isBtEnabled) Color(0x222979FF) else CyberGlassCard)
                                .border(1.dp, if (isBtEnabled) Color(0xFF2979FF) else CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Bluetooth Master Power", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Text(if (isBtEnabled) "🔵 Transceiver Online · Hi-Res Ready" else "⚪ Radio Powered Down", color = if (isBtEnabled) Color(0xFF2979FF) else MikuTextSecondary, fontSize = 10.5.sp)
                                }
                                Switch(
                                    checked = isBtEnabled,
                                    onCheckedChange = { next ->
                                        com.miku.player.bluetooth.MikuBluetoothController.toggleBluetooth(next)
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color(0xFF2979FF))
                                )
                            }
                        }
                    }

                    if (isBtEnabled) {
                        // 2. Paired Devices Card
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CyberGlassCard)
                                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("PAIRED BLUETOOTH DEVICES (${pairedDevices.size})", color = Color(0xFF2979FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                    
                                    Button(
                                        onClick = {
                                            if (isScanning) {
                                                com.miku.player.bluetooth.MikuBluetoothController.stopScan()
                                            } else {
                                                com.miku.player.bluetooth.MikuBluetoothController.startScan()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Color(0x33FF4081) else Color(0x332979FF)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text(if (isScanning) "Stop Scan" else "+ Scan Nearby", color = if (isScanning) Color(0xFFFF80AB) else Color(0xFF2979FF), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                if (pairedDevices.isEmpty()) {
                                    Text("No paired wireless gear found.", color = MikuTextSecondary, fontSize = 11.sp)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        pairedDevices.forEach { devItem ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (devItem.isConnected) Color(0x222979FF) else Color(0x11FFFFFF))
                                                    .border(1.dp, if (devItem.isConnected) Color(0xFF2979FF) else Color.Transparent, RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        if (!devItem.isConnected && !devItem.isConnecting) {
                                                            com.miku.player.bluetooth.MikuBluetoothController.connectDevice(devItem.device)
                                                        }
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
                                                    tint = if (devItem.isConnected) Color(0xFF00E5FF) else Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(devItem.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        text = when {
                                                            devItem.isConnected -> "🟢 Active Audio Connection"
                                                            devItem.isConnecting -> "🟡 Connecting..."
                                                            else -> devItem.address
                                                        },
                                                        color = if (devItem.isConnected) Color(0xFF00E5FF) else if (devItem.isConnecting) Color(0xFFFFD54F) else MikuTextSecondary,
                                                        fontSize = 9.5.sp
                                                    )
                                                }

                                                Spacer(Modifier.width(6.dp))

                                                if (devItem.isConnected) {
                                                    Button(
                                                        onClick = { com.miku.player.bluetooth.MikuBluetoothController.disconnectDevice(devItem.device) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF5252)),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(26.dp)
                                                    ) {
                                                        Text("Disconnect", color = Color(0xFFFF8A80), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                } else if (devItem.isConnecting) {
                                                    CircularProgressIndicator(
                                                        color = Color(0xFF2979FF),
                                                        modifier = Modifier.size(18.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Button(
                                                        onClick = { com.miku.player.bluetooth.MikuBluetoothController.connectDevice(devItem.device) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(26.dp)
                                                    ) {
                                                        Text("Connect", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }

                                                Spacer(Modifier.width(4.dp))

                                                IconButton(
                                                    onClick = { com.miku.player.bluetooth.MikuBluetoothController.unpairDevice(devItem.device) },
                                                    modifier = Modifier.size(26.dp)
                                                ) {
                                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Forget", tint = MikuTextSecondary, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Live Discovered Nearby Gear
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CyberGlassCard)
                                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("AVAILABLE NEARBY GEAR", color = Color(0xFF2979FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                        if (isScanning) {
                                            Spacer(Modifier.width(6.dp))
                                            CircularProgressIndicator(color = Color(0xFF2979FF), modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            if (isScanning) {
                                                com.miku.player.bluetooth.MikuBluetoothController.stopScan()
                                            } else {
                                                com.miku.player.bluetooth.MikuBluetoothController.startScan()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Color(0x33FF4081) else Color(0x332979FF)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text(if (isScanning) "Stop" else "Scan", color = if (isScanning) Color(0xFFFF80AB) else Color(0xFF2979FF), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                if (discoveredDevices.isEmpty()) {
                                    Text(if (isScanning) "Scanning for headphones, IEMs & wireless DACs..." else "Tap 'Scan' to discover nearby devices.", color = MikuTextSecondary, fontSize = 11.sp)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        discoveredDevices.forEach { devItem ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0x11FFFFFF))
                                                    .clickable {
                                                        com.miku.player.bluetooth.MikuBluetoothController.pairDevice(devItem.device)
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
                                                    tint = Color(0xFF2979FF),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(devItem.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text("${devItem.address} · Signal ${devItem.rssi} dBm", color = MikuTextSecondary, fontSize = 9.5.sp)
                                                }
                                                Button(
                                                    onClick = { com.miku.player.bluetooth.MikuBluetoothController.pairDevice(devItem.device) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x332979FF)),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.height(26.dp)
                                                ) {
                                                    Text("Pair & Connect", color = Color(0xFF82B1FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Hi-Res Audio Codecs Priority
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CyberGlassCard)
                                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Hi-Res Codec Pipeline", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                listOf(
                                    "LDAC (990 / 660 / 330 kbps 96kHz/24-Bit)" to Color(0xFF00E676),
                                    "Qualcomm aptX HD (576 kbps 48kHz/24-Bit)" to Color(0xFF00E5FF),
                                    "aptX Adaptive (Low Latency / Dynamic)" to Color(0xFF2979FF),
                                    "AAC / SBC (Standard Audio Codec)" to MikuTextSecondary
                                ).forEach { (codec, col) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(col))
                                        Spacer(Modifier.width(8.dp))
                                        Text(codec, color = Color.White, fontSize = 10.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 3. MIKU STORAGE SETTINGS MODAL                                            */
/* ========================================================================= */

@Composable
fun MikuStorageSettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    val internalStats = remember {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
            val free = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
            val used = total - free
            Triple(used, total, free)
        } catch (_: Throwable) { Triple(24L, 64L, 40L) }
    }

    val sdStats = remember {
        try {
            val sdDir = File("/storage/EAFF-98FE")
            if (sdDir.exists()) {
                val stat = StatFs(sdDir.path)
                val total = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
                val free = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
                val used = total - free
                Triple(used, total, free)
            } else Triple(0L, 0L, 0L)
        } catch (_: Throwable) { Triple(0L, 0L, 0L) }
    }

    var isRescanning by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF0081C24), Color(0xF5040D12))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFF00E5FF), MikuPink)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                MikuTopBar(title = "Storage & MicroSD", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Internal Storage Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Internal eMMC Flash", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Text("${internalStats.first} GB / ${internalStats.second} GB", color = MikuCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (internalStats.first.toFloat() / internalStats.second.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = MikuCyan,
                                trackColor = Color(0xFF102830)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("${internalStats.third} GB Free Space available", color = MikuTextSecondary, fontSize = 10.sp)
                        }
                    }

                    // MicroSD Storage Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("MicroSD Card (/storage/EAFF-98FE)", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Text(if (sdStats.second > 0) "${sdStats.first} GB / ${sdStats.second} GB" else "Not Mounted", color = MikuNeonPink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                            if (sdStats.second > 0) {
                                LinearProgressIndicator(
                                    progress = { (sdStats.first.toFloat() / sdStats.second.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = MikuNeonPink,
                                    trackColor = Color(0xFF301020)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("${sdStats.third} GB Free for Lossless FLAC / DSD Music", color = MikuTextSecondary, fontSize = 10.sp)
                            } else {
                                Text("Insert FAT32 / exFAT MicroSD Card for expanded music library storage", color = MikuTextSecondary, fontSize = 10.sp)
                            }
                        }
                    }

                    // Rescan Media Button
                    item {
                        Button(
                            onClick = {
                                isRescanning = true
                                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                    try {
                                        val intent = Intent(ctx, LibraryScanService::class.java)
                                        ctx.startService(intent)
                                    } catch (_: Throwable) {}
                                    kotlinx.coroutines.delay(2000)
                                    isRescanning = false
                                }
                                Toast.makeText(ctx, "⚡ Deep SD Ingress Library Scan Triggered", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MikuCyan.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, MikuCyan),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = MikuCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRescanning) "Scanning Library..." else "Trigger Fast Audio Library Rescan", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 4. MIKU APPS SETTINGS MODAL                                               */
/* ========================================================================= */

@Composable
fun MikuAppsSettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val installedApps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName == "com.miku.player" }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF0081C24), Color(0xF5040D12))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFFF9100), MikuCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                MikuTopBar(title = "Installed Applications", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(installedApps, key = { it.packageName }) { app ->
                        val label = remember { pm.getApplicationLabel(app).toString() }
                        val icon = remember { try { pm.getApplicationIcon(app) } catch (_: Throwable) { null } }

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(12.dp))
                                .clickable {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${app.packageName}")
                                        }
                                        ctx.startActivity(intent)
                                    } catch (_: Throwable) {}
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (icon != null) {
                                    Image(bitmap = icon.toBitmap(48, 48).asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp))
                                } else {
                                    Icon(Icons.Default.Apps, contentDescription = null, tint = Color(0xFFFF9100), modifier = Modifier.size(36.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(app.packageName, color = MikuTextSecondary, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MikuTextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 5. MIKU SECURITY SETTINGS MODAL                                           */
/* ========================================================================= */

@Composable
fun MikuSecuritySettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    var doubleTapPowerCamera by remember {
        mutableStateOf(
            try {
                Settings.Secure.getInt(ctx.contentResolver, "camera_double_tap_power_gesture_disabled", 0) == 0
            } catch (_: Throwable) { true }
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF0081C24), Color(0xF5040D12))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFE040FB), MikuCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                MikuTopBar(title = "Security & Gestures", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Double Tap Power
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            CyberSwitchRow(
                                title = "Double-Press Power For Camera",
                                subtitle = "Quickly press the power button twice to launch Camera",
                                checked = doubleTapPowerCamera,
                                onCheckedChange = {
                                    doubleTapPowerCamera = it
                                    val disabled = if (it) 0 else 1
                                    try {
                                        Settings.Secure.putInt(ctx.contentResolver, "camera_double_tap_power_gesture_disabled", disabled)
                                    } catch (_: Throwable) {}
                                    RootShell.execFast("settings put secure camera_double_tap_power_gesture_disabled $disabled")
                                }
                            )
                        }
                    }


                    // Security Info
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CyberInfoRow("Encryption Status", "Qualcomm TrustZone AES-256 Active")
                            CyberInfoRow("Biometric Hardware", "PIN / Pattern Lock Active")
                            CyberInfoRow("SELinux Policy", "Enforcing (Custom Magisk Sandbox)")
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 6. ABOUT DEVICE & DEVELOPER MODALS                                        */
/* ========================================================================= */

@Composable
fun MikuAboutDeviceModal(
    onDismissRequest: () -> Unit,
    onOpenDevOptions: () -> Unit
) {
    val ctx = LocalContext.current
    var devTapCount by remember { mutableIntStateOf(0) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val uptimeFormatted = remember {
        val sec = SystemClock.elapsedRealtime() / 1000
        val hrs = sec / 3600
        val mins = (sec % 3600) / 60
        "${hrs}h ${mins}m"
    }

    val kernelVersion = remember {
        try {
            val v = File("/proc/version").readText()
            v.substringBefore(" (").replace("Linux version ", "")
        } catch (_: Throwable) {
            System.getProperty("os.version") ?: "4.19.157"
        }
    }

    val wifiIp = remember {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else ""
        } catch (_: Throwable) { "" }
    }

    val storageStats = remember {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
            val free = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
            val used = total - free
            Pair("${used} GB / ${total} GB", "${free} GB Free")
        } catch (_: Throwable) {
            Pair("24.5 GB / 64 GB", "39.5 GB Free")
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF0081C24), Color(0xF5040D12))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(MikuCyan, MikuNeonPink)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                MikuTopBar(title = "About HiBy M500 DAP", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Hero Branding Card
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0x3300E5FF), Color(0x33FF4081))))
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "HiBy M500 (Miku_San)",
                                        color = MikuCyan,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF00E676).copy(alpha = 0.2f))
                                            .border(1.dp, Color(0xFF00E676), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("ROOTED DAP", color = Color(0xFF00E676), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Custom Audiophile Operating System · Hatsune Miku Cyber Edition",
                                    color = MikuTextSecondary,
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                    }

                    // Hardware Specs Section
                    item {
                        Text(
                            "HARDWARE ARCHITECTURE",
                            color = MikuCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CyberInfoRow("SoC Processor", "Qualcomm Snapdragon 665 (SM6125)")
                            CyberInfoRow("CPU Cores", "8x Kryo 260 @ 2.0 GHz (64-Bit Octa-Core)")
                            CyberInfoRow("Audio DAC", "Dual Cirrus Logic CS43131 MasterHIFI™")
                            CyberInfoRow("Max PCM / DSD", "Direct ALSA 384kHz 32-Bit / DSD256")
                            CyberInfoRow("RAM Memory", "4.0 GB LPDDR4x Ultra High Speed")
                            CyberInfoRow("Internal Flash", storageStats.first + " (${storageStats.second})")
                            CyberInfoRow("MicroSD Storage", "/storage/EAFF-98FE (Mounted SDXC)")
                            CyberInfoRow("Display Panel", "4.0\" IPS Retina 1080x540 (300 PPI)")
                            CyberInfoRow("Battery Cell", "3200 mAh Li-Po w/ QuickCharge 3.0")
                        }
                    }

                    // Software & Firmware Section
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "SOFTWARE & FIRMWARE CORE",
                            color = MikuNeonPink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CyberInfoRow("Android Version", "Android 13 Custom Core (API 33)")
                            CyberInfoRow("Security Patch", "2026-08-01")
                            CyberInfoRow("Linux Kernel", kernelVersion)
                            CyberInfoRow("Baseband / Modem", "MPSS.AT.4.4.c4-00041-NICOBAR")
                            CyberInfoRow("Firmware Release", "v0.9.179-MikuCustom (HiBy M500 Pro)")

                            // Build Number (Interactive 7-Tap Developer Unlock)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MikuCyan.copy(alpha = 0.08f))
                                    .clickable {
                                        devTapCount++
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        if (devTapCount >= 7) {
                                            Toast.makeText(ctx, "🎉 Developer Options Unlocked!", Toast.LENGTH_LONG).show()
                                            onOpenDevOptions()
                                        } else if (devTapCount >= 3) {
                                            Toast.makeText(ctx, "🛠️ Tap ${7 - devTapCount} more times for Developer Mode", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Build Number (Tap 7x for Dev)", color = MikuCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        Text("M500_MIKU_AUDIOPHILE_V2.0_20260818", color = Color.White, fontSize = 10.sp)
                                    }
                                    Text(
                                        if (devTapCount >= 7) "UNLOCKED" else "${devTapCount}/7",
                                        color = if (devTapCount >= 7) Color(0xFF00E676) else MikuNeonPink,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Network Telemetry Section
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "NETWORK & TELEMETRY",
                            color = Color(0xFF00E676),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CyberInfoRow("Wi-Fi IP Address", wifiIp)
                            CyberInfoRow("Wi-Fi MAC Address", "74:97:79:D3:A2:18")
                            CyberInfoRow("Bluetooth MAC", "74:97:79:D3:A2:19")
                            CyberInfoRow("System Uptime", uptimeFormatted)
                            CyberInfoRow("Serial Number", "M500A192800472")
                            CyberInfoRow("SIM Carrier Link", "Google Fi (Data-Only Shield Active)")
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun MikuDeveloperOptionsModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    var isAdbEnabled by remember {
        mutableStateOf(
            try {
                val p = Runtime.getRuntime().exec("getprop service.adb.tcp.port")
                p.inputStream.bufferedReader().readText().trim() == "5555"
            } catch (_: Throwable) { false }
        )
    }

    val wifiIp = remember {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else ""
        } catch (_: Throwable) { "" }
    }

    var usbDebugging by remember {
        mutableStateOf(
            try {
                Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 1) == 1
            } catch (_: Throwable) { true }
        )
    }

    var stayAwake by remember {
        mutableStateOf(
            try {
                Settings.Global.getInt(ctx.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0
            } catch (_: Throwable) { false }
        )
    }

    var pointerLocation by remember {
        mutableStateOf(
            try {
                Settings.System.getInt(ctx.contentResolver, "pointer_location", 0) == 1
            } catch (_: Throwable) { false }
        )
    }

    var animScale by remember { mutableFloatStateOf(0.5f) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF0081C24), Color(0xF5040D12))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFF00E676), MikuCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                MikuTopBar(title = "Developer Options & ADB", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Wireless ADB Port 5555 Card
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isAdbEnabled) Color(0x3300E676) else CyberGlassCard)
                                .border(1.5.dp, if (isAdbEnabled) Color(0xFF00E676) else CyberGlassBorder, RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Wireless ADB (Port 5555)",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont
                                        )
                                        Text(
                                            if (isAdbEnabled) "🟢 Active on $wifiIp:5555" else "🔴 Disabled (TCP/IP Port Closed)",
                                            color = if (isAdbEnabled) Color(0xFF00E676) else MikuNeonPink,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Switch(
                                        checked = isAdbEnabled,
                                        onCheckedChange = { next ->
                                            isAdbEnabled = next
                                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                                val cmd = if (next) {
                                                    "setprop service.adb.tcp.port 5555 && stop adbd && start adbd"
                                                } else {
                                                    "setprop service.adb.tcp.port -1 && stop adbd && start adbd"
                                                }
                                                try {
                                                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                                                } catch (_: Throwable) {}
                                            }
                                            Toast.makeText(
                                                ctx,
                                                if (next) "⚡ Wireless ADB Started on $wifiIp:5555\nRun: adb connect $wifiIp:5555" else "Wireless ADB Stopped",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = Color(0xFF00E676)
                                        )
                                    )
                                }

                                if (isAdbEnabled) {
                                    Spacer(Modifier.height(8.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF04131A))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            "Connect command:\n  adb connect $wifiIp:5555",
                                            color = MikuCyan,
                                            fontSize = 10.5.sp,
                                            fontFamily = AudiowideFont
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // System Debugging Toggles
                    item {
                        Text(
                            "DEBUGGING & POWER CONTROLS",
                            color = MikuCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CyberSwitchRow(
                                title = "USB Debugging",
                                subtitle = "Enable Android Debug Bridge over USB Type-C",
                                checked = usbDebugging,
                                onCheckedChange = {
                                    usbDebugging = it
                                    try {
                                        Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, if (it) 1 else 0)
                                    } catch (_: Throwable) {}
                                }
                            )

                            CyberSwitchRow(
                                title = "Stay Awake While Charging",
                                subtitle = "Screen will never sleep while connected to power",
                                checked = stayAwake,
                                onCheckedChange = {
                                    stayAwake = it
                                    try {
                                        Settings.Global.putInt(ctx.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, if (it) 7 else 0)
                                    } catch (_: Throwable) {}
                                }
                            )

                            CyberSwitchRow(
                                title = "Pointer Location Overlay",
                                subtitle = "Show screen coordinates and touch trails",
                                checked = pointerLocation,
                                onCheckedChange = {
                                    pointerLocation = it
                                    try {
                                        Settings.System.putInt(ctx.contentResolver, "pointer_location", if (it) 1 else 0)
                                    } catch (_: Throwable) {}
                                }
                            )
                        }
                    }

                    // Animation & Speed Scales
                    item {
                        Text(
                            "GRAPHICS & ANIMATION SPEED",
                            color = MikuNeonPink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Window & Transition Scale: ${animScale}x", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0.0f to "OFF", 0.5f to "0.5x (FAST)", 1.0f to "1.0x (DEFAULT)").forEach { (scale, label) ->
                                    val isSelected = animScale == scale
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) MikuCyan else Color.White.copy(alpha = 0.08f))
                                            .border(1.dp, if (isSelected) MikuCyan else CyberGlassBorder, RoundedCornerShape(8.dp))
                                            .clickable {
                                                animScale = scale
                                                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                                    try {
                                                        Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global window_animation_scale $scale && settings put global transition_animation_scale $scale && settings put global animator_duration_scale $scale")).waitFor()
                                                    } catch (_: Throwable) {}
                                                }
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, color = if (isSelected) Color.Black else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun CyberInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MikuTextSecondary, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CyberSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MikuTextSecondary, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color(0xFF00E676)
            )
        )
    }
}
