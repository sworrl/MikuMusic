package com.m500.hardware

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CpuPerformance {
    private const val TAG = "CpuPerformance"

    suspend fun setEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        if (!RootShell.isAvailable()) return@withContext
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("cpu_perf_enabled", enabled).apply()

        if (enabled) {
            val script = """
                for gov in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
                    [ -f "${'$'}gov" ] && echo performance > "${'$'}gov"
                done
                setprop vendor.audio.hiby.cpu_tuner performance
            """.trimIndent()
            RootShell.exec(script)
            Log.i(TAG, "CPU Governor: PERFORMANCE")
        } else {
            val script = """
                for gov in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
                    [ -f "${'$'}gov" ] && echo schedutil > "${'$'}gov"
                done
                setprop vendor.audio.hiby.cpu_tuner schedutil
            """.trimIndent()
            RootShell.exec(script)
            Log.i(TAG, "CPU Governor: SCHEDUTIL")
        }
    }

    fun isEnabled(ctx: Context): Boolean {
        // Real governor, not just the saved pref (which lied when the root write failed).
        val gov = try { java.io.File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim() } catch (_: Throwable) { "" }
        if (gov.isNotEmpty()) return gov == "performance"
        return ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE).getBoolean("cpu_perf_enabled", false)
    }
}
