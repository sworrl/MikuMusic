import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.miku.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miku.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 2247
        versionName = "2.0.247"

        // Last.fm API credentials — read from local.properties (gitignored, never committed) so
        // the key/secret never live in source. Register a free app at
        // https://www.last.fm/api/account/create, then add to local.properties:
        //   lastfm.api.key=...
        //   lastfm.api.secret=...
        // Blank until set — LastFm.isConfigured gates every call so absence fails soft, not with
        // a crash, and Settings shows "not configured" instead of a broken login button.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProps.getProperty("lastfm.api.key", "")}\"")
        buildConfigField("String", "LASTFM_API_SECRET", "\"${localProps.getProperty("lastfm.api.secret", "")}\"")

        // Media ingestion server + network/VPN telemetry — read from local.properties (gitignored,
        // never committed) so no personal host/IP/subnet lives in source. All default to "" when the
        // file omits the key: MikuIngestConfig then falls back to runtime auto-discovery (empty host)
        // or a neutral "not configured" display. The user can also set these at runtime (persisted to
        // SharedPreferences) via MikuIngestConfig.set*. Example local.properties entries:
        //   miku.sync.host=192.0.2.10      # your rsync ingest server (TEST-NET example)
        //   miku.sync.port=8730
        //   miku.lan.hints=192.0.2.        # comma-separated IP/subnet hints for discovery + home detection
        //   miku.home.ssids=MyWifi         # comma-separated home Wi-Fi SSIDs
        //   miku.vpn.endpoint=vpn.example.com:51820
        //   miku.vpn.assigned.ip=192.0.2.250
        //   miku.vpn.routes=192.0.2.0/24
        buildConfigField("String", "MIKU_SYNC_HOST", "\"${localProps.getProperty("miku.sync.host", "")}\"")
        buildConfigField("String", "MIKU_SYNC_PORT", "\"${localProps.getProperty("miku.sync.port", "")}\"")
        buildConfigField("String", "MIKU_LAN_HINTS", "\"${localProps.getProperty("miku.lan.hints", "")}\"")
        buildConfigField("String", "MIKU_HOME_SSIDS", "\"${localProps.getProperty("miku.home.ssids", "")}\"")
        buildConfigField("String", "MIKU_VPN_ENDPOINT", "\"${localProps.getProperty("miku.vpn.endpoint", "")}\"")
        buildConfigField("String", "MIKU_VPN_ASSIGNED_IP", "\"${localProps.getProperty("miku.vpn.assigned.ip", "")}\"")
        buildConfigField("String", "MIKU_VPN_ROUTES", "\"${localProps.getProperty("miku.vpn.routes", "")}\"")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -O3 -ffast-math -fexceptions -frtti")
            }
        }
        ndk {
            abiFilters.addAll(setOf("arm64-v8a"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val signingProps = Properties().apply {
        val candidates = listOf(
            rootProject.file("../keystore.properties"),
            rootProject.file("keystore.properties"),
            rootProject.file("local.properties")
        )
        for (f in candidates) {
            if (f.exists()) {
                f.inputStream().use { load(it) }
                break
            }
        }
    }
    val platformStorePath: String? = signingProps.getProperty("platform.storeFile") ?: signingProps.getProperty("release.keystore")
    val platformStoreFile = platformStorePath?.let { path: String ->
        listOf(
            rootProject.file("../$path"),
            rootProject.file(path),
            file(path)
        ).firstOrNull { it.exists() }
    }
    val platformStorePass: String? = signingProps.getProperty("platform.storePassword") ?: signingProps.getProperty("release.password")
    val platformKeyAlias: String = signingProps.getProperty("platform.keyAlias") ?: signingProps.getProperty("release.keyAlias", "platform")
    val platformKeyPass: String? = signingProps.getProperty("platform.keyPassword") ?: signingProps.getProperty("release.password")
    val hasPlatformSigning = platformStoreFile != null && platformStorePass != null && platformKeyPass != null

    signingConfigs {
        if (hasPlatformSigning) {
            create("platform") {
                storeFile = platformStoreFile
                storePassword = platformStorePass
                keyAlias = platformKeyAlias
                keyPassword = platformKeyPass
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (hasPlatformSigning) {
                signingConfig = signingConfigs.getByName("platform")
            }
        }
        release {
            if (hasPlatformSigning) {
                signingConfig = signingConfigs.getByName("platform")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint {
        // Correct-by-design for this platform-signed, single-device ROM app — not defects:
        //  UnsafeOptInUsageError: we deliberately extend Media3 @UnstableApi internals
        //    (MikuDirectAudioSink) for the bit-perfect DIRECT path.
        //  ProtectedPermissions/QueryAllPackagesPermission: system app on our own ROM.
        disable += setOf("UnsafeOptInUsageError", "ProtectedPermissions", "QueryAllPackagesPermission")
        // Accept the remaining known issues (NewApi on an API-34-only device, pre-granted runtime
        // permissions, framework false-positives) via a baseline, so the build stays green while
        // any NEW issue still fails the build.
        baseline = file("lint-baseline.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
    }
    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "MikuMusic-v${versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("org.videolan.android:libvlc-all:3.6.2")
    implementation("org.videolan.android:medialibrary-all:0.13.13-rc17")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("net.jthink:jaudiotagger:3.0.1")
    // Real backdrop blur ("Pixel glass") — Modifier.haze() marks scrollable content as a blur
    // source, Modifier.hazeChild() on an overlay panel blurs whatever's currently behind it live.
    // Pinned to 0.7.x (the API used below): newer 1.x/2.x releases pull in AndroidX versions that
    // need a newer AGP than this project's 8.5.2.
    implementation("dev.chrisbanes.haze:haze:0.7.3")
    implementation("dev.chrisbanes.haze:haze-materials:0.7.3")
    // Encrypted-at-rest storage for the Last.fm session key — AES256-GCM prefs file whose own
    // key lives in the Android Keystore (hardware-backed on this chipset), not app storage.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Android for Cars App Library for custom Android Auto interface
    implementation("androidx.car.app:app:1.4.0")
}
