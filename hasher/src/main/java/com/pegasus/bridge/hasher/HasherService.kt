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

    // Taken by every start request and by the end of a scan, so the one cannot
    // run between the lines of the other. Guards isRunning, scanJob, the wake lock
    // and the foreground state as well as the id below.
    private val lifecycle = Any()
    // The newest start request: the only id stopSelf(int) honours.
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = synchronized(lifecycle) {
        latestStartId = startId
        handleStart(intent, startId)
    }

    private fun handleStart(intent: Intent?, startId: Int): Int {
        if (intent?.action == "CANCEL") {
            // A Cancel that finds no scan — tapped as one was finishing — still has
            // to stop the service: the finally that would have has already run,
            // with an id that is no longer the newest.
            if (isRunning) scanJob?.cancel() else stopSelf(startId)
            return START_NOT_STICKY
        }
        if (isRunning) {
            Log.i(TAG, "Scan already in progress, ignoring duplicate start request")
            // Do NOT call stopSelf(startId) here — startId is the latest, so Android
            // would tear down the whole service, cancelling the running scan. The
            // scan's finally stops it with this id instead.
            return START_NOT_STICKY
        }

        val rootsCsv = intent?.getStringExtra(EXTRA_ROOTS) ?: run { endWithoutScan(startId); return START_NOT_STICKY }
        val jobId    = intent.getStringExtra(EXTRA_JOB_ID) ?: java.util.UUID.randomUUID().toString()
        val roots    = rootsCsv.split('|', ',').map { it.trim() }.filter { it.isNotEmpty() }

        val creds = Config.load()
        val raUser   = creds.ra?.user   ?: ""
        val raApiKey = creds.ra?.apiKey ?: ""
        if (raUser.isEmpty() || raApiKey.isEmpty()) {
            writeError(jobId, "scan", "Missing RA credentials in credentials.json")
            endWithoutScan(startId); return START_NOT_STICKY
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
            } catch (e: ScanAborted) {
                // Logged where it was decided, with the counts. The message is
                // the advice the theme shows, so it goes out as it is.
                writeError(jobId, "scan", e.message ?: "Scan aborted")
            } catch (t: Throwable) {
                // Throwable, not Exception. An Error — an UnsatisfiedLinkError
                // from the native hasher, or one a lookup's owner hands every file
                // waiting on it — went straight past, so pending kept saying
                // "running", the theme's popup with it, and the Error left the
                // coroutine and killed the app. It ends the scan like any other
                // failure; rethrown after the error is written, it would still
                // kill the app.
                Log.e(TAG, "Scan failed", t)
                writeError(jobId, "scan", t.message ?: t.javaClass.simpleName)
            } finally {
                // Under the lock a start request takes. Outside it, one arriving
                // after isRunning went false began a scan that the rest of this
                // block then undid: its wake lock released, its notification
                // taken down and, with the newest id below, the service stopped
                // under it.
                synchronized(lifecycle) {
                    isRunning = false
                    releaseWakeLock()
                    Paths.markDone(jobId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // The newest start request, not this scan's. stopSelf(int)
                    // ignores any other id, so once a Cancel from the notification
                    // or a duplicate start had come in, the scan's own id stopped
                    // nothing and the service stayed up, idle, until Android
                    // reclaimed it.
                    stopSelf(latestStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Ends a start request that will not become a scan.
     *
     * DataLayerRouter starts this service with startForegroundService, and from
     * Android 9 a service started that way which stops before calling
     * startForeground takes the app down: "Context.startForegroundService() did
     * not then call Service.startForeground()". So the notification goes up, for
     * as long as it takes to take it down again. The error, if there is one, is
     * written first, so the theme sees it either way.
     */
    private fun endWithoutScan(startId: Int) {
        startForeground(NOTIFICATION_ID, buildNotification("Scanning ROM folders…", 0, 0))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scanJob?.cancel(); scope.cancel()
        synchronized(lifecycle) { isRunning = false; releaseWakeLock() }
        super.onDestroy()
    }

    // ── Pipeline ────────────────────────────────────────────────────────────

    private data class HashJob(
        val file: File, val cacheKey: String, val hash: HashResult,
        val platform: String, val fileKB: Long, val fileSize: Long, val lastModified: Long
    )
    private data class ResultJob(
        val job: HashJob,
        val answer: RAApiClient.Lookup? = null,   // null when RetroAchievements was not asked
        val cached:  Boolean = false,   // file unchanged since last scan; metadata already on disk
        val skipped: Boolean = false,   // platform not on RA
        val settled: Boolean = false    // answer is a verdict from an earlier scan, already in the ledger
    )

    /**
     * Ends a scan that RetroAchievements has stopped answering, or whose key it
     * has refused.
     *
     * Thrown by the collector once every stage has been stopped, so the job ends
     * the way any failed job does: the error lands in `pending/{jobId}.json` and
     * the `finally` in [onStartCommand] releases the foreground service and the
     * wake lock. A class of its own so that handler can tell an abort it expected
     * from a crash it did not.
     */
    private class ScanAborted(message: String) : Exception(message)

    // Snapshot of `metadata/{gameId}.json` used for incremental scans.
    private data class CachedMeta(
        val gameId:       Int,
        val hash:         String,
        val fileMd5:      String,
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
                // An id with no title is not a match, and the collector no longer
                // writes one. Files written before it stopped are still on disk —
                // 27 of 732 on the tablet — and treating them as cached kept every
                // one of them out of the index and away from the network for as
                // long as the ROM stayed unchanged. Ignored here, they are looked
                // up again; the file itself is left alone, for a real match to
                // overwrite if one ever comes.
                if (j.optInt("gameId") <= 0 || j.optString("title").isBlank()) continue
                map[cacheKey] = CachedMeta(
                    gameId       = j.optInt("gameId"),
                    hash         = rom.optString("hash"),
                    fileMd5      = rom.optString("fileMd5"),
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

        val romFiles = RomScanner.scan(roots, RomScanExtensions.forScan)
        val total    = romFiles.size
        Log.i(TAG, "Found $total ROM files")
        if (total == 0) { writePending(jobId, "scan", "done", 1.0, "No ROMs found"); return@coroutineScope }

        val apiClient   = RAApiClient(raUser, raApiKey)
        val hashChannel = Channel<HashJob>(capacity = 32)
        val resultChannel = Channel<ResultJob>(capacity = 128)
        // hash -> the one lookup for it, in flight or finished.
        //
        // The promise and not the result, which is what makes the de-duplication
        // real. Holding results, a worker read the map, released the lock and only
        // then called the network, so a second worker could find the same hash
        // absent in that gap and call as well. The lock covered the map, never the
        // decision to ask.
        val hashDedup   = mutableMapOf<String, CompletableDeferred<RAApiClient.Lookup>>()

        // Pre-load existing metadata so unchanged files can skip hash + API entirely.
        val metaCache = preloadMetadataCache()
        Log.i(TAG, "Loaded ${metaCache.size} cached metadata entries for incremental scan")

        // The dumps RetroAchievements holds only as virtual ids, remembered between
        // scans. A match is remembered by its metadata file; these have none to
        // write, so each was hashed and asked about again, twice, on every scan,
        // for an answer that had not changed. Kept in the desktop's ledger — the
        // same class, the same file under cache/ — as the verdict the desktop
        // records for an answer that is no: NOT_FOUND, whose fortnight lets a dump
        // RA comes to support turn up again. Only these are written, a few dozen
        // on a large library, so nothing is forgotten: a scan of some roots must
        // not drop the verdicts on the others.
        val ledger = ScanLedger(Paths.cache(ScanLedger.FILE_NAME))
        val now    = System.currentTimeMillis() / 1000L

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
                    // fileMd5 is required too: metadata written before plain
                    // hashes existed would otherwise stay cached forever, and a
                    // library already scanned once would never gain the field.
                    val cached = metaCache[cacheKey]
                    if (cached != null
                        && cached.hash.isNotEmpty()
                        && cached.fileMd5.isNotEmpty()
                        && cached.fileSize == fileSize
                        && cached.lastModified == lastModified) {
                        resultChannel.send(ResultJob(
                            HashJob(file, cacheKey, HashResult(cached.hash, 0), rawPlatform, fileKB, fileSize, lastModified),
                            null, cached = true
                        ))
                        continue
                    }

                    // A virtual id from an earlier scan, for the file as it is now.
                    val settled = ledger.canSkip(ledgerKey(file), fileSize, lastModified, now)
                    if (settled != null && settled.gameId > RAApiClient.VIRTUAL_ID_BASE) {
                        resultChannel.send(ResultJob(
                            HashJob(file, cacheKey, HashResult("", 0), rawPlatform, fileKB, fileSize, lastModified),
                            RAApiClient.Lookup.Incompatible(settled.gameId), settled = true
                        ))
                        continue
                    }

                    // Hash (uncached path)
                    // Cancellation is let through: caught as an Exception, a stage
                    // told to stop reported the file it was hashing as a result
                    // and went on to send it.
                    val result = try { withContext(Dispatchers.IO) { hashFile(file) } }
                                 catch (c: CancellationException) { throw c }
                                 catch (e: Exception) { null }
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
                    // Claiming the hash and registering the promise happen under
                    // one lock, so exactly one worker owns the call and any other
                    // file with the same hash awaits it instead of racing it.
                    val hash = hj.hash.hash
                    var mine: CompletableDeferred<RAApiClient.Lookup>? = null
                    val pending = synchronized(hashDedup) {
                        hashDedup[hash] ?: CompletableDeferred<RAApiClient.Lookup>().also {
                            mine = it
                            hashDedup[hash] = it
                        }
                    }

                    val owned = mine
                    val answer: RAApiClient.Lookup = if (owned == null) {
                        // Someone else is already asking. A file that arrives while
                        // a failing lookup is in flight shares that failure; one that
                        // arrives after it finished finds the entry gone and asks again.
                        pending.await()
                    } else {
                        var answer: RAApiClient.Lookup = RAApiClient.Lookup.Failed
                        var failure: Throwable? = null
                        try {
                            answer = apiClient.lookupHash(hash)
                        } catch (t: Throwable) {
                            failure = t
                            throw t
                        } finally {
                            // Settled on every path, cancellation included: an owner
                            // that simply stopped would leave the files waiting on it
                            // awaiting a promise nobody completes. The entry goes
                            // before the promise is settled, so no file can pick up
                            // a failure that has already finished. Only an answer is
                            // worth remembering for this run; a failure deliberately
                            // is not, so a later file with this hash asks again.
                            if (answer == RAApiClient.Lookup.Failed || answer == RAApiClient.Lookup.KeyRefused)
                                synchronized(hashDedup) { hashDedup.remove(hash) }
                            if (failure == null) owned.complete(answer)
                            else owned.completeExceptionally(failure)
                        }
                        answer
                    }
                    resultChannel.send(ResultJob(hj, answer))
                }
            }
        }

        // Coordinator: close channels in the correct order as upstream stages finish
        val coordinator = launch {
            feeder.join()
            producers.forEach { it.join() }
            hashChannel.close()
            workers.forEach { it.join() }
            resultChannel.close()
        }

        // Collector: write per-game metadata/{gameId}.json
        var processed = 0; var newEntries = 0; var cachedHits = 0; var skippedPlat = 0
        var failedLookups = 0; var unmatched = 0; var incompatible = 0
        // Aim for ~50 progress updates over the whole scan, with a sane minimum.
        val writeStep = (total / 50).coerceAtLeast(10)
        for (r in resultChannel) {
            val answer = r.answer
            when {
                r.skipped -> skippedPlat++
                r.cached  -> cachedHits++
                answer == RAApiClient.Lookup.Failed || answer == RAApiClient.Lookup.KeyRefused -> failedLookups++
                // Always titled now. An id with no title — an HTML page, a body cut
                // short or `[]` from API_GetGameExtended for a real id — is a
                // failed lookup, not a Hit: as a Hit it was counted `untitled`,
                // blamed on RetroAchievements, and cleared the count the abort
                // watches.
                answer is RAApiClient.Lookup.Hit -> {
                    writeMetadata(r.job, answer.meta)
                    newEntries++
                }
                // A dump RetroAchievements knows and does not support — the
                // Virtual Console Metroid gets 1100001487, game 1487 untested.
                // Counted as untitled it was asked about on every scan, twice,
                // and reported as a game RA could not describe. Recorded once,
                // when it was actually asked.
                answer is RAApiClient.Lookup.Incompatible -> {
                    incompatible++
                    if (!r.settled) ledger.record(
                        ledgerKey(r.job.file), ScanLedger.State.NOT_FOUND,
                        r.job.fileSize, r.job.lastModified, now, gameId = answer.virtualId,
                        detail = "RetroAchievements holds this dump only as virtual id ${answer.virtualId}")
                }
                // RetroAchievements answered, and the answer was no — a `Miss`,
                // and with it a file that would not hash, which never got as far
                // as asking.
                //
                // A `Miss` used to match no branch here, so it was counted only
                // in `processed` and reported nowhere: a library of ROMs the
                // database has never heard of finished with "0 new, 0 cached,
                // 0 skipped, 0 lookups failed" and no hint that anything had been
                // looked at. Measured on the tablet, where `amiga`, `amstradcpc`
                // and `arcade` each said exactly that.
                else -> unmatched++
            }
            processed++

            // Once RetroAchievements has stopped answering there is nothing to
            // gain from grinding through the rest of the library: every file
            // would be recorded as unknown. Stop and say so. A refused key stops
            // it at once: every lookup that finds a game will be refused the same
            // way, and the scan used to go through the whole library like that,
            // since the r=gameid call between two refusals cleared the count.
            val keyRefused = answer == RAApiClient.Lookup.KeyRefused
            if (keyRefused || apiClient.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Log.e(TAG, "aborting scan: ${if (keyRefused) "API key refused, " else ""}" +
                           "$failedLookups lookups failed, ${apiClient.consecutiveFailures} in a row")
                // This used to `return@coroutineScope`, which cannot leave: a scope
                // waits for its children, and the producers and workers were still
                // running, sending into a result queue nobody drained any more.
                // Whenever more files were left than the queues hold (64 + 32 +
                // 128 slots) they filled up and blocked for good. The `finally` in
                // onStartCommand never ran, isRunning stayed true — so every later
                // scan was turned away as a duplicate — and the wake lock held until
                // its 60-minute timeout. The abort meant to save a doomed run hung
                // it instead.
                //
                // So every stage is cancelled, and the queues with them: cancel
                // rather than close, because closing refuses new sends but leaves
                // one already blocked on a full buffer where it is. The join is
                // NonCancellable so a Cancel tapped now cannot skip the index; it
                // waits only for what cannot be interrupted, a hash being taken.
                // A request in flight is cancelled with the worker that made it.
                val stages = listOf(feeder, coordinator) + producers + workers
                stages.forEach { it.cancel() }
                fileQueue.cancel(); hashChannel.cancel(); resultChannel.cancel()
                withContext(NonCancellable) { stages.joinAll() }
                writeDiscoveryIndex()
                ledger.save(Paths::writeAtomic)
                throw ScanAborted(
                    if (keyRefused)
                        "RetroAchievements refused the API key for $raUser after $processed of $total files " +
                        "($newEntries identified). Nothing was recorded as missing. " +
                        "Copy the Web API key from your RetroAchievements settings into credentials.json " +
                        "and scan again."
                    else
                        "RetroAchievements stopped responding after $processed of $total files " +
                        "($newEntries identified). Nothing was recorded as missing. " +
                        "Wait a few minutes and scan again — it will resume where it left off.")
            }
            if (processed % writeStep == 0 || processed == total) {
                val pct = processed.toDouble() / total
                writePending(jobId, "scan", "running", pct,
                    "[$processed/$total] ${r.job.file.name}",
                    newEntries, cachedHits, skippedPlat, unmatched, incompatible)
                updateNotification("[$processed/$total] ${r.job.file.name}", processed, total)
            }
        }

        writeDiscoveryIndex()
        ledger.save(Paths::writeAtomic)
        // The dumps RA does not support get a clause of their own, and only when
        // there are any. Left out, they would vanish from the summary altogether —
        // a library of them would finish "0 new, 0 cached, 0 skipped, 0 not in the
        // database".
        writePending(jobId, "scan", "done", 1.0,
            "Done — $newEntries new, $cachedHits cached, $skippedPlat skipped, " +
            "$unmatched not in the database" +
            (if (incompatible > 0) ", $incompatible dumps RetroAchievements does not support" else ""),
            newEntries, cachedHits, skippedPlat, unmatched, incompatible)
        Log.i(TAG, "Scan complete: $processed processed, $newEntries new, $cachedHits cached, " +
                   "$skippedPlat skipped, $unmatched unmatched, $incompatible incompatible, " +
                   "$failedLookups lookups failed")
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

    /** What the ledger knows a file by, which is what the desktop knows it by too. */
    private fun ledgerKey(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

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
                // Plain hashes of the ROM bytes, for databases that match by
                // file rather than title. Distinct from `hash`, the rcheevos one.
                .put("fileMd5",      job.hash.fileMd5)
                .put("fileCrc32",    job.hash.fileCrc32)
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
        newEntries: Int = 0, cachedHits: Int = 0, skippedPlatforms: Int = 0,
        /** Looked up and genuinely not in RetroAchievements — an answer, not a failure. */
        unmatched: Int = 0,
        /** Held by RetroAchievements only as a virtual id — incompatible, untested, needs a patch. */
        incompatible: Int = 0
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
            .put("unmatched",         unmatched)
            .put("incompatible",      incompatible)
            .put("startedAt", now)
            .put("updatedAt", now)
        val f = Paths.pending(jobId)
        val tmp = File(f.parent, "${f.name}.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(f)
    }

    // Through a temp file, as writePending does, and done is marked only once
    // the file is whole. writeText truncates first: a theme poll landing in that
    // gap read an empty file, took it for a finished job, and never showed the
    // abort, the cancel or the error this is the only record of.
    private fun writeError(jobId: String, verb: String, error: String) {
        val now = System.currentTimeMillis() / 1000L
        Paths.writeAtomic(Paths.pending(jobId), JSONObject()
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
        "zip" -> hashZip(file) ?: withPlainHashes(NativeHasher.hash(file.absolutePath), file)
        "7z"  -> hash7z(file)  ?: withPlainHashes(NativeHasher.hash(file.absolutePath), file)
        else  -> withPlainHashes(NativeHasher.hash(file.absolutePath), file)
    }

    /** Hashes the ROM itself — the extracted entry for an archive, else the file. */
    private fun withPlainHashes(result: HashResult?, romFile: File): HashResult? {
        if (result == null) return null
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val crc = java.util.zip.CRC32()
            romFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    crc.update(buf, 0, n)
                }
            }
            result.copy(
                fileMd5 = md.digest().joinToString("") { "%02x".format(it) },
                fileCrc32 = "%08x".format(crc.value)
            )
        } catch (c: kotlinx.coroutines.CancellationException) { throw c
        } catch (t: Throwable) {
            // Costs a scraper lookup, never the RA match the scan exists for.
            Log.w(TAG, "plain hash failed: ${romFile.name}: ${t.message}")
            result
        }
    }

    private fun hashZip(zipFile: File): HashResult? = try {
        ZipFile(zipFile).use { zf ->
            val largest = zf.entries().asSequence().filter { !it.isDirectory }.maxByOrNull { it.size } ?: return null
            val ext = largest.name.substringAfterLast(".", "bin")
            val tmp = File.createTempFile("bridge_", ".$ext", cacheDir)
            try {
                zf.getInputStream(largest).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                withPlainHashes(NativeHasher.hash(tmp.absolutePath), tmp)
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
                withPlainHashes(NativeHasher.hash(tmp.absolutePath), tmp)
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
