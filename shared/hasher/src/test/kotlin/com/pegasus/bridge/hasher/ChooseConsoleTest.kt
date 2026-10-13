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
            Case("megadrive", "x.chd", hash(9)),
            Case("segacd", "x.bin", hash(9)),
            Case("mastersystem", "x.sms", hash(11)),
            Case("gamegear", "x.gg", hash(15)),
            // rcheevos has a console for .sms since 12.5.0, so the extension
            // says which of a family the file is, and what a stray is.
            Case("gamegear", "x.sms", hash(11)),
            Case("gba", "x.sms", hash(11, foreign = true)),
            // PC Engine: the CD game is its descriptor or a .chd, and
            // nothing else. Among the HuCards a .chd is the disc a .cue is.
            Case("pcengine", "x.pce", hash(8)),
            Case("pcengine", "x.sgx", hash(8)),
            Case("pcengine", "x.cue", hash(76)),
            Case("pcengine", "x.chd", hash(76)),
            Case("pcenginecd", "x.cue", hash(76)),
            Case("pcenginecd", "x.chd", hash(76)),
            Case("pcenginecd", "X.CHD", hash(76)),
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
            // A .chd is a disc of its collection's console, for the nine
            // consoles the reader has been held to, loose or out of an
            // archive.
            Case("ps2", "x.chd", hash(21)),
            Case("ps2", "X.CHD", hash(21)),
            Case("ps2", "x.chd", hash(21), insideArchive = true),
            Case("ps2", "x.chd", hash(21), size = 4096 * MIB),
            Case("psx", "x.chd", hash(12)),
            Case("psp", "x.chd", hash(41)),
            Case("segacd", "x.chd", hash(9)),
            Case("saturn", "x.chd", hash(39)),
            Case("dreamcast", "x.chd", hash(40)),
            Case("3do", "x.chd", hash(43)),
            Case("neogeocd", "x.chd", hash(56)),
            Case("neogeocdjp", "x.chd", hash(56)),
            Case("neogeocdjp", "x.cue", hash(56)),
            Case("neogeocd", "x.iso", hash(56)),
            // And of no other: a cartridge console would hash the bytes of
            // the container, and the PC-FX and the Jaguar CD ask for tracks
            // in ways the reader has not been held to.
            Case("nes", "x.chd", UNSUPPORTED_FORMAT),
            Case("gc", "x.chd", UNSUPPORTED_FORMAT),
            Case("wii", "x.chd", UNSUPPORTED_FORMAT),
            Case("pcfx", "x.chd", UNSUPPORTED_FORMAT),
            Case("atarijaguar", "x.chd", UNSUPPORTED_FORMAT),
            // A folder of Jaguar CD games is that console for its sheets,
            // and its .chd is turned away as under `jaguar`.
            Case("atarijaguarcd", "x.cue", hash(77)),
            Case("jaguarcd", "x.cue", hash(77)),
            Case("atarijaguarcd", "x.chd", UNSUPPORTED_FORMAT),
            Case("jaguarcd", "X.CHD", UNSUPPORTED_FORMAT, insideArchive = true),
            Case("nes", "x.chd", UNSUPPORTED_FORMAT, insideArchive = true),
            Case("psp", "x.iso", hash(41)),
            Case("psp", "x.cso", UNSUPPORTED_FORMAT),
            Case("wii", "x.iso", hash(19, 16)),
            Case("wii", "x.gcm", hash(16)),
            Case("wii", "x.wad", hash(19)),
            // A .wbfs holds a Wii disc wherever it lies, and is a stray
            // where the Wii is not of the collection's family. Not one out
            // of an archive, which would be several GiB copied out to read
            // a disc whose other files may have stayed behind.
            Case("wii", "x.wbfs", hash(19)),
            Case("wii", "X.WBFS", hash(19)),
            Case("gc", "x.wbfs", hash(19)),
            Case("ps2", "x.wbfs", hash(19, foreign = true)),
            Case("wii", "x.wbfs", UNSUPPORTED_FORMAT, insideArchive = true),
            Case("arcade", "x.wbfs", UNSUPPORTED_FORMAT),
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
            // But for a Neo Geo cartridge in one file, which rcheevos hashes
            // by its bytes, as the console the sets are of.
            Case("arcade", "x.neo", hash(27)),
            Case("neogeo", "x.neo", hash(27)),
            Case("arcade", "X.NEO", hash(27)),
            Case("snes", "x.neo", hash(27, foreign = true)),
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
            // A .chd too: rcheevos tries it as each console it knows a
            // .chd of, and what it settles on is looked at afterwards.
            Case("somefolder", "x.chd", Plan.Guess),
            Case("somefolder", "x.chd", Plan.Guess, insideArchive = true),
            // Not guessed either: rcheevos has no console for the extension,
            // and its guess for one it does not know is the Game Boy.
            Case("somefolder", "x.wbfs", hash(19)),
            Case("somefolder", "x.wbfs", UNSUPPORTED_FORMAT, insideArchive = true),
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
        // rcheevos would hash this one, and any file beside it, as a
        // PlayStation 3 game. The collection is turned away whole.
        val ps3 = ConsoleChoice.choose(RcConsoles.row("ps3"), "bin", 1, false) as Plan.Unsupported
        assertEquals("rcheevos' algorithm for RC_CONSOLE_PLAYSTATION_3 (id 82) is held back: " +
                     "RetroAchievements has no hashes of PlayStation 3 games", ps3.reason)

        fun words(collection: String, extension: String, insideArchive: Boolean = false) =
            (ConsoleChoice.choose(RcConsoles.row(collection), extension, 1, insideArchive) as Plan.UnsupportedFormat).reason
        assertEquals("a .txt file is not a game", words("psx", "txt"))
        assertEquals(".cso is a format rcheevos does not read", words("psp", "CSO"))
        assertEquals("a .chd is read as a disc of nine consoles, and console 7 is not one of them", words("nes", "chd"))
        assertEquals("a .chd is read as a disc of nine consoles, and console 49 is not one of them", words("pcfx", "CHD"))
        assertEquals("a .chd is read as a disc of nine consoles, and console 77 is not one of them",
                     words("atarijaguar", "chd"))
        assertEquals(words("atarijaguar", "chd"), words("atarijaguarcd", "chd"))
        assertEquals("an arcade set is a .zip or a .7z, and this is a .bin", words("arcade", "bin"))
        assertEquals("an arcade set is a .zip or a .7z, and this is a .chd", words("arcade", "chd"))
        assertEquals("an arcade set inside an archive has lost its name", words("arcade", "zip", insideArchive = true))
        assertEquals("an archive inside an archive is not opened", words("nes", "7z", insideArchive = true))
        assertEquals("a .wbfs inside an archive is not unpacked", words("wii", "wbfs", insideArchive = true))
        assertEquals("a .md file in this collection is not a Mega Drive cartridge", words("psx", "md"))
        assertEquals("rcheevos reads a .pbp only as a PSP game", words("psx", "pbp"))
        assertEquals("a .cue describes a disc, and console 3 is not hashed as one", words("snes", "cue"))
        assertEquals("a PC Engine CD game is hashed from its .cue or its .chd, and this is a .iso",
                     words("pcenginecd", "iso"))
        // What a build with no reader for a format says of it, and the
        // ledger knows such a build's verdicts by. Nothing is turned away
        // in these words today.
        assertEquals(".xyz is a format this build has no reader for", HashRecipe.noReaderFor("xyz"))
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
                    if (row.arcade && extension != "neo")
                        wrong += "$what is in an arcade collection and is hashed as a file"
                    if (extension == "chd" && plan.console !in ConsoleChoice.CHD_CONSOLES)
                        wrong += "$what is a .chd hashed as console ${plan.console}"
                    if (extension == "chd" && plan.alternates.isNotEmpty()) wrong += "$what: $plan"
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

    /**
     * Where nothing is known of a collection rcheevos picks the console, from
     * a list of its own for the extension, and that list has consoles this
     * build is not to hash for: an .iso is tried as a PlayStation 3 disc.
     * Such an answer is no hash to keep or to ask about.
     */
    @Test fun `a guess that lands on a console held back is a failure, kept`() {
        val ps2 = HashOutcome.Ok(HashResult("0123456789abcdef0123456789abcdef", 21))
        val unread = HashOutcome.Failed("Could not open file")
        assertTrue(ConsoleChoice.guessed(ps2) === ps2, "a console that is hashed for")
        assertTrue(ConsoleChoice.guessed(unread) === unread, "a failure is rcheevos' to word")

        for (console in RcConsoles.HELD_BACK) {
            val name = RcConsoles.console(console)!!.constant
            assertEquals(HashOutcome.Failed("rcheevos takes it for a file of $name (id $console), which is held back",
                                            retryable = false),
                         ConsoleChoice.guessed(HashOutcome.Ok(HashResult("0123456789abcdef0123456789abcdef", console))),
                         "console $console")
        }
        assertTrue(82 in RcConsoles.HELD_BACK, "the PlayStation 3, which the guess for an .iso and a .chd tries")
    }

    /**
     * For a .chd that list has the PC-FX on it as well, which is none of
     * the consoles a .chd is read as. In a collection of that console the
     * file is turned away by its name; where nothing is known of the
     * collection the hash has been made by the time anybody can tell, and
     * is not kept. The same console is a good answer for any other file.
     */
    @Test fun `a guess that takes a chd for a console it is not read as is a failure, kept`() {
        fun answered(console: Int) = HashOutcome.Ok(HashResult("0123456789abcdef0123456789abcdef", console))

        for (console in ConsoleChoice.CHD_CONSOLES) {
            val ok = answered(console)
            assertTrue(ConsoleChoice.guessed(ok, "chd") === ok, "console $console")
        }
        for (extension in listOf("chd", "CHD")) {
            assertEquals(HashOutcome.Failed("rcheevos takes this .chd for console 49, " +
                                            "which is not one of the nine a .chd is read as", retryable = false),
                         ConsoleChoice.guessed(answered(49), extension))
        }
        val pcfx = answered(49)
        assertTrue(ConsoleChoice.guessed(pcfx, "cue") === pcfx, "a cue sheet of the same console")
        assertTrue(ConsoleChoice.guessed(pcfx) === pcfx, "a caller that names no extension")
        // Held back comes first, and is said in its own words.
        assertEquals(HashOutcome.Failed("rcheevos takes it for a file of RC_CONSOLE_PLAYSTATION_3 (id 82), which is held back",
                                        retryable = false), ConsoleChoice.guessed(answered(82), "chd"))
        val unread = HashOutcome.Failed("Could not open file")
        assertTrue(ConsoleChoice.guessed(unread, "chd") === unread)
        assertEquals(setOf(9, 12, 21, 39, 40, 41, 43, 56, 76), ConsoleChoice.CHD_CONSOLES)
        assertTrue(ConsoleChoice.CHD_CONSOLES.all { RcConsoles.console(it)?.algorithm == RcConsoles.Algorithm.DISC },
                   "a console of the nine is not hashed as a disc")
    }

    @Test fun `the three lists of extensions that are never hashed`() {
        assertEquals(setOf("rvz", "wia", "gcz", "ciso", "cso", "zso", "isz", "ecm", "cdi", "nrg", "nsp", "xci",
                           "nsz", "xcz", "ccd", "toc", "mds", "sub"), ConsoleChoice.DENIED_ALWAYS)
        assertEquals(emptySet(), ConsoleChoice.DENIED_THIS_BUILD)
        assertEquals(ArchiveSelector.NEVER_THE_ROM + setOf("bml", "sbi", "lst", "elf"), ConsoleChoice.NOT_A_ROM)
        assertTrue("txt" in ConsoleChoice.NOT_A_ROM && "sav" in ConsoleChoice.NOT_A_ROM)
        // Markdown to most software and a Mega Drive cartridge here: the collection decides, not a list.
        assertTrue("md" !in ConsoleChoice.NOT_A_ROM)
        val lists = listOf(ConsoleChoice.DENIED_ALWAYS, ConsoleChoice.DENIED_THIS_BUILD, ConsoleChoice.NOT_A_ROM)
        assertEquals(lists.sumOf { it.size }, lists.flatten().toSet().size, "an extension in two lists")
        assertTrue(lists.flatten().all { it == it.lowercase() })
    }
}
