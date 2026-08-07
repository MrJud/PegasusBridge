package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.SchemaVersion
import com.pegasus.bridge.daemon.MicroHttpServer.Request
import com.pegasus.bridge.daemon.MicroHttpServer.Response
import com.pegasus.bridge.hasher.RomScanPipeline
import com.pegasus.bridge.ra.RaConsoleMap
import com.pegasus.bridge.ra.RaMatcher
import com.pegasus.bridge.ra.RaSync
import com.pegasus.bridge.scrapers.ScrapeSourceDispatcher
import com.pegasus.bridge.scrapers.ScreenScraperClient
import com.pegasus.bridge.video.SearchCallback
import com.pegasus.bridge.video.TrailerDownloader
import com.pegasus.bridge.video.VideoRequest
import com.pegasus.bridge.video.YouTubeResolver
import com.pegasus.bridge.video.YouTubeSearcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Maps HTTP endpoints onto the shared modules.
 *
 * Everything that finishes in seconds answers **synchronously**: the caller does
 * one request and gets the JSON in the response. That is the whole reason for
 * the HTTP contract — a theme needs no job ids, no polling and no knowledge of
 * the directory layout. Only a ROM scan, which can run for minutes, returns a
 * job id to poll.
 */
class BridgeRouter(
    private val paths: BridgePaths,
    private val config: Config,
    private val jobs: JobRegistry,
    /** Supplied lazily so a scan is only wired up where a hasher exists. */
    private val scanPipeline: (() -> RomScanPipeline)? = null,
    private val scrapers: ScrapeSourceDispatcher = ScrapeSourceDispatcher(config),
    private val raSync: RaSync = RaSync(paths, config)
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun handle(req: Request): Response = when (req.path) {
        "/", "/health"    -> health()
        "/scrape"         -> scrape(req)
        "/ra/search"      -> raSearch(req)
        "/ra/match"       -> raMatch(req)
        "/ra/consoles"    -> raConsoles()
        "/ra/profile"     -> raProfile(req)
        "/ra/game"        -> raGame(req)
        "/video/search"   -> videoSearch(req)
        "/video/resolve"  -> videoResolve(req)
        "/video/download" -> videoDownload(req)
        "/credentials"        -> credentials(req)
        "/credentials/status" -> credentialsStatus()
        "/credentials/clear"  -> credentialsClear(req)
        "/screenscraper/user" -> screenScraperUser()
        "/scan"           -> scan(req)
        else              -> jobStatus(req) ?: Response.notFound("no endpoint ${req.path}")
    }

    // ── Meta ────────────────────────────────────────────────────────────────

    private fun health() = Response.json(JSONObject()
        .put("schemaVersion", SchemaVersion.CURRENT)
        .put("status", "ok")
        .put("dataRoot", paths.root.absolutePath)
        // Booleans, as they have always been: /health is the contract other
        // themes probe, and the richer shape lives at /credentials/status.
        .put("credentials", JSONObject()
            .put("ra",          config.load().ra?.user?.isNotEmpty() == true)
            .put("steamGridDb", config.load().steamGridDb != null)
            .put("igdb",        config.load().igdb != null))
        .toString())

    // ── Scraping ────────────────────────────────────────────────────────────

    private fun scrape(req: Request): Response {
        val source = req.param("source") ?: return Response.badRequest("missing source")
        val op     = req.param("op")     ?: return Response.badRequest("missing op")
        val params = req.query.filterKeys { it != "source" && it != "op" }

        return try {
            val result = scrapers.run(source, op, params)
            val payload = JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("source", source)
                .put("op", op)
                .put("status", if (result.isEmpty()) "no_results" else "ok")
                .put("fetchedAt", BridgePaths.epochSeconds())
                .put("results", result.results)
            Response.json(payload.toString())
        } catch (e: IllegalArgumentException) {
            Response.badRequest(e.message ?: "bad request")
        } catch (e: IllegalStateException) {
            // Missing credentials: the caller can act on this, so say so plainly.
            Response.badRequest(e.message ?: "missing credentials")
        } catch (e: Exception) {
            BridgeLog.e(TAG, "scrape $source/$op failed", e)
            Response.serverError(e.message ?: e.javaClass.simpleName)
        }
    }

    // ── RetroAchievements ───────────────────────────────────────────────────

    /**
     * Games on a console, filtered by title.
     *
     * Takes either a numeric `consoleId` or a Pegasus `platform` short name —
     * the latter so a theme needs no console table of its own. `limit` exists
     * because a caller that filters locally as the user types wants the whole
     * catalogue, not a page of it.
     */
    private fun raSearch(req: Request): Response {
        val consoleId = req.intParam("consoleId")
            ?: req.param("platform")?.let { RaConsoleMap.consoleId(it) }
            ?: return Response.badRequest("missing consoleId or platform")
        if (consoleId <= 0) return Response.badRequest("no RetroAchievements console for that platform")

        val term = req.param("term").orEmpty()
        // The theme's manual "link a game" list only wants games that have
        // achievements; a catalogue match wants everything.
        val onlyWithAch = req.param("withAchievements") == "1"
        val limit = req.intParam("limit")?.coerceIn(1, 5000) ?: 200

        val jobId = jobs.newId("rasearch")
        return when (val r = raSync.searchGames(consoleId, term, jobId, onlyWithAch, limit)) {
            is RaSync.Result.Ok      -> Response.json(paths.searchRa(jobId).readText())
            is RaSync.Result.Skipped -> Response.badRequest(r.reason)
            is RaSync.Result.Failed  -> Response.badRequest(r.reason)
        }
    }

    private fun raProfile(req: Request): Response {
        val user = req.param("user")
            ?: config.load().ra?.user?.takeIf { it.isNotEmpty() }
            ?: return Response.badRequest("missing user")
        return when (val r = runBlocking { raSync.refreshProfile(user) }) {
            is RaSync.Result.Ok      -> Response.json(paths.profile(user).readText())
            is RaSync.Result.Skipped -> Response.badRequest(r.reason)
            is RaSync.Result.Failed  -> Response.serverError(r.reason)
        }
    }

    private fun raGame(req: Request): Response {
        val gameId = req.intParam("gameId") ?: return Response.badRequest("missing gameId")
        return when (val r = raSync.refreshGameDetail(gameId)) {
            is RaSync.Result.Ok      -> Response.json(paths.metadata(gameId.toString()).readText())
            is RaSync.Result.Skipped -> Response.badRequest(r.reason)
            is RaSync.Result.Failed  -> Response.serverError(r.reason)
        }
    }


    /**
     * Which RetroAchievements game a Pegasus game is.
     *
     * The endpoint that lets a theme stop carrying a fuzzy matcher and a console
     * table of its own: it asks the question and gets an answer, instead of
     * assembling one from primitives that have to stay in step with the Bridge.
     */
    private fun raMatch(req: Request): Response {
        val title = req.param("title") ?: return Response.badRequest("missing title")
        val m = raSync.matchGame(title, req.param("platform").orEmpty(), req.param("file"))
        return Response.json(RaMatcher.toJson(m)
            .put("schemaVersion", SchemaVersion.CURRENT).toString())
    }

    private fun raConsoles(): Response = Response.json(JSONObject()
        .put("schemaVersion", SchemaVersion.CURRENT)
        .put("status", "ok")
        .put("table", raSync.consoleTable())
        .toString())

    /** Which credentials are set, and the RA username. Never the secrets. */
    private fun credentialsStatus(): Response = Response.json(JSONObject()
        .put("schemaVersion", SchemaVersion.CURRENT)
        .put("status", "ok")
        .put("credentials", config.status())
        .toString())

    /**
     * Proves the ScreenScraper credentials and reports the quota.
     *
     * Synchronous, like the other credential verbs: it is one cheap call, and a settings
     * screen asking "are these right" should not have to poll a job file for it. This is
     * the ScreenScraper equivalent of `/ra/profile` — the only call whose failure means
     * "your credentials are wrong" rather than "no such game", so it is what the login
     * card should ask before claiming a green tick.
     */
    private fun screenScraperUser(): Response {
        val r = ScreenScraperClient.userInfo(config)
        val q = r.getOrElse {
            // 200 with status "error", not an HTTP failure: the request reached us and
            // was answered — what failed is upstream, and the message is the useful part.
            return Response.json(JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("status", "error")
                .put("error", it.message ?: "ScreenScraper refused the credentials")
                .toString())
        }
        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("user", q.user)
            .put("maxThreads", q.maxThreads)
            .put("requestsToday", q.requestsToday)
            .put("maxRequestsPerDay", q.maxRequestsPerDay)
            .put("maxRequestsPerMinute", q.maxRequestsPerMinute)
            .toString())
    }

    /** Forgets one block — what "log out" means once the Bridge holds the keys. */
    private fun credentialsClear(req: Request): Response {
        val block = req.param("block") ?: return Response.badRequest("missing block")
        if (!config.clearBlock(block)) return Response.badRequest("unknown block '$block'")
        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("cleared", block)
            .put("credentials", config.status())
            .toString())
    }

    // ── Video ───────────────────────────────────────────────────────────────

    private fun videoSearch(req: Request): Response {
        val q = req.param("q") ?: return Response.badRequest("missing q")
        if (q.length > 200) return Response.badRequest("query too long")
        return try {
            val items = runBlocking { YouTubeSearcher.search(q, limit = 20) }
            Response.json(SearchCallback.okJson(q, items))
        } catch (t: Throwable) {
            BridgeLog.e(TAG, "video search failed", t)
            Response.json(SearchCallback.errorJson(q, t.message ?: "unknown"))
        }
    }

    /**
     * Resolves a YouTube page URL to playable stream URLs.
     *
     * This is what replaces Android's fullscreen player Activity: the theme
     * already has a media player, so the daemon hands back a URL instead of
     * taking over the screen.
     */
    private fun videoResolve(req: Request): Response {
        val url = req.param("url") ?: return Response.badRequest("missing url")
        val request = VideoRequest.of(url) ?: return Response.badRequest("url must be https")
        return try {
            val s = runBlocking { YouTubeResolver.resolve(request.url) }
            Response.json(JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("status", "ok")
                .put("title", s.title)
                .put("durationSec", s.durationSec)
                .put("progressive", s.progressive?.let {
                    JSONObject().put("url", it.url)
                        .put("mimeType", it.mimeType)
                        .put("resolution", it.resolutionLabel)
                } ?: JSONObject.NULL)
                .put("dashManifest", s.dashManifestUrl ?: JSONObject.NULL)
                .toString())
        } catch (t: Throwable) {
            BridgeLog.w(TAG, "resolve failed for ${request.url}: ${t.message}")
            Response.json(JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("status", "error")
                .put("error", t.message ?: t.javaClass.simpleName)
                .toString())
        }
    }

    /**
     * Caches a trailer locally. Asynchronous like [scan], because a download can
     * run for minutes; progress arrives through `/jobs/{id}`.
     */
    private fun videoDownload(req: Request): Response {
        val url = req.param("url") ?: return Response.badRequest("missing url")
        val request = VideoRequest.of(url) ?: return Response.badRequest("url must be https")
        val key = VideoRequest.sanitizeGameKey(req.param("gameKey"))
        if (key.isEmpty()) return Response.badRequest("missing or unusable gameKey")

        val target = File(paths.download, "$key.mp4")
        val job = jobs.createWithClientId(req.param("jobId"), "download-video")

        scope.launch {
            try {
                TrailerDownloader.download(request.url, target, paths.download(job.id))
                if (target.isFile && target.length() > 0) {
                    jobs.finish(job, JSONObject()
                        .put("localPath", target.absolutePath)
                        .put("bytes", target.length()))
                } else {
                    jobs.fail(job, "download produced no file")
                }
            } catch (t: Throwable) {
                BridgeLog.e(TAG, "download failed", t)
                jobs.fail(job, t.message ?: t.javaClass.simpleName)
            }
        }
        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "started")
            .put("jobId", job.id)
            .toString())
    }

    // ── Credentials ─────────────────────────────────────────────────────────

    private fun credentials(req: Request): Response {
        // Accepts a JSON body or plain query params, so a theme can use whichever
        // its HTTP client makes easy.
        val fields: Map<String, String> = if (req.body.isNotBlank()) {
            try {
                val j = JSONObject(req.body)
                j.keys().asSequence().associateWith { j.optString(it) }
            } catch (e: Exception) {
                return Response.badRequest("body is not valid JSON")
            }
        } else req.query

        val known = listOf("user", "apiKey", "sgdbKey", "igdbClientId", "igdbClientSecret",
                           "ssDevId", "ssDevPassword", "ssUser", "ssPassword", "ssSoftname")
        if (known.none { !fields[it].isNullOrBlank() })
            return Response.badRequest("no credential fields supplied")

        config.writeCredentials(
            raUser           = fields["user"],
            raApiKey         = fields["apiKey"],
            sgdbKey          = fields["sgdbKey"],
            igdbClientId     = fields["igdbClientId"],
            igdbClientSecret = fields["igdbClientSecret"],
            ssDevId          = fields["ssDevId"],
            ssDevPassword    = fields["ssDevPassword"],
            ssUser           = fields["ssUser"],
            ssPassword       = fields["ssPassword"],
            ssSoftname       = fields["ssSoftname"]
        )
        // Report which blocks changed, never the values.
        val updated = JSONArray().apply {
            if (!fields["user"].isNullOrBlank() || !fields["apiKey"].isNullOrBlank()) put("ra")
            if (!fields["sgdbKey"].isNullOrBlank()) put("steamGridDb")
            if (!fields["igdbClientId"].isNullOrBlank() || !fields["igdbClientSecret"].isNullOrBlank()) put("igdb")
            if (listOf("ssDevId", "ssDevPassword", "ssUser", "ssPassword", "ssSoftname")
                    .any { !fields[it].isNullOrBlank() }) put("screenScraper")
        }
        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "ok")
            .put("updated", updated)
            .toString())
    }

    // ── Scan (the one asynchronous verb) ────────────────────────────────────

    private fun scan(req: Request): Response {
        val make = scanPipeline ?: return Response.serverError("no ROM hasher available")
        val roots = req.param("roots")?.split('|', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: return Response.badRequest("missing roots")
        if (roots.isEmpty()) return Response.badRequest("no usable root in roots")

        // Honour a client-supplied id so a theme that reloads mid-scan can keep
        // polling the same job instead of losing track of it.
        val job = jobs.createWithClientId(req.param("jobId"), "scan")
        scope.launch {
            try {
                val summary = make().scan(roots) { p ->
                    jobs.update(job, p.fraction, "[${p.processed}/${p.total}] ${p.currentFile}",
                        JSONObject()
                            .put("newEntries", p.newEntries)
                            .put("cachedHits", p.cachedHits)
                            .put("skippedPlatforms", p.skippedPlatforms))
                }
                jobs.finish(job, JSONObject()
                    .put("total", summary.total)
                    .put("newEntries", summary.newEntries)
                    .put("cachedHits", summary.cachedHits)
                    .put("skippedPlatforms", summary.skippedPlatforms)
                    .put("indexed", summary.indexed))
            } catch (t: Throwable) {
                BridgeLog.e(TAG, "scan failed", t)
                jobs.fail(job, t.message ?: t.javaClass.simpleName)
            }
        }
        return Response.json(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("status", "started")
            .put("jobId", job.id)
            .toString())
    }

    /** `/jobs/{id}` — the only polling endpoint the contract needs. */
    private fun jobStatus(req: Request): Response? {
        if (!req.path.startsWith("/jobs/")) return null
        val id = req.path.removePrefix("/jobs/")
        val job = jobs.get(id) ?: return Response.notFound("unknown job $id")
        return Response.json(job.toJson().toString())
    }

    private companion object {
        const val TAG = "BridgeRouter"
    }
}
