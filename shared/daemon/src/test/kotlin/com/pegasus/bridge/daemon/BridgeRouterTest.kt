package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import com.pegasus.bridge.hasher.GameMetadata
import com.pegasus.bridge.hasher.HashResult
import com.pegasus.bridge.hasher.RaHashLookup
import com.pegasus.bridge.hasher.RomHasher
import com.pegasus.bridge.hasher.RomScanPipeline
import com.pegasus.bridge.pegasus.MetadataFile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeRouterTest {

    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths
    private lateinit var config: Config
    private lateinit var server: MicroHttpServer

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private class FixedHasher : RomHasher {
        override fun hash(path: String) = HashResult(File(path).readText().trim(), 7)
    }
    private class FixedLookup : RaHashLookup {
        override suspend fun lookup(hash: String) =
            if (hash == "hash-smb") GameMetadata(1446, "Super Mario Bros.", "NES", "/i.png", 76)
            else GameMetadata(gameId = 0)
    }

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("router-data").toFile()
        romRoot  = Files.createTempDirectory("router-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        config = Config(paths)
        BridgeLog.current = NoopLog

        val registry = JobRegistry(paths)
        val router = BridgeRouter(
            paths, config, registry,
            scanPipeline = { RomScanPipeline(paths, FixedHasher(), FixedLookup(), throttleMs = { 0L }) }
        )
        server = MicroHttpServer(handler = router::handle)
        server.start()
    }

    @AfterTest fun tearDown() {
        server.stop()
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun url(p: String) = "http://127.0.0.1:${server.port}$p"
    private fun get(p: String) = client.newCall(Request.Builder().url(url(p)).build()).execute()
    private fun post(p: String, body: String) = client.newCall(
        Request.Builder().url(url(p)).post(body.toRequestBody()).build()).execute()

    // ── health ──────────────────────────────────────────────────────────────

    @Test fun `health reports the data root and which credentials exist`() {
        get("/health").use { r ->
            val j = JSONObject(r.body!!.string())
            assertEquals("ok", j.getString("status"))
            assertEquals(dataRoot.absolutePath, j.getString("dataRoot"))
            assertFalse(j.getJSONObject("credentials").getBoolean("steamGridDb"))
        }
        config.writeCredentials(sgdbKey = "K")
        get("/health").use { r ->
            assertTrue(JSONObject(r.body!!.string()).getJSONObject("credentials").getBoolean("steamGridDb"))
        }
    }

    @Test fun `an unknown endpoint is a 404 with a json body`() {
        get("/nope").use { r ->
            assertEquals(404, r.code)
            assertEquals("error", JSONObject(r.body!!.string()).getString("status"))
        }
    }

    // ── credentials ─────────────────────────────────────────────────────────

    // The whole point of the HTTP contract: one call, answer in the response.
    @Test fun `credentials accepts a json body and reports which blocks changed`() {
        post("/credentials", """{"sgdbKey":"SGKEY","igdbClientId":"CID","igdbClientSecret":"SEC"}""").use { r ->
            assertEquals(200, r.code)
            val updated = JSONObject(r.body!!.string()).getJSONArray("updated")
            assertEquals(2, updated.length())
            assertTrue((0 until updated.length()).map { updated.getString(it) }
                .containsAll(listOf("steamGridDb", "igdb")))
        }
        val creds = config.load()
        assertEquals("SGKEY", creds.steamGridDb?.apiKey)
        assertEquals("CID", creds.igdb?.clientId)
    }

    @Test fun `credentials also accepts plain query params`() {
        get("/credentials?sgdbKey=VIAQUERY").use { r -> assertEquals(200, r.code) }
        assertEquals("VIAQUERY", config.load().steamGridDb?.apiKey)
    }

    @Test fun `a partial credentials write leaves the other blocks alone`() {
        post("/credentials", """{"sgdbKey":"A","igdbClientId":"CID"}""").use { it.body!!.string() }
        post("/credentials", """{"sgdbKey":"B"}""").use { it.body!!.string() }
        val c = config.load()
        assertEquals("B", c.steamGridDb?.apiKey)
        assertEquals("CID", c.igdb?.clientId, "igdb must survive an sgdb-only write")
    }

    @Test fun `credentials rejects an empty or malformed request`() {
        post("/credentials", "not json").use { r -> assertEquals(400, r.code) }
        post("/credentials", "{}").use { r -> assertEquals(400, r.code) }
    }

    // The theme binds its settings UI to this instead of keeping its own copy of
    // the credentials, so it has to be complete about presence and silent about
    // values.
    @Test fun `credentials status reports presence and the username, not the secrets`() {
        post("/credentials", """{"user":"MrJud","apiKey":"RASECRET","sgdbKey":"SGSECRET"}""")
            .use { it.body!!.string() }

        get("/credentials/status").use { r ->
            val body = r.body!!.string()
            assertEquals(200, r.code)
            val c = JSONObject(body).getJSONObject("credentials")
            assertTrue(c.getJSONObject("ra").getBoolean("configured"))
            assertEquals("MrJud", c.getJSONObject("ra").getString("user"))
            assertTrue(c.getJSONObject("steamGridDb").getBoolean("configured"))
            assertFalse(c.getJSONObject("igdb").getBoolean("configured"))
            assertFalse(body.contains("RASECRET"))
            assertFalse(body.contains("SGSECRET"))
        }
    }

    @Test fun `clearing a block is what logging out does`() {
        post("/credentials", """{"user":"MrJud","apiKey":"KEY","sgdbKey":"SGKEY"}""")
            .use { it.body!!.string() }

        get("/credentials/clear?block=ra").use { r ->
            assertEquals(200, r.code)
            val j = JSONObject(r.body!!.string())
            assertEquals("ra", j.getString("cleared"))
            assertFalse(j.getJSONObject("credentials").getJSONObject("ra").getBoolean("configured"))
        }
        assertEquals(null, config.load().ra)
        assertEquals("SGKEY", config.load().steamGridDb?.apiKey, "the rest must survive")
    }

    @Test fun `clearing refuses an unknown or missing block`() {
        get("/credentials/clear?block=nonsense").use { r -> assertEquals(400, r.code) }
        get("/credentials/clear").use { r -> assertEquals(400, r.code) }
    }

    @Test fun `credential values never appear in the response`() {
        post("/credentials", """{"sgdbKey":"SUPERSECRET"}""").use { r ->
            assertFalse(r.body!!.string().contains("SUPERSECRET"))
        }
    }

    // ── scrape ──────────────────────────────────────────────────────────────

    @Test fun `scrape validates its parameters before doing any work`() {
        get("/scrape?op=search").use { r ->
            assertEquals(400, r.code)
            assertTrue(JSONObject(r.body!!.string()).getString("error").contains("source"))
        }
        get("/scrape?source=sgdb").use { r ->
            assertEquals(400, r.code)
            assertTrue(JSONObject(r.body!!.string()).getString("error").contains("op"))
        }
    }

    @Test fun `scrape without credentials explains itself instead of failing opaquely`() {
        get("/scrape?source=sgdb&op=search&term=Contra").use { r ->
            assertEquals(400, r.code)
            assertTrue(JSONObject(r.body!!.string()).getString("error").contains("steamGridDb"),
                       "the error should name the missing credential")
        }
    }

    @Test fun `scrape rejects an unknown source`() {
        get("/scrape?source=nope&op=search&term=x").use { r ->
            assertEquals(400, r.code)
            assertTrue(JSONObject(r.body!!.string()).getString("error").contains("nope"))
        }
    }

    // ── ra ──────────────────────────────────────────────────────────────────

    @Test fun `ra endpoints validate their parameters`() {
        get("/ra/search").use { r -> assertEquals(400, r.code) }
        get("/ra/game").use { r -> assertEquals(400, r.code) }
    }

    @Test fun `ra search without credentials is a 400, not a hang`() {
        get("/ra/search?consoleId=7&term=mario").use { r ->
            assertEquals(400, r.code)
            assertTrue(JSONObject(r.body!!.string()).getString("error").contains("credential"))
        }
    }

    // ── video ───────────────────────────────────────────────────────────────

    // ── declaring a collection nobody declared ──────────────────────────────

    /**
     * The tablet's `n64`: three hundred megabytes of cartridges, no metafile,
     * and therefore no games at all as far as Pegasus is concerned.
     */
    @Test fun `propose finds a directory of roms that declares nothing`() {
        val n64 = File(romRoot, "n64").apply { mkdirs() }
        File(n64, "Super Mario 64.z64").writeText("rom")
        File(n64, "GoldenEye 007.z64").writeText("rom")

        get("/collections/propose?roots=${romRoot.absolutePath}").use { r ->
            assertEquals(200, r.code)
            val j = JSONObject(r.body!!.string())
            val c = j.getJSONArray("collections").getJSONObject(0)
            assertEquals(n64.absolutePath, c.getString("directory"))
            assertEquals(2, c.getInt("candidateFiles"))
            assertTrue(c.getString("because").contains("no Pegasus metadata file"))
            // With no system table cached, the extensions come off the files and
            // the answer says so rather than implying a platform was recognised.
            assertEquals("absent", j.getString("systemTable"))
            assertEquals("observed", c.getString("extensionsFrom"))
        }
    }

    /** A collection that already finds its games is not proposed again. */
    @Test fun `propose leaves a working collection alone`() {
        val n64 = File(romRoot, "n64").apply { mkdirs() }
        File(n64, "Super Mario 64.z64").writeText("rom")
        File(n64, "metadata.pegasus.txt").writeText(
            "collection: Nintendo 64\nshortname: n64\nextensions: z64\n")
        get("/collections/propose?roots=${romRoot.absolutePath}").use { r ->
            assertEquals(0, JSONObject(r.body!!.string()).getInt("count"))
        }
    }

    @Test fun `apply writes an overlay that declares the extensions`() {
        val n64 = File(romRoot, "n64").apply { mkdirs() }
        File(n64, "Super Mario 64.z64").writeText("rom")

        get("/collections/apply?directory=${n64.absolutePath}" +
            "&name=Nintendo%2064&shortName=n64&extensions=z64,n64,v64").use { r ->
            assertEquals(200, r.code)
            assertEquals("ok", JSONObject(r.body!!.string()).getString("status"))
        }
        val overlay = File(n64, "zz-pegasusbridge.metadata.pegasus.txt")
        assertTrue(overlay.isFile)
        val text = overlay.readText()
        assertTrue(text.contains("collection: Nintendo 64"), text)
        assertTrue(text.contains("shortname: n64"), text)
        assertTrue(text.contains("extensions: z64, n64, v64"), text)
        // No launch was asked for, so none was invented.
        assertFalse(text.contains("launch:"), text)
        // And the collection is now readable by the same parser Pegasus uses.
        assertEquals("Nintendo 64", MetadataFile.readCollection(n64)?.name)
    }

    /**
     * Without extensions Pegasus finds nothing, so an overlay lacking them is a
     * file that looks like it worked and did not.
     */
    @Test fun `apply refuses to declare a collection with no extensions`() {
        val n64 = File(romRoot, "n64").apply { mkdirs() }
        get("/collections/apply?directory=${n64.absolutePath}&name=Nintendo%2064").use { r ->
            assertEquals(400, r.code)
            assertTrue(JSONObject(r.body!!.string()).getString("error").contains("extensions"))
        }
        assertFalse(File(n64, "zz-pegasusbridge.metadata.pegasus.txt").exists())
    }

    // ── a page of another site ──────────────────────────────────────────────

    /**
     * The request that was sent to a daemon in a sandbox before the server
     * looked at where a request came from: `/emulators/apply`, as a browser
     * sends it for a page of another site. It was answered 200 and the file was
     * there, with a command Pegasus runs when a game is started.
     *
     * A page's picture or fetch, a name rebound to this machine, and the three
     * ways Chrome 155 still got the file written while `Sec-Fetch-Site: none`
     * and an `Origin` on this machine were served: a prefetch, a redirect from
     * a site the person opened, a WebSocket from a page on another port. Then
     * the same request with none of a browser's headers, as the theme sends
     * it, which has to go on writing.
     */
    @Test fun `a page of another site cannot plant a launch command`() {
        val nes = File(romRoot, "nes").apply { mkdirs() }
        File(nes, "Super Mario Bros.nes").writeText("rom")
        val overlay = File(nes, "zz-pegasusbridge.metadata.pegasus.txt")
        val request = "/emulators/apply?directory=${nes.absolutePath}&launch=planted%20%7Bfile.path%7D"

        val asked = listOf(
            listOf("Origin" to "https://attacker.invalid"),
            listOf("Sec-Fetch-Site" to "cross-site"),
            listOf("Host" to "attacker.invalid:${server.port}"),
            listOf("Sec-Purpose" to "prefetch", "Sec-Fetch-Site" to "none", "Sec-Fetch-Mode" to "navigate",
                   "Sec-Fetch-Dest" to "document"),
            listOf("Sec-Fetch-Site" to "none", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-User" to "?1",
                   "Sec-Fetch-Dest" to "document"),
            listOf("Connection" to "Upgrade", "Upgrade" to "websocket", "Origin" to "http://localhost:3000",
                   "Sec-WebSocket-Version" to "13", "Sec-WebSocket-Key" to "QSDwO7KamAKEh6Zj7szF+A=="))
        for (headers in asked) {
            client.newCall(Request.Builder().url(url(request))
                .apply { headers.forEach { (name, value) -> header(name, value) } }.build())
                .execute().use { r ->
                    assertEquals(403, r.code, "$headers")
                    assertEquals(null, r.header("Access-Control-Allow-Origin"), "$headers")
                }
            assertFalse(overlay.exists(), "$headers: the overlay was written")
            assertEquals(listOf("Super Mario Bros.nes"), nes.list()!!.toList(), "$headers")
        }

        get(request).use { r ->
            assertEquals(200, r.code)
            assertEquals("ok", JSONObject(r.body!!.string()).getString("status"))
        }
        assertTrue(overlay.readText().contains("launch: planted {file.path}"), overlay.readText())
    }

    // ── choosing an emulator ────────────────────────────────────────────────

    /**
     * Found on the tablet, by passing a `file=` whose ROM name was empty.
     *
     * The path collapsed to the collection's own directory, `rom != null` held,
     * and `setGame` wrote a per-game preference keyed by exactly the path the
     * `collections` map already used — two entries, same key, one of them
     * meaningless. Existence is deliberately still not required, because a
     * caller may legitimately name a ROM inside an archive that no `isFile`
     * would find; a directory is refused because a directory is never a game.
     */
    @Test fun `select refuses a directory handed in as a game file`() {
        val dir = File(romRoot, "snes").apply { mkdirs() }
        get("/launch/select?emulator=retroarch&file=${dir.absolutePath}").use { r ->
            assertEquals(400, r.code)
            val j = JSONObject(r.body!!.string())
            assertTrue(j.getString("error").contains("is a directory"), j.toString())
        }
        // And nothing was recorded on the way to refusing.
        assertFalse(File(paths.config, "launch-preferences.json").exists())
    }

    @Test fun `clear refuses a directory handed in as a game file`() {
        val dir = File(romRoot, "snes").apply { mkdirs() }
        get("/launch/clear?file=${dir.absolutePath}").use { r ->
            assertEquals(400, r.code)
            assertTrue(JSONObject(r.body!!.string()).getString("error").contains("is a directory"))
        }
    }

    @Test fun `video resolve refuses a non-https url`() {
        get("/video/resolve?url=http%3A%2F%2Fexample.com%2Fv.mp4").use { r ->
            assertEquals(400, r.code)
            assertTrue(JSONObject(r.body!!.string()).getString("error").contains("https"))
        }
        get("/video/resolve").use { r -> assertEquals(400, r.code) }
    }

    @Test fun `video search rejects an over-long query`() {
        get("/video/search?q=" + "x".repeat(300)).use { r -> assertEquals(400, r.code) }
        get("/video/search").use { r -> assertEquals(400, r.code) }
    }

    // ── scan: the one asynchronous verb ─────────────────────────────────────

    @Test fun `scan starts a job and the job reports completion with a summary`() {
        File(romRoot, "nes").mkdirs()
        File(romRoot, "nes/Super Mario Bros. (World).nes").writeText("hash-smb")
        File(romRoot, "nes/Unknown.nes").writeText("hash-nope")

        val jobId = get("/scan?roots=" + romRoot.absolutePath).use { r ->
            val j = JSONObject(r.body!!.string())
            assertEquals("started", j.getString("status"))
            j.getString("jobId")
        }

        var body = JSONObject()
        for (attempt in 0 until 100) {
            Thread.sleep(200)
            body = get("/jobs/$jobId").use { JSONObject(it.body!!.string()) }
            if (body.getString("status") != "running") break
        }

        assertEquals("done", body.getString("status"), "job did not finish: $body")
        val result = body.getJSONObject("result")
        assertEquals(2, result.getInt("total"))
        assertEquals(1, result.getInt("newEntries"))
        assertEquals(1, result.getInt("indexed"))
        assertEquals(1.0, body.getDouble("progress"))
    }

    // The theme picks the id and persists it, so a reload can re-attach to a scan
    // that is still running. The daemon has to adopt it rather than mint its own.
    @Test fun `scan adopts a client-supplied job id`() {
        File(romRoot, "nes").mkdirs()
        File(romRoot, "nes/Game.nes").writeText("hash-smb")

        val id = get("/scan?jobId=my_own_id&roots=" + romRoot.absolutePath)
            .use { JSONObject(it.body!!.string()).getString("jobId") }
        assertEquals("my_own_id", id)
        get("/jobs/my_own_id").use { r -> assertEquals(200, r.code) }
    }

    @Test fun `a job id that could escape the directory is refused`() {
        File(romRoot, "nes").mkdirs()
        File(romRoot, "nes/Game.nes").writeText("hash-smb")

        val id = get("/scan?jobId=../../etc/passwd&roots=" + romRoot.absolutePath)
            .use { JSONObject(it.body!!.string()).getString("jobId") }
        assertFalse(id.contains("/"), "path separators must not survive: $id")
        assertFalse(id.contains(".."))
    }

    // The progress popup shows running totals beside the percentage, so they have
    // to be published while the job runs, not only in the final result.
    @Test fun `scan publishes running counters, not just the final result`() {
        File(romRoot, "nes").mkdirs()
        repeat(6) { File(romRoot, "nes/Game$it.nes").writeText("hash-smb") }

        get("/scan?jobId=counter_job&roots=" + romRoot.absolutePath).use { it.body!!.string() }

        var sawCounters = false
        for (attempt in 0 until 100) {
            Thread.sleep(100)
            val j = get("/jobs/counter_job").use { JSONObject(it.body!!.string()) }
            if (j.has("newEntries") && j.has("cachedHits") && j.has("skippedPlatforms"))
                sawCounters = true
            if (j.getString("status") != "running") break
        }
        assertTrue(sawCounters, "counters were never published alongside progress")
    }

    @Test fun `scan validates roots`() {
        get("/scan").use { r -> assertEquals(400, r.code) }
        get("/scan?roots=").use { r -> assertEquals(400, r.code) }
    }

    @Test fun `an unknown job id is a 404`() {
        get("/jobs/does-not-exist").use { r -> assertEquals(404, r.code) }
    }

    // A file-polling client must never see the done marker before the result.
    @Test fun `a finished job leaves a non-empty done marker and no pending file`() {
        File(romRoot, "nes").mkdirs()
        File(romRoot, "nes/Game.nes").writeText("hash-smb")

        val jobId = get("/scan?roots=" + romRoot.absolutePath)
            .use { JSONObject(it.body!!.string()).getString("jobId") }

        for (attempt in 0 until 100) {
            Thread.sleep(200)
            val s = get("/jobs/$jobId").use { JSONObject(it.body!!.string()).getString("status") }
            if (s != "running") break
        }

        val marker = paths.done(jobId)
        assertTrue(marker.isFile, "done marker missing")
        assertTrue(marker.length() > 0, "marker must carry content to be visible to QML")
        assertFalse(paths.pending(jobId).exists(), "pending must be cleared on completion")
    }
}
