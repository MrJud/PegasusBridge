package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.SchemaVersion
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A ROM scan's record in `pending/{jobId}.json` as the Android service writes
 * it for the theme's popup to poll: what it says while the scan runs, the
 * sentence it ends on, and the error that takes their place.
 *
 * These were put together inside HasherService, which no test in this
 * repository can run, and every string in them is one the theme either shows
 * as it comes or takes apart. Here each is a function of plain values that
 * returns the record and writes nothing, so a test can put in front of the
 * theme's reader the record the service puts on the tablet. The strings are
 * the service's, to the character.
 *
 * What the theme takes from a record: `status`, of which it tests only
 * "running" and "error"; `progress`; `message`, shown whole unless it has the
 * shape `[3/40] name`, which gives it the two numbers and the name;
 * `newEntries` and `cachedHits`; and `error`, shown in place of the message.
 *
 * The desktop daemon's record of a job is JobRegistry's, and is not built here.
 * What the daemon takes from this is [abortAdvice], so that a scan which stopped
 * itself is told in the same words on both.
 */
object ScanJobRecord {

    /**
     * A scan's counts so far, under the names the record gives them. Every
     * result is in one of the seven and in no other, so together they are the
     * results the record reports.
     */
    data class Counts(
        val newEntries: Int,
        val cachedHits: Int,
        val skippedPlatforms: Int,
        /** Looked up and genuinely not in RetroAchievements — an answer, not a failure. */
        val unmatched: Int,
        /** Held by RetroAchievements only as a virtual id — incompatible, untested, needs a patch. */
        val incompatible: Int,
        /** Files that gave no hash to ask about. */
        val hashFailed: Int,
        /** Lookups that brought nothing usable back. */
        val failedLookups: Int
    )

    /**
     * The record before anything is known of the library.
     *
     * For the caller to write before it walks the directories, which on a card
     * can take a while: a job that has left no record by the theme's fifth poll
     * is taken for one that finished.
     */
    fun started(jobId: String, startedAt: Long, updatedAt: Long = BridgePaths.epochSeconds()): JSONObject =
        pending(jobId, "running", 0.0, "Scanning ROM folders…", Counts(0, 0, 0, 0, 0, 0, 0), startedAt, updatedAt)

    /**
     * The record once the files are counted, until the first result.
     *
     * [started] with another message, and nothing else of it moved: the walk
     * is over, so "Scanning ROM folders…" is no longer what is going on, and
     * the first result can be a long way off, behind a disc image to hash or
     * a lookup that is being retried. A sentence, and not `[0/13]` with a
     * name after it: the theme takes a message of that shape apart and shows
     * what follows the numbers as the file in hand. This one it shows whole,
     * as it shows the one before it.
     */
    fun checking(
        jobId: String, total: Int,
        startedAt: Long, updatedAt: Long = BridgePaths.epochSeconds()
    ): JSONObject =
        pending(jobId, "running", 0.0, "Checking $total ROM ${if (total == 1) "file" else "files"}…",
                Counts(0, 0, 0, 0, 0, 0, 0), startedAt, updatedAt)

    /**
     * The record after a result. The name goes after the two numbers as it is:
     * the theme reads them from the start of the message and takes the rest
     * for the name, so a file called `[BIOS] Something` is shown as that.
     */
    fun running(
        jobId: String, processed: Int, total: Int, fileName: String, counts: Counts,
        startedAt: Long, updatedAt: Long = BridgePaths.epochSeconds()
    ): JSONObject =
        pending(jobId, "running", if (total <= 0) 0.0 else processed.toDouble() / total,
                "[$processed/$total] $fileName", counts, startedAt, updatedAt)

    /**
     * The record of a scan that ran to its end.
     *
     * The dumps RA does not support get a clause of their own, and only when
     * there are any. Left out, they would vanish from the summary altogether —
     * a library of them would finish "0 new, 0 cached, 0 skipped, 0 not in the
     * database". The same goes for the files that could not be hashed and the
     * lookups that got no answer.
     */
    fun done(
        jobId: String, counts: Counts,
        startedAt: Long, updatedAt: Long = BridgePaths.epochSeconds()
    ): JSONObject =
        pending(jobId, "done", 1.0,
                "Done — ${counts.newEntries} new, ${counts.cachedHits} cached, " +
                "${counts.skippedPlatforms} skipped, ${counts.unmatched} not in the database" +
                clause(counts.incompatible, "dumps RetroAchievements does not support") +
                clause(counts.hashFailed, "could not be hashed") +
                clause(counts.failedLookups, "lookups got no answer"),
                counts, startedAt, updatedAt)

    /** The record of a scan whose roots hold no ROM: done, with nothing to count. */
    fun noRoms(
        jobId: String, counts: Counts,
        startedAt: Long, updatedAt: Long = BridgePaths.epochSeconds()
    ): JSONObject =
        pending(jobId, "done", 1.0, "No ROMs found", counts, startedAt, updatedAt)

    /**
     * The record of a scan that did not finish: cancelled, stopped by the
     * pipeline, or ended by something thrown. No progress and no counts, as the
     * service has always written it. The theme shows [error] and nothing else.
     */
    fun error(
        jobId: String, error: String,
        startedAt: Long, updatedAt: Long = BridgePaths.epochSeconds()
    ): JSONObject =
        base(jobId, "error", startedAt, updatedAt).put("error", error)

    /**
     * What to tell the person whose scan stopped itself, which is what they
     * can do about it and differs from one cause to the next. [identified] is
     * how many matches the scan had written by then.
     *
     * [raUser] is whose key was refused, and the sentence says so when there
     * is somebody to name. The Android service never starts a scan without a
     * user. The desktop daemon does, and a key refused there read "refused the
     * API key for  after 3 of 3 files": a blank user is left out, " for "
     * with it.
     */
    fun abortAdvice(
        cause: RomScanPipeline.AbortCause, raUser: String,
        processed: Int, total: Int, identified: Int
    ): String = when (cause) {
        RomScanPipeline.AbortCause.KEY_REFUSED ->
            "RetroAchievements refused the API key" + (if (raUser.isBlank()) "" else " for $raUser") +
            " after $processed of $total files " +
            "($identified identified). Nothing was recorded as missing. " +
            "Copy the Web API key from your RetroAchievements settings into credentials.json " +
            "and scan again."
        // The cause first and what to do last: the theme shows an error on one
        // line and cuts a long one in the middle.
        RomScanPipeline.AbortCause.OFFLINE ->
            "No internet connection: stopped after $processed of $total files " +
            "($identified identified). Nothing was recorded as missing. " +
            "Connect and scan again — it will resume where it left off."
        RomScanPipeline.AbortCause.SOURCE_DOWN ->
            "RetroAchievements stopped responding after $processed of $total files " +
            "($identified identified). Nothing was recorded as missing. " +
            "Wait a few minutes and scan again — it will resume where it left off."
    }

    /**
     * [abortAdvice] for a summary of the pipeline's. The pipeline names the
     * cause of every abort it makes; the type lets one through without, and
     * that one is told as an outage.
     */
    fun abortAdvice(summary: RomScanPipeline.Summary, raUser: String): String =
        abortAdvice(summary.abortCause ?: RomScanPipeline.AbortCause.SOURCE_DOWN, raUser,
                    summary.processed, summary.total, summary.newEntries)

    /** [running] for a report of the pipeline's. */
    fun running(
        jobId: String, progress: RomScanPipeline.Progress,
        startedAt: Long, updatedAt: Long = BridgePaths.epochSeconds()
    ): JSONObject =
        running(jobId, progress.processed, progress.total, progress.currentFile,
                Counts(progress.newEntries, progress.cachedHits, progress.skippedPlatforms,
                       progress.unmatched, progress.incompatible,
                       progress.hashFailed, progress.failedLookups),
                startedAt, updatedAt)

    /**
     * The record a scan ends on, from what the pipeline returned.
     *
     * A scan the pipeline cut short is an error here, though the pipeline
     * returns it like any other. `status` is all the theme goes by: written as
     * done with the abort in a field of its own, a scan RetroAchievements
     * stopped answering a tenth of the way through would be announced as
     * complete. The text is [abortAdvice] and not the summary's `reason`, which
     * is a line for the log.
     */
    fun finished(
        jobId: String, summary: RomScanPipeline.Summary, raUser: String,
        startedAt: Long, updatedAt: Long = BridgePaths.epochSeconds()
    ): JSONObject {
        val counts = Counts(summary.newEntries, summary.cachedHits, summary.skippedPlatforms,
                            summary.unmatched, summary.incompatible,
                            summary.hashFailed, summary.failedLookups)
        return when {
            // Asked of `aborted` and not of the cause: a summary that names none
            // must not come out as done either.
            summary.aborted -> error(jobId, abortAdvice(summary, raUser), startedAt, updatedAt)
            summary.total == 0 -> noRoms(jobId, counts, startedAt, updatedAt)
            else -> done(jobId, counts, startedAt, updatedAt)
        }
    }

    /**
     * Whether a result is worth a record, for a caller that is told of every
     * result, which is more often than it should write.
     *
     * Four reasons, any one of which is enough:
     *
     * - nothing has been recorded yet. The first result gets a record however
     *   small a part of the library it is. Until it had one, a scan of 13
     *   files whose lookups all failed showed "Scanning ROM folders…" at 0%
     *   for the 31 seconds it took to stop: eight results are not ten;
     * - it is the last result;
     * - a fiftieth of the library has gone by since the last record, or ten
     *   results where that is more: the step the Android service has always
     *   written by. On a library read from its cache, thousands of results a
     *   second, this is the rule that holds the writes to about fifty. It
     *   goes by the gap since the record before and not by [processed] being
     *   a multiple of the step, since a record written for another reason
     *   moves the count the gap is taken from;
     * - [RECORD_INTERVAL_MS] has gone by since the last record. On a first
     *   scan a result is a file hashed and a lookup answered, perhaps two a
     *   second, and a fiftieth of a large library took minutes to go by with
     *   the record standing still. This adds at most one write a second.
     *
     * [lastPublished] is the count the last record carried, 0 before the
     * first, and [sinceLastMs] the time since it was written, by a clock that
     * cannot be set: [Pace] keeps both.
     */
    fun due(processed: Int, total: Int, lastPublished: Int, sinceLastMs: Long): Boolean =
        lastPublished == 0 || processed == total ||
        processed - lastPublished >= (total / 50).coerceAtLeast(10) ||
        sinceLastMs >= RECORD_INTERVAL_MS

    /**
     * Half of the two seconds between the theme's polls, so that each poll
     * finds a record written since the one before it, of a scan that has
     * moved. Shorter would be writes nobody reads.
     */
    const val RECORD_INTERVAL_MS = 1_000L

    /**
     * [due] for a scan under way: what the last record carried and when it
     * was written, kept from one result to the next. One for a scan, asked by
     * the collector's callback alone, so it needs no lock.
     *
     * The clock is nanoTime, as the lookup's pacing is and for its reason:
     * the wall clock can be stepped, and a step backwards would hold the
     * record still for as long as the step. A test gives its own.
     */
    class Pace(private val nanoTime: () -> Long = System::nanoTime) {
        private var published = 0
        private var publishedAt = 0L

        /** Whether to write a record for this result. A yes is taken as the record written. */
        fun due(processed: Int, total: Int): Boolean {
            val now = nanoTime()
            val sinceLastMs = TimeUnit.NANOSECONDS.toMillis(now - publishedAt)
            if (!ScanJobRecord.due(processed, total, published, sinceLastMs)) return false
            published = processed
            publishedAt = now
            return true
        }
    }

    private fun clause(count: Int, what: String): String =
        if (count > 0) ", $count $what" else ""

    private fun base(jobId: String, status: String, startedAt: Long, updatedAt: Long): JSONObject =
        JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("jobId", jobId)
            .put("verb", "scan")
            .put("status", status)
            .put("startedAt", startedAt)
            .put("updatedAt", updatedAt)

    private fun pending(
        jobId: String, status: String, progress: Double, message: String, counts: Counts,
        startedAt: Long, updatedAt: Long
    ): JSONObject =
        base(jobId, status, startedAt, updatedAt)
            .put("progress", progress)
            .put("message", message)
            .put("newEntries", counts.newEntries)
            .put("cachedHits", counts.cachedHits)
            .put("skippedPlatforms", counts.skippedPlatforms)
            .put("unmatched", counts.unmatched)
            .put("incompatible", counts.incompatible)
            .put("hashFailed", counts.hashFailed)
            .put("failedLookups", counts.failedLookups)
}
