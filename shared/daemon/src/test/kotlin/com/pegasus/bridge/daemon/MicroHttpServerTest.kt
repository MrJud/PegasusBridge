package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun get(pathAndQuery: String) =
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}$pathAndQuery").build()).execute()

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
}
