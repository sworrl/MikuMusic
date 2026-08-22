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
        val sp = ctx.getSharedPreferences("m500_hardware_prefs", Context.MODE_PRIVATE)
        return sp.getBoolean("cpu_perf_enabled", false)
    }
}
