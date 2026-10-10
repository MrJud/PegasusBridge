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
 *
 * Those are the shapes the lookup answered in while its answer was a
 * [GameMetadata] that might be null, and most of these tests still say what
 * they expect in them, through [asLegacy]. The four at the end read the
 * [LookupOutcome] itself, for what the old shapes could not say: the real id
 * and the reason behind a virtual id, and which kind of failure a failure was.
 */
class RaHashLookupTest {
    /**
     * [cutShort] promises more bytes than it sends and then closes: headers
     * that arrive and a body that does not, which the client meets as an
     * exception and not as an answer. [hungUp] sends nothing at all and closes,
     * which the client meets as an exception that quotes where it was asking.
     */
    private data class Reply(val body: String, val status: Int = 200, val cutShort: Boolean = false,
                             val hungUp: Boolean = false)

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
            // Closed before a header is sent, the exchange takes its connection with it.
            if (reply.hungUp) { exchange.close(); return@createContext }
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong() + if (reply.cutShort) 64 else 0)
            // Closing a body that is short of its length throws, and what a
            // handler throws makes the server drop the connection: the client
            // has the headers by then and finds the body ended early.
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
        heldPorts.forEach { it.close() }
    }

    @Test fun `only an explicit successful zero is a confirmed hash miss`() = runTest {
        replies += Reply("""{"Success":true,"GameID":0}""")

        assertEquals(GameMetadata(gameId = 0), lookup.lookup(HASH).asLegacy())
        assertEquals(0, lookup.consecutiveFailures)
        assertEquals(listOf("/dorequest.php"), requests.toList())
    }

    // Not yet seen from RA, but it is what a maintenance screen or a proxy's
    // challenge page looks like: HTML, served with 200. Read as gameId 0 it was
    // NOT_FOUND, and the ledger kept it for fourteen days.
    @Test fun `an html page served with 200 is a failure, not a miss`() = runTest {
        replies += Reply("<html><body>Temporarily unavailable</body></html>")

        assertNull(lookup.lookup(HASH).asLegacy())
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
            assertNull(lookup.lookup(HASH).asLegacy(), body)
            assertEquals(index + 1, lookup.consecutiveFailures, body)
        }
        assertTrue(requests.all { it == "/dorequest.php" })
    }

    @Test fun `valid metadata resolves the game and resets failures`() = runTest {
        replies += Reply("bad response")
        assertNull(lookup.lookup(HASH).asLegacy())
        replies += Reply("""{"Success":true,"GameID":"1446"}""")
        replies += Reply(VALID_METADATA)

        val result = assertNotNull(lookup.lookup(HASH).asLegacy())

        assertEquals(GameMetadata(1446, "Super Mario Bros.", "NES", "/Images/1.png", 76), result)
        assertEquals(0, lookup.consecutiveFailures)
    }

    @Test fun `metadata in an array remains supported`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("[$VALID_METADATA]")
        assertEquals(1446, assertNotNull(lookup.lookup(HASH).asLegacy()).gameId)
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
            assertNull(lookup.lookup(HASH).asLegacy(), body)
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
        assertNull(lookup.lookup(HASH).asLegacy())
        assertEquals(1, lookup.consecutiveFailures)

        for (virtual in listOf(1_000_000_001, 1_100_000_123, 1_200_000_005)) {
            replies += Reply("""{"Success":true,"GameID":$virtual}""")
            assertEquals(GameMetadata(gameId = virtual), lookup.lookup(HASH).asLegacy(), "$virtual")
            assertEquals(1, lookup.consecutiveFailures, "$virtual moved the count")
        }
        assertEquals(List(4) { "/dorequest.php" }, requests.toList(), "a virtual id must not be asked about")

        replies += Reply("<html>not an answer</html>")
        assertNull(lookup.lookup(HASH).asLegacy())
        assertEquals(2, lookup.consecutiveFailures)
    }

    // RAWeb compares strictly: the base itself is not virtual. What an id
    // settles by itself is asked in one place, by the lookup, by an audit's
    // recorded answers and by the lookups tests are given.
    @Test fun `the virtual id bases and what they mean are RAWeb's`() {
        fun words(id: Int) = when (val outcome = LookupOutcome.ofIdAlone(id)) {
            null -> "a game's own id"
            LookupOutcome.NotFound -> "not known"
            is LookupOutcome.IdOnly -> "game ${outcome.gameId}, ${outcome.reason.words}, sent as ${outcome.virtualId}"
            else -> outcome.toString()
        }
        assertEquals("not known", words(0))
        assertEquals("a game's own id", words(1487))
        assertEquals("a game's own id", words(1_000_000_000))
        assertEquals("game 1, incompatible, sent as 1000000001", words(1_000_000_001))
        assertEquals("game 1487, untested, sent as 1100001487", words(1_100_001_487))
        assertEquals("game 5, patch required, sent as 1200000005", words(1_200_000_005))
        // The second base too: RAWeb decodes 1 100 000 000 itself as incompatible.
        assertEquals("game 100000000, incompatible, sent as 1100000000", words(1_100_000_000))
    }

    // An error object is RA refusing the request, not describing a game: if the
    // key behind it is bad, every match after it will fail the same way.
    @Test fun `an explicit refusal from the metadata endpoint is a failure`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("""{"Success":false,"Error":"Invalid credentials"}""")

        assertNull(lookup.lookup(HASH).asLegacy())
        assertEquals(1, lookup.consecutiveFailures)
    }

    @Test fun `a confirmed miss resets consecutive failures`() = runTest {
        replies += Reply("service unavailable", 401)
        assertNull(lookup.lookup(HASH).asLegacy())
        assertEquals(1, lookup.consecutiveFailures)
        replies += Reply("""{"Success":true,"GameID":0}""")
        assertEquals(0, assertNotNull(lookup.lookup(HASH).asLegacy()).gameId)
        assertEquals(0, lookup.consecutiveFailures)
    }

    // A revoked key, as RA actually serves it: r=gameid carries no key and
    // answers 200, the metadata call answers 401. Counted per request, the 200
    // zeroed what the 401 had raised and the count never got past 1.
    @Test fun `a refused key adds up even though every game id request succeeds`() = runTest {
        repeat(3) { n ->
            replies += Reply("""{"Success":true,"GameID":1446}""")
            replies += Reply(UNAUTHENTICATED, 401)
            assertNull(lookup.lookup(HASH).asLegacy())
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

        assertNull(lookup.lookup(HASH).asLegacy())
        assertTrue(lookup.authRejected)
        assertEquals(2, requests.size, "a refusal is not retried")

        // A miss is answered without the key: it clears the count, not the refusal.
        replies += Reply("""{"Success":true,"GameID":0}""")
        assertEquals(0, assertNotNull(lookup.lookup(HASH).asLegacy()).gameId)
        assertEquals(0, lookup.consecutiveFailures)
        assertTrue(lookup.authRejected)
    }

    // Failures, but none of them the key: a 404, an explicit Success:false (not
    // something RAWeb sends for a bad key), and a 401 on r=gameid, which carries
    // no key to refuse.
    @Test fun `other refusals do not say the key was refused`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("not found", 404)
        assertNull(lookup.lookup(HASH).asLegacy())
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("""{"Success":false,"Error":"Invalid credentials"}""")
        assertNull(lookup.lookup(HASH).asLegacy())
        replies += Reply(UNAUTHENTICATED, 401)
        assertNull(lookup.lookup(HASH).asLegacy())

        assertEquals(3, lookup.consecutiveFailures)
        assertFalse(lookup.authRejected)
    }

    @Test fun `temporary server failures are retried`() = runTest {
        replies += Reply("rate limited", 429)
        replies += Reply("unavailable", 503)
        replies += Reply("""{"Success":true,"GameID":0}""")

        assertEquals(0, assertNotNull(lookup.lookup(HASH).asLegacy()).gameId)
        assertEquals(3, requests.size)
        assertEquals(0, lookup.consecutiveFailures)
    }

    // The username is allowed through: SafeUrl keeps `z=` on purpose, because a
    // line that cannot say whose request failed sends the reader to a packet
    // capture. The key is what must never appear.
    @Test fun `exhausted metadata retries count once and never log the key`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        repeat(4) { replies += Reply("$USER $API_KEY", 503) }

        assertNull(lookup.lookup(HASH).asLegacy())

        assertEquals(5, requests.size)
        assertEquals(1, lookup.consecutiveFailures)
        assertTrue(logs.any { it.contains("retries exhausted") && it.contains("i=1446") }, "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")
    }

    @Test fun `a body that echoes the key is quoted without it`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply("<html>Bad request: /API/API_GetGameExtended.php?z=$USER&y=$API_KEY&i=1446</html>")

        assertNull(lookup.lookup(HASH).asLegacy())
        assertTrue(logs.any { it.contains("Bad request") }, "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")
    }

    // Back-offs of 1, 2 and 4 seconds between four attempts, and nothing after
    // the last: there is no attempt left to wait for. The old loop slept 8 more
    // seconds before giving up. Pacing adds at most 250 ms before each retry.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `the last attempt is not followed by a back-off`() = runTest {
        repeat(4) { replies += Reply("unavailable", 503) }

        assertNull(lookup.lookup(HASH).asLegacy())

        assertEquals(4, requests.size)
        assertTrue(currentTime in 7_000L..7_750L, "virtual time spent: $currentTime ms")
    }

    private val heldPorts = ArrayList<java.net.Socket>()

    /**
     * A port with nothing behind it: every request to it is refused at once,
     * and for real. Bound and never listened on, and held until the test is
     * over. A port taken and given back is the next one the system hands out
     * now and then, to anything on the machine, and the request would be
     * answered.
     */
    private fun deadPort(): Int =
        java.net.Socket().also { it.bind(InetSocketAddress("127.0.0.1", 0)); heldPorts += it }.localPort

    private fun unreachable(deviceOffline: () -> Boolean) =
        RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:${deadPort()}", DeviceConnection(deviceOffline))

    // A tablet in airplane mode. Each lookup used to go through its four
    // attempts and the seven seconds of back-off between them, two lookups at
    // a time, until eight had failed: 31 seconds to stop a scan of 13 files.
    // The device is asked once, about the one request made, and nothing is
    // waited for. No clock moves here because nothing sleeps: the first
    // request of a lookup is not paced.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a request that fails on a device with no connection is not tried again`() = runTest {
        var asked = 0
        val lookup = unreachable { asked++; true }
        assertFalse(lookup.offline, "offline before any request had failed")
        assertEquals(0, asked, "the device was asked before any request had failed")

        assertNull(lookup.lookup(HASH).asLegacy())

        assertEquals(1, asked, "one request fails once: the device is asked once")
        assertEquals(0L, currentTime, "a back-off was waited through")
        assertTrue(lookup.offline)
        assertEquals(1, lookup.consecutiveFailures)
        assertTrue(logs.any { it.contains("no internet connection") && it.contains("/dorequest.php") }, "$logs")
        assertTrue(logs.none { it.contains("retries exhausted") }, "$logs")
    }

    // What the device says explains a failure and prevents nothing. Here it
    // would say there is no connection, and is wrong: the requests are made
    // all the same, both are answered, and it is never asked.
    @Test fun `a device that would say it is offline is not asked while requests are answered`() = runTest {
        var asked = 0
        val lookup = RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:${server.address.port}") { asked++; true }
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply(VALID_METADATA)

        assertEquals(1446, assertNotNull(lookup.lookup(HASH).asLegacy()).gameId)

        assertEquals(2, requests.size)
        assertEquals(0, asked, "the device was asked about a request that had not failed")
        assertFalse(lookup.offline)
    }

    // The device is asked about a request that brought back nothing, and a
    // refusal is something. Too many requests, and then a server in trouble
    // four times over: each is waited out and asked again as it always was,
    // by a lookup whose device would have said there is no connection. Asked
    // after a status as well, the first 429 on a network Android has its
    // doubts about would end the scan with the advice to connect.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a request that is answered with a refusal is not put to the device`() = runTest {
        var asked = 0
        val lookup = RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:${server.address.port}") { asked++; true }
        replies += Reply("slow down", 429)
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply(VALID_METADATA)

        assertEquals(1446, assertNotNull(lookup.lookup(HASH).asLegacy()).gameId)

        assertEquals(3, requests.size, "the request that was refused was not made again")
        assertEquals(0, asked, "the device was asked about a request that was answered")
        assertFalse(lookup.offline)

        repeat(4) { replies += Reply("unavailable", 503) }
        val before = currentTime
        assertNull(lookup.lookup(HASH).asLegacy())

        assertEquals(7, requests.size, "a request answered 503 was not made four times")
        // The seven seconds of back-off, and the quarter of a second each of
        // the four requests is held behind the one before it.
        assertTrue(currentTime - before in 7_000L..8_000L, "virtual time spent: ${currentTime - before} ms")
        assertEquals(0, asked, "the device was asked about a request that was answered")
        assertFalse(lookup.offline, "four answers were put down to the connection")
    }

    // The same dead port on a device that has a connection, or that was given
    // nobody to ask: four attempts and the back-off between them, as before,
    // and nothing put down to the connection.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a request that fails on a device with a connection is tried four times, as it was`() = runTest {
        var asked = 0
        val lookup = unreachable { asked++; false }

        assertNull(lookup.lookup(HASH).asLegacy())

        assertEquals(4, asked, "asked after each attempt that failed")
        assertTrue(currentTime in 7_000L..7_750L, "virtual time spent: $currentTime ms")
        assertFalse(lookup.offline)
        assertTrue(logs.any { it.contains("retries exhausted") }, "$logs")

        val nobodyToAsk = RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:${deadPort()}")
        val before = currentTime
        assertNull(nobodyToAsk.lookup(HASH).asLegacy())
        assertTrue(currentTime - before in 7_000L..7_750L, "virtual time spent: ${currentTime - before} ms")
        assertFalse(nobodyToAsk.offline)
    }

    // What the platform throws when asked must not be what ends a scan, nor
    // what calls it offline. An Error as well as an exception: a call the
    // device's Android does not have is a NoSuchMethodError, and the lookup's
    // own catch lets an Error through to fail the whole scan.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a device that cannot say whether it is connected is taken to be`() = runTest {
        for (thrown in listOf(SecurityException("Package android does not belong to 10234"),
                              NoSuchMethodError("getActiveNetwork"))) {
            var asked = 0
            val lookup = unreachable { asked++; throw thrown }
            val before = currentTime

            assertNull(lookup.lookup(HASH).asLegacy(), "$thrown")

            assertEquals(4, asked, "$thrown")
            assertTrue(currentTime - before in 7_000L..7_750L, "$thrown: ${currentTime - before} ms")
            assertFalse(lookup.offline, "$thrown")
        }
    }

    // The request that carries the key is the second of a lookup. Here the
    // first is answered and the second is cut off in its body, on a device
    // that then says it has no connection: the line written for it names the
    // endpoint and the game, and not the key.
    @Test fun `a request given up for want of a connection is logged without the key`() = runTest {
        val lookup = RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:${server.address.port}") { true }
        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply(VALID_METADATA, cutShort = true)

        assertNull(lookup.lookup(HASH).asLegacy())

        assertEquals(2, requests.size, "the request that failed was made again")
        assertTrue(lookup.offline)
        assertTrue(logs.any { it.contains("no internet connection") && it.contains("i=1446") }, "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")
    }

    // Offline is not kept as a refused key is. A request that is answered was
    // carried by a connection, whatever the answer: a 404 here, which is a
    // failed lookup and still proof that the source was reached.
    @Test fun `any answer takes back what a failed request said about the connection`() = runTest {
        val lookup = RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:${server.address.port}") { true }
        replies += Reply("""{"Success":true,"GameID":0}""", cutShort = true)
        assertNull(lookup.lookup(HASH).asLegacy())
        assertTrue(lookup.offline)

        replies += Reply("not found", 404)
        assertNull(lookup.lookup(HASH).asLegacy())
        assertFalse(lookup.offline, "an answer arrived and the lookup still says there is no connection")
        assertEquals(2, lookup.consecutiveFailures)
    }

    // Nor is it kept when the next request fails on a device that says it is
    // connected again. That one is retried, and the scan is not stopped for a
    // connection the device now has.
    @Test fun `a request that fails once the device is connected again takes it back too`() = runTest {
        var connected = false
        val lookup = unreachable { !connected }
        assertNull(lookup.lookup(HASH).asLegacy())
        assertTrue(lookup.offline)

        connected = true
        assertNull(lookup.lookup(HASH).asLegacy())
        assertFalse(lookup.offline)
        assertTrue(logs.any { it.contains("retries exhausted") }, "$logs")
    }

    // The lookup as the Android service builds it: the device is asked through
    // an OfflineVerdict, here on the clock of the test's own delays, and says
    // each time that its network has not been found to work. A router with no
    // line out. The first three attempts are within the time a network takes
    // to be tried and go on as ever; at the fourth, seven seconds in, it has
    // lasted, and the lookup says so.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a network not found to work settles it at the last attempt of the first lookup`() = runTest {
        val verdict = OfflineVerdict { TimeUnit.MILLISECONDS.toNanos(currentTime) }
        var asked = 0
        val lookup = unreachable { asked++; verdict.offline(LinkState.UNVALIDATED) }

        assertNull(lookup.lookup(HASH).asLegacy())

        assertEquals(4, asked)
        assertTrue(currentTime in 7_000L..7_750L, "virtual time spent: $currentTime ms")
        assertTrue(lookup.offline)
    }

    // A network Android never passes and that carries every request, which is
    // what a Wi-Fi is when Android's own test is kept from getting out. Two
    // requests fail on it ten minutes apart, each made again and answered,
    // with two hundred lookups answered in between. Counted from the first
    // failure of the scan to whichever comes next, the wait would be over at
    // the second, which would not be made again, and the scan would stop for
    // want of a connection. An answer is what starts the wait over.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `answers between two failed requests keep a doubted network from being called offline`() = runTest {
        var nowMs = 0L
        val verdict = OfflineVerdict { TimeUnit.MILLISECONDS.toNanos(nowMs) }
        var asked = 0
        val lookup = RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:${server.address.port}", object : DeviceConnection {
            override fun offline(): Boolean { asked++; return verdict.offline(LinkState.UNVALIDATED) }
            override fun answered() = verdict.answered()
        })
        val miss = """{"Success":true,"GameID":0}"""

        replies += Reply(miss, cutShort = true)
        replies += Reply(miss)
        assertEquals(GameMetadata(gameId = 0), lookup.lookup(HASH).asLegacy())
        repeat(200) {
            nowMs += 3_000
            replies += Reply(miss)
            assertEquals(GameMetadata(gameId = 0), lookup.lookup(HASH).asLegacy())
        }

        replies += Reply(miss, cutShort = true)
        replies += Reply(miss)
        assertEquals(GameMetadata(gameId = 0), lookup.lookup(HASH).asLegacy(),
                     "a request that failed once on a network that had answered 201 was not made again")

        assertEquals(2, asked)
        assertFalse(lookup.offline)
        assertEquals(204, requests.size)
        assertTrue(logs.none { it.contains("no internet connection") }, "$logs")
    }

    // Whoever is told of an answer is not let undo it. What the platform's
    // side throws there would otherwise be caught as the request failing, and
    // an answer that had arrived would be asked for again and then given up.
    @Test fun `an answer stays an answer when telling of it throws`() = runTest {
        val lookup = RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:${server.address.port}", object : DeviceConnection {
            override fun offline() = true
            override fun answered() { throw IllegalStateException("no verdict to tell") }
        })
        replies += Reply("""{"Success":true,"GameID":0}""")

        assertEquals(GameMetadata(gameId = 0), lookup.lookup(HASH).asLegacy())

        assertEquals(1, requests.size)
        assertFalse(lookup.offline)
    }

    // The decoding the lookup answers a virtual id with, by RAWeb's strict
    // comparisons: a base itself is not above it, so the first is a game's own
    // id and each of the others belongs to the base below. The last but one is
    // above every base, which leaves a real id no game has; the last is a
    // real id of five figures under the second base.
    @Test fun `virtual ids decode to the real id and the reason`() {
        val expected = listOf(
            1 to null,
            1_000_000_000 to null,
            1_000_000_001 to (1 to LookupOutcome.Compatibility.INCOMPATIBLE),
            1_100_000_000 to (100_000_000 to LookupOutcome.Compatibility.INCOMPATIBLE),
            1_100_001_487 to (1487 to LookupOutcome.Compatibility.UNTESTED),
            1_200_000_000 to (100_000_000 to LookupOutcome.Compatibility.UNTESTED),
            1_200_000_005 to (5 to LookupOutcome.Compatibility.PATCH_REQUIRED),
            1_300_000_001 to (100_000_001 to LookupOutcome.Compatibility.PATCH_REQUIRED),
            1_100_014_068 to (14068 to LookupOutcome.Compatibility.UNTESTED))
        val wrong = expected.filter { (id, decoded) -> VirtualGameId.decode(id) != decoded }
            .map { (id, decoded) -> "$id: ${VirtualGameId.decode(id)}, not $decoded" }
        assertEquals(emptyList(), wrong)
        // And an id is virtual exactly when it is above the lowest base.
        for ((id, _) in expected)
            assertEquals(id > VirtualGameId.INCOMPATIBLE_BASE, VirtualGameId.decode(id) != null, "$id")
    }

    @Test fun `each of the four answers comes back as what it is`() = runTest {
        replies += Reply("""{"Success":true,"GameID":0}""")
        assertEquals<LookupOutcome>(LookupOutcome.NotFound, lookup.lookup(HASH))

        replies += Reply("""{"Success":true,"GameID":1446}""")
        replies += Reply(VALID_METADATA)
        assertEquals<LookupOutcome>(
            LookupOutcome.Match(GameMetadata(1446, "Super Mario Bros.", "NES", "/Images/1.png", 76)),
            lookup.lookup(HASH))

        for ((virtual, real, reason) in listOf(
                Triple(1_000_000_001, 1, LookupOutcome.Compatibility.INCOMPATIBLE),
                Triple(1_100_001_487, 1487, LookupOutcome.Compatibility.UNTESTED),
                Triple(1_200_000_005, 5, LookupOutcome.Compatibility.PATCH_REQUIRED))) {
            replies += Reply("""{"Success":true,"GameID":$virtual}""")
            assertEquals<LookupOutcome>(LookupOutcome.IdOnly(real, reason, virtualId = virtual), lookup.lookup(HASH))
        }
        assertEquals(0, lookup.consecutiveFailures)

        replies += Reply("<html>not an answer</html>")
        assertEquals(LookupOutcome.Cause.MALFORMED, (lookup.lookup(HASH) as LookupOutcome.Failed).cause)
        assertEquals(1, lookup.consecutiveFailures)
    }

    // The first base is not a virtual id, so it is asked about as a game is,
    // and a match may hold it: the two limits are one number, and a lookup
    // that drew them one apart would throw on the id between.
    @Test fun `the id on the first base is a game's, asked about and matched`() = runTest {
        replies += Reply("""{"Success":true,"GameID":1000000000}""")
        replies += Reply("""{"ID":1000000000,"Title":"The Last Real Id","NumAchievements":3}""")

        assertEquals<LookupOutcome>(
            LookupOutcome.Match(GameMetadata(1_000_000_000, "The Last Real Id", numAchievements = 3)),
            lookup.lookup(HASH))
        assertEquals(listOf("/dorequest.php", "/API/API_GetGameExtended.php"), requests.toList())
        assertTrue(logs.isEmpty(), "$logs")
    }

    // Which kind it was, for each way a lookup comes to nothing. Told by what
    // happened to the request and never by what the device says when asked:
    // a status is a refusal on a device that calls itself offline too, and
    // only the 401 that answers the request carrying the key is the key
    // refused. The two things the pipeline stops a scan on go with their
    // causes and with no other.
    //
    // And the few words that go with the kind say nothing of where the request
    // was going. A request that brought nothing back ends in an exception, and
    // its message is the address and the port for a connection refused, and
    // the URL for one dropped before a header came. The failure used to carry
    // that message, out of the lookup and to whatever reads the outcome; it
    // carries the exception's class, and the message stays in the log.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a failure says which kind it was`() = runTest {
        val id = Reply("""{"Success":true,"GameID":1446}""")
        val here = server.address.port
        val (auth, offline, refused) = Triple(LookupOutcome.Cause.AUTH, LookupOutcome.Cause.OFFLINE,
                                              LookupOutcome.Cause.REFUSED)
        val (transport, malformed) = LookupOutcome.Cause.TRANSPORT to LookupOutcome.Cause.MALFORMED
        val noId = "no usable game id"
        val noGame = "no usable metadata for game 1446"
        class Case(val name: String, val cause: LookupOutcome.Cause, val detail: String,
                   val replies: List<Reply>, val deviceOffline: Boolean = false, val reachable: Boolean = true)
        val cases = listOf(
            Case("the id: 404", refused, "HTTP 404", listOf(Reply("not found", 404))),
            // It carries no key, so there is none for it to refuse.
            Case("the id: 401", refused, "HTTP 401", listOf(Reply(UNAUTHENTICATED, 401))),
            Case("the id: 503 four times", refused, "HTTP 503", List(4) { Reply("unavailable", 503) }),
            Case("the id: 429 four times, on a device that says it is offline", refused, "HTTP 429",
                 List(4) { Reply("slow down", 429) }, deviceOffline = true),
            Case("the id: Success false", refused, noId, listOf(Reply("""{"Success":false,"GameID":0}"""))),
            Case("the id: a page", malformed, noId, listOf(Reply("<html>Temporarily unavailable</html>"))),
            Case("the id: no GameID", malformed, noId, listOf(Reply("""{"Success":true}"""))),
            Case("the id: no Success", malformed, noId, listOf(Reply("""{"GameID":0}"""))),
            Case("the id: no answer", transport, "ConnectException", emptyList(), reachable = false),
            Case("the id: no answer and no connection", offline, "ConnectException", emptyList(),
                 deviceOffline = true, reachable = false),
            Case("the id: cut short four times", transport, "ProtocolException",
                 List(4) { Reply("""{"Success":true,"GameID":0}""", cutShort = true) }),
            Case("the id: hung up on four times", transport, "IOException", List(4) { Reply("", hungUp = true) }),
            Case("the game: 401", auth, "HTTP 401", listOf(id, Reply(UNAUTHENTICATED, 401))),
            Case("the game: 404", refused, "HTTP 404", listOf(id, Reply("not found", 404))),
            Case("the game: 503 four times", refused, "HTTP 503", listOf(id) + List(4) { Reply("unavailable", 503) }),
            Case("the game: Success false", refused, "metadata for game 1446 refused",
                 listOf(id, Reply("""{"Success":false,"Error":"Invalid credentials"}"""))),
            Case("the game: an empty list", malformed, noGame, listOf(id, Reply("[]"))),
            Case("the game: no title", malformed, noGame,
                 listOf(id, Reply("""{"ID":1446,"Title":"   ","NumAchievements":0}"""))),
            Case("the game: a page that echoes the key", malformed, noGame,
                 listOf(id, Reply("<html>/API/API_GetGameExtended.php?z=$USER&y=$API_KEY&i=1446</html>"))),
            Case("the game: cut short, and no connection", offline, "ProtocolException",
                 listOf(id, Reply(VALID_METADATA, cutShort = true)), deviceOffline = true))

        val wrong = ArrayList<String>()
        for (case in cases) {
            replies.clear()
            replies += case.replies
            logs.clear()
            val port = if (case.reachable) here else deadPort()
            val asked = RaApiHashLookup(USER, API_KEY, "http://127.0.0.1:$port",
                                        DeviceConnection { case.deviceOffline })
            val outcome = asked.lookup(HASH)
            val said = (outcome as? LookupOutcome.Failed)?.detail
            fun note(what: String) { wrong += "${case.name}: $what" }

            if ((outcome as? LookupOutcome.Failed)?.cause != case.cause) note("$outcome, not ${case.cause}")
            if (said != case.detail) note("said \"$said\", not \"${case.detail}\"")
            if (said.isNullOrBlank()) note("said nothing")
            if (said.orEmpty().contains(API_KEY)) note("the key is in \"$said\"")
            for (where in listOf("://", "127.0.0.1", "$port")) {
                if (said.orEmpty().contains(where)) note("where it was asking, $where, is in \"$said\"")
            }
            // Left out of the outcome and not lost: the log still has what the
            // exception said, after its class.
            if ((case.cause == transport || case.cause == offline) && logs.none { it.contains("$said: ") }) {
                note("the log has not kept what the exception said: $logs")
            }
            if (asked.authRejected != (case.cause == auth)) note("authRejected is ${asked.authRejected}")
            if (asked.offline != (case.cause == offline)) note("offline is ${asked.offline}")
            if (asked.consecutiveFailures != 1) note("counted ${asked.consecutiveFailures} times")
            if (replies.isNotEmpty()) note("${replies.size} replies were not asked for")
        }
        assertEquals(emptyList(), wrong)
    }

    // The catch at the end of a lookup, which nothing the source does can
    // reach: what a request throws and what a body throws are caught before
    // it. It is there for a fault in the lookup itself, and the one thing a
    // test can make throw on the way is the log, at the warning for a body
    // that is no answer. Such a fault is a failure like the others, counted
    // and asked about again, and not an exception thrown out of the scan. It
    // says its class and nothing its message held, which is in the log.
    @Test fun `a fault of the lookup's own is a failure that says its class and no more`() = runTest {
        val here = "http://127.0.0.1:${server.address.port}"
        val recording = BridgeLog.current
        BridgeLog.current = object : BridgeLog by recording {
            override fun w(tag: String, msg: String, t: Throwable?) {
                throw IllegalStateException("asked $here/dorequest.php with $API_KEY")
            }
        }
        replies += Reply("<html>not an answer</html>")

        assertEquals<LookupOutcome>(
            LookupOutcome.Failed(LookupOutcome.Cause.TRANSPORT, "IllegalStateException"), lookup.lookup(HASH))
        assertEquals(1, lookup.consecutiveFailures)
        assertFalse(lookup.authRejected)
        assertFalse(lookup.offline)
        assertTrue(logs.any { it.contains("lookup failed for hash $HASH: IllegalStateException: asked $here") },
                   "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")
    }

    /**
     * The coroutine returning is not the proof: suspendCancellableCoroutine hands
     * a cancelled caller back at once whatever happens to the socket, and with
     * the call left running OkHttp would go on reading until the 30 s timeout.
     * So the server streams a body that never ends, a byte every 20 ms, and
     * watches for the moment a write fails because the client has gone. Only
     * call.cancel() closes that socket while the body is held open.
     */
    @Test fun `cancellation stops an in-flight body read without reporting failure`() {
        val bodyStarted = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val clientGone = CountDownLatch(1)
        val returnedNormally = AtomicBoolean()
        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            exchange.sendResponseHeaders(200, 0)   // chunked: no length, no end
            try {
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
                bodyStarted.countDown()
                while (!releaseBody.await(20, TimeUnit.MILLISECONDS)) {
                    exchange.responseBody.write(' '.code)
                    exchange.responseBody.flush()
                }
            } catch (e: java.io.IOException) {
                clientGone.countDown()
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
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(clientGone.await(2, TimeUnit.SECONDS),
                           "the body read went on after the cancel: the socket was never closed")
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
