package com.pegasus.bridge.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.pegasus.bridge.core.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service handling the two non-UI video verbs:
 *   - `search-video`   → writes `/sdcard/PegasusData/search/{jobId}.json`
 *   - `download-video` → writes `/sdcard/PegasusData/download/{jobId}.json`
 *     and saves the video to `/sdcard/PegasusData/download/<gameKey>.mp4`
 *
 * The `play-video` verb is handled directly by [VideoPlayerActivity] (no service needed).
 */
class VideoService : Service() {

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Video scraping…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val verb  = intent?.getStringExtra(EXTRA_VERB)  ?: run { stopSelf(startId); return START_NOT_STICKY }
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: run { stopSelf(startId); return START_NOT_STICKY }

        scope.launch {
            Paths.ensureAll()
            try {
                when (verb) {
                    VERB_SEARCH   -> handleSearch(intent, jobId)
                    VERB_DOWNLOAD -> handleDownload(intent, jobId)
                    else          -> Log.w(TAG, "Unknown verb: $verb")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "VideoService error for verb=$verb", t)
            } finally {
                Paths.markDone(jobId)
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

    private suspend fun handleSearch(intent: Intent, jobId: String) {
        val query = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (query.isEmpty() || query.length > 200) {
            Log.w(TAG, "Invalid query: len=${query.length}")
            return
        }

        val outFile = Paths.search(jobId)
        val json = try {
            val items = YouTubeSearcher.search(query, limit = 20)
            SearchCallback.okJson(query, items)
        } catch (t: Throwable) {
            Log.e(TAG, "Search failed", t)
            SearchCallback.errorJson(query, t.message ?: "unknown")
        }
        SearchCallback.atomicWrite(outFile, json)
        Log.i(TAG, "Search done: q='$query' → ${outFile.path} (${json.length}b)")
    }

    private suspend fun handleDownload(intent: Intent, jobId: String) {
        val url     = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val gameKey = intent.getStringExtra(EXTRA_GAME_KEY)?.trim().orEmpty()

        if (!url.startsWith("https://", ignoreCase = true)) {
            Log.w(TAG, "Rejected non-HTTPS URL: $url")
            return
        }
        val sanitizedKey = sanitizeGameKey(gameKey)
        if (sanitizedKey.isEmpty()) {
            Log.w(TAG, "Rejected empty/invalid gameKey: $gameKey")
            return
        }

        val callbackFile = Paths.download(jobId)
        val videoFile    = File(Paths.DOWNLOAD, "$sanitizedKey.mp4")

        Log.i(TAG, "Download: url=$url key=$sanitizedKey → ${videoFile.path}")
        TrailerDownloader.download(
            url          = url,
            outVideoFile = videoFile,
            callbackFile = callbackFile
        )
    }

    /**
     * Sanitizes gameKey to `[a-z0-9|]*` (pipes replaced with underscore) so it's safe as a
     * filename component. Mirrors the QML `Utils.gameKey()` normalization.
     */
    private fun sanitizeGameKey(raw: String): String {
        if (raw.isEmpty() || raw.length > 200) return ""
        return raw.lowercase().replace(Regex("[^a-z0-9|]"), "").replace('|', '_')
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Video Scraper", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pegasus Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()

    companion object {
        private const val TAG             = "VideoService"
        private const val CHANNEL_ID      = "video_scraper"
        private const val NOTIFICATION_ID = 4

        const val EXTRA_VERB     = "verb"
        const val EXTRA_JOB_ID   = "jobId"
        const val EXTRA_QUERY    = "query"
        const val EXTRA_URL      = "url"
        const val EXTRA_GAME_KEY = "gameKey"

        const val VERB_SEARCH    = "search-video"
        const val VERB_DOWNLOAD  = "download-video"
    }
}
