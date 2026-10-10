package com.pegasus.bridge.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The console table against the rcheevos it is a copy of, and the rows
 * against what they are for.
 *
 * The first half reads the vendored C sources, by the path the hasher's own
 * test of the extension table uses: Gradle runs tests from the module's
 * directory, and shared/core is as deep as shared/hasher. A file that is not
 * there fails the test. Skipped, it would leave the copy unchecked on the day
 * the sources move.
 */
class RcConsolesTest {

    private fun vendored(path: String): String {
        val file = File("../../hasher/src/main/cpp/rcheevos/$path")
        assertTrue(file.isFile, "the vendored rcheevos is not at ${file.absolutePath}")
        return file.readText()
    }

    /** Fails with every wrong row at once: one at a time, a table is slow to put right. */
    private fun report(what: String, wrong: List<String>) {
        if (wrong.isNotEmpty()) fail("$what:\n  " + wrong.joinToString("\n  "))
    }

    private fun hashable(name: String): RcConsoles.Hashable =
        RcConsoles.row(name) as? RcConsoles.Hashable ?: fail("$name has no hashable row: ${RcConsoles.row(name)}")

    // ── Against rc_consoles.h and hash.c ────────────────────────────────────

    @Test fun `the console ids are the header's`() {
        val header = vendored("include/rc_consoles.h")
        // Above 99 the header lists hubs, events and standalone sets, which are
        // not consoles a file can be of.
        val inHeader = Regex("""RC_CONSOLE_(\w+)\s*=\s*(\d+)""").findAll(header)
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .filter { it.second in 1..99 }
            .toList()
        assertEquals(81, inHeader.size, "ids found in the header: $inHeader")
        assertEquals(inHeader, RcConsoles.CONSOLES.map { it.name to it.id })
        assertEquals("RC_CONSOLE_MEGA_DRIVE", RcConsoles.console(1)?.constant)
        assertNull(RcConsoles.console(0))
        assertNull(RcConsoles.console(82))
    }

    /**
     * rc_hash_from_file is one switch. The case labels above a body share it,
     * and a body under `#ifndef RC_HASH_NO_X` is only there in a build that
     * does not define it, which is the class. Outside every guard the class is
     * what the body calls.
     */
    private fun dispatched(hashC: String): Map<String, RcConsoles.Algorithm> {
        // The definition, not the declaration further up, which ends in a semicolon.
        val definition = Regex("""static int rc_hash_from_file\([^)]*\)\s*\{""").find(hashC)
            ?: fail("rc_hash_from_file is not in hash.c")
        val body = hashC.substring(definition.range.last).substringBefore("\n}\n")
        val classes = LinkedHashMap<String, RcConsoles.Algorithm>()
        var guard: String? = null
        var guardOfGroup: String? = null
        val labels = mutableListOf<String>()
        val code = StringBuilder()

        fun close() {
            if (labels.isNotEmpty()) {
                val algorithm = when (guardOfGroup) {
                    "DISC" -> RcConsoles.Algorithm.DISC
                    "ROM" -> RcConsoles.Algorithm.ROM
                    "ZIP" -> RcConsoles.Algorithm.ZIP
                    "ENCRYPTED" -> RcConsoles.Algorithm.ENCRYPTED
                    null -> when {
                        "rc_hash_buffered_file(" in code -> RcConsoles.Algorithm.BUFFERED
                        "rc_hash_generate_from_playlist(" in code -> RcConsoles.Algorithm.WHOLE_M3U
                        "rc_hash_whole_file(" in code -> RcConsoles.Algorithm.WHOLE
                        else -> fail("no class for what $labels do: $code")
                    }
                    else -> fail("a guard this test does not know: RC_HASH_NO_$guardOfGroup")
                }
                for (label in labels) {
                    assertNull(classes.put(label, algorithm), "$label has two cases")
                }
            }
            labels.clear()
            code.setLength(0)
        }

        for (line in body.lines().map { it.trim() }) {
            val label = Regex("""^case RC_CONSOLE_(\w+):""").find(line)
            when {
                label != null -> {
                    if (code.isNotEmpty()) close()
                    if (labels.isEmpty()) guardOfGroup = guard
                    labels += label.groupValues[1]
                }
                line.startsWith("default:") -> close()
                line.startsWith("#ifndef RC_HASH_NO_") -> { close(); guard = line.substringAfter("RC_HASH_NO_").trim() }
                line.startsWith("#endif") -> { close(); guard = null }
                line.startsWith("#") -> fail("a preprocessor line this test does not know: $line")
                labels.isNotEmpty() -> code.append(line).append('\n')
            }
        }
        close()
        return classes
    }

    @Test fun `each algorithm class is what rc_hash_from_file dispatches`() {
        val classes = dispatched(vendored("src/rhash/hash.c"))
        assertTrue(classes.size > 50, "the switch was not read: $classes")

        val wrong = RcConsoles.CONSOLES.mapNotNull { console ->
            val there = classes[console.name] ?: RcConsoles.Algorithm.NONE
            if (there == console.algorithm) null
            else "${console.constant} (${console.id}) is ${console.algorithm} here and $there in hash.c"
        }
        report("the table is behind hash.c", wrong)
        assertEquals(emptySet(), classes.keys - RcConsoles.CONSOLES.map { it.name }.toSet(),
                     "cases for consoles the table lacks")

        assertEquals(listOf(20, 22, 31, 34, 35, 36, 42, 48, 50, 52, 54, 58, 60, 61, 64, 66, 67, 68, 70),
                     RcConsoles.CONSOLES.filter { it.algorithm == RcConsoles.Algorithm.NONE }.map { it.id })
    }

    /**
     * What is compiled is decided where the library is built, twice: by the
     * script for the desktop and by CMake for Android. Both are read, so that
     * a class switched off or on in either is a failure here, where the rows
     * that depend on it are.
     */
    @Test fun `every hashable row's console is compiled`() {
        val switchedOff = RcConsoles.NOT_COMPILED.map { "RC_HASH_NO_$it" }.toSet()
        for (path in listOf("../native/build.sh", "../../hasher/src/main/cpp/CMakeLists.txt")) {
            val file = File(path)
            assertTrue(file.isFile, "the build file is not at ${file.absolutePath}")
            val defined = file.readLines().map { it.substringBefore('#') }
                .flatMap { Regex("""RC_HASH_NO_\w+""").findAll(it).map { m -> m.value } }.toSet()
            assertEquals(switchedOff, defined, "what $path leaves out of the library")
        }

        val notCompiled = RcConsoles.CONSOLES.filter { it.algorithm in RcConsoles.NOT_COMPILED }.map { it.id }.toSet()
        assertEquals(setOf(62), RcConsoles.HELD_BACK, "held back")
        assertTrue(RcConsoles.HELD_BACK.containsAll(notCompiled), "not compiled and not held back: $notCompiled")

        val wrong = mutableListOf<String>()
        for (row in RcConsoles.ROWS.filterIsInstance<RcConsoles.Hashable>()) {
            val named = row.family + row.overridesByExtension.values + row.overridesBySize.map { it.console } +
                        row.alternates.values.flatten()
            for (id in named) if (!RcConsoles.canHash(id)) wrong += "${row.key} names console $id, which cannot be hashed"
            if (row.console !in row.family) wrong += "${row.key} is not in its own family"
            if (!row.family.containsAll(named)) wrong += "${row.key} sends files outside its family: $named"
            if (row.arcade != (row.console == 27)) wrong += "${row.key} is arcade and its console is ${row.console}"
        }
        for (row in RcConsoles.ROWS.filterIsInstance<RcConsoles.NoAlgorithm>()) {
            if (RcConsoles.console(row.id) == null) wrong += "${row.key} has id ${row.id}, which is no console"
            if (RcConsoles.canHash(row.id)) wrong += "${row.key} is refused and console ${row.id} can be hashed"
        }
        report("rows that cannot be", wrong)

        assertTrue(RcConsoles.canHash(7))
        for (id in listOf(0, 20, 35, 62, 64, 66, 82)) assertTrue(!RcConsoles.canHash(id), "console $id")
    }

    // ── The rows ────────────────────────────────────────────────────────────

    @Test fun `every row key is already normalised`() {
        val wrong = mutableListOf<String>()
        val claimed = HashMap<String, String>()
        for (row in RcConsoles.ROWS) {
            if (FuzzyMatch.normalizePlatform(row.key) != row.key)
                wrong += "${row.key} is normalised to ${FuzzyMatch.normalizePlatform(row.key)}, so nothing reaches it"
            for (name in listOf(row.key) + row.spellings) {
                if (name != name.lowercase()) wrong += "$name is not in lower case"
                // Two spellings of one row may fold onto each other; two rows may not share one.
                val before = claimed.put(FuzzyMatch.normalizePlatform(name), row.key)
                if (before != null && before != row.key) wrong += "$name is claimed by $before and by ${row.key}"
                if (RcConsoles.row(name) !== row) wrong += "$name does not reach the row ${row.key}"
            }
        }
        report("names in the table", wrong)

        // The four spellings normalizePlatform leaves alone, which the table folds itself.
        for ((spelling, key) in listOf("n3ds" to "3ds", "vita" to "psvita", "cdi" to "cdimono1", "tgcd" to "pcenginecd")) {
            assertEquals(spelling, FuzzyMatch.normalizePlatform(spelling))
            assertEquals(key, RcConsoles.row(spelling)?.key)
        }
        // And names as they are written on disk.
        assertEquals("mastersystem", RcConsoles.row("Sega Master System")?.key)
        assertEquals("wiiu", RcConsoles.row("Wii U")?.key)
        assertEquals("genesis", RcConsoles.row("MegaDrive")?.key)
        assertNull(RcConsoles.row(""))
        assertNull(RcConsoles.row(null))
        assertNull(RcConsoles.row("1_bios_pack"))
    }

    /**
     * The nine names a scan turned away before reading anything while it
     * had a list of its own for that, which this table replaced. None may
     * become hashable by the way.
     */
    @Test fun `every platform skipped today is still not hashable`() {
        val wrong = listOf("switch", "psvita", "wiiu", "pc", "windows", "android", "ios", "3ds", "n3ds")
            .mapNotNull { name ->
                when (val row = RcConsoles.row(name)) {
                    is RcConsoles.NotOnRa, is RcConsoles.NoAlgorithm -> null
                    else -> "$name: $row"
                }
            }
        report("skipped today and not refused by the table", wrong)
    }

    @Test fun `each collection is the console its games are listed under`() {
        val expected = mapOf(
            "nes" to 7, "famicom" to 7, "snes" to 3, "gb" to 4, "gbc" to 6, "gba" to 5, "n64" to 2,
            "nds" to 18, "ds" to 18, "psx" to 12, "ps1" to 12, "psone" to 12, "ps2" to 21, "psp" to 41,
            "3do" to 43, "dreamcast" to 40, "dc" to 40, "saturn" to 39,
            "genesis" to 1, "megadrive" to 1, "sega32x" to 10, "32x" to 10, "segacd" to 9, "megacd" to 9,
            "mastersystem" to 11, "sms" to 11, "gamegear" to 15, "gg" to 15, "sg1000" to 33,
            "pcengine" to 8, "tg16" to 8, "pcenginecd" to 76, "tgcd" to 76, "pcfx" to 49,
            "jaguar" to 17, "atarijaguar" to 17, "lynx" to 13, "atarilynx" to 13,
            "atari2600" to 25, "atari7800" to 51, "wii" to 19, "gc" to 16, "gamecube" to 16, "ngc" to 16,
            "adam" to 44, "colecovision" to 44, "amstradcpc" to 37, "cpc" to 37, "apple2" to 38,
            "arcadia" to 73, "arduboy" to 71, "c64" to 30, "commodore64" to 30, "channelf" to 57,
            "msx" to 29, "msx2" to 29, "ngp" to 14, "ngpc" to 14, "pokemini" to 24,
            "supervision" to 63, "watara" to 63, "vectrex" to 46, "intellivision" to 45, "virtualboy" to 28,
            "wonderswan" to 53, "wonderswancolor" to 53, "zxspectrum" to 59, "spectrum" to 59,
            "arcade" to 27, "mame" to 27, "fbneo" to 27, "fba" to 27, "atomiswave" to 27, "neogeo" to 27,
            "naomi" to 27, "cps1" to 27, "cps2" to 27, "cps3" to 27)
        val wrong = expected.mapNotNull { (name, console) ->
            val row = RcConsoles.row(name)
            if (row is RcConsoles.Hashable && row.console == console) null else "$name should be $console: $row"
        }.toMutableList()
        val arcade = setOf("arcade", "atomiswave", "neogeo", "naomi", "cps1", "cps2", "cps3")
        for (row in RcConsoles.ROWS.filterIsInstance<RcConsoles.Hashable>()) {
            if (row.arcade != (row.key in arcade)) wrong += "${row.key}: arcade is ${row.arcade}"
            for (name in listOf(row.key) + row.spellings) if (name !in expected) wrong += "$name is not in this test"
        }
        report("hashable rows", wrong)

        val refused = mapOf("amiga" to 35, "cdimono1" to 42, "cdi" to 42, "wiiu" to 20, "3ds" to 62, "n3ds" to 62)
        for ((name, id) in refused) {
            val row = RcConsoles.row(name)
            assertTrue(row is RcConsoles.NoAlgorithm && row.id == id, "$name should be refused under id $id: $row")
        }
        for (name in listOf("switch", "psvita", "vita", "ps3", "bbcmicro", "chailove", "cdtv", "pc", "windows",
                            "android", "ios")) {
            assertTrue(RcConsoles.row(name) is RcConsoles.NotOnRa, "$name: ${RcConsoles.row(name)}")
        }
        assertEquals(expected.size + refused.size + 11,
                     RcConsoles.ROWS.sumOf { 1 + it.spellings.size }, "names in the table and in this test")
    }

    @Test fun `a family says which of its consoles a file is`() {
        val genesis = hashable("megadrive")
        assertEquals(setOf(1, 9, 10, 11, 15, 33), genesis.family)
        assertEquals(mapOf("cue" to 9, "iso" to 9, "chd" to 9, "32x" to 10, "sms" to 11), genesis.overridesByExtension)
        // rcheevos takes a .bin for a CD track above 32 MiB, and not at it.
        assertEquals(listOf(RcConsoles.BySize(setOf("bin", "img"), 32L * 1024 * 1024, 9)), genesis.overridesBySize)

        assertEquals(setOf(7, 81), hashable("nes").family)
        assertEquals(mapOf("fds" to 81), hashable("nes").overridesByExtension)
        assertEquals(setOf(3, 4, 6), hashable("snes").family)
        assertEquals(setOf(4, 6), hashable("gb").family)
        assertEquals(setOf(4, 6), hashable("gbc").family)
        assertEquals(setOf(4, 5, 6), hashable("gba").family)
        assertEquals(setOf(11, 15, 33), hashable("mastersystem").family)
        assertEquals(setOf(11, 15), hashable("gamegear").family)
        assertEquals(mapOf("cue" to 76), hashable("pcengine").overridesByExtension)
        assertEquals(mapOf("cue" to 77), hashable("jaguar").overridesByExtension)

        val wii = hashable("wii")
        assertEquals(mapOf("gcm" to 16), wii.overridesByExtension)
        assertEquals(mapOf("iso" to listOf(16)), wii.alternates)
        assertEquals(mapOf("iso" to listOf(19)), hashable("gc").alternates)
        assertEquals(emptyMap(), hashable("gc").overridesByExtension)

        // Everything else is its console and nothing more.
        val plain = RcConsoles.ROWS.filterIsInstance<RcConsoles.Hashable>().filter {
            it.key !in setOf("nes", "snes", "gb", "gbc", "gba", "genesis", "mastersystem", "gamegear",
                             "pcengine", "jaguar", "wii", "gc")
        }
        report("rows with more than a console", plain.filter {
            it.family != setOf(it.console) || it.overridesByExtension.isNotEmpty() ||
            it.overridesBySize.isNotEmpty() || it.alternates.isNotEmpty()
        }.map { it.toString() })
    }

    // ── resolve ─────────────────────────────────────────────────────────────

    @Test fun `the short name is the collection and the folder narrows it inside one family`() {
        fun console(shortName: String, dirName: String) =
            (RcConsoles.resolve(shortName, dirName) as? RcConsoles.Hashable)?.console

        val genesis = RcConsoles.row("genesis")
        assertTrue(RcConsoles.resolve("megadrive", "megadrive") === genesis)
        assertTrue(RcConsoles.resolve("megadrive", "genesis") === genesis)
        assertEquals(10, console("megadrive", "sega32x"))
        assertEquals(9, console("megadrive", "segacd"))
        assertEquals(15, console("mastersystem", "gamegear"))
        assertEquals(44, console("adam", "adam"))

        // A folder outside the family is only a folder's name.
        assertEquals(1, console("megadrive", "snes"))
        assertEquals(1, console("megadrive", "Some Game (Disc 1)"))
        val ngpc = RcConsoles.resolve("ngpc", "neogeo") as RcConsoles.Hashable
        assertEquals(14, ngpc.console)
        assertTrue(!ngpc.arcade, "a folder called neogeo that declares ngpc is not an arcade collection")
        // And it never turns a collection that is refused into one that is hashed, or the reverse.
        assertTrue(RcConsoles.resolve("vita", "psvita") is RcConsoles.NotOnRa)
        assertTrue(RcConsoles.resolve("switch", "nes") is RcConsoles.NotOnRa)
        assertTrue(RcConsoles.resolve("n3ds", "nds") is RcConsoles.NoAlgorithm)
        assertEquals(7, console("nes", "switch"))
        assertEquals(1, console("megadrive", "amiga"))

        // A short name nobody knows leaves it to the folder.
        assertEquals(15, console("whatever", "gamegear"))
        assertTrue(RcConsoles.resolve("whatever", "switch") is RcConsoles.NotOnRa)
        assertNull(RcConsoles.resolve("x", "y"))
        assertNull(RcConsoles.resolve(null, null))
        assertEquals(7, console("nes", ""))
    }

    // ── describe ────────────────────────────────────────────────────────────

    @Test fun `a row is described on one line that changes when the row does`() {
        assertEquals("unknown", RcConsoles.describe(null))
        assertEquals("n64=2 family[2]", RcConsoles.describe(RcConsoles.row("n64")))
        assertEquals("genesis=1 family[1,9,10,11,15,33] ext[32x:10,chd:9,cue:9,iso:9,sms:11] " +
                     "size[bin+img>33554432:9]", RcConsoles.describe(RcConsoles.row("megadrive")))
        assertEquals("wii=19 family[16,19] ext[gcm:16] alt[iso:16]", RcConsoles.describe(RcConsoles.row("wii")))
        assertEquals("neogeo=27 family[27] arcade", RcConsoles.describe(RcConsoles.row("neogeo")))
        assertEquals("3ds=62 no algorithm", RcConsoles.describe(RcConsoles.row("n3ds")))
        assertEquals("psvita not on RetroAchievements", RcConsoles.describe(RcConsoles.row("vita")))

        val lines = RcConsoles.ROWS.map { RcConsoles.describe(it) }
        assertEquals(lines.size, lines.toSet().size, "two rows with one description")
        assertTrue(lines.none { '\n' in it })

        // The same row written down in another order is the same line.
        val a = RcConsoles.Hashable("x", emptyList(), 1, linkedSetOf(9, 1),
            overridesByExtension = linkedMapOf("iso" to 9, "cue" to 9),
            overridesBySize = listOf(RcConsoles.BySize(linkedSetOf("img", "bin"), 5, 9)),
            alternates = linkedMapOf("z" to listOf(9), "a" to listOf(1, 9)))
        val b = RcConsoles.Hashable("x", listOf("spelt", "otherwise"), 1, linkedSetOf(1, 9),
            overridesByExtension = linkedMapOf("cue" to 9, "iso" to 9),
            overridesBySize = listOf(RcConsoles.BySize(linkedSetOf("bin", "img"), 5, 9)),
            alternates = linkedMapOf("a" to listOf(1, 9), "z" to listOf(9)))
        assertEquals("x=1 family[1,9] ext[cue:9,iso:9] size[bin+img>5:9] alt[a:1+9,z:9]", RcConsoles.describe(a))
        assertEquals(RcConsoles.describe(a), RcConsoles.describe(b))
        // The order alternates are tried in is part of the row.
        assertNotEquals(RcConsoles.describe(a),
                        RcConsoles.describe(a.copy(alternates = mapOf("z" to listOf(9), "a" to listOf(9, 1)))))
        // So is the order of the size rules: the first that fits a file decides, and
        // a .bin of 8 bytes is console 9 under these two and console 1 under them turned round.
        val sizes = listOf(RcConsoles.BySize(setOf("bin"), 5, 9), RcConsoles.BySize(setOf("bin"), 7, 1))
        assertEquals("x=1 family[1,9] size[bin>5:9,bin>7:1]",
                     RcConsoles.describe(RcConsoles.Hashable("x", emptyList(), 1, setOf(1, 9), overridesBySize = sizes)))
        assertEquals("x=1 family[1,9] size[bin>7:1,bin>5:9]",
                     RcConsoles.describe(RcConsoles.Hashable("x", emptyList(), 1, setOf(1, 9), overridesBySize = sizes.reversed())))
        // And so is everything a verdict depends on; the words of a reason are not.
        val amiga = RcConsoles.row("amiga") as RcConsoles.NoAlgorithm
        assertEquals(RcConsoles.describe(amiga), RcConsoles.describe(amiga.copy(reason = "said otherwise")))
        assertNotEquals(RcConsoles.describe(amiga), RcConsoles.describe(amiga.copy(id = 36)))
        assertEquals("rcheevos has no hashing algorithm for RC_CONSOLE_AMIGA (id 35)", amiga.reason)
        assertEquals("RetroAchievements has no console for switch", (RcConsoles.row("switch") as RcConsoles.NotOnRa).reason)
        assertTrue("decryption keys" in (RcConsoles.row("3ds") as RcConsoles.NoAlgorithm).reason)
    }
}
