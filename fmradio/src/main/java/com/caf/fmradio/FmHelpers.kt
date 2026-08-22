package com.caf.fmradio

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal helpers so the FM app (com.caf.fmradio) is self-contained — it can't
 * depend on the com.miku.player app module, so the few utilities it used are
 * reproduced here (RootShell, CrashSentinel, MikuBackButton).
 */

/** Runs short shell commands. As a platform-signed system app com.caf.fmradio has
 *  the SELinux domain to poke vendor props directly — no `su` needed; if a call is
 *  denied it's harmless (the tuner path doesn't require it). */
object RootShell {
    fun execFast(cmd: String): String = try {
        val p = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (_: Throwable) { "" }
}

/** Installs a last-resort uncaught-exception guard so a tuner glitch closes cleanly
 *  instead of hard-crashing the process. */
object CrashSentinel {
    fun install(activity: Activity) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            android.util.Log.e("FmRadio", "uncaught in ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }
}

@Composable
fun MikuBackButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp)
            .clip(CircleShape)
            .background(Color(0x2200E5FF))
            .border(1.dp, MikuCyan, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("‹", color = MikuCyan, fontSize = 24.sp)
    }
}
