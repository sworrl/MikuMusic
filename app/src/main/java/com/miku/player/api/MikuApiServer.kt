package com.miku.player.api

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLServerSocketFactory

/**
 * Embedded High-Performance HTTP / HTTPS Server for Miku Music Remote API.
 * 
 * Features:
 * - Ultra-lightweight native socket HTTP/1.1 micro-engine (0 external dependencies).
 * - Full TLS (HTTPS) / Plain HTTP modes.
 * - Hardware HMAC-SHA256 signature authentication & replay defense.
 * - Multi-threaded non-blocking worker pool.
 * - mDNS Network Service Discovery registration (_miku-remote._tcp).
 */
object MikuApiServer {
    private const val TAG = "MikuApiServer"
    private const val SERVICE_TYPE = "_miku-remote._tcp."

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false
    private var threadPool: ExecutorService? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var router: MikuApiRouter? = null

    @Synchronized
    fun start(context: Context) {
        if (isRunning) {
            Log.d(TAG, "MikuApiServer already running")
            return
        }

        val app = context.applicationContext
        if (!MikuApiSecurity.isApiEnabled(app)) {
            Log.i(TAG, "MikuApiServer is disabled in preferences")
            return
        }

        router = MikuApiRouter(app)
        val port = MikuApiSecurity.getApiPort(app)
        val useHttps = MikuApiSecurity.isHttpsEnabled(app)

        try {
            val ss = if (useHttps) {
                val sslContext = MikuTlsContext.getOrCreateSslContext(app)
                val factory = sslContext.serverSocketFactory
                factory.createServerSocket(port)
            } else {
                ServerSocket(port)
            }

            serverSocket = ss
            isRunning = true
            threadPool = Executors.newFixedThreadPool(8)

            Log.i(TAG, "MikuApiServer started on port $port (HTTPS=$useHttps, AuthRequired=${MikuApiSecurity.isAuthRequired(app)})")

            threadPool?.execute {
                acceptLoop(app, ss)
            }

            registerNsdService(app, port)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start MikuApiServer on port $port", e)
            isRunning = false
            try { serverSocket?.close() } catch (_: Throwable) {}
            serverSocket = null
        }
    }

    @Synchronized
    fun stop(context: Context) {
        if (!isRunning) return
        isRunning = false
        Log.i(TAG, "Stopping MikuApiServer...")

        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null

        threadPool?.shutdownNow()
        threadPool = null

        unregisterNsdService(context)
        Log.i(TAG, "MikuApiServer stopped")
    }

    fun isServerRunning(): Boolean = isRunning

    private fun acceptLoop(context: Context, ss: ServerSocket) {
        while (isRunning && !ss.isClosed) {
            try {
                val socket = ss.accept()
                threadPool?.execute {
                    handleClientSocket(context, socket)
                }
            } catch (e: Throwable) {
                if (isRunning) {
                    Log.w(TAG, "Error accepting client connection", e)
                }
            }
        }
    }

    private fun handleClientSocket(context: Context, socket: Socket) {
        try {
            socket.soTimeout = 10_000 // 10s I/O timeout
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0

            while (true) {
                line = readLine(input)
                if (line.isNullOrEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            // CORS pre-flight OPTIONS request
            if (method == "OPTIONS") {
                sendCorsOptionsResponse(output)
                return
            }

            // Read Request Body
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = input.read(buf, read, contentLength - read)
                    if (count <= 0) break
                    read += count
                }
                buf
            } else {
                ByteArray(0)
            }

            // Parse Path and Query Parameters
            val path = if (rawPath.contains("?")) rawPath.substringBefore("?") else rawPath
            val queryParams = parseQueryParams(rawPath)

            // Security Authentication Verification (HMAC-SHA256 / Bearer Token)
            val auth = MikuApiSecurity.verifyRequest(context, method, path, headers, body)
            if (!auth.isAuthorized) {
                Log.w(TAG, "Unauthorized request $method $path: ${auth.message}")
                val err = ApiResponse.error(401, "Unauthorized: ${auth.message}")
                sendResponse(output, err)
                return
            }

            // Dispatch to Router
            val r = router ?: MikuApiRouter(context)
            val response = try {
                r.handleRequest(method, path, queryParams, body)
            } catch (e: Throwable) {
                Log.e(TAG, "Error handling $method $path", e)
                ApiResponse.error(500, "Internal server error: ${e.message}")
            }

            sendResponse(output, response)
        } catch (e: Throwable) {
            Log.w(TAG, "Client socket handler exception: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun sendResponse(output: OutputStream, res: ApiResponse) {
        val statusText = when (res.statusCode) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }

        val sb = StringBuilder()
        sb.append("HTTP/1.1 ${res.statusCode} $statusText\r\n")
        sb.append("Content-Type: ${res.contentType}\r\n")
        sb.append("Content-Length: ${res.body.size}\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Authorization, Content-Type, X-Miku-Signature, X-Miku-Timestamp, X-Miku-Nonce, X-Miku-Key\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")

        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (res.body.isNotEmpty()) {
            output.write(res.body)
        }
        output.flush()
    }

    private fun sendCorsOptionsResponse(output: OutputStream) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 204 No Content\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Authorization, Content-Type, X-Miku-Signature, X-Miku-Timestamp, X-Miku-Nonce, X-Miku-Key\r\n")
        sb.append("Access-Control-Max-Age: 86400\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var b: Int
        while (true) {
            b = input.read()
            if (b == -1) {
                if (baos.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                baos.write(b)
            }
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private fun parseQueryParams(url: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val queryIdx = url.indexOf('?')
        if (queryIdx < 0 || queryIdx >= url.length - 1) return map
        val queryStr = url.substring(queryIdx + 1)
        val pairs = queryStr.split("&")
        for (p in pairs) {
            val kv = p.split("=")
            if (kv.isNotEmpty()) {
                val k = URLDecoder.decode(kv[0], "UTF-8")
                val v = if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else ""
                map[k] = v
            }
        }
        return map
    }

    private fun registerNsdService(context: Context, port: Int) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "MikuMusic-M500"
                serviceType = SERVICE_TYPE
                setPort(port)
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS Service successfully registered: ${serviceInfo.serviceName} on port $port")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS Service unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS Unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register mDNS service", e)
        }
    }

    private fun unregisterNsdService(context: Context) {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Throwable) {}
        registrationListener = null
        nsdManager = null
    }
}
