package com.pegasus.bridge.ra

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaSyncTest {

    private lateinit var root: File
    private lateinit var paths: BridgePaths
    private lateinit var config: Config
    private lateinit var sync: RaSync
    private lateinit var server: MockWebServer
    private var originalBase = ""

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("ra-sync-test").toFile()
        paths = BridgePaths(root); paths.ensureAll()
        config = Config(paths)
        config.writeCredentials(raUser = "MrJud", raApiKey = "KEY")
        sync = RaSync(paths, config)
        server = MockWebServer(); server.start()
        originalBase = RaApiClient.BASE
        RaApiClient.BASE = server.url("/API/").toString()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        RaApiClient.BASE = originalBase
        server.shutdown()
        root.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun route(map: Map<String, String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = map.entries.firstOrNull { path.contains(it.key) }?.value ?: "[]"
                return MockResponse().setBody(body)
            }
        }
    }

    // ── searchGames: the f-parameter bug ────────────────────────────────────

    // `f` is a numeric flag, not a text filter. The old code put the search term
    // there, so RA returned the whole console catalogue regardless of input.
    @Test fun `search sends a numeric flag and never the term`() {
        route(mapOf("API_GetGameList" to """[{"ID":1,"Title":"Contra"}]"""))
        sync.searchGames(7, "mario", "job1")
        val path = server.takeRequest().path!!
        assertTrue(path.contains("f=0"), "expected numeric flag in: $path")
        assertFalse(path.contains("f=mario"), "search term must not be sent as the flag: $path")
    }

    @Test fun `search filters the catalogue client-side`() {
        route(mapOf("API_GetGameList" to """
            [{"ID":1,"Title":"Super Mario Bros."},
             {"ID":2,"Title":"Contra"},
             {"ID":3,"Title":"Mario Bros."}]
        """.trimIndent()))
        sync.searchGames(7, "mario", "job1")

        val out = JSONObject(paths.searchRa("job1").readText())
        assertEquals("ok", out.getString("status"))
        val results = out.getJSONArray("results")
        assertEquals(2, results.length(), "only the two Mario titles should match")
        assertEquals("Super Mario Bros.", results.getJSONObject(0).getString("Title"))
    }

    @Test fun `search is case-insensitive and an empty term returns everything`() {
        route(mapOf("API_GetGameList" to """[{"ID":1,"Title":"Contra"},{"ID":2,"Title":"Metroid"}]"""))
        sync.searchGames(7, "CONTRA", "job1")
        assertEquals(1, JSONObject(paths.searchRa("job1").readText()).getJSONArray("results").length())

        sync.searchGames(7, "", "job2")
        assertEquals(2, JSONObject(paths.searchRa("job2").readText()).getJSONArray("results").length())
    }

    // RA's catalogue is full of "~Hack~" entries that would otherwise lead the
    // results for any popular series, burying the game the user actually wants.
    @Test fun `search puts official titles before rom hacks`() {
        route(mapOf("API_GetGameList" to """
            [{"ID":1,"Title":"~Hack~ Castlevania II: Subotai's Hunt"},
             {"ID":2,"Title":"~Demo~ Castlevania Tech Demo"},
             {"ID":3,"Title":"Castlevania III: Dracula's Curse"},
             {"ID":4,"Title":"Castlevania"}]
        """.trimIndent()))
        sync.searchGames(7, "castlevania", "job1")

        val results = JSONObject(paths.searchRa("job1").readText()).getJSONArray("results")
        assertEquals(4, results.length())
        assertEquals("Castlevania III: Dracula's Curse", results.getJSONObject(0).getString("Title"))
        assertEquals("Castlevania", results.getJSONObject(1).getString("Title"),
                     "official titles keep their relative order")
        assertTrue(results.getJSONObject(2).getString("Title").startsWith("~"))
        assertTrue(results.getJSONObject(3).getString("Title").startsWith("~"))
    }

    @Test fun `search reports no_results rather than failing`() {
        route(mapOf("API_GetGameList" to """[{"ID":1,"Title":"Contra"}]"""))
        sync.searchGames(7, "zzz-nothing", "job1")
        assertEquals("no_results", JSONObject(paths.searchRa("job1").readText()).getString("status"))
    }

    @Test fun `search without credentials writes an error the theme can read`() {
        val bare = BridgePaths(Files.createTempDirectory("ra-nocreds").toFile()).also { it.ensureAll() }
        val s = RaSync(bare, Config(bare))
        val r = s.searchGames(7, "x", "job1")
        assertTrue(r is RaSync.Result.Skipped)
        val out = JSONObject(bare.searchRa("job1").readText())
        assertEquals("error", out.getString("status"))
        bare.root.deleteRecursively()
    }

    // ── refreshProfile ──────────────────────────────────────────────────────

    @Test fun `profile writes both cache files`() = runBlocking {
        route(mapOf(
            "API_GetUserSummary"            to """{"User":"MrJud","TotalPoints":1234}""",
            "API_GetUserCompletionProgress" to """{"Count":5}""",
            "API_GetUserRecentlyPlayedGames" to """[{"GameID":1446,"Title":"Super Mario Bros."}]"""
        ))
        val r = sync.refreshProfile("MrJud")
        assertTrue(r is RaSync.Result.Ok, "got $r")

        val profile = JSONObject(paths.profile("MrJud").readText())
        assertEquals("MrJud", profile.getJSONObject("summary").getString("User"))
        assertEquals(1, profile.getJSONArray("recentlyPlayed").length())
        assertEquals(5, JSONObject(paths.completion("MrJud").readText()).getJSONObject("data").getInt("Count"))
    }

    // A failed refresh must not replace a good cache with an empty one.
    @Test fun `profile keeps the existing cache when every call comes back empty`() {
        BridgePaths.writeAtomic(paths.profile("MrJud"), """{"summary":{"User":"MrJud"},"stale":true}""")
        route(mapOf("API_" to ""))

        val r = runBlocking { sync.refreshProfile("MrJud") }
        assertTrue(r is RaSync.Result.Failed, "got $r")
        assertTrue(paths.profile("MrJud").readText().contains("stale"), "cache must be preserved")
    }

    // A partial failure is the common one: RetroAchievements throttles during a
    // ROM scan and the summary call comes back empty while completion succeeds.
    // Guarding on "all three empty" let that overwrite a good profile with an
    // empty one, and the user lost their avatar and points with no error shown.
    @Test fun `an empty summary does not wipe the cached profile`() {
        BridgePaths.writeAtomic(paths.profile("MrJud"),
            """{"summary":{"User":"MrJud","TotalPoints":105},"good":true}""")
        route(mapOf(
            "API_GetUserSummary"            to "",
            "API_GetUserCompletionProgress" to """{"Count":5}""",
            "API_GetUserRecentlyPlayedGames" to """[]"""
        ))

        val r = runBlocking { sync.refreshProfile("MrJud") }
        assertTrue(r is RaSync.Result.Ok, "the run partly succeeded, so it is not a failure")

        val kept = JSONObject(paths.profile("MrJud").readText())
        assertTrue(kept.optBoolean("good"), "the profile must survive an empty summary")
        assertEquals(105, kept.getJSONObject("summary").getInt("TotalPoints"))
        assertEquals(5, JSONObject(paths.completion("MrJud").readText())
            .getJSONObject("data").getInt("Count"), "completion still updates")
    }

    @Test fun `an empty completion does not wipe the cached completion`() {
        BridgePaths.writeAtomic(paths.completion("MrJud"), """{"data":{"Count":9},"good":true}""")
        route(mapOf(
            "API_GetUserSummary"            to """{"User":"MrJud"}""",
            "API_GetUserCompletionProgress" to "",
            "API_GetUserRecentlyPlayedGames" to """[]"""
        ))

        runBlocking { sync.refreshProfile("MrJud") }
        assertTrue(JSONObject(paths.completion("MrJud").readText()).optBoolean("good"))
        assertEquals("MrJud", JSONObject(paths.profile("MrJud").readText())
            .getJSONObject("summary").getString("User"), "the profile still updates")
    }

    // ── refreshGameDetail ───────────────────────────────────────────────────

    @Test fun `detail merges into existing metadata without losing fields`() {
        BridgePaths.writeAtomic(paths.metadata("2221"), """
            {"schemaVersion":1,"gameId":2221,"title":"Castlevania III",
             "rom":{"hash":"abc"},"ra":{"total":55}}
        """.trimIndent())
        route(mapOf("API_GetGameInfoAndUserProgress" to """{"NumAwarded":7,"Title":"Castlevania III"}"""))

        assertTrue(sync.refreshGameDetail(2221) is RaSync.Result.Ok)

        val meta = JSONObject(paths.metadata("2221").readText())
        assertEquals("Castlevania III", meta.getString("title"), "existing fields must survive")
        assertEquals("abc", meta.getJSONObject("rom").getString("hash"))
        assertEquals(55, meta.getJSONObject("ra").getInt("total"), "existing ra fields must survive")
        assertEquals(7, meta.getJSONObject("ra").getJSONObject("detail").getInt("NumAwarded"))
    }

    @Test fun `detail leaves metadata alone when the call comes back empty`() {
        BridgePaths.writeAtomic(paths.metadata("2221"), """{"gameId":2221,"ra":{"total":55}}""")
        route(mapOf("API_" to ""))

        assertTrue(sync.refreshGameDetail(2221) is RaSync.Result.Failed)
        val meta = JSONObject(paths.metadata("2221").readText())
        assertFalse(meta.getJSONObject("ra").has("detail"))
        assertEquals(55, meta.getJSONObject("ra").getInt("total"))
    }

    @Test fun `detail rejects a non-positive gameId`() {
        assertTrue(sync.refreshGameDetail(0) is RaSync.Result.Failed)
    }

    // ── matchGame ───────────────────────────────────────────────────────────
    //
    // This is what replaces the fuzzy matcher and console table the theme used
    // to carry, so the cases mirror what that QML code did.

    private fun writeIndex(vararg entries: Pair<String, String>) {
        val byKey = JSONObject()
        for ((key, json) in entries) byKey.put(key, JSONObject(json))
        BridgePaths.writeAtomic(paths.discoveryIndex,
            JSONObject().put("byKey", byKey).toString())
    }

    @Test fun `a scanned rom is answered from the index, with no network call`() {
        writeIndex("castlevaniaiiidraculascurse|nes" to
            """{"gameId":2221,"title":"Castlevania III: Dracula's Curse","platform":"nes"}""")

        val m = sync.matchGame("Castlevania III - Dracula's Curse (USA)", "nes",
                               "/roms/nes/Castlevania III - Dracula's Curse (USA).nes")
        assertEquals(2221, m?.gameId)
        assertEquals("hash_index", m?.method)
        assertTrue(m!!.onDevice, "an indexed game is on the device by definition")
        assertEquals(0, server.requestCount, "the index is authoritative; do not call the API")
    }

    // The platform half of a key comes from the folder name, which users pick.
    @Test fun `an indexed game is still found when the folder name differs`() {
        writeIndex("contra|nintendo-nes" to
            """{"gameId":1447,"title":"Contra","platform":"nes"}""")

        val m = sync.matchGame("Contra (USA)", "nes", "/roms/nes/Contra (USA).nes")
        assertEquals(1447, m?.gameId, "a title-only hit still identifies the game")
        assertTrue(m!!.onDevice)
    }

    @Test fun `an entry the hasher could not identify is not treated as a match`() {
        writeIndex("contra|nes" to """{"gameId":0,"title":"","platform":"nes"}""")
        route(mapOf("API_GetGameList" to """[]"""))
        assertEquals(null, sync.matchGame("Contra", "nes", "/roms/nes/Contra (USA).nes"))
    }

    @Test fun `a game not on the device falls back to the console catalogue`() {
        route(mapOf("API_GetGameList" to
            """[{"ID":1483,"Title":"Mega Man 3"},{"ID":1447,"Title":"Contra"}]"""))

        val m = sync.matchGame("Mega Man 3", "nes")
        assertEquals(1483, m?.gameId)
        assertEquals("pegasus_title", m?.method)
        assertFalse(m!!.onDevice, "a catalogue match says nothing about the device")
        assertEquals("NES", m.consoleName)
    }

    @Test fun `a platform without retroachievements is answered without a call`() {
        val m = sync.matchGame("Zelda Tears of the Kingdom", "switch")
        assertEquals(null, m)
        assertEquals(0, server.requestCount)
    }

    @Test fun `an unrecognisable title returns nothing rather than a wrong game`() {
        route(mapOf("API_GetGameList" to """[{"ID":1447,"Title":"Contra"}]"""))
        assertEquals(null, sync.matchGame("Totally Unrelated Game XYZ", "nes"))
    }

    // ── consoleTable ────────────────────────────────────────────────────────

    @Test fun `the console table carries every lookup a theme needs`() {
        val t = sync.consoleTable()
        assertEquals(7, t.getJSONObject("consoleId").getInt("nes"))
        assertEquals("nes", t.getJSONObject("pegasusShortName").getString("NES"))
        assertEquals("N64", t.getJSONObject("shortLabel").getString("Nintendo 64"))
        assertEquals("NES", t.getJSONObject("consoleName").getString("nes"))
    }
}
