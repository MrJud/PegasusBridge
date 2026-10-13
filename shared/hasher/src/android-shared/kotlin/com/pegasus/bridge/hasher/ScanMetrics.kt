package com.pegasus.bridge.hasher

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.LongAdder

/**
 * Where the time of one scan went, and which files cost it most.
 *
 * Two kinds of number, kept apart because they do not add up with one
 * another. The four phases follow one another on the coroutine that runs the
 * scan, are measured by the clock on the wall and come to the whole. The four
 * sums are of time spent inside the scanning phase and are added up over the
 * workers, so that four of them hashing for a minute are four minutes: a sum
 * can be more than the scan took, and is divided by its workers for the least
 * time it can have cost.
 *
 * The clock is handed in, so that a test moves it by hand and reads exact
 * numbers. [threadRead] is asked on the thread that hashes, before a file and
 * after it, which is the only place where the system's count is of that file.
 */
class ScanMetrics(
    private val nanoTime: () -> Long,
    private val threadRead: () -> IoCounters.Count? = IoCounters::thread
) {
    /**
     * One of the files that took longest in the hasher. [bytesRead] is what
     * the hashing thread asked the system for meanwhile, which for a disc
     * image is far less than [size]; null where the system does not say.
     */
    data class Slow(val path: String, val ms: Long, val size: Long, val bytesRead: Long?)

    /**
     * The numbers of a scan that has returned. [walkMs], [prepareMs], [scanMs]
     * and [finishMs] add up to [wallMs] exactly. [hashMs], [lookupMs],
     * [throttleMs] and [writeMs] are sums over [hashWorkers] and
     * [lookupWorkers]. [bytesRead] is null where the system gave no count, and
     * for a scan that handed the hasher nothing.
     */
    data class Snapshot(
        val wallMs: Long,
        val walkMs: Long,
        val prepareMs: Long,
        val scanMs: Long,
        val finishMs: Long,
        val hashMs: Long,
        val lookupMs: Long,
        val throttleMs: Long,
        val writeMs: Long,
        val hashWorkers: Int,
        val lookupWorkers: Int,
        val filesHashed: Int,
        val lookups: Int,
        val bytesRead: Long?,
        val slowest: List<Slow>
    ) {
        /**
         * The object both shells put in the record a scan ends on, under
         * `metrics`. Made here so that the two cannot come to differ.
         */
        fun toJson(): JSONObject = JSONObject().apply {
            put("wallMs", wallMs)
            put("phaseMs", JSONObject().apply {
                put("walk", walkMs); put("prepare", prepareMs); put("scan", scanMs); put("finish", finishMs)
            })
            put("busyMs", JSONObject().apply {
                put("hash", hashMs); put("lookup", lookupMs); put("throttle", throttleMs); put("write", writeMs)
            })
            put("hashWorkers", hashWorkers)
            put("lookupWorkers", lookupWorkers)
            put("filesHashed", filesHashed)
            put("lookups", lookups)
            bytesRead?.let { put("bytesRead", it) }
            put("slowest", JSONArray().apply {
                for (s in slowest) put(JSONObject().apply {
                    put("file", s.path); put("ms", s.ms); put("size", s.size)
                    s.bytesRead?.let { put("bytesRead", it) }
                })
            })
        }
    }

    // The marks, in nanoseconds of the clock given. One the scan did not
    // reach stays where the one before it is, so its phase comes to 0: a
    // library with no file is walked and finished, and nothing in between.
    private val startedAt = nanoTime()
    @Volatile private var walkedAt = UNSET
    @Volatile private var preparedAt = UNSET
    @Volatile private var scannedAt = UNSET
    @Volatile private var finishedAt = UNSET

    private val hashNanos = LongAdder()
    private val lookupNanos = LongAdder()
    private val throttleMillis = LongAdder()
    private val writeNanos = LongAdder()
    private val hashed = LongAdder()
    private val asked = LongAdder()
    private val bytes = LongAdder()
    // Files whose bytes the system counted. With none, there is no number
    // to give, which is not the same as a count of 0.
    private val counted = LongAdder()
    private val slowest = ArrayList<Slow>(KEPT + 1)

    /** The walk is over: every file there is to look at has been found. */
    fun walked() { walkedAt = nanoTime() }

    /** What earlier scans left has been loaded, and the first file is about to be gone over. */
    fun prepared() { preparedAt = nanoTime() }

    /** Every file has been gone over, or the scan stopped itself. */
    fun scanned() { scannedAt = nanoTime() }

    /** The ledger and the index are on disk. */
    fun finished() { finishedAt = nanoTime() }

    /**
     * Times [block], which hashes the file at [path], and counts what it
     * read. Called on the thread that does the reading, since the count is
     * that thread's. A hash that throws is timed and counted as any other:
     * the time was spent.
     */
    fun <T> hashing(path: String, size: Long, block: () -> T): T {
        val readBefore = threadRead()
        val began = nanoTime()
        try {
            return block()
        } finally {
            val nanos = nanoTime() - began
            val read = IoCounters.between(readBefore, threadRead())
            hashNanos.add(nanos)
            hashed.increment()
            if (read != null) { bytes.add(read); counted.increment() }
            keep(Slow(path, nanos / NANOS_IN_MS, size, read))
        }
    }

    /** Times one question put to the lookup, with whatever it waited for. */
    inline fun <T> lookingUp(block: () -> T): T {
        val began = now()
        try { return block() } finally { lookedUp(began) }
    }

    /** A pause the caller asked for after a hash, as asked. */
    fun throttled(ms: Long) { throttleMillis.add(ms) }

    /** Times a write the scan makes as it goes: a metadata file, a save of the ledger. */
    inline fun <T> writing(block: () -> T): T {
        val began = now()
        try { return block() } finally { wrote(began) }
    }

    @PublishedApi internal fun now(): Long = nanoTime()
    @PublishedApi internal fun lookedUp(began: Long) { lookupNanos.add(nanoTime() - began); asked.increment() }
    @PublishedApi internal fun wrote(began: Long) { writeNanos.add(nanoTime() - began) }

    private fun keep(file: Slow) = synchronized(slowest) {
        slowest += file
        // By path where two took as long, so that the list is the same
        // whichever of them a worker finished first.
        slowest.sortWith(compareByDescending<Slow> { it.ms }.thenBy { it.path })
        if (slowest.size > KEPT) slowest.removeAt(slowest.size - 1)
    }

    /**
     * The numbers as they stand, for a scan run with [hashWorkers] files in
     * the hasher at once and [lookupWorkers] asking about hashes.
     *
     * Each mark is turned to milliseconds since the start before any is
     * taken from another. Rounded one by one, four phases could come to a
     * millisecond or two less than the whole.
     */
    fun snapshot(hashWorkers: Int, lookupWorkers: Int): Snapshot {
        val walked = since(walkedAt, 0L)
        val prepared = since(preparedAt, walked)
        val scanned = since(scannedAt, prepared)
        val wall = since(if (finishedAt == UNSET) nanoTime() else finishedAt, scanned)
        return Snapshot(
            wallMs = wall,
            walkMs = walked, prepareMs = prepared - walked,
            scanMs = scanned - prepared, finishMs = wall - scanned,
            hashMs = hashNanos.sum() / NANOS_IN_MS, lookupMs = lookupNanos.sum() / NANOS_IN_MS,
            throttleMs = throttleMillis.sum(), writeMs = writeNanos.sum() / NANOS_IN_MS,
            hashWorkers = hashWorkers, lookupWorkers = lookupWorkers,
            filesHashed = hashed.sum().toInt(), lookups = asked.sum().toInt(),
            bytesRead = if (counted.sum() > 0) bytes.sum() else null,
            slowest = synchronized(slowest) { slowest.toList() })
    }

    /** Milliseconds from the start to [mark], and never less than [atLeast], which is what an unset mark gives. */
    private fun since(mark: Long, atLeast: Long): Long =
        if (mark == UNSET) atLeast else maxOf(atLeast, (mark - startedAt) / NANOS_IN_MS)

    private companion object {
        const val KEPT = 5
        const val NANOS_IN_MS = 1_000_000L
        // Not a time: the clock's numbers start anywhere, below zero as well.
        const val UNSET = Long.MIN_VALUE
    }
}
