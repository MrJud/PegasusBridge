package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.FuzzyMatch
import com.pegasus.bridge.core.RcConsoles
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
 * since the last scan, files of a collection nobody can hash for, files of a
 * format nobody reads and placeholders bypass both hashing and the network.
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
     * Which extensions count as ROMs in a given directory, in place of the
     * scan's own answer: the built-in set and what the folder's collection
     * declares, which [RomScanner.scanWithCollections] reads from the
     * collection's metafile.
     *
     * Both shells used to supply this, each reading the metafile through the
     * module that parses one, because this module could not. It can now, and
     * neither shell gives anything here. What is left is for a test that
     * wants a scan to count fewer files than it would.
     */
    private val extensionsFor: ((File) -> Set<String>)? = null,
    /**
     * How many results go by between two reports of progress, for a library
     * of [total] files. The last result is reported whatever this says.
     *
     * A fiftieth of the library unless the caller says otherwise, which is
     * what the daemon wants: it publishes every report it is given, to memory
     * and to a file, and a rescan of a few thousand cached files is over in a
     * second or two. The Android service asks for every result and decides for
     * itself which are worth a record, because its rule goes by the time since
     * the last one as well, and a clock is no use to a caller that is only
     * told of every hundredth result.
     */
    private val reportStep: (total: Int) -> Int = { (it / 50).coerceAtLeast(1) },
    /**
     * The least time, in milliseconds, between two saves of the ledger while
     * a scan runs. 0 saves after every result that settled something, which
     * is what a test wants.
     */
    private val ledgerSaveEveryMs: Long = LEDGER_SAVE_EVERY_MS
) {

    // Refused here rather than discovered mid-scan. With no hash workers nothing
    // reads the file queue, and with no API workers nothing reads the hash queue.
    // On a library bigger than that queue's buffer, the stage feeding it waits
    // forever and the scan never returns; on a smaller one it returns having
    // quietly skipped the work. Neither says why.
    init {
        require(hashWorkers > 0) { "hashWorkers must be positive, was $hashWorkers" }
        require(apiWorkers > 0) { "apiWorkers must be positive, was $apiWorkers" }
        require(ledgerSaveEveryMs >= 0) { "ledgerSaveEveryMs must not be negative, was $ledgerSaveEveryMs" }
    }

    // How every save of the ledger is written: forced to disk before it takes
    // the place of the ledger there is. The ledger is the one file a scan
    // writes over itself as it goes, and with nothing forced a power cut
    // could leave its name on an empty file: every answer of "no" a library
    // had been given, asked for again. Only the ledger: a metadata file and
    // the index are written whole once and a scan puts back whichever is
    // lost, and the record of a running job is not worth a force each time.
    private val writeLedger: (File, String) -> Unit =
        { f, text -> BridgePaths.writeAtomic(f, text, durable = true) }

    /**
     * How far a scan has got. The seven counts after [currentFile] are the ones
     * [Summary] ends with, and in every report they add up to [processed].
     */
    data class Progress(
        val processed: Int,
        val total: Int,
        val currentFile: String,
        val newEntries: Int,
        val cachedHits: Int,
        val skippedPlatforms: Int,
        val unmatched: Int = 0,
        val incompatible: Int = 0,
        val hashFailed: Int = 0,
        val failedLookups: Int = 0
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
        /**
         * Which of the three aborts it was, when [aborted]. Null otherwise.
         *
         * For a caller that has to tell a person what to do next. [reason] cannot
         * serve: it is a sentence for the log, with this run's counts in it, and
         * the advice differs by the kind and not by the wording.
         */
        val abortCause: AbortCause? = null,
        /**
         * Lookups that brought nothing usable back — distinct from "not in the
         * database". The ones the source never answered, and the ones it answered
         * with a real id and no title: neither a match nor a verdict, recorded as
         * a retry like the others, and until it was counted here counted nowhere.
         */
        val failedLookups: Int = 0,
        /**
         * Asked about, and the source does not know the dump: a verdict of id 0,
         * reached by this scan or by an earlier one and still standing.
         *
         * With [newEntries], [cachedHits], [skippedPlatforms], [incompatible],
         * [hashFailed] and [failedLookups] this adds up to [processed]: every file
         * the collector saw is in exactly one of the seven. While there were four,
         * a miss was in none of them: a library the database had never heard of
         * had 0 in each, and only [states] said that anything had been looked at.
         * [skippedPlatforms] takes a verdict of UNSUPPORTED found standing as well
         * as one decided now, and the placeholders: files that stand for a game
         * the library does not hold, with no more in them to look for than in a
         * file of a platform nobody covers.
         *
         * The names of this one and the next are the keys the Android job record
         * already has for the same two numbers.
         */
        val unmatched: Int = 0,
        /**
         * The source holds the dump only under a [VirtualGameId], as one it does
         * not consider playable as it is. An answer and not a match, whether given
         * now or still standing: a verdict of KNOWN_UNSUPPORTED.
         */
        val incompatible: Int = 0,
        /**
         * Files that gave no hash to ask about: unreadable, an archive with
         * several entries that could each be the ROM or with none that is, one
         * the hasher knows it cannot hash, or one of a format that is not read
         * at all and was not opened. [states] keeps the five apart.
         */
        val hashFailed: Int = 0,
        /**
         * Every file the scan looked at, counted by what happened to it.
         *
         * The point of the ledger, surfaced: a run that indexed nine of ten files
         * can now say whether the tenth was a miss, an unreadable file, an archive
         * nobody could resolve, or a source that stopped answering.
         */
        val states: Map<ScanLedger.State, Int> = emptyMap(),
        /**
         * Archives holding several plausible ROMs, with the candidates: the ones
         * [states] counts as AMBIGUOUS_ARCHIVE, so only files this scan looked at.
         */
        val ambiguousArchives: List<Pair<String, String>> = emptyList()
    )

    /** Why a scan stopped itself. What a person can do about it differs from one to the next. */
    enum class AbortCause {
        /**
         * The source refused the credentials, or there is no key to send
         * and nothing was asked. Nothing changes until the key does.
         */
        KEY_REFUSED,
        /** A request failed and the device says it has no connection. Nothing changes until it has one. */
        OFFLINE,
        /** [MAX_CONSECUTIVE_FAILURES] lookups in a row got no answer. Waiting may be enough. */
        SOURCE_DOWN
    }

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
    private class ScanAborted(val why: String, val kind: AbortCause) : Exception(why)

    /**
     * [onCounted] is told how many files the walk found, once, before any of
     * them is read. It is not called for a library with none. The first
     * result can be a long way from the walk, behind a first file to hash,
     * which can be a disc image, and a first lookup to wait for; until then
     * the count is the only thing there is to tell. A report with nothing
     * processed would have said it too, and would have reached every caller
     * of [onProgress] as a result with no file.
     */
    suspend fun scan(
        roots: List<String>,
        onCounted: (total: Int) -> Unit = {},
        onProgress: (Progress) -> Unit = {}
    ): Summary {
        paths.ensureAll()

        // One resolver for one scan, as it asks to be: it never reads a
        // metafile twice, so one kept longer would not hear of an edit.
        val files = RomScanner.scanWithCollections(roots, CollectionResolver(), extensionsFor)
        val total = files.size
        BridgeLog.i(TAG, "found $total ROM files under ${roots.size} root(s)")
        if (total == 0) return Summary(0, 0, 0, 0, 0, writeDiscoveryIndex())

        val meta = preloadMetadataCache()
        BridgeLog.i(TAG, "loaded ${meta.byKey.size} cached entries of ${meta.keyOfGame.size} " +
                         "games for incremental scan")
        // The files this walk found, by key. A match the ledger holds is
        // believed only while its game's metadata file names a ROM that is
        // here and is the game's still, and this is how a file finds the
        // one that is named to ask that of it.
        val walked = files.groupBy {
            FuzzyMatch.makeCacheKey(it.file.nameWithoutExtension, it.collection.shortName)
        }

        // Verdicts from previous scans, including the ones that are not matches.
        // Without it a library of mostly-unknown ROMs asked the source about every
        // one of them on every run, and could never say why any of them was missing.
        //
        // Each is kept under a number worked out from what it was reached
        // with, this hasher among the rest, and stands only while its file
        // would be given the same number today. The line that number is made
        // from is logged, so that two logs side by side say what changed
        // between two builds when a scan reads a library again.
        val recipe = HashRecipe(hasher.engine)
        BridgeLog.i(TAG, "verdicts are kept under the recipe ${recipe.global}")
        val ledger = ScanLedger(File(paths.cache, ScanLedger.FILE_NAME), recipe)
        val now = BridgePaths.epochSeconds()

        val fileQueue    = Channel<RomScanner.ScannedFile>(capacity = 64)
        val hashQueue    = Channel<HashJob>(capacity = 32)
        val resultQueue  = Channel<ResultJob>(capacity = 128)
        // hash -> the one lookup for it, in flight or finished.
        //
        // Holding the promise rather than the result is what makes the de-duplication
        // real. The map used to hold results: a worker read it, released the lock and
        // then called the network, so a second worker could read the same absent hash
        // in that gap and call as well. The lock covered the map, never the decision.
        val hashDedup    = mutableMapOf<String, CompletableDeferred<LookupOutcome>>()

        // One of the seven for every result, and no result in two: together they
        // are `processed`, at every report and at the end.
        var processed = 0; var newEntries = 0; var cached = 0; var skipped = 0
        var unmatched = 0; var incompatible = 0; var hashFailed = 0
        var failedLookups = 0
        var abortReason = ""
        var abortCause: AbortCause? = null
        // When the ledger was last written, by a clock that does not jump.
        var savedAt = System.nanoTime()

        try {
            // In here and not straight after the walk, so that a caller whose
            // record cannot be written ends the scan as any other failure
            // does, with the index rebuilt on the way out.
            onCounted(total)
            // No key, no scan, and said before a file is read. Every request
            // carries the key, so each file that needs a lookup would be read
            // and hashed for an answer that cannot come, a disc image among
            // them; and a scan whose files are all settled would end as done
            // and hide that the next new ROM cannot be looked up. Thrown in
            // here so that it ends as any stop does: the ledger and the index
            // are written as they stand, and what earlier scans settled is
            // left as it was.
            if (lookup.keyMissing) {
                throw ScanAborted("no RetroAchievements API key is configured " +
                                  "(0 of $total processed)", AbortCause.KEY_REFUSED)
            }
            coroutineScope {
                val feeder = launch(Dispatchers.IO) {
                    try {
                        for (f in files) fileQueue.send(f)
                    } finally {
                        fileQueue.close()
                    }
                }

                val producers = List(hashProducers(hashWorkers)) {
                    launch(Dispatchers.Default) {
                        for (scanned in fileQueue) {
                            if (!isActive) break
                            processFile(scanned, meta, walked, ledger, now, hashQueue, resultQueue)
                        }
                    }
                }

                val workers = List(apiWorkers) {
                    launch(Dispatchers.IO) {
                        for (job in hashQueue) {
                            // Asked, as the producers ask, because nothing else here
                            // need notice that the scan has been stopped. A cancel
                            // shows only where a coroutine suspends: a hash waiting in
                            // the buffer is taken without suspending, and so is a result
                            // sent while the queue has room. A lookup that suspends is
                            // cancelled there, as RaApiHashLookup is at its next
                            // request. One that holds its thread is not, and its worker
                            // went on through every hash still queued, 32 of them, with
                            // the scope waiting for it.
                            if (!isActive) break
                            // One lookup per distinct hash, however many files share it.
                            // Claiming the hash and registering the promise happen under the
                            // same lock, so exactly one worker owns the call and the others
                            // await it instead of racing it.
                            //
                            // Per hash and consoles it may be of, since the answer is
                            // to both: the same hash put to other consoles is another
                            // question. Files of one collection that share a hash were
                            // hashed as one console, so they still share the lookup.
                            val hash = job.hash.hash
                            val consoles = consolesOf(job)
                            val key = "$hash|${consoles.joinToString(",")}"
                            var mine: CompletableDeferred<LookupOutcome>? = null
                            val pending = synchronized(hashDedup) {
                                hashDedup[key] ?: CompletableDeferred<LookupOutcome>().also {
                                    mine = it
                                    hashDedup[key] = it
                                }
                            }

                            val outcome: LookupOutcome
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
                                outcome = try {
                                    lookup.lookup(hash, consoles)
                                } catch (t: Throwable) {
                                    synchronized(hashDedup) { hashDedup.remove(key) }
                                    owned.completeExceptionally(t)
                                    throw t
                                }
                                // Only a real answer is worth remembering. A failure is
                                // left uncached and unrecorded so the next scan asks
                                // again — recording it would write the game off for good.
                                if (outcome is LookupOutcome.Failed) {
                                    synchronized(hashDedup) { hashDedup.remove(key) }
                                }
                                owned.complete(outcome)
                            } else {
                                // Someone else is already asking. Note that a file arriving
                                // while a failing lookup is still in flight now shares that
                                // failure instead of repeating the call; one that arrives
                                // after it has finished finds the entry gone and retries, as
                                // before.
                                outcome = pending.await()
                            }
                            resultQueue.send(ResultJob(job, outcome))
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

                // At least 1, whatever the caller's rule comes to: the count of
                // results is divided by it.
                val step = reportStep(total).coerceAtLeast(1)
                for (r in resultQueue) {
                    val job = r.job
                    val verdict = r.preRecorded
                    when {
                        // Already recorded by the producer; nothing to add.
                        r.skipped -> skipped++
                        r.cached  -> cached++
                        // Recorded by the producer as well, or by an earlier scan and
                        // still standing. Counted by what the verdict is, so that a
                        // rescan puts a file where the scan that asked about it did.
                        // These went into none of the counts, and on a second scan of
                        // a library of misses every one of them was 0.
                        verdict != null -> when (verdict) {
                            ScanLedger.State.NOT_FOUND -> unmatched++
                            ScanLedger.State.KNOWN_UNSUPPORTED -> incompatible++
                            ScanLedger.State.UNSUPPORTED,
                            ScanLedger.State.PLACEHOLDER -> skipped++
                            ScanLedger.State.HASH_FAILED,
                            ScanLedger.State.UNHASHABLE,
                            ScanLedger.State.UNSUPPORTED_FORMAT,
                            ScanLedger.State.NO_PLAYABLE_ENTRY,
                            ScanLedger.State.AMBIGUOUS_ARCHIVE -> hashFailed++
                            // Neither arrives this way: a match that stands, in the
                            // ledger or in its metadata file, is sent as cached, one
                            // that does not is hashed again, and a retry is never left
                            // standing. Listed so that a state added to the ledger does
                            // not compile until it has been given a count here.
                            ScanLedger.State.MATCHED   -> cached++
                            ScanLedger.State.API_RETRY -> failedLookups++
                        }
                        // What the lookup said of the hash. Every answer it has is named
                        // here, so that one added to it does not compile until it has
                        // been given a count and a record.
                        else -> when (val outcome = r.outcome) {
                            // No answer at all. Recorded as a retry and never as a verdict:
                            // caching a refusal as "this game has no achievements" is the
                            // bug that cost a whole run, 85 answers of 913 requests. A real
                            // id that came with no title is one of these as well: neither a
                            // match nor a verdict, and no lookup can hand it over as either.
                            //
                            // Null is a result that says nothing of itself, which nothing
                            // here sends. Were one sent, it would be asked about again, and
                            // not written off as a miss.
                            is LookupOutcome.Failed, null -> {
                                failedLookups++
                                ledger.record(canonical(job.file), job.collection, ScanLedger.State.API_RETRY,
                                              job.fileSize, job.lastModified, now,
                                              detail = "the source did not answer")
                            }
                            // RA knows the dump, but only as one it does not consider playable
                            // as it is: a Virtual Console Metroid comes back as 1100001487,
                            // game 1487 untested. Not a match — the Web API has no game under
                            // that number, and writing one produced a junk metadata file the
                            // index discarded — but an answer all the same, and kept as one.
                            // As API_RETRY, which is never cached, the file was read in
                            // full and asked about again on every scan: 65 files and 130
                            // requests a scan in one library, reported as a source that did
                            // not answer.
                            //
                            // Under the game's own id, 1487, with the reason beside it. It
                            // was kept as a miss under the number as sent, and whoever read
                            // the ledger had to know the bases to learn which game the dump
                            // is of. No metadata file even so: that would say the game is
                            // in the library, and this dump earns nothing for it.
                            //
                            // The lookup a scan is built with no longer answers this. It
                            // looks a hash up in the lists of consoles, which hold the
                            // hashes RetroAchievements lets count and no others, so such a
                            // dump is now a hash it does not know and is counted with the
                            // misses. The branch is for a lookup that does answer it, as an
                            // audit's recorded answers do; the entries it wrote stand in a
                            // ledger until their month is out.
                            is LookupOutcome.IdOnly -> {
                                incompatible++
                                ledger.record(canonical(job.file), job.collection, ScanLedger.State.KNOWN_UNSUPPORTED,
                                              job.fileSize, job.lastModified, now,
                                              gameId = outcome.gameId,
                                              detail = outcome.reason.words)
                            }
                            is LookupOutcome.Match -> {
                                writeMetadata(job, outcome.game); newEntries++
                                ledger.record(canonical(job.file), job.collection, ScanLedger.State.MATCHED,
                                              job.fileSize, job.lastModified, now,
                                              gameId = outcome.game.gameId)
                            }
                            // The hash is in none of the lists of the consoles it may be
                            // of, each of them read whole. A real verdict, remembered
                            // until its TTL runs out.
                            LookupOutcome.NotFound -> {
                                unmatched++
                                ledger.record(canonical(job.file), job.collection, ScanLedger.State.NOT_FOUND,
                                              job.fileSize, job.lastModified, now)
                            }
                        }
                    }

                    // The ledger goes to disk as the scan goes, and not only as
                    // it ends. Both saves at the end are code the scan has to
                    // reach, and a process that is killed reaches neither: the
                    // system taking the service away, a power button, a kill.
                    // Every answer of "no" a long first scan had been given
                    // was then lost, and each of those files was read and
                    // asked about again.
                    //
                    // Here, after the result has been recorded, so that a
                    // match is in its metadata file before it is in the
                    // ledger: a kill between the two leaves a file the next
                    // scan adopts, never an entry with no file to stand on.
                    // Only when something new was recorded, so that a rescan
                    // of cached files writes nothing, and no more often than
                    // every [ledgerSaveEveryMs], because each save writes the
                    // whole ledger. Nothing is forgotten here: that is for a
                    // scan that gets to its end.
                    if (ledger.dirty && (System.nanoTime() - savedAt) / 1_000_000 >= ledgerSaveEveryMs) {
                        ledger.save(writeLedger)
                        savedAt = System.nanoTime()
                    }

                    processed++
                    if (processed % step == 0 || processed == total) {
                        onProgress(Progress(processed, total, r.job.file.name, newEntries, cached, skipped,
                                            unmatched, incompatible, hashFailed, failedLookups))
                    }

                    // A refused key fails every lookup from here on: each request
                    // carries it, and no list can be had without one. Stopped at the
                    // first refusal, and named. Left to the count of failures the
                    // scan would stop eight files later and blame a source that
                    // "stopped answering", with the advice to wait.
                    if (lookup.authRejected) {
                        throw ScanAborted("RetroAchievements refused the API key " +
                                          "($processed of $total processed)", AbortCause.KEY_REFUSED)
                    }

                    // No connection: the lookup has given up on a request because
                    // the device says so, and the rest of the library would go the
                    // same way. Before the count of failures is looked at, so that
                    // a scan that has both is told what it can act on: this used to
                    // end eight lookups and half a minute later as a source that
                    // "stopped answering", with the advice to wait.
                    //
                    // Looked at only on a result whose lookup failed. The lookup
                    // says it has no connection before its result is in the queue,
                    // and files that need no lookup go past in their thousands
                    // meanwhile: stopped on one of those, the scan would end with
                    // no failed lookup in its counts and the file that had failed
                    // in no ledger, since what is still queued at an abort is
                    // dropped.
                    if (r.outcome is LookupOutcome.Failed && lookup.offline) {
                        throw ScanAborted("no internet connection " +
                                          "($processed of $total processed)", AbortCause.OFFLINE)
                    }

                    // Once RetroAchievements has stopped answering there is nothing to
                    // gain from grinding through the rest of the library: every file
                    // would be recorded as unknown. Stop, keep what was found, say so.
                    if (lookup.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        throw ScanAborted(
                            "the lookup source stopped answering: $failedLookups of " +
                            "$processed failed, ${lookup.consecutiveFailures} in a row",
                            AbortCause.SOURCE_DOWN)
                    }
                }
            }
        } catch (a: ScanAborted) {
            abortReason = a.why
            abortCause = a.kind
            BridgeLog.e(TAG, "aborted after $processed/$total: $abortReason")
        } catch (t: Throwable) {
            // A caller that cancelled, or something that failed on the way: a lookup
            // that threw, a metadata file that could not be written. There is no
            // summary to return, but what the scan found out is as true as after
            // an abort, and it used to be dropped here: the index and the ledger
            // were written below, on the way to the return, which this does not
            // reach. The matches survived in their own files. The answers that
            // were "no" are nowhere but in the ledger, so a Cancel late in a long
            // first scan had every one of those files read and asked about again.
            //
            // Both calls are plain blocking code, and have to stay that: this
            // coroutine may be cancelled already, and in a cancelled coroutine the
            // first suspension point throws instead of going on. The scope does
            // not return before its children have finished, so nothing else is
            // writing the ledger by now.
            //
            // A write that fails must not take the place of what ended the scan.
            // save() keeps its own failure to itself; the index does not.
            //
            // The ledger is saved as it stands. Nothing is dropped from it here:
            // forget() is below, for a scan that gets as far as its summary.
            BridgeLog.w(TAG, "stopped after $processed/$total by ${t.javaClass.simpleName}; " +
                             "keeping the index and the ledger")
            runCatching { writeDiscoveryIndex() }
                .onFailure { BridgeLog.w(TAG, "could not rebuild the index: ${it.message}") }
            ledger.save(writeLedger)
            throw t
        } finally {
            // Cancel, not close: closing refuses new sends but leaves one already
            // blocked on a full buffer where it is, and cancelling wakes it. By
            // the time this runs there is none to wake, whichever way the scan
            // ended. The scope above does not come back before its children have
            // finished, after an abort, after a cancel by the caller and after a
            // failure alike, which is what the catch above counts on. Taken
            // out, the tests still passed and a stopped scan took no longer. It
            // stays as a second line: were a sender ever started outside the
            // scope, this is what would release it.
            fileQueue.cancel(); hashQueue.cancel(); resultQueue.cancel()
        }

        // Pruned here, on the way to the summary, and not where the ledger is
        // opened, which came to the same while this was the only save. The catch
        // above saves too. A scan cancelled or broken off, with a root that was
        // not there when it started, then wrote down the loss of every verdict
        // that root had; and Cancel is what a person does on seeing the card is
        // out. Such a scan only adds now. Nothing in between depends on the
        // order: the ledger is asked only about files the walk found, which are
        // never the ones dropped, and the counts are of this run.
        ledger.forget(files.map { canonical(it.file) }.toSet(), roots.map { canonical(File(it)) })
        ledger.save(writeLedger)
        // The index after the ledger, and not before it as it was. A write of
        // the index that fails is thrown from here, so that a scan whose index
        // is not on disk does not end as done, and nothing under it is reached.
        // The ledger was under it. A scan that got to its end and could not
        // write the index then left no ledger either, and the next one read and
        // asked about every file again, the misses with the rest.
        val indexed = writeDiscoveryIndex()
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
            aborted = abortReason.isNotEmpty(), reason = abortReason, abortCause = abortCause,
            failedLookups = failedLookups, unmatched = unmatched,
            incompatible = incompatible, hashFailed = hashFailed, states = states,
            ambiguousArchives = ledger.ambiguousArchives()
        )
    }

    private suspend fun processFile(
        scanned: RomScanner.ScannedFile,
        meta: MetaCache,
        walked: Map<String, List<RomScanner.ScannedFile>>,
        ledger: ScanLedger,
        now: Long,
        hashQueue: Channel<HashJob>,
        resultQueue: Channel<ResultJob>
    ) {
        val file        = scanned.file
        val collection  = scanned.collection
        // What the file's collection calls its platform, and not the name of
        // the folder the file is in. The two are one for `nes/Game.nes` and
        // for nothing kept a level down: `psx/<game>/Game.bin` was platform
        // `<game>`, under a key no theme asks by, and the files in a folder
        // under `switch` were hashed and asked about.
        val rawPlatform = collection.shortName
        val path        = canonical(file)
        val size        = file.length()
        val modified    = file.lastModified()

        // Counted with the platforms skipped, whether decided now or found
        // standing: for whoever reads the counts it is a file there was no
        // game in to look for, and not one that failed.
        suspend fun placeholder(detail: String) {
            ledger.record(path, collection, ScanLedger.State.PLACEHOLDER, size, modified, now, detail = detail)
            resultQueue.send(ResultJob(HashJob(file, "", HashResult("", 0), collection, size, modified),
                                       preRecorded = ScanLedger.State.PLACEHOLDER))
        }

        // Whether the file can be hashed at all, from the table of consoles
        // and from its name, before a byte of it is read. Ahead of the two
        // skips below, so that it is decided again on every scan: it costs
        // nothing, and a verdict kept from an older table would outlive the
        // table. A list of nine platforms stood here, RetroAchievements'
        // gaps as somebody remembered them: an Amiga disk or a CD-i image
        // was hashed as whatever its extension suggested, and asked about.
        //
        // The plans that say how to hash a file are the hasher's to follow:
        // every file that gets past here is handed to it with its collection.
        val row = RcConsoles.resolve(collection.shortName, collection.dirName)
        val plan = ConsoleChoice.choose(row, file.extension, size, insideArchive = false)

        // The collection: nobody can hash for its console, whatever the
        // file. Counted as a platform skipped, as it always was.
        if (plan is ConsoleChoice.Plan.Unsupported) {
            ledger.record(path, collection, ScanLedger.State.UNSUPPORTED, size, modified, now, detail = plan.reason)
            resultQueue.send(ResultJob(HashJob(file, "", HashResult("", 0), collection, 0, 0),
                                       skipped = true))
            return
        }

        // A file with nothing in it stands for a game that is not there,
        // whatever it is called. Known from its size, so it is here with
        // the verdicts that cost no read, and ahead of the one on the
        // file's format: an empty `.cso` is no more a packed disc than an
        // empty `.nes` is a cartridge. And ahead of the hasher's plans, of
        // which one needs no byte of the file: an arcade set is hashed by
        // its name, and an empty `.zip` among them was asked about as one.
        //
        // No bytes is also the size the system gives for a file that is not
        // there, and one the walk found can be gone by now, with the card it
        // was on. So a file of no size is asked whether it is a file still,
        // and no other is. What is not goes on to the hasher, which says so,
        // and is tried again at the next scan.
        if (size == 0L && file.isFile) {
            placeholder("empty file")
            return
        }

        /** Whether the file is a few lines of text and nothing else, which takes reading it. */
        suspend fun isTextStub(): Boolean = size in 1..PlaceholderRule.TEXT_LIMIT &&
            runInterruptible(Dispatchers.IO) {
                PlaceholderRule.classify(file.extension, size) { head(file, PlaceholderRule.TEXT_LIMIT + 1) }
            } == PlaceholderRule.Kind.TEXT_STUB

        // The file: its collection can be hashed and it cannot. It gives
        // no hash to ask about, and is counted with the others that do
        // not.
        //
        // Unless it is a sentence under that name. A library that keeps a
        // line of text for a game it does not hold calls it what the game
        // would be called, `.cso` or `.cdi` as readily as `.gbc`, and a
        // packed disc image of forty bytes is no more a disc than an empty
        // one is. Asked of the name alone, each of those ended every scan
        // among the files that could not be hashed, which reads as a
        // fault in the library: on one tablet thirty of them. So a file
        // small enough to be a stub is looked at before its format is held
        // against it, and one found standing as a placeholder is left so
        // without a read. No real image of a format nobody reads is that
        // small, so this opens nothing a scan would otherwise leave shut
        // but stubs. A file whose name says it is no game at all, a `.txt`
        // or a `.cfg` a collection lists, is what it says and not a stub.
        if (plan is ConsoleChoice.Plan.UnsupportedFormat) {
            if (size in 1..PlaceholderRule.TEXT_LIMIT && file.extension.lowercase() !in ConsoleChoice.NOT_A_ROM) {
                val standing = ledger.canSkip(path, collection, size, modified, now)
                if (standing?.state == ScanLedger.State.PLACEHOLDER) {
                    ledger.count(path, standing)
                    resultQueue.send(ResultJob(HashJob(file, "", HashResult("", 0), collection, size, modified),
                                               preRecorded = ScanLedger.State.PLACEHOLDER))
                    return
                }
                if (isTextStub()) {
                    placeholder("text file, $size bytes: not a ROM image")
                    return
                }
            }
            ledger.record(path, collection, ScanLedger.State.UNSUPPORTED_FORMAT, size, modified, now,
                          detail = plan.reason)
            resultQueue.send(ResultJob(
                HashJob(file, "", HashResult("", 0), collection, size, modified),
                preRecorded = ScanLedger.State.UNSUPPORTED_FORMAT))
            return
        }

        val cacheKey = FuzzyMatch.makeCacheKey(file.nameWithoutExtension, rawPlatform)

        val settled = ledger.canSkip(path, collection, size, modified, now)

        // A match from a previous scan that is still standing: the file is the
        // one it was, under the number it was matched under, and the ledger
        // says which game it is. Neither read nor asked about.
        //
        // The game's metadata file has to be on disk, and to name a ROM that
        // is here and is the game's: this file, or another the walk found
        // that the ledger holds as a match of the same game, standing as
        // this one is. The theme finds a game by the key that file carries,
        // and one game has one file however many ROMs are of it, a sheet and
        // its track, two dumps, two discs. When the ROM it names is deleted,
        // or written over by another game or by a placeholder, or the file
        // was written before metadata carried a key, the one left has to be
        // identified again so that the file is written under its name;
        // skipped on the ledger's word alone it would stay out of the
        // index's keys for good. A file under the name is not enough: the
        // key would go on leading to this game from a ROM that is another.
        //
        // Nothing is recorded: the entry stands as it is, game and all.
        if (settled != null && settled.state == ScanLedger.State.MATCHED && settled.gameId > 0) {
            val named = meta.keyOfGame[settled.gameId]
            val vouched = named != null && (named == cacheKey || walked[named].orEmpty().any { other ->
                val standing = ledger.canSkip(canonical(other.file), other.collection,
                                              other.file.length(), other.file.lastModified(), now)
                standing?.state == ScanLedger.State.MATCHED && standing.gameId == settled.gameId
            })
            if (vouched) {
                ledger.count(path, settled)
                resultQueue.send(ResultJob(
                    HashJob(file, cacheKey, HashResult("", 0), collection, size, modified),
                    cached = true))
                return
            }
        }

        // A match the ledger cannot vouch for, taken from the metadata file
        // that describes this very file: its key, its size and its date. That
        // is every match of a ledger written before a cached match kept its
        // game, every match after the number of its collection has changed,
        // and every match when the ledger is lost. Not read and not asked
        // about, and written into the ledger with its game, so that the next
        // scan needs the ledger alone.
        //
        // `fileMd5` was required here as well, so that a file written before
        // the plain hashes existed would gain them. Nothing reads them from a
        // metadata file, the scrapers hash for themselves, and a whole read
        // of every ROM is not worth a field nobody asks for.
        val known = meta.byKey[cacheKey]
        if (known != null && known.hash.isNotEmpty() &&
            known.fileSize == size && known.lastModified == modified) {
            ledger.record(path, collection, ScanLedger.State.MATCHED, size, modified, now,
                          gameId = known.gameId)
            resultQueue.send(ResultJob(
                HashJob(file, cacheKey, HashResult(known.hash, 0), collection, size, modified),
                cached = true))
            return
        }

        // Any other verdict from a previous scan that is still standing. The one
        // that matters is NOT_FOUND: a library of mostly-unknown ROMs used to ask the
        // source about every one of them on every run, because a miss left no
        // trace to find. A refusal is never stored as a verdict, so this can only
        // ever skip an answer the source actually gave. A match that got here
        // has no metadata file to stand on, and is identified again below.
        if (settled != null && settled.state != ScanLedger.State.MATCHED) {
            ledger.count(path, settled)
            resultQueue.send(ResultJob(
                HashJob(file, cacheKey, HashResult("", 0), collection, size, modified),
                preRecorded = settled.state))
            return
        }

        // The other placeholder: a line or two of text under a ROM's name.
        // That takes reading it, so it comes after the three skips, which
        // leave it standing without a read, and only a file small enough to
        // be one is opened. What is refused here would otherwise go on as
        // the MD5 of a sentence. The words are of the file and not of a
        // game: the line a launcher keeps for one is such a text as well.
        if (isTextStub()) {
            placeholder("text file, $size bytes: not a ROM image")
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
        val outcome = try { runInterruptible(Dispatchers.IO) { hasher.hashDetailed(file.absolutePath, collection) } }
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
                ledger.record(path, collection, ScanLedger.State.AMBIGUOUS_ARCHIVE, size, modified, now,
                              detail = outcome.candidates.joinToString(", "))
                resultQueue.send(ResultJob(
                    HashJob(file, cacheKey, HashResult("", 0), collection, size, modified),
                    preRecorded = ScanLedger.State.AMBIGUOUS_ARCHIVE))
            }
            is HashOutcome.Failed -> {
                // A file that may be fixed is retried; one the hasher knows it cannot
                // hash is kept for a while, or every scan would ask the same question.
                val state = if (outcome.retryable) ScanLedger.State.HASH_FAILED
                            else ScanLedger.State.UNHASHABLE
                ledger.record(path, collection, state, size, modified, now, detail = outcome.reason)
                resultQueue.send(ResultJob(
                    HashJob(file, cacheKey, HashResult("", 0), collection, size, modified),
                    preRecorded = state))
            }
            // The two answers of an archive that was opened and gave nothing to
            // hash. Neither has a hash to ask about, and each is kept: the
            // archive will hold the same at the next scan.
            is HashOutcome.NoPlayableEntry -> {
                ledger.record(path, collection, ScanLedger.State.NO_PLAYABLE_ENTRY, size, modified, now,
                              detail = outcome.reason)
                resultQueue.send(ResultJob(
                    HashJob(file, cacheKey, HashResult("", 0), collection, size, modified),
                    preRecorded = ScanLedger.State.NO_PLAYABLE_ENTRY))
            }
            is HashOutcome.UnsupportedFormat -> {
                ledger.record(path, collection, ScanLedger.State.UNSUPPORTED_FORMAT, size, modified, now,
                              detail = outcome.reason)
                resultQueue.send(ResultJob(
                    HashJob(file, cacheKey, HashResult("", 0), collection, size, modified),
                    preRecorded = ScanLedger.State.UNSUPPORTED_FORMAT))
            }
            is HashOutcome.Ok -> {
                throttleMs().takeIf { it > 0 }?.let { delay(it) }
                hashQueue.send(HashJob(file, cacheKey, outcome.result, collection, size, modified))
            }
        }
    }

    /**
     * The first [limit] bytes of [file], or all of it when it has fewer.
     * Read until the stream ends or the room does: one read may give less
     * than was asked for with more to come.
     */
    private fun head(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            val n = input.read(buffer, filled, limit - filled)
            if (n < 0) break
            filled += n
        }
        buffer.copyOf(filled)
    }

    /** One spelling per file, so two roots reaching it by different symlinks agree: the walk's own. */
    private fun canonical(file: File): String = RomScanner.canonical(file)

    private fun writeMetadata(job: HashJob, meta: GameMetadata) {
        val now = BridgePaths.epochSeconds()
        val json = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("gameId",   meta.gameId)
            .put("title",    meta.title)
            .put("platform", FuzzyMatch.normalizePlatform(job.collection.shortName))
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
                // Both keys are always written, and both are empty for a file
                // the scan did not read whole.
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

    /**
     * What the metadata files on disk say, read once as a scan starts.
     *
     * [byKey] is by the key of the ROM a file describes, for a match that is
     * taken from its file. [keyOfGame] is by game, for a match the ledger
     * holds: the key its file names, or "" for a file written before they
     * carried one. It has every game the index would list, with or without a
     * `rom` block, since what it answers is whether the theme can find the
     * game and under which name.
     */
    private class MetaCache(val byKey: Map<String, CachedMeta>, val keyOfGame: Map<Int, String>)

    private fun preloadMetadataCache(): MetaCache {
        val byKey = HashMap<String, CachedMeta>()
        val keyOfGame = HashMap<Int, String>()
        val files = paths.metadata.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_")
        } ?: return MetaCache(byKey, keyOfGame)
        for (f in files) {
            try {
                val j   = JSONObject(f.readText())
                // An id with no title is not a match, and the collector does not
                // write one. Files written before it stopped are still on disk —
                // 27 of 732 on the tablet — and the index drops every one of them,
                // so trusting them as cached kept their ROMs out of it, and away
                // from the network, for as long as the ROM stayed unchanged.
                // Ignored here, they are looked up again; the file itself is left
                // alone, for a real match to overwrite if one ever comes.
                val gameId = j.optInt("gameId")
                if (gameId <= 0 || j.optString("title").isBlank()) continue
                // The index's own third condition, so that a game is known here
                // exactly when it is listed there.
                if (j.optJSONObject("ra") == null) continue
                val key = j.optString("cacheKey")
                keyOfGame[gameId] = key
                val rom = j.optJSONObject("rom") ?: continue
                if (key.isEmpty()) continue
                byKey[key] = CachedMeta(gameId, rom.optString("hash"),
                                        rom.optLong("fileSize"), rom.optLong("lastModified"))
            } catch (_: Exception) {}
        }
        return MetaCache(byKey, keyOfGame)
    }

    /**
     * The consoles a file's hash is to be looked up in, RetroAchievements'
     * ids, in the order to try them: the one the file was hashed as, then
     * what that one stands for when it was taken from the file's extension
     * ([STANDS_FOR]), then its collection's own, then the rest of the
     * collection's family, then what [FILED_BESIDE] adds to any of those.
     *
     * More than the one it was hashed as because that one is where the
     * hasher's rules put the file, and RetroAchievements files a hash under
     * the game's console: a `.gb` file of a Game Boy Color game is hashed as
     * a Game Boy's and listed under Game Boy Color. A lookup by hash alone
     * found it wherever it was, and one console's list would lose it.
     *
     * A file hashed as no console, by a hasher that does not say, is left
     * with its collection's.
     */
    private fun consolesOf(job: HashJob): List<Int> {
        val row = RcConsoles.resolve(job.collection.shortName, job.collection.dirName) as? RcConsoles.Hashable
        val hashedAs = job.hash.consoleId
        // A console of the row's family is the collection's word for what the
        // file is. Any other is rcheevos' reading of the extension: all there
        // is for a collection with no row, and for a stray in one that has.
        val byExtension = row == null || hashedAs !in row.family
        val first = listOf(hashedAs) + (if (byExtension) STANDS_FOR[hashedAs].orEmpty() else emptyList()) +
                    listOfNotNull(row?.console) + row?.family.orEmpty().sorted()
        return (first + first.flatMap { FILED_BESIDE[it].orEmpty() }).filter { it > 0 }.distinct()
    }

    private data class HashJob(
        val file: File, val cacheKey: String, val hash: HashResult,
        /**
         * The file's collection. Its short name is the platform the metadata
         * is written with, and the whole of it is what the collector's
         * verdicts are kept under in the ledger, as the producer's are.
         */
        val collection: CollectionRef, val fileSize: Long, val lastModified: Long
    )
    private data class ResultJob(
        val job: HashJob,
        /**
         * What the lookup said of the file's hash. Null for a file whose hash
         * was never put to it, for which one of the three below says why.
         */
        val outcome: LookupOutcome? = null,
        val cached: Boolean = false, val skipped: Boolean = false,
        /**
         * The verdict the producer already wrote for this file, or found standing
         * from an earlier scan. The collector records nothing for it and only
         * counts it, which takes knowing what the verdict was.
         */
        val preRecorded: ScanLedger.State? = null
    )
    private data class CachedMeta(val gameId: Int, val hash: String,
                                  val fileSize: Long, val lastModified: Long)

    companion object {
        private const val TAG = "RomScanPipeline"
        const val DEFAULT_HASH_WORKERS = 4

        /**
         * How many files a scan built with [hashWorkers] reads and hashes at
         * once: that many, or one for each core where the machine has fewer.
         * [scan] starts this many producers, and a shell that tells somebody
         * the count asks here, so that it says the one a scan runs with.
         */
        fun hashProducers(hashWorkers: Int): Int =
            hashWorkers.coerceAtMost(Runtime.getRuntime().availableProcessors())

        // A lookup is a read of a list in memory, and a list is fetched by
        // one worker at a time whatever their number: the second is there
        // so that hashes of a console whose list is loaded are answered
        // while another console's is being fetched.
        const val DEFAULT_API_WORKERS  = 2
        const val MAX_CONSECUTIVE_FAILURES = 8

        /**
         * Consoles rcheevos hashes alike and RetroAchievements keeps apart,
         * which no row's family holds: a DSi game in a folder of DS games is
         * hashed as a DS game, 18, and listed under DSi, 78.
         *
         * Here and not in [RcConsoles]: a row's family says what a file may
         * be hashed as, and is part of what its collection's recipe number
         * is made from, so a console added there would have every verdict of
         * the collection worked out again for a hash that does not change.
         */
        private val FILED_BESIDE = mapOf(18 to listOf(78), 78 to listOf(18))

        /**
         * What the console of a file stands for when rcheevos took it from
         * the file's extension. It has one console for an extension, and for
         * some the one it names is the first of several that hash a file
         * alike: its table sends a `.fds` to the NES, a `.bin` to the Mega
         * Drive "since they all use the same hashing algorithm", and a disc
         * to the Sega CD, which "handles both Sega CD and Saturn". The hash
         * is right whichever of them the file is of, and RetroAchievements
         * lists it under the game's own console. Asked by the hash alone it
         * was found there; in the list of the console rcheevos named, a
         * Famicom disk or a Saturn disc in a folder nothing is known of is
         * a hash that is not known, for fourteen days.
         *
         * Only for a console taken from the extension, a collection with no
         * row or a stray in one that has. Where a row says what its files
         * are, the console is the row's and stands for itself, and a Mega
         * Drive cartridge RetroAchievements does not have would otherwise
         * have eight more lists asked for.
         *
         * Not here: a file with an extension rcheevos has never heard of, in
         * a collection with no row. It is hashed whole, as a Game Boy
         * cartridge, and which console has it nothing says.
         */
        private val STANDS_FOR = mapOf(
            // .bin: the 32X, the Atari 2600, the Super Cassette Vision, the
            // Channel F, the Supervision, the Mega Duck, the Arcadia 2001
            // and the Interton VC 4000.
            1 to listOf(10, 25, 55, 57, 63, 69, 73, 74),
            // .gb and .gbc go by the extension, and a cartridge that runs on
            // both consoles is written either way.
            4 to listOf(6),
            6 to listOf(4),
            // .fds: the Famicom Disk System.
            7 to listOf(81),
            // .cue, .chd and .iso: the Saturn.
            9 to listOf(39),
            // .rom: the Channel F. .dsk, which is tried as an MSX disk before
            // any other: the Amstrad CPC, the Apple II, the ZX Spectrum.
            29 to listOf(37, 38, 57, 59),
            // .tap: the Oric and the ZX Spectrum.
            30 to listOf(32, 59),
            // .nib, tried as an Apple II disk first: the Commodore 64.
            38 to listOf(30)
        )

        /**
         * The least time between two writes of the ledger while a scan runs.
         * A write is made as a result comes in, so what a scan that is
         * killed can lose is what it settled in this long after its last
         * write, however long ago that was.
         */
        const val LEDGER_SAVE_EVERY_MS = 10_000L
    }
}
