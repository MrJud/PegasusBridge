package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgeVersion
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the live lookup asks and what it makes of each kind of answer, against
 * a local server that serves exactly the bodies under test. Nothing here
 * reaches RetroAchievements, and the games and hashes are invented.
 *
 * The lookup asks for the list of a console and answers that console's
 * hashes from it. Three answers must not bleed into each other: a match,
 * which comes with its title or not at all; a hash that is in none of its
 * lists, which the ledger keeps for fourteen days; and a lookup that could
 * not be made, which is asked again at the next scan. A list is every file of
 * a console at once, so most of these tests are of the third: what is not a
 * list, and must not be taken for one that lacks the hash. An HTML page
 * served with 200 used to come out as a miss.
 *
 * Some tests still say what they expect as a [GameMetadata] that might be
 * null, through [asLegacy], which is how the lookup answered once.
 */
class RaHashLookupTest {
    /**
     * [cutShort] promises more bytes than it sends and then closes: headers
     * that arrive and a body that does not, which the client meets as an
     * exception and not as an answer. [hungUp] sends nothing at all and closes,
     * which the client meets as an exception that quotes where it was asking.
     * [headers] go out with the status, as a `Retry-After` does with a 429.
     */
    private data class Reply(val body: String, val status: Int = 200, val cutShort: Boolean = false,
                             val hungUp: Boolean = false, val headers: Map<String, String> = emptyMap())

    private lateinit var server: HttpServer
    private lateinit var lookup: RaApiHashLookup
    private lateinit var previousLog: BridgeLog
    private lateinit var lists: File
    private lateinit var here: String
    private val now = AtomicLong(1_700_000_000L)
    private val replies = ConcurrentLinkedQueue<Reply>()
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    /** The query of each request, as it was sent and not yet decoded. */
    private val queries = Collections.synchronizedList(mutableListOf<String>())
    /** The User-Agent of each request, and null for one that named none. */
    private val agents = Collections.synchronizedList(mutableListOf<String?>())
    private val inFlight = AtomicInteger()
    private val mostInFlight = AtomicInteger()
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
        lists = Files.createTempDirectory("ra-lookup-lists").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            queries += exchange.requestURI.rawQuery.orEmpty()
            agents += exchange.requestHeaders.getFirst("User-Agent")
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                val reply = replies.poll() ?: Reply("unexpected request", 400)
                // Closed before a header is sent, the exchange takes its connection with it.
                if (reply.hungUp) { exchange.close(); return@createContext }
                val bytes = reply.body.toByteArray()
                reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong() + if (reply.cutShort) 64 else 0)
                // Closing a body that is short of its length throws, and what a
                // handler throws makes the server drop the connection: the client
                // has the headers by then and finds the body ended early.
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            } finally {
                inFlight.decrementAndGet()
            }
        }
        server.start()
        here = "http://127.0.0.1:${server.address.port}"
        lookup = asking()
    }

    @AfterTest fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
        BridgeLog.current = previousLog
        heldPorts.forEach { it.close() }
        lists.deleteRecursively()
    }

    /** A lookup as a new scan builds one, on the folder and the clock of the test. */
    private fun asking(key: String = API_KEY, user: String = USER, folder: File = lists, url: String = here,
                       device: DeviceConnection = DeviceConnection { false }) =
        RaApiHashLookup(user, key, folder, url, { now.get() }, device)

    /** One game as the API lists it. With no [console] it says of which it is. */
    private fun game(id: Int, title: String?, vararg hashes: String, console: Int? = 7,
                     achievements: Any? = 12) = buildString {
        append("""{"ID":$id,"NumLeaderboards":0,"Points":50,"ImageIcon":"/Images/$id.png",""")
        if (title != null) append(""""Title":${JSONObject.quote(title)},""")
        if (console != null) append(""""ConsoleID":$console,"ConsoleName":"Invented System $console",""")
        if (achievements != null) append(""""NumAchievements":$achievements,""")
        append(""""Hashes":[${hashes.joinToString(",") { "\"$it\"" }}]}""")
    }

    private fun list(vararg games: String) = Reply(games.joinToString(",", "[", "]"))

    /** A list that holds [HASH] as the game of the tests, for [console]. */
    private fun listed(console: Int = 7) = list(game(4101, "Moss Kingdom", HASH, console = console),
                                                game(4102, "Paper Rally", console = console))

    private val mossKingdom = GameMetadata(4101, "Moss Kingdom", "Invented System 7", "/Images/4101.png", 12)

    private fun params(query: String): Map<String, String> = query.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    private fun kept(): List<String> = lists.list().orEmpty().sorted()

    @Test fun `one request answers every hash of a console`() = runTest {
        replies += list(game(4101, "Moss Kingdom", HASH, OTHER), game(4102, "Paper Rally", THIRD))

        assertEquals(4101, lookup.lookup(HASH, listOf(7)).asLegacy()?.gameId)
        assertEquals(4101, lookup.lookup(OTHER, listOf(7)).asLegacy()?.gameId)
        assertEquals(4102, lookup.lookup(THIRD, listOf(7)).asLegacy()?.gameId)

        assertEquals(listOf(LIST_PATH), requests.toList())
        // Every game of the console, with its hashes: `f=1` would leave out
        // the games with no achievements, which hold most of the hashes.
        assertEquals(mapOf("z" to USER, "y" to API_KEY, "i" to "7", "h" to "1"), params(queries.single()))
        assertFalse("f=" in queries.single())
        assertEquals(listOf(1, 1, 0), listOf(lookup.requests, lookup.listsFetched, lookup.listsRead))
        assertTrue(logs.any { it == "console 7: 2 listed, 2 games, 3 hashes, fetched" }, "$logs")
    }

    @Test fun `a match comes with the title, icon and count of the list`() = runTest {
        replies += list(game(4101, "Moss Kingdom", HASH), game(4102, "Paper Rally", OTHER, achievements = 0))

        assertEquals<LookupOutcome>(LookupOutcome.Match(mossKingdom), lookup.lookup(HASH, listOf(7)))
        // A game with no achievements is a game: the list has it, and its
        // count is a number.
        assertEquals<LookupOutcome>(
            LookupOutcome.Match(GameMetadata(4102, "Paper Rally", "Invented System 7", "/Images/4102.png", 0)),
            lookup.lookup(OTHER, listOf(7)))
        assertEquals(1, requests.size)
    }

    @Test fun `a hash in none of its lists is a miss, and clears the count of failures`() = runTest {
        replies += Reply("<html>Temporarily unavailable</html>")
        assertIs<LookupOutcome.Failed>(lookup.lookup(HASH, listOf(9)))
        assertEquals(1, lookup.consecutiveFailures)
        replies += listed()

        assertEquals<LookupOutcome>(LookupOutcome.NotFound, lookup.lookup(OTHER, listOf(7)))

        assertEquals(0, lookup.consecutiveFailures)
        assertEquals(2, requests.size)
    }

    // RetroAchievements compares hashes without regard to case and its lists
    // hold some in capitals. Compared as written, those games are never found.
    @Test fun `a hash the list has in capitals is matched`() = runTest {
        replies += list(game(4101, "Moss Kingdom", HASH.uppercase()))

        assertEquals(4101, lookup.lookup(HASH, listOf(7)).asLegacy()?.gameId)
        assertEquals(4101, lookup.lookup(HASH.uppercase(), listOf(7)).asLegacy()?.gameId, "asked in capitals")
    }

    // The console a file was hashed as comes first, and nearly every hash is
    // in its list. The others are asked for only when it is not.
    @Test fun `a miss in the first console asks the next, a hit does not`() = runTest {
        replies += listed(console = 4)
        replies += list(game(4201, "Tin Harbour", OTHER, console = 6))
        assertEquals<LookupOutcome>(LookupOutcome.NotFound, lookup.lookup(THIRD, listOf(4, 6)))
        assertEquals(listOf("4", "6"), queries.map { params(it)["i"] })
        // Found in the second, and described as a game of that console.
        assertEquals(GameMetadata(4201, "Tin Harbour", "Invented System 6", "/Images/4201.png", 12),
                     lookup.lookup(OTHER, listOf(4, 6)).asLegacy())
        assertEquals(2, requests.size)

        val other = asking(folder = File(lists, "another-scan"))
        replies += listed(console = 4)
        assertEquals(4101, other.lookup(HASH, listOf(4, 6)).asLegacy()?.gameId)
        assertEquals(1, other.requests, "a hash found in the first list had the second asked for")
    }

    @Test fun `a list is kept on disk and the next scan asks nobody`() = runTest {
        replies += list(game(4101, "Moss Kingdom", HASH, OTHER), game(4102, "Paper Rally"),
                        game(4103, "Tin Harbour", OTHER, THIRD))
        assertEquals(4101, lookup.lookup(OTHER, listOf(7)).asLegacy()?.gameId)
        assertEquals(listOf("7.tsv"), kept())
        assertTrue(API_KEY !in File(lists, "7.tsv").readText() && USER !in File(lists, "7.tsv").readText())

        now.addAndGet(3 * DAY + 100)
        val next = asking()
        assertEquals<LookupOutcome>(LookupOutcome.Match(mossKingdom), next.lookup(HASH, listOf(7)))
        assertEquals(4101, next.lookup(OTHER, listOf(7)).asLegacy()?.gameId, "the first game it was given to")
        assertEquals(4103, next.lookup(THIRD, listOf(7)).asLegacy()?.gameId)
        assertEquals<LookupOutcome>(LookupOutcome.NotFound, next.lookup("f".repeat(32), listOf(7)))

        assertEquals(1, requests.size)
        assertEquals(listOf(0, 0, 1), listOf(next.requests, next.listsFetched, next.listsRead))
        assertTrue(logs.any { it == "console 7: 2 games, 3 hashes, read from disk, 3 days old" }, "$logs")
    }

    @Test fun `a list older than seven days is asked for again`() = runTest {
        replies += listed()
        lookup.lookup(HASH, listOf(7))

        now.addAndGet(7 * DAY)
        assertEquals(4101, asking().lookup(HASH, listOf(7)).asLegacy()?.gameId)
        assertEquals(1, requests.size, "a list seven days old was asked for again")

        now.addAndGet(1)
        replies += list(game(4101, "Moss Kingdom", HASH), game(4105, "Low Tide", OTHER))
        val later = asking()
        assertEquals(4105, later.lookup(OTHER, listOf(7)).asLegacy()?.gameId)
        assertEquals(2, requests.size)
        assertEquals(listOf(1, 0), listOf(later.listsFetched, later.listsRead))
        // And it is the new one that is kept.
        assertEquals(4105, asking().lookup(OTHER, listOf(7)).asLegacy()?.gameId)
        assertEquals(2, requests.size)
    }

    @Test fun `a list on disk that cannot be read is asked for again`() = runTest {
        replies += listed()
        lookup.lookup(HASH, listOf(7))
        val file = File(lists, "7.tsv")
        file.writeText(file.readText().dropLast(40))

        replies += listed()
        val next = asking()
        assertEquals(4101, next.lookup(HASH, listOf(7)).asLegacy()?.gameId)

        assertEquals(2, requests.size)
        assertEquals(listOf(1, 0), listOf(next.listsFetched, next.listsRead))
        assertEquals(4101, asking().lookup(HASH, listOf(7)).asLegacy()?.gameId)
        assertEquals(2, requests.size, "the list written over the broken one was not read back")
    }

    // The files of a console come one after another. Asked for at each, a
    // list that cannot be had would cost four requests a file; and the scan
    // has to stop all the same, which it does on the count.
    @Test fun `a list that could not be had fails every lookup of its console without asking again`() = runTest {
        repeat(4) { replies += Reply("unavailable", 503) }

        repeat(5) { n ->
            assertEquals<LookupOutcome>(LookupOutcome.Failed(LookupOutcome.Cause.REFUSED, "HTTP 503"),
                                        lookup.lookup(if (n % 2 == 0) HASH else OTHER, listOf(7)))
            assertEquals(n + 1, lookup.consecutiveFailures)
        }

        assertEquals(4, requests.size)
        assertEquals(listOf(4, 0, 0), listOf(lookup.requests, lookup.listsFetched, lookup.listsRead))
        assertEquals(emptyList(), kept())
    }

    // The old list has the hash. It is past its time all the same, and what
    // it does not have would be written off for fourteen days on its word.
    @Test fun `a list past its seven days is not answered from when the refresh fails`() = runTest {
        replies += listed()
        lookup.lookup(HASH, listOf(7))
        now.addAndGet(7 * DAY + 1)
        repeat(4) { replies += Reply("unavailable", 503) }
        val later = asking()

        assertIs<LookupOutcome.Failed>(later.lookup(HASH, listOf(7)))
        assertIs<LookupOutcome.Failed>(later.lookup(OTHER, listOf(7)))

        assertEquals(5, requests.size)
        assertEquals(0, later.listsRead)
    }

    // No console a scan asks about has no games, so `[]` is an answer gone
    // wrong. Believed, it is a miss for every file of the console, kept for
    // fourteen days, from a list kept for seven. And it is one console's
    // trouble: counted, eight of its files would stop the scan there, at
    // every scan, with the rest of the library never reached.
    @Test fun `an empty list is a failure that is not kept and is not held against the source`() = runTest {
        replies += Reply("[]")

        repeat(3) {
            assertEquals<LookupOutcome>(
                LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED, "an empty list of console 7"),
                lookup.lookup(HASH, listOf(7)))
        }

        assertEquals(0, lookup.consecutiveFailures)
        assertEquals(1, requests.size)
        assertEquals(0, lookup.listsFetched)
        assertEquals(emptyList(), kept())

        replies += listed()
        assertEquals(4101, asking().lookup(HASH, listOf(7)).asLegacy()?.gameId)
        assertEquals(2, requests.size, "the next scan did not ask again")
    }

    @Test fun `a list of another console's games is a failure that is not held against the source`() = runTest {
        replies += list(game(4101, "Moss Kingdom", HASH), game(4102, "Paper Rally", OTHER, console = 9))

        repeat(3) {
            assertEquals<LookupOutcome>(
                LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED, "no usable list of console 7"),
                lookup.lookup(HASH, listOf(7)))
        }

        assertEquals(0, lookup.consecutiveFailures)
        assertEquals(1, requests.size)
        assertEquals(emptyList(), kept())
        assertTrue(logs.any { "no usable list of console 7, game 4102 is of another console" in it }, "$logs")

        replies += listed()
        assertEquals(4101, asking().lookup(HASH, listOf(7)).asLegacy()?.gameId)
    }

    // A hash that is in the first list is found there, whatever became of
    // the second. One that is not may be in the second, and nobody can say.
    @Test fun `a family list that could not be had fails the miss and not the hit`() = runTest {
        replies += listed(console = 4)
        repeat(4) { replies += Reply("unavailable", 503) }

        assertEquals<LookupOutcome>(LookupOutcome.Failed(LookupOutcome.Cause.REFUSED, "HTTP 503"),
                                    lookup.lookup(OTHER, listOf(4, 6)))
        assertEquals(1, lookup.consecutiveFailures)
        assertEquals(4101, lookup.lookup(HASH, listOf(4, 6)).asLegacy()?.gameId)
        assertEquals(0, lookup.consecutiveFailures)
        assertIs<LookupOutcome.Failed>(lookup.lookup(THIRD, listOf(4, 6)))

        assertEquals(5, requests.size)
    }

    // The title is what makes a match, and the count is written down with
    // it. A game that came without one is known to be there, so its hashes
    // are no miss either. The rest of the list is as good as it was.
    @Test fun `a game the list cannot describe is a failure for its hashes only`() = runTest {
        replies += list(game(4101, "   ", HASH), game(4102, "Paper Rally", OTHER),
                        game(4103, "Tin Harbour", THIRD, achievements = null))

        assertEquals<LookupOutcome>(
            LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED, "no usable description of game 4101"),
            lookup.lookup(HASH, listOf(7)))
        assertEquals(1, lookup.consecutiveFailures)
        assertEquals(4102, lookup.lookup(OTHER, listOf(7)).asLegacy()?.gameId)
        assertEquals(0, lookup.consecutiveFailures)
        assertEquals<LookupOutcome>(
            LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED, "no usable description of game 4103"),
            lookup.lookup(THIRD, listOf(7)))
        assertEquals(1, requests.size)
    }

    @Test fun `a hash with no console is a failure, asks nobody and is not held against the source`() = runTest {
        val noConsole = LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED, "no console to look the hash up in")

        assertEquals<LookupOutcome>(noConsole, lookup.lookup(HASH))
        assertEquals<LookupOutcome>(noConsole, lookup.lookup(HASH, emptyList()))
        assertEquals<LookupOutcome>(noConsole, lookup.lookup(HASH, listOf(0, -1)))

        assertEquals(0, lookup.consecutiveFailures)
        assertEquals(emptyList(), requests.toList())
        assertEquals(0, lookup.requests)
    }

    // Written into the URL as it is, an `&` in a key ends it and begins
    // another parameter, and a `+` arrives as a space.
    @Test fun `a key with a character a URL cannot carry is sent whole`() = runTest {
        val key = "k/ey+1 &i=9#x%41"
        val user = "some one&y=other"
        replies += listed()

        assertEquals(4101, asking(key = key, user = user).lookup(HASH, listOf(7)).asLegacy()?.gameId)

        assertEquals(mapOf("z" to user, "y" to key, "i" to "7", "h" to "1"), params(queries.single()))
    }

    @Test fun `nothing is asked of dorequest php or of API_GetGameExtended php`() = runTest {
        replies += listed()
        replies += listed(console = 4)
        replies += Reply("<html>not a list</html>")
        assertIs<LookupOutcome.Match>(lookup.lookup(HASH, listOf(7)))
        assertEquals<LookupOutcome>(LookupOutcome.NotFound, lookup.lookup(OTHER, listOf(7, 4)))
        assertIs<LookupOutcome.Failed>(lookup.lookup(THIRD, listOf(9)))
        assertIs<LookupOutcome.Match>(lookup.lookup(HASH, listOf(4)))

        assertEquals(List(3) { LIST_PATH }, requests.toList())
        assertEquals(LIST_PATH, "/API/API_GetGameList.php")
    }

    // Two workers ask at once, and the first hash of each is of the same
    // console. The answer is held until both have asked.
    @Test fun `two lookups at once of one console make one request`() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            arrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            val bytes = listed().body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        runBlocking {
            val first = async(Dispatchers.IO) { lookup.lookup(HASH, listOf(7)) }
            assertTrue(arrived.await(3, TimeUnit.SECONDS), "request did not reach the local server")
            val second = async(Dispatchers.IO) { lookup.lookup(OTHER, listOf(7)) }
            // Long enough for the second to have asked, were it going to.
            delay(400)
            release.countDown()

            assertEquals<LookupOutcome>(LookupOutcome.Match(mossKingdom), first.await())
            assertEquals<LookupOutcome>(LookupOutcome.NotFound, second.await())
            assertEquals(1, requests.size)
            assertEquals(listOf(1, 1), listOf(lookup.requests, lookup.listsFetched))
        }
    }

    // Not yet seen from RA, but it is what a maintenance screen or a proxy's
    // challenge page looks like: HTML, served with 200. Read as a list with
    // nothing in it, it was a miss, and the ledger kept it for fourteen days.
    @Test fun `an html page served with 200 is a failure, not a miss`() = runTest {
        replies += Reply("<html><body>Temporarily unavailable</body></html>")

        assertEquals<LookupOutcome>(LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED, "no usable list of console 7"),
                                    lookup.lookup(HASH, listOf(7)))
        assertEquals(1, lookup.consecutiveFailures)
        assertNull(lookup.lookup(OTHER, listOf(7)).asLegacy())
        assertEquals(2, lookup.consecutiveFailures, "the source is not answering as itself, and that counts")
        assertEquals(listOf(LIST_PATH), requests.toList())
        assertEquals(emptyList(), kept())
        assertTrue(logs.any { it.contains("Temporarily unavailable") },
                   "the refused body should be quoted, so a format change can be diagnosed:\n$logs")
    }

    // An answer cut off in the middle that arrives as a whole body, as a
    // proxy can make of one, is a list of the games before the cut to
    // whoever reads it loosely.
    @Test fun `a list that is not whole is a failure, not a list of what came before the cut`() = runTest {
        replies += Reply(listed().body.dropLast(30))

        assertEquals(LookupOutcome.Cause.MALFORMED, (lookup.lookup(HASH, listOf(7)) as LookupOutcome.Failed).cause)
        assertEquals(1, lookup.consecutiveFailures)
        assertEquals(emptyList(), kept())
    }

    // What RAWeb's api-token guard answers whenever `y` matches no account's web
    // API key: a wrong, revoked or empty key, and a banned account's, which the
    // ban clears. Every request carries the key, so nothing after it can be
    // answered, and no request is spent on hearing it again.
    @Test fun `a 401 is the key refused, and stays refused`() = runTest {
        assertFalse(lookup.authRejected)
        replies += Reply(UNAUTHENTICATED, 401)

        assertEquals<LookupOutcome>(LookupOutcome.Failed(LookupOutcome.Cause.AUTH, "HTTP 401"),
                                    lookup.lookup(HASH, listOf(7)))
        assertTrue(lookup.authRejected)
        assertEquals(1, requests.size, "a refusal is not retried")

        assertEquals<LookupOutcome>(LookupOutcome.Failed(LookupOutcome.Cause.AUTH, "HTTP 401"),
                                    lookup.lookup(OTHER, listOf(9)))
        assertEquals(1, requests.size, "another console was asked for with the key that had been refused")
        assertEquals(2, lookup.consecutiveFailures)
        assertTrue(lookup.authRejected)
        assertEquals(emptyList(), kept())
    }

    // Failures, but none of them the key: a 404, and an explicit Success:false,
    // which is not something RAWeb sends for a bad key.
    @Test fun `other refusals do not say the key was refused`() = runTest {
        replies += Reply("not found", 404)
        assertNull(lookup.lookup(HASH, listOf(7)).asLegacy())
        replies += Reply("""{"Success":false,"Error":"Invalid credentials"}""")
        assertNull(lookup.lookup(HASH, listOf(8)).asLegacy())
        replies += Reply("""{"message":"Unauthenticated."}""")
        assertNull(lookup.lookup(HASH, listOf(9)).asLegacy())

        assertEquals(3, lookup.consecutiveFailures)
        assertFalse(lookup.authRejected)
    }

    @Test fun `temporary server failures are retried`() = runTest {
        replies += Reply("rate limited", 429)
        replies += Reply("unavailable", 503)
        replies += listed()

        assertEquals(4101, lookup.lookup(HASH, listOf(7)).asLegacy()?.gameId)
        assertEquals(3, requests.size)
        assertEquals(listOf(3, 1), listOf(lookup.requests, lookup.listsFetched))
        assertEquals(0, lookup.consecutiveFailures)
    }

    // The username is allowed through: SafeUrl keeps `z=` on purpose, because a
    // line that cannot say whose request failed sends the reader to a packet
    // capture. The key is what must never appear.
    @Test fun `exhausted retries count once and never log the key`() = runTest {
        repeat(4) { replies += Reply("$USER $API_KEY", 503) }

        assertNull(lookup.lookup(HASH, listOf(7)).asLegacy())

        assertEquals(4, requests.size)
        assertEquals(1, lookup.consecutiveFailures)
        assertTrue(logs.any { it.contains("retries exhausted") && it.contains(LIST_PATH) && it.contains("i=7") },
                   "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")
    }

    // As typed, and as a URL writes it: a page that echoes the query shows
    // the key as it travelled.
    @Test fun `a body that echoes the key is quoted without it`() = runTest {
        replies += Reply("<html>Bad request: $LIST_PATH?z=$USER&y=$API_KEY&i=7&h=1</html>")

        assertNull(lookup.lookup(HASH, listOf(7)).asLegacy())
        assertTrue(logs.any { it.contains("Bad request") }, "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")

        // The key as the request carries it is learnt from a request, and
        // not written here from what a URL is thought to make of it.
        val key = "k/ey+1 &x"
        replies += Reply("not found", 404)
        asking(key = key).lookup(HASH, listOf(8))
        val carried = queries.last().substringAfter("&y=").substringBefore("&i=")
        assertTrue(carried != key && URLDecoder.decode(carried, "UTF-8") == key, carried)
        val asAForm = java.net.URLEncoder.encode(key, "UTF-8")

        logs.clear()
        replies += Reply("<html>Bad request: $LIST_PATH?z=$USER&y=$carried&i=7 or y=$asAForm or y=$key</html>")
        assertNull(asking(key = key).lookup(HASH, listOf(7)).asLegacy())

        assertTrue(logs.any { it.contains("Bad request") }, "$logs")
        for (form in listOf(key, carried, asAForm))
            assertTrue(logs.none { it.contains(form) }, "the API key reached the log as $form:\n$logs")
    }

    // Back-offs of 1, 2 and 4 seconds between four attempts, and nothing after
    // the last: there is no attempt left to wait for. The old loop slept 8 more
    // seconds before giving up, which would be 15 here.
    //
    // The spacing of requests is measured on the real clock and the waits of
    // these tests are on a clock of their own, which no time passes on while
    // the test sleeps. So each request after the first is held a second more
    // here, where on a real clock the back-off before it had been its second.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `the last attempt is not followed by a back-off`() = runTest {
        repeat(4) { replies += Reply("unavailable", 503) }

        assertNull(lookup.lookup(HASH, listOf(7)).asLegacy())

        assertEquals(4, requests.size)
        assertTrue(currentTime in 7_000L..10_000L, "virtual time spent: $currentTime ms")
    }

    @Test fun `every request says which program and version is asking`() = runTest {
        replies += Reply("unavailable", 503)
        replies += listed()

        assertEquals(4101, lookup.lookup(HASH, listOf(7)).asLegacy()?.gameId)

        val named = "PegasusBridge/${BridgeVersion.NAME} (+https://github.com/MrJud/PegasusBridge)"
        assertEquals(listOf<String?>(named, named), agents.toList())
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(BridgeVersion.NAME), BridgeVersion.NAME)
    }

    // Too many requests is the source well and counting. How long to stay
    // away is its own to say, and it says so in seconds.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a 429 is waited out for as long as Retry-After says`() = runTest {
        replies += Reply("slow down", 429, headers = mapOf("Retry-After" to " 7 "))
        replies += listed()

        assertEquals<LookupOutcome>(LookupOutcome.Match(mossKingdom), lookup.lookup(HASH, listOf(7)))

        assertEquals(2, requests.size)
        assertTrue(currentTime in 7_000L..8_000L, "virtual time spent: $currentTime ms")
        assertEquals(0, lookup.consecutiveFailures)
        assertTrue(logs.any { it == "RetroAchievements asked to wait 7 s" }, "$logs")
    }

    // RetroAchievements counts over a minute. A date in the header is read as
    // no header: it would be held to this machine's clock.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a 429 that names no wait is given a minute`() = runTest {
        for ((n, headers) in listOf(emptyMap(), mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT"),
                                    mapOf("Retry-After" to "-5")).withIndex()) {
            replies += Reply("slow down", 429, headers = headers)
            replies += listed(console = 7 + n)
            val asked = asking(folder = File(lists, "scan-$n"))
            val before = currentTime

            assertEquals(4101, asked.lookup(HASH, listOf(7 + n)).asLegacy()?.gameId, "$headers")

            assertEquals(2, asked.requests, "$headers")
            assertTrue(currentTime - before in 60_000L..61_000L, "$headers: ${currentTime - before} ms")
        }
    }

    // A scan is not held still for an hour on one answer, and the one thing
    // not to do is ask before the time given. So the lookup ends there, and
    // the console's other files end with it and ask nobody.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a 429 that asks for more than two minutes ends the lookup at once`() = runTest {
        replies += Reply("slow down", 429, headers = mapOf("Retry-After" to "3600"))
        val gaveUp = LookupOutcome.Failed(LookupOutcome.Cause.REFUSED, "HTTP 429, asked to wait 3600 s")

        assertEquals<LookupOutcome>(gaveUp, lookup.lookup(HASH, listOf(7)))
        assertEquals<LookupOutcome>(gaveUp, lookup.lookup(OTHER, listOf(7)))

        assertEquals(1, requests.size)
        assertTrue(currentTime < 1_000L, "virtual time spent: $currentTime ms")
        assertEquals(2, lookup.consecutiveFailures)
        assertFalse(lookup.authRejected)

        // Two minutes is waited, and no more than that.
        replies += Reply("slow down", 429, headers = mapOf("Retry-After" to "120"))
        replies += listed(console = 8)
        val before = currentTime
        assertEquals(4101, lookup.lookup(HASH, listOf(8)).asLegacy()?.gameId)
        assertTrue(currentTime - before in 120_000L..122_000L, "virtual time spent: ${currentTime - before} ms")
        replies += Reply("slow down", 429, headers = mapOf("Retry-After" to "121"))
        assertIs<LookupOutcome.Failed>(lookup.lookup(HASH, listOf(9)))
        assertEquals(4, requests.size)
    }

    // Three waits for four attempts. A wait after the last would be twenty
    // seconds or more here, for an answer nobody is going to ask for.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a 429 at the last attempt is not waited on`() = runTest {
        repeat(4) { replies += Reply("slow down", 429, headers = mapOf("Retry-After" to "5")) }

        assertEquals<LookupOutcome>(LookupOutcome.Failed(LookupOutcome.Cause.REFUSED, "HTTP 429"),
                                    lookup.lookup(HASH, listOf(7)))

        assertEquals(4, requests.size)
        assertTrue(currentTime in 15_000L..18_000L, "virtual time spent: $currentTime ms")
        assertEquals(3, logs.count { it == "RetroAchievements asked to wait 5 s" }, "$logs")
    }

    // From RetroAchievements a 403 is a client it has blocked, by what it
    // calls itself or where it asks from. It was taken for a limit and asked
    // three times more.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a 403 is never asked again`() = runTest {
        replies += Reply("Forbidden", 403, headers = mapOf("Retry-After" to "1"))
        val forbidden = LookupOutcome.Failed(LookupOutcome.Cause.REFUSED, "HTTP 403")

        assertEquals<LookupOutcome>(forbidden, lookup.lookup(HASH, listOf(7)))

        assertEquals(1, requests.size)
        assertFalse(lookup.authRejected, "a blocked client is not a refused key")
        assertTrue(currentTime < 1_000L, "virtual time spent: $currentTime ms")

        assertEquals<LookupOutcome>(forbidden, lookup.lookup(OTHER, listOf(7)))
        assertEquals(1, requests.size, "the console was asked for again")
        assertEquals(2, lookup.consecutiveFailures)
        assertEquals(emptyList(), kept())
    }

    // Two workers, each with a hash of another console. The second list is
    // asked for when the first has been answered and a second after it was
    // asked for: less the moment the first request took, since the spacing
    // is from one request's start to the next.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `requests are one at a time and a second apart`() = runTest {
        // The first request a process makes takes as long as loading the
        // client does, and that is not what is measured here.
        replies += listed(console = 9)
        asking(folder = File(lists, "another-scan")).lookup(HASH, listOf(9))
        replies += listed(console = 7)
        replies += listed(console = 8)

        val first = async { lookup.lookup(HASH, listOf(7)) }
        val second = async { lookup.lookup(HASH, listOf(8)) }

        assertIs<LookupOutcome.Match>(first.await())
        assertIs<LookupOutcome.Match>(second.await())
        assertEquals(listOf("9", "7", "8"), queries.map { params(it)["i"] })
        assertEquals(1, mostInFlight.get(), "two requests were with the server at once")
        assertTrue(currentTime in 900L..1_000L, "virtual time spent: $currentTime ms")
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
        asking(url = "http://127.0.0.1:${deadPort()}", device = DeviceConnection(deviceOffline))

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

        assertNull(lookup.lookup(HASH, listOf(7)).asLegacy())

        assertEquals(1, asked, "one request fails once: the device is asked once")
        assertEquals(0L, currentTime, "a back-off was waited through")
        assertTrue(lookup.offline)
        assertEquals(1, lookup.consecutiveFailures)
        assertTrue(logs.any { it.contains("no internet connection") && it.contains(LIST_PATH) }, "$logs")
        assertTrue(logs.none { it.contains("retries exhausted") }, "$logs")

        // The next file of the console is not asked about: the list is known
        // to be missing. It fails the same way and the scan stops on it.
        assertEquals(LookupOutcome.Cause.OFFLINE, (lookup.lookup(OTHER, listOf(7)) as LookupOutcome.Failed).cause)
        assertEquals(1, asked)
        assertTrue(lookup.offline)
    }

    // With the lists on disk there is nothing to ask, and so nothing to fail:
    // a new ROM is matched on a plane, by a device that would say it has no
    // connection and is never asked.
    @Test fun `a list on disk is answered from with no connection at all`() = runTest {
        replies += listed()
        lookup.lookup(HASH, listOf(7))
        var asked = 0

        val onAPlane = unreachable { asked++; true }
        assertEquals(4101, onAPlane.lookup(HASH, listOf(7)).asLegacy()?.gameId)
        assertEquals<LookupOutcome>(LookupOutcome.NotFound, onAPlane.lookup(OTHER, listOf(7)))

        assertEquals(0, asked)
        assertFalse(onAPlane.offline)
        assertEquals(0, onAPlane.requests)
    }

    // What the device says explains a failure and prevents nothing. Here it
    // would say there is no connection, and is wrong: the request is made
    // all the same, it is answered, and the device is never asked.
    @Test fun `a device that would say it is offline is not asked while requests are answered`() = runTest {
        var asked = 0
        val lookup = asking { asked++; true }
        replies += listed()

        assertEquals(4101, lookup.lookup(HASH, listOf(7)).asLegacy()?.gameId)

        assertEquals(1, requests.size)
        assertEquals(0, asked, "the device was asked about a request that had not failed")
        assertFalse(lookup.offline)
    }

    // The device is asked about a request that brought back nothing, and a
    // refusal is something. Too many requests, and then a server in trouble
    // four times over: each is waited out and asked again,
    // by a lookup whose device would have said there is no connection. Asked
    // after a status as well, the first 429 on a network Android has its
    // doubts about would end the scan with the advice to connect.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a request that is answered with a refusal is not put to the device`() = runTest {
        var asked = 0
        val lookup = asking { asked++; true }
        replies += Reply("slow down", 429)
        replies += listed()

        assertEquals(4101, lookup.lookup(HASH, listOf(7)).asLegacy()?.gameId)

        assertEquals(2, requests.size, "the request that was refused was not made again")
        assertEquals(0, asked, "the device was asked about a request that was answered")
        assertFalse(lookup.offline)

        repeat(4) { replies += Reply("unavailable", 503) }
        val before = currentTime
        assertNull(lookup.lookup(HASH, listOf(8)).asLegacy())

        assertEquals(6, requests.size, "a request answered 503 was not made four times")
        // The seven seconds of back-off, and the second each of the four
        // requests is held behind the one before it on this clock.
        assertTrue(currentTime - before in 7_000L..11_000L, "virtual time spent: ${currentTime - before} ms")
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

        assertNull(lookup.lookup(HASH, listOf(7)).asLegacy())

        assertEquals(4, asked, "asked after each attempt that failed")
        assertEquals(4, lookup.requests)
        assertTrue(currentTime in 7_000L..10_000L, "virtual time spent: $currentTime ms")
        assertFalse(lookup.offline)
        assertTrue(logs.any { it.contains("retries exhausted") }, "$logs")

        val nobodyToAsk = RaApiHashLookup(USER, API_KEY, lists, "http://127.0.0.1:${deadPort()}")
        val before = currentTime
        assertNull(nobodyToAsk.lookup(HASH, listOf(7)).asLegacy())
        assertTrue(currentTime - before in 7_000L..10_000L, "virtual time spent: ${currentTime - before} ms")
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

            assertNull(lookup.lookup(HASH, listOf(7)).asLegacy(), "$thrown")

            assertEquals(4, asked, "$thrown")
            assertTrue(currentTime - before in 7_000L..10_000L, "$thrown: ${currentTime - before} ms")
            assertFalse(lookup.offline, "$thrown")
        }
    }

    // Every request carries the key. Here one is cut off in its body, on a
    // device that then says it has no connection: the line written for it
    // names the endpoint and the console, and not the key.
    @Test fun `a request given up for want of a connection is logged without the key`() = runTest {
        val lookup = asking { true }
        replies += listed().copy(cutShort = true)

        assertNull(lookup.lookup(HASH, listOf(7)).asLegacy())

        assertEquals(1, requests.size, "the request that failed was made again")
        assertTrue(lookup.offline)
        assertTrue(logs.any { it.contains("no internet connection") && it.contains("i=7") }, "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")
        assertEquals(emptyList(), kept())
    }

    // Offline is not kept as a refused key is. A request that is answered was
    // carried by a connection, whatever the answer: a 404 here, which is a
    // failed lookup and still proof that the source was reached.
    @Test fun `any answer takes back what a failed request said about the connection`() = runTest {
        val lookup = asking { true }
        replies += listed().copy(cutShort = true)
        assertNull(lookup.lookup(HASH, listOf(7)).asLegacy())
        assertTrue(lookup.offline)

        replies += Reply("not found", 404)
        assertNull(lookup.lookup(HASH, listOf(8)).asLegacy())
        assertFalse(lookup.offline, "an answer arrived and the lookup still says there is no connection")
        assertEquals(2, lookup.consecutiveFailures)
    }

    // Nor is it kept when the next request fails on a device that says it is
    // connected again. That one is retried, and the scan is not stopped for a
    // connection the device now has.
    @Test fun `a request that fails once the device is connected again takes it back too`() = runTest {
        var connected = false
        val lookup = unreachable { !connected }
        assertNull(lookup.lookup(HASH, listOf(7)).asLegacy())
        assertTrue(lookup.offline)

        connected = true
        assertNull(lookup.lookup(HASH, listOf(8)).asLegacy())
        assertFalse(lookup.offline)
        assertTrue(logs.any { it.contains("retries exhausted") }, "$logs")
    }

    // The lookup as the Android service builds it: the device is asked through
    // an OfflineVerdict, here on the clock of the test's own delays, and says
    // each time that its network has not been found to work. A router with no
    // line out. The first three attempts are within the time a network takes
    // to be tried and go on as ever; at the fourth, seven seconds in, it has
    // lasted, and the lookup says so.
    //
    // The verdict is given the time a real clock shows at each attempt, which
    // is the back-offs before it: none, 1, 3 and 7 seconds. The clock of the
    // test would do if it showed the same, and it shows a second more for
    // every request after the first (see `the last attempt is not followed by
    // a back-off`), which puts the third attempt at the five seconds the
    // verdict waits, give or take the millisecond a request takes.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a network not found to work settles it at the last attempt of the first lookup`() = runTest {
        val onARealClock = listOf(0L, 1_000L, 3_000L, 7_000L)
        var asked = 0
        val verdict = OfflineVerdict { TimeUnit.MILLISECONDS.toNanos(onARealClock[asked - 1]) }
        val lookup = unreachable { asked++; verdict.offline(LinkState.UNVALIDATED) }

        assertEquals(LookupOutcome.Cause.OFFLINE, (lookup.lookup(HASH, listOf(7)) as LookupOutcome.Failed).cause)

        assertEquals(4, asked)
        assertTrue(currentTime in 7_000L..10_000L, "virtual time spent: $currentTime ms")
        assertTrue(lookup.offline)
    }

    // A network Android never passes and that carries every request, which is
    // what a Wi-Fi is when Android's own test is kept from getting out. Two
    // requests fail on it ten minutes apart, each made again and answered,
    // with two hundred requests answered in between. Counted from the first
    // failure of the scan to whichever comes next, the wait would be over at
    // the second, which would not be made again, and the scan would stop for
    // want of a connection. An answer is what starts the wait over.
    //
    // A request is a console's list, so each lookup here is of another
    // console, and the list it is given names none.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `answers between two failed requests keep a doubted network from being called offline`() = runTest {
        var nowMs = 0L
        val verdict = OfflineVerdict { TimeUnit.MILLISECONDS.toNanos(nowMs) }
        var asked = 0
        val lookup = asking(device = object : DeviceConnection {
            override fun offline(): Boolean { asked++; return verdict.offline(LinkState.UNVALIDATED) }
            override fun answered() = verdict.answered()
        })
        val anyConsole = list(game(4101, "Moss Kingdom", OTHER, console = null))

        replies += anyConsole.copy(cutShort = true)
        replies += anyConsole
        assertEquals(GameMetadata(gameId = 0), lookup.lookup(HASH, listOf(1)).asLegacy())
        repeat(200) { n ->
            nowMs += 3_000
            replies += anyConsole
            assertEquals(GameMetadata(gameId = 0), lookup.lookup(HASH, listOf(2 + n)).asLegacy())
        }

        replies += anyConsole.copy(cutShort = true)
        replies += anyConsole
        assertEquals(GameMetadata(gameId = 0), lookup.lookup(HASH, listOf(202)).asLegacy(),
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
        val lookup = asking(device = object : DeviceConnection {
            override fun offline() = true
            override fun answered() { throw IllegalStateException("no verdict to tell") }
        })
        replies += listed()

        assertEquals(4101, lookup.lookup(HASH, listOf(7)).asLegacy()?.gameId)

        assertEquals(1, requests.size)
        assertFalse(lookup.offline)
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

    // The decoding of a virtual id, by RAWeb's strict
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

    // Which kind it was, for each way a lookup comes to nothing. Told by what
    // happened to the request and never by what the device says when asked:
    // a status is a refusal on a device that calls itself offline too, and
    // the two things the pipeline stops a scan on go with their causes and
    // with no other. Whether it is held against the source goes with the
    // kind as well: everything is, but a whole answer that is no list of the
    // console.
    //
    // And the few words that go with the kind say nothing of where the request
    // was going. A request that brought nothing back ends in an exception, and
    // its message is the address and the port for a connection refused, and
    // the URL for one dropped before a header came. The failure used to carry
    // that message, out of the lookup and to whatever reads the outcome; it
    // carries the exception's class, and the message stays in the log.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a failure says which kind it was`() = runTest {
        val port = server.address.port
        val (auth, offline, refused) = Triple(LookupOutcome.Cause.AUTH, LookupOutcome.Cause.OFFLINE,
                                              LookupOutcome.Cause.REFUSED)
        val (transport, malformed) = LookupOutcome.Cause.TRANSPORT to LookupOutcome.Cause.MALFORMED
        val noList = "no usable list of console 7"
        class Case(val name: String, val cause: LookupOutcome.Cause, val detail: String,
                   val replies: List<Reply>, val deviceOffline: Boolean = false, val reachable: Boolean = true,
                   val counted: Int = 1)
        val cases = listOf(
            Case("401", auth, "HTTP 401", listOf(Reply(UNAUTHENTICATED, 401))),
            Case("404", refused, "HTTP 404", listOf(Reply("not found", 404))),
            Case("503 four times", refused, "HTTP 503", List(4) { Reply("unavailable", 503) }),
            Case("429 four times, on a device that says it is offline", refused, "HTTP 429",
                 List(4) { Reply("slow down", 429) }, deviceOffline = true),
            Case("Success false", refused, "the list of console 7 was refused",
                 listOf(Reply("""{"Success":false,"Error":"Invalid credentials"}"""))),
            Case("a page", malformed, noList, listOf(Reply("<html>Temporarily unavailable</html>"))),
            Case("a page that echoes the key", malformed, noList,
                 listOf(Reply("<html>$LIST_PATH?z=$USER&y=$API_KEY&i=7</html>"))),
            Case("an object that is no list", malformed, noList, listOf(Reply("""{"message":"Unauthenticated."}"""))),
            Case("nothing", malformed, noList, listOf(Reply(""))),
            Case("a list cut short in a body that arrived whole", malformed, noList,
                 listOf(Reply(listed().body.dropLast(5)))),
            Case("another console's list", malformed, noList, listOf(listed(console = 9)), counted = 0),
            Case("an array of numbers", malformed, noList, listOf(Reply("[1,2,3]")), counted = 0),
            Case("games without their hashes", malformed, noList,
                 listOf(Reply("""[{"ID":4101,"Title":"Moss Kingdom","ConsoleID":7,"NumAchievements":12}]""")),
                 counted = 0),
            Case("an empty list", malformed, "an empty list of console 7", listOf(Reply("[]")), counted = 0),
            Case("no answer", transport, "ConnectException", emptyList(), reachable = false),
            Case("no answer and no connection", offline, "ConnectException", emptyList(),
                 deviceOffline = true, reachable = false),
            Case("cut short four times", transport, "ProtocolException", List(4) { listed().copy(cutShort = true) }),
            Case("hung up on four times", transport, "IOException", List(4) { Reply("", hungUp = true) }),
            Case("cut short, and no connection", offline, "ProtocolException",
                 listOf(listed().copy(cutShort = true)), deviceOffline = true))

        val wrong = ArrayList<String>()
        for (case in cases) {
            replies.clear()
            replies += case.replies
            logs.clear()
            val to = if (case.reachable) port else deadPort()
            val asked = asking(url = "http://127.0.0.1:$to", device = DeviceConnection { case.deviceOffline })
            val outcome = asked.lookup(HASH, listOf(7))
            val said = (outcome as? LookupOutcome.Failed)?.detail
            fun note(what: String) { wrong += "${case.name}: $what" }

            if ((outcome as? LookupOutcome.Failed)?.cause != case.cause) note("$outcome, not ${case.cause}")
            if (said != case.detail) note("said \"$said\", not \"${case.detail}\"")
            if (said.isNullOrBlank()) note("said nothing")
            if (said.orEmpty().contains(API_KEY)) note("the key is in \"$said\"")
            for (where in listOf("://", "127.0.0.1", "$to")) {
                if (said.orEmpty().contains(where)) note("where it was asking, $where, is in \"$said\"")
            }
            // Left out of the outcome and not lost: the log still has what the
            // exception said, after its class.
            if ((case.cause == transport || case.cause == offline) && logs.none { it.contains("$said: ") }) {
                note("the log has not kept what the exception said: $logs")
            }
            if (logs.any { it.contains(API_KEY) }) note("the key is in the log: $logs")
            if (asked.authRejected != (case.cause == auth)) note("authRejected is ${asked.authRejected}")
            if (asked.offline != (case.cause == offline)) note("offline is ${asked.offline}")
            if (asked.consecutiveFailures != case.counted) note("counted ${asked.consecutiveFailures} times")
            if (replies.isNotEmpty()) note("${replies.size} replies were not asked for")
            // The same again for the next file of the console, and nobody asked.
            val made = asked.requests
            if (asked.lookup(OTHER, listOf(7)) != outcome) note("the next lookup was not answered the same")
            if (asked.requests != made) note("the list was asked for again")
            if (asked.consecutiveFailures != 2 * case.counted) note("counted ${asked.consecutiveFailures} after two")
            if (kept().isNotEmpty()) note("${kept()} was written")
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
        val recording = BridgeLog.current
        BridgeLog.current = object : BridgeLog by recording {
            override fun w(tag: String, msg: String, t: Throwable?) {
                throw IllegalStateException("asked $here$LIST_PATH with $API_KEY")
            }
        }
        replies += Reply("<html>not an answer</html>")

        assertEquals<LookupOutcome>(
            LookupOutcome.Failed(LookupOutcome.Cause.TRANSPORT, "IllegalStateException"),
            lookup.lookup(HASH, listOf(7)))
        assertEquals(1, lookup.consecutiveFailures)
        assertFalse(lookup.authRejected)
        assertFalse(lookup.offline)
        assertTrue(logs.any { it.contains("lookup failed for hash $HASH: IllegalStateException: asked $here") },
                   "$logs")
        assertTrue(logs.none { it.contains(API_KEY) }, "the API key reached the log:\n$logs")

        // Nothing was kept of it, a list or a failure: the lock was let go,
        // and the next lookup asks.
        BridgeLog.current = recording
        replies += listed()
        assertEquals(4101, lookup.lookup(HASH, listOf(7)).asLegacy()?.gameId)
        assertEquals(2, requests.size)
    }

    /**
     * The coroutine returning is not the proof: suspendCancellableCoroutine hands
     * a cancelled caller back at once whatever happens to the socket, and with
     * the call left running OkHttp would go on reading until the 30 s timeout.
     * So the server streams a body that never ends, a byte every 20 ms, and
     * watches for the moment a write fails because the client has gone. Only
     * call.cancel() closes that socket while the body is held open.
     *
     * The lookup was cancelled while it held the lock a list is loaded
     * under. The lock is let go and nothing is kept, so the same object
     * asks again and is answered.
     */
    @Test fun `cancellation stops an in-flight body read without reporting failure`() {
        val bodyStarted = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val clientGone = CountDownLatch(1)
        val returnedNormally = AtomicBoolean()
        val root = server.createContext("/never-ends") { exchange ->
            exchange.sendResponseHeaders(200, 0)   // chunked: no length, no end
            try {
                exchange.responseBody.write('['.code)
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
        val slow = asking(url = "$here${root.path}")
        runBlocking {
            val job = launch(Dispatchers.IO) {
                slow.lookup(HASH, listOf(7))
                returnedNormally.set(true)
            }
            try {
                assertTrue(bodyStarted.await(3, TimeUnit.SECONDS), "request did not reach the local server")
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(clientGone.await(2, TimeUnit.SECONDS),
                           "the body read went on after the cancel: the socket was never closed")
                assertFalse(returnedNormally.get(), "lookup must propagate cancellation to its caller")
                assertEquals(0, slow.consecutiveFailures)
                assertEquals(1, slow.requests)
                assertTrue(logs.isEmpty(), "cancellation must not produce an error log: $logs")
            } finally {
                releaseBody.countDown()
                job.cancelAndJoin()
            }
            // Cancelled under the lock, and the lock is free: the next
            // lookup of that object is not left waiting for it. Its request
            // is answered at once with what is no list, since the server has
            // nothing else under that path.
            server.removeContext(root)
            assertIs<LookupOutcome.Failed>(withTimeout(5000) { slow.lookup(HASH, listOf(7)) })
            assertEquals(2, slow.requests)
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
            val job = launch(Dispatchers.IO) { lookup.lookup(HASH, listOf(7)) }
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
        const val OTHER = "11111111111111111111111111111111"
        const val THIRD = "abcdefabcdefabcdefabcdefabcdefab"
        const val USER = "test-private-user"
        const val API_KEY = "test-private-api-key"
        const val LIST_PATH = "/API/API_GetGameList.php"
        const val DAY = 24L * 60 * 60
        /** RAWeb's body for an AuthenticationException on the legacy API (app/Exceptions/Handler.php). */
        const val UNAUTHENTICATED = """{"message":"Unauthenticated.","errors":[{"status":"401","code":"unauthorized","title":"Unauthenticated."}]}"""
    }
}
