package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.SchemaVersion
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks the jobs that are too slow to answer inside one request — in practice
 * only a ROM scan. Everything else the daemon does finishes in seconds and is
 * answered synchronously, which is the point of moving to HTTP.
 *
 * State is also mirrored to `pending/{jobId}.json` and `done/{jobId}.done` so a
 * theme still reading files keeps working during the transition.
 */
class JobRegistry(private val paths: BridgePaths) {

    enum class State { RUNNING, DONE, ERROR }

    data class Job(
        val id: String,
        val verb: String,
        @Volatile var state: State = State.RUNNING,
        @Volatile var progress: Double = 0.0,
        @Volatile var message: String = "",
        @Volatile var error: String? = null,
        @Volatile var result: JSONObject? = null,
        /**
         * Extra fields published at the top level while the job runs.
         *
         * A ROM scan reports running counts — new, cached, skipped — and the
         * theme's progress popup reads them beside `progress` and `message`,
         * exactly as the Android service used to write them into `pending/`.
         */
        @Volatile var counters: JSONObject? = null,
        val startedAt: Long = BridgePaths.epochSeconds()
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("schemaVersion", SchemaVersion.CURRENT)
            .put("jobId", id)
            .put("verb", verb)
            .put("status", when (state) {
                State.RUNNING -> "running"
                State.DONE    -> "done"
                State.ERROR   -> "error"
            })
            .put("progress", progress)
            .put("message", message)
            .put("startedAt", startedAt)
            .put("updatedAt", BridgePaths.epochSeconds())
            .also { j -> error?.let { j.put("error", it) } }
            .also { j -> result?.let { j.put("result", it) } }
            .also { j -> counters?.let { c -> c.keys().forEach { k -> j.put(k, c.get(k)) } } }
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val counter = AtomicLong()

    fun newId(prefix: String): String =
        "${prefix}_${System.currentTimeMillis()}_${counter.incrementAndGet()}"

    fun create(id: String, verb: String): Job {
        val job = Job(id, verb)
        jobs[id] = job
        publish(job)
        return job
    }

    /**
     * Accepts an id the caller chose, so a client can keep polling the same job
     * across a reload. Rejected if it could escape the jobs directory or collide
     * with a live job, in which case the caller gets a generated one.
     */
    fun createWithClientId(requested: String?, verb: String): Job =
        create(usable(requested) ?: newId(verb.substringBefore('-')), verb)

    /**
     * Makes the id a caller chose a second name for [job], which was running
     * before the caller asked.
     *
     * The theme picks the id of a scan itself, polls that id and never reads
     * the one in the answer. So when its request is answered with a scan that
     * is already running, telling it that scan's id is not enough: the id it
     * sent has to lead there, or its first poll is a 404 and its popup says
     * the job is unknown while the scan goes on. An id that cannot be used,
     * for the reasons [createWithClientId] gives, is left alone, and that
     * caller has the id in the answer to go by.
     *
     * Only the map knows the second name. The copy in `pending/` and the
     * marker in `done/` are written under the job's own id and no other.
     */
    fun alias(requested: String?, job: Job) {
        usable(requested)?.let { jobs[it] = job }
    }

    private fun usable(requested: String?): String? = requested?.trim()?.takeIf { id ->
        id.isNotEmpty() && id.length <= 100 &&
        id.all { it.isLetterOrDigit() || it == '_' || it == '-' } &&
        jobs[id]?.state != State.RUNNING
    }

    fun get(id: String): Job? = jobs[id]

    fun update(job: Job, progress: Double, message: String, counters: JSONObject? = null) {
        job.progress = progress
        job.message = message
        if (counters != null) job.counters = counters
        publish(job)
    }

    fun finish(job: Job, result: JSONObject?) {
        job.state = State.DONE
        job.progress = 1.0
        job.result = result
        complete(job)
    }

    /**
     * [result] is for a job that failed with something to show for it. A scan
     * the pipeline cut short has counted and written what it got through, and
     * its progress stays where it stopped: unlike [finish], nothing here says
     * the work was all done.
     */
    fun fail(job: Job, reason: String, result: JSONObject? = null) {
        job.state = State.ERROR
        job.error = reason
        job.result = result
        complete(job)
    }

    /** Drops finished jobs older than [maxAgeSeconds] so the map cannot grow forever. */
    fun evictOlderThan(maxAgeSeconds: Long) {
        val cutoff = BridgePaths.epochSeconds() - maxAgeSeconds
        jobs.entries.removeIf { (_, j) -> j.state != State.RUNNING && j.startedAt < cutoff }
    }

    private fun publish(job: Job) {
        runCatching { BridgePaths.writeAtomic(paths.pending(job.id), job.toJson().toString()) }
    }

    private fun complete(job: Job) {
        // Order matters: the result must be readable before the marker appears,
        // or a file-polling client can see "done" and find nothing.
        publish(job)
        runCatching { paths.markDone(job.id) }
        runCatching { paths.pending(job.id).delete() }
    }
}
