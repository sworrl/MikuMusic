package com.miku.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Receives the M500's live PCM and plays it out of the TV.
 *
 * The protocol constants are duplicated from the player's `MikuCastProtocol` rather than shared.
 * These are two separate applications installed on two different devices, so a shared module
 * would have to be published somewhere both builds can see; for nine bytes of framing that trade
 * is not worth it. If the wire format changes, both sides change together.
 *
 * WHAT "BIT-PERFECT" MEANS ON THIS END. The M500 sends the file's own samples at the file's own
 * rate. Whether they reach the TOSLINK port untouched is the TV's business, not ours: many sets
 * resample everything to 48kHz regardless of what the app asks for, and some clamp optical output
 * to 24/96 even when the panel reports more. So this asks for the stream's exact rate and
 * encoding, then reads back what `AudioTrack` actually granted and reports BOTH. A mismatch is
 * shown on screen rather than hidden, because claiming 24/192 while the hardware quietly
 * downsamples is the kind of fake telemetry this project exists to avoid.
 */
class MikuCastClient(private val ctx: Context) {

    companion object {
        private const val TAG = "MikuCastClient"
        const val PORT = 8796
        const val SERVICE_TYPE = "_mikucast._tcp"

        private const val HEADER_SIZE = 9
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'I'.code.toByte(), 'K'.code.toByte(), 'U'.code.toByte())

        private const val TYPE_FORMAT: Byte = 1
        private const val TYPE_PCM: Byte = 2
        private const val TYPE_META: Byte = 3
        private const val TYPE_CONTROL: Byte = 4
        private const val TYPE_PING: Byte = 5
        private const val TYPE_SKIP: Byte = 6
    }

    data class StreamFormat(
        val sampleRate: Int = 0,
        val bytesPerSample: Int = 0,
        val channelCount: Int = 0,
        val encoding: Int = 0,
    )

    data class ClientState(
        val connected: Boolean = false,
        val host: String = "",
        val status: String = "Looking for the M500",
        /** What the M500 said it is sending. */
        val source: StreamFormat = StreamFormat(),
        /** What this TV's AudioTrack actually accepted. Differs when the set resamples. */
        val grantedRate: Int = 0,
        val grantedEncodingLabel: String = "",
        /** True only when granted matches source exactly. */
        val passthroughExact: Boolean = false,
        val bytesPlayed: Long = 0L,
        val skips: Long = 0L,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val lastError: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var track: AudioTrack? = null

    private val _state = MutableStateFlow(ClientState())
    val state: StateFlow<ClientState> = _state

    /** Most recent PCM, for the visualiser. Single buffer, overwritten; readers copy if needed. */
    @Volatile var lastPcm: ByteArray? = null
        private set

    fun start(explicitHost: String? = null) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val host = explicitHost ?: discover()
                if (host == null) {
                    _state.value = _state.value.copy(status = "No M500 found on this network")
                    delay(5000)
                    continue
                }
                runSession(host)
                // Reconnect rather than giving up: the player is in a pocket and will come and go.
                _state.value = _state.value.copy(connected = false, status = "Reconnecting to $host")
                delay(2000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { socket?.close() }
        releaseTrack()
        _state.value = ClientState(status = "Stopped")
    }

    /** Send a transport command back to the M500. */
    fun send(cmd: String, extra: JSONObject? = null) {
        val o = out ?: return
        val body = (extra ?: JSONObject()).put("cmd", cmd).toString().toByteArray()
        runCatching {
            synchronized(o) {
                o.write(header(TYPE_CONTROL, body.size)); o.write(body); o.flush()
            }
        }.onFailure { Log.w(TAG, "control send failed: ${it.message}") }
    }

    // ---- session ----------------------------------------------------------

    private suspend fun runSession(host: String) {
        val s = Socket()
        try {
            _state.value = _state.value.copy(status = "Connecting to $host")
            s.connect(InetSocketAddress(host, PORT), 5000)
            s.tcpNoDelay = true
            s.keepAlive = true
            socket = s
            out = s.getOutputStream()
            _state.value = _state.value.copy(connected = true, host = host, status = "Connected", lastError = "")
            Log.i(TAG, "connected to $host")

            val input = DataInputStream(s.getInputStream())
            val head = ByteArray(HEADER_SIZE)
            while (s.isConnected && !s.isClosed) {
                input.readFully(head)
                if (!magicMatches(head)) continue
                val len = lengthOf(head)
                if (len < 0 || len > 1 shl 22) break
                when (head[4]) {
                    TYPE_FORMAT -> {
                        val b = ByteArray(len); input.readFully(b)
                        applyFormat(JSONObject(String(b)))
                    }
                    TYPE_PCM -> {
                        val b = ByteArray(len); input.readFully(b)
                        playPcm(b)
                    }
                    TYPE_META -> {
                        val b = ByteArray(len); input.readFully(b)
                        applyMeta(JSONObject(String(b)))
                    }
                    TYPE_SKIP -> {
                        val b = ByteArray(len); if (len > 0) input.readFully(b)
                        // The M500 dropped audio because we fell behind. Flush rather than play
                        // what is now seconds stale, and say so instead of drifting quietly.
                        runCatching { track?.flush() }
                        _state.value = _state.value.copy(
                            skips = _state.value.skips + 1,
                            status = "Resynced after a network stall"
                        )
                        Log.w(TAG, "stream skipped, flushed")
                    }
                    TYPE_PING -> { /* liveness */ }
                    else -> { if (len > 0) input.skipBytes(len) }
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "session ended: ${t.javaClass.simpleName}: ${t.message}")
            _state.value = _state.value.copy(lastError = t.localizedMessage ?: t.javaClass.simpleName)
        } finally {
            runCatching { s.close() }
            socket = null; out = null
            releaseTrack()
        }
    }

    private fun applyFormat(j: JSONObject) {
        val f = StreamFormat(
            sampleRate = j.optInt("sampleRate"),
            bytesPerSample = j.optInt("bytesPerSample"),
            channelCount = j.optInt("channelCount"),
            encoding = j.optInt("encoding"),
        )
        releaseTrack()

        // Map the sender's bytes-per-sample onto an AudioTrack encoding. 24-bit packed is asked
        // for explicitly where the platform supports it; where it does not, PCM_FLOAT would mean
        // converting every sample and is a different stream, so we report the mismatch instead of
        // silently substituting.
        val enc = when (f.bytesPerSample) {
            2 -> AudioFormat.ENCODING_PCM_16BIT
            3 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            4 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        val channelMask = when (f.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        val built = runCatching {
            val min = AudioTrack.getMinBufferSize(f.sampleRate, channelMask, enc).coerceAtLeast(8192)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(enc)
                        .setSampleRate(f.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(min * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.w(TAG, "AudioTrack at ${f.sampleRate}/${f.bytesPerSample * 8} refused: ${it.message}")
            _state.value = _state.value.copy(
                source = f,
                grantedRate = 0,
                grantedEncodingLabel = "refused",
                passthroughExact = false,
                status = "This TV refused ${f.sampleRate}Hz ${f.bytesPerSample * 8}-bit"
            )
            return
        }

        built.play()
        track = built

        // Read back what the hardware actually gave us. These can differ from what was asked.
        val grantedRate = runCatching { built.sampleRate }.getOrDefault(0)
        val grantedEnc = runCatching { built.audioFormat }.getOrDefault(0)
        val exact = grantedRate == f.sampleRate && grantedEnc == enc
        _state.value = _state.value.copy(
            source = f,
            grantedRate = grantedRate,
            grantedEncodingLabel = encodingLabel(grantedEnc),
            passthroughExact = exact,
            status = if (exact) "Playing" else "Playing, but the TV changed the format"
        )
        Log.i(TAG, "asked ${f.sampleRate}/${f.bytesPerSample * 8}, got $grantedRate/${encodingLabel(grantedEnc)} exact=$exact")
    }

    private fun playPcm(b: ByteArray) {
        lastPcm = b
        val t = track ?: return
        runCatching { t.write(b, 0, b.size, AudioTrack.WRITE_BLOCKING) }
            .onSuccess { _state.value = _state.value.copy(bytesPlayed = _state.value.bytesPlayed + b.size) }
    }

    private fun applyMeta(j: JSONObject) {
        _state.value = _state.value.copy(
            title = j.optString("title", ""),
            artist = j.optString("artist", ""),
            album = j.optString("album", ""),
        )
    }

    private fun releaseTrack() {
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
    }

    private fun encodingLabel(enc: Int): String = when (enc) {
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit packed"
        AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "float"
        else -> "enc $enc"
    }

    // ---- discovery --------------------------------------------------------

    /** Resolve the M500 over mDNS. Returns null if nothing answers within the window. */
    private suspend fun discover(): String? {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        var found: String? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("mikucast")) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        found = i.host?.hostAddress
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        return runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            repeat(50) { if (found != null) return@repeat; delay(100) }
            runCatching { nsd.stopServiceDiscovery(listener) }
            found
        }.getOrNull()
    }

    // ---- framing ----------------------------------------------------------

    private fun header(type: Byte, length: Int): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        System.arraycopy(MAGIC, 0, h, 0, 4)
        h[4] = type
        h[5] = (length ushr 24).toByte(); h[6] = (length ushr 16).toByte()
        h[7] = (length ushr 8).toByte(); h[8] = length.toByte()
        return h
    }

    private fun magicMatches(h: ByteArray) =
        h[0] == MAGIC[0] && h[1] == MAGIC[1] && h[2] == MAGIC[2] && h[3] == MAGIC[3]

    private fun lengthOf(h: ByteArray) =
        ((h[5].toInt() and 0xFF) shl 24) or ((h[6].toInt() and 0xFF) shl 16) or
            ((h[7].toInt() and 0xFF) shl 8) or (h[8].toInt() and 0xFF)
}
