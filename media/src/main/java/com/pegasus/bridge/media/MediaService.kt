package com.pegasus.bridge.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.core.SchemaVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

// ForegroundService che risponde al verb pegasus-data://scrape-media
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
        val gameId   = intent?.getStringExtra("gameId")   ?: run { stopSelf(startId); return START_NOT_STICKY }
        val title    = intent.getStringExtra("title")     ?: run { stopSelf(startId); return START_NOT_STICKY }
        val platform = intent.getStringExtra("platform")  ?: ""
        val jobId    = intent.getStringExtra("jobId")     ?: gameId

        scope.launch {
            Paths.ensureAll()
            writePending(jobId, "running")
            try {
                val payload = MediaAggregator.scrape(gameId, title, platform)
                val outFile = Paths.media(gameId)
                outFile.writeText(payload.toJson().toString(2))
                touchDone(jobId)
            } catch (e: Exception) {
                writePending(jobId, "error", e.message)
                touchDone(jobId)
            } finally {
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

    private fun writePending(jobId: String, status: String, error: String? = null) {
        val now = System.currentTimeMillis() / 1000L
        val j = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("jobId",      jobId)
            .put("verb",       "scrape-media")
            .put("status",     status)
            .put("startedAt",  now)
            .put("updatedAt",  now)
        if (error != null) j.put("error", error)
        Paths.pending(jobId).writeText(j.toString())
    }

    private fun touchDone(jobId: String) {
        Paths.done(jobId).createNewFile()
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

    companion object {
        private const val CHANNEL_ID      = "pegasus_bridge"
        private const val NOTIFICATION_ID = 1001
    }
}
