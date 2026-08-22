package com.pegasus.bridge.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.core.SchemaVersion
import com.pegasus.bridge.scrapers.ScreenScraperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

// ForegroundService per tutti i verb di scraping media:
//   • scrape-media  → aggregatore multi-source (usa MediaAggregator → media/{gameId}.json)
//   • scrape-source → dispatcher per-source single-op (→ scrape/{jobId}.json)
//
// Avviato da DataLayerRouter — non chiamare direttamente dal tema.
class MediaService : Service() {

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Scraping media…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val verb  = intent?.getStringExtra(EXTRA_VERB) ?: VERB_SCRAPE_MEDIA
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID) ?: run { stopSelf(startId); return START_NOT_STICKY }

        scope.launch {
            Paths.ensureAll()
            writePending(jobId, verb, "running")
            try {
                when (verb) {
                    VERB_SCRAPE_MEDIA  -> handleScrapeMedia(intent, jobId)
                    VERB_SCRAPE_SOURCE -> handleScrapeSource(intent, jobId)
                    VERB_SS_USER       -> handleScreenScraperUser(jobId)
                    else               -> writeScrapeError(jobId, "unknown", "unknown", "unknown verb: $verb")
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaService error for verb=$verb", e)
                writePending(jobId, verb, "error", e.message)
            } finally {
                Paths.markDone(jobId)
                Paths.pending(jobId).delete()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private fun handleScrapeMedia(intent: Intent, jobId: String) {
        val gameId   = intent.getStringExtra("gameId")  ?: return
        val title    = intent.getStringExtra("title")   ?: return
        val platform = intent.getStringExtra("platform") ?: ""
        val payload  = MediaAggregator.scrape(gameId, title, platform)
        Paths.media(gameId).writeText(payload.toJson().toString(2))
    }

    /**
     * Proves the ScreenScraper credentials and reports the quota.
     *
     * The Android half of the daemon's `/screenscraper/user`, which had no
     * counterpart here: the `ss` ops were `game`, `media` and `systems`, and
     * none of those separates "your password is wrong" from "no such game". A
     * login card needs the one call whose failure means the former, or it shows
     * a green tick for credentials nothing has checked.
     *
     * The answer goes to the `search-ra` slot, where `credentials-status` and
     * `clear-credentials` already put theirs — it is a credentials verb, not a
     * scrape, whatever service happens to own the client.
     *
     * "ok" or "error" in the payload rather than a thrown failure, matching the
     * daemon: the request was answered, and what failed is upstream. The message
     * is the useful part — it says which of the two passwords is in the wrong box.
     */
    private fun handleScreenScraperUser(jobId: String) {
        val payload = ScreenScraperClient.userInfo(Config).fold(
            onSuccess = { q ->
                JSONObject()
                    .put("schemaVersion", SchemaVersion.CURRENT)
                    .put("status", "ok")
                    .put("user", q.user)
                    .put("maxThreads", q.maxThreads)
                    .put("requestsToday", q.requestsToday)
                    .put("maxRequestsPerDay", q.maxRequestsPerDay)
                    .put("maxRequestsPerMinute", q.maxRequestsPerMinute)
            },
            onFailure = { t ->
                JSONObject()
                    .put("schemaVersion", SchemaVersion.CURRENT)
                    .put("status", "error")
                    .put("error", t.message ?: "ScreenScraper refused the credentials")
            })
        Paths.searchRa(jobId).writeText(payload.toString())
    }

    private fun handleScrapeSource(intent: Intent, jobId: String) {
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: return writeScrapeError(jobId, "?", "?", "missing source")
        val op     = intent.getStringExtra(EXTRA_OP)     ?: return writeScrapeError(jobId, source, "?", "missing op")
        val params = intent.getBundleExtra(EXTRA_PARAMS)?.toStringMap() ?: emptyMap()

        val now = System.currentTimeMillis() / 1000L
        try {
            val result = ScrapeSourceDispatcher.run(source, op, params)
            val payload = JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("jobId",     jobId)
                .put("source",    source)
                .put("op",        op)
                .put("status",    if (result.isEmpty()) "no_results" else "ok")
                .put("fetchedAt", now)
                .put("results",   result.results)
            Paths.scrape(jobId).writeText(payload.toString(2))
        } catch (e: Exception) {
            writeScrapeError(jobId, source, op, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun writeScrapeError(jobId: String, source: String, op: String, msg: String) {
        val now = System.currentTimeMillis() / 1000L
        val payload = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("jobId",     jobId)
            .put("source",    source)
            .put("op",        op)
            .put("status",    "error")
            .put("error",     msg)
            .put("fetchedAt", now)
            .put("results",   org.json.JSONArray())
        Paths.scrape(jobId).writeText(payload.toString(2))
    }

    // ── Pending tracking ─────────────────────────────────────────────────────

    private fun writePending(jobId: String, verb: String, status: String, error: String? = null) {
        val now = System.currentTimeMillis() / 1000L
        val j = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("jobId",     jobId)
            .put("verb",      verb)
            .put("status",    status)
            .put("startedAt", now)
            .put("updatedAt", now)
        if (error != null) j.put("error", error)
        Paths.pending(jobId).writeText(j.toString())
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Pegasus Bridge", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pegasus Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()

    private fun Bundle.toStringMap(): Map<String, String> {
        val out = HashMap<String, String>(size())
        for (k in keySet()) {
            getString(k)?.let { out[k] = it }
        }
        return out
    }

    companion object {
        private const val TAG             = "MediaService"
        private const val CHANNEL_ID      = "pegasus_bridge"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_VERB   = "verb"
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_OP     = "op"
        const val EXTRA_PARAMS = "params"

        const val VERB_SCRAPE_MEDIA  = "scrape-media"
        const val VERB_SCRAPE_SOURCE = "scrape-source"
        const val VERB_SS_USER       = "screenscraper-user"
    }
}
