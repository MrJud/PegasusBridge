package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which files a scan has in the hasher, written down as it goes, so that the
 * scan after one whose process died can tell which file it may have died of.
 *
 * The hasher is C, entered in the scan's own process, and a file made to
 * mislead it can end that process with a signal. The ledger is saved every few
 * seconds and says nothing of the file in hand, so the next scan came to the
 * same file and died of it again, at every start, and no file after it in the
 * walk was ever reached.
 *
 * ── The file ───────────────────────────────────────────────
 *
 * One JSON object to a line, so that any character of a path is safe:
 *
 *     {"begin":"<path>","n":7,"size":S,"modified":M,"alone":false}
 *     {"end":7}
 *     {"suspect":"<path>","size":S,"modified":M}
 *     {"convicted":"<path>","size":S,"modified":M,"despite":T}
 *     {"cleared":"<path>"}
 *     {"stopped":true}
 *
 * `begin` is written before the hasher is given the file and `end`, which
 * names its begin by the number, when the hasher has answered, whatever it
 * answered. The other four are what [start] and [finish] make of those two;
 * `despite` is there for a conviction of a file that had been set aside
 * before, and [start] says what it is.
 *
 * Each line is one write to a [FileOutputStream] opened for appending, with
 * nothing between the two that keeps bytes back and nothing forced to disk. A
 * process that dies leaves what it wrote with the system, so a crash and a
 * kill lose no line, and forcing each would cost two syncs for every file
 * hashed. Not a channel of java.nio: a write to one from a thread that has
 * been interrupted closes it, and a Cancel interrupts the thread a hash runs
 * on. What a power cut leaves is another matter: the lines written long
 * enough before it are on disk, so it leaves suspects as a crash does, and the
 * last of them may be missing or cut short. A line that does not parse is
 * passed over. The worst of that is a suspect not known, which is where every
 * scan stood before there was a trace.
 *
 * ── What is made of it ─────────────────────────────────────
 *
 * A begin with no end is a file that was in the hasher when the process went.
 * One death proves nothing of any of them: several files are in hand at once,
 * and a process is killed for reasons that are none of theirs. So they are
 * SUSPECTS, and the next scan that walks them hashes them first, one at a
 * time, each begun as `alone`. A good file hashes and gets the verdict it
 * would have had. A process that goes again with one file begun alone, and no
 * other begun, has died twice with that file in hand and the second time with
 * nothing else to blame: the file is CONVICTED, and the scan that next walks
 * it keeps it from the hasher and records why ([SET_ASIDE_REASON]).
 *
 * A JVM that stops in order runs its shutdown hooks, and one that a signal
 * from inside the hasher ended does not. So while a scan holds the trace a
 * hook writes `stopped`, and the begins above that line are nobody's fault: a
 * service told to stop, Ctrl-C, the machine going down. Android runs no hook
 * for a process it kills, so there a kill and a crash still look alike.
 *
 * One trace to a process. A scan that starts while another is still going,
 * which Android allows for as long as a cancelled scan waits for its hashes,
 * is given one that reads nothing and writes nothing: with two scans hashing
 * at once no file is alone, and neither is to clear away the other's lines.
 * While another is going, whether that one keeps a trace or not: a third
 * that starts when the first has ended and the second has not is no more
 * alone than the second was.
 */
class HashTrace private constructor(private val file: File, private val inert: Boolean) {

    /**
     * What a file was when it was begun: it is the same file only while both
     * still hold. For a conviction, [despite] is when the ledger had set the
     * file aside already, as the entry says it, or null when it had not.
     */
    private class Mark(val size: Long, val modified: Long, val despite: Long? = null)

    private val lock = Any()
    private val carried = LinkedHashMap<String, Mark>()
    private val convictions = LinkedHashMap<String, Mark>()
    private var stream: FileOutputStream? = null
    private var sequence = 0L
    private var broken = false
    private var stopped = false
    private var finished = false
    private var hook: Thread? = null

    /**
     * The files in the hasher when an earlier scan died that no scan has
     * tried again since, by path. For the scan to hash first and alone.
     */
    val suspects: Set<String> get() = synchronized(lock) { carried.keys.toSet() }

    /** The files a process died on alone, by path, which no saved ledger holds as set aside yet. */
    val convicted: Set<String> get() = synchronized(lock) { convictions.keys.toSet() }

    /**
     * Why [path] is not to be handed to the hasher, or null when it may be.
     *
     * A conviction is of the file that was in hand, known by its size and
     * date. One of another size or date is another file, and the conviction
     * is dropped: the file is tried, alone, as any the trace or the ledger
     * names is.
     *
     * A conviction that holds stays here until [finish] finds it in a ledger
     * that was saved. The scan records the verdict in memory, and a process
     * that dies before the save would otherwise leave neither.
     */
    fun convicted(path: String, size: Long, modified: Long): String? = synchronized(lock) {
        val mark = convictions[path] ?: return null
        if (mark.size == size && mark.modified == modified) return SET_ASIDE_REASON
        convictions.remove(path)
        null
    }

    /**
     * Runs [block], which hands the file at [path] to the hasher, between a
     * begin and an end. [alone] says that no other file is in the hasher
     * meanwhile, which only the caller knows.
     *
     * The end is written however the block ends, an exception and a
     * cancellation among the ways. The caller sees to it that the block does
     * not come back before the hasher has: an end written while the hasher
     * still holds the file would clear a file that can yet kill the process.
     */
    inline fun <T> hashing(path: String, size: Long, modified: Long, alone: Boolean, block: () -> T): T {
        val n = begin(path, size, modified, alone)
        try {
            return block()
        } finally {
            end(n)
        }
    }

    /** Writes the begin of [hashing], and answers the number its end is to name. */
    fun begin(path: String, size: Long, modified: Long, alone: Boolean): Long = synchronized(lock) {
        if (inert) return 0
        val n = ++sequence
        write(JSONObject().put("begin", path).put("n", n).put("size", size)
                  .put("modified", modified).put("alone", alone))
        n
    }

    /** Writes the end of the begin that answered [n]. */
    fun end(n: Long) {
        synchronized(lock) { if (!inert) write(JSONObject().put("end", n)) }
    }

    /**
     * [path] has been gone over alone by this scan and is a suspect no
     * longer, whether it was hashed or needed no hash: a verdict found
     * standing, a placeholder. Written down, so that a process that dies
     * later in the scan does not leave it a suspect again. A conviction is
     * not cleared this way; see [convicted].
     */
    fun cleared(path: String) {
        synchronized(lock) {
            if (carried.remove(path) != null) write(JSONObject().put("cleared", path))
        }
    }

    /**
     * Ends the scan's hold of the trace, after the scan's last save of
     * [ledger], and leaves on disk only what a later scan still has to
     * know, or no file when that is nothing.
     *
     * A conviction is dropped when the ledger holds its file as set aside,
     * with the conviction's size and date, and only when that ledger is on
     * disk: one whose save failed is not, and a process that dies before the
     * next save would leave the file neither convicted nor set aside. By the
     * ledger's state and not by the scan having recorded it: a scan that set
     * the file aside and died after a save in the middle leaves both, and
     * every scan after it skips the file on the ledger's word and never comes
     * to the conviction. Left standing, it would set the file aside again
     * when its month is out, without the one try it is promised. Not by an
     * entry the file was convicted in spite of, though, which [start] says
     * the why of.
     *
     * [found] and [roots] are given by a scan that got to its end, and are
     * what it gives [ScanLedger.forget]: a suspect or a conviction goes by
     * the rule an entry of the ledger goes by, under a root that was scanned
     * and not found there, or gone from disk wherever it was. One outside
     * the roots whose file is there is kept for the scan that walks it. A
     * scan that was stopped gives neither and drops nothing by this rule,
     * as it forgets nothing.
     *
     * The file is written from what is held here, also when a write to it
     * failed during the scan: every hash the scan began has ended by now.
     */
    fun finish(ledger: ScanLedger, found: Set<String>? = null, roots: List<String> = emptyList()) {
        synchronized(lock) {
            // Once: the count below is of scans, and a second finish of one
            // would take another's away.
            if (finished) return
            finished = true
            if (inert) {
                scans.decrementAndGet()
                return
            }
            try {
                runCatching { stream?.close() }
                stream = null
                // Refused by a JVM that is on its way down, where the hook
                // runs or has run; it writes nothing once the stream is gone.
                hook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
                hook = null
                if (!ledger.dirty) convictions.entries.removeIf { (path, mark) -> ledger.holdsAside(path, mark) }
                if (found != null) {
                    val stale = ScanLedger.stale(found, roots)
                    carried.keys.removeIf(stale)
                    convictions.keys.removeIf(stale)
                }
                rewrite()
            } finally {
                scans.decrementAndGet()
            }
        }
    }

    // The entry that sets aside the file a conviction is of, by its size
    // and date, whether or not it still stands.
    private fun ScanLedger.asideFor(path: String, mark: Mark): ScanLedger.Entry? =
        entry(path)?.takeIf {
            it.state == ScanLedger.State.UNHASHABLE && it.detail.startsWith(SET_ASIDE) &&
            it.fileSize == mark.size && it.lastModified == mark.modified
        }

    // Whether the ledger has set the file aside since it was convicted: an
    // entry made at another time than the one the conviction knew of.
    private fun ScanLedger.holdsAside(path: String, mark: Mark): Boolean {
        val e = asideFor(path, mark) ?: return false
        return e.checkedAt != mark.despite
    }

    // One write for the whole line, straight to the system. A write that
    // fails ends the trace for this scan and not the scan: it goes on as
    // every scan did before there was a trace.
    private fun write(line: JSONObject) {
        if (broken || stopped) return
        try {
            val out = stream ?: FileOutputStream(file, true).also { stream = it }
            out.write((line.toString() + "\n").toByteArray())
        } catch (e: Exception) {
            broken = true
            BridgeLog.w(TAG, "could not write ${file.name}, so this scan keeps no trace of " +
                             "what it is hashing: ${e.message}")
        }
    }

    // Only when this scan wrote a line: a scan that hashed nothing has no
    // begin to speak for, and is not to leave a file where there was none.
    // Nothing is written after it, by a hash that ends or one that begins
    // while the JVM is on its way down, so that it is the last line.
    private fun stop() {
        synchronized(lock) {
            if (stream != null) write(JSONObject().put("stopped", true))
            stopped = true
        }
    }

    private fun rewrite() {
        if (carried.isEmpty() && convictions.isEmpty()) {
            if (file.exists() && !file.delete()) BridgeLog.w(TAG, "could not remove ${file.name}")
            return
        }
        val text = StringBuilder()
        for ((path, mark) in carried) text.append(line("suspect", path, mark)).append('\n')
        for ((path, mark) in convictions) text.append(line("convicted", path, mark)).append('\n')
        runCatching { BridgePaths.writeAtomic(file, text.toString()) }
            .onFailure {
                // Not left as it was. At the start it holds the begins of a
                // process that is gone, numbered as this scan numbers its
                // own, and at the end of a scan whose writes failed it may
                // hold a begin with its end missing: read by the scan after,
                // either makes a suspect or a conviction of a file nothing
                // died on. With no file the worst is a suspect not known.
                BridgeLog.w(TAG, "could not write ${file.name}, which is removed: ${it.message}")
                file.delete()
            }
    }

    private fun line(key: String, path: String, mark: Mark) =
        JSONObject().put(key, path).put("size", mark.size).put("modified", mark.modified)
            .also { if (mark.despite != null) it.put("despite", mark.despite) }

    private class Begun(val path: String, val mark: Mark, val alone: Boolean)

    // What the file says of the scans before this one. Anything that is not
    // one of the six lines, whole, is passed over. Answers the path of the
    // file it convicted, when the process before this one died on one alone.
    private fun read(): String? {
        val begun = LinkedHashMap<Long, Begun>()
        file.forEachLine { text ->
            val line = runCatching { JSONObject(text) }.getOrNull() ?: return@forEachLine
            fun mark() = if (line.has("size") && line.has("modified"))
                Mark(line.optLong("size"), line.optLong("modified")) else null
            when {
                line.has("begin") -> {
                    val mark = mark() ?: return@forEachLine
                    if (!line.has("n")) return@forEachLine
                    begun[line.optLong("n")] = Begun(line.optString("begin"), mark, line.optBoolean("alone"))
                }
                line.has("end") -> begun.remove(line.optLong("end"))
                line.has("suspect") -> mark()?.let { carried[line.optString("suspect")] = it }
                line.has("convicted") -> mark()?.let {
                    val despite = if (line.has("despite")) line.optLong("despite") else null
                    convictions[line.optString("convicted")] = Mark(it.size, it.modified, despite)
                }
                line.has("cleared") -> carried.remove(line.optString("cleared"))
                // An orderly stop: the hashes in flight above it were ended
                // by the JVM going down, and no file is to answer for that.
                line.has("stopped") -> begun.clear()
            }
        }
        val only = begun.values.singleOrNull()
        if (only != null && only.alone) {
            carried.remove(only.path)
            convictions[only.path] = only.mark
            return only.path
        } else {
            // Several in hand, or one among others: nothing says which. A
            // file begun again was let through by [convicted] as another
            // file than the one convicted, so that conviction is spent.
            for (b in begun.values) {
                convictions.remove(b.path)
                carried[b.path] = b.mark
            }
        }
        return null
    }

    companion object {
        private const val TAG = "HashTrace"
        const val FILE_NAME = "scan-inflight.jsonl"

        /**
         * What the detail of a file set aside begins with. The ledger knows
         * such an entry by it ([ScanLedger.setAside]), and so does whoever
         * reads the ledger or an audit's table.
         */
        const val SET_ASIDE = "set aside: "

        const val SET_ASIDE_REASON = SET_ASIDE + "the scan's process ended twice while this file was being " +
            "hashed, the second time with no other file in hand. It is tried again in 30 days, or sooner " +
            "if the file changes or the hasher does"

        /**
         * A trace longer than this is not read. Two lines for a file hashed
         * come to some 250 bytes, so a first scan of fifty thousand files
         * gets here, and the lines that matter are the last few; but a file
         * this long is more likely not a trace at all.
         */
        const val MAX_BYTES = 16L * 1024 * 1024

        // The scans of this process that have started and not finished,
        // those given a trace that does nothing among them.
        private val scans = AtomicInteger(0)

        /**
         * Opens the trace at [file] for one scan, which has to [finish] it
         * however it ends: reads what earlier scans left, works out the
         * suspects and the convictions, and writes the file again with those
         * alone, or removes it when there are none.
         *
         * [ledger] is the scan's, as read from disk. A conviction it already
         * holds as set aside is dropped here; [finish] says why.
         *
         * But a file is set aside for a month, and when the month is out, or
         * the hasher is another, the entry is in the ledger still and the
         * scan hands the file over all the same: that is the one try it is
         * promised. A process that ends in it leaves the file begun alone,
         * so convicted, and in the ledger the entry of a month ago, with the
         * file's size and date. That entry is not the conviction acted on.
         * Taken for it, the conviction was dropped here at every start, the
         * file was handed over again, first, and every scan from then on
         * ended on it before it had come to any other file. So a conviction
         * made here takes note of the entry the ledger has for its file
         * already, by the time it was made, and only an entry of another
         * time drops it: the one the scan that sets the file aside again
         * will record.
         */
        fun start(file: File, ledger: ScanLedger): HashTrace {
            if (scans.getAndIncrement() != 0) {
                BridgeLog.i(TAG, "another scan is going in this process: this one keeps no trace")
                return HashTrace(file, inert = true)
            }
            val trace = HashTrace(file, inert = false)
            synchronized(trace.lock) {
                try {
                    if (file.isFile) {
                        if (file.length() > MAX_BYTES) {
                            BridgeLog.w(TAG, "${file.name} is ${file.length()} bytes and is not read: " +
                                             "what an earlier scan died with in hand is not known")
                        } else {
                            trace.read()?.let { path ->
                                val mark = trace.convictions.getValue(path)
                                val known = with(trace) { ledger.asideFor(path, mark) }
                                if (known != null) trace.convictions[path] = Mark(mark.size, mark.modified, known.checkedAt)
                            }
                        }
                    }
                } catch (e: Exception) {
                    BridgeLog.w(TAG, "${file.name} could not be read and is started again: ${e.message}")
                    trace.carried.clear()
                    trace.convictions.clear()
                }
                trace.convictions.entries.removeIf { (path, mark) -> with(trace) { ledger.holdsAside(path, mark) } }
                trace.rewrite()
                val hook = Thread({ trace.stop() }, "hash-trace-stop")
                // A JVM already on its way down refuses a hook, and has no
                // use for one.
                if (runCatching { Runtime.getRuntime().addShutdownHook(hook) }.isSuccess) trace.hook = hook
            }
            return trace
        }
    }
}
