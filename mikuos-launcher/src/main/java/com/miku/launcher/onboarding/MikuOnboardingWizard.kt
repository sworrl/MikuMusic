package com.miku.launcher.onboarding

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.miku.launcher.R
import com.miku.launcher.RootShell
import java.text.SimpleDateFormat
import java.util.*
import java.io.File
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import androidx.compose.runtime.snapshots.SnapshotStateMap

// Cyberpunk Palette
val MikuOnboardingTeal = Color(0xFF00E5FF)
val MikuOnboardingPink = Color(0xFFFF4081)
val MikuOnboardingGreen = Color(0xFF00E676)
val MikuOnboardingGold = Color(0xFFFFD600)
val MikuOnboardingPurple = Color(0xFFB388FF)
val MikuOnboardingBg = Color(0xFF040D14)
val MikuOnboardingCard = Color(0xDD0A1926)
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
    // Fully Implemented MikuOS Native Custom UI
    SupportedLanguage("en", "English", "English (United States)", "🇺🇸", isMikuUiComplete = true, region = "Americas / Global"),
    SupportedLanguage("ja", "Japanese", "日本語 (Japanese)", "🇯🇵", isMikuUiComplete = true, region = "Asia"),
    SupportedLanguage("zh_CN", "Chinese (Simplified)", "简体中文", "🇨🇳", isMikuUiComplete = true, region = "Asia"),
    SupportedLanguage("zh_TW", "Chinese (Traditional)", "繁體中文", "🇹🇼", isMikuUiComplete = true, region = "Asia"),

    // Global Android System Locales (Graceful Fallback to English for Custom Assets)
    SupportedLanguage("ko", "Korean", "한국어", "🇰🇷", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("es", "Spanish", "Español", "🇪🇸", isMikuUiComplete = false, region = "Europe / Americas"),
    SupportedLanguage("fr", "French", "Français", "🇫🇷", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("de", "German", "Deutsch", "🇩🇪", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("it", "Italian", "Italiano", "🇮🇹", isMikuUiComplete = false, region = "Europe"),
    SupportedLanguage("pt", "Portuguese", "Português", "🇵🇹", isMikuUiComplete = false, region = "Europe / Americas"),
    SupportedLanguage("ru", "Russian", "Русский", "🇷🇺", isMikuUiComplete = false, region = "Europe / Asia"),
    SupportedLanguage("ar", "Arabic", "العربية", "🇸🇦", isMikuUiComplete = false, region = "Middle East"),
    SupportedLanguage("hi", "Hindi", "हिन्दी", "🇮🇳", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("th", "Thai", "ไทย", "🇹🇭", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("vi", "Vietnamese", "Tiếng Việt", "🇻🇳", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("id", "Indonesian", "Bahasa Indonesia", "🇮🇩", isMikuUiComplete = false, region = "Asia"),
    SupportedLanguage("tr", "Turkish", "Türkçe", "🇹🇷", isMikuUiComplete = false, region = "Europe / Asia"),
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
    // Americas
    TimeZoneOption("Pacific/Honolulu", "Hawaii-Aleutian Time (Honolulu)", "Honolulu", "Americas", "UTC-10:00"),
    TimeZoneOption("America/Anchorage", "Alaska Time (Anchorage)", "Anchorage", "Americas", "UTC-9:00"),
    TimeZoneOption("America/Los_Angeles", "Pacific Time (Los Angeles, Seattle)", "Los Angeles", "Americas", "UTC-8:00"),
    TimeZoneOption("America/Denver", "Mountain Time (Denver, Salt Lake)", "Denver", "Americas", "UTC-7:00"),
    TimeZoneOption("America/Phoenix", "Mountain Standard Time (Phoenix - No DST)", "Phoenix", "Americas", "UTC-7:00"),
    TimeZoneOption("America/Chicago", "Central Time (Chicago, Dallas, Austin)", "Chicago", "Americas", "UTC-6:00"),
    TimeZoneOption("America/New_York", "Eastern Time (New York, Boston, Miami)", "New York", "Americas", "UTC-5:00"),
    TimeZoneOption("America/Toronto", "Eastern Time (Toronto, Montreal)", "Toronto", "Americas", "UTC-5:00"),
    TimeZoneOption("America/Halifax", "Atlantic Time (Halifax)", "Halifax", "Americas", "UTC-4:00"),
    TimeZoneOption("America/Sao_Paulo", "Brasília Time (São Paulo, Rio)", "São Paulo", "Americas", "UTC-3:00"),
    TimeZoneOption("America/Argentina/Buenos_Aires", "Argentina Time (Buenos Aires)", "Buenos Aires", "Americas", "UTC-3:00"),

    // Europe & Africa
    TimeZoneOption("UTC", "Universal Coordinated Time (UTC / GMT)", "UTC", "UTC", "UTC+0:00"),
    TimeZoneOption("Europe/London", "Greenwich / British Time (London, Dublin)", "London", "Europe", "UTC+0:00"),
    TimeZoneOption("Europe/Paris", "Central European Time (Paris, Berlin, Rome)", "Paris", "Europe", "UTC+1:00"),
    TimeZoneOption("Europe/Berlin", "Central European Time (Berlin, Vienna, Madrid)", "Berlin", "Europe", "UTC+1:00"),
    TimeZoneOption("Europe/Amsterdam", "Central European Time (Amsterdam, Brussels)", "Amsterdam", "Europe", "UTC+1:00"),
    TimeZoneOption("Europe/Athens", "Eastern European Time (Athens, Helsinki)", "Athens", "Europe", "UTC+2:00"),
    TimeZoneOption("Africa/Cairo", "Eastern European Time (Cairo)", "Cairo", "Africa", "UTC+2:00"),
    TimeZoneOption("Africa/Johannesburg", "South Africa Standard Time (Johannesburg)", "Johannesburg", "Africa", "UTC+2:00"),
    TimeZoneOption("Europe/Moscow", "Moscow Standard Time (Moscow, St. Petersburg)", "Moscow", "Europe", "UTC+3:00"),
    TimeZoneOption("Asia/Riyadh", "Arabian Standard Time (Riyadh, Kuwait)", "Riyadh", "Middle East", "UTC+3:00"),

    // Asia & Middle East
    TimeZoneOption("Asia/Dubai", "Gulf Standard Time (Dubai, Abu Dhabi)", "Dubai", "Middle East", "UTC+4:00"),
    TimeZoneOption("Asia/Karachi", "Pakistan Standard Time (Karachi, Islamabad)", "Karachi", "Asia", "UTC+5:00"),
    TimeZoneOption("Asia/Kolkata", "India Standard Time (New Delhi, Mumbai)", "New Delhi", "Asia", "UTC+5:30"),
    TimeZoneOption("Asia/Kathmandu", "Nepal Time (Kathmandu)", "Kathmandu", "Asia", "UTC+5:45"),
    TimeZoneOption("Asia/Dhaka", "Bangladesh Standard Time (Dhaka)", "Dhaka", "Asia", "UTC+6:00"),
    TimeZoneOption("Asia/Bangkok", "Indochina Time (Bangkok, Hanoi, Jakarta)", "Bangkok", "Asia", "UTC+7:00"),
    TimeZoneOption("Asia/Shanghai", "China Standard Time (Beijing, Shanghai)", "Beijing", "Asia", "UTC+8:00"),
    TimeZoneOption("Asia/Hong_Kong", "Hong Kong Standard Time", "Hong Kong", "Asia", "UTC+8:00"),
    TimeZoneOption("Asia/Taipei", "Taipei Standard Time", "Taipei", "Asia", "UTC+8:00"),
    TimeZoneOption("Asia/Singapore", "Singapore Standard Time", "Singapore", "Asia", "UTC+8:00"),
    TimeZoneOption("Asia/Tokyo", "Japan Standard Time (Tokyo, Osaka, Kyoto)", "Tokyo", "Asia", "UTC+9:00"),
    TimeZoneOption("Asia/Seoul", "Korea Standard Time (Seoul)", "Seoul", "Asia", "UTC+9:00"),

    // Oceania & Pacific
    TimeZoneOption("Australia/Perth", "Australian Western Standard Time (Perth)", "Perth", "Oceania", "UTC+8:00"),
    TimeZoneOption("Australia/Adelaide", "Australian Central Standard Time (Adelaide)", "Adelaide", "Oceania", "UTC+9:30"),
    TimeZoneOption("Australia/Sydney", "Australian Eastern Time (Sydney, Melbourne)", "Sydney", "Oceania", "UTC+10:00"),
    TimeZoneOption("Pacific/Auckland", "New Zealand Standard Time (Auckland)", "Auckland", "Oceania", "UTC+12:00")
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

// Google Play Store — a BUNDLED app installed from the ROM's optional-APK dir.
// It is a hard prerequisite for every "pulled" (market://) app, so the install
// step force-installs it first when any pulled app is selected.
val PlayStoreApp = ProvisionableApp(
    id = "playstore",
    name = "Google Play Store",
    category = "Core System",
    description = "App storefront — required to pull non-bundled apps",
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

// APKs bundled on the ROM image at /system/etc/mikuos-optional-apks/ — installable
// offline during onboarding without network access. The id must match the APK
// filename (minus .apk) that the build script places on the partition.
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

// Flat lookup across every provisionable app — used by Step 4 (Install Apps) to
// resolve selected ids back to their ProvisionableApp definitions.
val AllProvisionableApps: List<ProvisionableApp> = GoogleCoreApps + OptionalApps + BundledApps

/**
 * Hatsune Miku Fast-Boot Onboarding Wizard.
 * Follows exact flow: Language Selection -> Time & Time Zone -> Google & Optional Apps -> Complete.
 * Loads instantly with zero UI thread blocking.
 */
@Composable
fun MikuOnboardingWizardModal(
    prefs: SharedPreferences,
    onFinish: () -> Unit
) {
    val ctx = LocalContext.current
    var currentStep by remember { mutableIntStateOf(0) }
    val totalSteps = 5

    // 1. Language State
    var selectedLanguage by remember {
        mutableStateOf(prefs.getString("selected_language_code", "en") ?: "en")
    }

    // 2. Time & Timezone State
    var selectedTimeZone by remember {
        mutableStateOf(prefs.getString("selected_timezone_id", TimeZone.getDefault().id) ?: "America/Los_Angeles")
    }
    var use24HourFormat by remember {
        // Default to 24-hour time for MikuOS.
        mutableStateOf(prefs.getBoolean("use_24_hour_format", true))
    }
    var autoTimeSync by remember {
        mutableStateOf(prefs.getBoolean("auto_time_sync", true))
    }

    // 3. Apps State.
    // The user wants the GOOGLE suite installed by default (Play/GMS/Chrome/Photos/
    // GCam/Maps), NOT the FOSS bundle. Those Google APKs are shipped on the ROM in
    // /system/etc/mikuos-optional-apks/ so the installer treats them as offline-
    // installable and installs via PackageInstaller — it NEVER opens the Play Store
    // (a missing bundled APK is skipped, not handed off; see startInstall). So
    // default-select the Google core; leave the optional FOSS/streaming set off.
    val selectedApps = remember {
        mutableStateMapOf<String, Boolean>().apply {
            GoogleCoreApps.forEach { put(it.id, true) }
            OptionalApps.forEach { put(it.id, it.isDefaultSelected) }
            BundledApps.forEach { put(it.id, false) }
        }
    }

    // 3b. Live install state per app id, owned here so the NEXT button on the
    // Install step (index 3) can stay disabled until every SELECTED app has
    // reached a terminal state (Installed or explicitly Skipped by the user).
    val installStates = remember { mutableStateMapOf<String, InstallPhase>() }

    BackHandler(enabled = currentStep > 0) {
        currentStep--
    }

    LaunchedEffect(Unit) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val isHeadset = am?.isWiredHeadsetOn == true || am?.isBluetoothA2dpOn == true
            val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            val targetVol = if (isHeadset) (maxVol * 0.20f).toInt().coerceAtLeast(1) else (maxVol * 0.80f).toInt().coerceAtLeast(1)
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            MediaPlayer.create(ctx, R.raw.headset_plugged_in_en)?.apply {
                setOnCompletionListener { release() }
                start()
            }
        } catch (_: Throwable) {}
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MikuOnboardingBg)
    ) {
        // Miku Cyberpunk Stage Artwork Inlay
        Image(
            painter = painterResource(id = R.drawable.miku_cyber_stage),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.18f),
            contentScale = ContentScale.Crop
        )

        // Ambient Cyber Background Glow
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(MikuOnboardingTeal.copy(alpha = 0.15f), Color.Transparent),
                    radius = size.width * 0.9f
                ),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.15f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(MikuOnboardingPink.copy(alpha = 0.12f), Color.Transparent),
                    radius = size.width * 0.7f
                ),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.85f)
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top Header with Step Indicator & Official 01 Badge
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(MikuOnboardingTeal.copy(alpha = 0.3f), MikuOnboardingPink.copy(alpha = 0.3f))))
                            .border(1.2.dp, MikuOnboardingTeal, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("01", color = MikuOnboardingTeal, fontSize = 12.5.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (currentStep) {
                            0 -> "STEP 1: LANGUAGE"
                            1 -> "STEP 2: DATE & TIME"
                            2 -> "STEP 3: APPS & SERVICES"
                            3 -> "STEP 4: INSTALL APPS"
                            else -> "STEP 5: COMPLETE"
                        },
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }

                    // Progress Dots
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until totalSteps) {
                            Box(
                                Modifier
                                    .size(width = if (i == currentStep) 18.dp else 6.dp, height = 6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (i == currentStep) MikuOnboardingTeal
                                        else if (i < currentStep) MikuOnboardingTeal.copy(alpha = 0.4f)
                                        else Color.White.copy(alpha = 0.15f)
                                    )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Step Body with Smooth Horizontal Transitions
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                    slideOutHorizontally { width -> -width } + fadeOut()
                                )
                            } else {
                                (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                    slideOutHorizontally { width -> width } + fadeOut()
                                )
                            }
                        },
                        label = "WizardStep"
                    ) { step ->
                        when (step) {
                            0 -> LanguageSelectionScreen(
                                selectedCode = selectedLanguage,
                                onSelect = { selectedLanguage = it }
                            )
                            1 -> DateTimeSelectionScreen(
                                selectedZone = selectedTimeZone,
                                onZoneSelect = { selectedTimeZone = it },
                                use24H = use24HourFormat,
                                on24HToggle = { use24HourFormat = it },
                                autoSync = autoTimeSync,
                                onAutoSyncToggle = { autoTimeSync = it }
                            )
                            2 -> AppsProvisioningScreen(selectedApps)
                            3 -> AppInstallScreen(
                                selectedApps = selectedApps,
                                installStates = installStates
                            )
                            4 -> CompletionScreen(
                                lang = selectedLanguage,
                                timeZone = selectedTimeZone,
                                appsCount = installStates.count { it.value == InstallPhase.Installed }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // NEXT gate: on the Install step (index 3) the user must confirm every
                // install — NEXT stays disabled until each SELECTED app is terminal
                // (Installed or Skipped). All other steps are always navigable forward.
                val selectedAppIds = AllProvisionableApps.filter { selectedApps[it.id] == true }.map { it.id }
                val installsResolved = selectedAppIds.all {
                    (installStates[it] ?: InstallPhase.NotStarted) in TerminalInstallPhases
                }
                val nextEnabled = currentStep != 3 || installsResolved

                // Navigation Controls
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep > 0) {
                        OutlinedButton(
                            onClick = { currentStep-- },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Back", fontSize = 12.sp)
                        }
                    } else {
                        Spacer(Modifier.width(80.dp))
                    }

                    Button(
                        onClick = {
                            if (currentStep < totalSteps - 1) {
                                currentStep++
                            } else {
                                // Save All Setup Selections
                                val editor = prefs.edit()
                                editor.putBoolean("miku_onboarding_completed", true)
                                editor.putString("selected_language_code", selectedLanguage)
                                editor.putString("selected_timezone_id", selectedTimeZone)
                                editor.putBoolean("use_24_hour_format", use24HourFormat)
                                editor.putBoolean("auto_time_sync", autoTimeSync)
                                selectedApps.forEach { (key, isSelected) ->
                                    editor.putBoolean("app_provision_$key", isSelected)
                                }
                                editor.apply()

                                // Persist 24h/12h to the system provider too (may lack WRITE_SETTINGS).
                                try {
                                    android.provider.Settings.System.putString(
                                        ctx.contentResolver,
                                        android.provider.Settings.System.TIME_12_24,
                                        if (use24HourFormat) "24" else "12"
                                    )
                                } catch (_: Throwable) {}

                                // App installation now happens VISIBLY on Step 4 (Install Apps),
                                // where the user confirms each install before reaching this screen.
                                // Only best-effort root extras remain here (silent no-op without
                                // root); the default IME is set by the miku_ime.rc init step and the
                                // 12/24h format was persisted above via Settings.System.
                                Thread {
                                    try {
                                        RootShell.execFast("setprop persist.sys.timezone $selectedTimeZone")
                                    } catch (_: Throwable) {}
                                }.start()

                                onFinish()
                            }
                        },
                        enabled = nextEnabled,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MikuOnboardingTeal,
                            disabledContainerColor = MikuOnboardingTeal.copy(alpha = 0.30f),
                            disabledContentColor = Color.Black.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(
                            if (currentStep == totalSteps - 1) "START MIKUOS" else "NEXT",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            if (currentStep == totalSteps - 1) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

// ----------------------------------------------------------------
// Step 1: Language Selection (World Locales with Search & Categorization)
// ----------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelectionScreen(
    selectedCode: String,
    onSelect: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") } // All, MikuOS Ready, Americas, Europe, Asia, Middle East

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
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Text(
            "Choose your system display and input language for MikuOS.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.5.sp
        )

        Spacer(Modifier.height(8.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search world languages...", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = MikuOnboardingTeal, modifier = Modifier.size(16.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
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
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("All", "MikuOS Ready", "Americas", "Europe", "Asia", "Middle East").forEach { filter ->
                val isSelected = selectedFilter == filter
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MikuOnboardingTeal.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (isSelected) MikuOnboardingTeal else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { selectedFilter = filter }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        filter,
                        color = if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filteredLanguages) { lang ->
                val isSelected = lang.code == selectedCode
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MikuOnboardingTeal.copy(alpha = 0.15f) else MikuOnboardingCard)
                        .border(
                            1.dp,
                            if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(lang.code) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(lang.flag, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lang.nativeName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            if (lang.isMikuUiComplete) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MikuOnboardingGreen.copy(alpha = 0.15f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text("MIKU UI", color = MikuOnboardingGreen, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text("OS LOCALE", color = Color.White.copy(alpha = 0.5f), fontSize = 7.5.sp, fontWeight = FontWeight.Normal)
                                }
                            }
                        }
                        Text(
                            "${lang.name} (${lang.code}) • ${if (lang.isMikuUiComplete) "Full Native Translation" else "Global OS Locale with EN Fallback"}",
                            color = if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.45f),
                            fontSize = 8.5.sp
                        )
                    }
                    if (isSelected) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MikuOnboardingTeal, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------
// Step 2: Date & Time Configuration (Interactive World Time Zone Dropdown)
// ----------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeSelectionScreen(
    selectedZone: String,
    onZoneSelect: (String) -> Unit,
    use24H: Boolean,
    on24HToggle: (Boolean) -> Unit,
    autoSync: Boolean,
    onAutoSyncToggle: (Boolean) -> Unit
) {
    val liveTime = remember {
        val sdf = SimpleDateFormat(if (use24H) "HH:mm" else "hh:mm a", Locale.getDefault())
        sdf.format(Date())
    }

    var showTzPickerModal by remember { mutableStateOf(false) }
    var tzSearchQuery by remember { mutableStateOf("") }
    var tzRegionFilter by remember { mutableStateOf("All") }

    val currentZoneObj = remember(selectedZone) {
        SupportedTimeZones.firstOrNull { it.id == selectedZone } ?: TimeZoneOption(
            id = selectedZone,
            name = selectedZone.substringAfterLast('/'),
            city = selectedZone.substringAfterLast('/'),
            region = "Custom",
            offset = "UTC"
        )
    }

    val filteredTimeZones = remember(tzSearchQuery, tzRegionFilter) {
        SupportedTimeZones.filter { zone ->
            val matchesSearch = tzSearchQuery.isBlank() ||
                zone.name.contains(tzSearchQuery, ignoreCase = true) ||
                zone.city.contains(tzSearchQuery, ignoreCase = true) ||
                zone.id.contains(tzSearchQuery, ignoreCase = true) ||
                zone.offset.contains(tzSearchQuery, ignoreCase = true)

            val matchesRegion = when (tzRegionFilter) {
                "Americas" -> zone.region == "Americas"
                "Europe" -> zone.region == "Europe"
                "Asia" -> zone.region == "Asia"
                "Middle East" -> zone.region == "Middle East"
                "Africa" -> zone.region == "Africa"
                "Oceania" -> zone.region == "Oceania"
                "UTC" -> zone.region == "UTC"
                else -> true
            }

            matchesSearch && matchesRegion
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "DATE & TIME ZONE",
            color = MikuOnboardingTeal,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Text(
            "Set your local time zone and clock format.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.5.sp
        )

        Spacer(Modifier.height(10.dp))

        // Live Clock Display Card
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MikuOnboardingCard)
                .border(1.dp, MikuOnboardingBorder, RoundedCornerShape(16.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(liveTime, color = MikuOnboardingTeal, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("Current Device Time", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Toggles
        ToggleRowItem("Automatic Network Time (NTP)", "Sync clock via 0.pool.ntp.org", autoSync, onAutoSyncToggle)
        Spacer(Modifier.height(6.dp))
        ToggleRowItem("24-Hour Military Format", "Use 24-hour time instead of 12-hour AM/PM", use24H, on24HToggle)

        Spacer(Modifier.height(12.dp))
        Text("SELECT TIME ZONE", color = MikuOnboardingTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))

        // Interactive Dropdown Trigger Card
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MikuOnboardingTeal.copy(alpha = 0.12f))
                .border(1.dp, MikuOnboardingTeal, RoundedCornerShape(14.dp))
                .clickable { showTzPickerModal = true }
                .padding(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, contentDescription = "Globe", tint = MikuOnboardingTeal, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(currentZoneObj.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("${currentZoneObj.id} • ${currentZoneObj.offset}", color = MikuOnboardingTeal, fontSize = 9.sp)
                    }
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Dropdown", tint = MikuOnboardingTeal, modifier = Modifier.size(24.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        // Quick Pick Recommendations
        Text("POPULAR REGIONS", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        SupportedTimeZones.take(4).forEach { zone ->
            val isSelected = zone.id == selectedZone
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) MikuOnboardingTeal.copy(alpha = 0.15f) else MikuOnboardingCard)
                    .border(1.dp, if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .clickable { onZoneSelect(zone.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(zone.name, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(zone.offset, color = if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.4f), fontSize = 8.5.sp)
                }
                if (isSelected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MikuOnboardingTeal, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    // World Time Zone Searchable Dropdown Modal
    if (showTzPickerModal) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable { showTzPickerModal = false }
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MikuOnboardingBg)
                    .border(1.dp, MikuOnboardingTeal, RoundedCornerShape(16.dp))
                    .clickable(enabled = false) {}
                    .padding(16.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "WORLD TIME ZONES",
                            color = MikuOnboardingTeal,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        IconButton(onClick = { showTzPickerModal = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    // Search Input
                    OutlinedTextField(
                        value = tzSearchQuery,
                        onValueChange = { tzSearchQuery = it },
                        placeholder = { Text("Search city, country, or UTC...", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = MikuOnboardingTeal, modifier = Modifier.size(16.dp)) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MikuOnboardingTeal,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            cursorColor = MikuOnboardingTeal,
                            focusedContainerColor = MikuOnboardingCard,
                            unfocusedContainerColor = MikuOnboardingCard,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    // Region Filters
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("All", "Americas", "Europe", "Asia", "Middle East", "Africa", "Oceania", "UTC").forEach { region ->
                            val isSelected = tzRegionFilter == region
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MikuOnboardingTeal.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (isSelected) MikuOnboardingTeal else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { tzRegionFilter = region }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    region,
                                    color = if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.6f),
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(filteredTimeZones) { zone ->
                            val isSelected = zone.id == selectedZone
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) MikuOnboardingTeal.copy(alpha = 0.2f) else MikuOnboardingCard)
                                    .border(1.dp, if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                    .clickable {
                                        onZoneSelect(zone.id)
                                        showTzPickerModal = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(zone.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("${zone.id} (${zone.region})", color = Color.White.copy(alpha = 0.5f), fontSize = 8.5.sp)
                                }
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) MikuOnboardingTeal else Color.White.copy(alpha = 0.1f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        zone.offset,
                                        color = if (isSelected) Color.Black else MikuOnboardingTeal,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------
// Step 3: Apps & Services Provisioning (Smart Dependency Latching)
// ----------------------------------------------------------------
@Composable
fun AppsProvisioningScreen(selectedApps: MutableMap<String, Boolean>) {
    val gmsEnabled = selectedApps["gms"] ?: true

    Column(Modifier.fillMaxSize()) {
        Text(
            "APPS & SERVICES",
            color = MikuOnboardingTeal,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Text(
            "Choose installed apps. Google apps require Google Core Services backend.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.5.sp
        )

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            item {
                Text("GOOGLE CORE SUITE", color = MikuOnboardingGold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
            }
            items(GoogleCoreApps) { app ->
                val isGms = app.id == "gms"
                val isSelected = selectedApps[app.id] ?: false
                val isEnabled = isGms || gmsEnabled
                val actualSelected = if (!isEnabled) false else isSelected

                AppItemCard(
                    app = app,
                    isSelected = actualSelected,
                    isEnabled = isEnabled,
                    disabledReason = if (!isEnabled) "Requires GMS Core Backend" else null
                ) {
                    val next = !actualSelected
                    selectedApps[app.id] = next
                    if (isGms && !next) {
                        // Unchecking GMS automatically disables & unchecks all dependent Google apps
                        GoogleCoreApps.filter { it.id != "gms" }.forEach { child ->
                            selectedApps[child.id] = false
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("OPTIONAL STREAMING & MEDIA", color = MikuOnboardingTeal, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
            }
            items(OptionalApps) { app ->
                val isSelected = selectedApps[app.id] ?: false
                AppItemCard(app, isSelected) { selectedApps[app.id] = !isSelected }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("BUNDLED ON DEVICE (OFFLINE)", color = MikuOnboardingGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    "Installable from ROM image — no network required",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.sp
                )
                Spacer(Modifier.height(4.dp))
            }
            items(BundledApps) { app ->
                val isSelected = selectedApps[app.id] ?: false
                AppItemCard(app, isSelected) { selectedApps[app.id] = !isSelected }
            }
        }
    }
}

// ----------------------------------------------------------------
// Step 4: Visible Install & Confirm
//   Installs every SELECTED app right here with live per-row state
//   (NotStarted -> Installing -> Installed / Failed / Skipped). Apps whose APK is
//   bundled under /system/etc/mikuos-optional-apks/ install offline via
//   PackageInstaller (the system shows its per-app confirm dialog); everything
//   else is "pulled" from the Play Store. Google Play Store is force-installed
//   first when a pulled app is selected and the store isn't present yet.
//   The wizard's NEXT button (owned by the parent) stays disabled until every
//   selected app is terminal — so the user CONFIRMS the installs before moving on.
// ----------------------------------------------------------------
@Composable
fun AppInstallScreen(
    selectedApps: Map<String, Boolean>,
    installStates: SnapshotStateMap<String, InstallPhase>
) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val scope = rememberCoroutineScope()

    // Scan bundled APKs once (reads each APK's real package name off the ROM partition).
    var apkIndex by remember { mutableStateOf<BundledApkIndex?>(null) }
    LaunchedEffect(Unit) {
        apkIndex = withContext(Dispatchers.IO) { BundledApkIndex.scan(pm) }
    }

    // PackageInstaller status receiver: surfaces the per-app confirm dialog
    // (STATUS_PENDING_USER_ACTION) and flips rows to Installed / Failed.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                val appId = intent.getStringExtra(EXTRA_INSTALL_APP_ID) ?: return
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = if (Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.let {
                            try { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> installStates[appId] = InstallPhase.Installed
                    else -> if (installStates[appId] == InstallPhase.Installing) installStates[appId] = InstallPhase.Failed
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(INSTALL_STATUS_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {} }
    }

    // Apps to install during onboarding init: ONLY the selected ones. We do NOT
    // auto-prepend the Play Store — onboarding is offline-only and never launches
    // Play. Apps without a bundled APK are simply skipped (installable later from
    // Aurora/F-Droid). Ordered bundled-first.
    val displayList: List<ProvisionableApp> = remember(apkIndex) {
        val idx = apkIndex ?: return@remember emptyList()
        val selected = AllProvisionableApps.filter { selectedApps[it.id] == true }
        val list = ArrayList<ProvisionableApp>()
        list.addAll(selected)
        list.sortedBy { app ->
            when {
                app.packageName == PLAY_STORE_PKG -> 0
                idx.apkFor(app) != null -> 1
                else -> 2
            }
        }
    }

    // Seed state for each row — already-present packages start Installed.
    LaunchedEffect(displayList) {
        displayList.forEach { app ->
            val present = isPackageInstalled(pm, app.packageName)
            when (val cur = installStates[app.id]) {
                null -> installStates[app.id] = if (present) InstallPhase.Installed else InstallPhase.NotStarted
                else -> if (present && cur != InstallPhase.Installed) installStates[app.id] = InstallPhase.Installed
            }
        }
    }

    // --- install drivers -------------------------------------------------
    fun phaseOf(id: String) = installStates[id] ?: InstallPhase.NotStarted

    // Polls for presence while the row is still Installing; bails early if the row
    // leaves Installing (user Skips, or the receiver reports failure).
    suspend fun awaitWhileInstalling(app: ProvisionableApp, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (installStates[app.id] == InstallPhase.Installing &&
            System.currentTimeMillis() - start < timeoutMs
        ) {
            if (isPackageInstalled(pm, app.packageName)) return true
            delay(1000)
        }
        return isPackageInstalled(pm, app.packageName)
    }

    suspend fun ensurePlayStore() {
        if (isPackageInstalled(pm, PLAY_STORE_PKG)) return
        val apk = apkIndex?.apkForPackage(PLAY_STORE_PKG) ?: return
        val psId = PlayStoreApp.id
        if (installStates[psId] != InstallPhase.Installed) installStates[psId] = InstallPhase.Installing
        withContext(Dispatchers.IO) { try { commitBundledInstall(ctx, apk, psId) } catch (_: Throwable) {} }
        val ok = awaitPackageInstalled(pm, PLAY_STORE_PKG, 180_000L)
        if (installStates[psId] != InstallPhase.Installed) {
            installStates[psId] = if (ok) InstallPhase.Installed else InstallPhase.Failed
        }
    }

    fun startInstall(app: ProvisionableApp) {
        if (installStates[app.id] == InstallPhase.Installed) return
        val apk = apkIndex?.apkFor(app)
        installStates[app.id] = InstallPhase.Installing
        scope.launch {
            if (apk != null) {
                // Bundled: offline PackageInstaller session (system confirm dialog).
                val committed = withContext(Dispatchers.IO) {
                    try { commitBundledInstall(ctx, apk, app.id); true } catch (_: Throwable) { false }
                }
                if (!committed) { installStates[app.id] = InstallPhase.Failed; return@launch }
                val ok = awaitWhileInstalling(app, 120_000L)
                if (installStates[app.id] == InstallPhase.Installing) {
                    installStates[app.id] = if (ok) InstallPhase.Installed else InstallPhase.Failed
                }
            } else {
                // No bundled APK -> NOT installable offline. Onboarding never opens
                // the Play Store (that snapped users to Play and then hung the wizard
                // in a 10-min poll). Mark Skipped immediately; the app can be pulled
                // later from Aurora/F-Droid.
                installStates[app.id] = if (isPackageInstalled(pm, app.packageName))
                    InstallPhase.Installed else InstallPhase.Skipped
            }
        }
    }

    fun skip(app: ProvisionableApp) { installStates[app.id] = InstallPhase.Skipped }

    // Install All: process each unresolved row in order, one at a time, so confirm
    // dialogs / store hand-offs never stack. Waits for each to resolve before the next.
    fun installAll() {
        scope.launch {
            for (app in displayList) {
                val ph = installStates[app.id]
                if (ph == InstallPhase.Installed || ph == InstallPhase.Skipped) continue
                startInstall(app)
                while (true) {
                    val p = installStates[app.id]
                    if (p != null && p != InstallPhase.Installing && p != InstallPhase.NotStarted) break
                    delay(400)
                }
            }
        }
    }

    // --- UI --------------------------------------------------------------
    val installedCount = displayList.count { installStates[it.id] == InstallPhase.Installed }
    val resolvedCount = displayList.count { (installStates[it.id] ?: InstallPhase.NotStarted) in TerminalInstallPhases }
    val anyPending = displayList.any {
        val p = phaseOf(it.id); p != InstallPhase.Installed && p != InstallPhase.Skipped
    }

    Column(Modifier.fillMaxSize()) {
        Text("INSTALL APPS", color = MikuOnboardingTeal, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text(
            "Confirm each install before continuing. Bundled apps install offline; the rest open the Play Store.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.5.sp
        )
        Spacer(Modifier.height(8.dp))

        if (apkIndex == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = MikuOnboardingTeal, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Scanning bundled packages…", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
        } else if (displayList.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No apps selected — tap NEXT to continue.", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        } else {
            // Progress summary + Install All
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MikuOnboardingCard)
                    .border(1.dp, MikuOnboardingBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "$installedCount installed · $resolvedCount / ${displayList.size} resolved",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (anyPending) "Install or skip every app to unlock NEXT" else "All set — you can continue",
                        color = if (anyPending) MikuOnboardingGold else MikuOnboardingGreen, fontSize = 9.sp
                    )
                }
                Button(
                    onClick = { installAll() },
                    enabled = anyPending,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MikuOnboardingTeal,
                        disabledContainerColor = Color.White.copy(alpha = 0.08f)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("INSTALL ALL", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(displayList) { app ->
                    AppInstallRow(
                        app = app,
                        phase = phaseOf(app.id),
                        bundled = apkIndex?.apkFor(app) != null,
                        onInstall = { startInstall(app) },
                        onSkip = { skip(app) }
                    )
                }
            }
        }
    }
}

@Composable
fun AppInstallRow(
    app: ProvisionableApp,
    phase: InstallPhase,
    bundled: Boolean,
    onInstall: () -> Unit,
    onSkip: () -> Unit
) {
    val accent = app.accentColor
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (phase == InstallPhase.Installed) MikuOnboardingGreen.copy(alpha = 0.10f) else MikuOnboardingCard)
            .border(
                1.dp,
                when (phase) {
                    InstallPhase.Installed -> MikuOnboardingGreen.copy(alpha = 0.6f)
                    InstallPhase.Failed -> MikuOnboardingPink.copy(alpha = 0.6f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(app.icon, contentDescription = app.name, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.name, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background((if (bundled) MikuOnboardingGreen else MikuOnboardingGold).copy(alpha = 0.15f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        if (bundled) "BUNDLED" else "PLAY STORE",
                        color = if (bundled) MikuOnboardingGreen else MikuOnboardingGold,
                        fontSize = 7.5.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                when (phase) {
                    InstallPhase.NotStarted -> if (bundled) "Ready to install offline" else "Opens Play Store to install"
                    InstallPhase.Installing -> if (bundled) "Installing…" else "Waiting for Play Store…"
                    InstallPhase.Installed -> "Installed"
                    InstallPhase.Failed -> "Install failed — retry or skip"
                    InstallPhase.Skipped -> "Skipped"
                },
                color = when (phase) {
                    InstallPhase.Installed -> MikuOnboardingGreen
                    InstallPhase.Failed -> MikuOnboardingPink
                    InstallPhase.Skipped -> Color.White.copy(alpha = 0.5f)
                    else -> Color.White.copy(alpha = 0.6f)
                },
                fontSize = 9.sp, maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        when (phase) {
            InstallPhase.Installing -> {
                CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onSkip, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Skip", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
            InstallPhase.Installed -> Icon(
                Icons.Default.CheckCircle, contentDescription = "Installed",
                tint = MikuOnboardingGreen, modifier = Modifier.size(22.dp)
            )
            InstallPhase.Skipped -> TextButton(onClick = onInstall, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Install", color = MikuOnboardingTeal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            InstallPhase.Failed -> {
                TextButton(onClick = onInstall, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Retry", color = MikuOnboardingGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onSkip, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Skip", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
            InstallPhase.NotStarted -> {
                TextButton(onClick = onSkip, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Skip", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                }
                Button(
                    onClick = onInstall,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(if (bundled) "Install" else "Get", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ----------------------------------------------------------------
// Step 5: Completion & Start MikuOS
// ----------------------------------------------------------------
@Composable
fun CompletionScreen(
    lang: String,
    timeZone: String,
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
                .size(70.dp)
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
            Icon(Icons.Default.Check, contentDescription = "Ready", tint = MikuOnboardingTeal, modifier = Modifier.size(36.dp))
        }

        Spacer(Modifier.height(14.dp))

        Text(
            "YOU'RE READY FOR MIKUOS",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center
        )

        Text(
            "Setup complete. Your m500 is tuned and ready to play.",
            color = MikuOnboardingTeal,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        // Summary Card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MikuOnboardingCard)
                .border(1.dp, MikuOnboardingBorder, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Text("CONFIGURATION SUMMARY", color = MikuOnboardingTeal, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))

            SummaryItem(Icons.Default.Language, "Language", SystemLanguages.firstOrNull { it.code == lang }?.nativeName ?: "English")
            SummaryItem(Icons.Default.AccessTime, "Time Zone", SupportedTimeZones.firstOrNull { it.id == timeZone }?.name ?: timeZone)
            SummaryItem(Icons.Default.Apps, "Installed Apps", "$appsCount Apps Installed")
            SummaryItem(Icons.Default.Keyboard, "Keyboard (IME)", "AOSP LatinIME (Themed)")
            SummaryItem(Icons.Default.GraphicEq, "Audio Engine", "Dual CS43198 Direct ALSA")
        }
    }
}

// ----------------------------------------------------------------
// Helper Components
// ----------------------------------------------------------------
@Composable
fun AppItemCard(
    app: ProvisionableApp,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    disabledReason: String? = null,
    onToggle: () -> Unit
) {
    val alpha = if (isEnabled) 1.0f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected && isEnabled) app.accentColor.copy(alpha = 0.12f) else MikuOnboardingCard)
            .border(
                1.dp,
                if (isSelected && isEnabled) app.accentColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = isEnabled) { onToggle() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isEnabled) app.accentColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(app.icon, contentDescription = app.name, tint = if (isEnabled) app.accentColor else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.name,
                    color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.4f),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
                if (disabledReason != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikuOnboardingPink.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(disabledReason, color = MikuOnboardingPink, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                app.description,
                color = if (isEnabled) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.3f),
                fontSize = 9.sp,
                maxLines = 1
            )
        }
        Checkbox(
            checked = isSelected && isEnabled,
            enabled = isEnabled,
            onCheckedChange = { if (isEnabled) onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = app.accentColor,
                checkmarkColor = Color.Black,
                disabledCheckedColor = Color.Gray,
                disabledUncheckedColor = Color.DarkGray
            )
        )
    }
}

@Composable
fun ToggleRowItem(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MikuOnboardingCard)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clickable { onToggle(!checked) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MikuOnboardingTeal)
        )
    }
}

@Composable
fun SummaryItem(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = MikuOnboardingTeal, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text("$label: ", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
