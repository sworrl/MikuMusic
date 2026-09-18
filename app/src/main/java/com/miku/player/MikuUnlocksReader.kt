package com.miku.player

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * READ-ONLY view of the cross-app unlocks earned in the BPM rhythm game.
 *
 * The game (in the launcher, `com.miku.launcher.bpm.MikuUnlocks`) is the ONLY writer. It keeps a
 * JSON object in `Settings.Global` under `miku_unlocks`:
 *
 *     {"<unlock_id>": {"at": <epochMillis>, "src": "bpm"}}
 *
 * Miku Music only reads it, to decide which cosmetics are available. The ids are copied here
 * verbatim and must never be renamed on either side: a renamed id silently re-locks something the
 * player already earned.
 *
 * FAILURE POSTURE: if the read throws or the value is missing, NOTHING is treated as unlocked.
 * Failing open would hand out every reward for free and make the game pointless; failing closed is
 * recoverable (play a few more taps, or the game re-grants on its next evaluate()).
 */
object MikuUnlocksReader {
    private const val SETTINGS_KEY = "miku_unlocks"

    const val PLAYER_VIZ_NEGI_RAIN = "player.viz.negi_rain"
    const val PLAYER_VIZ_VOCALOID_CIRCUIT = "player.viz.vocaloid_circuit"
    const val PLAYER_PROJECTM_VORTEX_CORE = "player.projectm.vortex_core"
    const val PLAYER_TAPE_SAKURA = "player.tape.sakura"
    const val PLAYER_TAPE_VAPORWAVE = "player.tape.vaporwave"
    const val PLAYER_TAPE_GLOWWORM = "player.tape.glowworm"

    /** Cheap in-memory cache. The game writes rarely and a Settings read per grid cell is silly. */
    @Volatile private var cached: Set<String>? = null
    @Volatile private var cachedAt = 0L

    fun unlockedIds(ctx: Context): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAt < 5_000L) return it }
        val out = HashSet<String>()
        try {
            val raw = Settings.Global.getString(ctx.contentResolver, SETTINGS_KEY)
            if (!raw.isNullOrBlank()) {
                val o = JSONObject(raw)
                val it = o.keys()
                while (it.hasNext()) out.add(it.next())
            }
        } catch (_: Throwable) {
            // Fail closed. See the class doc.
        }
        cached = out; cachedAt = now
        return out
    }

    fun isUnlocked(ctx: Context, id: String): Boolean = id in unlockedIds(ctx)

    /** Forget the cache so a grant made while Miku Music is open shows up on the next picker open. */
    fun invalidate() { cached = null }

    /**
     * Tape theme index -> unlock id, for the indices that are earned. Indices are the position in
     * `TAPE_THEMES`; an unlisted index is always available. Keep this keyed by index rather than by
     * filtering the list, because the saved preference is a raw ordinal and removing an entry would
     * shift every later theme.
     */
    val TAPE_THEME_GATES: Map<Int, String> = mapOf(
        15 to PLAYER_TAPE_SAKURA,
        16 to PLAYER_TAPE_VAPORWAVE,
        17 to PLAYER_TAPE_GLOWWORM
    )

    /** `ShaderPreset` ordinal -> unlock id. Same raw-ordinal reasoning as the tape gates. */
    val SHADER_PRESET_GATES: Map<Int, String> = mapOf(
        3 to PLAYER_VIZ_NEGI_RAIN,
        4 to PLAYER_VIZ_VOCALOID_CIRCUIT
    )

    /** `ProjectMPreset` ordinal -> unlock id. */
    val PROJECTM_PRESET_GATES: Map<Int, String> = mapOf(
        3 to PLAYER_PROJECTM_VORTEX_CORE
    )

    /** True when [index] is gated and not yet earned. */
    fun locked(ctx: Context, gates: Map<Int, String>, index: Int): Boolean {
        val id = gates[index] ?: return false
        return !isUnlocked(ctx, id)
    }

    /** First index at or after [from] that is not locked, wrapping. Used by the cycle buttons. */
    fun nextUnlocked(ctx: Context, gates: Map<Int, String>, from: Int, size: Int): Int {
        if (size <= 0) return 0
        for (step in 1..size) {
            val cand = (from + step) % size
            if (!locked(ctx, gates, cand)) return cand
        }
        return (from + 1) % size
    }
}
