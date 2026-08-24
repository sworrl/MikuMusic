package com.miku.launcher.bpm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Beat-match accuracy tier for the rhythm game, seasons engine, and incremental clicker. */
enum class HitAccuracy { PERFECT, GOOD, MISS }

/**
 * Hatsune Miku BPM Tap Observatory Lifetime & Monthly Seasons Engine.
 *
 * Tracks:
 * - Lifetime Cumulative Rhythm Metrics (Total Taps, Score, Max Combo, Perfect Ratio).
 * - Monthly Seasons (e.g. "Season 8 · Neon Mirai", "Season 9 · Sakura Eclipse").
 * - Rank Tiers: BRONZE ➔ SILVER ➔ GOLD ➔ PLATINUM ➔ DIAMOND ➔ DIVA MASTER.
 */
object MikuBpmSeasonsEngine {
    private const val PREFS_NAME = "miku_bpm_seasons_prefs"
    private const val KEY_LIFETIME = "lifetime_stats_json"
    private const val KEY_SEASONS = "monthly_seasons_json"

    enum class SeasonRank(val title: String, val badge: String, val minPoints: Long, val colorHex: Long) {
        BRONZE("Bronze Diva", "🥉", 0L, 0xFFCD7F32),
        SILVER("Silver Vocalist", "🥈", 1_000L, 0xFFC0C0C0),
        GOLD("Gold Virtuoso", "🥇", 5_000L, 0xFFFFD700),
        PLATINUM("Platinum Synth", "💠", 15_000L, 0xFF00E5FF),
        DIAMOND("Diamond Producer", "💎", 50_000L, 0xFF9933FF),
        DIVA_MASTER("Diva Master 39", "👑", 150_000L, 0xFFFF2277)
    }

    data class LifetimeStats(
        val totalTaps: Long = 0L,
        val totalScore: Long = 0L,
        val maxCombo: Int = 0,
        val perfectHits: Long = 0L,
        val goodHits: Long = 0L,
        val missHits: Long = 0L,
        val highestBpmLocked: Float = 0f
    )

    data class SeasonStats(
        val seasonKey: String = "",       // "2026-08"
        val seasonName: String = "",      // "Season 8 · Neon Mirai"
        val seasonTaps: Long = 0L,
        val seasonScore: Long = 0L,
        val seasonBestCombo: Int = 0,
        val seasonRankPoints: Long = 0L,
        val rank: SeasonRank = SeasonRank.BRONZE
    )

    private val _lifetime = MutableStateFlow(LifetimeStats())
    val lifetime: StateFlow<LifetimeStats> = _lifetime.asStateFlow()

    private val _currentSeason = MutableStateFlow(SeasonStats())
    val currentSeason: StateFlow<SeasonStats> = _currentSeason.asStateFlow()

    private var prefs: SharedPreferences? = null

    private val seasonNames = listOf(
        "Digital Genesis",      // Jan
        "Cyber Valentine",      // Feb
        "Sakura Miracle",       // Mar
        "Spring Bloom",         // Apr
        "Electronic Wave",      // May
        "Magical Summer",       // Jun
        "Starlight Sonata",     // Jul
        "Neon Mirai 39",        // Aug
        "Autumn Symphony",      // Sep
        "Midnight Pumpkin",     // Oct
        "Frostbite Echo",       // Nov
        "Holy Diva Blizzard"    // Dec
    )

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadStats()
        }
    }

    private fun loadStats() {
        val p = prefs ?: return
        val lifeRaw = p.getString(KEY_LIFETIME, null)
        if (lifeRaw != null) {
            try {
                val obj = JSONObject(lifeRaw)
                _lifetime.value = LifetimeStats(
                    totalTaps = obj.optLong("taps", 0L),
                    totalScore = obj.optLong("score", 0L),
                    maxCombo = obj.optInt("combo", 0),
                    perfectHits = obj.optLong("perfect", 0L),
                    goodHits = obj.optLong("good", 0L),
                    missHits = obj.optLong("miss", 0L),
                    highestBpmLocked = obj.optDouble("highBpm", 0.0).toFloat()
                )
            } catch (_: Throwable) {}
        }

        // Current Month Key
        val cal = Calendar.getInstance()
        val key = SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
        val monthIdx = cal.get(Calendar.MONTH).coerceIn(0, 11)
        val monthNum = monthIdx + 1
        val seasonTitle = "Season $monthNum · ${seasonNames[monthIdx]}"

        val seasonRaw = p.getString("${KEY_SEASONS}_$key", null)
        if (seasonRaw != null) {
            try {
                val obj = JSONObject(seasonRaw)
                val pts = obj.optLong("pts", 0L)
                _currentSeason.value = SeasonStats(
                    seasonKey = key,
                    seasonName = seasonTitle,
                    seasonTaps = obj.optLong("taps", 0L),
                    seasonScore = obj.optLong("score", 0L),
                    seasonBestCombo = obj.optInt("combo", 0),
                    seasonRankPoints = pts,
                    rank = computeRank(pts)
                )
            } catch (_: Throwable) {}
        } else {
            _currentSeason.value = SeasonStats(
                seasonKey = key,
                seasonName = seasonTitle,
                seasonTaps = 0L,
                seasonScore = 0L,
                seasonBestCombo = 0,
                seasonRankPoints = 0L,
                rank = SeasonRank.BRONZE
            )
        }
    }

    private fun computeRank(points: Long): SeasonRank = when {
        points >= SeasonRank.DIVA_MASTER.minPoints -> SeasonRank.DIVA_MASTER
        points >= SeasonRank.DIAMOND.minPoints -> SeasonRank.DIAMOND
        points >= SeasonRank.PLATINUM.minPoints -> SeasonRank.PLATINUM
        points >= SeasonRank.GOLD.minPoints -> SeasonRank.GOLD
        points >= SeasonRank.SILVER.minPoints -> SeasonRank.SILVER
        else -> SeasonRank.BRONZE
    }

    /**
     * Records a rhythm tap event and updates both Lifetime and Monthly Season stats.
     */
    fun recordTap(
        accuracy: HitAccuracy,
        currentCombo: Int,
        scoreEarned: Long,
        currentBpm: Float
    ) {
        val ptsGain = when (accuracy) {
            HitAccuracy.PERFECT -> 15L + (currentCombo * 2L)
            HitAccuracy.GOOD -> 8L + currentCombo
            HitAccuracy.MISS -> 1L
        }

        // 1. Update Lifetime
        val curLife = _lifetime.value
        val newLife = curLife.copy(
            totalTaps = curLife.totalTaps + 1,
            totalScore = curLife.totalScore + scoreEarned,
            maxCombo = maxOf(curLife.maxCombo, currentCombo),
            perfectHits = curLife.perfectHits + if (accuracy == HitAccuracy.PERFECT) 1 else 0,
            goodHits = curLife.goodHits + if (accuracy == HitAccuracy.GOOD) 1 else 0,
            missHits = curLife.missHits + if (accuracy == HitAccuracy.MISS) 1 else 0,
            highestBpmLocked = maxOf(curLife.highestBpmLocked, currentBpm)
        )
        _lifetime.value = newLife

        // 2. Update Current Season
        val curSeason = _currentSeason.value
        val newPts = curSeason.seasonRankPoints + ptsGain
        val newRank = computeRank(newPts)
        if (newRank != curSeason.rank) {
            com.miku.launcher.audio.MikuSeasonalAudioEngine.playLevelUpFanfare()
        }
        val newSeason = curSeason.copy(
            seasonTaps = curSeason.seasonTaps + 1,
            seasonScore = curSeason.seasonScore + scoreEarned,
            seasonBestCombo = maxOf(curSeason.seasonBestCombo, currentCombo),
            seasonRankPoints = newPts,
            rank = newRank
        )
        _currentSeason.value = newSeason

        // Persist
        saveStats(newLife, newSeason)
    }

    private fun saveStats(life: LifetimeStats, season: SeasonStats) {
        val p = prefs ?: return
        try {
            val lifeObj = JSONObject().apply {
                put("taps", life.totalTaps)
                put("score", life.totalScore)
                put("combo", life.maxCombo)
                put("perfect", life.perfectHits)
                put("good", life.goodHits)
                put("miss", life.missHits)
                put("highBpm", life.highestBpmLocked.toDouble())
            }
            val seasonObj = JSONObject().apply {
                put("taps", season.seasonTaps)
                put("score", season.seasonScore)
                put("combo", season.seasonBestCombo)
                put("pts", season.seasonRankPoints)
            }
            p.edit()
                .putString(KEY_LIFETIME, lifeObj.toString())
                .putString("${KEY_SEASONS}_${season.seasonKey}", seasonObj.toString())
                .apply()
        } catch (_: Throwable) {}
    }
}
