package com.miku.player

/**
 * Launch-crash forensics rig (2026-09-10 "Unsupported concurrent change during composition"
 * crash-loop). Two tools:
 *  - install(): logs every snapshot apply performed OFF the main thread with the state objects'
 *    class@identity - the "modified outside composition" half of the crash names itself.
 *  - off(key): Settings.Global-gated kill switches (miku_dbg_off_<key> = 1) so launch-time
 *    subsystems can be bisected live without rebuilding. Flags are read once per process -
 *    am force-stop after flipping.
 */
object MikuDbg {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun off(ctx: android.content.Context, key: String): Boolean = cache.getOrPut(key) {
        try {
            android.provider.Settings.Global.getInt(ctx.contentResolver, "miku_dbg_off_$key", 0) == 1
        } catch (_: Throwable) { false }
    }

    @Volatile private var installed = false
    // Retain the handle: registerApplyObserver returns an ObserverHandle that stops firing if
    // it is garbage-collected (the 2026-09-10 first attempt logged nothing because of this).
    @Suppress("unused") private var applyHandle: Any? = null
    fun install(ctx: android.content.Context) {
        if (installed) return
        installed = true
        if (off(ctx, "snaplog")) return
        try {
            applyHandle = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { set, _ ->
                if (set.isEmpty()) return@registerApplyObserver
                // Log every apply carrying a Compose state object, with the writer thread + a
                // trimmed stack, so the "modified outside composition" writer names itself.
                val names = set.take(6).joinToString {
                    it.javaClass.name.substringAfterLast('.') + "@" + Integer.toHexString(System.identityHashCode(it))
                }
                val where = Thread.currentThread().stackTrace
                    .drop(3).take(6)
                    .filter { it.className.startsWith("com.miku") }
                    .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
                android.util.Log.e("SnapApply", "[" + Thread.currentThread().name + "] " + names +
                    (if (where.isNotBlank()) "  via " + where else ""))
            }
        } catch (_: Throwable) {}
    }
}
