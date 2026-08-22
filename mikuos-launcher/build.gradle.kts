import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Optional, gitignored local secrets file for the arcobocconotto RGB integration
// (direct HMAC key path / endpoint overrides for local dev). Never committed;
// see mikuos-launcher/arco.properties.example for the expected keys. Everything
// here defaults to an empty string when the file is absent — the app falls back
// to interactive mDNS discovery + SAS pairing at runtime in that case.
val arcoProps = Properties().apply {
    val f = rootProject.file("mikuos-launcher/arco.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun arcoProp(key: String) = arcoProps.getProperty(key, "")

// Gitignored local.properties (never committed) — seeds the media-ingestion / network-telemetry
// BuildConfig fields so no personal host/IP/subnet lives in source. All default to "" when absent;
// MikuIngestConfig then falls back to runtime auto-discovery or a neutral display.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String) = localProps.getProperty(key, "")

android {
    namespace = "com.miku.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.miku.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.1.18"

        buildConfigField("String", "ARCO_HMAC_KEY_ID", "\"${arcoProp("ARCO_HMAC_KEY_ID")}\"")
        buildConfigField("String", "ARCO_HMAC_SECRET", "\"${arcoProp("ARCO_HMAC_SECRET")}\"")
        buildConfigField("String", "ARCO_LAN_URL", "\"${arcoProp("ARCO_LAN_URL")}\"")
        buildConfigField("String", "ARCO_PUBLIC_URL", "\"${arcoProp("ARCO_PUBLIC_URL")}\"")

        // Media ingestion server + network/VPN telemetry (see MikuIngestConfig). Sourced from the
        // gitignored local.properties; all default to "" (=> runtime auto-discovery / neutral display).
        // Example keys: miku.sync.host, miku.sync.port, miku.lan.hints, miku.home.ssids,
        // miku.vpn.endpoint, miku.vpn.assigned.ip, miku.vpn.routes (TEST-NET values, e.g. 192.0.2.10).
        buildConfigField("String", "MIKU_SYNC_HOST", "\"${localProp("miku.sync.host")}\"")
        buildConfigField("String", "MIKU_SYNC_PORT", "\"${localProp("miku.sync.port")}\"")
        buildConfigField("String", "MIKU_LAN_HINTS", "\"${localProp("miku.lan.hints")}\"")
        buildConfigField("String", "MIKU_HOME_SSIDS", "\"${localProp("miku.home.ssids")}\"")
        buildConfigField("String", "MIKU_VPN_ENDPOINT", "\"${localProp("miku.vpn.endpoint")}\"")
        buildConfigField("String", "MIKU_VPN_ASSIGNED_IP", "\"${localProp("miku.vpn.assigned.ip")}\"")
        buildConfigField("String", "MIKU_VPN_ROUTES", "\"${localProp("miku.vpn.routes")}\"")
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
    val platformStorePath: String? = signingProps.getProperty("platform.storeFile")
    val platformStoreFile = platformStorePath?.let { path: String ->
        listOf(
            rootProject.file("../$path"),
            rootProject.file(path),
            file(path)
        ).firstOrNull { it.exists() }
    }
    val platformStorePass: String? = signingProps.getProperty("platform.storePassword")
    val platformKeyAlias: String = signingProps.getProperty("platform.keyAlias", "platform")
    val platformKeyPass: String? = signingProps.getProperty("platform.keyPassword")
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
        compose = true
        buildConfig = true
    }

    lint {
        // Correct-by-design for a platform-signed system launcher — not defects:
        //  QueryAllPackagesPermission: a launcher legitimately enumerates all apps.
        //  ProtectedPermissions: system/signature permissions on our own ROM.
        disable += setOf("QueryAllPackagesPermission", "ProtectedPermissions")
        // Accept remaining known issues (NewApi on API-34-only device, pre-granted runtime perms,
        // framework false-positives) via a baseline; NEW issues still fail the build.
        baseline = file("lint-baseline.xml")
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "MikuOS_Launcher-v${defaultConfig.versionName}.apk"
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0") // animated GIF decoding (HiBy onboarding GIFs)

    // media3 MediaController: the OS lockscreen (com.miku.launcher.lockscreen) connects to the
    // music app's MediaSession (com.miku.player/.PlaybackService) to read now-playing metadata /
    // artwork and drive transport, without importing any app classes.
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // arcobocconotto RGB fleet integration: JSON models + encrypted local credential storage.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Native WireGuard tunnel (embeddable, bundles wireguard-go arm64 backend + config parser).
    // Powers the in-launcher VPN client (com.miku.launcher.vpn) for reaching the user's home UDR.
    implementation("com.wireguard.android:tunnel:1.0.20230706")
}
