package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.FuzzyMatch
import com.pegasus.bridge.core.SchemaVersion
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Scans ROM directories, hashes what it finds and resolves each hash to a
 * RetroAchievements game.
 *
 * This is the pipeline that used to live inside `HasherService`, with the
 * Android shell removed: no Service, no wake lock, no notifications, no thermal
 * API. Throttling is now a caller-supplied hook, so Android can keep its thermal
 * back-off and the desktop daemon can simply not throttle.
 *
 * Shape: feeder → N hash producers → API workers → collector. Files unchanged
 * since the last scan, and platforms RetroAchievements does not cover, bypass
 * both hashing and the network.
 */
class RomScanPipeline(
    private val paths: BridgePaths,
    private val hasher: RomHasher,
    private val lookup: RaHashLookup,
    /** Milliseconds to pause after a hash. Android passes its thermal back-off. */
    private val throttleMs: () -> Long = { 0L },
    private val hashWorkers: Int = DEFAULT_HASH_WORKERS,
    private val apiWorkers: Int = DEFAULT_API_WORKERS,
    /**
     * Which extensions count as ROMs in a given directory.
     *
     * Supplied by the caller because the honest answer lives in the collection's
     * own `metadata.pegasus.txt`, and this module cannot read one without
     * depending on the module that parses it. The default is the built-in set,
     * which is what every existing caller already got.
     */
    private val extensionsFor: (File) -> Set<String> = { RomScanner.ROM_EXTENSIONS }
) {

    // Refused here rather than discovered mid-scan. With no hash workers nothing
    // reads the file queue, and with no API workers nothing reads the hash queue.
    // On a library bigger than that queue's buffer, the stage feeding it waits
    // forever and the scan never returns; on a smaller one it returns having
    // quietly skipped the work. Neither says why.
    init {
        require(hashWorkers > 0) { "hashWorkers must be positive, was $hashWorkers" }
        require(apiWorkers > 0) { "apiWorkers must be positive, was $apiWorkers" }
    }

    data class Progress(
        val processed: Int,
        val total: Int,
        val currentFile: String,
        val newEntries: Int,
        val cachedHits: Int,
        val skippedPlatforms: Int
    ) {
        val fraction: Double get() = if (total <= 0) 0.0 else processed.toDouble() / total
    }

    data class Summary(
        val total: Int,
        /**
         * Files whose result the collector actually saw.
         *
         * Equal to [total] on a scan that ran to the end, and less than it on one
         * that was cut short — which is the only honest way to report a partial
         * run. A summary that reported [total] either way would describe an abort
         * as a complete scan.
         */
        val processed: Int,
        val newEntries: Int,
        val cachedHits: Int,
        val skippedPlatforms: Int,
        val indexed: Int,
        /** True when the scan stopped early. Its useful work is still on disk. */
        val aborted: Boolean = false,
        /** Why, in one sentence, when [aborted]. Empty otherwise. */
        val reason: String = "",
        /** Lookups that never got an answer — distinct from "not in the database". */
        val failedLookups: Int = 0,
        /**
         * Every file the scan looked at, counted by what happened to it.
         *
         * The point of the ledger, surfaced: a run that indexed nine of ten files
         * can now say whether the tenth was a miss, an unreadable file, an archive
         * nobody could resolve, or a source that stopped answering.
         */
        val states: Map<ScanLedger.State, Int> = emptyMap(),
        /** Archives holding several plausible ROMs, with the candidates. */
        val ambiguousArchives: List<Pair<String, String>> = emptyList()
    )

    /**
     * Cuts a doomed scan short.
     *
     * Thrown by the collector *inside* the [coroutineScope], which is the whole
     * point: `break` only stopped the collector, and the producers went on hashing
     * and went on sending into a queue nobody was draining. Once the 128-slot
     * buffer filled they blocked forever, and the scope — which waits for its
     * children — never returned. The abort that was supposed to save a doomed run
     * hung it instead, which a 400-file test reproduced in under a second.
     *
     * Throwing cancels every child through the ordinary structured-concurrency
     * path, so a blocked `send` is woken rather than waited on.
     */
    private class ScanAborted(val why: String) : Exception(why)

    suspend fun scan(
        roots: List<String>,
        onProgress: (Progress) -> Unit = {}
    ): Summary {
        paths.ensureAll()

        val files = RomScanner.scan(roots, extensionsFor)
        val total = files.size
        BridgeLog.i(TAG, "found $total ROM files under ${roots.size} root(s)")
        if (total == 0) return Summary(0, 0, 0, 0, 0, writeDiscoveryIndex())

        val metaCache = preloadMetadataCache()
        BridgeLog.i(TAG, "loaded ${metaCache.size} cached entries for incremental scan")

        // Verdicts from previous scans, including the ones that are not matches.
        // Without it a library of mostly-unknown ROMs asked the source about every
        // one of them on every run, and could never say why any of them was missing.
        val ledger = ScanLedger(File(paths.cache, ScanLedger.FILE_NAME))
        ledger.forget(files.map { canonical(it) }.toSet(), roots.map { canonical(File(it)) })
        val now = BridgePaths.epochSeconds()

        val fileQueue    = Channel<File>(capacity = 64)
        val hashQueue    = Channel<HashJob>(capacity = 32)
        val resultQueue  = Channel<ResultJob>(capacity = 128)
        // hash -> the one lookup for it, in flight or finished.
        //
        // Holding the promise rather than the result is what makes the de-duplication
        // real. The map used to hold results: a worker read it, released the lock and
        // then called the network, so a second worker could read the same absent hash
        // in that gap and call as well. The lock covered the map, never the decision.
        val hashDedup    = mutableMapOf<String, CompletableDeferred<GameMetadata?>>()

        var processed = 0; var newEntries = 0; var cached = 0; var skipped = 0
        var failedLookups = 0
        var abortReason = ""

        try {
            coroutineScope {
                val feeder = launch(Dispatchers.IO) {
                    try {
                        for (f in files) fileQueue.send(f)
                    } finally {
                        fileQueue.close()
                    }
                }

                val producers = List(hashWorkers.coerceAtMost(Runtime.getRuntime().availableProcessors())) {
                    launch(Dispatchers.Default) {
                        for (file in fileQueue) {
                            if (!isActive) break
                            processFile(file, metaCache, ledger, now, hashQueue, resultQueue)
                        }
                    }
                }

                val workers = List(apiWorkers) {
                    launch(Dispatchers.IO) {
                        for (job in hashQueue) {
                            // One network call per distinct hash, however many files share it.
                            // Claiming the hash and registering the promise happen under the
                            // same lock, so exactly one worker owns the call and the others
                            // await it instead of racing it.
                            val hash = job.hash.hash
                            var mine: CompletableDeferred<GameMetadata?>? = null
                            val pending = synchronized(hashDedup) {
                                hashDedup[hash] ?: CompletableDeferred<GameMetadata?>().also {
                                    mine = it
                                    hashDedup[hash] = it
                                }
                            }

                            val meta: GameMetadata?
                            val owned = mine
                            if (owned != null) {
                                // The owner settles its promise on every path. Not for an
                                // abort or a cancelled caller, nor for any other exception,
                                // which fails the scope: the followers are children of the
                                // same scope and are cancelled with it either way. It is
                                // for a lookup that throws a cancellation of its own, a
                                // timeout inside it say, while the scan goes on. This
                                // worker then ends without the scope noticing, and a
                                // follower awaiting a promise nobody completes waits for
                                // ever; tried with such a lookup, scan() never returned.
                                // No lookup here throws one, so no test reaches this.
                                meta = try {
                                    lookup.lookup(hash)
                                } catch (t: Throwable) {
                                    synchronized(hashDedup) { hashDedup.remove(hash) }
                                    owned.completeExceptionally(t)
                                    throw t
                                }
                                // Only a real answer is worth remembering. A failure is
                                // left uncached and unrecorded so the next scan asks
                                // again — recording it would write the game off for good.
                                if (meta == null) synchronized(hashDedup) { hashDedup.remove(hash) }
                                owned.complete(meta)
                            } else {
                                // Someone else is already asking. Note that a file arriving
                                // while a failing lookup is still in flight now shares that
                                // failure instead of repeating the call; one that arrives
                                // after it has finished finds the entry gone and retries, as
                                // before.
                                meta = pending.await()
                            }
                            resultQueue.send(ResultJob(job, meta, failed = meta == null))
                        }
                    }
                }

                launch {
                    feeder.join()
                    producers.forEach { it.join() }
                    hashQueue.close()
                    workers.forEach { it.join() }
                    resultQueue.close()
                }

                val step = (total / 50).coerceAtLeast(1)
                for (r in resultQueue) {
                    val job = r.job
                    when {
                        // Already counted and recorded by the producer; nothing to add.
                        r.skipped || r.cached || r.preRecorded -> {
                            if (r.skipped) skipped++
                            if (r.cached)  cached++
                        }
                        // No answer at all. Recorded as a retry and never as a verdict:
                        // caching a refusal as "this game has no achievements" is the
                        // bug that cost a whole run, 85 answers of 913 requests.
                        r.failed -> {
                            failedLookups++
                            ledger.record(canonical(job.file), ScanLedger.State.API_RETRY,
                                          job.fileSize, job.lastModified, now,
                                          detail = "the source did not answer")
                        }
                        // RA knows the dump, but only as one it does not consider playable
                        // as it is: a Virtual Console Metroid comes back as 1100001487,
                        // game 1487 untested. Not a match — the Web API has no game under
                        // that number, and writing one produced a junk metadata file the
                        // index discarded — but an answer all the same, and kept like a
                        // miss. As API_RETRY, which is never cached, the file was read in
                        // full and asked about again on every scan: 65 files and 130
                        // requests a scan in one library, reported as a source that did
                        // not answer.
                        r.meta != null && VirtualGameId.isVirtual(r.meta.gameId) -> {
                            ledger.record(canonical(job.file), ScanLedger.State.NOT_FOUND,
                                          job.fileSize, job.lastModified, now,
                                          gameId = r.meta.gameId,
                                          detail = "RetroAchievements knows this dump only by virtual id " +
                                                   "${r.meta.gameId}: ${VirtualGameId.describe(r.meta.gameId)}")
                        }
                        // A usable match needs a title, not just an id. Blank rather than
                        // empty, as on Android: a title of spaces would be written here and
                        // then distrusted by preloadMetadataCache, so the same ROM would be
                        // asked about and counted new every scan.
                        r.meta != null && r.meta.gameId > 0 && r.meta.title.isNotBlank() -> {
                            writeMetadata(job, r.meta); newEntries++
                            ledger.record(canonical(job.file), ScanLedger.State.MATCHED,
                                          job.fileSize, job.lastModified, now,
                                          gameId = r.meta.gameId)
                        }
                        // A real id with no title. RaApiHashLookup answers null for one
                        // now, but the interface does not forbid it, and it is not a match
                        // or a verdict either. Retried, not written off.
                        r.meta != null && r.meta.gameId > 0 -> {
                            ledger.record(canonical(job.file), ScanLedger.State.API_RETRY,
                                          job.fileSize, job.lastModified, now,
                                          gameId = r.meta.gameId,
                                          detail = "the source knows id ${r.meta.gameId} but gave no title")
                        }
                        // gameId 0: the source was asked and said no. A real verdict,
                        // remembered until its TTL runs out.
                        else -> ledger.record(canonical(job.file), ScanLedger.State.NOT_FOUND,
                                              job.fileSize, job.lastModified, now)
                    }
                    processed++
                    if (processed % step == 0 || processed == total) {
                        onProgress(Progress(processed, total, r.job.file.name, newEntries, cached, skipped))
                    }

                    // A refused key fails every match from here on, and the misses in
                    // between, answered without the key, kept the failure count below
                    // the limit: with one ROM in four unknown, 24 went through with no
                    // abort, and when one did come it blamed a source that "stopped
                    // answering". Stopped at the first refusal, and named.
                    if (lookup.authRejected) {
                        throw ScanAborted("RetroAchievements refused the API key " +
                                          "($processed of $total processed)")
                    }

                    // Once RetroAchievements has stopped answering there is nothing to
                    // gain from grinding through the rest of the library: every file
                    // would be recorded as unknown. Stop, keep what was found, say so.
                    if (lookup.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        throw ScanAborted(
                            "the lookup source stopped answering: $failedLookups of " +
                            "$processed failed, ${lookup.consecutiveFailures} in a row")
                    }
                }
            }
        } catch (a: ScanAborted) {
            abortReason = a.why
            BridgeLog.e(TAG, "aborted after $processed/$total: $abortReason")
        } finally {
            // Cancel, not close: closing refuses new sends but leaves one already
            // blocked on a full buffer where it is. Cancelling wakes it. Redundant
            // after a ScanAborted — the scope has cancelled its children by then —
            // and not redundant when the *caller* cancels us.
            fileQueue.cancel(); hashQueue.cancel(); resultQueue.cancel()
        }

        val indexed = writeDiscoveryIndex()
        ledger.save { f, text -> BridgePaths.writeAtomic(f, text) }
        val states = ledger.counts()
        BridgeLog.i(TAG, "scan ${if (abortReason.isEmpty()) "complete" else "aborted"}: " +
                         "$processed/$total processed, $newEntries new, " +
                         "$cached cached, $skipped skipped, $indexed indexed, " +
                         "$failedLookups lookups failed; " +
                         states.entries.sortedBy { it.key.name }
                               .joinToString(", ") { "${it.key.name}=${it.value}" })
        return Summary(
            total = total, processed = processed, newEntries = newEntries,
            cachedHits = cached, skippedPlatforms = skipped, indexed = indexed,
            aborted = abortReason.isNotEmpty(), reason = abortReason,
            failedLookups = failedLookups, states = states,
            ambiguousArchives = ledger.ambiguousArchives()
        )
    }

    private suspend fun processFile(
        file: File,
        metaCache: Map<String, CachedMeta>,
        ledger: ScanLedger,
        now: Long,
        hashQueue: Channel<HashJob>,
        resultQueue: Channel<ResultJob>
    ) {
        val rawPlatform = file.parentFile?.name ?: "unknown"
        val platform    = FuzzyMatch.normalizePlatform(rawPlatform)
        val path        = canonical(file)
        val size        = file.length()
        val modified    = file.lastModified()

        if (platform in UNSUPPORTED_PLATFORMS) {
            ledger.record(path, ScanLedger.State.UNSUPPORTED, size, modified, now,
                          detail = "RetroAchievements does not cover $platform")
            resultQueue.send(ResultJob(HashJob(file, "", HashResult("", 0), rawPlatform, 0, 0),
                                       null, skipped = true))
            return
        }

        val cacheKey = FuzzyMatch.makeCacheKey(file.nameWithoutExtension, rawPlatform)

        // Unchanged since the last scan: the metadata is already on disk and the
        // index rebuild will pick it up, so skip both hashing and the network.
        // `fileMd5` is also required: metadata written before plain hashes
        // existed would otherwise be cached forever, and a field added to the
        // schema would stay empty on every library that had already been
        // scanned once — the incremental skip is what would hide it.
        val known = metaCache[cacheKey]
        if (known != null && known.hash.isNotEmpty() && known.fileMd5.isNotEmpty() &&
            known.fileSize == size && known.lastModified == modified) {
            ledger.record(path, ScanLedger.State.MATCHED, size, modified, now)
            resultQueue.send(ResultJob(
                HashJob(file, cacheKey, HashResult(known.hash, 0), rawPlatform, size, modified),
                null, cached = true))
            return
        }

        // A verdict from a previous scan that is still standing. The one that
        // matters is NOT_FOUND: a library of mostly-unknown ROMs used to ask the
        // source about every one of them on every run, because a miss left no
        // trace to find. A refusal is never stored as a verdict, so this can only
        // ever skip an answer the source actually gave.
        val settled = ledger.canSkip(path, size, modified, now)
        if (settled != null && settled.state != ScanLedger.State.MATCHED) {
            ledger.count(settled.state)
            resultQueue.send(ResultJob(
                HashJob(file, cacheKey, HashResult("", 0), rawPlatform, size, modified),
                null, preRecorded = true))
            return
        }

        // Throwable: one file that cannot be read must cost that file, not the
        // scan. Cancellation still propagates.
        //
        // Interruptible, because a hash is a blocking read that can take minutes:
        // a 7.9 GiB ISO read cold beside three others takes about nine. Under a
        // plain withContext an abort or a cancel had to wait for every digest in
        // flight before the scope could return; this interrupts the thread, so a
        // read that honours interrupts stops where it is. A java.io stream does
        // not, and a loop reading one has to look for the interrupt itself.
        val outcome = try { runInterruptible(Dispatchers.IO) { hasher.hashDetailed(file.absolutePath, rawPlatform) } }
                      catch (c: kotlinx.coroutines.CancellationException) { throw c }
                      catch (t: Throwable) {
                          // An interrupted read rarely says so: a file channel throws
                          // ClosedByInterruptException, which is an IOException. When the
                          // scan is being cancelled, that is the cancellation and not a
                          // broken file — recording it as HASH_FAILED would put the abort's
                          // own side effect in the summary as a fault in the library.
                          currentCoroutineContext().ensureActive()
                          BridgeLog.w(TAG, "hash failed: ${file.name}", t)
                          HashOutcome.Failed(t.message ?: t.javaClass.simpleName)
                      }

        when (outcome) {
            is HashOutcome.AmbiguousArchive -> {
                // Deliberately not hashed. The old rule picked the largest entry,
                // which could be a bonus disc or an included patch, and recorded the
                // resulting miss as a game the database does not have.
                BridgeLog.w(TAG, "${file.name}: ${outcome.candidates.size} entries could each " +
                                 "be the ROM (${outcome.candidates.take(3).joinToString(", ")})")
                ledger.record(path, ScanLedger.State.AMBIGUOUS_ARCHIVE, size, modified, now,
                              detail = outcome.candidates.joinToString(", "))
                resultQueue.send(ResultJob(
                    HashJob(file, cacheKey, HashResult("", 0), rawPlatform, size, modified),
                    null, preRecorded = true))
            }
            is HashOutcome.Failed -> {
                // A file that may be fixed is retried; one the hasher knows it cannot
                // hash is kept for a while, or every scan would ask the same question.
                val state = if (outcome.retryable) ScanLedger.State.HASH_FAILED
                            else ScanLedger.State.UNHASHABLE
                ledger.record(path, state, size, modified, now, detail = outcome.reason)
                resultQueue.send(ResultJob(
                    HashJob(file, cacheKey, HashResult("", 0), rawPlatform, size, modified),
                    null, preRecorded = true))
            }
            is HashOutcome.Ok -> {
                throttleMs().takeIf { it > 0 }?.let { delay(it) }
                hashQueue.send(HashJob(file, cacheKey, outcome.result, rawPlatform, size, modified))
            }
        }
    }

    /** One spelling per file, so two roots reaching it by different symlinks agree. */
    private fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun writeMetadata(job: HashJob, meta: GameMetadata) {
        val now = BridgePaths.epochSeconds()
        val json = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("gameId",   meta.gameId)
            .put("title",    meta.title)
            .put("platform", FuzzyMatch.normalizePlatform(job.platform))
            .put("cacheKey", job.cacheKey)
            .put("ra", JSONObject()
                .put("points", 0).put("progress", 0.0).put("unlocked", 0)
                .put("total", meta.numAchievements)
                .put("imageIcon", meta.imageIcon)
                .put("fetchedAt", now))
            .put("rom", JSONObject()
                .put("hash", job.hash.hash)
                // Plain hashes of the ROM bytes, for databases that match by
                // file rather than by title. Distinct from `hash`, which is the
                // rcheevos one — see HashResult for why they cannot be shared.
                .put("fileMd5", job.hash.fileMd5)
                .put("fileCrc32", job.hash.fileCrc32)
                .put("fileSize", job.fileSize)
                .put("lastModified", job.lastModified))
            .put("fetchedAt", now)
        BridgePaths.writeAtomic(paths.metadata(meta.gameId.toString()), json.toString(2))
    }

    /**
     * Rebuilds `metadata/_index.json` from every per-game file: `games[]` for the
     * discovered-games list, `byKey{}` for reverse lookup from a ROM cache key.
     */
    private fun writeDiscoveryIndex(): Int {
        val files = paths.metadata.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_")
        } ?: return 0

        val games = JSONArray()
        val byKey = JSONObject()
        for (f in files) {
            try {
                val j        = JSONObject(f.readText())
                val ra       = j.optJSONObject("ra") ?: continue
                val gameId   = j.optInt("gameId")
                val title    = j.optString("title")
                // Blank, like the collector and the cache: a legacy title of spaces
                // listed here would be a game with no name in the list.
                if (gameId <= 0 || title.isBlank()) continue

                val entry = JSONObject()
                    .put("gameId", gameId)
                    .put("title", title)
                    .put("platform", j.optString("platform"))
                    .put("total", ra.optInt("total"))
                    .put("imageIcon", ra.optString("imageIcon"))
                games.put(entry)

                j.optString("cacheKey").takeIf { it.isNotEmpty() }?.let { byKey.put(it, entry) }
            } catch (e: Exception) {
                BridgeLog.w(TAG, "index skipped ${f.name}: ${e.message}")
            }
        }

        val payload = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("fetchedAt", BridgePaths.epochSeconds())
            .put("count", games.length())
            .put("games", games)
            .put("byKey", byKey)
        BridgePaths.writeAtomic(paths.discoveryIndex, payload.toString(2))
        return games.length()
    }

    private fun preloadMetadataCache(): Map<String, CachedMeta> {
        val map = HashMap<String, CachedMeta>()
        val files = paths.metadata.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_")
        } ?: return map
        for (f in files) {
            try {
                val j   = JSONObject(f.readText())
                val rom = j.optJSONObject("rom") ?: continue
                val key = j.optString("cacheKey")
                if (key.isEmpty()) continue
                // An id with no title is not a match, and the collector does not
                // write one. Files written before it stopped are still on disk —
                // 27 of 732 on the tablet — and the index drops every one of them,
                // so trusting them as cached kept their ROMs out of it, and away
                // from the network, for as long as the ROM stayed unchanged.
                // Ignored here, they are looked up again; the file itself is left
                // alone, for a real match to overwrite if one ever comes.
                if (j.optInt("gameId") <= 0 || j.optString("title").isBlank()) continue
                map[key] = CachedMeta(rom.optString("hash"), rom.optString("fileMd5"),
                                      rom.optLong("fileSize"), rom.optLong("lastModified"))
            } catch (_: Exception) {}
        }
        return map
    }

    private data class HashJob(
        val file: File, val cacheKey: String, val hash: HashResult,
        val platform: String, val fileSize: Long, val lastModified: Long
    )
    private data class ResultJob(
        val job: HashJob, val meta: GameMetadata?,
        val cached: Boolean = false, val skipped: Boolean = false,
        val failed: Boolean = false,  // never got an answer — not the same as "unknown"
        /** The producer already wrote this file's verdict; the collector only counts it. */
        val preRecorded: Boolean = false
    )
    private data class CachedMeta(val hash: String, val fileMd5: String,
                                  val fileSize: Long, val lastModified: Long)

    companion object {
        private const val TAG = "RomScanPipeline"
        const val DEFAULT_HASH_WORKERS = 4
        // Matches RaHashLookup.MAX_PARALLEL: more workers than permits only
        // queues them behind the semaphore.
        const val DEFAULT_API_WORKERS  = 2
        const val MAX_CONSECUTIVE_FAILURES = 8

        /** Platforms RetroAchievements does not cover — skipped before any I/O. */
        val UNSUPPORTED_PLATFORMS = setOf(
            "switch", "psvita", "wiiu", "pc", "windows", "android", "ios", "3ds", "n3ds"
        )
    }
}
