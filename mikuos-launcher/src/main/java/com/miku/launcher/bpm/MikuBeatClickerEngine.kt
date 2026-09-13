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
 * Game Theory Mechanics:
 * - Active Tap Yield: 1.0 base + 3% of passive BPS.
 * - Accuracy Scaler: Off-Beat = 1x, GOOD = 2x, PERFECT = 5x + 8% CRIT chance (10x).
 * - Combo Compounding: Multiplier scales with consecutive on-beat hits: 1.0 + (combo * 0.05).
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

    enum class BpmSkin(
        val displayName: String,
        val icon: String,
        val primaryColor: Long,
        val accentColor: Long,
        val glowColor: Long,
        val patternName: String
    ) {
        SAKURA_DREAM("Sakura Dream", "🌸", 0xFFFF85B3, 0xFF39C5BB, 0xFFFFB5D5, "sakura_lattice"),
        CYBER_MIRAI("Cyber Mirai", "🌟", 0xFF00E5FF, 0xFF9D4EDD, 0xFF7000FF, "hex_matrix"),
        HONEY_SWEET("Honey Sweet", "🍯", 0xFFFFD166, 0xFFFF9F1C, 0xFFFFE49E, "honeycomb"),
        GOTHIC_DIVA("Gothic Lolita", "🖤", 0xFFFF2277, 0xFF7B2CBF, 0xFFFF5599, "velvet_damask"),
        SNOW_CRYSTAL("Snow Crystal", "❄️", 0xFF7FE6DE, 0xFF48CAE4, 0xFFE0F7FA, "frost_shimmer")
    }

    data class Achievement(
        val id: String,
        val title: String,
        val desc: String,
        val icon: String,
        val rewardLeeks: Double,
        val isUnlocked: Boolean
    )

    private val initialBuildings = listOf(
        Building("chibi", "Chibi Miku", "🎤", 15.0, 0.3, 0, "Little chibi Miku humming along"),
        Building("farm", "Leek Farm", "🌱", 100.0, 1.8, 0, "Hydroponic cyber leeks growing under LEDs"),
        Building("synth", "Yamaha DX7", "🎹", 1100.0, 14.0, 0, "Classic 1983 6-operator FM synthesizer"),
        Building("arcade", "DIVA Arcade", "🕹️", 12000.0, 75.0, 0, "Sanwa arcade buttons and coin drops"),
        Building("stage", "Hologram Stage", "🌟", 130000.0, 420.0, 0, "3D Cyber projection with stadium sound"),
        Building("mirai", "Mirai Stadium", "🏟️", 1400000.0, 2600.0, 0, "50,000 glowing penlights in unison"),
        Building("satellite", "Orbital Station", "🛰️", 20000000.0, 18000.0, 0, "Broadcasting Miku's voice across deep space")
    )

    private val initialSkills = listOf(
        SkillUpgrade("magnet", "★ Golden Leek Magnet", "🧲", 250.0, 0, 10, "+20% Golden Leek appearance rate per tier"),
        SkillUpgrade("window", "★ Timing Window Expander", "🎯", 600.0, 0, 8, "+6ms wider PERFECT hit window"),
        SkillUpgrade("overdrive", "★ Fever Rush Overdrive", "⚡", 2000.0, 0, 5, "+3s Fever duration & +15x multiplier boost"),
        SkillUpgrade("pentatonic", "★ Pentatonic Mastery", "🎼", 5000.0, 0, 5, "+40% melodic combo point bonus"),
        SkillUpgrade("autopilot", "★ Chibi Vocaloid Autopilot", "🤖", 15000.0, 0, 5, "Chibi Miku passively taps along on half-beats")
    )

    private val initialAchievements = listOf(
        Achievement("first_beat", "First Beat Match", "Hit 10 Perfect beats in a row", "🌸", 500.0, false),
        Achievement("fever_queen", "Fever Queen", "Trigger 100% Super Fever Frenzy", "🔥", 2500.0, false),
        Achievement("diva_50", "Diva Transcendence", "Reach 50x Combo Streak", "👑", 10000.0, false),
        Achievement("audio_arch", "Master Calibrator", "Log 25 accurate rhythm taps to database", "🎧", 5000.0, false),
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

    /** -1 = no taps this session; there is no accuracy to report. It used to return 100f. */
    val sessionAccuracyPct: Float
        get() = if (_sessionTaps.value > 0) (_sessionPerfects.value.toFloat() / _sessionTaps.value.toFloat()) * 100f else -1f

    val sessionGrade: String
        get() = when {
            // Open the observatory having never tapped and this used to award "🌸 SSS" —
            // a top grade over an empty set. No taps = no grade; under 5 = provisional count.
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

        val skinName = p.getString("current_skin", BpmSkin.SAKURA_DREAM.name)
        _currentSkin.value = runCatching { BpmSkin.valueOf(skinName!!) }.getOrDefault(BpmSkin.SAKURA_DREAM)

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

    /**
     * Calculates base Beats/Leeks Per Second.
     */
    fun getBaseBps(): Double = _buildings.value.sumOf { it.totalBps }

    /**
     * Overclocks BPS based on live playing song BPM and active Fever frenzy.
     */
    fun getEffectiveBps(songBpm: Float): Double {
        val base = getBaseBps()
        val bpmFactor = (songBpm / 120.0).coerceIn(0.5, 4.0)
        val feverFactor = if (_feverSeconds.value > 0) 7.7 else 1.0
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

        // Fever countdown
        if (_feverSeconds.value > 0) {
            _feverSeconds.value = (_feverSeconds.value - 1).coerceAtLeast(0)
            if (_feverSeconds.value == 0) {
                _feverEnergy.value = 0f
            }
        }

        // Golden Leek random spawn (approx every 30-45s)
        val now = SystemClock.elapsedRealtime()
        if (now >= nextGoldenLeekCheck && !_goldenLeekVisible.value) {
            _goldenLeekVisible.value = true
            nextGoldenLeekCheck = now + Random.nextLong(25000L, 45000L)
        }
    }

    /**
     * Handles an active rhythm tap on the orb.
     * Evaluates accuracy, combo, crit, charges fever, and rewards leek currency.
     */
    fun tap(accuracy: HitAccuracy, songBpm: Float): Double {
        _sessionTaps.value += 1
        if (accuracy == HitAccuracy.PERFECT) {
            _sessionPerfects.value += 1
        }

        val baseClick = 1.0 + (getBaseBps() * 0.03)
        val accuracyMultiplier = when (accuracy) {
            HitAccuracy.PERFECT -> 5.0
            HitAccuracy.GOOD -> 2.0
            HitAccuracy.MISS -> 1.0
        }

        if (accuracy != HitAccuracy.MISS) {
            _combo.value += 1
        } else {
            _combo.value = 0
        }

        val comboBonus = 1.0 + (_combo.value * 0.05).coerceAtMost(5.0)
        val isCrit = Random.nextFloat() < 0.09f // 9% Crit chance
        val critMultiplier = if (isCrit) 10.0 else 1.0
        val feverMultiplier = if (_feverSeconds.value > 0) 7.7 else 1.0
        val bpmBonus = (songBpm / 120.0).coerceIn(0.8, 3.0)

        // Charge Fever Gauge
        if (_feverSeconds.value == 0) {
            val chargeAmount = when (accuracy) {
                HitAccuracy.PERFECT -> if (isCrit) 18f else 9f
                HitAccuracy.GOOD -> 4.5f
                HitAccuracy.MISS -> 0f
            }
            val newEnergy = (_feverEnergy.value + chargeAmount).coerceIn(0f, 100f)
            _feverEnergy.value = newEnergy
            if (newEnergy >= 100f) {
                // Auto-Trigger SUPER FEVER HYPERDRIVE!
                _feverSeconds.value = 15
                com.miku.launcher.audio.MikuSeasonalAudioEngine.playGoldenLeekJingle()
            }
        }

        val totalTapYield = baseClick * accuracyMultiplier * comboBonus * critMultiplier * feverMultiplier * bpmBonus
        _leeks.value += totalTapYield
        _totalEarned.value += totalTapYield

        // Spawn floating text
        val textStr = when {
            isCrit -> "+${formatNumber(totalTapYield)} [CRIT! ✨]"
            accuracy == HitAccuracy.PERFECT -> "+${formatNumber(totalTapYield)} [PERFECT! 💖]"
            accuracy == HitAccuracy.GOOD -> "+${formatNumber(totalTapYield)} [GOOD 🌸]"
            else -> "+${formatNumber(totalTapYield)}"
        }
        val ft = FloatingText(
            id = SystemClock.uptimeMillis(),
            text = textStr,
            x = Random.nextFloat() * 120f - 60f,
            y = Random.nextFloat() * 40f - 20f,
            isCrit = isCrit,
            isFever = _feverSeconds.value > 0
        )
        _floatingTexts.value = (_floatingTexts.value + ft).takeLast(6)

        // Spawn Juicy Particle Bursts
        val particleEmojis = listOf("✨", "💖", "🌸", "⭐", "🎵", "🥬")
        val particleColors = listOf(0xFFFF85B3, 0xFF39C5BB, 0xFFFFD166, 0xFFDFB8FF, 0xFFFF3385)
        val count = if (isCrit) 12 else 7
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
        _particles.value = (_particles.value + newParticles).takeLast(24)

        // Melodic Pentatonic Chime Feedback
        if (accuracy != HitAccuracy.MISS) {
            com.miku.launcher.audio.MikuSeasonalAudioEngine.playComboMelody(_combo.value, _feverSeconds.value > 0)
        }
        if (isCrit) {
            com.miku.launcher.audio.MikuSeasonalAudioEngine.playCritChime()
        }

        return totalTapYield
    }

    /**
     * Tapping the golden leek awards an instant bounty and 15s of 77x Fever Frenzy!
     */
    fun tapGoldenLeek(): Double {
        _goldenLeekVisible.value = false
        val reward = (getBaseBps() * 90.0).coerceAtLeast(77.0)
        _leeks.value += reward
        _totalEarned.value += reward
        _feverSeconds.value = 15
        _feverEnergy.value = 100f
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
     * Sets active BPM observatory skin theme.
     */
    fun setSkin(skin: BpmSkin) {
        _currentSkin.value = skin
        saveState()
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
                "first_beat" -> _sessionPerfects.value >= 10
                "fever_queen" -> _feverSeconds.value > 0
                "diva_50" -> _combo.value >= 50
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
