package com.miku.launcher.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Low-latency real-time PCM Synthesizer for Hatsune Miku Vocaloid & Cyber Rhythm sound effects.
 *
 * Generates FM-synth / DX7 style chimes, level-up fanfares, and golden leek jingles
 * without requiring external sound files.
 */
object MikuSeasonalAudioEngine {
    private const val SAMPLE_RATE = 44100
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Plays a crisp, FM-synthesized level-up fanfare (Ascending Pentatonic Cyber Chime).
     */
    fun playLevelUpFanfare() {
        scope.launch {
            // Notes: E5 (659.25Hz), G#5 (830.61Hz), B5 (987.77Hz), E6 (1318.51Hz)
            val notes = doubleArrayOf(659.25, 830.61, 987.77, 1318.51)
            val durationPerNoteSec = 0.09
            val totalSamples = (SAMPLE_RATE * (durationPerNoteSec * notes.size + 0.25)).toInt()
            val pcm = ShortArray(totalSamples)

            var offset = 0
            for (freq in notes) {
                val noteSamples = (SAMPLE_RATE * durationPerNoteSec).toInt()
                for (i in 0 until noteSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = exp(-t * 12.0)
                    // FM Synthesis: Carrier + Modulator
                    val mod = sin(2.0 * PI * (freq * 2.0) * t) * 0.4
                    val sample = sin(2.0 * PI * freq * t + mod) * env
                    val idx = offset + i
                    if (idx < totalSamples) {
                        pcm[idx] = (sample * 16000.0).toInt().coerceIn(-32767, 32767).toShort()
                    }
                }
                offset += (SAMPLE_RATE * durationPerNoteSec * 0.8).toInt()
            }

            // Ring-out tail on the top note (E6)
            val tailSamples = (SAMPLE_RATE * 0.35).toInt()
            for (i in 0 until tailSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val env = exp(-t * 6.0)
                val sample = sin(2.0 * PI * 1318.51 * t) * env
                val idx = offset + i
                if (idx < totalSamples) {
                    val existing = pcm[idx].toInt()
                    val mixed = existing + (sample * 18000.0).toInt()
                    pcm[idx] = mixed.coerceIn(-32767, 32767).toShort()
                }
            }

            playPcm(pcm)
        }
    }

    /**
     * Sparkling Golden Leek Fever Frenzy Jingle.
     */
    fun playGoldenLeekJingle() {
        scope.launch {
            val totalSamples = (SAMPLE_RATE * 0.45).toInt()
            val pcm = ShortArray(totalSamples)

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val env = exp(-t * 7.0)
                // Dual FM shimmers
                val s1 = sin(2.0 * PI * (1760.0 + sin(2.0 * PI * 35.0 * t) * 200.0) * t)
                val s2 = sin(2.0 * PI * (2637.0 + sin(2.0 * PI * 42.0 * t) * 300.0) * t) * 0.6
                val s3 = sin(2.0 * PI * 3520.0 * t) * 0.3
                val sample = (s1 + s2 + s3) * env * 0.5
                pcm[i] = (sample * 20000.0).toInt().coerceIn(-32767, 32767).toShort()
            }

            playPcm(pcm)
        }
    }

    /**
     * Plays a sharp, satisfying resonant cyber bleep for perfect taps or critical hits.
     */
    fun playCritChime() {
        scope.launch {
            val totalSamples = (SAMPLE_RATE * 0.12).toInt()
            val pcm = ShortArray(totalSamples)

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val env = exp(-t * 28.0)
                val freq = 1200.0 + (1.0 - t / 0.12) * 800.0
                val sample = sin(2.0 * PI * freq * t) * env
                pcm[i] = (sample * 18000.0).toInt().coerceIn(-32767, 32767).toShort()
            }

            playPcm(pcm)
        }
    }

    /**
     * Pentatonic musical chime that steps up the scale as the combo streak increases.
     * C5 (523Hz), D5 (587Hz), E5 (659Hz), G5 (784Hz), A5 (880Hz), C6 (1046Hz), D6 (1174Hz), E6 (1318Hz)...
     */
    fun playComboMelody(combo: Int, isFever: Boolean) {
        scope.launch {
            val scale = doubleArrayOf(
                523.25, 587.33, 659.25, 783.99, 880.00,
                1046.50, 1174.66, 1318.51, 1567.98, 1760.00, 2093.00
            )
            val noteIndex = (combo.coerceAtLeast(1) - 1) % scale.size
            val baseFreq = scale[noteIndex]
            val freq = if (isFever) baseFreq * 1.5 else baseFreq

            val durationSec = if (isFever) 0.08 else 0.11
            val totalSamples = (SAMPLE_RATE * durationSec).toInt()
            val pcm = ShortArray(totalSamples)

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val env = exp(-t * (if (isFever) 22.0 else 18.0))
                val mod = sin(2.0 * PI * (freq * 2.0) * t) * (if (isFever) 0.6 else 0.3)
                val sample = sin(2.0 * PI * freq * t + mod) * env
                pcm[i] = (sample * 15000.0).toInt().coerceIn(-32767, 32767).toShort()
            }

            playPcm(pcm)
        }
    }

    private fun playPcm(pcm: ShortArray) {
        try {
            val bufferSize = pcm.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()
            scope.launch {
                kotlinx.coroutines.delay((pcm.size * 1000L / SAMPLE_RATE) + 100L)
                try {
                    track.stop()
                    track.release()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
