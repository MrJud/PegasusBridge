package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.SchemaVersion
import org.json.JSONObject

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
 */
object ScanJobRecord {

    /**
     * A scan's counts so far, under the names the record gives them.
     *
     * The last two are null for a caller that does not keep them: the service's
     * own loop counts a file that would not hash with the ones that are not in
     * the database, and only logs the lookups that failed. Neither key is
     * written then. A 0 it never counted would read as "none failed".
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
        val hashFailed: Int? = null,
        /** Lookups that brought nothing usable back. */
        val failedLookups: Int? = null
    )

    /**
     * The record before the first result.
     *
     * For the caller to write before it walks the directories, which on a card
     * can take a while: a job that has left no record by the theme's fifth poll
     * is taken for one that finished.
     */
    fun started(jobId: String, startedAt: Long, updatedAt: Long = BridgePaths.epochSeconds()): JSONObject =
        pending(jobId, "running", 0.0, "Scanning ROM folders…", Counts(0, 0, 0, 0, 0), startedAt, updatedAt)

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
     * lookups that got no answer, from a caller that counts them.
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
     * can do about it and differs between the two causes. [identified] is how
     * many matches the scan had written by then.
     */
    fun abortAdvice(
        cause: RomScanPipeline.AbortCause, raUser: String,
        processed: Int, total: Int, identified: Int
    ): String = when (cause) {
        RomScanPipeline.AbortCause.KEY_REFUSED ->
            "RetroAchievements refused the API key for $raUser after $processed of $total files " +
            "($identified identified). Nothing was recorded as missing. " +
            "Copy the Web API key from your RetroAchievements settings into credentials.json " +
            "and scan again."
        RomScanPipeline.AbortCause.SOURCE_DOWN ->
            "RetroAchievements stopped responding after $processed of $total files " +
            "($identified identified). Nothing was recorded as missing. " +
            "Wait a few minutes and scan again — it will resume where it left off."
    }

    /** [running] for a report of the pipeline's, which keeps all seven counts. */
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
            // The pipeline names the cause of every abort it makes. The type lets
            // one through without, and that one must not come out as done either.
            summary.aborted -> error(jobId,
                abortAdvice(summary.abortCause ?: RomScanPipeline.AbortCause.SOURCE_DOWN, raUser,
                            summary.processed, summary.total, summary.newEntries),
                startedAt, updatedAt)
            summary.total == 0 -> noRoms(jobId, counts, startedAt, updatedAt)
            else -> done(jobId, counts, startedAt, updatedAt)
        }
    }

    /**
     * Whether a result is worth a record, for a caller that is told of results
     * more often than it should write.
     *
     * About fifty records over a scan and never one for fewer than ten results,
     * the step the Android service has always written by, and the last result
     * whatever the gap. It is a rule on the gap since the record before, and
     * not on [processed] being a multiple of that step. The pipeline reports
     * every `total / 50` results, and the multiples of the two steps meet only
     * now and then: a library of 480 files would have had a record every 90.
     */
    fun due(processed: Int, total: Int, lastPublished: Int): Boolean =
        processed == total || processed - lastPublished >= (total / 50).coerceAtLeast(10)

    private fun clause(count: Int?, what: String): String =
        if (count != null && count > 0) ", $count $what" else ""

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
            .also { j -> counts.hashFailed?.let { j.put("hashFailed", it) } }
            .also { j -> counts.failedLookups?.let { j.put("failedLookups", it) } }
}
