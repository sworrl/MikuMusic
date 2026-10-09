package com.caf.fmradio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * 1:1 Kotlin port of HiBy's AudioTrackHelper from vendor FM2.
 *
 * The FM PCM reaches the app as a RADIO_TUNER capture stream at 48 kHz 16-bit stereo and is
 * written straight back out through this track. Attributes, format and buffer sizing match the
 * stock class, which is the only configuration this device is known to carry FM audio on.
 *
 * It carries nothing until the HAL's FM session is actually running: see [FmAudioRoute].
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
            track.write(data, offset, size)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception writing to AudioTrack", t)
            -1
        }
    }

    /**
     * Write decoded mono samples, duplicated to both channels.
     *
     * The capture is mono once the vendor framing is undone (see FmCaptureFrame), and the track
     * is stereo because that is the configuration this device carries FM on.
     */
    fun writeMono(samples: ShortArray, count: Int): Int {
        val track = audioTrack ?: return -1
        if (!isPlaying || count <= 0) return 0
        val need = count * 2
        var buf = stereoScratch
        if (buf == null || buf.size < need) { buf = ShortArray(need); stereoScratch = buf }
        for (i in 0 until count) { val v = samples[i]; buf[i * 2] = v; buf[i * 2 + 1] = v }
        return try {
            track.write(buf, 0, need)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception writing mono to AudioTrack", t)
            -1
        }
    }

    private var stereoScratch: ShortArray? = null

    @Synchronized
    fun setVolume(vol: Float) {
        currentVolume = vol.coerceIn(0.0f, 1.0f)
        audioTrack?.setVolume(currentVolume)
    }

    fun isTrackPlaying(): Boolean = isPlaying
    fun isTrackInitialized(): Boolean = isInitialized
}
