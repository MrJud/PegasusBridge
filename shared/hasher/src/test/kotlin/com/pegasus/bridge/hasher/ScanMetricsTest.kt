package com.pegasus.bridge.hasher

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The numbers of a scan, with a clock and a count of bytes that only the
 * test moves: nothing here waits, and every number is exact.
 */
class ScanMetricsTest {

    private val clock = AtomicLong()
    private fun pass(nanos: Long) { clock.addAndGet(nanos) }
    private fun passMs(ms: Long) = pass(ms * 1_000_000)

    // Each phase here lasts a nanosecond less than two milliseconds. Turned
    // to milliseconds one by one they are 1 each and 4 together, for a scan
    // that took 7. The start is below zero, as the system's clock may be.
    @Test fun `the four phases add up to the whole`() {
        clock.set(-5_000_000_000)
        val m = ScanMetrics(clock::get, { null })
        pass(1_999_999); m.walked()
        pass(1_999_999); m.prepared()
        pass(1_999_999); m.scanned()
        pass(1_999_999); m.finished()
        // Time that passes after the last mark is not the scan's.
        passMs(40)

        val s = m.snapshot(hashWorkers = 4, lookupWorkers = 2)
        assertEquals(listOf(7L, 1L, 2L, 2L, 2L), listOf(s.wallMs, s.walkMs, s.prepareMs, s.scanMs, s.finishMs))
        assertEquals(s.wallMs, s.walkMs + s.prepareMs + s.scanMs + s.finishMs)
        assertEquals(listOf(4, 2), listOf(s.hashWorkers, s.lookupWorkers))

        // A library with no file is walked and finished, and the two phases
        // it never reached come to nothing.
        val empty = ScanMetrics(clock::get, { null })
        passMs(30); empty.walked()
        passMs(12); empty.finished()
        val e = empty.snapshot(1, 1)
        assertEquals(listOf(42L, 30L, 0L, 0L, 12L), listOf(e.wallMs, e.walkMs, e.prepareMs, e.scanMs, e.finishMs))

        // What goes on inside the scanning phase is added up apart, and can
        // come to more than the phase: two workers were busy at once.
        val busy = ScanMetrics(clock::get, { null })
        busy.walked(); busy.prepared()
        busy.hashing("/roms/a.bin", 10) { passMs(100) }
        busy.hashing("/roms/b.bin", 10) { passMs(100) }
        clock.addAndGet(-100 * 1_000_000L)
        busy.lookingUp { passMs(7) }
        busy.lookingUp { passMs(8) }
        busy.writing { passMs(3) }
        busy.throttled(250); busy.throttled(250)
        busy.scanned(); busy.finished()
        val b = busy.snapshot(2, 1)
        assertEquals(listOf(118L, 118L), listOf(b.wallMs, b.scanMs))
        assertEquals(listOf(200L, 15L, 500L, 3L), listOf(b.hashMs, b.lookupMs, b.throttleMs, b.writeMs))
        assertEquals(listOf(2, 2), listOf(b.filesHashed, b.lookups))
    }

    @Test fun `the five slowest files are kept, slowest first`() {
        val m = ScanMetrics(clock::get, { null })
        val took = listOf("c" to 30L, "g" to 5L, "a" to 70L, "e" to 30L, "b" to 1L, "f" to 900L, "d" to 30L)
        for ((name, ms) in took) m.hashing("/roms/$name.bin", ms * 10) { passMs(ms) }
        // One that throws took its time all the same.
        assertFailsWith<IllegalStateException> {
            m.hashing("/roms/broken.bin", 4) { passMs(60); error("unreadable") }
        }

        val s = m.snapshot(1, 1)
        assertEquals(8, s.filesHashed)
        assertEquals(1126L, s.hashMs)
        // Three took 30 ms: by path, so that the list does not go by which
        // of them a worker happened to finish first.
        assertEquals(
            listOf("/roms/f.bin" to 900L, "/roms/a.bin" to 70L, "/roms/broken.bin" to 60L,
                   "/roms/c.bin" to 30L, "/roms/d.bin" to 30L),
            s.slowest.map { it.path to it.ms })
        assertEquals(listOf(9000L, 700L, 4L, 300L, 300L), s.slowest.map { it.size })
    }

    @Test fun `bytes are added up where the system counts them and left out where it does not`() {
        // The system's count as it goes: each taking of it adds its own
        // length, 120 here, which is not the file's.
        val read = AtomicLong(1_000)
        val counted = ScanMetrics(clock::get, { IoCounters.Count(read.getAndAdd(120), 120) })
        counted.hashing("/roms/whole.bin", 4096) { read.addAndGet(4096); passMs(2) }
        counted.hashing("/roms/disc.chd", 700_000_000) { read.addAndGet(65_536); passMs(9) }
        counted.hashing("/roms/unopened.zip", 512) { passMs(1) }

        val s = counted.snapshot(1, 1)
        assertEquals(4096L + 65_536L, s.bytesRead)
        assertEquals(listOf("disc.chd" to 65_536L, "whole.bin" to 4096L, "unopened.zip" to 0L),
                     s.slowest.map { it.path.substringAfterLast('/') to it.bytesRead })
        assertEquals(65_536L, s.toJson().getJSONArray("slowest").getJSONObject(0).getLong("bytesRead"))

        // No count from the system: no number, which is not a count of 0.
        val blind = ScanMetrics(clock::get, { null })
        blind.hashing("/roms/whole.bin", 4096) { passMs(2) }
        val b = blind.snapshot(1, 1)
        assertNull(b.bytesRead)
        assertEquals(listOf<Long?>(null), b.slowest.map { it.bytesRead })
        assertFalse(b.toJson().has("bytesRead"))
        assertFalse(b.toJson().getJSONArray("slowest").getJSONObject(0).has("bytesRead"))

        // And none for a scan that handed the hasher nothing, whatever the
        // system would have said.
        assertNull(ScanMetrics(clock::get, { IoCounters.Count(0, 120) }).snapshot(1, 1).bytesRead)
    }

    // What both shells publish. A key added or renamed here changes what a
    // theme is promised, and the contract's document with it.
    @Test fun `the metrics written as JSON have these keys and no others`() {
        val full = ScanMetrics.Snapshot(
            wallMs = 51_240, walkMs = 820, prepareMs = 310, scanMs = 49_600, finishMs = 510,
            hashMs = 181_300, lookupMs = 2_100, throttleMs = 0, writeMs = 640,
            hashWorkers = 4, lookupWorkers = 2, filesHashed = 1973, lookups = 1890,
            bytesRead = 16_857_798_246,
            slowest = listOf(ScanMetrics.Slow("/library/ps2/Example Disc.chd", 9_440, 4_361_240_576, 5_898_240))
        ).toJson()

        assertEquals(setOf("wallMs", "phaseMs", "busyMs", "hashWorkers", "lookupWorkers",
                           "filesHashed", "lookups", "bytesRead", "slowest"), full.keySet())
        assertEquals(mapOf<String, Any>("walk" to 820L, "prepare" to 310L, "scan" to 49_600L, "finish" to 510L),
                     full.getJSONObject("phaseMs").toMap())
        assertEquals(mapOf<String, Any>("hash" to 181_300L, "lookup" to 2_100L, "throttle" to 0L, "write" to 640L),
                     full.getJSONObject("busyMs").toMap())
        assertEquals(listOf(51_240L, 4L, 2L, 1973L, 1890L, 16_857_798_246L),
                     listOf("wallMs", "hashWorkers", "lookupWorkers", "filesHashed", "lookups", "bytesRead")
                         .map { full.getLong(it) })
        val slow = full.getJSONArray("slowest")
        assertEquals(1, slow.length())
        assertEquals(mapOf<String, Any>("file" to "/library/ps2/Example Disc.chd", "ms" to 9_440L,
                                        "size" to 4_361_240_576, "bytesRead" to 5_898_240L),
                     slow.getJSONObject(0).toMap())

        // Without a count of bytes the two keys are gone and nothing else is.
        val bare = ScanMetrics.Snapshot(4, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 0, null,
                                        listOf(ScanMetrics.Slow("/library/nes/Example.nes", 0, 24_592, null))).toJson()
        assertEquals(full.keySet() - "bytesRead", bare.keySet())
        assertEquals(setOf("file", "ms", "size"), bare.getJSONArray("slowest").getJSONObject(0).keySet())
        // Nothing hashed: an empty list, and not a key left out.
        assertEquals(0, ScanMetrics.Snapshot(4, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, null, emptyList())
            .toJson().getJSONArray("slowest").length())
    }
}
