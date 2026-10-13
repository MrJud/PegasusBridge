package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a scan makes of the trace an earlier one left: which files it tries
 * first and alone, which it keeps from the hasher, and what it leaves for the
 * scan after it. The traces here are written by hand, as a process that died
 * would have left them; ScanDeathTest has processes that do die.
 */
class HashTraceTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var ledger: ScanLedger
    private val opened = mutableListOf<HashTrace>()

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("hash-trace").toFile()
        file = File(dir, HashTrace.FILE_NAME)
        ledger = ScanLedger(File(dir, ScanLedger.FILE_NAME))
        BridgeLog.current = NoopLog
    }

    // The trace is one to a process, and a test that failed with one in hand
    // would leave every test after it, in this class and the others, with a
    // trace that does nothing.
    @AfterTest fun tearDown() {
        opened.forEach { it.finish(ledger) }
        dir.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun start(with: ScanLedger = ledger) = HashTrace.start(file, with).also { opened += it }

    private fun begin(path: String, n: Int, alone: Boolean = false, size: Long = 10, modified: Long = 20) =
        JSONObject().put("begin", path).put("n", n).put("size", size).put("modified", modified)
            .put("alone", alone).toString()
    private fun end(n: Int) = """{"end":$n}"""
    private fun suspect(path: String, size: Long = 10, modified: Long = 20) =
        JSONObject().put("suspect", path).put("size", size).put("modified", modified).toString()
    private fun convicted(path: String, size: Long = 10, modified: Long = 20) =
        JSONObject().put("convicted", path).put("size", size).put("modified", modified).toString()

    private fun left(vararg lines: String) = file.writeText(lines.joinToString("") { it + "\n" })

    /** The lines on disk, each as the one thing it says and of which file. */
    private fun onDisk(): List<String> = if (!file.exists()) emptyList() else file.readLines().map {
        val j = JSONObject(it)
        val key = listOf("begin", "end", "suspect", "convicted", "cleared", "stopped").first(j::has)
        "$key ${j.get(key)}"
    }

    @Test fun `a begin with no end is a suspect`() {
        left(begin("/roms/a", 1), begin("/roms/b", 2), begin("/roms/c", 3), end(2))

        val trace = start()

        assertEquals(setOf("/roms/a", "/roms/c"), trace.suspects)
        assertEquals(emptySet(), trace.convicted)
        assertNull(trace.convicted("/roms/a", 10, 20))
        // Written again at once, with those alone: the begins are of a
        // process that is gone, and this one numbers its own from 1.
        assertEquals(listOf("suspect /roms/a", "suspect /roms/c"), onDisk())
    }

    @Test fun `a begin made alone with no end is convicted`() {
        left(suspect("/roms/a"), suspect("/roms/b"), begin("/roms/a", 1, alone = true), end(1),
             """{"cleared":"/roms/a"}""", begin("/roms/b", 2, alone = true))

        val trace = start()

        assertEquals(emptySet(), trace.suspects)
        assertEquals(setOf("/roms/b"), trace.convicted)
        val reason = trace.convicted("/roms/b", 10, 20)
        assertTrue(reason != null && reason.startsWith(HashTrace.SET_ASIDE), "$reason")
        assertTrue("30 days" in reason!!, reason)
        assertEquals(listOf("convicted /roms/b"), onDisk())
    }

    // Not what one process writes, which begins one file alone at a time.
    // But two could, were the rule of one trace to a process ever broken,
    // and then neither file was alone in fact.
    @Test fun `two begun alone are suspects and not convicted`() {
        left(begin("/roms/a", 1, alone = true), begin("/roms/b", 2, alone = true))

        val trace = start()

        assertEquals(setOf("/roms/a", "/roms/b"), trace.suspects)
        assertEquals(emptySet(), trace.convicted)
    }

    // A power cut can leave the last line short, or anything at all.
    @Test fun `a line cut short or of no sense is passed over`() {
        left("not a line of ours", "{", "[1,2]", """{"begin":"/roms/nosize","n":1}""",
             """{"begin":"/roms/nonumber","size":1,"modified":2}""", """{"suspect":"/roms/nodate","size":1}""",
             """{"other":"thing"}""", "", begin("/roms/a", 2),
             begin("/roms/torn", 3).dropLast(9))

        val trace = start()

        assertEquals(setOf("/roms/a"), trace.suspects)
        assertEquals(emptySet(), trace.convicted)
    }

    @Test fun `a suspect not tried again is carried to the next trace`() {
        left(begin("/roms/a", 1), begin("/roms/b", 2))

        // A scan that was stopped before it came to either.
        start().finish(ledger)
        assertEquals(listOf("suspect /roms/a", "suspect /roms/b"), onDisk())

        // One that tried the first and died later, of two other files.
        val second = start()
        second.hashing("/roms/a", 10, 20, alone = true) { }
        second.cleared("/roms/a")
        second.begin("/roms/c", 10, 20, alone = false)
        second.begin("/roms/d", 10, 20, alone = false)
        assertEquals(listOf("suspect /roms/a", "suspect /roms/b", "begin /roms/a", "end 1", "cleared /roms/a",
                            "begin /roms/c", "begin /roms/d"), onDisk())
        second.finish(ledger)
        left(suspect("/roms/a"), suspect("/roms/b"), begin("/roms/a", 1, alone = true), end(1),
             """{"cleared":"/roms/a"}""", begin("/roms/c", 2), begin("/roms/d", 3))

        assertEquals(setOf("/roms/b", "/roms/c", "/roms/d"), start().suspects)
    }

    @Test fun `a convicted file of another size or date is no longer convicted`() {
        left(convicted("/roms/a"), convicted("/roms/b"), convicted("/roms/c"))

        val trace = start()

        assertNull(trace.convicted("/roms/a", 11, 20), "another size")
        assertNull(trace.convicted("/roms/b", 10, 21), "another date")
        // Dropped, and not only passed this once: the file is hashed now,
        // and what is learnt of it is learnt of the file it has become.
        assertEquals(setOf("/roms/c"), trace.convicted)
        assertNull(trace.convicted("/roms/a", 10, 20))
        assertTrue(trace.convicted("/roms/c", 10, 20) != null)
        assertNull(trace.convicted("/roms/never", 10, 20))
    }

    private fun setAside(path: String, at: Long, size: Long = 10, modified: Long = 20) =
        ledger.record(path, CollectionRef.inferred("nes"), ScanLedger.State.UNHASHABLE, size, modified, now = at,
                      detail = HashTrace.SET_ASIDE_REASON)

    // What says that a conviction has been acted on is the ledger holding
    // the file as set aside, once that ledger is on disk. RomScanPipelineTest
    // has scans that do the setting aside; here it is the rule alone.
    @Test fun `a conviction goes when a saved ledger holds its file as set aside`() {
        left(convicted("/roms/a"), convicted("/roms/b", size = 11), convicted("/roms/c"))
        setAside("/roms/a", at = 5)
        setAside("/roms/b", at = 5)
        ledger.save { _, _ -> }

        // Of the file as it was convicted, by its size and date.
        val trace = start()
        assertEquals(setOf("/roms/b", "/roms/c"), trace.convicted)
        assertEquals(listOf("convicted /roms/b", "convicted /roms/c"), onDisk())

        // Set aside by this scan, in a ledger whose save did not get
        // through: a process that died now would leave the file nowhere.
        setAside("/roms/c", at = 6)
        assertTrue(ledger.dirty)
        trace.finish(ledger)
        assertEquals(listOf("convicted /roms/b", "convicted /roms/c"), onDisk())

        val second = start()
        ledger.save { _, _ -> }
        second.finish(ledger)
        assertEquals(listOf("convicted /roms/b"), onDisk())
    }

    // A month on the entry is in the ledger still, and the file is tried
    // once more, alone. When the process ends in that, the entry is not the
    // conviction acted on: it is the one the file was tried in spite of.
    @Test fun `a conviction is not dropped by the entry its file was tried in spite of`() {
        setAside("/roms/a", at = 5)
        ledger.save { _, _ -> }
        left(begin("/roms/a", 1, alone = true))

        val trace = start()
        assertEquals(setOf("/roms/a"), trace.convicted)
        assertTrue(trace.convicted("/roms/a", 10, 20) != null)

        // Nor by a scan that ends before it comes to the file, nor by the
        // scan after that one, which reads what this one wrote.
        trace.finish(ledger)
        assertEquals(listOf("convicted /roms/a"), onDisk())
        val second = start()
        assertEquals(setOf("/roms/a"), second.convicted)

        // The scan that does come to it sets it aside again, as of now,
        // and that entry is the conviction acted on.
        setAside("/roms/a", at = 6)
        ledger.save { _, _ -> }
        second.finish(ledger)
        assertFalse(file.exists(), "left behind: ${onDisk()}")
    }

    @Test fun `a trace with nothing left is removed`() {
        left(suspect("/roms/a"), begin("/roms/b", 1), end(1))

        val trace = start()
        assertTrue(file.exists())
        trace.cleared("/roms/a")
        // A hash that ends by throwing has ended all the same.
        runCatching { trace.hashing("/roms/c", 1, 2, alone = false) { error("unreadable") } }
        assertEquals(listOf("suspect /roms/a", "cleared /roms/a", "begin /roms/c", "end 1"), onDisk())
        trace.finish(ledger)

        assertFalse(file.exists(), "the trace is still there")
        assertEquals(emptyList(), dir.list().orEmpty().toList(), "left beside it")

        // And one with nothing in it from the start, where a scan that
        // hashes nothing makes no file.
        left(begin("/roms/b", 1), end(1))
        val empty = start()
        assertFalse(file.exists(), "a trace of hashes that all ended was kept")
        empty.cleared("/roms/never")
        empty.finish(ledger)
        assertFalse(file.exists(), "a scan that hashed nothing left a trace")
    }

    // A service that is stopped, Ctrl-C, the machine going down: the JVM
    // goes with hashes in flight and no file is to answer for it. What was
    // carried from before that scan is as true as it was.
    @Test fun `a trace that ends with an orderly stop names no suspect`() {
        left(suspect("/roms/a"), convicted("/roms/b"), begin("/roms/c", 1), begin("/roms/d", 2, alone = true),
             """{"stopped":true}""")

        val trace = start()

        assertEquals(setOf("/roms/a"), trace.suspects)
        assertEquals(setOf("/roms/b"), trace.convicted)

        // The same with one file begun alone and nothing else, which
        // without the last line is a conviction.
        trace.finish(ledger)
        left(begin("/roms/d", 1, alone = true), """{"stopped":true}""")
        val alone = start()
        assertEquals(emptySet(), alone.suspects + alone.convicted)
    }

    // ---- a process the system says it ended from outside

    /** A shell whose record says [ended] for the process [of], and nothing of any other. */
    private class Shell(override val current: String, val of: String? = null, val ended: Long? = null) : ProcessEnds {
        val asked = mutableListOf<String>()
        override fun endedFromOutside(process: String): Long? {
            asked += process
            return ended.takeIf { process == of }
        }
    }

    private fun start(shell: ProcessEnds, clock: () -> Long = { 0L }) =
        HashTrace.start(file, ledger, shell, clock).also { opened += it }

    private fun begin(path: String, n: Int, by: String?, at: Any?, alone: Boolean = false): String =
        JSONObject(begin(path, n, alone)).also { if (by != null) it.put("by", by); if (at != null) it.put("at", at) }
            .toString()

    // Android kills a process without running its hooks, so no `stopped` is
    // written, and says afterwards why. The user stopped the app a second
    // after the last file was begun: the files in hand had nothing to do
    // with it.
    @Test fun `a trace left by a process ended from outside names no suspect`() {
        left(begin("/roms/a", 1, by = "4711", at = 5_000L), begin("/roms/b", 2, by = "4711", at = 7_000L),
             begin("/roms/c", 3, by = "4711", at = 9_000L), end(2))
        val shell = Shell(current = "4802", of = "4711", ended = 10_000L)

        val trace = start(shell)

        assertEquals(emptySet(), trace.suspects)
        assertEquals(emptySet(), trace.convicted)
        assertEquals(listOf("4711"), shell.asked)
        assertFalse(file.exists(), "nothing is left to carry")
    }

    @Test fun `and convicts no file begun alone`() {
        left(suspect("/roms/a"), begin("/roms/a", 1, by = "4711", at = 9_000L, alone = true))

        val trace = start(Shell(current = "4802", of = "4711", ended = 10_000L))

        assertEquals(emptySet(), trace.convicted)
        assertNull(trace.convicted("/roms/a", 10, 20))
        // Still to be tried alone: it was a suspect before this process
        // began it, and the process was ended before it had answered.
        assertEquals(setOf("/roms/a"), trace.suspects)
        assertEquals(listOf("suspect /roms/a"), onDisk())
    }

    // The same lines, and the system has no record of that process, or one
    // whose reason is a crash's, which the shell answers alike: null.
    @Test fun `a process that crashed leaves its suspects as before`() {
        left(begin("/roms/a", 1, by = "4711", at = 5_000L), begin("/roms/b", 2, by = "4711", at = 7_000L))
        val shell = Shell(current = "4802")

        val trace = start(shell)

        assertEquals(setOf("/roms/a", "/roms/b"), trace.suspects)
        assertEquals(listOf("4711"), shell.asked)
        trace.finish(ledger)

        left(begin("/roms/c", 1, by = "4711", at = 5_000L, alone = true))
        val again = start(Shell(current = "4802"))
        assertEquals(setOf("/roms/c"), again.convicted)
    }

    // A file that hangs the hasher is ended by the user stopping the app,
    // which is an end from outside. It had been in hand for longer than any
    // good file takes, and stays what it was: a suspect, and begun alone a
    // conviction. To the millisecond on both sides of the two minutes.
    @Test fun `a file more than two minutes in the hasher when the app was stopped is a suspect as before`() {
        val limit = HashTrace.LONG_IN_HAND_MS
        assertEquals(120_000L, limit)
        left(begin("/roms/quick", 1, by = "4711", at = 500_000L), begin("/roms/hung", 2, by = "4711", at = 400_000L - 1))
        val stopped = Shell(current = "4802", of = "4711", ended = 400_000L + limit)
        val trace = start(stopped)
        // One of the two was there too long, and nothing says the stop was
        // not for it: neither is let off. The quick one hashes and is cleared.
        assertEquals(setOf("/roms/quick", "/roms/hung"), trace.suspects)
        trace.finish(ledger)

        left(begin("/roms/hung", 1, by = "4711", at = 400_000L - 1, alone = true))
        assertEquals(setOf("/roms/hung"), start(stopped).convicted)
        opened.last().finish(ledger)
        file.delete()

        left(begin("/roms/slow", 1, by = "4711", at = 400_000L, alone = true))
        val onTheLine = start(stopped)
        assertEquals(emptySet(), onTheLine.convicted)
        assertEquals(emptySet(), onTheLine.suspects)
    }

    // Pids come round again, and a reboot, which leaves no record, starts
    // them low. A record of an end before a begin was written is of some
    // earlier process that had the name.
    @Test fun `a record older than a begin is not this process's`() {
        left(begin("/roms/a", 1, by = "4711", at = 5_000L), begin("/roms/b", 2, by = "4711", at = 10_001L))

        val trace = start(Shell(current = "4802", of = "4711", ended = 10_000L))

        assertEquals(setOf("/roms/a", "/roms/b"), trace.suspects)
    }

    // A trace an older build left, or a shell that handed none in when it
    // was written; one begin among several without the name; a time that is
    // no whole number, or so far off that taking it from the end would wrap.
    @Test fun `a begin with no process named is a suspect whatever the shell says`() {
        val shell = Shell(current = "4802", of = "4711", ended = 10_000L)
        val cases = listOf(
            listOf(begin("/roms/a", 1), begin("/roms/b", 2)),
            listOf(begin("/roms/a", 1, by = "4711", at = 9_000L), begin("/roms/b", 2)),
            listOf(begin("/roms/a", 1, by = "4711", at = 9_000L), begin("/roms/b", 2, by = "4712", at = 9_000L)),
            listOf(begin("/roms/a", 1, by = "4711", at = 9_000L), begin("/roms/b", 2, by = "4711", at = null)),
            listOf(begin("/roms/a", 1, by = "4711", at = "9000"), begin("/roms/b", 2, by = "4711", at = 9_000L)),
            listOf(begin("/roms/a", 1, by = "4711", at = 9000.5), begin("/roms/b", 2, by = "4711", at = 9_000L)),
            listOf(begin("/roms/a", 1, by = "4711", at = Long.MIN_VALUE), begin("/roms/b", 2, by = "4711", at = 9_000L)),
            listOf(begin("/roms/a", 1, by = "", at = 9_000L), begin("/roms/b", 2, by = "", at = 9_000L)))
        for (lines in cases) {
            left(*lines.toTypedArray())
            val trace = start(shell)
            assertEquals(setOf("/roms/a", "/roms/b"), trace.suspects, lines.toString())
            trace.finish(ledger)
            file.delete()
        }
        // And with no shell at all, whatever the lines carry.
        left(begin("/roms/a", 1, by = "4711", at = 9_000L), begin("/roms/b", 2, by = "4711", at = 9_000L))
        assertEquals(setOf("/roms/a", "/roms/b"), start().suspects)
    }

    // The system gives a new process the pid of one that is gone. The
    // record of that pid may then be the old one's or nobody's, and the
    // begins are held as they were.
    @Test fun `the running process is not asked about`() {
        left(begin("/roms/a", 1, by = "4711", at = 9_000L), begin("/roms/b", 2, by = "4711", at = 9_500L))
        val shell = Shell(current = "4711", of = "4711", ended = 10_000L)

        val trace = start(shell)

        assertEquals(setOf("/roms/a", "/roms/b"), trace.suspects)
        assertEquals(emptyList(), shell.asked)
    }

    @Test fun `a shell that throws is one that does not know`() {
        left(begin("/roms/a", 1, by = "4711", at = 9_000L, alone = true))
        val throwing = object : ProcessEnds {
            override val current = "4802"
            override fun endedFromOutside(process: String): Long? = throw SecurityException("not for this caller")
        }

        assertEquals(setOf("/roms/a"), start(throwing).convicted)
        opened.last().finish(ledger)
        file.delete()

        // One that cannot name the running process marks no begin, and the
        // trace it writes is the one a scan with no shell writes.
        val nameless = object : ProcessEnds {
            override val current: String get() = throw IllegalStateException("no name")
            override fun endedFromOutside(process: String): Long? = 10_000L
        }
        left(begin("/roms/b", 1, by = "4711", at = 9_000L, alone = true))
        val trace = start(nameless)
        assertEquals(setOf("/roms/b"), trace.convicted)
        trace.begin("/roms/c", 10, 20, alone = false)
        assertFalse(JSONObject(file.readLines().last()).let { it.has("by") || it.has("at") })
    }

    // What an earlier scan left as suspects is not this process's to let
    // off: only the begins it left itself are.
    @Test fun `suspects carried from an earlier scan stay`() {
        left(suspect("/roms/old"), convicted("/roms/worse"),
             begin("/roms/a", 1, by = "4711", at = 9_000L), begin("/roms/b", 2, by = "4711", at = 9_500L))

        val trace = start(Shell(current = "4802", of = "4711", ended = 10_000L))

        assertEquals(setOf("/roms/old"), trace.suspects)
        assertEquals(setOf("/roms/worse"), trace.convicted)
        assertEquals(listOf("suspect /roms/old", "convicted /roms/worse"), onDisk())
    }

    // What the trace writes with a shell, and that it writes what it always
    // did with none: the two fields are the whole of the difference.
    @Test fun `a begin carries the process and the time only when the shell names the process`() {
        var now = 1_000L
        val trace = start(Shell(current = "4802"), clock = { now })
        trace.begin("/roms/a", 10, 20, alone = false)
        now = 2_500L
        trace.begin("/roms/b", 10, 20, alone = true)
        val lines = file.readLines().map(::JSONObject)
        assertEquals(listOf("4802" to 1_000L, "4802" to 2_500L), lines.map { it.getString("by") to it.getLong("at") })
        assertEquals(setOf("begin", "n", "size", "modified", "alone", "by", "at"), lines[0].keySet())
        trace.finish(ledger)
        file.delete()

        val plain = start()
        plain.begin("/roms/a", 10, 20, alone = false)
        assertEquals(setOf("begin", "n", "size", "modified", "alone"), JSONObject(file.readLines().single()).keySet())
    }

    @Test fun `a second trace opened in the same process is inert and leaves the file alone`() {
        val owner = start()
        // What the owner might have written by now.
        left(suspect("/roms/a"), begin("/roms/b", 1, alone = true))
        val before = file.readText()

        val second = start()
        assertEquals(emptySet(), second.suspects + second.convicted)
        assertNull(second.convicted("/roms/b", 10, 20))
        assertEquals("ran", second.hashing("/roms/c", 1, 2, alone = true) { "ran" })
        second.cleared("/roms/a")
        second.finish(ledger)
        assertEquals(before, file.readText())

        // Its finish did not give back what it never held: a third is inert
        // too, until the owner has finished.
        val third = start()
        assertEquals(emptySet(), third.suspects)
        third.finish(ledger)
        assertEquals(before, file.readText())

        owner.finish(ledger)
        assertFalse(file.exists(), "the owner held nothing and left a file")
        left(suspect("/roms/a"))
        assertEquals(setOf("/roms/a"), start().suspects)
    }

    // Three scans, of which the first has ended and the second, which keeps
    // no trace, has not. A file the third begins as alone is in the hasher
    // beside whatever the second has there, and a process that ends then
    // has not ended on it alone.
    @Test fun `a trace opened while a scan that keeps none is still going is inert too`() {
        val first = start()
        val second = start()
        first.finish(ledger)
        left(suspect("/roms/a"))
        val before = file.readText()

        val third = start()
        assertEquals(emptySet(), third.suspects)
        third.begin("/roms/a", 10, 20, alone = true)
        assertEquals(before, file.readText())

        // A finish given twice counts once, or the second would end here
        // for the third as well.
        second.finish(ledger)
        second.finish(ledger)
        assertEquals(emptySet(), start().suspects)
        opened.forEach { it.finish(ledger) }
        assertEquals(setOf("/roms/a"), start().suspects)
    }

    // A Cancel leaves the thread a hash ran on interrupted, and the lines
    // are not written by that thread; but were one, it has to be written,
    // and the lines after it. A channel of java.nio closes itself on a write
    // from such a thread, and the trace would be at an end with a begin
    // open in it.
    @Test fun `a line is written by a thread that has been interrupted`() {
        val trace = start()

        val writer = Thread {
            Thread.currentThread().interrupt()
            trace.hashing("/roms/a", 10, 20, alone = true) { }
            trace.begin("/roms/b", 10, 20, alone = false)
        }
        writer.start()
        writer.join()

        assertEquals(listOf("begin /roms/a", "end 1", "begin /roms/b"), onDisk())
    }

    @Test fun `a trace too large is replaced by an empty one`() {
        val filler = "x".repeat(1023) + "\n"
        file.outputStream().buffered().use { out ->
            out.write((begin("/roms/a", 1) + "\n").toByteArray())
            repeat((HashTrace.MAX_BYTES / 1024).toInt()) { out.write(filler.toByteArray()) }
        }
        assertTrue(file.length() > HashTrace.MAX_BYTES)

        val trace = start()

        assertEquals(emptySet(), trace.suspects)
        assertFalse(file.exists(), "the trace was left as it was")
        // And it is a trace again from here on.
        trace.begin("/roms/b", 1, 2, alone = false)
        assertEquals(listOf("begin /roms/b"), onDisk())
    }

    // The file is written again through one beside it, and that can fail
    // where a line more to the file itself does not. What was in it is then
    // of a process that is gone: this scan's lines after those would be
    // numbered as they are, and an end of this scan would end a begin of
    // that one.
    @Test fun `a trace that cannot be written again is removed and not left as it was`() {
        left(begin("/roms/a", 1), begin("/roms/b", 2))
        File(dir, "${file.name}.tmp").mkdirs()

        val trace = start()

        // Known to this scan all the same, which tries them alone.
        assertEquals(setOf("/roms/a", "/roms/b"), trace.suspects)
        assertFalse(file.exists(), "left as it was: ${onDisk()}")
        trace.hashing("/roms/a", 10, 20, alone = true) { }
        trace.cleared("/roms/a")
        trace.begin("/roms/b", 10, 20, alone = true)
        assertEquals(listOf("begin /roms/a", "end 1", "cleared /roms/a", "begin /roms/b"), onDisk())
    }

    // The ledger's own rule for an entry, so that neither outlives the other:
    // under a root that was scanned and not found there, or gone from disk.
    @Test fun `a suspect whose file is gone is dropped by a scan that ends`() {
        val root = File(dir, "scanned").apply { mkdirs() }
        val elsewhere = File(dir, "elsewhere").apply { mkdirs() }
        val found = File(root, "found.nes").apply { writeText("x") }
        val unlisted = File(root, "unlisted.nes").apply { writeText("x") }
        val kept = File(elsewhere, "kept.nes").apply { writeText("x") }
        val gone = File(elsewhere, "gone.nes")
        val lines = listOf(suspect(found.path), suspect(unlisted.path), suspect(kept.path), suspect(gone.path),
                           convicted(File(root, "c-unlisted.nes").path), convicted(File(elsewhere, "c-gone.nes").path),
                           convicted(File(elsewhere, "c-kept.nes").apply { writeText("x") }.path))
        left(*lines.toTypedArray())

        // A scan that was stopped drops nothing, as it forgets nothing.
        start().finish(ledger)
        assertEquals(7, onDisk().size)

        start().finish(ledger, found = setOf(found.path), roots = listOf(root.path))
        assertEquals(listOf("suspect ${found.path}", "suspect ${kept.path}",
                            "convicted ${File(elsewhere, "c-kept.nes").path}"), onDisk())
    }
}
