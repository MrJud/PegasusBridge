package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.RcConsoles
import kotlin.test.Test
import kotlin.test.assertEquals

class ScanWeightTest {

    private val MB = 1L shl 20
    private val GB = 1L shl 30

    /** The plan a scan makes for a loose file called so, in a collection called [collection]. */
    private fun plan(collection: String, extension: String, size: Long): ConsoleChoice.Plan =
        ConsoleChoice.choose(RcConsoles.resolve(collection, collection), extension, size, insideArchive = false)

    private fun weight(collection: String, extension: String, size: Long, standing: Boolean = false): Long =
        ScanWeight.of(plan(collection, extension, size), extension, size, standing)

    // The edge every pinned number of a small library rests on: under 64 KiB
    // a file weighs one, as a file counted.
    @Test fun `a file weighs one more for every 64 KiB the scan is to read of it`() {
        assertEquals(ConsoleChoice.Plan.Hash(5), plan("gba", "gba", 1))
        assertEquals(1, weight("gba", "gba", 0))
        assertEquals(1, weight("gba", "gba", 65_535))
        assertEquals(2, weight("gba", "gba", 65_536))
        assertEquals(2, weight("gba", "gba", 131_071))
        assertEquals(17, weight("gba", "gba", MB))
        // A size nothing has is no reading, and no weight under one.
        assertEquals(1, ScanWeight.of(ConsoleChoice.Plan.Hash(5), "gba", -1, standing = false))
    }

    @Test fun `a disc weighs the few megabytes a parser reads of it, whatever its size`() {
        assertEquals(ConsoleChoice.Plan.Hash(12), plan("psx", "chd", 4 * GB))
        assertEquals(65, weight("psx", "chd", 4 * GB))
        assertEquals(65, weight("ps2", "iso", 4 * GB))
        // Under the bound a disc is its size, as any file.
        assertEquals(17, weight("psx", "bin", MB))
    }

    // A sheet is a few lines, and what is read for it is the tracks it
    // names: weighed by its own size it was one unit, a file not read, for
    // the same reading its disc packed as a .chd is given 65 for.
    @Test fun `a disc's sheet weighs as the disc it names and not by its own few lines`() {
        assertEquals(ConsoleChoice.Plan.Hash(12), plan("psx", "cue", 120))
        assertEquals(65, weight("psx", "cue", 120))
        assertEquals(65, weight("psx", "CUE", 120))
        assertEquals(ConsoleChoice.Plan.Hash(40), plan("dreamcast", "gdi", 90))
        assertEquals(65, weight("dreamcast", "gdi", 90))
        assertEquals(65, ScanWeight.of(ConsoleChoice.Plan.Guess, "cue", 120, standing = false))
        assertEquals(65, ScanWeight.of(ConsoleChoice.Plan.Guess, "gdi", 90, standing = false))
        // Settled, or in a collection that is not hashed as discs, it is not read.
        assertEquals(1, weight("psx", "cue", 120, standing = true))
        assertEquals(1, weight("nes", "cue", 120))
        // An image of a few sectors is still its size.
        assertEquals(1, weight("psx", "iso", 4096))
        assertEquals(1, ScanWeight.of(ConsoleChoice.Plan.Guess, "chd", 4096, standing = false))
    }

    @Test fun `a Wii disc weighs 48 MiB, in its container or out of it`() {
        assertEquals(ConsoleChoice.Plan.Hash(19), plan("wii", "wbfs", 4 * GB))
        assertEquals(769, weight("wii", "wbfs", 4 * GB + 700 * MB))
        assertEquals(769, weight("wii", "iso", 4 * GB + 700 * MB))
        // And in a folder nothing is known of, where a .wbfs is a Wii disc still.
        assertEquals(769, weight("somewhere", "wbfs", 4 * GB))
    }

    // Held to its size, a DS collection would take most of the bar and be
    // over in a second: half a megabyte is read of half a gigabyte.
    @Test fun `a Nintendo DS cartridge weighs as a disc does and not by its size`() {
        assertEquals(ConsoleChoice.Plan.Hash(18), plan("nds", "nds", 512 * MB))
        assertEquals(65, weight("nds", "nds", 512 * MB))
        assertEquals(65, ScanWeight.of(ConsoleChoice.Plan.Hash(78), "nds", 512 * MB, standing = false))
    }

    @Test fun `a file hashed whole weighs no more than the 64 MiB rcheevos reads of one`() {
        assertEquals(ScanWeight.WHOLE_BYTES, ArchiveAwareHasher.DIGEST_LIMIT)
        assertEquals(1025, weight("gba", "gba", 200 * MB))
        assertEquals(1025, weight("gba", "gba", 64 * MB))
        assertEquals(1024, weight("gba", "gba", 64 * MB - 1))
        // The Nintendo 64 has a parser and reads the file all the same.
        assertEquals(1025, weight("n64", "z64", 200 * MB))
    }

    @Test fun `an archive weighs its whole size, since its entry is unpacked whole`() {
        assertEquals(ConsoleChoice.Plan.OpenArchive, plan("psx", "zip", GB))
        assertEquals(16_385, weight("psx", "zip", GB))
        assertEquals(16_385, weight("somewhere", "7z", GB))
    }

    @Test fun `a file that is not read weighs one, whatever its size`() {
        // A verdict that stands.
        assertEquals(1, weight("psx", "chd", 4 * GB, standing = true))
        assertEquals(1, weight("psx", "zip", GB, standing = true))
        // An arcade set, hashed by its name.
        assertEquals(ConsoleChoice.Plan.ArcadeSet, plan("arcade", "zip", 300 * MB))
        assertEquals(1, weight("arcade", "zip", 300 * MB))
        // A format nobody reads, and a console nobody hashes.
        assertEquals(1, weight("gc", "rvz", 2 * GB))
        assertEquals(1, ScanWeight.of(ConsoleChoice.Plan.Unsupported("no"), "iso", 2 * GB, standing = false))
    }

    @Test fun `a file left to a guess weighs as a disc when its name is a disc's`() {
        assertEquals(ConsoleChoice.Plan.Guess, plan("somewhere", "chd", 4 * GB))
        for (ext in listOf("chd", "iso", "cue", "gdi", "CHD"))
            assertEquals(65, ScanWeight.of(ConsoleChoice.Plan.Guess, ext, 4 * GB, standing = false), ext)
        assertEquals(1025, weight("somewhere", "bin", 700 * MB))
        assertEquals(1, ScanWeight.of(ConsoleChoice.Plan.ResolvePlaylist, "m3u", 80, standing = false))
    }
}
