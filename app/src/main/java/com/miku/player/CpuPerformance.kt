package com.miku.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root-gated CPU governor override — a real step up from `Window.setSustainedPerformanceMode`
 * (already used elsewhere in MainActivity, an official Android *hint* the OS may or may not honor)
 * to actually pinning every core's cpufreq governor to "performance" (max clock, no scaling-down)
 * while enabled. Same pattern as ScreenOffHelper: stash the REAL per-core governor before touching
 * anything, restore it faithfully rather than guessing a default back, and restore defensively
 * from multiple places so a crash/kill mid-session can't strand the device pinned at max clock
 * (real battery/thermal cost if left stuck, on a device already thermally close to the edge under
 * load per the hardware audit).
 *
 * Root-gated, always optional — see RootShell's doc comment. `setSustainedPerformanceMode` keeps
 * working exactly as before regardless of root/this feature; this is purely additive on top of it
 * when the user has root AND has opted in via Settings — never required, never assumed.
 */
object CpuPerformance {
    private const val PERF_GOVERNOR = "performance"

    suspend fun isSupported(): Boolean = withContext(Dispatchers.IO) {
        RootShell.isAvailable() && corePaths().isNotEmpty()
    }

    /** Every cpuN/cpufreq/scaling_governor path currently on the device — read fresh each time
     *  rather than cached, since core count/paths are fixed per-boot but this is cheap either way. */
    private fun corePaths(): List<String> {
        val out = RootShell.execOut("ls -d /sys/devices/system/cpu/cpu[0-9]*/cpufreq") ?: return emptyList()
        return out.lineSequence().map { "$it/scaling_governor" }.toList()
    }

    suspend fun setEnabled(ctx: Context, on: Boolean) = withContext(Dispatchers.IO) {
        if (!RootShell.isAvailable()) return@withContext
        PlayerPreferences.saveCpuPerfEnabled(ctx, on)
        if (on) applyPerformance(ctx) else restoreStock(ctx)
    }

    /** Call defensively (Activity onResume/onPause/onDestroy) — cheap no-op if nothing to do,
     *  self-heals a stranded "performance" pin from an earlier crashed/killed session the same way
     *  ScreenOffHelper does for the screen timeout. */
    suspend fun applyIfEnabled(ctx: Context) = withContext(Dispatchers.IO) {
        if (RootShell.isAvailable() && PlayerPreferences.loadCpuPerfEnabled(ctx)) applyPerformance(ctx)
    }

    suspend fun restoreIfStranded(ctx: Context) = withContext(Dispatchers.IO) {
        if (RootShell.isAvailable() && !PlayerPreferences.loadCpuPerfEnabled(ctx)) restoreStock(ctx)
    }

    /** Always releases the pin (backgrounding/pausing the app) WITHOUT touching the persisted
     *  toggle — [applyIfEnabled] reapplies it on the next resume if the user still has it on.
     *  Pinning every core at max clock while the app isn't even in the foreground would just be
     *  pure battery waste with zero benefit. */
    suspend fun onBackground(ctx: Context) = withContext(Dispatchers.IO) {
        if (RootShell.isAvailable()) restoreStock(ctx)
    }

    private fun applyPerformance(ctx: Context) {
        val paths = corePaths()
        if (paths.isEmpty()) return
        // Stash real governors once — never overwrite an existing stash with "performance" (that
        // would corrupt the record on a second consecutive enable, e.g. after a quick toggle).
        if (PlayerPreferences.loadStashedGovernors(ctx) == null) {
            val current = paths.associateWith { RootShell.execOut("cat $it")?.trim().orEmpty() }
            if (current.values.any { it.isNotBlank() }) PlayerPreferences.saveStashedGovernors(ctx, current)
        }
        val available = RootShell.execOut("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors").orEmpty()
        if (!available.contains(PERF_GOVERNOR)) return   // this kernel doesn't offer it — no-op, not a crash
        paths.forEach { RootShell.exec("echo $PERF_GOVERNOR > $it") }
    }

    private fun restoreStock(ctx: Context) {
        val stashed = PlayerPreferences.loadStashedGovernors(ctx) ?: return
        stashed.forEach { (path, governor) -> if (governor.isNotBlank()) RootShell.exec("echo $governor > $path") }
        PlayerPreferences.clearStashedGovernors(ctx)
    }
}
