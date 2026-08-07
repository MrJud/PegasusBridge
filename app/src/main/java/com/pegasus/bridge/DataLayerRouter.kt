package com.pegasus.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.core.SchemaVersion
import com.pegasus.bridge.hasher.HasherService
import com.pegasus.bridge.media.MediaService
import com.pegasus.bridge.ra.RaConsoleMap
import com.pegasus.bridge.ra.RaService
import com.pegasus.bridge.video.VideoPlayerActivity
import com.pegasus.bridge.video.VideoService
import org.json.JSONObject

/**
 * Thin activity, no UI — receives every `pegasus-data://<verb>?…` intent URI and dispatches
 * to the correct Service (or Activity for `play-video`).
 *
 * Contract: writes `pending/{jobId}.json` + `done/{jobId}.done` before terminating for any
 * verb it can't or won't handle, so the theme's polling loop never hangs.
 *
 * Phase 7 note: legacy schemes `rahasher://` and `pegasus-video://` have been removed from
 * the manifest; the theme side must now use only `pegasus-data://`.
 */
class DataLayerRouter : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        dispatch(uri)
        finish()
    }

    private fun dispatch(uri: Uri) {
        val verb  = uri.host ?: run { finish(); return }
        val jobId = uri.getQueryParameter("jobId") ?: java.util.UUID.randomUUID().toString()

        when (verb) {
            "scan" -> {
                val roots = uri.getQueryParameter("roots") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, HasherService::class.java).apply {
                    putExtra(HasherService.EXTRA_ROOTS,  roots)
                    putExtra(HasherService.EXTRA_JOB_ID, jobId)
                })
            }
            "scrape-media" -> {
                val gameId   = uri.getQueryParameter("gameId")   ?: return stub(jobId, verb)
                val title    = uri.getQueryParameter("title")    ?: return stub(jobId, verb)
                val platform = uri.getQueryParameter("platform") ?: ""
                startForegroundService(Intent(this, MediaService::class.java).apply {
                    putExtra(MediaService.EXTRA_VERB, MediaService.VERB_SCRAPE_MEDIA)
                    putExtra("gameId",                gameId)
                    putExtra("title",                 title)
                    putExtra("platform",              platform)
                    putExtra(MediaService.EXTRA_JOB_ID, jobId)
                })
            }
            "scrape-source" -> {
                val source = uri.getQueryParameter("source") ?: return stub(jobId, verb)
                val op     = uri.getQueryParameter("op")     ?: return stub(jobId, verb)
                // Raccogli tutti gli altri param URL (escluso jobId/source/op) in un Bundle.
                val paramsBundle = Bundle().apply {
                    for (name in uri.queryParameterNames) {
                        if (name == "jobId" || name == "source" || name == "op") continue
                        uri.getQueryParameter(name)?.let { putString(name, it) }
                    }
                }
                startForegroundService(Intent(this, MediaService::class.java).apply {
                    putExtra(MediaService.EXTRA_VERB,   MediaService.VERB_SCRAPE_SOURCE)
                    putExtra(MediaService.EXTRA_JOB_ID, jobId)
                    putExtra(MediaService.EXTRA_SOURCE, source)
                    putExtra(MediaService.EXTRA_OP,     op)
                    putExtra(MediaService.EXTRA_PARAMS, paramsBundle)
                })
            }
            "refresh-ra-profile" -> {
                val user = uri.getQueryParameter("user") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,   RaService.VERB_PROFILE)
                    putExtra(RaService.EXTRA_USER,   user)
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            "refresh-ra" -> {
                val gameId = uri.getQueryParameter("gameId")?.toIntOrNull() ?: return stub(jobId, verb)
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,    RaService.VERB_DETAIL)
                    putExtra(RaService.EXTRA_GAME_ID, gameId)
                    putExtra(RaService.EXTRA_JOB_ID,  jobId)
                })
            }
            "search-ra-games" -> {
                // Either a numeric consoleId or a Pegasus platform short name:
                // resolving the name here is what lets a theme carry no console
                // table of its own. Same contract as the daemon's /ra/search.
                val consoleId = uri.getQueryParameter("consoleId")?.toIntOrNull()
                    ?: uri.getQueryParameter("platform")?.let { RaConsoleMap.consoleId(it) }
                    ?: return stub(jobId, verb)
                if (consoleId <= 0) return stub(jobId, verb)
                val term = uri.getQueryParameter("term") ?: ""
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,       RaService.VERB_SEARCH_GAMES)
                    putExtra(RaService.EXTRA_CONSOLE_ID, consoleId)
                    putExtra(RaService.EXTRA_TERM,       term)
                    putExtra(RaService.EXTRA_WITH_ACH,   uri.getQueryParameter("withAchievements") == "1")
                    putExtra(RaService.EXTRA_LIMIT,
                             uri.getQueryParameter("limit")?.toIntOrNull() ?: 200)
                    putExtra(RaService.EXTRA_JOB_ID,     jobId)
                })
            }
            "match-ra" -> {
                val title = uri.getQueryParameter("title") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,     RaService.VERB_MATCH)
                    putExtra(RaService.EXTRA_TERM,     title)
                    putExtra(RaService.EXTRA_PLATFORM, uri.getQueryParameter("platform") ?: "")
                    uri.getQueryParameter("file")?.let { putExtra(RaService.EXTRA_FILE, it) }
                    putExtra(RaService.EXTRA_JOB_ID,   jobId)
                })
            }
            "ra-consoles" -> {
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,   RaService.VERB_CONSOLES)
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            "credentials-status" -> {
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,   RaService.VERB_CRED_STATUS)
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            "clear-credentials" -> {
                val block = uri.getQueryParameter("block") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB,   RaService.VERB_CRED_CLEAR)
                    putExtra(RaService.EXTRA_BLOCK,  block)
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            "set-credentials" -> {
                // Every field is optional — the theme saves one settings entry at
                // a time and the service merges it into credentials.json. Reject
                // only a request that carries nothing at all.
                val user   = uri.getQueryParameter("user")
                val apiKey = uri.getQueryParameter("apiKey")
                val sgdb   = uri.getQueryParameter("sgdbKey")
                val igdbId = uri.getQueryParameter("igdbClientId")
                val igdbSc = uri.getQueryParameter("igdbClientSecret")
                val ssDev  = uri.getQueryParameter("ssDevId")
                val ssDevP = uri.getQueryParameter("ssDevPassword")
                val ssUser = uri.getQueryParameter("ssUser")
                val ssPass = uri.getQueryParameter("ssPassword")
                val ssSoft = uri.getQueryParameter("ssSoftname")
                if (listOfNotNull(user, apiKey, sgdb, igdbId, igdbSc,
                                  ssDev, ssDevP, ssUser, ssPass, ssSoft).all { it.isEmpty() })
                    return stub(jobId, verb)

                startForegroundService(Intent(this, RaService::class.java).apply {
                    putExtra(RaService.EXTRA_VERB, RaService.VERB_SET_CREDENTIALS)
                    user?.let   { putExtra(RaService.EXTRA_USER, it) }
                    apiKey?.let { putExtra(RaService.EXTRA_API_KEY, it) }
                    sgdb?.let   { putExtra(RaService.EXTRA_SGDB_KEY, it) }
                    igdbId?.let { putExtra(RaService.EXTRA_IGDB_CLIENT_ID, it) }
                    igdbSc?.let { putExtra(RaService.EXTRA_IGDB_CLIENT_SECRET, it) }
                    ssDev?.let  { putExtra(RaService.EXTRA_SS_DEV_ID, it) }
                    ssDevP?.let { putExtra(RaService.EXTRA_SS_DEV_PASSWORD, it) }
                    ssUser?.let { putExtra(RaService.EXTRA_SS_USER, it) }
                    ssPass?.let { putExtra(RaService.EXTRA_SS_PASSWORD, it) }
                    ssSoft?.let { putExtra(RaService.EXTRA_SS_SOFTNAME, it) }
                    putExtra(RaService.EXTRA_JOB_ID, jobId)
                })
            }
            "search-video" -> {
                val query = uri.getQueryParameter("q") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, VideoService::class.java).apply {
                    putExtra(VideoService.EXTRA_VERB,   VideoService.VERB_SEARCH)
                    putExtra(VideoService.EXTRA_QUERY,  query)
                    putExtra(VideoService.EXTRA_JOB_ID, jobId)
                })
            }
            "play-video" -> {
                // Direct launch — VideoPlayerActivity reads `url` / `title` / `gameKey`
                // from the original URI via UriParams.fromIntentData.
                startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                    data = uri
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                // No pending/done for play-video — it's a fire-and-forget UI launch.
            }
            "download-video" -> {
                val url     = uri.getQueryParameter("url")     ?: return stub(jobId, verb)
                val gameKey = uri.getQueryParameter("gameKey") ?: return stub(jobId, verb)
                startForegroundService(Intent(this, VideoService::class.java).apply {
                    putExtra(VideoService.EXTRA_VERB,     VideoService.VERB_DOWNLOAD)
                    putExtra(VideoService.EXTRA_URL,      url)
                    putExtra(VideoService.EXTRA_GAME_KEY, gameKey)
                    putExtra(VideoService.EXTRA_JOB_ID,   jobId)
                })
            }
            else -> stub(jobId, verb)
        }
    }

    /**
     * Writes pending+done stub (no real work) so the theme's polling loop doesn't hang
     * on malformed URIs or truly unknown verbs.
     */
    private fun stub(jobId: String, verb: String) {
        Paths.ensureAll()
        val now = System.currentTimeMillis() / 1000L
        Paths.pending(jobId).writeText(
            JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("jobId",     jobId)
                .put("verb",      verb)
                .put("status",    "error")
                .put("error",     "verb not implemented or missing required params")
                .put("startedAt", now)
                .put("updatedAt", now)
                .toString()
        )
        Paths.markDone(jobId)
    }
}
