package com.miku.player

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Single source of truth for "is the network ingress engine allowed to run".
 *
 * Backed by `Settings.Global miku_ingest_enabled` (default **0 = OFF**) so the MikuOS launcher's
 * quick-settings tile, this app, and the daemon all read the SAME switch. When OFF, nothing in
 * this app touches the network for library purposes — no m500d discovery, no rsync transceiver,
 * no daemon sync triggers — but local SD-card scanning (manual, periodic, and the launcher's
 * Force Scan broadcast) keeps working exactly as before.
 */
object MikuIngestGate {
    const val KEY = "miku_ingest_enabled"
    private val observers = HashMap<Context, ContentObserver>()

    fun isEnabled(ctx: Context): Boolean {
        val on = runCatching { Settings.Global.getInt(ctx.contentResolver, KEY, 0) == 1 }.getOrDefault(false)
        MikuSyncTransceiver.ingestEnabledFlag = on
        return on
    }

    fun setEnabled(ctx: Context, enabled: Boolean): Boolean {
        val v = if (enabled) 1 else 0
        val ok = runCatching { Settings.Global.putInt(ctx.contentResolver, KEY, v) }.getOrDefault(false)
        if (!ok) RootShell.execFast("settings put global $KEY $v")
        MikuSyncTransceiver.ingestEnabledFlag = enabled
        return ok
    }

    /** Watch the Global for live flips (the launcher tile) — [onChange] runs on the main thread
     *  only when the value actually changed. One observer per Context; [unobserve] to release. */
    fun observe(ctx: Context, onChange: (Boolean) -> Unit) {
        if (observers.containsKey(ctx)) return
        var last = isEnabled(ctx)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val now = isEnabled(ctx)
                if (now != last) { last = now; onChange(now) }
            }
        }
        runCatching { ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY), false, obs) }
        observers[ctx] = obs
    }

    fun unobserve(ctx: Context) {
        observers.remove(ctx)?.let { runCatching { ctx.contentResolver.unregisterContentObserver(it) } }
    }
}
