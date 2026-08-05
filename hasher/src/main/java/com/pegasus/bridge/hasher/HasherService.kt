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

        // Parallelism tuning
        private const val NUM_HASH_PRODUCERS = 4   // parallel hash workers (CPU+IO bound)
        // Matches RAApiClient.MAX_PARALLEL: more workers than permits only queues
        // them up behind the semaphore.
        private const val NUM_API_WORKERS    = 2   // parallel RA hash-lookup workers (network)
        private const val MAX_CONSECUTIVE_FAILURES = 8

        // Platforms RetroAchievements does not cover today — skip them entirely to save IO.
        // Conservative denylist: only entries that are clearly out of scope.
        private val RA_UNSUPPORTED_PLATFORMS = setOf(
            "switch", "psvita", "wiiu", "pc", "windows", "android", "ios",
            "3ds", "n3ds"
        )

        const val EXTRA_ROOTS  = "roots"   // pipe- or comma-separated directories
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
        if (isRunning) {
            Log.i(TAG, "Scan already in progress, ignoring duplicate start request")
            // Do NOT call stopSelf(startId) here — startId is the latest, so Android
            // would tear down the whole service, cancelling the running scan.
            return START_NOT_STICKY
        }

        val rootsCsv = intent?.getStringExtra(EXTRA_ROOTS) ?: run { stopSelf(); return START_NOT_STICKY }
        val jobId    = intent.getStringExtra(EXTRA_JOB_ID) ?: java.util.UUID.randomUUID().toString()
        val roots    = rootsCsv.split('|', ',').map { it.trim() }.filter { it.isNotEmpty() }

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
                Paths.markDone(jobId)
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
    private data class ResultJob(
        val job: HashJob,
        val meta: GameMetadata?,
        val cached:  Boolean = false,   // file unchanged since last scan; metadata already on disk
        val skipped: Boolean = false,   // platform not on RA
        val failed:  Boolean = false    // lookup never got an answer — not the same as "unknown"
    )

    // Snapshot of `metadata/{gameId}.json` used for incremental scans.
    private data class CachedMeta(
        val gameId:       Int,
        val hash:         String,
        val fileSize:     Long,
        val lastModified: Long
    )

    // Reads every per-game metadata file once at scan start so we can skip files
    // whose (cacheKey, fileSize, lastModified) triple is unchanged. Cuts re-scan
    // time from minutes to seconds on stable libraries.
    private fun preloadMetadataCache(): Map<String, CachedMeta> {
        val map = HashMap<String, CachedMeta>()
        val files = Paths.METADATA.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_")
        } ?: return map
        for (f in files) {
            try {
                val j        = JSONObject(f.readText())
                val cacheKey = j.optString("cacheKey")
                val rom      = j.optJSONObject("rom") ?: continue
                if (cacheKey.isEmpty()) continue
                map[cacheKey] = CachedMeta(
                    gameId       = j.optInt("gameId"),
                    hash         = rom.optString("hash"),
                    fileSize     = rom.optLong("fileSize"),
                    lastModified = rom.optLong("lastModified")
                )
            } catch (_: Exception) {}
        }
        return map
    }

    private suspend fun runScan(roots: List<String>, jobId: String, raUser: String, raApiKey: String) = coroutineScope {
        Paths.ensureAll()
        writePending(jobId, "scan", "running", 0.0, "Scanning ROM folders…")

        val romFiles = RomScanner.scan(roots)
        val total    = romFiles.size
        Log.i(TAG, "Found $total ROM files")
        if (total == 0) { writePending(jobId, "scan", "running", 1.0, "No ROMs found"); return@coroutineScope }

        val apiClient   = RAApiClient(raUser, raApiKey)
        val hashChannel = Channel<HashJob>(capacity = 32)
        val resultChannel = Channel<ResultJob>(capacity = 128)
        val hashDedup   = mutableMapOf<String, GameMetadata?>()

        // Pre-load existing metadata so unchanged files can skip hash + API entirely.
        val metaCache = preloadMetadataCache()
        Log.i(TAG, "Loaded ${metaCache.size} cached metadata entries for incremental scan")

        // ── Pipeline:
        //   feeder  → fileQueue → N parallel hash producers → hashChannel → API workers → resultChannel → collector
        // Cached/skipped entries bypass hashChannel and go straight to resultChannel.
        val fileQueue = Channel<File>(capacity = 64)
        val feeder = launch(Dispatchers.IO) {
            for (f in romFiles) fileQueue.send(f)
            fileQueue.close()
        }

        val numProducers = NUM_HASH_PRODUCERS.coerceAtMost(Runtime.getRuntime().availableProcessors())
        val producers = List(numProducers) {
            launch(Dispatchers.Default) {
                for (file in fileQueue) {
                    if (!isActive) break
                    val rawPlatform  = file.parentFile?.name ?: "unknown"
                    val normPlatform = FuzzyMatch.normalizePlatform(rawPlatform)

                    // Pre-filter: skip platforms RA does not cover
                    if (normPlatform in RA_UNSUPPORTED_PLATFORMS) {
                        resultChannel.send(ResultJob(
                            HashJob(file, "", HashResult("", 0), rawPlatform, 0, 0, 0),
                            null, skipped = true
                        ))
                        continue
                    }

                    val cacheKey     = FuzzyMatch.makeCacheKey(file.nameWithoutExtension, rawPlatform)
                    val fileSize     = file.length()
                    val fileKB       = fileSize / 1024
                    val lastModified = file.lastModified()

                    // Incremental skip: file matched in a previous scan and hasn't changed.
                    // Metadata is already on disk; writeDiscoveryIndex() will pick it up.
                    val cached = metaCache[cacheKey]
                    if (cached != null
                        && cached.hash.isNotEmpty()
                        && cached.fileSize == fileSize
                        && cached.lastModified == lastModified) {
                        resultChannel.send(ResultJob(
                            HashJob(file, cacheKey, HashResult(cached.hash, 0), rawPlatform, fileKB, fileSize, lastModified),
                            null, cached = true
                        ))
                        continue
                    }

                    // Hash (uncached path)
                    val result = try { withContext(Dispatchers.IO) { hashFile(file) } } catch (e: Exception) { null }
                    if (result == null) {
                        resultChannel.send(ResultJob(
                            HashJob(file, cacheKey, HashResult("", 0), rawPlatform, fileKB, fileSize, lastModified),
                            null
                        ))
                        continue
                    }

                    // Throttle — thermal only (ioDelay removed: too aggressive on SD cards)
                    val delay = thermalDelayMs()
                    if (delay > 0) delay(delay)

                    hashChannel.send(HashJob(file, cacheKey, result, rawPlatform, fileKB, fileSize, lastModified))
                }
            }
        }

        // Workers: parallel API lookups
        val workers = List(NUM_API_WORKERS) {
            launch(Dispatchers.IO) {
                for (hj in hashChannel) {
                    val known = synchronized(hashDedup) { hashDedup[hj.hash.hash] }
                    if (known != null) { resultChannel.send(ResultJob(hj, known)); continue }

                    when (val r = apiClient.lookupHash(hj.hash.hash)) {
                        is RAApiClient.Lookup.Hit -> {
                            synchronized(hashDedup) { hashDedup[hj.hash.hash] = r.meta }
                            resultChannel.send(ResultJob(hj, r.meta))
                        }
                        RAApiClient.Lookup.Miss -> {
                            // A real answer, worth remembering for this run.
                            val miss = GameMetadata(gameId = 0)
                            synchronized(hashDedup) { hashDedup[hj.hash.hash] = miss }
                            resultChannel.send(ResultJob(hj, miss))
                        }
                        RAApiClient.Lookup.Failed ->
                            // Deliberately not cached and not counted as an
                            // answer: the next scan must ask again.
                            resultChannel.send(ResultJob(hj, null, failed = true))
                    }
                }
            }
        }

        // Coordinator: close channels in the correct order as upstream stages finish
        launch {
            feeder.join()
            producers.forEach { it.join() }
            hashChannel.close()
            workers.forEach { it.join() }
            resultChannel.close()
        }

        // Collector: write per-game metadata/{gameId}.json
        var processed = 0; var newEntries = 0; var cachedHits = 0; var skippedPlat = 0
        var failedLookups = 0
        // Aim for ~50 progress updates over the whole scan, with a sane minimum.
        val writeStep = (total / 50).coerceAtLeast(10)
        for (r in resultChannel) {
            when {
                r.skipped -> skippedPlat++
                r.cached  -> cachedHits++
                r.failed  -> failedLookups++
                r.meta != null && r.meta.gameId > 0 -> {
                    writeMetadata(r.job, r.meta)
                    newEntries++
                }
            }
            processed++

            // Once RetroAchievements has stopped answering there is nothing to
            // gain from grinding through the rest of the library: every file
            // would be recorded as unknown. Stop and say so.
            if (apiClient.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Log.e(TAG, "aborting scan: $failedLookups lookups failed, " +
                           "${apiClient.consecutiveFailures} in a row")
                writeDiscoveryIndex()
                writeError(jobId, "scan",
                    "RetroAchievements stopped responding after $processed of $total files " +
                    "($newEntries identified). Nothing was recorded as missing. " +
                    "Wait a few minutes and scan again — it will resume where it left off.")
                return@coroutineScope
            }
            if (processed % writeStep == 0 || processed == total) {
                val pct = processed.toDouble() / total
                writePending(jobId, "scan", "running", pct,
                    "[$processed/$total] ${r.job.file.name}",
                    newEntries, cachedHits, skippedPlat)
                updateNotification("[$processed/$total] ${r.job.file.name}", processed, total)
            }
        }

        writeDiscoveryIndex()
        writePending(jobId, "scan", "running", 1.0,
            "Done — $newEntries new, $cachedHits cached, $skippedPlat skipped",
            newEntries, cachedHits, skippedPlat)
        Log.i(TAG, "Scan complete: $processed processed, $newEntries new, $cachedHits cached, " +
                   "$skippedPlat skipped, $failedLookups lookups failed")
    }

    // Enumera metadata/*.json ed emette metadata/_index.json con:
    //   • games[]  — lista per "Discovered Games"
    //   • byKey{}  — reverse lookup cacheKey → { gameId, title, platform, imageIcon, total }
    // Sostituisce il legacy /sdcard/ReStory/ra_hashes_cache.json (struttura external_hashes).
    private fun writeDiscoveryIndex() {
        try {
            val dir = Paths.METADATA
            val files = dir.listFiles { f ->
                f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_")
            } ?: return
            val games = org.json.JSONArray()
            val byKey = JSONObject()
            for (f in files) {
                try {
                    val j        = JSONObject(f.readText())
                    val ra       = j.optJSONObject("ra") ?: continue
                    val gameId   = j.optInt("gameId")
                    val title    = j.optString("title")
                    val platform = j.optString("platform")
                    val total    = ra.optInt("total")
                    val icon     = ra.optString("imageIcon")
                    if (gameId <= 0 || title.isEmpty()) continue

                    games.put(JSONObject()
                        .put("gameId",    gameId)
                        .put("title",     title)
                        .put("platform",  platform)
                        .put("total",     total)
                        .put("imageIcon", icon))

                    // cacheKey è scritto dal hasher in formato "normalizedTitle|shortName" —
                    // lo usiamo verbatim come chiave per il reverse lookup.
                    val cacheKey = j.optString("cacheKey")
                    if (cacheKey.isNotEmpty()) {
                        byKey.put(cacheKey, JSONObject()
                            .put("gameId",    gameId)
                            .put("title",     title)
                            .put("platform",  platform)
                            .put("imageIcon", icon)
                            .put("total",     total))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Index skip ${f.name}: ${e.message}")
                }
            }
            val payload = JSONObject()
                .put("schemaVersion", SchemaVersion.CURRENT)
                .put("fetchedAt", System.currentTimeMillis() / 1000L)
                .put("count",     games.length())
                .put("games",     games)
                .put("byKey",     byKey)
            val out = File(Paths.METADATA, "_index.json")
            val tmp = File(out.parent, "_index.json.tmp")
            tmp.writeText(payload.toString(2))
            tmp.renameTo(out)
            Log.i(TAG, "Discovery index written: ${games.length()} games, ${byKey.length()} keys")
        } catch (e: Exception) {
            Log.e(TAG, "writeDiscoveryIndex failed", e)
        }
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

    private fun writePending(
        jobId: String, verb: String, status: String, progress: Double, message: String,
        newEntries: Int = 0, cachedHits: Int = 0, skippedPlatforms: Int = 0
    ) {
        val now = System.currentTimeMillis() / 1000L
        val j = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("jobId",     jobId)
            .put("verb",      verb)
            .put("status",    status)
            .put("progress",  progress)
            .put("message",   message)
            .put("newEntries",        newEntries)
            .put("cachedHits",        cachedHits)
            .put("skippedPlatforms",  skippedPlatforms)
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
        Paths.markDone(jobId)
    }

    // ── File hashing ──────────────────────────────────────────────────────

    private fun hashFile(file: File): HashResult? = when (file.extension.lowercase()) {
        // A failed extraction falls back to hashing the file as-is, because the
        // extension is a claim and not a fact: a plain ROM renamed .7z is common
        // enough that refusing it loses real games. The fallback cannot produce a
        // wrong match, only a miss — a compressed byte stream hashes to nothing
        // RetroAchievements knows.
        "zip" -> hashZip(file) ?: NativeHasher.hash(file.absolutePath)
        "7z"  -> hash7z(file)  ?: NativeHasher.hash(file.absolutePath)
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
    } catch (c: kotlinx.coroutines.CancellationException) { throw c
    } catch (t: Throwable) { Log.e(TAG, "ZIP failed: ${zipFile.name}", t); null }

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
    // Throwable, not Exception: a missing optional codec arrives as
    // NoClassDefFoundError, which is an Error. Catching only Exception let it
    // escape the worker and take the whole scan down with it — the symptom was
    // a scan that reported "complete, 0/0" a second after starting.
    } catch (c: kotlinx.coroutines.CancellationException) { throw c
    } catch (t: Throwable) { Log.e(TAG, "7z failed: ${sevenZ.name}", t); null }

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
            PowerManager.THERMAL_STATUS_LIGHT    -> 0L      // softened
            PowerManager.THERMAL_STATUS_MODERATE -> 200L    // softened (was 600)
            PowerManager.THERMAL_STATUS_SEVERE   -> 800L    // softened (was 2000)
            PowerManager.THERMAL_STATUS_CRITICAL -> 3000L   // softened (was 5000)
            else                                  -> 5000L
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
