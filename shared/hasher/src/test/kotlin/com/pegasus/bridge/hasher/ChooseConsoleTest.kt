package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.RcConsoles
import com.pegasus.bridge.hasher.ConsoleChoice.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What is done with a file, by the collection it is in and what it is called.
 *
 * Each row is a file of a kind a real library holds, and what rcheevos made
 * of it when left to guess: the MD5 of a compressed image, of a readme, of a
 * descriptor's few lines, or of a cartridge under the wrong console. The
 * rows are in one table, and every wrong one is reported at once.
 */
class ChooseConsoleTest {

    private companion object {
        const val MIB = 1024L * 1024L
        /** Any reason will do: the kind of the plan is what is asked. */
        val UNSUPPORTED = Plan.Unsupported("")
        val UNSUPPORTED_FORMAT = Plan.UnsupportedFormat("")
    }

    private class Case(val collection: String, val file: String, val expected: Plan,
                       val size: Long = 512 * 1024, val insideArchive: Boolean = false)

    private fun hash(console: Int, vararg alternates: Int, foreign: Boolean = false) =
        Plan.Hash(console, alternates.toList(), foreign)

    private fun choose(case: Case): Plan =
        ConsoleChoice.choose(RcConsoles.row(case.collection), case.file.substringAfterLast('.', ""),
                             case.size, case.insideArchive)

    /** Equal, but for the words of a refusal, which only have to be there. */
    private fun same(expected: Plan, actual: Plan): Boolean = when (expected) {
        is Plan.Unsupported -> actual is Plan.Unsupported && actual.reason.isNotBlank()
        is Plan.UnsupportedFormat -> actual is Plan.UnsupportedFormat && actual.reason.isNotBlank()
        else -> expected == actual
    }

    @Test fun `the console chosen for each collection and extension`() {
        val cases = listOf(
            // The extension's own console, inside the family and outside it.
            Case("gba", "x.gba", hash(5)),
            Case("gba", "x.gb", hash(4)),
            Case("snes", "x.sfc", hash(3)),
            Case("snes", "x.nes", hash(7, foreign = true)),
            Case("snes", "x.gb", hash(4)),
            Case("snes", "x.z64", hash(2, foreign = true)),
            Case("nes", "x.nes", hash(7)),
            Case("nes", "x.fds", hash(81)),
            // An extension of a console this build cannot hash is no evidence.
            Case("gba", "x.3ds", hash(5)),
            // Mega Drive: cartridges, and the discs and add-ons kept with them.
            Case("megadrive", "x.md", hash(1)),
            Case("megadrive", "x.gen", hash(1)),
            Case("megadrive", "x.smd", hash(1)),
            Case("megadrive", "x.bin", hash(1)),
            Case("megadrive", "x.cue", hash(9)),
            Case("megadrive", "x.iso", hash(9)),
            Case("megadrive", "x.32x", hash(10)),
            Case("megadrive", "x.sms", hash(11)),
            Case("megadrive", "x.gg", hash(15)),
            Case("megadrive", "x.bin", hash(9), size = 40 * MIB),
            Case("megadrive", "x.img", hash(9), size = 40 * MIB),
            Case("megadrive", "x.bin", hash(1), size = 32 * MIB),
            Case("megadrive", "x.bin", hash(9), size = 32 * MIB + 1),
            // The size says something of a .bin and an .img alone.
            Case("megadrive", "x.md", hash(1), size = 40 * MIB),
            Case("megadrive", "x.gdi", hash(40, foreign = true)),
            Case("segacd", "x.bin", hash(9)),
            Case("mastersystem", "x.sms", hash(11)),
            Case("gamegear", "x.gg", hash(15)),
            // PC Engine: the CD game is its descriptor and nothing else.
            Case("pcengine", "x.pce", hash(8)),
            Case("pcengine", "x.sgx", hash(8)),
            Case("pcengine", "x.cue", hash(76)),
            Case("pcenginecd", "x.cue", hash(76)),
            Case("pcenginecd", "x.iso", UNSUPPORTED_FORMAT),
            Case("pcenginecd", "x.bin", UNSUPPORTED_FORMAT),
            Case("atarijaguar", "x.j64", hash(17)),
            Case("atarijaguar", "x.cue", hash(77)),
            // Discs.
            Case("psx", "x.cue", hash(12)),
            Case("psx", "x.bin", hash(12)),
            Case("psx", "x.img", hash(12)),
            Case("psx", "x.iso", hash(12)),
            Case("psx", "X.CUE", hash(12)),
            Case("psx", "x.pbp", UNSUPPORTED_FORMAT),
            Case("psp", "x.pbp", hash(41)),
            Case("psx", "x.m3u", Plan.ResolvePlaylist),
            Case("gba", "x.m3u", Plan.ResolvePlaylist),
            Case("ps2", "x.iso", hash(21)),
            Case("ps2", "x.chd", UNSUPPORTED_FORMAT),
            Case("psp", "x.iso", hash(41)),
            Case("psp", "x.cso", UNSUPPORTED_FORMAT),
            Case("wii", "x.iso", hash(19, 16)),
            Case("wii", "x.gcm", hash(16)),
            Case("wii", "x.wad", hash(19)),
            Case("wii", "x.wbfs", UNSUPPORTED_FORMAT),
            Case("gc", "x.iso", hash(16, 19)),
            Case("gc", "x.gcm", hash(16)),
            Case("gc", "x.rvz", UNSUPPORTED_FORMAT),
            Case("nds", "x.dsi", hash(18)),
            Case("dreamcast", "x.gdi", hash(40)),
            Case("dreamcast", "x.cue", hash(40)),
            Case("dreamcast", "x.cdi", UNSUPPORTED_FORMAT),
            Case("3do", "x.bin", hash(43)),
            Case("adam", "x.bin", hash(44)),
            Case("psx", "x.ccd", UNSUPPORTED_FORMAT),
            Case("saturn", "x.toc", UNSUPPORTED_FORMAT),
            // A descriptor in a collection of cartridges would be hashed as text.
            Case("snes", "x.cue", UNSUPPORTED_FORMAT),
            Case("gba", "x.cue", UNSUPPORTED_FORMAT),
            Case("gba", "x.gdi", hash(40, foreign = true)),
            // What is not a game.
            Case("psx", "README.md", UNSUPPORTED_FORMAT),
            Case("psx", "notes.txt", UNSUPPORTED_FORMAT),
            Case("psx", "boot.elf", UNSUPPORTED_FORMAT),
            // Arcade: the set by its name, and nothing else in the folder.
            Case("arcade", "set.zip", Plan.ArcadeSet),
            Case("arcade", "set.7z", Plan.ArcadeSet),
            Case("atomiswave", "set.zip", Plan.ArcadeSet),
            Case("atomiswave", "set.7z", Plan.ArcadeSet),
            Case("mame", "set.zip", Plan.ArcadeSet),
            Case("neogeo", "set.zip", Plan.ArcadeSet),
            Case("arcade", "chip.bin", UNSUPPORTED_FORMAT),
            Case("arcade", "disk.chd", UNSUPPORTED_FORMAT),
            Case("arcade", "list.m3u", UNSUPPORTED_FORMAT),
            Case("arcade", "set.zip", UNSUPPORTED_FORMAT, insideArchive = true),
            // Archives are opened, once.
            Case("nes", "x.zip", Plan.OpenArchive),
            Case("psx", "x.7z", Plan.OpenArchive),
            Case("nes", "x.zip", UNSUPPORTED_FORMAT, insideArchive = true),
            Case("nes", "x.nes", hash(7), insideArchive = true),
            // Collections nobody can hash for, whatever the file.
            Case("amiga", "x.adf", UNSUPPORTED),
            Case("cdimono1", "x.bin", UNSUPPORTED),
            Case("n3ds", "x.cci", UNSUPPORTED),
            Case("ps3", "EBOOT.BIN", UNSUPPORTED),
            Case("switch", "x.zip", UNSUPPORTED),
            Case("switch", "x.m3u", UNSUPPORTED),
            Case("switch", "x.xci", UNSUPPORTED),
            Case("bbcmicro", "x.ssd", UNSUPPORTED),
            // A collection with no row: guessed, but for what no guess can hash.
            Case("somefolder", "x.nes", Plan.Guess),
            Case("somefolder", "x.bin", Plan.Guess, size = 40 * MIB),
            Case("somefolder", "x.cue", Plan.Guess),
            Case("somefolder", "x.md", Plan.Guess),
            Case("somefolder", "x.wbfs", UNSUPPORTED_FORMAT),
            Case("somefolder", "x.cso", UNSUPPORTED_FORMAT),
            Case("somefolder", "x.txt", UNSUPPORTED_FORMAT),
            Case("somefolder", "x.m3u", Plan.ResolvePlaylist),
            Case("somefolder", "x.zip", Plan.OpenArchive),
            Case("somefolder", "x.zip", UNSUPPORTED_FORMAT, insideArchive = true)
        )
        val wrong = cases.mapNotNull { case ->
            val actual = choose(case)
            if (same(case.expected, actual)) null
            else "${case.collection}/${case.file} of ${case.size} bytes" +
                 (if (case.insideArchive) ", out of an archive" else "") + ": expected ${case.expected}, got $actual"
        }
        if (wrong.isNotEmpty()) fail("${wrong.size} of ${cases.size} files planned wrongly:\n  " + wrong.joinToString("\n  "))
        assertEquals(null, RcConsoles.row("somefolder"), "the test's unknown collection has a row")
    }

    @Test fun `a refusal says why`() {
        val switch = ConsoleChoice.choose(RcConsoles.row("switch"), "nsp", 1, false) as Plan.Unsupported
        assertEquals((RcConsoles.row("switch") as RcConsoles.NotOnRa).reason, switch.reason)
        val n3ds = ConsoleChoice.choose(RcConsoles.row("n3ds"), "cci", 1, false) as Plan.Unsupported
        assertEquals((RcConsoles.row("3ds") as RcConsoles.NoAlgorithm).reason, n3ds.reason)

        fun words(collection: String, extension: String, insideArchive: Boolean = false) =
            (ConsoleChoice.choose(RcConsoles.row(collection), extension, 1, insideArchive) as Plan.UnsupportedFormat).reason
        assertEquals("a .txt file is not a game", words("psx", "txt"))
        assertEquals(".cso is a format rcheevos does not read", words("psp", "CSO"))
        assertEquals(".chd is a format this build has no reader for", words("ps2", "chd"))
        assertEquals("an arcade set is a .zip or a .7z, and this is a .bin", words("arcade", "bin"))
        assertEquals("an arcade set inside an archive has lost its name", words("arcade", "zip", insideArchive = true))
        assertEquals("an archive inside an archive is not opened", words("nes", "7z", insideArchive = true))
        assertEquals("a .md file in this collection is not a Mega Drive cartridge", words("psx", "md"))
        assertEquals("rcheevos reads a .pbp only as a PSP game", words("psx", "pbp"))
        assertEquals("a .cue describes a disc, and console 3 is not hashed as one", words("snes", "cue"))
        assertEquals("a PC Engine CD game is hashed from its .cue, and this is a .iso", words("pcenginecd", "iso"))
    }

    /**
     * Every row against every extension a scan picks up, at sizes either side
     * of what a row asks about. A known collection is never guessed for: that
     * is what knowing it is for.
     */
    @Test fun `no known collection falls back to guessing`() {
        val denied = ConsoleChoice.DENIED_ALWAYS + ConsoleChoice.DENIED_THIS_BUILD
        val extensions = RomScanner.ROM_EXTENSIONS + RomHashIO.RCHEEVOS_EXTENSIONS + denied + ConsoleChoice.NOT_A_ROM
        val wrong = mutableListOf<String>()
        var planned = 0
        for (row in RcConsoles.ROWS) for (extension in extensions) for (size in listOf(0L, 1L, 40 * MIB)) {
            for (insideArchive in listOf(false, true)) {
                val what = "${row.key}/x.$extension of $size bytes" + if (insideArchive) ", out of an archive" else ""
                val plan = try {
                    ConsoleChoice.choose(row, extension, size, insideArchive)
                } catch (e: Exception) {
                    wrong += "$what: $e"
                    continue
                }
                planned++
                if (plan is Plan.Guess) wrong += "$what is guessed for"
                if (row !is RcConsoles.Hashable && plan !is Plan.Unsupported) wrong += "$what: $plan"
                if (plan is Plan.Hash) {
                    if (extension in setOf("zip", "7z", "m3u") || extension in denied || extension in ConsoleChoice.NOT_A_ROM)
                        wrong += "$what is hashed as console ${plan.console}"
                    if (!RcConsoles.canHash(plan.console)) wrong += "$what is sent to console ${plan.console}, which cannot be hashed"
                    if (plan.alternates.any { !RcConsoles.canHash(it) || it == plan.console }) wrong += "$what: $plan"
                    val family = (row as RcConsoles.Hashable).family
                    if (plan.foreign == (plan.console in family)) wrong += "$what: $plan, and the family is $family"
                    if (row.arcade) wrong += "$what is in an arcade collection and is hashed as a file"
                }
                if (plan is Plan.ArcadeSet && !(row is RcConsoles.Hashable && row.arcade)) wrong += "$what is an arcade set"
            }
        }
        if (wrong.isNotEmpty()) fail("${wrong.size} plans that cannot be:\n  " + wrong.take(40).joinToString("\n  "))
        assertTrue(planned > 40_000, "only $planned plans were made")

        // No row names its own console as an alternate, nor one an extension is sent to. A row
        // that did would not have a console tried twice.
        val both = RcConsoles.Hashable("x", emptyList(), 19, setOf(16, 19), overridesByExtension = mapOf("gcm" to 16),
                                       alternates = mapOf("iso" to listOf(19, 16), "gcm" to listOf(16, 19)))
        assertEquals(Plan.Hash(19, listOf(16)), ConsoleChoice.choose(both, "iso", 1, false))
        assertEquals(Plan.Hash(16, listOf(19)), ConsoleChoice.choose(both, "gcm", 1, false))
        assertTrue(RomScanner.ROM_EXTENSIONS.containsAll(listOf("zip", "7z", "m3u", "chd", "cso", "wbfs", "rvz", "cdi")))
    }

    @Test fun `the three lists of extensions that are never hashed`() {
        assertEquals(setOf("rvz", "wia", "gcz", "ciso", "cso", "zso", "isz", "ecm", "cdi", "nrg", "nsp", "xci",
                           "nsz", "xcz", "ccd", "toc", "mds", "sub"), ConsoleChoice.DENIED_ALWAYS)
        assertEquals(setOf("chd", "wbfs"), ConsoleChoice.DENIED_THIS_BUILD)
        assertEquals(ArchiveSelector.NEVER_THE_ROM + setOf("bml", "sbi", "lst", "elf"), ConsoleChoice.NOT_A_ROM)
        assertTrue("txt" in ConsoleChoice.NOT_A_ROM && "sav" in ConsoleChoice.NOT_A_ROM)
        // Markdown to most software and a Mega Drive cartridge here: the collection decides, not a list.
        assertTrue("md" !in ConsoleChoice.NOT_A_ROM)
        val lists = listOf(ConsoleChoice.DENIED_ALWAYS, ConsoleChoice.DENIED_THIS_BUILD, ConsoleChoice.NOT_A_ROM)
        assertEquals(lists.sumOf { it.size }, lists.flatten().toSet().size, "an extension in two lists")
        assertTrue(lists.flatten().all { it == it.lowercase() })
    }
}
