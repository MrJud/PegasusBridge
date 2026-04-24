package com.pegasus.bridge.ra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.core.SchemaVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class RaService : Service() {

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Syncing RetroAchievements…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val verb  = intent?.getStringExtra(EXTRA_VERB)  ?: run { stopSelf(startId); return START_NOT_STICKY }
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: run { stopSelf(startId); return START_NOT_STICKY }

        scope.launch {
            Paths.ensureAll()
            try {
                when (verb) {
                    VERB_PROFILE -> handleProfile(intent, jobId)
                    VERB_DETAIL  -> handleDetail(intent, jobId)
                    else         -> Log.w(TAG, "Unknown verb: $verb")
                }
            } catch (e: Exception) {
                Log.e(TAG, "RaService error for verb=$verb", e)
            } finally {
                Paths.done(jobId).createNewFile()
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

    private fun handleProfile(intent: Intent, jobId: String) {
        val user = intent.getStringExtra(EXTRA_USER) ?: return
        val creds = Config.load().ra ?: return
        if (creds.user.isEmpty() || creds.apiKey.isEmpty()) return

        val now = System.currentTimeMillis() / 1000L

        val summary      = RaApiClient.fetchUserSummary(creds.user, creds.apiKey)
        val completion   = RaApiClient.fetchCompletion(creds.user, creds.apiKey)
        val recentPlayed = RaApiClient.fetchRecent(creds.user, creds.apiKey)

        // Skip write se tutte e tre le chiamate sono tornate vuote (errore network/auth).
        // Evita di sovrascrivere una cache buona con una vuota.
        val allEmpty = summary.length() == 0 && completion.length() == 0 && recentPlayed.length() == 0
        if (allEmpty) {
            Log.w(TAG, "Profile job: all API calls empty — skipping file write for user=$user")
            return
        }

        // Write profile/{user}.json — include summary + recentlyPlayed
        val profileJson = JSONObject()
            .put("schemaVersion",  SchemaVersion.CURRENT)
            .put("fetchedAt",      now)
            .put("summary",        summary)
            .put("recentlyPlayed", recentPlayed)
        Paths.profile(user).writeText(profileJson.toString(2))

        // Write completion/{user}.json
        val completionJson = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("fetchedAt",     now)
            .put("data",          completion)
        Paths.completion(user).writeText(completionJson.toString(2))

        Log.d(TAG, "Profile job done for user=$user")
    }

    private fun handleDetail(intent: Intent, jobId: String) {
        val gameId = intent.getIntExtra(EXTRA_GAME_ID, -1).takeIf { it > 0 } ?: return
        val creds  = Config.load().ra ?: return
        if (creds.user.isEmpty() || creds.apiKey.isEmpty()) return

        val now    = System.currentTimeMillis() / 1000L
        val detail = RaApiClient.fetchGameDetail(gameId, creds.user, creds.apiKey)

        // Skip merge se la chiamata è tornata vuota (errore network/auth) — evita di
        // sovrascrivere un detail valido con uno vuoto.
        if (detail.length() == 0) {
            Log.w(TAG, "Detail job: empty response — skipping merge for gameId=$gameId")
            return
        }

        // Merge into metadata/{gameId}.json — read existing, upsert ra.detail
        val metaFile = Paths.metadata(gameId.toString())
        val meta = if (metaFile.exists()) {
            try { JSONObject(metaFile.readText()) } catch (e: Exception) { JSONObject() }
        } else {
            JSONObject().put("schemaVersion", SchemaVersion.CURRENT)
        }

        val raBlock = meta.optJSONObject("ra") ?: JSONObject()
        raBlock.put("fetchedAt", now)
        raBlock.put("detail",    detail)
        meta.put("ra", raBlock)

        metaFile.writeText(meta.toString(2))
        Log.d(TAG, "Detail job done for gameId=$gameId")
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "RA Sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pegasus Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()

    companion object {
        private const val TAG           = "RaService"
        private const val CHANNEL_ID    = "ra_sync"
        private const val NOTIFICATION_ID = 3

        const val EXTRA_VERB    = "verb"
        const val EXTRA_JOB_ID  = "jobId"
        const val EXTRA_USER    = "user"
        const val EXTRA_GAME_ID = "gameId"

        const val VERB_PROFILE = "refresh-ra-profile"
        const val VERB_DETAIL  = "refresh-ra"
    }
}
