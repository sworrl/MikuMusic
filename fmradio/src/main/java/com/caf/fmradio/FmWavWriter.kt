package com.caf.fmradio

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Tees the live FM PCM to a WAV file.
 *
 * Separate from [AudioTrackHelper] because recording and playback are not the same job here.
 * On every output except Bluetooth the audio reaches the user through the HAL's hardware
 * loopback and no AudioTrack is involved at all, so a recorder living inside the track class
 * could only work on A2DP. Stock FM2 splits them the same way — it has its own `stopWavHelper`
 * alongside `stopAudioTrackHelper`.
 */
class FmWavWriter(private val sampleRate: Int = 48000, private val channels: Int = 2) {

    companion object { private const val TAG = "FmWavWriter" }

    private var out: RandomAccessFile? = null
    private var bytes: Long = 0L
    private var target: File? = null

    @Synchronized
    fun start(file: File): Boolean = try {
        file.parentFile?.mkdirs()
        val f = RandomAccessFile(file, "rw")
        f.setLength(0)
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        f.write("RIFF".toByteArray()); f.write(le32(0))          // chunk size, patched on stop
        f.write("WAVE".toByteArray()); f.write("fmt ".toByteArray())
        f.write(le32(16)); f.write(le16(1)); f.write(le16(channels))
        f.write(le32(sampleRate)); f.write(le32(byteRate))
        f.write(le16(channels * bits / 8)); f.write(le16(bits))
        f.write("data".toByteArray()); f.write(le32(0))          // data size, patched on stop
        out = f; bytes = 0L; target = file
        Log.i(TAG, "recording to ${file.absolutePath}")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "could not start recording", t)
        out = null; target = null
        false
    }

    @Synchronized
    fun write(data: ByteArray, offset: Int, size: Int) {
        val f = out ?: return
        try {
            f.write(data, offset, size)
            bytes += size
        } catch (t: Throwable) {
            Log.e(TAG, "write failed, closing the recording", t)
            stop()
        }
    }

    /** Patch the two length fields the header could not know up front, then close. */
    @Synchronized
    fun stop(): File? {
        val f = out ?: return null
        out = null
        val file = target
        target = null
        try {
            f.seek(4); f.write(le32((36 + bytes).toInt()))
            f.seek(40); f.write(le32(bytes.toInt()))
        } catch (t: Throwable) {
            Log.w(TAG, "could not finalize the WAV header", t)
        } finally {
            runCatching { f.close() }
        }
        Log.i(TAG, "recording stopped at $bytes bytes")
        return file
    }

    @Synchronized fun isRecording(): Boolean = out != null
    @Synchronized fun bytesWritten(): Long = if (out != null) bytes else 0L

    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )
}
