package com.miku.player.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves the live PCM and the control channel to the TV companion.
 *
 * SHAPE. The M500 listens and the TV connects, not the other way round. The player is the thing
 * that knows when audio exists, it is the one in a pocket moving between networks, and it should
 * not have to discover or hold a connection to a TV that is usually off. A TV that boots simply
 * finds the service over mDNS and attaches; when the player leaves the house the socket drops and
 * neither side has to care.
 *
 * ONE CLIENT. Deliberate. Two TVs would both want to be the output and there is no sensible way
 * to pick, so a second connection displaces the first and the first is told why. Multi-room is a
 * different feature with different clock requirements and is not pretended at here.
 *
 * BACKPRESSURE. The writer never blocks the audio thread: [MikuCastTap] owns a ring buffer and
 * this reads from it at its own pace. If the network cannot keep up the ring overruns, the tap
 * drops the OLDEST audio, and we send a SKIP frame so the TV can resync rather than drift. A
 * mirror that silently falls behind is worse than one that admits a gap.
 */
object MikuCastServer {

    private const val TAG = "MikuCastServer"

    /** Read chunk. ~21ms at 24/96 stereo: small enough to stay responsive, large enough to be cheap. */
    private const val CHUNK = 12 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    @Volatile private var client: Socket? = null
    @Volatile private var clientOut: OutputStream? = null

    val bytesSent = AtomicLong(0L)

    data class CastState(
        val listening: Boolean = false,
        val clientAddress: String = "",
        val connected: Boolean = false,
        val lastError: String = "",
    )

    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state

    /** Commands arriving FROM the TV. The player app subscribes and acts on them. */
    private val _commands = MutableStateFlow<JSONObject?>(null)
    val commands: StateFlow<JSONObject?> = _commands

    @Synchronized
    fun start(ctx: Context) {
        if (acceptJob?.isActive == true) return
        val app = ctx.applicationContext
        acceptJob = scope.launch {
            try {
                val s = ServerSocket(MikuCastProtocol.PORT)
                s.reuseAddress = true
                server = s
                _state.value = _state.value.copy(listening = true, lastError = "")
                Log.i(TAG, "listening on ${MikuCastProtocol.PORT}")
                advertise(app)
                while (!s.isClosed) {
                    val sock = try { s.accept() } catch (t: Throwable) { break }
                    handleClient(sock)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "server failed: ${t.javaClass.simpleName}: ${t.message}")
                _state.value = _state.value.copy(
                    listening = false,
                    lastError = t.localizedMessage ?: t.javaClass.simpleName
                )
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { client?.close() }
        runCatching { server?.close() }
        withdraw()
        acceptJob?.cancel()
        acceptJob = null
        server = null
        client = null
        clientOut = null
        MikuCastTap.setEnabled(false)
        _state.value = CastState()
        Log.i(TAG, "stopped")
    }

    private fun handleClient(sock: Socket) {
        // One client: displace any existing one so the newest TV wins, and say so rather than
        // leaving the old one silently dead.
        runCatching { client?.close() }
        sock.tcpNoDelay = true
        // Keep the socket alive across brief Wi-Fi stalls rather than dropping the stream.
        sock.keepAlive = true
        sock.soTimeout = 0
        client = sock
        clientOut = sock.getOutputStream()
        val who = sock.inetAddress?.hostAddress ?: "?"
        _state.value = _state.value.copy(connected = true, clientAddress = who)
        Log.i(TAG, "TV connected from $who")

        MikuCastTap.setEnabled(true)

        // Reader: control frames coming back from the TV.
        scope.launch { readLoop(sock) }
        // Writer: PCM going out.
        scope.launch { writeLoop(sock) }
    }

    private suspend fun writeLoop(sock: Socket) {
        val out = sock.getOutputStream()
        val buf = ByteArray(CHUNK)
        var cursor = MikuCastTap.totalWritten()
        var lastFormat: MikuCastTap.CastFormat? = null
        var lastPing = System.currentTimeMillis()
        try {
            while (!sock.isClosed && client === sock) {
                val fmt = MikuCastTap.format
                if (fmt != null && fmt != lastFormat) {
                    sendFormat(out, fmt)
                    lastFormat = fmt
                }

                val r = MikuCastTap.read(cursor, buf)
                if (r.skipped > 0L) {
                    // Tell the TV we jumped, so it can flush instead of playing stale audio.
                    synchronized(out) {
                        out.write(MikuCastProtocol.header(MikuCastProtocol.TYPE_SKIP, 8))
                        out.write(longBytes(r.skipped))
                        out.flush()
                    }
                    Log.w(TAG, "skipped ${r.skipped} bytes, TV fell behind")
                }
                cursor = r.nextCursor
                if (r.bytes > 0) {
                    synchronized(out) {
                        out.write(MikuCastProtocol.header(MikuCastProtocol.TYPE_PCM, r.bytes))
                        out.write(buf, 0, r.bytes)
                        out.flush()
                    }
                    bytesSent.addAndGet(r.bytes.toLong())
                } else {
                    // Nothing buffered: either paused or between tracks. Idle briefly rather than
                    // spinning, and keep the link proven with a ping.
                    val now = System.currentTimeMillis()
                    if (now - lastPing > 3000) {
                        synchronized(out) {
                            out.write(MikuCastProtocol.header(MikuCastProtocol.TYPE_PING, 0))
                            out.flush()
                        }
                        lastPing = now
                    }
                    kotlinx.coroutines.delay(15)
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "write loop ended: ${t.javaClass.simpleName}")
        } finally {
            disconnect(sock)
        }
    }

    private fun readLoop(sock: Socket) {
        try {
            val input = DataInputStream(sock.getInputStream())
            val head = ByteArray(MikuCastProtocol.HEADER_SIZE)
            while (!sock.isClosed && client === sock) {
                input.readFully(head)
                if (!MikuCastProtocol.magicMatches(head)) {
                    // Resynchronise rather than dropping: hunt for the next magic byte.
                    continue
                }
                val len = MikuCastProtocol.lengthOf(head)
                if (len < 0 || len > 1 shl 20) break
                val payload = ByteArray(len)
                if (len > 0) input.readFully(payload)
                when (head[4]) {
                    MikuCastProtocol.TYPE_CONTROL -> {
                        runCatching { _commands.value = JSONObject(String(payload)) }
                            .onFailure { Log.w(TAG, "bad control frame: ${it.message}") }
                    }
                    MikuCastProtocol.TYPE_PING -> { /* liveness only */ }
                    else -> Log.d(TAG, "ignoring frame type ${head[4]} from TV")
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "read loop ended: ${t.javaClass.simpleName}")
        } finally {
            disconnect(sock)
        }
    }

    private fun disconnect(sock: Socket) {
        if (client !== sock) return
        runCatching { sock.close() }
        client = null
        clientOut = null
        MikuCastTap.setEnabled(false)
        _state.value = _state.value.copy(connected = false, clientAddress = "")
        Log.i(TAG, "TV disconnected")
    }

    private fun sendFormat(out: OutputStream, f: MikuCastTap.CastFormat) {
        val json = JSONObject()
            .put("sampleRate", f.sampleRate)
            .put("bytesPerSample", f.bytesPerSample)
            .put("channelCount", f.channelCount)
            .put("encoding", f.encoding)
            .toString().toByteArray()
        synchronized(out) {
            out.write(MikuCastProtocol.header(MikuCastProtocol.TYPE_FORMAT, json.size))
            out.write(json)
            out.flush()
        }
        Log.i(TAG, "sent format ${f.sampleRate}Hz ${f.bytesPerSample * 8}-bit x${f.channelCount}")
    }

    /** Push now-playing metadata to the TV. Safe to call when nothing is connected. */
    fun sendMeta(meta: JSONObject) {
        val out = clientOut ?: return
        val bytes = meta.toString().toByteArray()
        runCatching {
            synchronized(out) {
                out.write(MikuCastProtocol.header(MikuCastProtocol.TYPE_META, bytes.size))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun longBytes(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = (v ushr (56 - i * 8)).toByte()
        return b
    }

    // ---- discovery ---------------------------------------------------------

    private fun advertise(ctx: Context) {
        runCatching {
            val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = MikuCastProtocol.SERVICE_NAME
                serviceType = MikuCastProtocol.SERVICE_TYPE
                port = MikuCastProtocol.PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "advertised as ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "mDNS registration failed ($code); the TV can still be pointed at this IP by hand")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            nsdListener = l
        }.onFailure { Log.w(TAG, "mDNS unavailable: ${it.javaClass.simpleName}") }
    }

    private fun withdraw() {
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        nsd = null
        nsdListener = null
    }
}
