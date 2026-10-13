package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Scans whose process really ends in the hasher, each in a JVM of its own
 * ([ScanChild]), and the scans that come after them on the same data root.
 *
 * A file that ended the process was met again by every scan, which ended
 * there too: no file after it in the walk was ever reached, and nothing
 * said which file it was. And the cure must not cost a good file its
 * verdict when the process is ended from outside, which happens far more
 * often than a file ends it.
 */
class ScanDeathTest {

    private lateinit var work: File
    private lateinit var data: File
    private lateinit var roms: File
    private var children = 0

    @BeforeTest fun setUp() {
        work = Files.createTempDirectory("scan-death").toFile()
        data = File(work, "data")
        roms = File(work, "roms")
        File(roms, "nes").mkdirs()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        work.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    // NUL at the end: a small file of nothing but text is a placeholder,
    // which a scan does not hash.
    private fun rom(name: String) = File(roms, "nes/$name").apply { writeText("hash-$name\u0000") }

    private val trace: File get() = File(data, "cache/${HashTrace.FILE_NAME}")

    private fun ledger(): JSONObject =
        JSONObject(File(data, "cache/${ScanLedger.FILE_NAME}").readText()).getJSONObject("entries")

    private fun entry(f: File): JSONObject = ledger().getJSONObject(RomScanner.canonical(f))

    /** One scan in a child, to its end: its status, what its hasher was handed and what it printed. */
    private class Ended(val status: Int, val events: List<String>, val said: String) {
        val handed: List<String> get() = events.filter { it.startsWith("> ") }.map { it.drop(2) }
    }

    private fun scan(on: ScanChild.On = ScanChild.On.NOTHING, name: String = ""): Ended {
        val n = ++children
        val handed = File(work, "handed-$n.txt"); val out = File(work, "out-$n.txt")
        val status = ScanChild.run(data, roms, handed, out, on, name)
        return Ended(status, if (handed.exists()) handed.readLines() else emptyList(), out.readText())
    }

    @Test fun `a file that kills the process is set aside by the third scan and the rest is scanned`() {
        val good = (1..5).map { rom("Game$it.nes") }
        val killer = rom("Killer.nes")

        // Dies with the killer in hand, and whatever else was beside it.
        val first = scan(ScanChild.On.HALT, "Killer.nes")
        assertEquals(ScanChild.HALTED, first.status, first.said)
        assertTrue("Killer.nes" in first.handed, "${first.events}")
        assertTrue(trace.exists(), "the scan that died left no trace")

        // Tries what the first had in hand, one at a time, and dies on the
        // one that is the killer. Nothing was begun beside it.
        val second = scan(ScanChild.On.HALT, "Killer.nes")
        assertEquals(ScanChild.HALTED, second.status, second.said)
        assertEquals("> Killer.nes", second.events.last(), "${second.events}")
        assertTrue(second.events.dropLast(1).chunked(2).all { (a, b) -> a.drop(2) == b.drop(2) && b.startsWith("<") },
                   "a file was begun beside another: ${second.events}")

        // Does not hand it over again, and gets to its end.
        val third = scan(ScanChild.On.HALT, "Killer.nes")
        assertEquals(0, third.status, third.said)
        assertTrue(ScanChild.DONE in third.said, third.said)
        assertFalse("Killer.nes" in third.handed, "the killer was handed over a third time: ${third.events}")

        assertEquals("UNHASHABLE", entry(killer).getString("state"))
        assertEquals(HashTrace.SET_ASIDE_REASON, entry(killer).getString("detail"))
        assertEquals(List(5) { "NOT_FOUND" }, good.map { entry(it).getString("state") })
        assertEquals(6, ledger().length())
        assertFalse(trace.exists(), "left behind: ${trace.takeIf { it.exists() }?.readText()}")

        // And the scans after that leave it be.
        val fourth = scan(ScanChild.On.HALT, "Killer.nes")
        assertEquals(0, fourth.status, fourth.said)
        assertEquals(emptyList(), fourth.handed)
    }

    /** Makes the verdict the ledger holds for [f] one reached [days] days ago. */
    private fun age(f: File, days: Long) {
        val file = File(data, "cache/${ScanLedger.FILE_NAME}")
        val whole = JSONObject(file.readText())
        val e = whole.getJSONObject("entries").getJSONObject(RomScanner.canonical(f))
        e.put("checkedAt", e.getLong("checkedAt") - days * 24 * 60 * 60)
        file.writeText(whole.toString())
    }

    // The month is the promise of one more try, and the try is of a file
    // that may end the process as it did. The scan after that death finds
    // the file begun alone, and in the ledger the entry of a month ago, the
    // same file by its size and date. That entry is not the conviction acted
    // on: it had stopped standing, which is why the file was tried. Taken
    // for it, the conviction was dropped at every start and the file handed
    // over again, and every scan from then on ended on it before it had
    // come to any other file.
    @Test fun `a file that ends the process again when its month is out costs one scan and no more`() {
        val good = (1..3).map { rom("Game$it.nes") }
        val killer = rom("Killer.nes")
        assertEquals(ScanChild.HALTED, scan(ScanChild.On.HALT, "Killer.nes").status)
        assertEquals(ScanChild.HALTED, scan(ScanChild.On.HALT, "Killer.nes").status)
        assertEquals(0, scan(ScanChild.On.HALT, "Killer.nes").status)
        assertEquals(HashTrace.SET_ASIDE_REASON, entry(killer).getString("detail"))
        val setAsideAt = entry(killer).getLong("checkedAt")

        age(killer, days = 31)
        val again = rom("Later.nes")

        // The one try: first, alone, and it ends the process.
        val tried = scan(ScanChild.On.HALT, "Killer.nes")
        assertEquals(ScanChild.HALTED, tried.status, tried.said)
        assertEquals(listOf("> Killer.nes"), tried.events)

        // And no second: set aside again, for a month from now, and the
        // scan goes on to the file that was waiting behind it.
        val after = scan(ScanChild.On.HALT, "Killer.nes")
        assertEquals(0, after.status, after.said)
        assertEquals(listOf("Later.nes"), after.handed)
        assertEquals("UNHASHABLE", entry(killer).getString("state"))
        assertEquals(HashTrace.SET_ASIDE_REASON, entry(killer).getString("detail"))
        assertTrue(entry(killer).getLong("checkedAt") >= setAsideAt, "the entry is still the one of a month ago")
        assertEquals(List(4) { "NOT_FOUND" }, (good + again).map { entry(it).getString("state") })
        assertFalse(trace.exists(), "left behind: ${trace.takeIf { it.exists() }?.readText()}")

        val last = scan(ScanChild.On.HALT, "Killer.nes")
        assertEquals(0, last.status, last.said)
        assertEquals(emptyList(), last.handed)
    }

    // The system taking the service away, a kill, a power button held: far
    // more common than a file that ends the process, and none of the files
    // in hand had anything to do with it.
    @Test fun `a scan killed from outside costs no file its verdict`() {
        val files = (1..3).map { rom("Game$it.nes") } + rom("Slow.nes")

        val handed = File(work, "handed-killed.txt")
        val child = ScanChild.start(data, roms, handed, File(work, "out-killed.txt"), ScanChild.On.SLEEP, "Slow.nes")
        try {
            // Killed once the trace names the file, which is before the
            // hasher has it.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(ScanChild.LIMIT_SECONDS)
            while (!(trace.exists() && "Slow.nes" in trace.readText())) {
                if (!child.isAlive || System.nanoTime() > until)
                    fail("the scan never came to the file: ${File(work, "out-killed.txt").readText().takeLast(700)}")
                Thread.sleep(20)
            }
        } finally {
            child.destroyForcibly().waitFor()
        }

        val next = scan()
        assertEquals(0, next.status, next.said)
        assertEquals(listOf("> Slow.nes", "< Slow.nes"),
                     next.events.filter { it.endsWith(" Slow.nes") }, "${next.events}")
        // First, and with nothing begun before it had answered. What else
        // the dead scan had in hand is tried the same way around it.
        val at = next.events.indexOf("> Slow.nes")
        assertEquals("< Slow.nes", next.events[at + 1], "${next.events}")
        assertTrue(next.events.take(at).chunked(2).all { (a, b) -> a.drop(2) == b.drop(2) },
                   "a file was begun beside another: ${next.events}")
        assertEquals(List(4) { "NOT_FOUND" }, files.map { entry(it).getString("state") })
        assertFalse(trace.exists(), "left behind: ${trace.takeIf { it.exists() }?.readText()}")
    }

    // A JVM that runs its shutdown hooks did not die of anything in the
    // hasher: it was told to go, by a service manager, Ctrl-C, the machine
    // going down. The file in hand then is as good as any other.
    @Test fun `a scan ended by exit and not by halt leaves no suspect`() {
        // Enough of them that the scan is in the middle of its files while
        // the JVM goes down, which the child takes some time over: hashes
        // end and others begin after the hook has written its line.
        (1..30).forEach { rom("Later$it.nes") }
        val inHand = rom("InHand.nes")

        val first = scan(ScanChild.On.EXIT, "InHand.nes")
        assertEquals(0, first.status, first.said)
        assertFalse(ScanChild.DONE in first.said, "the scan got to its end, and this showed nothing")
        val lines = trace.readLines()
        assertTrue(lines.any { "InHand.nes" in it && "begin" in it }, "$lines")
        // The last line, with files handed over after it was written:
        // nothing a hash wrote on the way down may stand below it.
        assertEquals(setOf("stopped"), JSONObject(lines.last()).keySet(), "$lines")
        val begun = lines.count { "begin" in it }
        assertTrue(first.handed.size > begun, "no file was handed over after the stop, and this showed nothing: " +
                                              "${first.handed.size} handed, $begun begun in the trace")

        // Twice over would be a conviction, were these deaths.
        val second = scan(ScanChild.On.EXIT, "InHand.nes")
        assertEquals(0, second.status, second.said)
        val opened = HashTrace.start(trace, ScanLedger(File(data, "cache/${ScanLedger.FILE_NAME}")))
        try {
            assertEquals(emptySet(), opened.suspects + opened.convicted)
        } finally {
            opened.finish(ScanLedger(File(data, "cache/${ScanLedger.FILE_NAME}")))
        }

        val third = scan()
        assertEquals(0, third.status, third.said)
        assertEquals("NOT_FOUND", entry(inHand).getString("state"))
        assertFalse(trace.exists())
    }
}
