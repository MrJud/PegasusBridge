package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The ScreenScraper identify cache: what it is keyed on, how many requests it
 * costs, and what a downloaded picture is named.
 *
 * Three defects live here, and every one of them is reachable from the theme's
 * ordinary game screen:
 *
 * 1. The artwork name was `ss-<gameId>-<kind>.<ext>`, so two regional dumps of
 *    one game shared a file and the second showed the first one's cover.
 * 2. The cache key was `platform|path`, so an Italian request after an English
 *    one got the English synopsis, and a replaced ROM kept the old identity.
 * 3. The map was an access-order LinkedHashMap read and written concurrently by
 *    the daemon's worker pool with no synchronisation, and a cache miss shared
 *    by four threads issued four requests against an account the API limits to
 *    one at a time.
 */
class ScreenScraperCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths
    private lateinit var config: Config
    private var originalBase = ""

    /** Every `jeuInfos` the server was asked for. The quota, made countable. */
    private val jeuInfosCalls = AtomicInteger()
    private val systemsCalls = AtomicInteger()

    /** Swapped per test to change what the API returns for the same ROM. */
    @Volatile private var mediaUrlSuffix = "us"

    @BeforeTest fun setUp() {
        BridgeLog.current = NoopLog
        dataRoot = Files.createTempDirectory("ss-data").toFile()
        romRoot  = Files.createTempDirectory("ss-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        writeCredentials()
        config = Config(paths)

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/jeuInfos.php") -> {
                        jeuInfosCalls.incrementAndGet()
                        // Slow enough that concurrent callers overlap for real: a
                        // de-duplication test against an instant server would pass
                        // even with no de-duplication at all.
                        Thread.sleep(150)
                        MockResponse().setBody(jeuInfosBody(languageOf(path)))
                    }
                    path.startsWith("/systemesListe.php") -> {
                        systemsCalls.incrementAndGet()
                        MockResponse().setBody(SYSTEMS_BODY)
                    }
                    path.startsWith("/media") -> MockResponse()
                        .setBody(if (path.contains("eu")) "EUROPEAN-BYTES" else "AMERICAN-BYTES")
                    else -> MockResponse().setResponseCode(404).setBody("Erreur : Jeu non trouvée !")
                }
            }
        }
        server.start()
        originalBase = ScreenScraperClient.BASE
        ScreenScraperClient.BASE = server.url("/").toString().trimEnd('/')
    }

    @AfterTest fun tearDown() {
        ScreenScraperClient.BASE = originalBase
        server.shutdown()
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun writeCredentials() {
        paths.config.mkdirs()
        paths.credentials.writeText("""
            {"screenScraper":{"devId":"dev","devPassword":"SENTINEL-DEV",
             "ssid":"member","ssPassword":"SENTINEL-MEMBER","softname":"PegasusBridge"}}
        """.trimIndent())
    }

    private fun languageOf(path: String) =
        Regex("[?&]lang=([a-z]{2})").find(path)?.groupValues?.get(1) ?: "en"

    /**
     * One game, with a media URL that carries the credentials — as the real API's
     * does — so any test asserting a filename is also asserting they stayed out of it.
     */
    private fun jeuInfosBody(lang: String) = """
    {"response":{"jeu":{
      "id":"1446",
      "noms":[{"region":"wor","text":"Contra"}],
      "editeur":{"text":"Konami"},
      "developpeur":{"text":"Konami"},
      "joueurs":{"text":"1-2"},
      "dates":[{"region":"wor","text":"1988-02-09"}],
      "note":{"text":"18"},
      "synopsis":[{"langue":"en","text":"ENGLISH SYNOPSIS"},
                  {"langue":"it","text":"SINOSSI ITALIANA"}],
      "genres":[{"noms":[{"langue":"en","text":"Run and gun"},
                         {"langue":"it","text":"Sparatutto"}],"nomcourt":"shooter"}],
      "medias":[
        {"type":"box-2D","region":"us","format":"png",
         "url":"${server.url("/media")}?devid=dev&devpassword=SENTINEL-DEV&media=box2d&r=us&v=$mediaUrlSuffix"},
        {"type":"box-2D","region":"eu","format":"png",
         "url":"${server.url("/media")}?devid=dev&devpassword=SENTINEL-DEV&media=box2d&r=eu&v=$mediaUrlSuffix"}
      ]
    }}}
    """.trimIndent().also { require(lang.isNotEmpty()) }

    private fun dispatcher() = ScrapeSourceDispatcher(config, paths)

    /** A distinct ROM file whose name declares its region, as No-Intro dumps do. */
    private fun rom(name: String, bytes: String): File {
        val dir = File(romRoot, "nes").apply { mkdirs() }
        return File(dir, name).apply { writeText(bytes) }
    }

    private fun mediaParams(file: File, kind: String = "cover", lang: String = "en") =
        mapOf("file" to file.absolutePath, "platform" to "nes", "kind" to kind, "lang" to lang)

    // ── H1.1: the artwork name must describe the picture, not the game ──────

    @Test fun `two regional dumps of one game get different files and different bytes`() {
        val usa = rom("Contra (USA).nes", "usa-bytes")
        val eur = rom("Contra (Europe).nes", "eur-bytes")
        val d = dispatcher()

        val a = d.run("ss", "media", mediaParams(usa)).results as JSONObject
        val b = d.run("ss", "media", mediaParams(eur)).results as JSONObject

        assertEquals("us", a.getString("region"))
        assertEquals("eu", b.getString("region"))
        assertNotEquals(a.getString("localPath"), b.getString("localPath"),
                        "one game id, two regional covers, one file: that is the bug")
        assertEquals("AMERICAN-BYTES", File(a.getString("localPath")).readText())
        assertEquals("EUROPEAN-BYTES", File(b.getString("localPath")).readText())
    }

    @Test fun `the same request twice reuses the file it already fetched`() {
        val usa = rom("Contra (USA).nes", "usa-bytes")
        val d = dispatcher()

        val first = d.run("ss", "media", mediaParams(usa)).results as JSONObject
        val path = File(first.getString("localPath"))
        val stamp = path.lastModified()
        path.setLastModified(stamp - 60_000)

        val second = d.run("ss", "media", mediaParams(usa)).results as JSONObject

        assertEquals(first.getString("localPath"), second.getString("localPath"))
        assertEquals(stamp - 60_000, File(second.getString("localPath")).lastModified(),
                     "the file was re-downloaded even though nothing had changed")
    }

    // The other half: reuse must not survive the picture itself changing.
    @Test fun `a changed media url is fetched rather than served stale`() {
        val usa = rom("Contra (USA).nes", "usa-bytes")

        val before = dispatcher().run("ss", "media", mediaParams(usa)).results as JSONObject
        mediaUrlSuffix = "v2"
        // A fresh dispatcher, because the point is the *file* name, not the memory cache.
        val after = dispatcher().run("ss", "media", mediaParams(usa)).results as JSONObject

        assertNotEquals(before.getString("localPath"), after.getString("localPath"),
                        "a replaced picture reused the old file name")
        assertNotEquals(before.getString("variant"), after.getString("variant"))
    }

    @Test fun `no credential from the media url reaches the file name or the response`() {
        val usa = rom("Contra (USA).nes", "usa-bytes")
        val r = dispatcher().run("ss", "media", mediaParams(usa)).results as JSONObject

        val name = File(r.getString("localPath")).name
        assertTrue(!name.contains("SENTINEL-DEV") && !name.contains("SENTINEL-MEMBER"),
                   "the media URL's credentials reached the file name: $name")
        assertTrue(!r.toString().contains("SENTINEL"), "a credential reached the response: $r")
    }

    // ── H1.2: one question, one request ─────────────────────────────────────

    @Test fun `four concurrent kinds of one rom cost one jeuInfos`() {
        val usa = rom("Contra (USA).nes", "usa-bytes")
        val d = dispatcher()
        val kinds = listOf("cover", "wheel", "wallpaper", "screenshot")
        val pool = Executors.newFixedThreadPool(kinds.size)
        val start = CountDownLatch(1)

        val futures = kinds.map { kind ->
            pool.submit {
                start.await()
                d.run("ss", "media", mediaParams(usa, kind))
            }
        }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, jeuInfosCalls.get(),
                     "one game asked about four ways should cost one request, " +
                     "against an account the API limits to one thread")
    }

    @Test fun `a game call and a media call for one rom share the identification`() {
        val usa = rom("Contra (USA).nes", "usa-bytes")
        val d = dispatcher()
        d.run("ss", "game", mediaParams(usa))
        d.run("ss", "media", mediaParams(usa))
        assertEquals(1, jeuInfosCalls.get())
    }

    @Test fun `english and italian each get their own synopsis and genres`() {
        val usa = rom("Contra (USA).nes", "usa-bytes")
        val d = dispatcher()

        val en = d.run("ss", "game", mediaParams(usa, lang = "en")).results as JSONObject
        val it = d.run("ss", "game", mediaParams(usa, lang = "it")).results as JSONObject

        assertEquals("ENGLISH SYNOPSIS", en.getString("description"))
        assertEquals("SINOSSI ITALIANA", it.getString("description"),
                     "the cache returned the English answer to an Italian question")
        assertEquals("Run and gun", en.getJSONArray("genres").getString(0))
        assertEquals("Sparatutto", it.getJSONArray("genres").getString(0))
        assertEquals(2, jeuInfosCalls.get(), "two languages are two questions")
    }

    @Test fun `replacing the rom at the same path invalidates the cached identity`() {
        val f = rom("Contra (USA).nes", "usa-bytes")
        val d = dispatcher()
        d.run("ss", "game", mediaParams(f))
        assertEquals(1, jeuInfosCalls.get())

        // A better dump, same name. Size and mtime both move.
        f.writeText("a-different-and-longer-dump-of-the-same-game")
        f.setLastModified(f.lastModified() + 60_000)

        d.run("ss", "game", mediaParams(f))
        assertEquals(2, jeuInfosCalls.get(),
                     "the replaced file kept the old identity")
    }

    @Test fun `an explicit systemeid is part of the question`() {
        val f = rom("Contra (USA).nes", "usa-bytes")
        val d = dispatcher()
        d.run("ss", "game", mediaParams(f) + ("systemeid" to "3"))
        d.run("ss", "game", mediaParams(f) + ("systemeid" to "75"))
        assertEquals(2, jeuInfosCalls.get(),
                     "the same file under a different system is a different question")
    }

    // The map is read and written from the daemon's worker pool. An access-order
    // LinkedHashMap rebalances on *read*, so unsynchronised concurrent gets alone
    // can corrupt it — the classic symptom being a get that never returns.
    @Test fun `concurrent access across many roms stays consistent and bounded`() {
        val files = (1..40).map { rom("Game $it (USA).nes", "bytes-$it") }
        val d = dispatcher()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        val futures = (1..240).map { i ->
            pool.submit {
                start.await()
                try { d.run("ss", "game", mediaParams(files[i % files.size])) }
                catch (t: Throwable) { errors += t }
            }
        }
        start.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(errors.isEmpty(), "concurrent identification failed: ${errors.firstOrNull()}")
        assertEquals(files.size, jeuInfosCalls.get(),
                     "240 requests over 40 ROMs should be 40 questions")
    }

    // ── H1.3: the system table is not kept forever ──────────────────────────

    @Test fun `a fresh system table is read from disk instead of refetched`() {
        val f = paths.cache(SS_SYSTEMS_FILE)
        BridgePaths.writeAtomic(f, ScreenScraperSystemMap.toJson(
            listOf(ScreenScraperClient.SsSystem(3, listOf("nes"), listOf("nes")))))

        dispatcher().run("ss", "systems", emptyMap())
        assertEquals(0, systemsCalls.get(), "a table fetched today was refetched")
    }

    @Test fun `a table older than the ttl is refetched`() {
        val f = paths.cache(SS_SYSTEMS_FILE)
        val stale = JSONObject(ScreenScraperSystemMap.toJson(
            listOf(ScreenScraperClient.SsSystem(3, listOf("nes"), listOf("nes")))))
        stale.put("fetchedAt", BridgePaths.epochSeconds() - 40L * 24 * 60 * 60)
        BridgePaths.writeAtomic(f, stale.toString())

        dispatcher().run("ss", "systems", emptyMap())
        assertEquals(1, systemsCalls.get(), "a forty-day-old table was trusted")
    }

    @Test fun `a table from an older schema is refetched`() {
        val f = paths.cache(SS_SYSTEMS_FILE)
        val old = JSONObject(ScreenScraperSystemMap.toJson(
            listOf(ScreenScraperClient.SsSystem(3, listOf("nes"), listOf("nes")))))
        old.remove("schemaVersion")
        BridgePaths.writeAtomic(f, old.toString())

        dispatcher().run("ss", "systems", emptyMap())
        assertEquals(1, systemsCalls.get(), "an unstamped table was trusted")
    }

    @Test fun `refresh equals one forces a refetch of a fresh table`() {
        BridgePaths.writeAtomic(paths.cache(SS_SYSTEMS_FILE), ScreenScraperSystemMap.toJson(
            listOf(ScreenScraperClient.SsSystem(3, listOf("nes"), listOf("nes")))))

        dispatcher().run("ss", "systems", mapOf("refresh" to "1"))
        assertEquals(1, systemsCalls.get())
    }

    // Losing the whole table because a refresh timed out would take arcade lookups
    // down with it, and those cannot work without an id at all.
    @Test fun `a failed refresh keeps the table that was already on disk`() {
        val f = paths.cache(SS_SYSTEMS_FILE)
        val stale = JSONObject(ScreenScraperSystemMap.toJson(
            listOf(ScreenScraperClient.SsSystem(3, listOf("nes"), listOf("nes")))))
        stale.put("fetchedAt", BridgePaths.epochSeconds() - 40L * 24 * 60 * 60)
        BridgePaths.writeAtomic(f, stale.toString())

        // Point the client somewhere that refuses, so the refresh cannot succeed.
        ScreenScraperClient.BASE = "http://127.0.0.1:1"
        val rom = rom("Contra (USA).nes", "usa-bytes")
        // Identification will fail too, but what is asserted is that the stale table
        // survived rather than being replaced by nothing.
        runCatching { dispatcher().run("ss", "game", mediaParams(rom)) }

        assertTrue(f.isFile, "the stale table was deleted")
        assertEquals(1, ScreenScraperSystemMap.fromJson(f.readText()).size)
    }

    private companion object {
        const val SS_SYSTEMS_FILE = "screenscraper_systems.json"
        val SYSTEMS_BODY = """
        {"response":{"systemes":[
          {"id":"3","noms":{"nom_eu":"Nintendo Entertainment System","noms_commun":"nes"},
           "extensions":"nes,fds"}
        ]}}
        """.trimIndent()
    }
}
