package com.miku.launcher.bpm

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.pow
import kotlin.random.Random

/**
 * Miku Beat Clicker — An Addictive Incremental Rhythm Economy (Cookie Clicker for Beat Tappers).
 *
 * THE ONE RULE: [tap] is for taps that were JUDGED against a real detected beat. A tap with no
 * beat reference goes to [freeTap], which pays the idle-game base click and nothing else — it
 * touches no accuracy statistic, no combo, no fever, no season rank. Every number this object
 * exposes for display is therefore backed by a real measurement or is honestly absent (-1/"—").
 *
 * Game Theory Mechanics:
 * - Active Tap Yield: 1.0 base + 3% of passive BPS.
 * - Accuracy Scaler: the five graded tiers in MikuRhythmTiming.kt (OK 1.2x ... PERFECT 6x),
 *   plus a 9% CRIT chance (10x).
 * - Combo Compounding: a TIERED multiplier with visible breakpoints (x1.5 at 8, x2 at 16,
 *   x3 at 32, x4 at 64, x5 at 128, x6 at 256) instead of a flat per-tap trickle — a player can
 *   see the next rung and decide to reach for it.
 * - Combo Shields: earn one every 20 consecutive GREAT-or-better hits (max 3); a shield eats
 *   one MISS on a combo of 10+ instead of resetting it. Near-miss tension with a mercy rule.
 * - Adaptive difficulty: windows widen for a struggling player and tighten as they land hits,
 *   and the tighter tiers pay more (see MikuRhythmTiming.RhythmTier).
 * - Pentatonic Melodic Plucks: Consecutive combo hits play ascending notes on a synthesized scale!
 * - Fever Energy Meter: Taps charge the Fever Meter (0-100%). At 100%, automatic 15s 77x SUPER FEVER!
 * - Particle Bursts: Every tap explodes with colorful stars, hearts, and musical notes.
 * - Performance Grading: Real-time SSS+ / SS / S / A arcade grade rating.
 * - Golden Leek Spawns: Random high-value sparkling target triggers 77x FEVER FRENZY!
 * - Exponential Tiered Buildings: Standard Cookie Clicker 1.15^n cost scaling.
 * - Offline Earnings: Welcome-back cache calculation.
 */
object MikuBeatClickerEngine {
    private const val PREFS_NAME = "miku_beat_clicker_prefs"
    private const val KEY_LEEKS = "leeks"
    private const val KEY_TOTAL_EARNED = "total_earned"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"

    data class Building(
        val id: String,
        val name: String,
        val icon: String,
        val baseCost: Double,
        val baseBps: Double,
        val owned: Int,
        val description: String
    ) {
        val currentCost: Double get() = baseCost * (1.15.pow(owned.toDouble()))
        val totalBps: Double get() = baseBps * owned
    }

    data class FloatingText(
        val id: Long,
        val text: String,
        val x: Float,
        val y: Float,
        val isCrit: Boolean,
        val isFever: Boolean
    )

    data class JuiceParticle(
        val id: Long,
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val emoji: String,
        val colorHex: Long,
        val sizeDp: Float
    )

    data class SkillUpgrade(
        val id: String,
        val name: String,
        val icon: String,
        val baseCost: Double,
        val level: Int,
        val maxLevel: Int,
        val description: String
    ) {
        val currentCost: Double get() = baseCost * (1.6.pow(level.toDouble()))
    }

    /** Silhouette of the beat node itself — the single most legible difference between skins. */
    enum class NodeShape { ORB, DIAMOND, HEX, STAR, SNOWFLAKE }

    /** Idle background treatment drawn behind everything. */
    enum class AmbientStyle { PETALS, HEX_RAIN, HONEY_BUBBLES, DAMASK_VEIL, SNOW_DRIFT }

    /** How a tap burst moves: gravity, speed, swirl, rise. */
    enum class BurstStyle { PETAL_FALL, SPARK_SHOT, BUBBLE_RISE, RIBBON_SWIRL, CRYSTAL_DRIFT }

    /** What a PERFECT does to the screen. */
    enum class HitEffect { RING, SHARD, SPLASH, VELVET_PULSE, FROST_CRACK }

    /**
     * Aesthetic skins.
     *
     * These used to differ ONLY by colour — same circle, same hearts drifting behind it, same
     * emoji burst, same type — so nobody could tell them apart and "unlocking" one meant nothing.
     * Each skin now changes four things a player sees at a glance: the SHAPE of the beat node,
     * the AMBIENT background, how the tap BURST moves (and which glyphs it throws), and the
     * hit EFFECT on a PERFECT — plus its own judgment typography.
     */
    enum class BpmSkin(
        val displayName: String,
        val icon: String,
        val primaryColor: Long,
        val accentColor: Long,
        val glowColor: Long,
        val patternName: String,
        val nodeShape: NodeShape,
        val ambient: AmbientStyle,
        val burst: BurstStyle,
        val hitEffect: HitEffect,
        /** Judgment text: monospaced arcade caps vs soft lower-case. */
        val judgmentArcade: Boolean,
        val judgmentSpacing: Float,
        val blurb: String
    ) {
        SAKURA_DREAM("Sakura Dream", "🌸", 0xFFFF85B3, 0xFF39C5BB, 0xFFFFB5D5, "sakura_lattice",
            NodeShape.ORB, AmbientStyle.PETALS, BurstStyle.PETAL_FALL, HitEffect.RING,
            false, 0f, "Round orb · petals fall and settle · soft ring"),
        CYBER_MIRAI("Cyber Mirai", "🌟", 0xFF00E5FF, 0xFF9D4EDD, 0xFF7000FF, "hex_matrix",
            NodeShape.HEX, AmbientStyle.HEX_RAIN, BurstStyle.SPARK_SHOT, HitEffect.SHARD,
            true, 1.5f, "Hex node · data rain · sparks shoot out hard"),
        HONEY_SWEET("Honey Sweet", "🍯", 0xFFFFD166, 0xFFFF9F1C, 0xFFFFE49E, "honeycomb",
            NodeShape.DIAMOND, AmbientStyle.HONEY_BUBBLES, BurstStyle.BUBBLE_RISE, HitEffect.SPLASH,
            false, 0f, "Diamond node · bubbles rise slowly · syrup splash"),
        GOTHIC_DIVA("Gothic Lolita", "🖤", 0xFFFF2277, 0xFF7B2CBF, 0xFFFF5599, "velvet_damask",
            NodeShape.STAR, AmbientStyle.DAMASK_VEIL, BurstStyle.RIBBON_SWIRL, HitEffect.VELVET_PULSE,
            true, 2.5f, "Star node · damask veil · ribbons swirl · velvet pulse"),
        SNOW_CRYSTAL("Snow Crystal", "❄️", 0xFF7FE6DE, 0xFF48CAE4, 0xFFE0F7FA, "frost_shimmer",
            NodeShape.SNOWFLAKE, AmbientStyle.SNOW_DRIFT, BurstStyle.CRYSTAL_DRIFT, HitEffect.FROST_CRACK,
            true, 1.0f, "Snowflake node · drifting snow · frost cracks out");

        /** Glyphs a tap burst throws — a different alphabet per skin, not a different tint. */
        val burstGlyphs: List<String>
            get() = when (this) {
                SAKURA_DREAM -> listOf("🌸", "💮", "🌷", "💗")
                CYBER_MIRAI -> listOf("✦", "⚡", "🔷", "💠")
                HONEY_SWEET -> listOf("🍯", "🐝", "🟡", "🍮")
                GOTHIC_DIVA -> listOf("🖤", "🥀", "🦇", "♠")
                SNOW_CRYSTAL -> listOf("❄", "💎", "✳", "✨")
            }

        /** Particle tints, taken from the skin's own palette instead of one shared rainbow. */
        val burstColors: List<Long>
            get() = listOf(primaryColor, accentColor, glowColor)

        /**
         * The unlock that gates this skin, or null if it is available from the start. Two skins
         * are free so the selector is never an empty shop; the other three are earned, which is
         * what makes the skin tab a reason to keep playing instead of a colour menu.
         */
        val unlockId: String?
            get() = when (this) {
                SAKURA_DREAM, CYBER_MIRAI -> null
                HONEY_SWEET -> MikuUnlocks.SKIN_HONEY_SWEET
                GOTHIC_DIVA -> MikuUnlocks.SKIN_GOTHIC_DIVA
                SNOW_CRYSTAL -> MikuUnlocks.SKIN_SNOW_CRYSTAL
            }

        fun isAvailable(ctx: Context): Boolean {
            val id = unlockId ?: return true
            return MikuUnlocks.isUnlocked(ctx, id)
        }
    }

    data class Achievement(
        val id: String,
        val title: String,
        val desc: String,
        val icon: String,
        val rewardLeeks: Double,
        val isUnlocked: Boolean
    )

    /**
     * Today's Setlist — three small, legible goals that reset at midnight.
     *
     * This is the "reason to come back" and the "reason to start" the session shape was missing:
     * an idle economy alone gives a player nothing to aim at in the next five minutes. Every
     * counter here is fed ONLY by judged taps, so the accuracy goal cannot be farmed by mashing
     * the node with nothing playing, and it reads "—" until there is enough data to state a rate.
     */
    data class DailySetlist(
        val dateKey: String = "",
        val judgedTaps: Int = 0,
        val bestCombo: Int = 0,
        val greatOrBetter: Int = 0,
        /** bit 0 = taps goal claimed, bit 1 = combo goal, bit 2 = accuracy goal. */
        val claimedMask: Int = 0
    ) {
        val accuracyPct: Float
            get() = if (judgedTaps >= GOAL_ACCURACY_MIN_TAPS) greatOrBetter * 100f / judgedTaps else -1f
        val tapsDone: Boolean get() = judgedTaps >= GOAL_TAPS
        val comboDone: Boolean get() = bestCombo >= GOAL_COMBO
        val accuracyDone: Boolean get() = accuracyPct >= GOAL_ACCURACY_PCT
        val completed: Int get() = (if (tapsDone) 1 else 0) + (if (comboDone) 1 else 0) + (if (accuracyDone) 1 else 0)

        companion object {
            const val GOAL_TAPS = 150
            const val GOAL_COMBO = 30
            const val GOAL_ACCURACY_PCT = 80f
            const val GOAL_ACCURACY_MIN_TAPS = 40
            const val REWARD_EACH = 2_500.0
        }
    }

    private val initialBuildings = listOf(
        Building("chibi", "Chibi Miku", "🎤", 15.0, 0.3, 0, "Little chibi Miku humming along"),
        Building("farm", "Leek Farm", "🌱", 100.0, 1.8, 0, "Hydroponic cyber leeks growing under LEDs"),
        Building("synth", "Yamaha DX7", "🎹", 1100.0, 14.0, 0, "Classic 1983 6-operator FM synthesizer"),
        Building("arcade", "DIVA Arcade", "🕹️", 12000.0, 75.0, 0, "Sanwa arcade buttons and coin drops"),
        Building("stage", "Hologram Stage", "🌟", 130000.0, 420.0, 0, "3D Cyber projection with stadium sound"),
        Building("mirai", "Mirai Stadium", "🏟️", 1400000.0, 2600.0, 0, "50,000 glowing penlights in unison"),
        Building("satellite", "Orbital Station", "🛰️", 20000000.0, 18000.0, 0, "Broadcasting Miku's voice across deep space")
    )

    // Every one of these is READ by the game loop below (see the skill accessors). They used to
    // be decorative: you could spend hundreds of thousands of leeks on a skill tree that changed
    // nothing at all, which is the least legible progression a game can have.
    private val initialSkills = listOf(
        SkillUpgrade("magnet", "★ Golden Leek Magnet", "🧲", 250.0, 0, 10, "Golden Leeks appear 12% sooner per tier"),
        SkillUpgrade("window", "★ Timing Window Expander", "🎯", 600.0, 0, 8, "+6ms wider hit windows per tier"),
        SkillUpgrade("overdrive", "★ Fever Rush Overdrive", "⚡", 2000.0, 0, 5, "+3s Fever & +15x Fever multiplier per tier"),
        SkillUpgrade("pentatonic", "★ Pentatonic Mastery", "🎼", 5000.0, 0, 5, "+25% combo multiplier bonus per tier"),
        // NOT an auto-tapper: a machine tap has no player timing in it, so scoring one would be
        // fabricating accuracy. Chibi Miku farms in the background instead.
        SkillUpgrade("autopilot", "★ Chibi Vocaloid Autopilot", "🤖", 15000.0, 0, 5, "Chibi Miku farms while you rest: +12% passive leeks/s per tier")
    )

    private val initialAchievements = listOf(
        Achievement("first_beat", "First Beat Match", "Hit 10 Perfect beats in a row", "🌸", 500.0, false),
        Achievement("fever_queen", "Fever Queen", "Trigger 100% Super Fever Frenzy", "🔥", 2500.0, false),
        Achievement("diva_50", "Diva Transcendence", "Reach 50x Combo Streak", "👑", 10000.0, false),
        Achievement("audio_arch", "Master Calibrator", "Judge 25 taps against a real beat", "🎧", 5000.0, false),
        Achievement("leek_tycoon", "Cyber Leek Tycoon", "Accumulate over 1,000,000 Leeks", "💰", 50000.0, false)
    )

    private val _leeks = MutableStateFlow(0.0)
    val leeks: StateFlow<Double> = _leeks.asStateFlow()

    private val _totalEarned = MutableStateFlow(0.0)
    val totalEarned: StateFlow<Double> = _totalEarned.asStateFlow()

    private val _buildings = MutableStateFlow(initialBuildings)
    val buildings: StateFlow<List<Building>> = _buildings.asStateFlow()

    private val _skills = MutableStateFlow(initialSkills)
    val skills: StateFlow<List<SkillUpgrade>> = _skills.asStateFlow()

    private val _currentSkin = MutableStateFlow(BpmSkin.SAKURA_DREAM)
    val currentSkin: StateFlow<BpmSkin> = _currentSkin.asStateFlow()

    private val _achievements = MutableStateFlow(initialAchievements)
    val achievements: StateFlow<List<Achievement>> = _achievements.asStateFlow()

    private val _combo = MutableStateFlow(0)
    val combo: StateFlow<Int> = _combo.asStateFlow()

    private val _feverEnergy = MutableStateFlow(0f) // 0% to 100%
    val feverEnergy: StateFlow<Float> = _feverEnergy.asStateFlow()

    private val _feverSeconds = MutableStateFlow(0)
    val feverSeconds: StateFlow<Int> = _feverSeconds.asStateFlow()

    private val _goldenLeekVisible = MutableStateFlow(false)
    val goldenLeekVisible: StateFlow<Boolean> = _goldenLeekVisible.asStateFlow()

    private val _floatingTexts = MutableStateFlow<List<FloatingText>>(emptyList())
    val floatingTexts: StateFlow<List<FloatingText>> = _floatingTexts.asStateFlow()

    private val _particles = MutableStateFlow<List<JuiceParticle>>(emptyList())
    val particles: StateFlow<List<JuiceParticle>> = _particles.asStateFlow()

    // Session Performance Grade Tracking
    private val _sessionTaps = MutableStateFlow(0)
    val sessionTaps: StateFlow<Int> = _sessionTaps.asStateFlow()

    private val _sessionPerfects = MutableStateFlow(0)
    val sessionPerfects: StateFlow<Int> = _sessionPerfects.asStateFlow()

    /** GREAT-or-better count this session — the band the arcade grade is scored on. */
    private val _sessionGreatOrBetter = MutableStateFlow(0)
    val sessionGreatOrBetter: StateFlow<Int> = _sessionGreatOrBetter.asStateFlow()

    /** Combo shields in hand: each one eats a single MISS instead of resetting the combo. */
    private val _comboShields = MutableStateFlow(0)
    val comboShields: StateFlow<Int> = _comboShields.asStateFlow()

    /** The adaptive difficulty tier the player has settled into this session. */
    private val _rhythmTier = MutableStateFlow(MikuRhythmTiming.RhythmTier.PRACTICE)
    val rhythmTier: StateFlow<MikuRhythmTiming.RhythmTier> = _rhythmTier.asStateFlow()

    private val _dailySetlist = MutableStateFlow(DailySetlist())
    val dailySetlist: StateFlow<DailySetlist> = _dailySetlist.asStateFlow()

    // Rolling window of the last judged taps (true = GREAT or better). Drives the tier, so a
    // player who is struggling RIGHT NOW gets wider windows rather than having to live down a
    // bad start for the rest of the session.
    private val recentJudged = ArrayDeque<Boolean>()
    private const val TIER_WINDOW = 30

    private const val MAX_COMBO_SHIELDS = 3
    private const val SHIELD_EARN_STREAK = 20
    private const val SHIELD_MIN_COMBO = 10
    private var shieldProgress = 0
    private var perfectStreak = 0
    /** Fractional-second carry for the fever countdown (tick() runs at 5 Hz, not 1 Hz). */
    private var feverAccumulator = 0.0

    /** -1 = no judged taps this session; there is no accuracy to report. */
    val sessionAccuracyPct: Float
        get() = if (_sessionTaps.value > 0) (_sessionGreatOrBetter.value.toFloat() / _sessionTaps.value.toFloat()) * 100f else -1f

    val sessionGrade: String
        get() = when {
            // Open the observatory having never tapped and this used to award "🌸 SSS" —
            // a top grade over an empty set. No JUDGED taps = no grade; under 5 = provisional.
            _sessionTaps.value == 0 -> "—"
            _sessionTaps.value < 5 -> "— (${_sessionTaps.value}/5)"
            sessionAccuracyPct >= 96f -> "💖 SSS+"
            sessionAccuracyPct >= 90f -> "✨ SS"
            sessionAccuracyPct >= 80f -> "⭐ S"
            sessionAccuracyPct >= 70f -> "🎵 A"
            else -> "🥬 B"
        }

    private var prefs: SharedPreferences? = null
    private var lastTickTime = SystemClock.elapsedRealtime()
    private var nextGoldenLeekCheck = SystemClock.elapsedRealtime() + 25000L

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p

        _leeks.value = p.getFloat(KEY_LEEKS, 0f).toDouble()
        _totalEarned.value = p.getFloat(KEY_TOTAL_EARNED, 0f).toDouble()

        val updated = initialBuildings.map { b ->
            val count = p.getInt("b_${b.id}", 0)
            b.copy(owned = count)
        }
        _buildings.value = updated

        val updatedSkills = initialSkills.map { s ->
            val lvl = p.getInt("s_${s.id}", 0)
            s.copy(level = lvl)
        }
        _skills.value = updatedSkills

        // Achievements were SAVED (see save(), "ach_<id>") but never restored, so the quests card
        // reset to "0 of 5 Done" with every achievement re-locked after each launcher restart —
        // and checkAchievements() re-granted rewardLeeks for conditions that were still met,
        // silently inflating the Leek total every session.
        _achievements.value = initialAchievements.map { a ->
            a.copy(isUnlocked = p.getBoolean("ach_${a.id}", false))
        }

        // Restore Today's Setlist — a saved day that is not today starts fresh at zero.
        val savedDay = p.getString("daily_date", "") ?: ""
        val today = todayKey()
        _dailySetlist.value = if (savedDay == today) {
            DailySetlist(
                dateKey = today,
                judgedTaps = p.getInt("daily_taps", 0),
                bestCombo = p.getInt("daily_combo", 0),
                greatOrBetter = p.getInt("daily_great", 0),
                claimedMask = p.getInt("daily_claimed", 0)
            )
        } else DailySetlist(dateKey = today)
        _comboShields.value = p.getInt("combo_shields", 0).coerceIn(0, MAX_COMBO_SHIELDS)

        val skinName = p.getString("current_skin", BpmSkin.SAKURA_DREAM.name)
        val restored = runCatching { BpmSkin.valueOf(skinName!!) }.getOrDefault(BpmSkin.SAKURA_DREAM)
        // Never restore a skin the player no longer has (unlock store cleared, /data wipe).
        _currentSkin.value = if (restored.isAvailable(context)) restored else BpmSkin.SAKURA_DREAM

        // Offline earnings
        val lastSaved = p.getLong(KEY_LAST_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        if (lastSaved > 0L && now > lastSaved) {
            val secondsAway = ((now - lastSaved) / 1000L).coerceIn(0L, 86400L)
            val baseBps = updated.sumOf { it.totalBps }
            val offlineGain = baseBps * secondsAway * 0.6 // 60% offline efficiency
            if (offlineGain > 0.0) {
                _leeks.value += offlineGain
                _totalEarned.value += offlineGain
            }
        }
        lastTickTime = SystemClock.elapsedRealtime()
    }

    // =====================================================================================
    // SKILL EFFECTS — the skill tree is read HERE, so buying a tier visibly changes the game.
    // =====================================================================================

    fun skillLevel(id: String): Int = _skills.value.firstOrNull { it.id == id }?.level ?: 0

    /** Timing Window Expander: flat ms added to every hit window (see MikuRhythmTiming). */
    val timingWindowBonusMs: Int get() = skillLevel("window") * 6

    /** Golden Leek Magnet: shortens the spawn timer (10 tiers => ~45% sooner, never instant). */
    private val goldenLeekHaste: Double get() = 1.0 / (1.0 + skillLevel("magnet") * 0.12)

    /** Fever Rush Overdrive: longer and stronger fever. */
    private val feverDurationSeconds: Int get() = 15 + skillLevel("overdrive") * 3
    private val feverMultiplier: Double get() = 7.7 + skillLevel("overdrive") * 15.0

    /** Pentatonic Mastery: amplifies the part of the combo multiplier above x1. */
    private val comboBonusScale: Double get() = 1.0 + skillLevel("pentatonic") * 0.25

    /** Chibi Vocaloid Autopilot: passive production only — it never fakes a judged tap. */
    private val autopilotBpsScale: Double get() = 1.0 + skillLevel("autopilot") * 0.12

    // =====================================================================================
    // COMBO CURVE — legible breakpoints instead of an invisible per-tap trickle
    // =====================================================================================

    /** The rungs of the combo ladder: (combo needed, multiplier). */
    private val COMBO_RUNGS = listOf(
        4 to 1.5, 8 to 2.0, 16 to 3.0, 32 to 4.0, 64 to 5.0, 128 to 6.0
    )

    /** Raw multiplier for a combo, before the Pentatonic bonus. */
    fun comboMultiplier(combo: Int): Double {
        var m = 1.0
        for ((need, mult) in COMBO_RUNGS) if (combo >= need) m = mult
        return m
    }

    /** Multiplier actually applied (combo curve + Pentatonic Mastery on the bonus part). */
    fun effectiveComboMultiplier(combo: Int): Double {
        val base = comboMultiplier(combo)
        return 1.0 + (base - 1.0) * comboBonusScale
    }

    /**
     * The next rung, as (taps still needed, multiplier it unlocks), or null at the top.
     * Rendered as "4 more → x3" so the player can SEE what the next push is worth — anticipation
     * is what makes a combo feel like a decision rather than a side effect.
     */
    fun nextComboRung(combo: Int): Pair<Int, Double>? =
        COMBO_RUNGS.firstOrNull { it.first > combo }?.let { (need, mult) -> (need - combo) to mult }

    /**
     * Calculates base Beats/Leeks Per Second.
     */
    fun getBaseBps(): Double = _buildings.value.sumOf { it.totalBps } * autopilotBpsScale

    /**
     * Overclocks BPS based on live playing song BPM and active Fever frenzy.
     */
    fun getEffectiveBps(songBpm: Float): Double {
        val base = getBaseBps()
        val bpmFactor = (songBpm / 120.0).coerceIn(0.5, 4.0)
        val feverFactor = if (_feverSeconds.value > 0) feverMultiplier else 1.0
        return base * bpmFactor * feverFactor
    }

    /**
     * Incremental game loop tick (invoked every 100-200ms).
     */
    fun tick(dtSeconds: Double, songBpm: Float) {
        val effectiveBps = getEffectiveBps(songBpm)
        if (effectiveBps > 0.0) {
            val delta = effectiveBps * dtSeconds
            _leeks.value += delta
            _totalEarned.value += delta
        }

        // Fever countdown — in REAL seconds. tick() runs every 200 ms, and this used to take a
        // whole second off the clock on every one of those ticks, so a "15 second" fever (the
        // reward for filling the entire gauge, and the loudest moment in the loop) actually
        // lasted three seconds and the on-screen countdown fell in visible jumps of five.
        if (_feverSeconds.value > 0) {
            feverAccumulator += dtSeconds
            while (feverAccumulator >= 1.0 && _feverSeconds.value > 0) {
                feverAccumulator -= 1.0
                _feverSeconds.value = (_feverSeconds.value - 1).coerceAtLeast(0)
            }
            if (_feverSeconds.value == 0) {
                feverAccumulator = 0.0
                _feverEnergy.value = 0f
            }
        }

        // Golden Leek random spawn (approx every 30-45s)
        val now = SystemClock.elapsedRealtime()
        if (now >= nextGoldenLeekCheck && !_goldenLeekVisible.value) {
            _goldenLeekVisible.value = true
            // Golden Leek Magnet shortens the wait — the skill's description is now true.
            nextGoldenLeekCheck = now + (Random.nextLong(25000L, 45000L) * goldenLeekHaste).toLong()
        }
    }

    /**
     * Handles a rhythm tap on the orb that WAS judged against a real detected beat.
     *
     * Evaluates the graded tier, advances/breaks the combo (spending a shield if one is held),
     * charges fever, feeds the adaptive difficulty window and the daily setlist, and pays out.
     *
     * CONTRACT: never call this for a tap with no beat reference — use [freeTap]. Everything
     * this function increments is rendered later as a measured statistic.
     *
     * @param scoreScale difficulty payout scale (MikuRhythmTiming.RhythmTier.scoreScale): the
     *        tighter the windows the player is being graded on, the more each hit is worth.
     */
    fun tap(accuracy: HitAccuracy, songBpm: Float, scoreScale: Float = 1f): Double {
        _sessionTaps.value += 1
        if (accuracy == HitAccuracy.PERFECT) _sessionPerfects.value += 1
        if (accuracy.isGreatOrBetter) _sessionGreatOrBetter.value += 1

        // Rolling difficulty window: the tier follows how the player is doing RIGHT NOW.
        recentJudged.addLast(accuracy.isGreatOrBetter)
        while (recentJudged.size > TIER_WINDOW) recentJudged.removeFirst()
        val rollingPct =
            if (recentJudged.size >= 10) recentJudged.count { it } * 100f / recentJudged.size else -1f
        val newTier = MikuRhythmTiming.tierFor(_sessionTaps.value, rollingPct)
        if (newTier != _rhythmTier.value) {
            _rhythmTier.value = newTier
            // A difficulty change is a real event — say so, don't move the goalposts silently.
            pushFloatingText("${newTier.badge} ${newTier.label}", isCrit = false)
        }

        val baseClick = 1.0 + (getBaseBps() * 0.03)

        // --- COMBO: grow, hold, shield, or break -------------------------------------------
        var shieldSaved = false
        when {
            accuracy.growsCombo -> {
                _combo.value += 1
                shieldProgress += 1
                if (shieldProgress >= SHIELD_EARN_STREAK && _comboShields.value < MAX_COMBO_SHIELDS) {
                    shieldProgress = 0
                    _comboShields.value += 1
                    pushFloatingText("🛡️ COMBO SHIELD +1", isCrit = false)
                    com.miku.launcher.audio.MikuSeasonalAudioEngine.playLevelUpFanfare()
                }
            }
            // OK: you stayed on the train but didn't build. Tension without punishment.
            accuracy.keepsCombo -> { /* combo held */ }
            // A MISS on a real streak is the moment the game is actually about. One shield
            // turns that dread into a rescue — the single most replay-driving beat in the loop.
            _combo.value >= SHIELD_MIN_COMBO && _comboShields.value > 0 -> {
                _comboShields.value -= 1
                shieldSaved = true
                pushFloatingText("🛡️ SAVED! x${_combo.value}", isCrit = true)
                com.miku.launcher.audio.MikuSeasonalAudioEngine.playCritChime()
            }
            else -> {
                if (_combo.value >= 10) pushFloatingText("💔 COMBO BROKEN (x${_combo.value})", isCrit = false)
                _combo.value = 0
                shieldProgress = 0
            }
        }
        if (!accuracy.isGreatOrBetter) shieldProgress = 0
        perfectStreak = if (accuracy == HitAccuracy.PERFECT) perfectStreak + 1 else 0

        val comboBonus = effectiveComboMultiplier(_combo.value)
        val isCrit = Random.nextFloat() < 0.09f // 9% Crit chance
        val critMultiplier = if (isCrit) 10.0 else 1.0
        val feverMult = if (_feverSeconds.value > 0) feverMultiplier else 1.0
        val bpmBonus = (songBpm / 120.0).coerceIn(0.8, 3.0)

        // --- FEVER: charged only by hits, weighted by the tier table --------------------------
        if (_feverSeconds.value == 0) {
            val chargeAmount = if (isCrit) accuracy.feverCharge * 2f else accuracy.feverCharge
            val newEnergy = (_feverEnergy.value + chargeAmount).coerceIn(0f, 100f)
            _feverEnergy.value = newEnergy
            if (newEnergy >= 100f) {
                _feverSeconds.value = feverDurationSeconds
                com.miku.launcher.audio.MikuSeasonalAudioEngine.playGoldenLeekJingle()
            }
        }

        val totalTapYield = baseClick * accuracy.yieldMultiplier * comboBonus * critMultiplier *
            feverMult * bpmBonus * scoreScale.coerceIn(0.5f, 3f)
        _leeks.value += totalTapYield
        _totalEarned.value += totalTapYield

        // Judgment word comes straight off the tier, so screen and economy can't disagree.
        val textStr = when {
            isCrit -> "+${formatNumber(totalTapYield)} [CRIT! ✨]"
            accuracy == HitAccuracy.MISS -> "+${formatNumber(totalTapYield)}"
            else -> "+${formatNumber(totalTapYield)} [${accuracy.label}! ${accuracy.emoji}]"
        }
        pushFloatingText(textStr, isCrit)

        // --- JUICE: particle burst scaled by how big the moment actually is -------------------
        // A x60 combo hit must not look like a lone OK. Count rises with crit, fever and combo
        // rung, so the screen gets visibly louder exactly as the stakes do.
        val comboRungBoost = when {
            _combo.value >= 64 -> 8
            _combo.value >= 32 -> 5
            _combo.value >= 16 -> 3
            _combo.value >= 8 -> 1
            else -> 0
        }
        val count = (if (isCrit) 12 else if (accuracy.isGreatOrBetter) 7 else 4) +
            comboRungBoost + (if (_feverSeconds.value > 0) 4 else 0) + (if (shieldSaved) 10 else 0)
        spawnParticles(count.coerceAtMost(26))

        // Melodic Pentatonic Chime Feedback
        if (accuracy.isHit) {
            com.miku.launcher.audio.MikuSeasonalAudioEngine.playComboMelody(_combo.value, _feverSeconds.value > 0)
        }
        if (isCrit) {
            com.miku.launcher.audio.MikuSeasonalAudioEngine.playCritChime()
        }

        recordDailyProgress(accuracy)
        return totalTapYield
    }

    /**
     * A tap with NO beat reference (nothing playing, or the detector has no recent pulse).
     *
     * It pays the incremental game's plain base click — the idle loop should still respond to a
     * finger — and deliberately touches nothing else: no accuracy counter, no combo, no fever,
     * no season rank, no telemetry. There was no beat, so there is no judgment to make, and a
     * judgment we did not make must never end up behind a number we display.
     */
    fun freeTap(): Double {
        val yieldAmount = 1.0 + (getBaseBps() * 0.03)
        _leeks.value += yieldAmount
        _totalEarned.value += yieldAmount
        pushFloatingText("+${formatNumber(yieldAmount)}", isCrit = false)
        spawnParticles(3)
        return yieldAmount
    }

    /** Shared floating-number spawner (used by judged taps, free taps and shield events). */
    private fun pushFloatingText(text: String, isCrit: Boolean) {
        val ft = FloatingText(
            id = SystemClock.uptimeMillis() + Random.nextInt(1000),
            text = text,
            x = Random.nextFloat() * 120f - 60f,
            y = Random.nextFloat() * 40f - 20f,
            isCrit = isCrit,
            isFever = _feverSeconds.value > 0
        )
        _floatingTexts.value = (_floatingTexts.value + ft).takeLast(6)
    }

    /** Shared particle burst. */
    private fun spawnParticles(count: Int) {
        if (count <= 0) return
        // Glyphs and tints come from the ACTIVE SKIN, so a burst looks like the skin you chose.
        val skin = _currentSkin.value
        val particleEmojis = skin.burstGlyphs
        val particleColors = skin.burstColors
        val newParticles = (0 until count).map {
            val angle = Random.nextFloat() * 2.0 * Math.PI
            val speed = Random.nextFloat() * 45f + 25f
            JuiceParticle(
                id = SystemClock.uptimeMillis() + it,
                x = 0f,
                y = 0f,
                vx = (Math.cos(angle) * speed).toFloat(),
                vy = (Math.sin(angle) * speed).toFloat(),
                emoji = particleEmojis.random(),
                colorHex = particleColors.random(),
                sizeDp = Random.nextFloat() * 6f + 14f
            )
        }
        _particles.value = (_particles.value + newParticles).takeLast(28)
    }

    /** Judged-tap progress toward Today's Setlist, with the one-off completion rewards. */
    private fun recordDailyProgress(accuracy: HitAccuracy) {
        val today = todayKey()
        val cur = _dailySetlist.value.let { if (it.dateKey == today) it else DailySetlist(dateKey = today) }
        var next = cur.copy(
            judgedTaps = cur.judgedTaps + 1,
            bestCombo = maxOf(cur.bestCombo, _combo.value),
            greatOrBetter = cur.greatOrBetter + if (accuracy.isGreatOrBetter) 1 else 0
        )
        var reward = 0.0
        if (next.tapsDone && (next.claimedMask and 1) == 0) { reward += DailySetlist.REWARD_EACH; next = next.copy(claimedMask = next.claimedMask or 1) }
        if (next.comboDone && (next.claimedMask and 2) == 0) { reward += DailySetlist.REWARD_EACH; next = next.copy(claimedMask = next.claimedMask or 2) }
        if (next.accuracyDone && (next.claimedMask and 4) == 0) { reward += DailySetlist.REWARD_EACH; next = next.copy(claimedMask = next.claimedMask or 4) }
        _dailySetlist.value = next
        if (reward > 0.0) {
            _leeks.value += reward
            _totalEarned.value += reward
            pushFloatingText("🎯 SETLIST GOAL +${formatNumber(reward)}", isCrit = true)
            com.miku.launcher.audio.MikuSeasonalAudioEngine.playLevelUpFanfare()
            saveState()
        }
    }

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())

    /**
     * Tapping the golden leek awards an instant bounty and 15s of 77x Fever Frenzy!
     */
    fun tapGoldenLeek(): Double {
        _goldenLeekVisible.value = false
        val reward = (getBaseBps() * 90.0).coerceAtLeast(77.0)
        _leeks.value += reward
        _totalEarned.value += reward
        _feverSeconds.value = feverDurationSeconds
        _feverEnergy.value = 100f
        // A bonus target is not a judged tap: it may hand you combo, but it must never touch
        // the accuracy counters that back the grade.
        _combo.value += 10
        com.miku.launcher.audio.MikuSeasonalAudioEngine.playGoldenLeekJingle()
        return reward
    }

    /**
     * Purchases an auto-producing building.
     */
    fun buyBuilding(id: String): Boolean {
        val list = _buildings.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false

        val b = list[index]
        val cost = b.currentCost
        if (_leeks.value >= cost) {
            _leeks.value -= cost
            list[index] = b.copy(owned = b.owned + 1)
            _buildings.value = list
            saveState()
            com.miku.launcher.audio.MikuSeasonalAudioEngine.playLevelUpFanfare()
            return true
        }
        return false
    }

    /**
     * Purchases a Producer Skill Upgrade.
     */
    fun buySkill(id: String): Boolean {
        val list = _skills.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false

        val s = list[index]
        if (s.level >= s.maxLevel) return false
        val cost = s.currentCost
        if (_leeks.value >= cost) {
            _leeks.value -= cost
            list[index] = s.copy(level = s.level + 1)
            _skills.value = list
            saveState()
            com.miku.launcher.audio.MikuSeasonalAudioEngine.playLevelUpFanfare()
            return true
        }
        return false
    }

    /**
     * Sets the active BPM observatory skin, if it has been earned.
     * Returns false for a locked skin — the caller shows what it takes instead.
     */
    fun setSkin(ctx: Context, skin: BpmSkin): Boolean {
        if (!skin.isAvailable(ctx)) return false
        _currentSkin.value = skin
        saveState()
        return true
    }

    /**
     * Evaluates achievement triggers and rewards bonus currency.
     */
    fun checkAchievements(): List<Achievement> {
        val list = _achievements.value.toMutableList()
        val newlyUnlocked = mutableListOf<Achievement>()

        for (i in list.indices) {
            val a = list[i]
            if (a.isUnlocked) continue

            val conditionMet = when (a.id) {
                // "in a row" is what the quest says, so count a real streak — it used to fire on
                // 10 perfects scattered across a session.
                "first_beat" -> perfectStreak >= 10
                "fever_queen" -> _feverSeconds.value > 0
                "diva_50" -> _combo.value >= 50
                // _sessionTaps counts JUDGED taps only, so this quest cannot be farmed by
                // mashing the node with nothing playing.
                "audio_arch" -> _sessionTaps.value >= 25
                "leek_tycoon" -> _totalEarned.value >= 1_000_000.0
                else -> false
            }

            if (conditionMet) {
                list[i] = a.copy(isUnlocked = true)
                _leeks.value += a.rewardLeeks
                _totalEarned.value += a.rewardLeeks
                newlyUnlocked.add(list[i])
                com.miku.launcher.audio.MikuSeasonalAudioEngine.playLevelUpFanfare()
            }
        }

        if (newlyUnlocked.isNotEmpty()) {
            _achievements.value = list
            saveState()
        }
        return newlyUnlocked
    }

    fun saveState() {
        val p = prefs ?: return
        val editor = p.edit()
        editor.putFloat(KEY_LEEKS, _leeks.value.toFloat())
        editor.putFloat(KEY_TOTAL_EARNED, _totalEarned.value.toFloat())
        editor.putLong(KEY_LAST_TIMESTAMP, System.currentTimeMillis())
        editor.putString("current_skin", _currentSkin.value.name)
        _buildings.value.forEach { b ->
            editor.putInt("b_${b.id}", b.owned)
        }
        _skills.value.forEach { s ->
            editor.putInt("s_${s.id}", s.level)
        }
        _achievements.value.forEach { a ->
            editor.putBoolean("ach_${a.id}", a.isUnlocked)
        }
        // Today's Setlist and the shields in hand survive a launcher restart; without this the
        // daily goals silently reset mid-day and the reward could be earned twice.
        val d = _dailySetlist.value
        editor.putString("daily_date", d.dateKey)
        editor.putInt("daily_taps", d.judgedTaps)
        editor.putInt("daily_combo", d.bestCombo)
        editor.putInt("daily_great", d.greatOrBetter)
        editor.putInt("daily_claimed", d.claimedMask)
        editor.putInt("combo_shields", _comboShields.value)
        editor.apply()
    }

    fun formatNumber(num: Double): String {
        return when {
            num >= 1_000_000_000_000.0 -> String.format(Locale.US, "%.2fT", num / 1_000_000_000_000.0)
            num >= 1_000_000_000.0 -> String.format(Locale.US, "%.2fB", num / 1_000_000_000.0)
            num >= 1_000_000.0 -> String.format(Locale.US, "%.2fM", num / 1_000_000.0)
            num >= 1_000.0 -> String.format(Locale.US, "%.1fK", num / 1_000.0)
            else -> String.format(Locale.US, "%.0f", num)
        }
    }
}
