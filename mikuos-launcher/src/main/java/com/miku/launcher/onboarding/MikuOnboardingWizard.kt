package com.miku.launcher.onboarding

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.launcher.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// Cyberpunk Palette
val MikuOnboardingTeal = Color(0xFF00E5FF)
val MikuOnboardingPink = Color(0xFFFF4081)
val MikuOnboardingGreen = Color(0xFF00E676)
val MikuOnboardingGold = Color(0xFFFFD600)
val MikuOnboardingPurple = Color(0xFFB388FF)
val MikuOnboardingBg = Color(0xFF040D14)
val MikuOnboardingCard = Color(0xDD0A1926)
val MikuOnboardingCardSelected = Color(0x3300E5FF)
val MikuOnboardingBorder = Color(0x5500E5FF)

data class SupportedLanguage(
    val code: String,
    val name: String,
    val nativeName: String,
    val flag: String,
    val isMikuUiComplete: Boolean = false,
    val region: String = "Global"
)

val SystemLanguages = listOf(
    SupportedLanguage("en", "English", "English (United States)", "🇺🇸", isMikuUiComplete = true, region = "Americas"),
    SupportedLanguage("ja", "Japanese", "日本語 (Japanese)", "🇯🇵", isMikuUiComplete = true, region = "Asia"),
    SupportedLanguage("zh_CN", "Chinese (Simplified)", "简体中文", "🇨🇳", isMikuUiComplete = true, region = "Asia"),
    SupportedLanguage("zh_TW", "Chinese (Traditional)", "繁體中文", "🇹🇼", isMikuUiComplete = true, region = "Asia"),
    SupportedLanguage("ko", "Korean", "한국어", "🇰🇷", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("es", "Spanish", "Español", "🇪🇸", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("fr", "French", "Français", "🇫🇷", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("de", "German", "Deutsch", "🇩🇪", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("it", "Italian", "Italiano", "🇮🇹", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("pt", "Portuguese", "Português", "🇵🇹", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("ru", "Russian", "Русский", "🇷🇺", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("ar", "Arabic", "العربية", "🇸🇦", isMikuUiComplete = false, region = "Middle East"),
    SupportedLanguage("hi", "Hindi", "हिन्दी", "🇮🇳", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("th", "Thai", "ไทย", "🇹🇭", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("vi", "Vietnamese", "Tiếng Việt", "🇻🇳", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("id", "Indonesian", "Bahasa Indonesia", "🇮🇩", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("tr", "Turkish", "Türkçe", "🇹🇷", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("pl", "Polish", "Polski", "🇵🇱", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("nl", "Dutch", "Nederlands", "🇳🇱", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("sv", "Swedish", "Svenska", "🇸🇪", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("uk", "Ukrainian", "Українська", "🇺🇦", isMikuUiComplete = false, region = "Europe")
)

data class TimeZoneOption(
    val id: String,
    val name: String,
    val city: String,
    val region: String,
    val offset: String
)

val SupportedTimeZones = listOf(
    TimeZoneOption("Pacific/Honolulu", "Hawaii Time (Honolulu)", "Honolulu", "Americas", "UTC-10:00"),
    TimeZoneOption("America/Anchorage", "Alaska Time (Anchorage)", "Anchorage", "Americas", "UTC-9:00"),
    TimeZoneOption("America/Los_Angeles", "Pacific Time (Los Angeles, Seattle)", "Los Angeles", "Americas", "UTC-8:00"),
    TimeZoneOption("America/Denver", "Mountain Time (Denver, Salt Lake)", "Denver", "Americas", "UTC-7:00"),
    TimeZoneOption("America/Phoenix", "Mountain Standard (Phoenix - No DST)", "Phoenix", "Americas", "UTC-7:00"),
    TimeZoneOption("America/Chicago", "Central Time (Chicago, Dallas, Austin)", "Chicago", "Americas", "UTC-6:00"),
    TimeZoneOption("America/New_York", "Eastern Time (New York, Miami)", "New York", "Americas", "UTC-5:00"),
    TimeZoneOption("America/Toronto", "Eastern Time (Toronto, Montreal)", "Toronto", "Americas", "UTC-5:00"),
    TimeZoneOption("America/Sao_Paulo", "Brasília Time (São Paulo, Rio)", "São Paulo", "Americas", "UTC-3:00"),
    TimeZoneOption("America/Argentina/Buenos_Aires", "Argentina Time (Buenos Aires)", "Buenos Aires", "Americas", "UTC-3:00"),
    TimeZoneOption("UTC", "Universal Coordinated Time (UTC / GMT)", "UTC", "UTC", "UTC+0:00"),
    TimeZoneOption("Europe/London", "Greenwich / British Time (London)", "London", "Europe", "UTC+0:00"),
    TimeZoneOption("Europe/Paris", "Central European Time (Paris, Berlin, Rome)", "Paris", "Europe", "UTC+1:00"),
    TimeZoneOption("Europe/Berlin", "Central European Time (Berlin, Madrid)", "Berlin", "Europe", "UTC+1:00"),
    TimeZoneOption("Europe/Amsterdam", "Central European Time (Amsterdam)", "Amsterdam", "Europe", "UTC+1:00"),
    TimeZoneOption("Europe/Athens", "Eastern European Time (Athens, Helsinki)", "Athens", "Europe", "UTC+2:00"),
    TimeZoneOption("Africa/Cairo", "Eastern European Time (Cairo)", "Cairo", "Africa", "UTC+2:00"),
    TimeZoneOption("Europe/Moscow", "Moscow Standard Time (Moscow)", "Moscow", "Europe", "UTC+3:00"),
    TimeZoneOption("Asia/Dubai", "Gulf Standard Time (Dubai)", "Dubai", "Middle East", "UTC+4:00"),
    TimeZoneOption("Asia/Karachi", "Pakistan Standard Time (Karachi)", "Karachi", "Asia", "UTC+5:00"),
    TimeZoneOption("Asia/Kolkata", "India Standard Time (New Delhi)", "New Delhi", "Asia", "UTC+5:30"),
    TimeZoneOption("Asia/Bangkok", "Indochina Time (Bangkok, Hanoi, Jakarta)", "Bangkok", "Asia", "UTC+7:00"),
    TimeZoneOption("Asia/Shanghai", "China Standard Time (Beijing, Shanghai)", "Beijing", "Asia", "UTC+8:00"),
    TimeZoneOption("Asia/Hong_Kong", "Hong Kong Standard Time", "Hong Kong", "Asia", "UTC+8:00"),
    TimeZoneOption("Asia/Taipei", "Taipei Standard Time", "Taipei", "Asia", "UTC+8:00"),
    TimeZoneOption("Asia/Singapore", "Singapore Standard Time", "Singapore", "Asia", "UTC+8:00"),
    TimeZoneOption("Asia/Tokyo", "Japan Standard Time (Tokyo, Osaka)", "Tokyo", "Asia", "UTC+9:00"),
    TimeZoneOption("Asia/Seoul", "Korea Standard Time (Seoul)", "Seoul", "Asia", "UTC+9:00"),
    TimeZoneOption("Australia/Perth", "Australian Western Time (Perth)", "Perth", "Oceania", "UTC+8:00"),
    TimeZoneOption("Australia/Sydney", "Australian Eastern Time (Sydney)", "Sydney", "Oceania", "UTC+10:00"),
    TimeZoneOption("Pacific/Auckland", "New Zealand Standard (Auckland)", "Auckland", "Oceania", "UTC+12:00")
)

data class ProvisionableApp(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val packageName: String,
    val icon: ImageVector,
    val accentColor: Color,
    val isDefaultSelected: Boolean = false
)

val PlayStoreApp = ProvisionableApp(
    id = "playstore",
    name = "Google Play Store",
    category = "Core System",
    description = "App storefront — required for cloud updates & non-bundled apps",
    packageName = "com.android.vending",
    icon = Icons.Default.Shop,
    accentColor = MikuOnboardingGreen,
    isDefaultSelected = true
)

val GoogleCoreApps = listOf(
    PlayStoreApp,
    ProvisionableApp(
        id = "gms",
        name = "Google Play Services (GMS)",
        category = "Core System",
        description = "Unified Google Play services & framework backend",
        packageName = "com.google.android.gms",
        icon = Icons.Default.CloudSync,
        accentColor = MikuOnboardingTeal,
        isDefaultSelected = true
    ),
    ProvisionableApp(
        id = "chrome",
        name = "Google Chrome",
        category = "Browser",
        description = "High-speed modern web browser with cloud sync",
        packageName = "com.android.chrome",
        icon = Icons.Default.Language,
        accentColor = MikuOnboardingGold,
        isDefaultSelected = true
    ),
    ProvisionableApp(
        id = "photos",
        name = "Google Photos",
        category = "Media",
        description = "High-resolution gallery with cloud backup & editing",
        packageName = "com.google.android.apps.photos",
        icon = Icons.Default.PhotoLibrary,
        accentColor = MikuOnboardingPink,
        isDefaultSelected = true
    ),
    ProvisionableApp(
        id = "camera",
        name = "Pixel Camera (GCam)",
        category = "Imaging",
        description = "Advanced computational photography & HDR+ imaging",
        packageName = "com.google.android.GoogleCamera",
        icon = Icons.Default.CameraAlt,
        accentColor = MikuOnboardingGreen,
        isDefaultSelected = true
    ),
    ProvisionableApp(
        id = "maps",
        name = "Google Maps",
        category = "Navigation",
        description = "Turn-by-turn navigation & global satellite mapping",
        packageName = "com.google.android.apps.maps",
        icon = Icons.Default.Map,
        accentColor = MikuOnboardingTeal,
        isDefaultSelected = true
    )
)

val OptionalApps = listOf(
    ProvisionableApp(
        id = "spotify",
        name = "Spotify",
        category = "Music Streaming",
        description = "High-bitrate global music catalog and playlists",
        packageName = "com.spotify.music",
        icon = Icons.Default.Headphones,
        accentColor = Color(0xFF1DB954),
        isDefaultSelected = true
    ),
    ProvisionableApp(
        id = "tidal",
        name = "TIDAL Hi-Fi",
        category = "Master Audio",
        description = "Bit-perfect FLAC streaming tuned for CS43198 DAC",
        packageName = "com.aspiro.tidal",
        icon = Icons.Default.Equalizer,
        accentColor = Color(0xFF00FFFF),
        isDefaultSelected = true
    ),
    ProvisionableApp(
        id = "applemusic",
        name = "Apple Music Lossless",
        category = "Hi-Res Lossless",
        description = "24-bit/192kHz ALAC Hi-Res Lossless streaming",
        packageName = "com.apple.android.music",
        icon = Icons.Default.MusicNote,
        accentColor = Color(0xFFFA243C),
        isDefaultSelected = false
    ),
    ProvisionableApp(
        id = "telegram",
        name = "Telegram Messenger",
        category = "Messaging",
        description = "Fast, secure cloud messaging & audio sharing",
        packageName = "org.telegram.messenger",
        icon = Icons.AutoMirrored.Filled.Send,
        accentColor = Color(0xFF2AABEE),
        isDefaultSelected = true
    ),
    ProvisionableApp(
        id = "signal",
        name = "Signal Private Messenger",
        category = "Encrypted Comms",
        description = "End-to-end encrypted messaging & voice calls",
        packageName = "org.thoughtcrime.securesms",
        icon = Icons.Default.Security,
        accentColor = Color(0xFF3A76F0),
        isDefaultSelected = true
    ),
    ProvisionableApp(
        id = "magisk",
        name = "Magisk Root & SU",
        category = "System & Modding",
        description = "Systemless Root Management & Kernel Module Framework",
        packageName = "com.topjohnwu.magisk",
        icon = Icons.Default.AdminPanelSettings,
        accentColor = MikuOnboardingPink,
        isDefaultSelected = false
    )
)

val BundledApps = listOf(
    ProvisionableApp(
        id = "Gallery2",
        name = "AOSP Gallery",
        category = "Media",
        description = "Lightweight offline photo gallery viewer",
        packageName = "com.android.gallery3d",
        icon = Icons.Default.PhotoLibrary,
        accentColor = MikuOnboardingGreen,
        isDefaultSelected = true
    ),
    ProvisionableApp(
        id = "HiByMusic",
        name = "HiBy Music (Legacy)",
        category = "Hi-Res Audio",
        description = "Stock HiBy lossless player with MSEB tuning",
        packageName = "com.hiby.music",
        icon = Icons.Default.Headphones,
        accentColor = MikuOnboardingGold,
        isDefaultSelected = false
    ),
    ProvisionableApp(
        id = "SnapdragonCamera",
        name = "Snapdragon Camera",
        category = "Imaging",
        description = "Qualcomm native camera with RAW capture",
        packageName = "org.codeaurora.snapcam",
        icon = Icons.Default.CameraAlt,
        accentColor = MikuOnboardingPink,
        isDefaultSelected = true
    )
)

val AllProvisionableApps: List<ProvisionableApp> = GoogleCoreApps + OptionalApps + BundledApps

/** Detects current system timezone or closest match from available network/location sources. */
fun detectSystemTimeZone(ctx: Context): String {
    val defaultId = TimeZone.getDefault().id
    val match = SupportedTimeZones.firstOrNull { it.id.equals(defaultId, ignoreCase = true) }
    if (match != null) return match.id

    // Check system property
    val prop = RootShell.execOut("getprop persist.sys.timezone")?.trim()
    if (!prop.isNullOrBlank()) {
        val propMatch = SupportedTimeZones.firstOrNull { it.id.equals(prop, ignoreCase = true) }
        if (propMatch != null) return propMatch.id
    }

    return "America/Denver" // Sensible fallback
}

/** Applies time zone and 24-hour time formatting across MikuOS and Android framework. */
fun applyDateTimeSettings(ctx: Context, timeZone: String, is24Hour: Boolean) {
    try {
        val timeFormatString = if (is24Hour) "24" else "12"
        Settings.System.putString(ctx.contentResolver, Settings.System.TIME_12_24, timeFormatString)
    } catch (_: Throwable) {}

    if (RootShell.isAvailable()) {
        val timeFormatString = if (is24Hour) "24" else "12"
        RootShell.exec("settings put system time_12_24 $timeFormatString")
        RootShell.exec("setprop persist.sys.timezone \"$timeZone\"")
        RootShell.exec("service call alarm 3 s16 \"$timeZone\"")
        RootShell.exec("cmd time_detector set_manual_time_zone \"$timeZone\"")
    }

    try {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        am?.setTimeZone(timeZone)
    } catch (_: Throwable) {}
}

/** Applies high-sensitivity Screen Protector Mode for tempered glass screen protectors. */
fun applyScreenProtectorMode(ctx: Context, enabled: Boolean) {
    val cr = ctx.contentResolver
    val v = if (enabled) 1 else 0
    try {
        Settings.Secure.putInt(cr, "touch_sensitivity_enabled", v)
        Settings.System.putInt(cr, "touch_sensitivity_enabled", v)
        Settings.System.putInt(cr, "screen_protector_mode", v)
    } catch (_: Throwable) {}
    if (RootShell.isAvailable()) {
        RootShell.exec(
            "settings put secure touch_sensitivity_enabled $v; " +
            "settings put system touch_sensitivity_enabled $v; " +
            "settings put system screen_protector_mode $v; " +
            "setprop persist.sys.screen_protector $v; " +
            "setprop persist.sys.touch_sensitivity $v"
        )
    }
}

@Composable
fun MikuOnboardingWizardModal(
    prefs: SharedPreferences,
    onFinish: () -> Unit
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    
    // Auto-grant location permissions silently in background
    LaunchedEffect(Unit) {
        if (RootShell.isAvailable()) {
            RootShell.exec("pm grant com.miku.launcher android.permission.ACCESS_FINE_LOCATION")
            RootShell.exec("pm grant com.miku.launcher android.permission.ACCESS_COARSE_LOCATION")
        }
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        onDispose {
            activity?.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    var currentStep by remember { mutableIntStateOf(0) }
    var selectedLanguage by remember { mutableStateOf("en") }
    
    val autoDetectedZone = remember { detectSystemTimeZone(ctx) }
    var selectedTimeZone by remember { mutableStateOf(autoDetectedZone) }
    var is24Hour by remember { mutableStateOf(true) } // Default to 24h as requested
    var isScreenProtectorMode by remember { mutableStateOf(false) } // Default OFF for OS, asked during onboarding
    
    val initialSelected = remember { AllProvisionableApps.filter { it.isDefaultSelected }.map { it.id }.toSet() }
    var selectedApps by remember { mutableStateOf(initialSelected) }

    val apkIndex by produceState<BundledApkIndex?>(initialValue = null) {
        val pm = ctx.packageManager
        value = withContext(Dispatchers.IO) {
            BundledApkIndex.scan(pm)
        }
    }

    val totalSteps = 5

    BackHandler(enabled = currentStep > 0 && currentStep != 3) {
        currentStep--
    }

    Scaffold(
        containerColor = MikuOnboardingBg,
        bottomBar = {
            if (currentStep != 3) { // Hide navigation buttons on Ninite automatic install page
                Surface(
                    color = MikuOnboardingCard,
                    border = BorderStroke(1.dp, MikuOnboardingBorder.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentStep > 0 && currentStep != 4) {
                            OutlinedButton(
                                onClick = { currentStep-- },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.4f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("BACK", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }

                        // Step Indicator Pills
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(totalSteps) { idx ->
                                Box(
                                    Modifier
                                        .size(if (idx == currentStep) 18.dp else 8.dp, 8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (idx == currentStep) MikuOnboardingTeal
                                            else if (idx < currentStep) MikuOnboardingTeal.copy(alpha = 0.4f)
                                            else Color.White.copy(alpha = 0.2f)
                                        )
                                )
                            }
                        }

                        if (currentStep < 4) {
                            Button(
                                onClick = { currentStep++ },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MikuOnboardingTeal,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("NEXT", fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", modifier = Modifier.size(18.dp))
                            }
                        } else {
                            Button(
                                onClick = {
                                    applyDateTimeSettings(ctx, selectedTimeZone, is24Hour)
                                    applyScreenProtectorMode(ctx, isScreenProtectorMode)
                                    prefs.edit()
                                        .putString("language", selectedLanguage)
                                        .putString("timezone", selectedTimeZone)
                                        .putBoolean("is_24_hour", is24Hour)
                                        .putBoolean("screen_protector_mode", isScreenProtectorMode)
                                        .putBoolean("onboarding_completed", true)
                                        .apply()
                                    onFinish()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MikuOnboardingTeal,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("FINISH", fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Check, contentDescription = "Finish", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            when (currentStep) {
                0 -> LanguageSelectionScreen(
                    selectedCode = selectedLanguage,
                    onSelect = { selectedLanguage = it }
                )
                1 -> DateTimeSelectionScreen(
                    selectedTimeZone = selectedTimeZone,
                    onSelectTimeZone = { selectedTimeZone = it },
                    is24Hour = is24Hour,
                    on24HourChange = { is24Hour = it },
                    isScreenProtectorMode = isScreenProtectorMode,
                    onScreenProtectorModeChange = { isScreenProtectorMode = it },
                    autoDetectedZone = autoDetectedZone,
                    onAutoDetectClick = { selectedTimeZone = autoDetectedZone }
                )
                2 -> AppsProvisioningScreen(
                    selectedApps = selectedApps,
                    onSelectedChange = { selectedApps = it }
                )
                3 -> AppInstallScreen(
                    selectedApps = selectedApps,
                    apkIndex = apkIndex,
                    onAutoNext = { currentStep++ }
                )
                4 -> CompletionScreen(
                    lang = selectedLanguage,
                    timeZone = selectedTimeZone,
                    is24Hour = is24Hour,
                    isScreenProtectorMode = isScreenProtectorMode,
                    appsCount = selectedApps.size
                )
            }
        }
    }
}

// ==========================================
// 1. DEDICATED LANGUAGE SELECTION SCREEN
// ==========================================
@Composable
fun LanguageSelectionScreen(
    selectedCode: String,
    onSelect: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }

    val filteredLanguages = remember(searchQuery, selectedFilter) {
        SystemLanguages.filter { lang ->
            val matchesSearch = searchQuery.isBlank() ||
                lang.name.contains(searchQuery, ignoreCase = true) ||
                lang.nativeName.contains(searchQuery, ignoreCase = true) ||
                lang.code.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "MikuOS Ready" -> lang.isMikuUiComplete
                "Americas" -> lang.region.contains("Americas", ignoreCase = true)
                "Europe" -> lang.region.contains("Europe", ignoreCase = true)
                "Asia" -> lang.region.contains("Asia", ignoreCase = true)
                "Middle East" -> lang.region.contains("Middle East", ignoreCase = true)
                else -> true
            }

            matchesSearch && matchesFilter
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "SELECT LANGUAGE",
            color = MikuOnboardingTeal,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Text(
            "Choose your system display and input language for MikuOS.",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp
        )

        Spacer(Modifier.height(12.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search languages...", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = MikuOnboardingTeal, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikuOnboardingTeal,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                cursorColor = MikuOnboardingTeal,
                focusedContainerColor = MikuOnboardingCard,
                unfocusedContainerColor = MikuOnboardingCard,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        )

        Spacer(Modifier.height(10.dp))

        // Region Filter Chips
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "MikuOS Ready", "Americas", "Europe", "Asia", "Middle East").forEach { filter ->
                val isSelected = selectedFilter == filter
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MikuOnboardingTeal.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (isSelected) MikuOnboardingTeal else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { selectedFilter = filter }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        filter,
                        color = if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filteredLanguages) { lang ->
                val isSelected = lang.code == selectedCode
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MikuOnboardingCardSelected else MikuOnboardingCard)
                        .border(
                            1.2.dp,
                            if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(lang.code) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(lang.flag, fontSize = 24.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                lang.nativeName,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            if (lang.isMikuUiComplete) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MikuOnboardingTeal.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("CYBER UI", color = MikuOnboardingTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text(
                            lang.name,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp
                        )
                    }

                    if (isSelected) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MikuOnboardingTeal),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 2. DEDICATED DATE, TIME & REGIONAL SCREEN
// ==========================================
@Composable
fun DateTimeSelectionScreen(
    selectedTimeZone: String,
    onSelectTimeZone: (String) -> Unit,
    is24Hour: Boolean,
    on24HourChange: (Boolean) -> Unit,
    isScreenProtectorMode: Boolean,
    onScreenProtectorModeChange: (Boolean) -> Unit,
    autoDetectedZone: String,
    onAutoDetectClick: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedRegion by remember { mutableStateOf("All") }

    val filteredTimeZones = remember(searchQuery, selectedRegion) {
        SupportedTimeZones.filter { tz ->
            val matchesSearch = searchQuery.isBlank() ||
                tz.name.contains(searchQuery, ignoreCase = true) ||
                tz.city.contains(searchQuery, ignoreCase = true) ||
                tz.id.contains(searchQuery, ignoreCase = true)

            val matchesRegion = when (selectedRegion) {
                "Americas" -> tz.region.contains("Americas", ignoreCase = true)
                "Europe" -> tz.region.contains("Europe", ignoreCase = true)
                "Asia" -> tz.region.contains("Asia", ignoreCase = true)
                "Middle East" -> tz.region.contains("Middle East", ignoreCase = true)
                "Oceania" -> tz.region.contains("Oceania", ignoreCase = true)
                else -> true
            }

            matchesSearch && matchesRegion
        }
    }

    val liveTime = remember(is24Hour, selectedTimeZone) {
        val tz = TimeZone.getTimeZone(selectedTimeZone)
        val pattern = if (is24Hour) "HH:mm:ss · EEE, MMM d" else "h:mm:ss a · EEE, MMM d"
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        sdf.timeZone = tz
        sdf.format(Date())
    }

    val selectedOption = SupportedTimeZones.firstOrNull { it.id == selectedTimeZone }

    Column(Modifier.fillMaxSize()) {
        Text(
            "REGIONAL DATE & HARDWARE",
            color = MikuOnboardingTeal,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Text(
            "Configure 24-hour military clock & screen protector touch boost.",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp
        )

        Spacer(Modifier.height(10.dp))

        // Digital Clock Preview Banner
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MikuOnboardingCard)
                .border(1.dp, MikuOnboardingBorder.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AccessTime, contentDescription = "Clock", tint = MikuOnboardingTeal, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE CLOCK PREVIEW", color = MikuOnboardingTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikuOnboardingTeal.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(if (is24Hour) "24H FORMAT" else "12H FORMAT", color = MikuOnboardingTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(liveTime, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
            }
        }

        Spacer(Modifier.height(8.dp))

        // 24-Hour Time Format Selector (Default: ON)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 24-Hour Button
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (is24Hour) MikuOnboardingTeal else Color.Transparent)
                    .clickable { on24HourChange(true) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (is24Hour) {
                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "24-HOUR (18:50)",
                        color = if (is24Hour) Color.Black else Color.White.copy(alpha = 0.7f),
                        fontSize = 11.5.sp,
                        fontWeight = if (is24Hour) FontWeight.Black else FontWeight.Normal
                    )
                }
            }

            // 12-Hour Button
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (!is24Hour) MikuOnboardingTeal else Color.Transparent)
                    .clickable { on24HourChange(false) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!is24Hour) {
                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "12-HOUR (6:50 PM)",
                        color = if (!is24Hour) Color.Black else Color.White.copy(alpha = 0.7f),
                        fontSize = 11.5.sp,
                        fontWeight = if (!is24Hour) FontWeight.Black else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Glass Screen Protector Mode Card
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isScreenProtectorMode) MikuOnboardingCardSelected else MikuOnboardingCard)
                .border(
                    1.2.dp,
                    if (isScreenProtectorMode) MikuOnboardingTeal else MikuOnboardingBorder.copy(alpha = 0.35f),
                    RoundedCornerShape(12.dp)
                )
                .clickable { onScreenProtectorModeChange(!isScreenProtectorMode) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isScreenProtectorMode) MikuOnboardingTeal.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.TouchApp,
                    contentDescription = "Screen Protector",
                    tint = if (isScreenProtectorMode) MikuOnboardingTeal else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SCREEN PROTECTOR MODE",
                        color = if (isScreenProtectorMode) MikuOnboardingTeal else Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isScreenProtectorMode) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MikuOnboardingTeal.copy(alpha = 0.2f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("TOUCH BOOST", color = MikuOnboardingTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(1.dp))
                Text(
                    "Increase touch sensitivity for tempered glass protectors",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = isScreenProtectorMode,
                onCheckedChange = { onScreenProtectorModeChange(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = MikuOnboardingTeal,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                )
            )
        }

        Spacer(Modifier.height(10.dp))

        // Location Auto-Detect Card
        val isAutoActive = selectedTimeZone == autoDetectedZone
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isAutoActive) MikuOnboardingTeal.copy(alpha = 0.15f) else MikuOnboardingCard)
                .border(
                    1.2.dp,
                    if (isAutoActive) MikuOnboardingTeal else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(12.dp)
                )
                .clickable { onAutoDetectClick() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isAutoActive) MikuOnboardingTeal else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = "Auto GPS",
                    tint = if (isAutoActive) Color.Black else MikuOnboardingTeal,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LOCATION AUTO-DETECT", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikuOnboardingGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text("PRE-AUTH", color = MikuOnboardingGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "Detected: ${SupportedTimeZones.firstOrNull { it.id == autoDetectedZone }?.name ?: autoDetectedZone}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isAutoActive) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MikuOnboardingTeal),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Active", tint = Color.Black, modifier = Modifier.size(13.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search cities or timezones...", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = MikuOnboardingTeal, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikuOnboardingTeal,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                cursorColor = MikuOnboardingTeal,
                focusedContainerColor = MikuOnboardingCard,
                unfocusedContainerColor = MikuOnboardingCard,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )

        Spacer(Modifier.height(8.dp))

        // Region Filter Chips
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Americas", "Europe", "Asia", "Middle East", "Oceania").forEach { region ->
                val isSelected = selectedRegion == region
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MikuOnboardingTeal.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (isSelected) MikuOnboardingTeal else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { selectedRegion = region }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        region,
                        color = if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.7f),
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filteredTimeZones) { tz ->
                val isSelected = tz.id == selectedTimeZone
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MikuOnboardingCardSelected else MikuOnboardingCard)
                        .border(
                            1.2.dp,
                            if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectTimeZone(tz.id) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tz.city,
                                color = Color.White,
                                fontSize = 14.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(tz.offset, color = MikuOnboardingTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            tz.name,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isSelected) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MikuOnboardingTeal),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 3. APPS SELECTION SCREEN
// ==========================================
@Composable
fun AppsProvisioningScreen(
    selectedApps: Set<String>,
    onSelectedChange: (Set<String>) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "CHOOSE APPS",
                    color = MikuOnboardingTeal,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    "Select which apps to provision during setup.",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { onSelectedChange(AllProvisionableApps.map { it.id }.toSet()) }
                ) {
                    Text("ALL", color = MikuOnboardingTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = { onSelectedChange(emptySet()) }
                ) {
                    Text("NONE", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            val categories = listOf(
                "Core System" to GoogleCoreApps,
                "Media & Streaming" to OptionalApps,
                "Bundled System Utilities" to BundledApps
            )

            categories.forEach { (catName, appList) ->
                item {
                    Text(
                        catName.uppercase(),
                        color = MikuOnboardingTeal.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }

                items(appList) { app ->
                    val isSelected = selectedApps.contains(app.id)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) app.accentColor.copy(alpha = 0.12f) else MikuOnboardingCard)
                            .border(
                                1.2.dp,
                                if (isSelected) app.accentColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                val next = selectedApps.toMutableSet()
                                if (isSelected) next.remove(app.id) else next.add(app.id)
                                onSelectedChange(next)
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(app.accentColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(app.icon, contentDescription = app.name, tint = app.accentColor, modifier = Modifier.size(20.dp))
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(Modifier.weight(1f)) {
                            Text(app.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(app.description, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                val next = selectedApps.toMutableSet()
                                if (checked) next.add(app.id) else next.remove(app.id)
                                onSelectedChange(next)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = app.accentColor,
                                uncheckedColor = Color.White.copy(alpha = 0.3f),
                                checkmarkColor = Color.Black
                            )
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. NINITE AUTOMATIC INSTALLER SCREEN
// ==========================================
@Composable
fun AppInstallScreen(
    selectedApps: Set<String>,
    apkIndex: BundledApkIndex?,
    onAutoNext: () -> Unit
) {
    val ctx = LocalContext.current
    val installStates = remember { mutableStateMapOf<String, InstallPhase>() }
    val displayList = remember { selectedApps.toList() }

    LaunchedEffect(apkIndex) {
        if (apkIndex == null) return@LaunchedEffect

        displayList.forEach { appId ->
            val app = AllProvisionableApps.firstOrNull { it.id == appId } ?: return@forEach
            val apk = apkIndex.apkFor(app)
            if (apk == null) {
                installStates[appId] = InstallPhase.Skipped
            } else {
                installStates[appId] = InstallPhase.Installing
                val success = performAppInstall(ctx, apk, appId)
                installStates[appId] = if (success) InstallPhase.Installed else InstallPhase.Failed
            }
        }

        delay(1200)
        onAutoNext()
    }

    val total = displayList.size
    val finished = installStates.count { it.value == InstallPhase.Installed || it.value == InstallPhase.Failed || it.value == InstallPhase.Skipped }
    val progress = if (total > 0) finished.toFloat() / total.toFloat() else 0f

    Column(Modifier.fillMaxSize()) {
        Text("INSTALLING APPS", color = MikuOnboardingTeal, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text("Zero-touch unattended application provisioning.", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)

        Spacer(Modifier.height(14.dp))

        // Global Progress Bar
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MikuOnboardingCard)
                .border(1.dp, MikuOnboardingBorder.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Overall Progress", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("$finished / $total", color = MikuOnboardingTeal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                color = MikuOnboardingTeal,
                trackColor = Color.White.copy(alpha = 0.1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }

        Spacer(Modifier.height(14.dp))

        // Tabular Ninite Rows
        Surface(
            color = MikuOnboardingCard,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("APPLICATION", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("STATUS", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                itemsIndexed(displayList) { index, appId ->
                    val app = AllProvisionableApps.firstOrNull { it.id == appId }
                    val phase = installStates[appId] ?: InstallPhase.NotStarted
                    val isEven = index % 2 == 0
                    val rowBg = if (isEven) Color.Transparent else Color.White.copy(alpha = 0.02f)

                    val (statusText, statusColor) = when (phase) {
                        InstallPhase.NotStarted -> "Queued" to Color.White.copy(alpha = 0.4f)
                        InstallPhase.Installing -> "Installing…" to MikuOnboardingTeal
                        InstallPhase.Installed -> "OK" to MikuOnboardingGreen
                        InstallPhase.Failed -> "Failed" to MikuOnboardingPink
                        InstallPhase.Skipped -> "Skipped" to Color.White.copy(alpha = 0.4f)
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(app?.name ?: appId, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                statusText,
                                color = statusColor,
                                fontSize = 13.sp,
                                fontWeight = if (phase == InstallPhase.Installed || phase == InstallPhase.Installing) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (phase == InstallPhase.Installing) {
                                Spacer(Modifier.width(8.dp))
                                LinearProgressIndicator(
                                    color = MikuOnboardingTeal,
                                    trackColor = Color.White.copy(alpha = 0.1f),
                                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                    if (index < displayList.size - 1) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                    }
                }
            }
        }
    }
}

// ==========================================
// 5. COMPLETION & SUMMARY SCREEN
// ==========================================
@Composable
fun CompletionScreen(
    lang: String,
    timeZone: String,
    is24Hour: Boolean,
    isScreenProtectorMode: Boolean,
    appsCount: Int
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(MikuOnboardingTeal, MikuOnboardingPink, MikuOnboardingGold, MikuOnboardingTeal)
                    )
                )
                .padding(3.dp)
                .clip(CircleShape)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = "Ready", tint = MikuOnboardingTeal, modifier = Modifier.size(40.dp))
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "YOU'RE READY FOR MIKUOS",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "Setup complete. Your M500 is tuned and ready to play.",
            color = MikuOnboardingTeal,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        // Summary Card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MikuOnboardingCard)
                .border(1.dp, MikuOnboardingBorder.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Text("CONFIGURATION SUMMARY", color = MikuOnboardingTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(10.dp))

            SummaryItem(Icons.Default.Language, "Language", SystemLanguages.firstOrNull { it.code == lang }?.nativeName ?: "English")
            SummaryItem(Icons.Default.AccessTime, "Time Zone", SupportedTimeZones.firstOrNull { it.id == timeZone }?.name ?: timeZone)
            SummaryItem(Icons.Default.Schedule, "Time Format", if (is24Hour) "24-Hour (Military)" else "12-Hour (AM/PM)")
            SummaryItem(Icons.Default.TouchApp, "Screen Protector", if (isScreenProtectorMode) "High Sensitivity Enabled" else "Standard")
            SummaryItem(Icons.Default.Apps, "Installed Apps", "$appsCount Apps Selected")
            SummaryItem(Icons.Default.Keyboard, "Keyboard (IME)", "AOSP LatinIME (Cyber Themed)")
            SummaryItem(Icons.Default.GraphicEq, "Audio Engine", "Dual CS43198 Direct ALSA")
        }
    }
}

@Composable
fun SummaryItem(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = MikuOnboardingTeal, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text("$label: ", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
