package com.pegasus.bridge.hasher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.FuzzyMatch
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.core.SchemaVersion
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

// Port di com.ra.romhasher.HasherService con le seguenti modifiche rispetto all'originale:
//   - Credenziali da Config.load() invece di SettingsReader (Phase 4)
//   - Progress → pending/{jobId}.json + done/{jobId}.done (contratto Phase 2)
//   - Output → per-game metadata/{gameId}.json invece di ra_hashes_cache.json
//   - FuzzyMatch.makeCacheKey() invece di CacheManager.makeKey() inline
class HasherService : Service() {

    companion object {
        private const val TAG             = "HasherService"
        private const val CHANNEL_ID      = "pegasus_bridge_hasher"
        private const val NOTIFICATION_ID = 2001
        private const val SAVE_INTERVAL   = 30
        private const val IO_COOLDOWN_MS  = 500L
        private const val IO_SEVERE_MS    = 1500L

        const val EXTRA_ROOTS  = "roots"   // CSV of directories
        const val EXTRA_JOB_ID = "jobId"

        @Volatile var isRunning = false
            private set
    }

    private val scope   = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scanJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager

    private var bestObservedKBperMs = 0.0
    private var hashCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "CANCEL") { scanJob?.cancel(); return START_NOT_STICKY }
        if (isRunning) { stopSelf(); return START_NOT_STICKY }

        val rootsCsv = intent?.getStringExtra(EXTRA_ROOTS) ?: run { stopSelf(); return START_NOT_STICKY }
        val jobId    = intent.getStringExtra(EXTRA_JOB_ID) ?: java.util.UUID.randomUUID().toString()
        val roots    = rootsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val creds = Config.load()
        val raUser   = creds.ra?.user   ?: ""
        val raApiKey = creds.ra?.apiKey ?: ""
        if (raUser.isEmpty() || raApiKey.isEmpty()) {
            writeError(jobId, "scan", "Missing RA credentials in credentials.json")
            stopSelf(startId); return START_NOT_STICKY
        }

        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("Scanning ROM folders…", 0, 0))
        acquireWakeLock()

        scanJob = scope.launch {
            try {
                runScan(roots, jobId, raUser, raApiKey)
            } catch (e: CancellationException) {
                Log.i(TAG, "Scan cancelled")
                writeError(jobId, "scan", "Cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                writeError(jobId, "scan", e.message ?: "Unknown error")
            } finally {
                isRunning = false
                releaseWakeLock()
                Paths.done(jobId).createNewFile()
                Paths.pending(jobId).delete()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scanJob?.cancel(); scope.cancel()
        isRunning = false; releaseWakeLock()
        super.onDestroy()
    }

    // ── Pipeline ────────────────────────────────────────────────────────────

    private data class HashJob(
        val file: File, val cacheKey: String, val hash: HashResult,
        val platform: String, val fileKB: Long, val fileSize: Long, val lastModified: Long
    )
    private data class ResultJob(val job: HashJob, val meta: GameMetadata?)

    private suspend fun runScan(roots: List<String>, jobId: String, raUser: String, raApiKey: String) = coroutineScope {
        Paths.ensureAll()
        writePending(jobId, "scan", "running", 0.0, "Scanning ROM folders…")

        val romFiles = RomScanner.scan(roots)
        val total    = romFiles.size
        Log.i(TAG, "Found $total ROM files")
        if (total == 0) { writePending(jobId, "scan", "running", 1.0, "No ROMs found"); return@coroutineScope }

        val apiClient   = RAApiClient(raUser, raApiKey)
        val hashChannel = Channel<HashJob>(capacity = 16)
        val resultChannel = Channel<ResultJob>(capacity = 64)
        val hashDedup   = mutableMapOf<String, GameMetadata?>()

        // Producer: sequential hash with thermal+IO throttle
        val producer = launch(Dispatchers.Default) {
            for (file in romFiles) {
                if (!isActive) break
                val platform = file.parentFile?.name ?: "unknown"
                val cacheKey = FuzzyMatch.makeCacheKey(
                    file.nameWithoutExtension, platform
                )
                val fileSize     = file.length()
                val fileKB       = fileSize / 1024
                val lastModified = file.lastModified()

                val t0     = System.nanoTime()
                val result = try { withContext(Dispatchers.IO) { hashFile(file) } } catch (e: Exception) { null }
                val hashMs = (System.nanoTime() - t0) / 1_000_000

                if (result == null) {
                    resultChannel.send(ResultJob(HashJob(file, cacheKey, HashResult("", 0), platform, fileKB, fileSize, lastModified), null))
                    continue
                }

                val delay = maxOf(ioDelayMs(hashMs, fileKB), thermalDelayMs())
                if (delay > 0) delay(delay)

                hashChannel.send(HashJob(file, cacheKey, result, platform, fileKB, fileSize, lastModified))
            }
            hashChannel.close()
        }

        // Workers: parallel API lookups
        val workers = List(8) {
            launch(Dispatchers.IO) {
                for (hj in hashChannel) {
                    val meta = synchronized(hashDedup) { hashDedup[hj.hash.hash] }
                        ?: apiClient.lookupHash(hj.hash.hash).also {
                            synchronized(hashDedup) { hashDedup[hj.hash.hash] = it }
                        }
                    resultChannel.send(ResultJob(hj, meta))
                }
            }
        }

        launch { producer.join(); workers.forEach { it.join() }; resultChannel.close() }

        // Collector: write per-game metadata/{gameId}.json
        var processed = 0; var newEntries = 0
        for (r in resultChannel) {
            if (r.meta != null && r.meta.gameId > 0) {
                writeMetadata(r.job, r.meta)
                newEntries++
            }
            processed++
            if (processed % 5 == 0 || processed == total) {
                val pct = processed.toDouble() / total
                writePending(jobId, "scan", "running", pct,
                    "[$processed/$total] ${r.job.file.name}")
                updateNotification("[$processed/$total] ${r.job.file.name}", processed, total)
            }
        }

        writePending(jobId, "scan", "running", 1.0, "Done — $newEntries new games found")
        Log.i(TAG, "Scan complete: $processed processed, $newEntries written")
    }

    // Writes /sdcard/PegasusData/metadata/{gameId}.json per contratto Phase 2
    private fun writeMetadata(job: HashJob, meta: GameMetadata) {
        val now = System.currentTimeMillis() / 1000L
        val j = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("gameId",   meta.gameId)
            .put("title",    meta.title)
            .put("platform", FuzzyMatch.normalizePlatform(job.platform))
            .put("cacheKey", job.cacheKey)
            .put("ra", JSONObject()
                .put("points",    0)
                .put("progress",  0.0)
                .put("unlocked",  0)
                .put("total",     meta.numAchievements)
                .put("imageIcon", meta.imageIcon)
                .put("fetchedAt", now))
            .put("rom", JSONObject()
                .put("hash",         job.hash.hash)
                .put("fileSize",     job.fileSize)
                .put("lastModified", job.lastModified))
            .put("fetchedAt", now)
        val out = Paths.metadata(meta.gameId.toString())
        val tmp = File(out.parent, "${out.name}.tmp")
        tmp.writeText(j.toString(2))
        tmp.renameTo(out)
    }

    private fun writePending(jobId: String, verb: String, status: String, progress: Double, message: String) {
        val now = System.currentTimeMillis() / 1000L
        val j = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("jobId",     jobId)
            .put("verb",      verb)
            .put("status",    status)
            .put("progress",  progress)
            .put("message",   message)
            .put("startedAt", now)
            .put("updatedAt", now)
        val f = Paths.pending(jobId)
        val tmp = File(f.parent, "${f.name}.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(f)
    }

    private fun writeError(jobId: String, verb: String, error: String) {
        val now = System.currentTimeMillis() / 1000L
        Paths.pending(jobId).writeText(JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("jobId",     jobId).put("verb", verb)
            .put("status",    "error").put("error", error)
            .put("startedAt", now).put("updatedAt", now).toString())
        Paths.done(jobId).createNewFile()
    }

    // ── File hashing ──────────────────────────────────────────────────────

    private fun hashFile(file: File): HashResult? = when (file.extension.lowercase()) {
        "zip" -> hashZip(file)
        "7z"  -> hash7z(file)
        else  -> NativeHasher.hash(file.absolutePath)
    }

    private fun hashZip(zipFile: File): HashResult? = try {
        ZipFile(zipFile).use { zf ->
            val largest = zf.entries().asSequence().filter { !it.isDirectory }.maxByOrNull { it.size } ?: return null
            val ext = largest.name.substringAfterLast(".", "bin")
            val tmp = File.createTempFile("bridge_", ".$ext", cacheDir)
            try {
                zf.getInputStream(largest).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                NativeHasher.hash(tmp.absolutePath)
            } finally { tmp.delete() }
        }
    } catch (e: Exception) { Log.e(TAG, "ZIP failed: ${zipFile.name}", e); null }

    private fun hash7z(sevenZ: File): HashResult? = try {
        SevenZFile(sevenZ).use { archive ->
            val largest = archive.entries.filter { !it.isDirectory && it.size > 0 }.maxByOrNull { it.size } ?: return null
            val ext = largest.name.substringAfterLast(".", "bin")
            val tmp = File.createTempFile("bridge_", ".$ext", cacheDir)
            try {
                archive.getInputStream(largest).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                NativeHasher.hash(tmp.absolutePath)
            } finally { tmp.delete() }
        }
    } catch (e: Exception) { Log.e(TAG, "7z failed: ${sevenZ.name}", e); null }

    // ── Throttle ─────────────────────────────────────────────────────────

    private fun ioDelayMs(hashTimeMs: Long, fileKB: Long): Long {
        if (hashTimeMs <= 0 || fileKB < 256) return 0
        val rate = fileKB.toDouble() / hashTimeMs
        hashCount++
        if (hashCount > 10 && hashTimeMs > 200 && rate > bestObservedKBperMs && rate < 100.0)
            bestObservedKBperMs = rate
        if (hashCount > 20 && bestObservedKBperMs > 50.0 && rate < bestObservedKBperMs / 10.0)
            return if (rate < bestObservedKBperMs / 30.0) IO_SEVERE_MS else IO_COOLDOWN_MS
        return 0L
    }

    private fun thermalDelayMs(): Long {
        val status = try { powerManager.currentThermalStatus } catch (_: Exception) { PowerManager.THERMAL_STATUS_NONE }
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE     -> 0L
            PowerManager.THERMAL_STATUS_LIGHT    -> 150L
            PowerManager.THERMAL_STATUS_MODERATE -> 600L
            PowerManager.THERMAL_STATUS_SEVERE   -> 2000L
            PowerManager.THERMAL_STATUS_CRITICAL -> 5000L
            else                                  -> 10000L
        }
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Pegasus Bridge — Hasher", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val cancelPi = PendingIntent.getService(
            this, 0,
            Intent(this, HasherService::class.java).apply { action = "CANCEL" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pegasus Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Cancel", cancelPi).build())
            .build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, progress, max))

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PegasusBridge::ScanWakeLock")
            .apply { acquire(60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
}
