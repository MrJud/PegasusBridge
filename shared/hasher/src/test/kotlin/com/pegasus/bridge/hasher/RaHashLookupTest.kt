package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the live lookup makes of each kind of answer, against a local server that
 * serves exactly the bodies under test.
 *
 * The pipeline reads four shapes from it and they must not bleed into each
 * other: null is "no answer" and is asked again next scan, gameId 0 is RA saying
 * no and is kept for fourteen days, a virtual id is RA knowing the dump only as
 * unplayable as it is, kept like a miss and counted neither way, and a real id
 * comes with its title or not at all. An HTML page served with 200 used to come
 * out as gameId 0.
 */
class RaHashLookupTest {
    private data class Reply(val body: String, val status: Int = 200)

    private lateinit var server: HttpServer
    private lateinit var lookup: RaApiHashLookup
    private lateinit var previousLog: BridgeLog
    private val replies = ConcurrentLinkedQueue<Reply>()
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ra-lookup-test").apply { isDaemon = true }
    }

    @BeforeTest fun setUp() {
        previousLog = BridgeLog.current
        BridgeLog.current = object : BridgeLog {
            override fun d(tag: String, msg: String) { logs += msg }
            override fun i(tag: String, msg: String) { logs += msg }
            override fun w(tag: String, msg: String, t: Throwable?) { logs += msg + t?.stackTraceToString().orEmpty() }
            override fun e(tag: String, msg: String, t: Throwable?) { logs += msg + t?.stackTraceToString().orEmpty() }
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            val reply = replies.poll() ?: Reply("unexpected request", 400)
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        lookup = RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:${server.address.port}")
    }

    @AfterTest fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
        BridgeLog.current = previousLog
    }

    @Test fun `only an explicit successful zero is a confirmed hash miss`() = runTest {
        replies += Reply("""{"Success":true,"GameID":0}""")

        assertEquals(GameMetadata(gameId = 0), lookup.lookup(HASH))
        assertEquals(0, lookup.consecutiveFailures)
        assertEquals(listOf("/dorequest.php"), requests.toList())
    }

    // Not yet seen from RA, but it is what a maintenance screen or a proxy's
    // challenge page looks like: HTML, served with 200. Read as gameId 0 it was
    // NOT_FOUND, and the ledger kept it for fourteen days.
    @Test fun `an html page served with 200 is a failure, not a miss`() = runTest {
        replies += Reply("<html><body>Temporarily unavailable</body></html>")

        assertNull(lookup.lookup(HASH))
        assertEquals(1, lookup.consecutiveFailures)
        assertEquals(listOf("/dorequest.php"), requests.toList(), "no metadata request for a non-answer")
        assertTrue(logs.any { it.contains("Temporarily unavailable") },
                   "the refused body should be quoted, so a format change can be diagnosed:\n$logs")
    }

    @Test fun `invalid game ID responses remain retryable lookup failures`() = runTest {
        val invalidResponses = listOf(
            "<html>Temporarily unavailable</html>",
            "",
            """[{"Success":true,"GameID":0}]""",
            """{"Success":false,"Error":"Please try again"}""",
            """{"Success":false,"GameID":0}""",
            """{"Success":"true","GameID":0}""",
            """{"Success":true}""",
            """{"GameID":0}""",
            """{"Success":true,"GameID":null}""",
            """{"Success":true,"GameID":"invalid"}""",
            """{"Success":true,"GameID":-1}""",
            """{"Success":true,"GameID":0.5}""",
            """{"Success":true,"GameID":4294967296}"""
        )
        invalidResponses.forEachIndexed { index, body ->
            replies += Reply(body)
            assertNull(lookup.lookup(HASH), body)
            assertEquals(index + 1, lookup.consecutiveFailures, body)
        }
        assertTrue(requests.all { it == "/dorequest.php" })
    }

    @Test fun `valid metadata resolves the game and resets failures`() = runTest {
        replies += Reply("bad response")
        assertNull(lookup.lookup(HASH))
        replies += Reply("""{"Success":true,"GameID":"1446"}""")
        replies += Reply(VALID_METADATA)

        val result = assertNotNull(lookup.lookup(HASH))

        assertEquals(GameMetadata(1446, "Super Mario Bros.", "NES", "/Images/1.png", 76), result)
        assertEquals(0, lookup.consecutiveFailures)
    }

    @Test fun `metadata in an array remains supported`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("[$VALID_METADATA]")
        assertEquals(1446, assertNotNull(lookup.lookup(HASH)).gameId)
    }

    // None of these may become a match, and every one counts against the source:
    // RA hands out a real id only for a game it has, so a body that does not
    // describe it is the metadata endpoint failing. As the id alone they cleared
    // the count, and a metadata endpoint serving HTML with 200 went unnoticed
    // through a whole library: 16 of 16 known ROMs retried, 0 lookups failed.
    @Test fun `metadata that does not describe a real game is a failure, not the id alone`() = runTest {
        val unusable = listOf(
            "<html>Temporarily unavailable</html>", "[]", "{}", "",
            """{"ID":1446,"Title":"","NumAchievements":0}""",
            """{"ID":1446,"Title":"   ","NumAchievements":0}""",
            """{"ID":1446,"Title":null,"NumAchievements":0}""",
            """{"ID":1447,"Title":"Wrong game","NumAchievements":0}""",
            """{"Title":"Super Mario Bros.","NumAchievements":76}""",
            """{"ID":1446,"Title":"Super Mario Bros."}""",
            """{"ID":1446,"Title":"Super Mario Bros.","NumAchievements":-1}"""
        )
        unusable.forEachIndexed { index, body ->
            replies += Reply("""{"Success":true,"GameID":1446}""")
            replies += Reply(body)
            assertNull(lookup.lookup(HASH), body)
            assertEquals(index + 1, lookup.consecutiveFailures, body)
        }
    }

    // RA answers a dump it does not consider playable as is with the real id plus
    // a base (RAWeb VirtualGameIdService: 1e9 incompatible, 1.1e9 untested, 1.2e9
    // patch required), and the Web API knows nothing under that number; a Virtual
    // Console Metroid does exactly this. Of 143 ROMs in one library that RA's hash
    // list did not know, 65 came back as such ids. Nothing is asked about them,
    // and they leave the count where it was: cleared by them, a broken metadata
    // endpoint could hide behind them; raised, a run of them would stop a scan.
    @Test fun `a virtual id is the id alone, asks for no metadata and counts neither way`() = runTest {
        replies += Reply("<html>not an answer</html>")
        assertNull(lookup.lookup(HASH))
        assertEquals(1, lookup.consecutiveFailures)

        for (virtual in listOf(1_000_000_001, 1_100_000_123, 1_200_000_005)) {
            replies += Reply("""{"Success":true,"GameID":$virtual}""")
            assertEquals(GameMetadata(gameId = virtual), lookup.lookup(HASH), "$virtual")
            assertEquals(1, lookup.consecutiveFailures, "$virtual moved the count")
        }
        assertEquals(List(4) { "/dorequest.php" }, requests.toList(), "a virtual id must not be asked about")

        replies += Reply("<html>not an answer</html>")
        assertNull(lookup.lookup(HASH))
        assertEquals(2, lookup.consecutiveFailures)
    }

    // RAWeb compares strictly: the base itself is not virtual.
    @Test fun `the virtual id bases and what they mean are RAWeb's`() {
        assertFalse(VirtualGameId.isVirtual(1_000_000_000))
        assertTrue(VirtualGameId.isVirtual(1_000_000_001))
        assertEquals("game 1, incompatible", VirtualGameId.describe(1_000_000_001))
        assertEquals("game 1487, untested", VirtualGameId.describe(1_100_001_487))
        assertEquals("game 5, patch required", VirtualGameId.describe(1_200_000_005))
        // The second base too: RAWeb decodes 1 100 000 000 itself as incompatible.
        assertEquals("game 100000000, incompatible", VirtualGameId.describe(1_100_000_000))
    }

    // An error object is RA refusing the request, not describing a game: if the
    // key behind it is bad, every match after it will fail the same way.
    @Test fun `an explicit refusal from the metadata endpoint is a failure`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("""{"Success":false,"Error":"Invalid credentials"}""")

        assertNull(lookup.lookup(HASH))
        assertEquals(1, lookup.consecutiveFailures)
    }

    @Test fun `a confirmed miss resets consecutive failures`() = runTest {
        replies += Reply("service unavailable", 401)
        assertNull(lookup.lookup(HASH))
        assertEquals(1, lookup.consecutiveFailures)
        replies += Reply("""{"Success":true,"GameID":0}""")
        assertEquals(0, assertNotNull(lookup.lookup(HASH)).gameId)
        assertEquals(0, lookup.consecutiveFailures)
    }

    // A revoked key, as RA actually serves it: r=gameid carries no key and
    // answers 200, the metadata call answers 401. Counted per request, the 200
    // zeroed what the 401 had raised and the count never got past 1.
    @Test fun `a refused key adds up even though every game id request succeeds`() = runTest {
        repeat(3) { n ->
            replies += Reply("""{"Success":true,"GameID":1446}""")
            replies += Reply(UNAUTHENTICATED, 401)
            assertNull(lookup.lookup(HASH))
            assertEquals(n + 1, lookup.consecutiveFailures)
        }
        assertEquals(6, requests.size)
    }

    // What RAWeb's api-token guard answers whenever `y` matches no account's web
    // API key: a wrong, revoked or empty key, and a banned account's, which the
    // ban clears. Every match after it fails the same way, whatever the misses
    // in between do to the failure count.
    @Test fun `a 401 from the metadata endpoint is the key refused, and stays refused`() = runTest {
        assertFalse(lookup.authRejected)
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply(UNAUTHENTICATED, 401)

        assertNull(lookup.lookup(HASH))
        assertTrue(lookup.authRejected)
        assertEquals(2, requests.size, "a refusal is not retried")

        // A miss is answered without the key: it clears the count, not the refusal.
        replies += Reply("""{"Success":true,"GameID":0}""")
        assertEquals(0, assertNotNull(lookup.lookup(HASH)).gameId)
        assertEquals(0, lookup.consecutiveFailures)
        assertTrue(lookup.authRejected)
    }

    // Failures, but none of them the key: a 404, an explicit Success:false (not
    // something RAWeb sends for a bad key), and a 401 on r=gameid, which carries
    // no key to refuse.
    @Test fun `other refusals do not say the key was refused`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("not found", 404)
        assertNull(lookup.lookup(HASH))
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("""{"Success":false,"Error":"Invalid credentials"}""")
        assertNull(lookup.lookup(HASH))
        replies += Reply(UNAUTHENTICATED, 401)
        assertNull(lookup.lookup(HASH))

        assertEquals(3, lookup.consecutiveFailures)
        assertFalse(lookup.authRejected)
    }

    @Test fun `temporary server failures are retried`() = runTest {
        replies += Reply("rate limited", 429)
        replies += Reply("unavailable", 503)
        replies += Reply("""{"Success":true,"GameID":0}""")

        assertEquals(0, assertNotNull(lookup.lookup(HASH)).gameId)
        assertEquals(3, requests.size)
        assertEquals(0, lookup.consecutiveFailures)
    }

    // The username is allowed through: SafeUrl keeps `z=` on purpose, because a
    // line that cannot say whose request failed sends the reader to a packet
    // capture. The key is what must never appear.
    @Test fun `exhausted metadata retries count once and never log the key`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        repeat(4) { replies += Reply("$USER $API_KEY", 503) }

        assertNull(lookup.lookup(HASH))

        assertEquals(5, requests.size)
        assertEquals(1, lookup.consecutiveFailures)
        assertTrue(logs.any { it.contains("retries exhausted") && it.contains("i=1446") }, "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")
    }

    @Test fun `a body that echoes the key is quoted without it`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("<html>Bad request: /API/API_GetGameExtended.php?z=$USER&y=$API_KEY&i=1446</html>")

        assertNull(lookup.lookup(HASH))
        assertTrue(logs.any { it.contains("Bad request") }, "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")
    }

    // Back-offs of 1, 2 and 4 seconds between four attempts, and nothing after
    // the last: there is no attempt left to wait for. The old loop slept 8 more
    // seconds before giving up. Pacing adds at most 250 ms before each retry.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `the last attempt is not followed by a back-off`() = runTest {
        repeat(4) { replies += Reply("unavailable", 503) }

        assertNull(lookup.lookup(HASH))

        assertEquals(4, requests.size)
        assertTrue(currentTime in 7_000L..7_750L, "virtual time spent: $currentTime ms")
    }

    @Test fun `cancellation stops an in-flight body read without reporting failure`() {
        val bodyStarted = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val returnedNormally = AtomicBoolean()
        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            exchange.sendResponseHeaders(200, 4096)
            try {
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
                bodyStarted.countDown()
                releaseBody.await(5, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        runBlocking {
            val job = launch(Dispatchers.IO) {
                lookup.lookup(HASH)
                returnedNormally.set(true)
            }
            try {
                assertTrue(bodyStarted.await(3, TimeUnit.SECONDS), "request did not reach the local server")
                // A blocking execute() would sit in that read until the 30 s timeout.
                withTimeout(2000) { job.cancelAndJoin() }
                assertFalse(returnedNormally.get(), "lookup must propagate cancellation to its caller")
                assertEquals(0, lookup.consecutiveFailures)
                assertEquals(1, requests.size)
                assertTrue(logs.isEmpty(), "cancellation must not produce an error log: $logs")
            } finally {
                releaseBody.countDown()
                job.cancelAndJoin()
            }
        }
    }

    // The other place an abort lands: between attempts, in the back-off.
    @Test fun `cancellation during a back-off is not a failure either`() {
        val firstRefused = CountDownLatch(1)
        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            val bytes = "unavailable".toByteArray()
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
            firstRefused.countDown()
        }
        runBlocking {
            val job = launch(Dispatchers.IO) { lookup.lookup(HASH) }
            assertTrue(firstRefused.await(3, TimeUnit.SECONDS), "request did not reach the local server")
            // Long enough for the 503 to arrive, well inside the one-second back-off.
            delay(300)
            withTimeout(2000) { job.cancelAndJoin() }
            assertEquals(0, lookup.consecutiveFailures)
            assertEquals(1, requests.size)
            assertTrue(logs.none { it.contains("retries exhausted") }, "$logs")
        }
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef"
        const val USER = "test-private-user"
        const val API_KEY = "test-private-api-key"
        const val VALID_METADATA = """{"ID":1446,"Title":"Super Mario Bros.","ConsoleName":"NES","ImageIcon":"/Images/1.png","NumAchievements":76}"""
        /** RAWeb's body for an AuthenticationException on the legacy API (app/Exceptions/Handler.php). */
        const val UNAUTHENTICATED = """{"message":"Unauthenticated.","errors":[{"status":"401","code":"unauthorized","title":"Unauthenticated."}]}"""
    }
}
