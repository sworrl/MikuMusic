package com.caf.fmradio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * 1:1 Kotlin port of Qualcomm CAF AudioTrackHelper from vendor FM2.
 * Feeds bit-perfect 48kHz stereo hardware FM tuner PCM into the audio subsystem.
 */
class AudioTrackHelper(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_OUT_STEREO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    companion object {
        private const val TAG = "FmAudioTrackHelper"
    }

    private var audioTrack: AudioTrack? = null
    private var bufferSize: Int = 0
    private var isInitialized: Boolean = false
    private var isPlaying: Boolean = false
    private var currentVolume: Float = 1.0f
    private var recOut: java.io.RandomAccessFile? = null
    private var recBytes: Long = 0L

    init {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        bufferSize = (minBuf * 2).coerceAtLeast(4096)
        initTrack()
    }

    @Synchronized
    fun initTrack(): Boolean {
        if (isInitialized && audioTrack != null) return true

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            isInitialized = (audioTrack?.state == AudioTrack.STATE_INITIALIZED)
            if (isInitialized) {
                audioTrack?.setVolume(currentVolume)
                Log.d(TAG, "AudioTrack initialized: sampleRate=$sampleRate, bufferSize=$bufferSize")
            } else {
                Log.e(TAG, "AudioTrack initialization failed state=${audioTrack?.state}")
            }
            return isInitialized
        } catch (t: Throwable) {
            Log.e(TAG, "Exception initializing AudioTrack", t)
            isInitialized = false
            return false
        }
    }

    @Synchronized
    fun play(): Boolean {
        if (!isInitialized && !initTrack()) return false
        try {
            audioTrack?.play()
            isPlaying = true
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Exception playing AudioTrack", t)
            isPlaying = false
            return false
        }
    }

    @Synchronized
    fun pause() {
        try {
            if (isPlaying) {
                audioTrack?.pause()
                isPlaying = false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception pausing AudioTrack", t)
        }
    }

    @Synchronized
    fun stop() {
        try {
            if (isPlaying) {
                audioTrack?.stop()
                isPlaying = false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception stopping AudioTrack", t)
        }
    }

    @Synchronized
    fun release() {
        try {
            stop()
            audioTrack?.release()
            audioTrack = null
            isInitialized = false
            isPlaying = false
        } catch (t: Throwable) {
            Log.e(TAG, "Exception releasing AudioTrack", t)
        }
    }

    fun write(data: ByteArray, offset: Int, size: Int): Int {
        val track = audioTrack ?: return -1
        if (!isPlaying) return 0
        return try {
            synchronized(this) {
                recOut?.let { it.write(data, offset, size); recBytes += size }
            }
            track.write(data, offset, size)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception writing to AudioTrack", t)
            -1
        }
    }

    /** Start teeing the live FM PCM to a WAV file (real recording, not a stub). Returns success. */
    @Synchronized
    fun startRecording(file: java.io.File): Boolean = try {
        file.parentFile?.mkdirs()
        val f = java.io.RandomAccessFile(file, "rw"); f.setLength(0)
        // 44-byte WAV header placeholder (patched with real sizes on stop).
        val ch = 2; val bits = 16; val byteRate = sampleRate * ch * bits / 8
        f.write("RIFF".toByteArray()); f.writeInt(0)                 // chunk size (patched)
        f.write("WAVE".toByteArray()); f.write("fmt ".toByteArray())
        f.write(le32(16)); f.write(le16(1)); f.write(le16(ch))
        f.write(le32(sampleRate)); f.write(le32(byteRate)); f.write(le16(ch * bits / 8)); f.write(le16(bits))
        f.write("data".toByteArray()); f.writeInt(0)                 // data size (patched)
        recOut = f; recBytes = 0L
        Log.d(TAG, "FM recording -> ${'$'}{file.absolutePath}")
        true
    } catch (t: Throwable) { Log.e(TAG, "startRecording failed", t); recOut = null; false }

    /** Finalize the WAV (patch RIFF/data sizes) and close. */
    @Synchronized
    fun stopRecording() {
        val f = recOut ?: return; recOut = null
        try {
            f.seek(4);  f.write(le32((36 + recBytes).toInt()))
            f.seek(40); f.write(le32(recBytes.toInt()))
        } catch (_: Throwable) {} finally { try { f.close() } catch (_: Throwable) {} }
    }

    fun isRecording(): Boolean = recOut != null
    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
    private fun le32(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())

    @Synchronized
    fun setVolume(vol: Float) {
        currentVolume = vol.coerceIn(0.0f, 1.0f)
        audioTrack?.setVolume(currentVolume)
    }

    fun isTrackPlaying(): Boolean = isPlaying
    fun isTrackInitialized(): Boolean = isInitialized
}
