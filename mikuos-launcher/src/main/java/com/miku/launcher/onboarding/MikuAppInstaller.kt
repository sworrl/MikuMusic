package com.miku.launcher.onboarding

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.delay
import java.io.File

/**
 * Non-Compose install plumbing for the onboarding wizard's Step 4 ("Install Apps").
 *
 * Responsibilities:
 *  - Detect which selected apps are BUNDLED on the ROM (a matching APK exists under
 *    /system/etc/mikuos-optional-apks/) vs. PULLED from the Play Store.
 *  - Drive offline PackageInstaller sessions for bundled APKs (the system surfaces its
 *    own per-app install-confirm dialog; no root required).
 *  - Hand pulled apps off to the Play Store via market://.
 *  - Poll package presence so the UI can flip each row NotStarted -> Installing ->
 *    Installed / Failed and gate the wizard's NEXT button.
 */

/** Per-app install state shown on Step 4 and used to gate the wizard's NEXT button. */
enum class InstallPhase { NotStarted, Installing, Installed, Failed, Skipped }

/** Phases that count as "resolved" — NEXT unlocks only once every selected app is here. */
val TerminalInstallPhases = setOf(InstallPhase.Installed, InstallPhase.Skipped)

/** Google Play Store — a bundled prerequisite that must exist before any pulled app can install. */
const val PLAY_STORE_PKG = "com.android.vending"

/** Broadcast action + extra used by the PackageInstaller commit status callback. */
const val INSTALL_STATUS_ACTION = "com.miku.launcher.onboarding.ACTION_APK_INSTALLED"
const val EXTRA_INSTALL_APP_ID = "com.miku.launcher.onboarding.EXTRA_APP_ID"

/**
 * Candidate on-ROM directories that may hold the bundled optional APKs. The build script
 * places them under /system/etc/mikuos-optional-apks/; the alternates cover mount layouts
 * (system-as-root, product/system_ext partitions) and the AOSP preloaded-apks path.
 */
val OptionalApkDirs = listOf(
    "/system/etc/mikuos-optional-apks",
    "/system/system/etc/mikuos-optional-apks",
    "/product/etc/mikuos-optional-apks",
    "/system_ext/etc/mikuos-optional-apks",
    "/data/preloaded_apks"
)

/** First existing optional-APK dir that actually contains at least one .apk. */
fun findOptionalApkDir(): File? = OptionalApkDirs
    .map { File(it) }
    .firstOrNull { d -> d.isDirectory && (d.listFiles()?.any { it.extension.equals("apk", true) } == true) }

/**
 * Runtime index of the bundled APKs. An app is BUNDLED (direct offline install) when
 * [apkFor] resolves a file; otherwise it is PULLED from the Play Store. Matching is by
 * the APK's real package name first (authoritative), then by filename stem == app id,
 * then by the trailing token of the package name.
 */
class BundledApkIndex(
    val byPackage: Map<String, File>,
    val byStem: Map<String, File>
) {
    fun apkFor(app: ProvisionableApp): File? =
        byPackage[app.packageName]
            ?: byStem[app.id.lowercase()]
            ?: byStem[app.packageName.substringAfterLast('.').lowercase()]

    fun apkForPackage(pkg: String): File? = byPackage[pkg]

    companion object {
        fun scan(pm: PackageManager): BundledApkIndex {
            val dir = findOptionalApkDir() ?: return BundledApkIndex(emptyMap(), emptyMap())
            val byPackage = HashMap<String, File>()
            val byStem = HashMap<String, File>()
            dir.listFiles { f -> f.extension.equals("apk", true) }?.forEach { f ->
                byStem[f.nameWithoutExtension.lowercase()] = f
                try {
                    pm.getPackageArchiveInfo(f.absolutePath, 0)?.packageName?.let { byPackage[it] = f }
                } catch (_: Throwable) { }
            }
            return BundledApkIndex(byPackage, byStem)
        }
    }
}

/** True if [pkg] is currently installed and visible to us. */
fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean = try {
    pm.getPackageInfo(pkg, 0); true
} catch (_: Throwable) { false }

/**
 * Streams a bundled APK into a PackageInstaller session and commits it. The commit status
 * (including STATUS_PENDING_USER_ACTION, which surfaces the system's per-app confirm dialog)
 * is delivered to [INSTALL_STATUS_ACTION] tagged with [EXTRA_INSTALL_APP_ID] = [appId].
 * Runs synchronously on the caller's (background) thread; may throw on I/O failure.
 */
fun commitBundledInstall(ctx: Context, apk: File, appId: String) {
    val pi = ctx.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val sid = pi.createSession(params)
    pi.openSession(sid).use { session ->
        apk.inputStream().use { input ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                input.copyTo(out)
                session.fsync(out)
            }
        }
        val intent = Intent(INSTALL_STATUS_ACTION)
            .setPackage(ctx.packageName)
            .putExtra(EXTRA_INSTALL_APP_ID, appId)
        // FLAG_MUTABLE is API 31+; the system fills in the status extras. On < 31
        // PendingIntents are mutable by default, so no flag is needed there.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            ctx, sid, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
        session.commit(pending.intentSender)
    }
}

/** Opens the Play Store details page for [pkg] (falls back to the web store). */
fun openPlayStore(ctx: Context, pkg: String) {
    val newTask = Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                .setPackage(PLAY_STORE_PKG)
                .addFlags(newTask)
        )
    } catch (_: Throwable) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .addFlags(newTask)
            )
        } catch (_: Throwable) { }
    }
}

/** Polls until [pkg] appears or [timeoutMs] elapses. Returns final presence. */
suspend fun awaitPackageInstalled(
    pm: PackageManager,
    pkg: String,
    timeoutMs: Long,
    intervalMs: Long = 1000L
): Boolean {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
        if (isPackageInstalled(pm, pkg)) return true
        delay(intervalMs)
    }
    return isPackageInstalled(pm, pkg)
}
