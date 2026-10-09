package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MicroHttpServerTest {

    private lateinit var server: MicroHttpServer
    private var lastRequest: MicroHttpServer.Request? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun startWith(handler: (MicroHttpServer.Request) -> MicroHttpServer.Response) {
        server = MicroHttpServer(handler = handler)
        server.start()
    }

    private fun get(pathAndQuery: String, vararg headers: Pair<String, String>) =
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}$pathAndQuery")
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()).execute()

    /**
     * A request written out byte for byte, and everything that came back. For
     * what OkHttp will not send: no Host, a header's name in capitals, a value
     * with an escape in it.
     */
    private fun raw(vararg lines: String): String = Socket("127.0.0.1", server.port).use { s ->
        s.getOutputStream().write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        s.getOutputStream().flush()
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun statusLine(response: String) = response.lineSequence().first().trim()

    /** A server whose handler counts its calls, for a test that needs it not to be called. */
    private fun startCounting(): AtomicInteger {
        val reached = AtomicInteger()
        startWith { reached.incrementAndGet(); MicroHttpServer.Response.json("""{"ok":true}""") }
        return reached
    }

    /** Keeps what the server logs, with the level each line was written at. */
    private class RecordingLog : BridgeLog {
        val lines = CopyOnWriteArrayList<String>()
        override fun d(tag: String, msg: String) { lines += "D $msg" }
        override fun i(tag: String, msg: String) { lines += "I $msg" }
        override fun w(tag: String, msg: String, t: Throwable?) { lines += "W $msg" }
        override fun e(tag: String, msg: String, t: Throwable?) { lines += "E $msg" }
        fun refusals() = lines.filter { "refused" in it }
    }

    @BeforeTest fun setUp() { BridgeLog.current = NoopLog }

    @AfterTest fun tearDown() {
        if (::server.isInitialized) server.stop()
        BridgeLog.current = StderrLog
    }

    @Test fun `serves a json body with the right status and headers`() {
        startWith { MicroHttpServer.Response.json("""{"ok":true}""") }
        get("/health").use { r ->
            assertEquals(200, r.code)
            assertEquals("""{"ok":true}""", r.body!!.string())
            assertTrue(r.header("Content-Type")!!.startsWith("application/json"))
            assertEquals("no-store", r.header("Cache-Control"))
        }
    }

    @Test fun `parses path and query, decoding percent escapes`() {
        startWith { req -> lastRequest = req; MicroHttpServer.Response.json("{}") }
        get("/scrape?source=sgdb&term=Super%20Mario%20Bros.%20%28USA%29&gameId=42").use { it.body!!.string() }

        val r = lastRequest!!
        assertEquals("GET", r.method)
        assertEquals("/scrape", r.path)
        assertEquals("sgdb", r.param("source"))
        assertEquals("Super Mario Bros. (USA)", r.param("term"), "percent-decoding failed")
        assertEquals(42, r.intParam("gameId"))
        assertNull(r.param("missing"))
    }

    @Test fun `an ampersand inside a value survives encoding`() {
        startWith { req -> lastRequest = req; MicroHttpServer.Response.json("{}") }
        get("/x?term=Tom%20%26%20Jerry").use { it.body!!.string() }
        assertEquals("Tom & Jerry", lastRequest!!.param("term"))
    }

    @Test fun `a trailing slash resolves to the same path`() {
        startWith { req -> lastRequest = req; MicroHttpServer.Response.json("{}") }
        get("/health/").use { it.body!!.string() }
        assertEquals("/health", lastRequest!!.path)
    }

    @Test fun `reads a post body`() {
        startWith { req -> lastRequest = req; MicroHttpServer.Response.json("{}") }
        val body = """{"sgdbKey":"SECRET"}"""
        client.newCall(Request.Builder()
            .url("http://127.0.0.1:${server.port}/credentials")
            .post(body.toRequestBody()).build()).execute().use { it.body!!.string() }

        assertEquals("POST", lastRequest!!.method)
        assertEquals(body, lastRequest!!.body)
    }

    @Test fun `a handler that throws becomes a 500 instead of killing the server`() {
        val calls = AtomicInteger()
        startWith { req ->
            if (calls.incrementAndGet() == 1) throw IllegalStateException("boom")
            MicroHttpServer.Response.json("""{"ok":true}""")
        }
        get("/x").use { r ->
            assertEquals(500, r.code)
            assertTrue(r.body!!.string().contains("boom"))
        }
        // the server must still be serving
        get("/x").use { r -> assertEquals(200, r.code) }
    }

    @Test fun `error bodies are valid json even with quotes and newlines`() {
        startWith { MicroHttpServer.Response.badRequest("bad \"input\"\nsecond line") }
        get("/x").use { r ->
            assertEquals(400, r.code)
            val body = r.body!!.string()
            // must parse as JSON rather than being broken by the raw quotes
            val parsed = org.json.JSONObject(body)
            assertEquals("error", parsed.getString("status"))
            assertTrue(parsed.getString("error").contains("bad \"input\""))
        }
    }

    @Test fun `serves concurrent requests`() {
        startWith { MicroHttpServer.Response.json("""{"ok":true}""") }
        val n = 20
        val latch = CountDownLatch(n)
        val ok = AtomicInteger()
        repeat(n) {
            Thread {
                runCatching { get("/x").use { r -> if (r.code == 200) ok.incrementAndGet() } }
                latch.countDown()
            }.start()
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS), "requests did not finish")
        assertEquals(n, ok.get())
    }

    // The API is for the local frontend; it must not be reachable from the network.
    @Test fun `binds to loopback only`() {
        startWith { MicroHttpServer.Response.json("{}") }
        val nonLoopback = InetAddress.getAllByName(InetAddress.getLocalHost().hostName)
            .firstOrNull { !it.isLoopbackAddress }
        if (nonLoopback == null) return  // single-interface machine, nothing to assert

        val reachable = runCatching {
            Socket().use { it.connect(java.net.InetSocketAddress(nonLoopback, server.port), 1000) }
            true
        }.getOrDefault(false)
        assertTrue(!reachable, "server must not accept connections on ${nonLoopback.hostAddress}")
    }

    @Test fun `garbage input does not take the server down`() {
        startWith { MicroHttpServer.Response.json("""{"ok":true}""") }
        runCatching {
            Socket("127.0.0.1", server.port).use { s ->
                s.getOutputStream().write("not a real request\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                s.getInputStream().readBytes()
            }
        }
        get("/x").use { r -> assertEquals(200, r.code) }
    }

    @Test fun `stop releases the port`() {
        startWith { MicroHttpServer.Response.json("{}") }
        val p = server.port
        server.stop()
        Thread.sleep(200)
        val rebound = runCatching { MicroHttpServer(requestedPort = p) { MicroHttpServer.Response.json("{}") }.also { it.start() } }
        assertTrue(rebound.isSuccess, "port $p was not released")
        rebound.getOrNull()?.stop()
    }

    // ── Requests a browser makes ─────────────────────────────────────────────
    //
    // The headers below are the ones a browser writes and a page cannot. Each
    // test goes through the socket, as a browser would, and where it says a
    // request is refused it also says the handler was never called: a refusal
    // that arrives after the write is no use to anyone.

    // What `curl http://127.0.0.1:<port>/health` sends, to the letter.
    @Test fun `a request with no header but Host is served, as curl sends it`() {
        val reached = startCounting()
        val r = raw("GET /health HTTP/1.1", "Host: 127.0.0.1:${server.port}",
                    "User-Agent: curl/8.22.0", "Accept: */*")
        assertEquals("HTTP/1.1 200 OK", statusLine(r))
        assertTrue(r.endsWith("""{"ok":true}"""))
        assertEquals(1, reached.get())
    }

    // The request head of a QML XMLHttpRequest, as Qt 5.15.19 wrote it to a
    // listener: these five headers, in this order, for the synchronous call
    // and the asynchronous one alike. Qt 6.12 differs only in Accept-Encoding.
    // No Origin and no header that begins Sec-, which is why every request
    // that has one can be refused, and the answer is read with no
    // Access-Control-Allow-Origin in it.
    @Test fun `the request the theme makes is served`() {
        startWith { req -> lastRequest = req; MicroHttpServer.Response.json("""{"ok":true}""") }
        val r = raw("GET /emulators/apply?directory=%2Froms%2Fnes&launch=x HTTP/1.1",
                    "Host: 127.0.0.1:${server.port}",
                    "Connection: Keep-Alive",
                    "Accept-Encoding: gzip, deflate",
                    "Accept-Language: it-IT,en,*",
                    "User-Agent: Mozilla/5.0")
        assertEquals("HTTP/1.1 200 OK", statusLine(r))
        assertTrue(r.endsWith("""{"ok":true}"""))
        assertEquals("/emulators/apply", lastRequest!!.path)
        assertEquals("/roms/nes", lastRequest!!.param("directory"))
    }

    @Test fun `a Host that names this machine is served, on any port`() {
        val reached = startCounting()
        val hosts = listOf(
            "127.0.0.1:${server.port}", "127.0.0.1", "localhost:${server.port}", "localhost",
            "[::1]:${server.port}", "[::1]", "LocalHost:${server.port}",
            // Under socket activation the theme names the port systemd holds
            // and the proxy passes the bytes on: not the port bound here.
            "127.0.0.1:38700")
        for (host in hosts) get("/health", "Host" to host).use { r ->
            assertEquals(200, r.code, "Host: $host")
        }
        assertEquals(hosts.size, reached.get())
    }

    @Test fun `a Host that names anything else is refused`() {
        val reached = startCounting()
        val hosts = listOf(
            "attacker.invalid", "attacker.invalid:${server.port}",
            // Names made to look like the three that pass.
            "127.0.0.1.attacker.invalid", "localhost.attacker.invalid:${server.port}",
            "attacker.invalid.localhost", "xlocalhost", "127.0.0.1x", "127.0.0.11",
            "127-0-0-1", "127a0b0c1:${server.port}",
            "[::1].attacker.invalid", "[::1", "::1",
            // Addresses that reach this socket or this machine and are not its name.
            "0.0.0.0:${server.port}", "127.0.0.2", "192.168.1.10:${server.port}",
            "127.0.0.1:", "127.0.0.1:port", "127.0.0.1:123456", "localhost.",
            "127.0.0.1 attacker.invalid", "attacker.invalid,127.0.0.1:${server.port}")
        for (host in hosts) get("/health", "Host" to host).use { r ->
            assertEquals(403, r.code, "Host: $host")
        }
        assertEquals(0, reached.get(), "a refused request reached the handler")
    }

    @Test fun `an empty Host is refused`() {
        val reached = startCounting()
        assertEquals("HTTP/1.1 403 Forbidden", statusLine(raw("GET /health HTTP/1.1", "Host:")))
        assertEquals(0, reached.get())
    }

    // An HTTP/1.0 client sends no Host, and no browser sends a request
    // without one, so there is nothing here for the rule to catch.
    @Test fun `a request with no Host at all is served`() {
        val reached = startCounting()
        assertEquals("HTTP/1.1 200 OK", statusLine(raw("GET /health HTTP/1.0")))
        assertEquals(1, reached.get())
    }

    // A page that has pointed its own name at 127.0.0.1, as Chrome 155 asked
    // for it when the name was mapped there. On plain http under a name of its
    // own the browser adds no Sec-Fetch-Site and no Origin: only Host gives
    // the request away.
    @Test fun `a rebound name is refused, and Host is all that gives it away`() {
        val reached = startCounting()
        val head = arrayOf(
            "GET /emulators/apply?directory=%2Froms%2Fnes&launch=x HTTP/1.1",
            "Host: rebound.test:${server.port}",
            "Connection: keep-alive",
            "Upgrade-Insecure-Requests: 1",
            "User-Agent: $CHROME",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif," +
                "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Encoding: gzip, deflate",
            "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
        assertEquals("HTTP/1.1 403 Forbidden", statusLine(raw(*head)))
        assertEquals(0, reached.get())
        // The same bytes under the daemon's own name are a request like curl's.
        head[1] = "Host: 127.0.0.1:${server.port}"
        assertEquals("HTTP/1.1 200 OK", statusLine(raw(*head)))
        assertEquals(1, reached.get())
    }

    // Two Hosts, of which a map keeps the second: refused first and passed
    // second would be served. RFC 9112 has it answered 400 in any order.
    @Test fun `a request with two Hosts is malformed, whichever comes first`() {
        val reached = startCounting()
        val here = "Host: 127.0.0.1:${server.port}"
        for (hosts in listOf(listOf("Host: attacker.invalid", here), listOf(here, "Host: attacker.invalid"),
                             listOf(here, here), listOf("HOST: attacker.invalid", "host: 127.0.0.1"))) {
            val r = raw("GET /emulators/apply?directory=%2Froms%2Fnes&launch=x HTTP/1.1", *hosts.toTypedArray())
            assertEquals("HTTP/1.1 400 Bad Request", statusLine(r), "$hosts")
        }
        assertEquals(0, reached.get(), "a request with two Hosts reached the handler")
    }

    // HTTP allows a space or a tab round a header's value and nothing else.
    // A no-break space or a control character after 127.0.0.1 makes another
    // name of it, and the bytes here are the ones sent: 0xA0, 0x1F, 0x0B.
    @Test fun `only a space or a tab is taken off a header's value`() {
        val reached = startCounting()
        for (host in listOf("127.0.0.1 ", " 127.0.0.1", "127.0.0.1:${server.port}\u001f",
                            "\u000b127.0.0.1", "localhost\u000c")) {
            assertEquals("HTTP/1.1 403 Forbidden", statusLine(raw("GET /health HTTP/1.1", "Host: $host")),
                         "Host: ${host.map { it.code }}")
        }
        assertEquals(0, reached.get())
        assertEquals("HTTP/1.1 200 OK", statusLine(raw("GET /health HTTP/1.1", "Host:\t 127.0.0.1:${server.port} \t ")))
        assertEquals(1, reached.get())
    }

    // `none` is what the browser says of an address the person typed, and
    // `same-origin` of a page the daemon served. Neither is served: the daemon
    // serves no page, and `none` is on a prefetch a page asked for and on a
    // redirect from any site the person opens (the two tests after this one).
    @Test fun `a request that says Sec-Fetch-Site is refused, whatever it says`() {
        val reached = startCounting()
        for (site in listOf("cross-site", "same-site", "same-origin", "none", "something-new", "")) {
            get("/emulators/apply?directory=/roms/nes&launch=x", "Sec-Fetch-Site" to site).use { r ->
                assertEquals(403, r.code, "Sec-Fetch-Site: $site")
            }
            get("/health", "Sec-Fetch-Site" to site).use { r -> assertEquals(403, r.code, "Sec-Fetch-Site: $site") }
        }
        assertEquals(0, reached.get(), "a refused request reached the handler")
    }

    // The request Chrome 155 made, byte for byte but for the port, for a page
    // on another site that held a speculation rule naming this URL. Nobody
    // clicked anything, and the browser calls it `none`.
    @Test fun `a prefetch a page asked the browser for is refused`() {
        val reached = startCounting()
        val r = raw("GET /emulators/apply?directory=%2Froms%2Fnes&launch=planted-by-prefetch HTTP/1.1",
                    "Host: 127.0.0.1:${server.port}",
                    "Connection: keep-alive",
                    "Upgrade-Insecure-Requests: 1",
                    "Sec-Purpose: prefetch",
                    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif," +
                        "image/webp,image/apng,*/*;q=0.8",
                    """sec-ch-ua: "Google Chrome";v="155", "Chromium";v="155", "Not(A:Brand";v="24"""",
                    """sec-ch-ua-platform: "Linux"""",
                    "sec-ch-ua-mobile: ?0",
                    "Sec-Fetch-Site: none",
                    "Sec-Fetch-Mode: navigate",
                    "Sec-Fetch-Dest: document",
                    "Referer: http://localhost:3000/",
                    "User-Agent: $CHROME",
                    "Accept-Encoding: gzip, deflate, br",
                    "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
        assertEquals("HTTP/1.1 403 Forbidden", statusLine(r))
        assertEquals(0, reached.get())
    }

    // What Chrome 155 sent after it was given the address of another site,
    // which answered 302 with this URL in Location. An address typed by hand
    // arrives the same, so the person at the browser is refused with it.
    @Test fun `an address typed in a browser, or redirected to from one, is refused`() {
        val reached = startCounting()
        val r = raw("GET /emulators/apply?directory=%2Froms%2Fnes&launch=planted-by-redirect HTTP/1.1",
                    "Host: 127.0.0.1:${server.port}",
                    "Connection: keep-alive",
                    "Upgrade-Insecure-Requests: 1",
                    "User-Agent: $CHROME",
                    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif," +
                        "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Sec-Fetch-Site: none",
                    "Sec-Fetch-Mode: navigate",
                    "Sec-Fetch-User: ?1",
                    "Sec-Fetch-Dest: document",
                    """sec-ch-ua: "Google Chrome";v="155", "Chromium";v="155", "Not(A:Brand";v="24"""",
                    "sec-ch-ua-mobile: ?0",
                    """sec-ch-ua-platform: "Linux"""",
                    "Accept-Encoding: gzip, deflate, br, zstd",
                    "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
        assertEquals("HTTP/1.1 403 Forbidden", statusLine(r))
        assertEquals(0, reached.get())
    }

    // The handshake Chrome 155 sent for `new WebSocket(...)` on a page served
    // from another port of this machine: a GET with that page's loopback
    // Origin and no Sec-Fetch-Site in it. The daemon speaks no WebSocket, and
    // the handler would have run on the GET all the same.
    @Test fun `a WebSocket handshake from a page on another port of this machine is refused`() {
        val reached = startCounting()
        val r = raw("GET /emulators/apply?directory=%2Froms%2Fnes&launch=planted-by-websocket HTTP/1.1",
                    "Host: 127.0.0.1:${server.port}",
                    "Connection: Upgrade",
                    "Pragma: no-cache",
                    "Cache-Control: no-cache",
                    "User-Agent: $CHROME",
                    "Upgrade: websocket",
                    "Origin: http://localhost:3000",
                    "Sec-WebSocket-Version: 13",
                    "Accept-Encoding: gzip, deflate, br, zstd",
                    "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Sec-WebSocket-Key: QSDwO7KamAKEh6Zj7szF+A==",
                    "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits")
        assertEquals("HTTP/1.1 403 Forbidden", statusLine(r))
        assertEquals(0, reached.get())
    }

    // The theme sends no Origin, so nothing the daemon serves needs one, and
    // an origin that looks near is not a friend: one on this machine is a page
    // served from another port, and `null` a sandboxed frame or a local file.
    @Test fun `a request with an Origin is refused, one on this machine and null included`() {
        val reached = startCounting()
        val origins = listOf(
            "http://127.0.0.1:${server.port}", "http://127.0.0.1", "http://localhost:3000",
            "http://[::1]:${server.port}", "https://localhost:8443", "null",
            "https://attacker.invalid", "http://attacker.invalid:${server.port}", "file://", "")
        for (origin in origins) get("/health", "Origin" to origin).use { r ->
            assertEquals(403, r.code, "Origin: $origin")
        }
        assertEquals(0, reached.get(), "a refused request reached the handler")
    }

    // Sec- begins the names a browser keeps for itself, and each of these was
    // in a request Chrome 155 made: on a prefetch, in a WebSocket handshake,
    // on every request to this address. The last is one nobody has defined.
    @Test fun `a request with any header that begins Sec- is refused`() {
        val reached = startCounting()
        val headers = listOf(
            "Sec-Purpose" to "prefetch", "Sec-WebSocket-Key" to "QSDwO7KamAKEh6Zj7szF+A==",
            "sec-ch-ua-mobile" to "?0", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Dest" to "document",
            "Sec-GPC" to "1", "Sec-Something-New" to "")
        for (header in headers) get("/health", header).use { r -> assertEquals(403, r.code, "$header") }
        assertEquals(0, reached.get(), "a refused request reached the handler")
    }

    // The prefix and the hyphen, at the start of the name: not every header
    // with those three letters in it.
    @Test fun `a header that only looks like one of a browser's is served`() {
        val reached = startCounting()
        val headers = listOf(
            "Sec" to "1", "Security" to "1", "X-Sec-Fetch-Site" to "cross-site", "X-Origin" to "https://attacker.invalid",
            "Original" to "1", "Upgrade-Insecure-Requests" to "1", "Referer" to "https://attacker.invalid/")
        for (header in headers) get("/health", header).use { r -> assertEquals(200, r.code, "$header") }
        assertEquals(headers.size, reached.get())
    }

    // One rule is enough, with nothing else amiss in the request.
    @Test fun `each rule refuses alone`() {
        val reached = startCounting()
        val here = "127.0.0.1:${server.port}"
        val requests = listOf(
            listOf("Host" to "attacker.invalid"),
            listOf("Host" to here, "Sec-Fetch-Site" to "same-origin"),
            listOf("Host" to here, "Origin" to "http://$here"),
            listOf("Host" to here, "Sec-Purpose" to "prefetch"))
        for (headers in requests) get("/health", *headers.toTypedArray()).use { r ->
            assertEquals(403, r.code, "$headers")
        }
        assertEquals(0, reached.get(), "a refused request reached the handler")
        get("/health", "Host" to here).use { r -> assertEquals(200, r.code) }
        assertEquals(1, reached.get())
    }

    // A header's name is the same name in any case, and HTTP/2-minded clients
    // write them all in lower case.
    @Test fun `the headers are read whatever the case of their names`() {
        val reached = startCounting()
        val host = "127.0.0.1:${server.port}"
        val refused = listOf(
            listOf("GET /health HTTP/1.1", "HOST: attacker.invalid"),
            listOf("GET /health HTTP/1.1", "host: attacker.invalid"),
            listOf("GET /health HTTP/1.1", "Host: $host", "SEC-FETCH-SITE: none"),
            listOf("GET /health HTTP/1.1", "Host: $host", "sec-fetch-site: none"),
            listOf("GET /health HTTP/1.1", "Host: $host", "SEC-PURPOSE: prefetch"),
            listOf("GET /health HTTP/1.1", "Host: $host", "sec-purpose: prefetch"),
            listOf("GET /health HTTP/1.1", "Host: $host", "ORIGIN: http://$host"),
            listOf("GET /health HTTP/1.1", "Host: $host", "origin: http://$host"))
        for (lines in refused) {
            assertEquals("HTTP/1.1 403 Forbidden", statusLine(raw(*lines.toTypedArray())), "$lines")
        }
        assertEquals(0, reached.get(), "a refused request reached the handler")
    }

    @Test fun `a refused request never reaches the handler, whatever the method`() {
        val reached = startCounting()
        val body = """{"sgdbKey":"SECRET"}"""
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/credentials")
            .header("Origin", "https://attacker.invalid").post(body.toRequestBody()).build())
            .execute().use { r -> assertEquals(403, r.code) }
        get("/emulators/apply?directory=/roms/nes&launch=x", "Sec-Fetch-Site" to "cross-site").use { r ->
            assertEquals(403, r.code)
        }
        assertEquals(0, reached.get())
        // And the server is as it was for the next caller.
        get("/health").use { r -> assertEquals(200, r.code) }
        assertEquals(1, reached.get())
    }

    @Test fun `a refusal is a 403 with the json body every other error has`() {
        val reached = startCounting()
        val r = raw("GET /health HTTP/1.1", "Host: 127.0.0.1:${server.port}", "Origin: https://attacker.invalid")
        assertEquals("HTTP/1.1 403 Forbidden", statusLine(r))
        val messages = listOf("Origin" to "https://attacker.invalid", "Host" to "attacker.invalid").map { header ->
            get("/health", header).use { resp ->
                assertEquals(403, resp.code)
                assertTrue(resp.header("Content-Type")!!.startsWith("application/json"))
                val parsed = org.json.JSONObject(resp.body!!.string())
                assertEquals("error", parsed.getString("status"))
                val error = parsed.getString("error")
                assertTrue(error.startsWith("refused"), error)
                // Short, and nothing of what was sent comes back in it.
                assertTrue(error.length < 100, error)
                assertFalse("attacker" in error, error)
                error
            }
        }
        // A person who opened the address in a browser is told what to use
        // instead, and one who named the daemon wrongly is told its names.
        assertTrue("browser" in messages[0] && "curl" in messages[0], messages[0])
        assertTrue("127.0.0.1" in messages[1] && "localhost" in messages[1], messages[1])
        assertEquals(0, reached.get())
    }

    // `Access-Control-Allow-Origin: *` was on every answer, and it is what
    // lets a page of another origin read one. The theme does not look for it.
    @Test fun `no answer carries Access-Control-Allow-Origin`() {
        val reached = startCounting()
        val answers = listOf(
            get("/health"),
            get("/health", "Origin" to "http://127.0.0.1:${server.port}"),
            get("/health", "Origin" to "https://attacker.invalid"),
            get("/health", "Sec-Fetch-Site" to "cross-site"))
        assertEquals(listOf(200, 403, 403, 403), answers.map { it.code })
        for (r in answers) r.use {
            assertNull(it.header("Access-Control-Allow-Origin"), "on a ${it.code}")
            assertTrue(it.headers.names().none { n -> n.startsWith("Access-Control-", ignoreCase = true) },
                       "on a ${it.code}: ${it.headers.names()}")
        }
        assertEquals(1, reached.get())
    }

    // For a browser that got a request past the rules above, an old one: the
    // answer is not to be given to another origin's page as a picture or a
    // script, nor read as anything but JSON. On every status the server has.
    @Test fun `every answer tells a browser it is for no page of another origin`() {
        val reached = AtomicInteger()
        startWith { req ->
            reached.incrementAndGet()
            if (req.path == "/throws") throw IllegalStateException("boom")
            MicroHttpServer.Response.json("""{"ok":true}""")
        }
        val here = "Host: 127.0.0.1:${server.port}"
        val answers = listOf(
            raw("GET /health HTTP/1.1", here),
            raw("GET /health HTTP/1.1", here, "Sec-Fetch-Site: cross-site"),
            raw("GET /health HTTP/1.1", here, here),
            raw("GET /throws HTTP/1.1", here))
        assertEquals(listOf("HTTP/1.1 200 OK", "HTTP/1.1 403 Forbidden", "HTTP/1.1 400 Bad Request",
                            "HTTP/1.1 500 Internal Server Error"), answers.map(::statusLine))
        for (answer in answers) {
            val head = answer.substringBefore("\r\n\r\n").lines()
            assertTrue("Cross-Origin-Resource-Policy: same-origin" in head, answer)
            assertTrue("X-Content-Type-Options: nosniff" in head, answer)
        }
        // The 200 and the 500: the refused one and the malformed one never got there.
        assertEquals(2, reached.get())
    }

    // What a browser asks before a request a page may not make unasked. It is
    // approved by a 2xx that names the origin, and gets neither, from another
    // site or from another port of this machine.
    @Test fun `a preflight is not approved, for any origin`() {
        val reached = startCounting()
        for ((origin, site) in listOf("https://attacker.invalid" to "cross-site",
                                      "http://localhost:3000" to "same-site")) {
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/credentials")
                .method("OPTIONS", null)
                .header("Origin", origin)
                .header("Sec-Fetch-Site", site)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type").build()).execute().use { r ->
                assertEquals(403, r.code, origin)
                assertTrue(r.headers.names().none { n -> n.startsWith("Access-Control-", ignoreCase = true) },
                           "${r.headers.names()}")
            }
        }
        assertEquals(0, reached.get())
    }

    // `/credentials` takes its keys in the query, so a line of the log that
    // held the query would hold them.
    @Test fun `a refusal is logged once, at the lowest level, without the query`() {
        val log = RecordingLog()
        val reached = startCounting()
        BridgeLog.current = log
        get("/credentials?user=me&apiKey=SECRET", "Origin" to "https://attacker.invalid").use { r ->
            assertEquals(403, r.code)
        }
        val lines = log.refusals()
        assertEquals(1, lines.size, "$lines")
        val line = lines.single()
        assertTrue(line.startsWith("D "), line)
        assertTrue("GET /credentials" in line, line)
        assertTrue("origin: https://attacker.invalid" in line, line)
        assertFalse("SECRET" in line || "apiKey" in line || "?" in line, line)
        assertEquals(0, reached.get())
    }

    // A browser's request has a dozen headers that refuse it. The line names
    // the one that says most about where the request came from, and the same
    // one every time, which the order of a hash map would not give.
    @Test fun `the line of a refusal names the header that says most`() {
        val log = RecordingLog()
        val reached = startCounting()
        BridgeLog.current = log
        val expected = listOf(
            // A prefetch: sec-ch-ua and sec-purpose are there as well.
            listOf("Sec-Purpose" to "prefetch", "sec-ch-ua-mobile" to "?0", "Sec-Fetch-Mode" to "navigate",
                   "Sec-Fetch-Site" to "none") to "sec-fetch-site: none",
            // A fetch to another origin has both: what the browser makes of
            // the two sites, rather than the name of one.
            listOf("Origin" to "http://localhost:3000", "Sec-Fetch-Site" to "same-site",
                   "Sec-Fetch-Mode" to "cors") to "sec-fetch-site: same-site",
            // A WebSocket handshake has no Sec-Fetch-Site: where the page is.
            listOf("Sec-WebSocket-Version" to "13", "Origin" to "http://localhost:3000",
                   "Sec-WebSocket-Key" to "QSDwO7KamAKEh6Zj7szF+A==") to "origin: http://localhost:3000",
            // With neither, the first by name. Of these three a HashMap of
            // sixteen or thirty-two buckets gives sec-fetch-mode first.
            listOf("Sec-Purpose" to "prefetch", "Sec-Fetch-Mode" to "navigate",
                   "sec-ch-ua" to "v155") to "sec-ch-ua: v155",
            // Host is asked first, and a request refused on it is said to be.
            listOf("Host" to "attacker.invalid", "Sec-Fetch-Site" to "none") to "host: attacker.invalid")
        for ((headers, _) in expected) get("/health", *headers.toTypedArray()).use { r -> assertEquals(403, r.code) }
        val lines = log.refusals()
        assertEquals(expected.size, lines.size, "$lines")
        for ((line, want) in lines.zip(expected.map { it.second })) {
            assertEquals("D refused GET /health: $want", line)
        }
        assertEquals(0, reached.get())
    }

    @Test fun `a request that is served is not logged as refused`() {
        val log = RecordingLog()
        startCounting()
        BridgeLog.current = log
        get("/health").use { r -> assertEquals(200, r.code) }
        assertEquals(emptyList(), log.refusals())
    }

    // Whoever is refused chose every byte of the line, the method, the path
    // and a header's name as much as its value: each is cut to length and
    // nothing in it can move the cursor of a terminal the log is read in.
    @Test fun `what a refused request sent is logged printable and cut short`() {
        val log = RecordingLog()
        val reached = startCounting()
        BridgeLog.current = log
        val esc = "\u001b[31m"
        raw("G${esc}ET${"m".repeat(500)} /p$esc${"p".repeat(500)} HTTP/1.1", "Host: 127.0.0.1:${server.port}",
            "Origin: https://attacker.invalid/$esc${"o".repeat(500)}")
        raw("GET /health HTTP/1.1", "Host: 127.0.0.1:${server.port}", "Sec-$esc${"n".repeat(500)}: v")
        val lines = log.refusals()
        assertEquals(2, lines.size, "$lines")
        for (line in lines) {
            assertTrue(line.all { it in ' '..'~' }, line)
            assertTrue(line.length < 420, "${line.length} characters")
        }
        assertTrue("origin: https://attacker.invalid/?[31mooo" in lines[0], lines[0])
        assertTrue("/health: sec-?[31mnnn" in lines[1], lines[1])
        assertEquals(0, reached.get())
    }

    // A page can have its browser ask as often as it likes, and every level
    // of the daemon's log is printed. Twenty lines a minute are written, the
    // rest are counted, and the count is written when a later minute brings a
    // refusal. The requests are refused all the same.
    @Test fun `the refusals of a minute are written up to twenty, and the rest counted`() {
        val log = RecordingLog()
        val now = AtomicLong(1_000)
        val reached = AtomicInteger()
        server = MicroHttpServer(clock = now::get) { reached.incrementAndGet(); MicroHttpServer.Response.json("{}") }
        server.start()
        BridgeLog.current = log
        fun refused(n: Int) = get("/n$n", "Sec-Fetch-Site" to "cross-site").use { r -> assertEquals(403, r.code) }

        // The minute began when the server did, at 1000.
        for (n in 1..25) { now.addAndGet(1_000); refused(n) }
        assertEquals((1..20).map { "/n$it:" }, log.refusals().map { it.split(' ')[3] })
        assertEquals(1, log.refusals().count { "no more are written this minute" in it }, "${log.refusals()}")
        assertTrue("no more are written this minute" in log.refusals().last(), log.refusals().last())

        now.set(1_000 + 59_999)
        refused(26)
        assertEquals(20, log.refusals().size)

        now.set(1_000 + 60_000)
        refused(27)
        assertEquals(listOf("D refused 6 more before this, counted and not written",
                            "D refused GET /n27: sec-fetch-site: cross-site"), log.refusals().drop(20))

        // The minute that began with that one is held to twenty like the first.
        for (n in 28..49) { now.addAndGet(1_000); refused(n) }
        assertEquals((27..46).map { "/n$it:" }, log.refusals().drop(21).map { it.split(' ')[3] })
        now.set(61_000 + 60_000)
        refused(50)
        assertEquals(listOf("D refused 3 more before this, counted and not written",
                            "D refused GET /n50: sec-fetch-site: cross-site"), log.refusals().drop(41))

        // A minute that left nothing unwritten is followed by no count.
        now.addAndGet(60_000)
        refused(51)
        assertEquals("D refused GET /n51: sec-fetch-site: cross-site", log.refusals().drop(43).single())
        assertEquals(0, reached.get())
    }

    private companion object {
        /** The User-Agent of the Chrome 155 whose requests are written out above. */
        const val CHROME = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "HeadlessChrome/155.0.0.0 Safari/537.36"
    }
}
