package com.miku.launcher

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * The startup tuning the launcher applies to make the device reachable and usable, done with
 * platform APIs instead of a root shell.
 *
 * WHY THIS EXISTS. `MikuLauncherActivity.onCreate` used to hand a 19-command string to
 * `RootShell.execFast`. MikuOS has no root and never will, so every launch produced
 * `Cannot run program "su": error=2, No such file or directory`, RootShell backed off for 120
 * seconds, and NOT ONE of those nineteen settings was ever applied. That included
 * `adb_wifi_enabled`, which the ingest relay depends on to reach the device at all, so the sync
 * could not work even once the server side existed.
 *
 * Every one of those commands has a first-class API behind it, and a platform-signed app holds the
 * permissions they need: WRITE_SECURE_SETTINGS for the `settings put` lines,
 * MANAGE_APP_OPS_MODES for `appops set`, GRANT_RUNTIME_PERMISSIONS for `pm grant`. This is the
 * standing rule in practice: a `su` failure here was a code bug, not a reason to install Magisk.
 *
 * WHAT IS DELIBERATELY NOT CARRIED OVER. The original string also ran `setprop` on four
 * `persist.*` / `service.adb.*` properties and then `stop adbd; start adbd`. Those are not
 * available to us and no amount of signing changes it: the property contexts are owned by the
 * `adbd` domain and `stop`/`start` are init verbs for the shell. They are dropped rather than
 * attempted-and-swallowed, because a call that cannot work should not look like one that might.
 * `Settings.Global.ADB_WIFI_ENABLED` is the supported route to the same outcome on Android 11 and
 * later, and it is set here.
 */
object MikuSystemTuning {

    private const val TAG = "MikuSystemTuning"

    private val GLOBAL_INTS = listOf(
        // adb, including over Wi-Fi. The ingest relay reaches the device this way, so these are
        // not a developer convenience here, they are load-bearing for sync.
        "adb_enabled" to 1,
        "development_settings_enabled" to 1,
        "adb_wifi_enabled" to 1,
        // Never drop Wi-Fi when the screen goes off, or a sync dies halfway through a library.
        "wifi_sleep_policy" to 2,
        // Stay awake on any charger while ingesting.
        "stay_on_while_plugged_in" to 3,
    )

    /** SYSTEM_ALERT_WINDOW for our own overlays: the nav pill, the shade, the volume modal. */
    private val OVERLAY_PACKAGES = listOf(
        "com.miku.launcher",
        "com.miku.player",
        "com.miku.systemui",
    )

    private val RUNTIME_GRANTS = listOf(
        "com.miku.launcher" to android.Manifest.permission.RECORD_AUDIO,
        "com.miku.launcher" to "android.permission.MEDIA_CONTENT_CONTROL",
    )

    /**
     * Apply everything, reporting per-item rather than as one opaque success.
     *
     * Each item is attempted independently: one SecurityException must not take the rest down,
     * because partial tuning that says which part failed is far more useful than an all-or-nothing
     * shell string that silently did nothing for months.
     */
    fun apply(ctx: Context) {
        val cr = ctx.contentResolver
        var ok = 0
        var failed = 0

        for ((key, value) in GLOBAL_INTS) {
            val current = runCatching { Settings.Global.getInt(cr, key, Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
            if (current == value) { ok++; continue }
            runCatching { Settings.Global.putInt(cr, key, value) }
                .onSuccess { ok++; Log.i(TAG, "global $key: $current -> $value") }
                .onFailure { failed++; Log.w(TAG, "global $key failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        for (pkg in OVERLAY_PACKAGES) {
            val uid = runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrNull()
            if (uid == null) { Log.d(TAG, "appops: $pkg not installed, skipping"); continue }
            runCatching {
                // setMode is @SystemApi and needs MANAGE_APP_OPS_MODES, which a platform-signed
                // app holds. Reflection rather than a direct call because the constant and the
                // overload are hidden; a direct reference would not compile against the SDK.
                val m = AppOpsManager::class.java.getMethod(
                    "setMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    String::class.java, Int::class.javaPrimitiveType
                )
                val opCode = AppOpsManager::class.java
                    .getField("OP_SYSTEM_ALERT_WINDOW").getInt(null)
                m.invoke(appOps, opCode, uid, pkg, AppOpsManager.MODE_ALLOWED)
            }.onSuccess { ok++; Log.i(TAG, "appops SYSTEM_ALERT_WINDOW allowed for $pkg") }
             .onFailure { failed++; Log.w(TAG, "appops for $pkg failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        for ((pkg, perm) in RUNTIME_GRANTS) {
            val already = runCatching {
                ctx.packageManager.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (already) { ok++; continue }
            runCatching {
                // grantRuntimePermission is @SystemApi behind GRANT_RUNTIME_PERMISSIONS.
                val m = PackageManager::class.java.getMethod(
                    "grantRuntimePermission", String::class.java, String::class.java,
                    android.os.UserHandle::class.java
                )
                m.invoke(ctx.packageManager, pkg, perm, android.os.Process.myUserHandle())
            }.onSuccess { ok++; Log.i(TAG, "granted $perm to $pkg") }
             .onFailure { failed++; Log.w(TAG, "grant $perm to $pkg failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        // The original string also pinned the default IME to
        // com.android.inputmethod.latin/.LatinIME. That is carried over, but only when the IME is
        // actually present: MikuOS ships HeliBoard, so on a current build that component may not
        // exist, and writing a default_input_method that resolves to nothing leaves the device
        // with no keyboard at all. Checked first, skipped loudly if absent.
        runCatching {
            val ime = "com.android.inputmethod.latin/.LatinIME"
            val pkg = ime.substringBefore('/')
            val installed = ctx.packageManager.getInstalledPackages(0).any { it.packageName == pkg }
            if (!installed) {
                Log.i(TAG, "default IME $pkg not installed, leaving the keyboard setting alone")
            } else if (Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD) != ime) {
                Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, ime)
                Settings.Secure.putString(cr, "enabled_input_methods", ime)
                Log.i(TAG, "default IME set to $ime")
            }
        }.onFailure { Log.w(TAG, "IME setting failed: ${it.javaClass.simpleName}: ${it.message}") }

        Log.i(TAG, "startup tuning: $ok applied or already set, $failed failed")
    }
}
