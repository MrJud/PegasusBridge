package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A very small HTTP/1.1 server, bound to loopback only.
 *
 * Hand-written rather than `com.sun.net.httpserver` because that package is not
 * part of the Android API, and the whole point of the HTTP contract is that the
 * *same* server runs on desktop and on Android. Nothing here is general-purpose:
 * it serves a handful of local endpoints returning JSON, so it always closes the
 * connection and never negotiates encodings.
 */
class MicroHttpServer(
    private val requestedPort: Int = 0,
    private val workers: Int = 8,
    private val handler: (Request) -> Response
) {

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String
    ) {
        fun param(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }
        fun intParam(name: String): Int? = param(name)?.toIntOrNull()
    }

    data class Response(
        val status: Int = 200,
        val body: String = "",
        val contentType: String = "application/json; charset=utf-8"
    ) {
        companion object {
            fun json(body: String) = Response(200, body)
            fun badRequest(message: String) = error(400, message)
            fun notFound(message: String = "unknown endpoint") = error(404, message)
            fun serverError(message: String) = error(500, message)

            private fun error(status: Int, message: String) = Response(
                status,
                """{"status":"error","error":${quote(message)}}"""
            )

            /** Minimal JSON string escaping — enough for error messages. */
            fun quote(s: String): String {
                val sb = StringBuilder("\"")
                for (c in s.take(500)) when (c) {
                    '"'  -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
                return sb.append('"').toString()
            }
        }
    }

    private var server: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    @Volatile private var running = false

    /** The port actually bound. Meaningful only after [start]. */
    var port: Int = 0
        private set

    fun start() {
        if (running) return
        // Loopback only: this API is for the local frontend, never the network.
        val s = ServerSocket(requestedPort, 50, InetAddress.getLoopbackAddress())
        server = s
        port = s.localPort
        running = true
        pool = Executors.newFixedThreadPool(workers) as ThreadPoolExecutor

        Thread({
            while (running) {
                val socket = try { s.accept() } catch (e: IOException) { if (running) BridgeLog.w(TAG, "accept failed: ${e.message}"); break }
                try {
                    pool?.execute { serve(socket) }
                } catch (e: Exception) {
                    // Pool saturated or shutting down — never leak the socket.
                    runCatching { socket.close() }
                }
            }
        }, "bridge-http-accept").apply { isDaemon = true }.start()

        BridgeLog.i(TAG, "listening on 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool?.shutdown()
        runCatching { pool?.awaitTermination(2, TimeUnit.SECONDS) }
        BridgeLog.i(TAG, "stopped")
    }

    private fun serve(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = READ_TIMEOUT_MS
            try {
                val input = BufferedInputStream(sock.getInputStream())
                val request = parse(input) ?: run {
                    write(sock.getOutputStream(), Response.badRequest("malformed request"))
                    return
                }
                val response = try {
                    handler(request)
                } catch (t: Throwable) {
                    BridgeLog.e(TAG, "handler threw for ${request.path}", t)
                    Response.serverError(t.message ?: t.javaClass.simpleName)
                }
                write(sock.getOutputStream(), response)
            } catch (e: IOException) {
                // Client hung up mid-request; nothing useful to do.
            }
        }
    }

    private fun parse(input: BufferedInputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                line.substring(idx + 1).trim()
        }

        val body = headers["content-length"]?.toIntOrNull()?.takeIf { it > 0 }?.let { len ->
            val capped = len.coerceAtMost(MAX_BODY_BYTES)
            val buf = ByteArray(capped)
            var read = 0
            while (read < capped) {
                val n = input.read(buf, read, capped - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } ?: ""

        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = if (qIdx >= 0) parseQuery(target.substring(qIdx + 1)) else emptyMap()

        return Request(method, path.trimEnd('/').ifEmpty { "/" }, query, headers, body)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i >= 0) pair.substring(0, i) else pair
            val v = if (i >= 0) pair.substring(i + 1) else ""
            runCatching {
                out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            }
        }
        return out
    }

    /** Reads a CRLF- or LF-terminated line without over-reading into the body. */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(c.toChar())
            if (sb.length > MAX_LINE) return null
        }
    }

    private fun write(out: OutputStream, response: Response) {
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ')
                .append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            // The frontend is a QML XMLHttpRequest, which may present an origin.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 500 -> "Internal Server Error"
        else -> "OK"
    }

    private companion object {
        const val TAG = "MicroHttpServer"
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_LINE = 8_192
        const val MAX_BODY_BYTES = 1 shl 20
    }
}
