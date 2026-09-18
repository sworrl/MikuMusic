package com.miku.launcher.bpm

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Cross-app unlocks earned in the BPM game.
 *
 * WHY THIS EXISTS. Progression used to be entirely self-referential: you tapped to earn leeks, you
 * spent leeks on buildings that earned more leeks. Nothing you did in the game ever changed
 * anything outside the game, so there was no reason to play it. These unlocks hand out real,
 * existing cosmetics in MikuOS and in Miku Music.
 *
 * THE CONTRACT (the player app reads the same key):
 *  - One `Settings.Global` key, **miku_unlocks**, holding a JSON object:
 *        {"<unlock_id>": {"at": <epochMillis>, "src": "bpm"}}
 *  - The GAME IS THE ONLY WRITER. Any app may read it; nothing else writes it.
 *  - Ids are stable, lowercase and namespaced by what they affect. Once shipped, never rename:
 *    a renamed id silently re-locks something the player already earned.
 *  - Ids live HERE, in one place, and are referenced as `MikuUnlocks.SKIN_HONEY` etc. rather than
 *    typed as string literals at call sites.
 *
 * TWO RULES, both inherited from the scoring rules:
 *  1. **Only judged taps pay.** Every requirement below reads MikuBpmSeasonsEngine's lifetime
 *     stats, and those only ever advance on a tap measured against a real detected beat
 *     (see MikuBpmSeasonsEngine.recordTap's contract). A free tap can never buy a reward.
 *  2. **Idempotent.** [unlock] no-ops if the id is already present, so a re-grant cannot re-pay
 *     the leek bonus or re-fire the fanfare.
 *
 * A mirror copy is kept in SharedPreferences: if the Settings.Global write is ever refused, the
 * player's earned unlocks are still remembered locally rather than silently lost, and reads take
 * the union of both.
 */
object MikuUnlocks {
    private const val TAG = "MikuUnlocks"

    /** The single Settings.Global key both apps use. Do not change. */
    const val SETTINGS_KEY = "miku_unlocks"

    private const val MIRROR_PREFS = "miku_unlocks_mirror"
    private const val SRC = "bpm"

    // ---- Unlock ids -----------------------------------------------------------------------
    // Each one corresponds to something that ACTUALLY EXISTS in this codebase. An unlock that
    // grants nothing is the same fake-reward problem it is supposed to fix.

    /** Launcher wallpaper picker id "w6" (Neon Wave) — prefs miku_launcher_prefs/current_wallpaper_id. */
    const val OS_WALLPAPER_NEON_WAVE = "os.wallpaper.w6"

    /** Launcher wallpaper picker id "w7" (Cyber Space Hologram). */
    const val OS_WALLPAPER_CYBER_HOLOGRAM = "os.wallpaper.w7"

    /** MikuThemeRegistry theme id "cozy_cafe". */
    const val OS_THEME_COZY_CAFE = "os.theme.cozy_cafe"

    /** MikuThemeRegistry theme id "cyber_stage". */
    const val OS_THEME_CYBER_STAGE = "os.theme.cyber_stage"

    /** MikuTopBarTheme.AURORA — prefs miku_launcher_prefs/top_bar_theme. */
    const val OS_TOPBAR_AURORA = "os.topbar.aurora"

    /** MikuTopBarTheme.MIDNIGHT. */
    const val OS_TOPBAR_MIDNIGHT = "os.topbar.midnight"

    /** Miku Shaders preset ShaderPreset.NEGI_RAIN — prefs miku_player_prefs/viz_shader_preset. */
    const val PLAYER_VIZ_NEGI_RAIN = "player.viz.negi_rain"

    /** Miku Shaders preset ShaderPreset.VOCALOID_CIRCUIT. */
    const val PLAYER_VIZ_VOCALOID_CIRCUIT = "player.viz.vocaloid_circuit"

    /** projectM preset ProjectMPreset.VORTEX_CORE — prefs miku_player_prefs/projectm_preset. */
    const val PLAYER_PROJECTM_VORTEX_CORE = "player.projectm.vortex_core"

    /** Tape Mode cassette theme "SAKURA" — prefs miku_player_prefs/tape_theme. */
    const val PLAYER_TAPE_SAKURA = "player.tape.sakura"

    /** Tape Mode cassette theme "VAPORWAVE". */
    const val PLAYER_TAPE_VAPORWAVE = "player.tape.vaporwave"

    /** Tape Mode cassette theme "GLOWWORM". */
    const val PLAYER_TAPE_GLOWWORM = "player.tape.glowworm"

    /** BPM game skin HONEY_SWEET — gated by this module itself. */
    const val SKIN_HONEY_SWEET = "game.skin.honey_sweet"

    /** BPM game skin GOTHIC_DIVA. */
    const val SKIN_GOTHIC_DIVA = "game.skin.gothic_diva"

    /** BPM game skin SNOW_CRYSTAL. */
    const val SKIN_SNOW_CRYSTAL = "game.skin.snow_crystal"

    /** What a player has to do to earn an unlock, in terms the UI can print as progress. */
    enum class Goal(val label: String) {
        JUDGED_TAPS("judged taps"),
        MAX_COMBO("max combo"),
        PERFECT_HITS("PERFECT hits"),
        SEASON_POINTS("season points")
    }

    data class Reward(
        val id: String,
        val title: String,
        /** Where it shows up, printed verbatim in the UI so the reward is never vague. */
        val where: String,
        val icon: String,
        val goal: Goal,
        val amount: Int
    )

    /**
     * The ladder, in the order it is earned. Requirements alternate between volume (judged taps),
     * consistency (PERFECT hits) and nerve (max combo), so no single way of playing unlocks
     * everything and each type of player has a next thing to chase.
     */
    val ALL: List<Reward> = listOf(
        Reward(SKIN_HONEY_SWEET, "Honey Sweet skin", "BPM game · Skins", "🍯", Goal.JUDGED_TAPS, 200),
        Reward(PLAYER_TAPE_SAKURA, "SAKURA cassette", "Miku Music · Tape Mode", "📼", Goal.MAX_COMBO, 25),
        Reward(OS_WALLPAPER_NEON_WAVE, "Neon Wave wallpaper", "MikuOS · Wallpapers", "🖼️", Goal.JUDGED_TAPS, 500),
        Reward(PLAYER_VIZ_NEGI_RAIN, "Negi Rain visualiser", "Miku Music · Miku Shaders", "🌧️", Goal.PERFECT_HITS, 250),
        Reward(SKIN_GOTHIC_DIVA, "Gothic Lolita skin", "BPM game · Skins", "🖤", Goal.MAX_COMBO, 50),
        Reward(OS_TOPBAR_AURORA, "Aurora top bar", "MikuOS · Top bar theme", "🌈", Goal.JUDGED_TAPS, 1_500),
        Reward(PLAYER_TAPE_VAPORWAVE, "VAPORWAVE cassette", "Miku Music · Tape Mode", "📼", Goal.MAX_COMBO, 75),
        Reward(PLAYER_PROJECTM_VORTEX_CORE, "Vortex Core preset", "Miku Music · projectM", "🌀", Goal.PERFECT_HITS, 1_000),
        Reward(OS_THEME_COZY_CAFE, "Cozy Cafe theme", "MikuOS · Themes", "☕", Goal.JUDGED_TAPS, 3_000),
        Reward(SKIN_SNOW_CRYSTAL, "Snow Crystal skin", "BPM game · Skins", "❄️", Goal.MAX_COMBO, 100),
        Reward(PLAYER_VIZ_VOCALOID_CIRCUIT, "Vocaloid Circuit visualiser", "Miku Music · Miku Shaders", "🔌", Goal.PERFECT_HITS, 2_500),
        Reward(OS_WALLPAPER_CYBER_HOLOGRAM, "Cyber Space Hologram", "MikuOS · Wallpapers", "🛸", Goal.JUDGED_TAPS, 5_000),
        Reward(PLAYER_TAPE_GLOWWORM, "GLOWWORM cassette", "Miku Music · Tape Mode", "📼", Goal.MAX_COMBO, 150),
        Reward(OS_THEME_CYBER_STAGE, "Cyber Stage theme", "MikuOS · Themes", "🎆", Goal.JUDGED_TAPS, 10_000),
        Reward(OS_TOPBAR_MIDNIGHT, "Midnight top bar", "MikuOS · Top bar theme", "🌙", Goal.SEASON_POINTS, 50_000)
    )

    fun rewardFor(id: String): Reward? = ALL.firstOrNull { it.id == id }

    // ---- storage ---------------------------------------------------------------------------

    private fun readJson(ctx: Context): JSONObject {
        val raw = try {
            Settings.Global.getString(ctx.contentResolver, SETTINGS_KEY)
        } catch (_: Throwable) { null }
        val obj = if (raw.isNullOrBlank()) JSONObject() else
            try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
        // Union with the local mirror, so a refused Settings.Global write never loses an unlock.
        try {
            val mirror = ctx.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .getString(SETTINGS_KEY, null)
            if (!mirror.isNullOrBlank()) {
                val m = JSONObject(mirror)
                val it = m.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    if (!obj.has(k)) obj.put(k, m.get(k))
                }
            }
        } catch (_: Throwable) {}
        return obj
    }

    private fun write(ctx: Context, obj: JSONObject) {
        val s = obj.toString()
        try {
            ctx.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .edit().putString(SETTINGS_KEY, s).apply()
        } catch (_: Throwable) {}
        try {
            Settings.Global.putString(ctx.contentResolver, SETTINGS_KEY, s)
        } catch (t: Throwable) {
            // Say it out loud rather than pretending it worked — the mirror above still holds it,
            // but the player app reads Settings.Global and will not see this unlock until the
            // write succeeds. (Assumed-success is exactly the bug class this project keeps hitting.)
            Log.w(TAG, "Settings.Global write refused; unlock kept in local mirror only", t)
        }
    }

    fun isUnlocked(ctx: Context, id: String): Boolean = readJson(ctx).has(id)

    fun unlockedIds(ctx: Context): Set<String> {
        val obj = readJson(ctx)
        val out = HashSet<String>()
        val it = obj.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    /** Grants [id]. Returns true ONLY on the first grant, so callers can pay a bonus exactly once. */
    fun unlock(ctx: Context, id: String): Boolean {
        val obj = readJson(ctx)
        if (obj.has(id)) return false
        obj.put(id, JSONObject().put("at", System.currentTimeMillis()).put("src", SRC))
        write(ctx, obj)
        return true
    }

    // ---- earning ---------------------------------------------------------------------------

    private fun progressToward(goal: Goal): Long {
        val life = MikuBpmSeasonsEngine.lifetime.value
        return when (goal) {
            Goal.JUDGED_TAPS -> life.totalTaps
            Goal.MAX_COMBO -> life.maxCombo.toLong()
            Goal.PERFECT_HITS -> life.perfectHits
            Goal.SEASON_POINTS -> MikuBpmSeasonsEngine.currentSeason.value.seasonRankPoints
        }
    }

    /** How far along this reward is, e.g. "312 / 500 judged taps". */
    fun progressLabel(reward: Reward): String =
        "${progressToward(reward.goal)} / ${reward.amount} ${reward.goal.label}"

    fun progressFraction(reward: Reward): Float =
        (progressToward(reward.goal).toFloat() / reward.amount.toFloat()).coerceIn(0f, 1f)

    /** The next thing to chase — a reward the player cannot see coming does not motivate. */
    fun nextLocked(ctx: Context): Reward? {
        val have = unlockedIds(ctx)
        return ALL.firstOrNull { it.id !in have }
    }

    /**
     * Grant everything whose requirement is now met. Call after a JUDGED tap (never after a free
     * one — although it would grant nothing anyway, since free taps do not move lifetime stats).
     * Returns only the rewards granted by THIS call.
     */
    fun evaluate(ctx: Context): List<Reward> {
        val have = unlockedIds(ctx)
        val granted = ArrayList<Reward>()
        for (r in ALL) {
            if (r.id in have) continue
            if (progressToward(r.goal) >= r.amount) {
                if (unlock(ctx, r.id)) granted.add(r)
            }
        }
        return granted
    }
}
