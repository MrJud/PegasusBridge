package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.StderrLog
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `steam-account` source as a theme meets it: the state each answer of
 * Steam's becomes, what every state costs in requests, and what is on disk
 * afterwards.
 *
 * Steam is a MockWebServer that counts what it is asked, per call. The clock
 * is the test's, so a file goes old and the budget of `progress` runs out
 * without anything waiting. The key is a sentinel, and the last test looks
 * for it in everything a request leaves behind.
 */
class SteamAccountOpsTest {

    private lateinit var server: MockWebServer

    /** A folder of the test's own, with the data root one level inside it: a path that climbs lands here. */
    private lateinit var sandbox: File
    private lateinit var root: File
    private lateinit var cacheDir: File
    private var originalBase = ""
    private var originalClock: () -> Long = { 0L }

    /** The clock, in milliseconds. Nothing moves it but [advance] and the server below. */
    private val now = AtomicLong(1_760_000_000_000L)

    private val ownedCalls = AtomicInteger()
    private val playerCalls = AtomicInteger()
    private val schemaCalls = AtomicInteger()

    /** The `l=` of every schema call, in the order they came. */
    private val schemaLanguages = Collections.synchronizedList(mutableListOf<String>())

    /** What the library call answers. */
    @Volatile private var owned: () -> MockResponse = { json(OWNED) }

    /** What the player call answers for a game; a game not here gets [PLAYER]. */
    private val player = ConcurrentHashMap<Int, () -> MockResponse>()

    /** What the schema call answers for a game, by language; a game not here gets both achievements. */
    private val schema = ConcurrentHashMap<Int, (String) -> MockResponse>()

    /** How far one player call moves the clock: what makes a request slow without a sleep. */
    @Volatile private var millisPerPlayerCall = 0L

    /** Real time a player call takes, for the one test where callers have to overlap. */
    @Volatile private var playerSleepMillis = 0L

    /** Every line the code under test logged, with any stack trace it gave. */
    private val log = Collections.synchronizedList(mutableListOf<String>())

    @BeforeTest fun setUp() {
        BridgeLog.current = object : BridgeLog {
            override fun d(tag: String, msg: String) { log += "$tag: $msg" }
            override fun i(tag: String, msg: String) { log += "$tag: $msg" }
            override fun w(tag: String, msg: String, t: Throwable?) { log += "$tag: $msg ${t?.stackTraceToString().orEmpty()}" }
            override fun e(tag: String, msg: String, t: Throwable?) { log += "$tag: $msg ${t?.stackTraceToString().orEmpty()}" }
        }
        sandbox = Files.createTempDirectory("steam-data").toFile()
        root = File(sandbox, "data")
        cacheDir = File(root, "cache/steam")

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(404)
                val path = url.encodedPath
                val appId = url.queryParameter("appid")?.toIntOrNull() ?: 0
                return when {
                    path.startsWith("/IPlayerService/GetOwnedGames/") -> {
                        ownedCalls.incrementAndGet()
                        owned()
                    }
                    path.startsWith("/ISteamUserStats/GetPlayerAchievements/") -> {
                        playerCalls.incrementAndGet()
                        now.addAndGet(millisPerPlayerCall)
                        if (playerSleepMillis > 0) Thread.sleep(playerSleepMillis)
                        (player[appId] ?: { json(PLAYER) })()
                    }
                    path.startsWith("/ISteamUserStats/GetSchemaForGame/") -> {
                        schemaCalls.incrementAndGet()
                        val language = url.queryParameter("l").orEmpty()
                        schemaLanguages += language
                        (schema[appId] ?: { l -> json(schemaBody(appId, l, "ACH_ONE", "ACH_TWO")) })(language)
                    }
                    path.startsWith("/ISteamUser/ResolveVanityURL/") ->
                        json("""{"response":{"success":1,"steamid":"$STEAM_ID"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        originalBase = SteamAccountClient.BASE
        SteamAccountClient.BASE = server.url("/").toString().trimEnd('/')
        originalClock = SteamAccountOps.clock
        SteamAccountOps.clock = { now.get() }
    }

    @AfterTest fun tearDown() {
        SteamAccountOps.clock = originalClock
        SteamAccountClient.BASE = originalBase
        server.shutdown()
        sandbox.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    // ── What the tests are written with ─────────────────────────────────────

    private val creds = SteamAccountClient.Credentials(KEY, STEAM_ID)

    private fun ask(
        op: String,
        vararg params: Pair<String, String>,
        credentials: SteamAccountClient.Credentials? = creds,
        dir: File? = cacheDir
    ): JSONObject = SteamAccountOps.run(op, mapOf(*params), credentials, dir)

    private fun advance(seconds: Long) { now.addAndGet(seconds * 1000L) }

    private fun nowSeconds() = now.get() / 1000L

    /** Runs [block] with Steam out of reach: a port nothing listens on. */
    private fun <T> offline(block: () -> T): T {
        val base = SteamAccountClient.BASE
        SteamAccountClient.BASE = "http://127.0.0.1:1"
        try {
            return block()
        } finally {
            SteamAccountClient.BASE = base
        }
    }

    /** Every file the test's folder holds, as a path from the data root: one outside it begins with `..`. */
    private fun files(): List<String> =
        sandbox.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    private fun schemaFile(appId: Int, language: String) = "cache/steam/schema/$appId.$language.json"
    private fun progressFile(appId: Int) = "cache/steam/$STEAM_ID/progress/$appId.json"
    private val libraryFile = "cache/steam/$STEAM_ID/library.json"

    private fun JSONObject.items(): List<JSONObject> = getJSONArray("items").objects()
    private fun JSONObject.list(): List<JSONObject> = getJSONArray("achievements").objects()
    private fun JSONObject.games(): List<JSONObject> = getJSONArray("games").objects()
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    /** What Steam answers with instead of an answer, by the state it is to become. */
    private val refusals: List<Pair<String, () -> MockResponse>> = listOf(
        "private" to { json(NOT_PUBLIC, 403) },
        "key_refused" to { html(FORBIDDEN, 403) },
        "key_refused" to { html(UNAUTHORIZED, 401) },
        "key_refused" to { html(NO_KEY, 400) },
        "rate_limited" to { html("<html><body>Too Many Requests</body></html>", 429) },
        "upstream_error" to { html("<html><body>Internal Server Error</body></html>", 500) },
        "upstream_error" to { html("<html><body>Maintenance</body></html>", 200) },
        "upstream_error" to { json("""{"playerstats":{"error":"$MARKER went wrong","success":false}}""", 403) }
    )

    // ── Not set up ──────────────────────────────────────────────────────────

    @Test fun `a bridge with no key or no whole steam id says so and asks steam nothing`() {
        val incomplete = listOf(
            null,
            SteamAccountClient.Credentials("", STEAM_ID),
            SteamAccountClient.Credentials("   ", STEAM_ID),
            SteamAccountClient.Credentials(KEY, ""),
            SteamAccountClient.Credentials(KEY, "7656119800000000"),      // sixteen digits
            SteamAccountClient.Credentials(KEY, "765611980000000000"),    // eighteen
            SteamAccountClient.Credentials(KEY, "7656119800000000x"),
            SteamAccountClient.Credentials(KEY, "../../../../../etc"),
            // Seventeen digits of another script: `\d` on Android would take them.
            SteamAccountClient.Credentials(KEY, "\u0667".repeat(17))
        )
        for (c in incomplete) {
            val library = ask("library", credentials = c)
            assertEquals("not_configured", library.getString("state"), "library with $c")
            assertEquals(SENTENCES.getValue("not_configured"), library.getString("message"))
            assertEquals("steam", library.getString("namespace"))
            assertEquals(0, library.getJSONArray("games").length())

            val achievements = ask("achievements", "appId" to "440", credentials = c)
            assertEquals("not_configured", achievements.getString("state"), "achievements with $c")
            assertEquals(0, achievements.getJSONArray("achievements").length())

            val progress = ask("progress", "appIds" to "440,570", credentials = c)
            assertEquals("not_configured", progress.getString("state"), "progress with $c")
            assertEquals(listOf("skipped", "skipped"), progress.items().map { it.getString("state") })
        }
        assertEquals(0, server.requestCount, "a Bridge that is not set up asked Steam")
        assertEquals(emptyList(), files())
    }

    // ── library ─────────────────────────────────────────────────────────────

    @Test fun `the library lists what steam owns, last played first and then most played`() {
        val r = ask("library")

        assertEquals("ok", r.getString("state"))
        assertEquals(SENTENCES.getValue("ok"), r.getString("message"))
        assertEquals("steam", r.getString("namespace"))
        assertEquals(STEAM_ID, r.getString("steamId"))
        assertFalse(r.getBoolean("cached"))
        assertFalse(r.getBoolean("stale"))
        assertEquals(nowSeconds(), r.getLong("fetchedAt"))

        val games = r.games()
        assertEquals(listOf(620, 440, 400, 570), games.map { it.getInt("appId") })
        val tf2 = games.single { it.getInt("appId") == 440 }
        assertEquals("Team Fortress 2", tf2.getString("name"))
        assertEquals(1200, tf2.getInt("playtimeMinutes"))
        assertEquals(30, tf2.getInt("playtimeLast2WeeksMinutes"))
        assertEquals(1750000000L, tf2.getLong("lastPlayedAt"))
        assertTrue(tf2.getString("iconUrl").endsWith("/apps/440/e3f595a92552da3d664ad00277fad2107345f743.jpg"))
        assertEquals("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/440/header.jpg",
                     tf2.getString("headerUrl"))
        assertEquals("steam://rungameid/440", tf2.getString("launchUri"))
        // No progress file, no progress: and nobody was asked for one.
        assertFalse(tf2.has("unlocked") || tf2.has("total") || tf2.has("progressAt"), "got $tf2")
        assertEquals(0, playerCalls.get() + schemaCalls.get())
    }

    @Test fun `the library is asked for once, answered from disk for ten minutes, then asked for again`() {
        val cold = ask("library")
        assertEquals(1, ownedCalls.get())
        assertEquals(listOf(libraryFile), files())

        advance(10 * 60)
        val warm = ask("library")
        assertEquals(1, ownedCalls.get(), "a library ten minutes old was asked for again")
        assertTrue(warm.getBoolean("cached"))
        assertFalse(warm.getBoolean("stale"))
        assertEquals(cold.getLong("fetchedAt"), warm.getLong("fetchedAt"))
        assertEquals(cold.getJSONArray("games").toString(), warm.getJSONArray("games").toString())

        advance(1)
        val expired = ask("library")
        assertEquals(2, ownedCalls.get(), "a library older than ten minutes was answered from disk")
        assertFalse(expired.getBoolean("cached"))
        assertEquals(nowSeconds(), expired.getLong("fetchedAt"))
    }

    @Test fun `a library steam does not give is the state it was refused with, and nothing is kept`() {
        for ((state, answer) in refusals) {
            owned = answer
            val r = ask("library")
            assertEquals(state, r.getString("state"))
            assertEquals(SENTENCES.getValue(if (state == "private") "private:library" else state), r.getString("message"))
            assertEquals(0, r.getJSONArray("games").length())
            assertFalse(r.getBoolean("cached") || r.getBoolean("stale"))
            assertEquals(emptyList(), files(), "$state was written to disk")
        }
        val gone = offline { ask("library") }
        assertEquals("offline", gone.getString("state"))
        assertEquals(SENTENCES.getValue("offline"), gone.getString("message"))
        assertEquals(emptyList(), files())

        // What Steam answers a private profile and a SteamID nobody has with.
        owned = { json("""{"response":{}}""") }
        val private = ask("library")
        assertEquals("private", private.getString("state"))
        assertTrue(private.getString("message").contains("not public"), private.getString("message"))
        assertTrue(private.getString("message").contains("no account has this SteamID"), private.getString("message"))
        assertEquals(emptyList(), files(), "a private library was written to disk")

        // None of it was remembered: the next answer is asked for and taken.
        val asked = ownedCalls.get()
        owned = { json(OWNED) }
        assertEquals("ok", ask("library").getString("state"))
        assertEquals(asked + 1, ownedCalls.get())
    }

    @Test fun `an old library is served when steam cannot be asked, and not when the account is refused`() {
        val first = ask("library")
        val kept = File(root, libraryFile).readText()

        for (state in listOf("offline", "rate_limited", "upstream_error", "upstream_error")) {
            advance(11 * 60)
            val r = when (state) {
                "offline" -> offline { ask("library") }
                "rate_limited" -> { owned = { html("slow down", 429) }; ask("library") }
                else -> { owned = { html("oops", 503) }; ask("library") }
            }
            assertEquals("ok", r.getString("state"), "after $state")
            assertTrue(r.getBoolean("stale"), "an old library served after $state is not marked stale")
            assertTrue(r.getBoolean("cached"))
            assertEquals(first.getLong("fetchedAt"), r.getLong("fetchedAt"))
            assertEquals(4, r.getJSONArray("games").length())
            assertEquals(kept, File(root, libraryFile).readText(), "$state rewrote the kept library")
        }

        for ((state, answer) in refusals.filter { it.first == "private" || it.first == "key_refused" }) {
            advance(11 * 60)
            owned = answer
            val r = ask("library")
            assertEquals(state, r.getString("state"))
            assertEquals(0, r.getJSONArray("games").length(), "an old library was served after $state")
            assertFalse(r.getBoolean("stale") || r.getBoolean("cached"))
            assertEquals(kept, File(root, libraryFile).readText(), "$state rewrote the kept library")
        }
    }

    // ── achievements ────────────────────────────────────────────────────────

    @Test fun `achievements are the schema with the account's unlock list laid over it`() {
        val r = ask("achievements", "appId" to "440")

        assertEquals("ok", r.getString("state"))
        assertEquals("steam", r.getString("namespace"))
        assertEquals(440, r.getInt("appId"))
        assertEquals("Team Fortress 2", r.getString("gameName"))
        assertEquals(1, r.getInt("unlocked"))
        assertEquals(2, r.getInt("total"))
        assertEquals(0.5, r.getDouble("progress"))
        assertFalse(r.getBoolean("cached"))
        assertFalse(r.getBoolean("stale"))

        val (one, two) = r.list()
        assertEquals("ACH_ONE", one.getString("id"))
        assertEquals("ACH_ONE in english", one.getString("title"))
        assertEquals("How ACH_ONE is earned, in english", one.getString("description"))
        assertTrue(one.getBoolean("unlocked"))
        assertEquals(1600000000L, one.getLong("unlockedAt"))
        assertEquals("https://cdn.example/440/ACH_ONE.jpg", one.getString("iconUrl"))
        assertEquals("https://cdn.example/440/ACH_ONE_gray.jpg", one.getString("iconLockedUrl"))
        assertFalse(one.getBoolean("hidden"))

        assertEquals("ACH_TWO", two.getString("id"))
        assertFalse(two.getBoolean("unlocked"))
        assertEquals(0L, two.getLong("unlockedAt"))
        assertTrue(two.getBoolean("hidden"))
        assertEquals("https://cdn.example/440/ACH_TWO.jpg", two.getString("iconUrl"))
        assertEquals("https://cdn.example/440/ACH_TWO_gray.jpg", two.getString("iconLockedUrl"))

        assertEquals(listOf(progressFile(440), schemaFile(440, "english")), files())
    }

    @Test fun `a game costs two calls cold, none warm, one after ten minutes and two after thirty days`() {
        ask("achievements", "appId" to "440")
        assertEquals(1 to 1, playerCalls.get() to schemaCalls.get())

        advance(10 * 60)
        val warm = ask("achievements", "appId" to "440")
        assertEquals(1 to 1, playerCalls.get() to schemaCalls.get(), "a warm game asked Steam")
        assertTrue(warm.getBoolean("cached"))
        assertEquals(2, warm.getInt("total"))
        assertEquals("ACH_ONE in english", warm.list().first().getString("title"))

        advance(1)
        val later = ask("achievements", "appId" to "440")
        assertEquals(2 to 1, playerCalls.get() to schemaCalls.get(),
                     "after ten minutes the unlock list is asked for again and the schema is not")
        assertFalse(later.getBoolean("cached"))

        advance(30L * 24 * 60 * 60 - 601)
        ask("achievements", "appId" to "440")
        assertEquals(3 to 1, playerCalls.get() to schemaCalls.get(), "a schema thirty days old was asked for again")

        advance(11 * 60)
        ask("achievements", "appId" to "440")
        assertEquals(4 to 2, playerCalls.get() to schemaCalls.get(), "a schema older than thirty days was trusted")
    }

    @Test fun `a game steam does not answer about is the state it was refused with, and nothing is kept`() {
        for ((state, answer) in refusals) {
            player[440] = answer
            val r = ask("achievements", "appId" to "440")
            assertEquals(state, r.getString("state"))
            assertEquals(SENTENCES.getValue(state), r.getString("message"))
            assertEquals(440, r.getInt("appId"))
            assertEquals(0, r.getJSONArray("achievements").length())
            assertEquals(0, r.getInt("total"))
            assertFalse(r.getBoolean("cached") || r.getBoolean("stale"))
            assertEquals(emptyList(), files(), "$state was written to disk")
        }
        val gone = offline { ask("achievements", "appId" to "440") }
        assertEquals("offline", gone.getString("state"))
        assertEquals(emptyList(), files())
        assertEquals(0, schemaCalls.get(), "the schema was asked for after the player call was refused")

        // None of it was remembered, and none of it became "no achievements".
        val asked = playerCalls.get()
        player.remove(440)
        val r = ask("achievements", "appId" to "440")
        assertEquals("ok", r.getString("state"))
        assertEquals(asked + 1, playerCalls.get())
    }

    @Test fun `an old unlock list is served when steam cannot be asked, and not when the account is refused`() {
        val first = ask("achievements", "appId" to "440")
        val kept = File(root, progressFile(440)).readText()

        fun <T> during(state: String, block: () -> T): T = when (state) {
            "offline" -> offline(block)
            "rate_limited" -> { player[440] = { html("slow down", 429) }; block() }
            else -> { player[440] = { html("oops", 502) }; block() }
        }
        for (state in listOf("offline", "rate_limited", "upstream_error")) {
            advance(11 * 60)
            val r = during(state) { ask("achievements", "appId" to "440") }
            assertEquals("ok", r.getString("state"), "after $state")
            assertTrue(r.getBoolean("stale"), "an old list served after $state is not marked stale")
            assertTrue(r.getBoolean("cached"))
            assertEquals(first.getLong("fetchedAt"), r.getLong("fetchedAt"))
            assertEquals(first.getJSONArray("achievements").toString(), r.getJSONArray("achievements").toString())

            val progress = during(state) { ask("progress", "appIds" to "440") }
            val item = progress.items().single()
            assertEquals("ok", item.getString("state"))
            assertEquals(1 to 2, item.getInt("unlocked") to item.getInt("total"))
            assertTrue(progress.getBoolean("stale"))
            assertEquals(kept, File(root, progressFile(440)).readText(), "$state rewrote the kept list")
        }
        assertEquals(1, schemaCalls.get(), "a schema was asked for while Steam could not be")

        for ((state, answer) in refusals.filter { it.first == "private" || it.first == "key_refused" }) {
            advance(11 * 60)
            player[440] = answer
            val r = ask("achievements", "appId" to "440")
            assertEquals(state, r.getString("state"))
            assertEquals(0, r.getJSONArray("achievements").length(), "an old list was served after $state")
            assertFalse(r.getBoolean("stale") || r.getBoolean("cached"))
            assertEquals(kept, File(root, progressFile(440)).readText(), "$state rewrote the kept list")
        }
    }

    // ── No stats: no achievements, or never played ──────────────────────────

    @Test fun `no stats and a schema that lists nothing is a game with no achievements, kept for a week`() {
        player[900] = { json(NO_STATS, 400) }
        schema[900] = { json("""{"game":{}}""") }

        val r = ask("achievements", "appId" to "900")
        assertEquals("no_achievements", r.getString("state"))
        assertEquals(SENTENCES.getValue("no_achievements"), r.getString("message"))
        assertEquals(0, r.getInt("total"))
        assertEquals(0.0, r.getDouble("progress"))
        assertEquals(0, r.getJSONArray("achievements").length())
        assertEquals(1 to 1, playerCalls.get() to schemaCalls.get())
        assertEquals(listOf(progressFile(900), schemaFile(900, "english")), files())

        val item = ask("progress", "appIds" to "900").items().single()
        assertEquals("no_achievements", item.getString("state"))
        assertTrue(item.getBoolean("cached"))
        assertEquals(1 to 1, playerCalls.get() to schemaCalls.get())

        advance(11 * 60)
        assertEquals("no_achievements", ask("achievements", "appId" to "900").getString("state"))
        assertEquals(2 to 1, playerCalls.get() to schemaCalls.get(), "an empty schema of today was asked for again")

        advance(7L * 24 * 60 * 60)
        assertEquals("no_achievements", ask("achievements", "appId" to "900").getString("state"))
        assertEquals(3 to 2, playerCalls.get() to schemaCalls.get(), "an empty schema was trusted for more than a week")
    }

    // Never started, or not owned: Steam says "no stats" of this one too.
    @Test fun `no stats and a schema that lists some is a game with none of them unlocked`() {
        player[901] = { json(NO_STATS, 400) }

        val r = ask("achievements", "appId" to "901")
        assertEquals("ok", r.getString("state"))
        assertEquals(0 to 2, r.getInt("unlocked") to r.getInt("total"))
        assertEquals(0.0, r.getDouble("progress"))
        assertEquals("Game 901", r.getString("gameName"))
        assertEquals(listOf("ACH_ONE in english", "ACH_TWO in english"), r.list().map { it.getString("title") })
        assertTrue(r.list().none { it.getBoolean("unlocked") })
        assertEquals("https://cdn.example/901/ACH_ONE_gray.jpg", r.list().first().getString("iconLockedUrl"))
        assertEquals(1 to 1, playerCalls.get() to schemaCalls.get())

        val item = ask("progress", "appIds" to "901").items().single()
        assertEquals("ok", item.getString("state"))
        assertEquals(0 to 2, item.getInt("unlocked") to item.getInt("total"))

        val warm = ask("achievements", "appId" to "901")
        assertTrue(warm.getBoolean("cached"))
        assertEquals(2, warm.getInt("total"))
        assertEquals(1 to 1, playerCalls.get() to schemaCalls.get())
    }

    @Test fun `an answer that lists no achievement is read as no stats is`() {
        player[902] = { json("""{"playerstats":{"steamID":"$STEAM_ID","gameName":"Stats Only","success":true}}""") }
        schema[902] = { json("""{"game":{"gameName":"Stats Only","availableGameStats":{"stats":[{"name":"s"}]}}}""") }
        val none = ask("achievements", "appId" to "902")
        assertEquals("no_achievements", none.getString("state"))
        assertEquals("Stats Only", none.getString("gameName"))

        player[903] = { json("""{"playerstats":{"steamID":"$STEAM_ID","gameName":"Unplayed","success":true}}""") }
        val some = ask("achievements", "appId" to "903")
        assertEquals("ok", some.getString("state"))
        assertEquals(0 to 2, some.getInt("unlocked") to some.getInt("total"))
    }

    @Test fun `no stats and no schema to be had is not a game without achievements`() {
        player[904] = { json(NO_STATS, 400) }
        schema[904] = { html("oops", 500) }
        val r = ask("achievements", "appId" to "904")
        assertEquals("upstream_error", r.getString("state"))
        assertEquals(emptyList(), files(), "an undecided game was written to disk")

        val item = ask("progress", "appIds" to "904").items().single()
        assertEquals("upstream_error", item.getString("state"))
        assertEquals(emptyList(), files())

        val gone = offline { ask("achievements", "appId" to "904") }
        assertEquals("offline", gone.getString("state"))
    }

    // The page Steam sends for a wrong key is also how it refuses a schema for
    // reasons of the game's own. The player call has just been answered with
    // the same key, so it is not the key.
    @Test fun `a schema refused with the page for a wrong key is not held against the key`() {
        player[905] = { json(NO_STATS, 400) }
        schema[905] = { html(FORBIDDEN, 403) }

        val r = ask("achievements", "appId" to "905")
        assertEquals("upstream_error", r.getString("state"))

        val progress = ask("progress", "appIds" to "905,440")
        assertEquals("ok", progress.getString("state"), "one game's schema stopped the whole request")
        assertEquals(listOf("upstream_error", "ok"), progress.items().map { it.getString("state") })
        assertEquals(emptyList(), files().filter { it.contains("905") })
    }

    @Test fun `an empty schema beside a list of achievements is not kept and takes no name away`() {
        schema[440] = { json("""{"game":{}}""") }

        val r = ask("achievements", "appId" to "440")
        assertEquals("ok", r.getString("state"))
        assertEquals(1 to 2, r.getInt("unlocked") to r.getInt("total"))
        assertEquals(listOf("ACH_ONE", "ACH_TWO"), r.list().map { it.getString("title") })
        assertEquals(listOf(progressFile(440)), files(), "an empty schema was kept on its own word")

        // Asked for again with the next list, and believed when it has names.
        schema.remove(440)
        advance(11 * 60)
        val named = ask("achievements", "appId" to "440")
        assertEquals(2, schemaCalls.get())
        assertEquals("ACH_ONE in english", named.list().first().getString("title"))

        // Empty again, a month on: the names on disk stay.
        schema[440] = { json("""{"game":{}}""") }
        advance(31L * 24 * 60 * 60)
        val still = ask("achievements", "appId" to "440")
        assertEquals(3, schemaCalls.get())
        assertEquals("ACH_ONE in english", still.list().first().getString("title"))
        assertTrue(still.getBoolean("stale"))
    }

    @Test fun `a schema that lacks an achievement of the account's list is asked for again once`() {
        schema[440] = { l -> json(schemaBody(440, l, "ACH_ONE")) }
        val before = ask("achievements", "appId" to "440")
        assertEquals(listOf("ACH_ONE in english", "ACH_TWO"), before.list().map { it.getString("title") })
        assertEquals(1 to 1, playerCalls.get() to schemaCalls.get(), "a schema just fetched was fetched twice")

        // The game was given a second achievement; the schema on disk is from before.
        schema.remove(440)
        advance(11 * 60)
        val after = ask("achievements", "appId" to "440")
        assertEquals(2 to 2, playerCalls.get() to schemaCalls.get())
        assertEquals(listOf("ACH_ONE in english", "ACH_TWO in english"), after.list().map { it.getString("title") })

        ask("achievements", "appId" to "440")
        assertEquals(2 to 2, playerCalls.get() to schemaCalls.get())
    }

    @Test fun `a schema that goes on lacking one is asked for once with each new list and not in between`() {
        schema[440] = { l -> json(schemaBody(440, l, "ACH_ONE")) }
        ask("achievements", "appId" to "440")
        assertEquals(1 to 1, playerCalls.get() to schemaCalls.get())

        advance(11 * 60)
        ask("achievements", "appId" to "440")
        assertEquals(2 to 2, playerCalls.get() to schemaCalls.get())

        repeat(3) { advance(60); ask("achievements", "appId" to "440") }
        assertEquals(2 to 2, playerCalls.get() to schemaCalls.get(), "a warm game asked for its schema again")

        advance(11 * 60)
        val r = ask("achievements", "appId" to "440")
        assertEquals(3 to 3, playerCalls.get() to schemaCalls.get())
        assertEquals(2, r.getInt("total"))
    }

    // ── refresh ─────────────────────────────────────────────────────────────

    @Test fun `refresh asks steam again, but not about a file younger than a minute`() {
        ask("achievements", "appId" to "440")
        ask("library")
        assertEquals(Triple(1, 1, 1), Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get()))

        advance(59)
        val young = ask("achievements", "appId" to "440", "refresh" to "1")
        ask("library", "refresh" to "1")
        ask("progress", "appIds" to "440", "refresh" to "1")
        assertEquals(Triple(1, 1, 1), Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get()),
                     "a refresh reached files younger than a minute")
        assertTrue(young.getBoolean("cached"))

        advance(1)
        val fresh = ask("achievements", "appId" to "440", "refresh" to "1")
        val library = ask("library", "refresh" to "true")
        assertEquals(Triple(2, 2, 2), Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get()),
                     "a refresh did not reach the progress, the library and the schema")
        assertFalse(fresh.getBoolean("cached") || library.getBoolean("cached"))

        // And the answer it fetched is itself younger than a minute now.
        ask("achievements", "appId" to "440", "refresh" to "1")
        ask("library", "refresh" to "1")
        assertEquals(Triple(2, 2, 2), Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get()))

        // Without the word, a file a minute old is simply fresh.
        advance(5 * 60)
        ask("achievements", "appId" to "440", "refresh" to "0")
        ask("library", "refresh" to "no")
        assertEquals(Triple(2, 2, 2), Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get()))

        advance(1)
        val item = ask("progress", "appIds" to "440", "refresh" to "1").items().single()
        assertEquals(3, playerCalls.get(), "a refresh of progress did not reach the file")
        assertFalse(item.getBoolean("cached"))
    }

    @Test fun `a refresh that steam does not answer serves the file it was to replace, marked stale`() {
        ask("achievements", "appId" to "440")
        advance(2 * 60)
        val r = offline { ask("achievements", "appId" to "440", "refresh" to "1") }
        assertEquals("ok", r.getString("state"))
        assertTrue(r.getBoolean("stale"))
        assertEquals(2, r.getInt("total"))
    }

    // ── progress ────────────────────────────────────────────────────────────

    @Test fun `progress counts each game with one call and the library then shows the counts for nothing`() {
        player[620] = { json(playerBody("Portal 2", "A" to 1, "B" to 1, "C" to 0)) }

        val r = ask("progress", "appIds" to "440, 620")
        assertEquals("ok", r.getString("state"))
        assertEquals("steam", r.getString("namespace"))
        assertFalse(r.getBoolean("cached"))
        val (tf2, portal) = r.items()
        assertEquals(setOf("appId", "state", "unlocked", "total", "cached", "fetchedAt"), tf2.keySet())
        assertEquals(440, tf2.getInt("appId"))
        assertEquals("ok", tf2.getString("state"))
        assertEquals(1 to 2, tf2.getInt("unlocked") to tf2.getInt("total"))
        assertFalse(tf2.getBoolean("cached"))
        assertEquals(nowSeconds(), tf2.getLong("fetchedAt"))
        assertEquals(2 to 3, portal.getInt("unlocked") to portal.getInt("total"))
        assertEquals(2 to 0, playerCalls.get() to schemaCalls.get(), "a game with a list needs no schema to be counted")

        val warm = ask("progress", "appIds" to "620,440")
        assertTrue(warm.getBoolean("cached"))
        assertTrue(warm.items().all { it.getBoolean("cached") })
        assertEquals(listOf(620, 440), warm.items().map { it.getInt("appId") })
        assertEquals(2, playerCalls.get())

        advance(5 * 60)
        val games = ask("library").games()
        val counted = games.single { it.getInt("appId") == 620 }
        assertEquals(2 to 3, counted.getInt("unlocked") to counted.getInt("total"))
        assertEquals(nowSeconds() - 5 * 60, counted.getLong("progressAt"))
        assertTrue(games.single { it.getInt("appId") == 440 }.has("unlocked"))
        assertTrue(games.filter { it.getInt("appId") in setOf(400, 570) }.none { it.has("unlocked") || it.has("total") })
        assertEquals(Triple(1, 2, 0), Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get()),
                     "the library's counts cost a call")

        // Still there, with its date, once the file is too old to answer `progress` from.
        advance(60 * 60)
        val old = ask("library").games().single { it.getInt("appId") == 620 }
        assertEquals(2 to 3, old.getInt("unlocked") to old.getInt("total"))
        assertEquals(2, playerCalls.get())
    }

    @Test fun `progress takes twenty games and refuses a request for more, or for what is not a game`() {
        val twenty = (1..20).joinToString(",") { (1000 + it).toString() }
        assertEquals(20, ask("progress", "appIds" to twenty).items().size)
        assertEquals(20, playerCalls.get())
        // The same game twice is one game.
        assertEquals(1, ask("progress", "appIds" to "1001,1001,1001,").items().size)

        val before = server.requestCount
        val kept = files()
        val bad = listOf("$twenty,1021", "", " , ", "abc", "440,../x", "440;570", "0", "-5", "440,4x4",
                         "99999999999", "1.5", "../../../etc/passwd")
        for (appIds in bad) {
            val e = assertFailsWith<IllegalArgumentException>("appIds=$appIds") { ask("progress", "appIds" to appIds) }
            assertFalse(e.message.orEmpty().contains(".."), "the message quotes the parameter: ${e.message}")
        }
        assertFailsWith<IllegalArgumentException> { ask("progress") }
        assertEquals(before, server.requestCount, "a refused request asked Steam")
        assertEquals(kept, files(), "a refused request wrote a file")
    }

    @Test fun `progress stops starting calls after eight seconds and marks the rest skipped`() {
        // On disk already, and so answered whatever the time.
        ask("progress", "appIds" to "1005")
        assertEquals(1, playerCalls.get())

        millisPerPlayerCall = 3_000
        val r = ask("progress", "appIds" to "1001,1002,1003,1004,1005,1006")

        // Calls begin at 0, 3 and 6 seconds; by the fourth game it is 9.
        assertEquals(listOf("ok", "ok", "ok", "skipped", "ok", "skipped"), r.items().map { it.getString("state") })
        assertEquals(4, playerCalls.get(), "a call was started after the eight seconds")
        assertEquals("ok", r.getString("state"), "running out of time is not a state of the account")
        val skipped = r.items()[3]
        assertEquals(0 to 0, skipped.getInt("unlocked") to skipped.getInt("total"))
        assertFalse(skipped.getBoolean("cached"))
        assertTrue(r.items()[4].getBoolean("cached"))
        assertFalse(File(root, progressFile(1004)).exists())

        // The next request finds three on disk and has time for the rest.
        millisPerPlayerCall = 0
        advance(60)
        val next = ask("progress", "appIds" to "1001,1002,1003,1004,1005,1006")
        assertTrue(next.items().all { it.getString("state") == "ok" })
        assertEquals(6, playerCalls.get())
    }

    @Test fun `after a state the whole account is in, progress asks about no other game`() {
        for (state in listOf("private", "key_refused", "rate_limited")) {
            val calls = playerCalls.get()
            player[440] = refusals.first { it.first == state }.second
            val r = ask("progress", "appIds" to "440,570,620")
            assertEquals(state, r.getString("state"))
            assertEquals(SENTENCES.getValue(state), r.getString("message"))
            assertEquals(listOf(state, "skipped", "skipped"), r.items().map { it.getString("state") })
            assertEquals(calls + 1, playerCalls.get(), "progress went on asking after $state")
            assertEquals(emptyList(), files())
        }
        val gone = offline { ask("progress", "appIds" to "440,570,620") }
        assertEquals("offline", gone.getString("state"))
        assertEquals(listOf("offline", "skipped", "skipped"), gone.items().map { it.getString("state") })

        // One game Steam could not answer about is that game's trouble.
        val calls = playerCalls.get()
        player[440] = { html("oops", 500) }
        val r = ask("progress", "appIds" to "440,570,620")
        assertEquals("ok", r.getString("state"))
        assertEquals(listOf("upstream_error", "ok", "ok"), r.items().map { it.getString("state") })
        assertEquals(calls + 3, playerCalls.get())
        assertEquals(listOf(progressFile(570), progressFile(620)), files())
    }

    // ── Language ────────────────────────────────────────────────────────────

    @Test fun `a language code becomes steam's name for the language, and anything else english`() {
        val expected = mapOf(
            "it" to "italian", "en" to "english", "it_IT" to "italian", "it-IT" to "italian", "IT" to "italian",
            "pt_BR" to "brazilian", "pt-br" to "brazilian", "pt" to "portuguese", "pt_PT" to "portuguese",
            "de" to "german", "fr" to "french", "es" to "spanish", "es_ES" to "spanish", "es_419" to "latam",
            "ja" to "japanese", "ko" to "koreana", "zh" to "schinese", "zh_CN" to "schinese",
            "zh_TW" to "tchinese", "zh_Hant" to "tchinese", "ru" to "russian", "pl" to "polish",
            " en_GB " to "english",
            // Not a code, or not one of Steam's.
            "" to "english", "xx" to "english", "klingon" to "english", "italian" to "english",
            "../x" to "english", "it/../../x" to "english", "it_../x" to "english", ".." to "english",
            "it\u0000" to "english", "it_IT.UTF-8" to "english", "i" to "english"
        )
        for ((code, language) in expected)
            assertEquals(language, SteamAccountOps.steamLanguage(code), "lang=$code")
        assertEquals("english", SteamAccountOps.steamLanguage(null))
    }

    @Test fun `each language has a schema of its own and the unlock list is one`() {
        val it = ask("achievements", "appId" to "440", "lang" to "it")
        val en = ask("achievements", "appId" to "440", "lang" to "en_US")

        assertEquals("ACH_ONE in italian", it.list().first().getString("title"))
        assertEquals("ACH_ONE in english", en.list().first().getString("title"),
                     "the schema of one language answered a request in another")
        assertEquals(listOf("italian", "english"), schemaLanguages)
        assertEquals(1, playerCalls.get(), "the unlock list was asked for once per language")
        assertEquals(listOf(progressFile(440), schemaFile(440, "english"), schemaFile(440, "italian")), files())

        ask("achievements", "appId" to "440", "lang" to "it_IT")
        assertEquals(2, schemaCalls.get())
    }

    @Test fun `a language that is a path reaches no file name and no request`() {
        for (lang in listOf("../x", "../../../../tmp/x", "it/../../x", "..", "/etc/passwd", "it\u0000../x")) {
            val r = ask("achievements", "appId" to "440", "lang" to lang, "refresh" to "1")
            assertEquals("ok", r.getString("state"))
            advance(61)
        }
        assertTrue(schemaLanguages.all { it == "english" }, "asked Steam in $schemaLanguages")
        assertEquals(listOf(progressFile(440), schemaFile(440, "english")), files())
        // The cache is the whole of what was written: nothing beside it, nothing above.
        assertEquals(listOf("cache"), root.list()?.toList())
        assertEquals(listOf("data"), sandbox.list()?.toList())
    }

    @Test fun `an app id that is not a number is refused before any file or request`() {
        for (appId in listOf("../../x", "abc", "0", "-440", "4x4", "440/../1", "", "99999999999")) {
            assertFailsWith<IllegalArgumentException>("appId=$appId") { ask("achievements", "appId" to appId) }
        }
        assertFailsWith<IllegalArgumentException> { ask("achievements") }
        assertEquals(0, server.requestCount)
        assertEquals(emptyList(), files())

        // The spelling the op has always taken.
        assertEquals("ok", ask("achievements", "appid" to "440").getString("state"))
    }

    // ── What is on disk ─────────────────────────────────────────────────────

    @Test fun `a file that is not what was written is asked for again and not answered from`() {
        ask("achievements", "appId" to "440")
        ask("library")
        val before = Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get())

        File(root, progressFile(440)).writeText("not json")
        File(root, schemaFile(440, "english")).writeText("""{"format":99,"appId":440,"language":"english","achievements":[]}""")
        File(root, libraryFile).writeText("""{"format":1,"fetchedAt":${nowSeconds()},"steamId":"76561190000000000","games":[]}""")

        val r = ask("achievements", "appId" to "440")
        assertEquals("ok", r.getString("state"))
        assertEquals("ACH_ONE in english", r.list().first().getString("title"))
        assertEquals(4, ask("library").getJSONArray("games").length(), "another account's library was answered from")
        assertEquals(Triple(before.first + 1, before.second + 1, before.third + 1),
                     Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get()))

        // One game's list under another game's name.
        File(root, progressFile(440)).copyTo(File(root, progressFile(570)))
        ask("progress", "appIds" to "570")
        assertEquals(before.second + 2, playerCalls.get(), "a list of another game was answered from")
    }

    @Test fun `with no data root nothing is kept and every request asks steam`() {
        player[900] = { json(NO_STATS, 400) }

        repeat(2) {
            val library = ask("library", dir = null)
            assertEquals("ok", library.getString("state"))
            assertFalse(library.getBoolean("cached"))
            assertEquals("ok", ask("achievements", "appId" to "440", dir = null).getString("state"))
            assertEquals("ok", ask("progress", "appIds" to "440", dir = null).getString("state"))
        }
        assertEquals(Triple(2, 4, 2), Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get()))

        // The schema that decides is not asked for a second time to list from.
        val r = ask("achievements", "appId" to "900", dir = null)
        assertEquals("ok", r.getString("state"))
        assertEquals(0 to 2, r.getInt("unlocked") to r.getInt("total"))
        assertEquals(Triple(2, 5, 3), Triple(ownedCalls.get(), playerCalls.get(), schemaCalls.get()))

        assertTrue(ask("library", dir = null).games().none { it.has("unlocked") })
        assertEquals(emptyList(), files())
        assertFalse(root.exists(), "something was made of a data root nobody gave")
    }

    // On Android each request is a coroutine of its own, and a game's tiles ask together.
    @Test fun `four requests for one cold game at once cost one call each to steam`() {
        playerSleepMillis = 150
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val futures = (1..4).map {
            pool.submit<JSONObject> {
                start.await()
                ask("achievements", "appId" to "440")
            }
        }
        start.countDown()
        val answers = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1 to 1, playerCalls.get() to schemaCalls.get(),
                     "four callers that found nothing kept each asked Steam")
        assertTrue(answers.all { it.getString("state") == "ok" && it.getInt("total") == 2 })
        assertEquals(1, answers.count { !it.getBoolean("cached") })
    }

    // ── resolve, and requests that are wrong in themselves ──────────────────

    @Test fun `resolve answers with the id alone and fails as it always has`() {
        val r = ask("resolve", "vanity" to "gabe")
        assertEquals(setOf("steamId"), r.keySet())
        assertEquals(STEAM_ID, r.getString("steamId"))
        // A key is enough: the id is what is being looked for.
        assertEquals(STEAM_ID, ask("resolve", "vanity" to "gabe",
                                   credentials = SteamAccountClient.Credentials(KEY, "")).getString("steamId"))

        assertFailsWith<IllegalArgumentException> { ask("resolve") }
        assertFailsWith<IllegalStateException> { ask("resolve", "vanity" to "gabe", credentials = null) }
        assertFailsWith<IllegalStateException> {
            ask("resolve", "vanity" to "gabe", credentials = SteamAccountClient.Credentials("", STEAM_ID))
        }
        val e = offline { assertFailsWith<SteamAccountClient.SteamException> { ask("resolve", "vanity" to "gabe") } }
        assertEquals(SteamAccountClient.Refusal.TRANSPORT, e.refusal)
        assertEquals(emptyList(), files())
    }

    @Test fun `an op that does not exist is the caller's mistake, set up or not`() {
        assertFailsWith<IllegalArgumentException> { ask("friends") }
        assertFailsWith<IllegalArgumentException> { ask("friends", credentials = null) }
        // A wrong parameter is reported before a missing configuration is.
        assertFailsWith<IllegalArgumentException> { ask("achievements", "appId" to "x", credentials = null) }
        assertFailsWith<IllegalArgumentException> { ask("progress", "appIds" to "x", credentials = null) }
        assertEquals(0, server.requestCount)
    }

    // ── The sentences, and what never reaches one ───────────────────────────

    @Test fun `the message of every state is its one sentence, never steam's text and never an exception's`() {
        val seen = HashMap<String, MutableSet<String>>()
        fun record(op: String, r: JSONObject) {
            val state = r.getString("state")
            seen.getOrPut(if (state == "private" && op == "library") "private:library" else state) { HashSet() }
                .add(r.getString("message"))
            assertFalse(r.toString().contains(MARKER), "Steam's own text reached the answer: $r")
            assertFalse(r.toString().contains("127.0.0.1"), "the network layer's text reached the answer: $r")
        }

        record("library", ask("library", credentials = null))
        record("library", ask("library"))
        record("achievements", ask("achievements", "appId" to "440"))
        player[900] = { json(NO_STATS, 400) }
        schema[900] = { json("""{"game":{}}""") }
        record("achievements", ask("achievements", "appId" to "900"))
        for ((_, answer) in refusals) {
            advance(11 * 60)
            File(root, "cache").deleteRecursively()
            owned = answer
            player[440] = answer
            record("library", ask("library"))
            record("achievements", ask("achievements", "appId" to "440"))
            record("progress", ask("progress", "appIds" to "440,570"))
        }
        // A text of Steam's that is recognised is still not repeated.
        player[440] = { json("""{"playerstats":{"error":"Profile is not public $MARKER","success":false}}""", 403) }
        record("achievements", ask("achievements", "appId" to "440"))
        File(root, "cache").deleteRecursively()
        record("library", offline { ask("library") })
        record("achievements", offline { ask("achievements", "appId" to "440") })
        record("progress", offline { ask("progress", "appIds" to "440") })

        assertEquals(SENTENCES.filterKeys { it != "skipped" }.mapValues { setOf(it.value) }, seen)
        assertEquals(SENTENCES.getValue("skipped"),
                     SteamAccountOps.sentence(SteamAccountOps.State.SKIPPED, "progress"))
        assertFalse(log.joinToString("\n").contains(MARKER), "Steam's own text reached the log")
    }

    @Test fun `the key is in no answer, in no file or file name, and in no line of the log`() {
        val answers = mutableListOf<String>()
        fun keep(r: JSONObject) { answers += r.toString() }

        player[900] = { json(NO_STATS, 400) }
        schema[900] = { json("""{"game":{}}""") }
        keep(ask("library"))
        keep(ask("achievements", "appId" to "440", "lang" to "it"))
        keep(ask("achievements", "appId" to "900"))
        keep(ask("progress", "appIds" to "440,570,900"))
        keep(ask("resolve", "vanity" to "gabe"))
        for ((_, answer) in refusals) {
            advance(11 * 60)
            owned = answer
            player[440] = answer
            schema[570] = { answer() }
            keep(ask("library"))
            keep(ask("achievements", "appId" to "440"))
            keep(ask("progress", "appIds" to "440,570"))
        }
        advance(11 * 60)
        keep(offline { ask("library") })
        keep(offline { ask("achievements", "appId" to "440", "refresh" to "1") })
        keep(offline { ask("progress", "appIds" to "440,1234") })
        answers += runCatching { offline { ask("resolve", "vanity" to "gabe") } }.exceptionOrNull()?.stackTraceToString().orEmpty()
        answers += runCatching { ask("progress", "appIds" to KEY) }.exceptionOrNull()?.stackTraceToString().orEmpty()

        // The sentinel did travel: it is what Steam was asked with.
        assertTrue(server.takeRequest().requestUrl?.queryParameter("key") == KEY)

        assertTrue(answers.size > 30 && answers.none { it.contains(KEY) }, "the key reached an answer")
        assertTrue(log.isNotEmpty(), "nothing was logged, so the log shows nothing")
        assertFalse(log.joinToString("\n").contains(KEY), "the key reached the log")
        assertFalse(log.joinToString("\n").contains(STEAM_ID), "the SteamID reached the log")
        val kept = sandbox.walkTopDown().filter { it.isFile }.toList()
        assertTrue(kept.size >= 5, "too little on disk to show anything: ${files()}")
        for (f in kept) {
            assertFalse(f.path.contains(KEY), "the key is in a file's name: $f")
            assertFalse(f.readText().contains(KEY), "the key is in $f")
        }
        assertEquals(emptyList(), sandbox.walkTopDown().filter { it.name.endsWith(".tmp") }.toList())
    }

    private companion object {
        const val KEY = "SENTINEL-STEAM-KEY"
        const val STEAM_ID = "76561198000000000"

        /** In a text of Steam's that no answer and no log line may repeat. */
        const val MARKER = "MARKER-FROM-STEAM"

        // The sentences, written out a second time on purpose: a state whose
        // message became anything else, an exception's text or Steam's, is no
        // longer equal to its line here.
        val SENTENCES = mapOf(
            "ok" to "Steam answered.",
            "not_configured" to "Steam is not set up: a Web API key and a 17-digit SteamID are needed.",
            "key_refused" to "Steam refused the Web API key.",
            "private" to "This Steam profile's game details are not public.",
            "private:library" to "Steam listed no games: this profile's game details are not public, " +
                                 "or no account has this SteamID.",
            "no_achievements" to "This game has no achievements on Steam.",
            "rate_limited" to "Steam is refusing requests for now: too many were made.",
            "offline" to "Steam could not be reached.",
            "upstream_error" to "Steam gave an answer that could not be used.",
            "skipped" to "Not asked this time."
        )

        // As Steam sent them on 2026-10-13, to a wrong key and to no key.
        const val FORBIDDEN =
            "<html><head><title>Forbidden</title></head><body><h1>Forbidden</h1>Access is denied. " +
            "Retrying will not help. Please verify your <pre>key=</pre> parameter.</body></html>"
        const val UNAUTHORIZED =
            "<html><head><title>Unauthorized</title></head><body><h1>Unauthorized</h1>Access is denied. " +
            "Retrying will not help. Please verify your <pre>key=</pre> parameter.</body></html>"
        const val NO_KEY =
            "<html><head><title>Bad Request</title></head><body><h1>Bad Request</h1>" +
            "Required parameter 'key' is missing</body></html>"

        // Believed, not measured.
        const val NOT_PUBLIC = """{"playerstats":{"error":"Profile is not public","success":false}}"""
        const val NO_STATS = """{"playerstats":{"error":"Requested app has no stats","success":false}}"""

        val PLAYER = playerBody("Team Fortress 2", "ACH_ONE" to 1, "ACH_TWO" to 0)

        const val OWNED = """
        {"response":{"game_count":4,"games":[
          {"appid":570,"name":"Dota 2","playtime_forever":0,"img_icon_url":"abc"},
          {"appid":440,"name":"Team Fortress 2","playtime_forever":1200,"playtime_2weeks":30,
           "img_icon_url":"e3f595a92552da3d664ad00277fad2107345f743","rtime_last_played":1750000000},
          {"appid":400,"name":"Portal","playtime_forever":500,"img_icon_url":"def","rtime_last_played":0},
          {"appid":620,"name":"Portal 2","playtime_forever":300,"img_icon_url":"ghi","rtime_last_played":1760000000}
        ]}}"""

        fun json(body: String, status: Int = 200): MockResponse = MockResponse()
            .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

        fun html(body: String, status: Int): MockResponse = MockResponse()
            .setResponseCode(status).setHeader("Content-Type", "text/html").setBody(body)

        /** A player answer: each achievement with whether it is achieved, the first at a known time. */
        fun playerBody(gameName: String, vararg achievements: Pair<String, Int>): String {
            val list = achievements.joinToString(",") { (name, achieved) ->
                """{"apiname":"$name","achieved":$achieved,"unlocktime":${if (achieved == 1) 1600000000 else 0}}"""
            }
            return """{"playerstats":{"steamID":"$STEAM_ID","gameName":"$gameName","achievements":[$list],"success":true}}"""
        }

        /** The schema of [appId] in [language]; every achievement but the first is a hidden one. */
        fun schemaBody(appId: Int, language: String, vararg names: String): String {
            val list = names.mapIndexed { i, name ->
                """{"name":"$name","defaultvalue":0,"displayName":"$name in $language","hidden":${if (i == 0) 0 else 1},
                    "description":"How $name is earned, in $language",
                    "icon":"https://cdn.example/$appId/$name.jpg","icongray":"https://cdn.example/$appId/${name}_gray.jpg"}"""
            }.joinToString(",")
            return """{"game":{"gameName":"Game $appId","gameVersion":"1","availableGameStats":{"achievements":[$list]}}}"""
        }
    }
}
