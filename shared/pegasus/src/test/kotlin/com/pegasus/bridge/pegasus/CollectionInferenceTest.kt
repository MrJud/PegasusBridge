package com.pegasus.bridge.pegasus

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
 * Inferring a collection from a directory of ROMs.
 *
 * The fixtures are the tablet's, verbatim where it matters: the `systeminfo.txt`
 * is ScreenScraper's `psx` file as ES-DE ships it, and the three directories
 * exercised — `gba`, `n64`, `n3ds` — are the three that hold real ROMs and no
 * usable declaration.
 */
class CollectionInferenceTest {

    private lateinit var root: File

    @BeforeTest fun setUp() { root = Files.createTempDirectory("infer").toFile() }
    @AfterTest fun tearDown() { root.deleteRecursively() }

    private fun dir(name: String, vararg files: String): File =
        File(root, name).apply {
            mkdirs()
            files.forEach { File(this, it).writeText("x".repeat(64)) }
        }

    /** A stand-in for ScreenScraper's table, with the entries the tests need. */
    private val table = CollectionInference.SystemLookup { key ->
        when (key.lowercase().replace(Regex("[^a-z0-9]"), "")) {
            "gba", "gameboyadvance" ->
                CollectionInference.SystemFacts("Game Boy Advance", listOf("gba", "bin"))
            "n64", "nintendo64" ->
                CollectionInference.SystemFacts("Nintendo 64", listOf("n64", "v64", "z64"))
            "3ds", "nintendo3ds" ->
                CollectionInference.SystemFacts("Nintendo 3DS", listOf("3ds"))
            else -> null
        }
    }

    // ── ES-DE's file, when there is one ─────────────────────────────────────

    /** Verbatim from the tablet, including the sections this does not read. */
    private val psxSystemInfo = """
        System name:
        psx

        Full system name:
        Sony PlayStation

        Supported file extensions:
        .bin .BIN .cue .CUE .chd .CHD .iso .ISO .m3u .M3U .7z .7Z .zip .ZIP

        Launch command:
        %EMULATOR_RETROARCH% %EXTRA_LIBRETRO%=mednafen_psx_libretro_android.so %EXTRA_ROM%=%ROM%

        Alternative launch commands:
        %EMULATOR_DUCKSTATION% %ACTIVITY_CLEAR_TASK% %EXTRA_bootPath%=%ROMSAF%
        %EMULATOR_EPSXE% %ACTION%=android.intent.action.MAIN %EXTRA_isoName%=%ROM%

        Platform (for scraping):
        psx

        Theme folder:
        psx
    """.trimIndent()

    @Test fun `an es-de systeminfo gives the name, short name and extensions`() {
        val i = EsDeSystemInfo.parse(psxSystemInfo)!!
        assertEquals("psx", i.systemName)
        assertEquals("Sony PlayStation", i.fullName)
        // Lower-cased, dots removed, the upper-case duplicates folded away.
        assertEquals(listOf("bin", "cue", "chd", "iso", "m3u", "7z", "zip"), i.extensions)
    }

    /**
     * The multi-line `Alternative launch commands:` section must not bleed into
     * the field before it, which is why the parser reads paragraphs rather than
     * matching three labels.
     */
    @Test fun `the launch sections are not mistaken for extensions`() {
        val i = EsDeSystemInfo.parse(psxSystemInfo)!!
        assertTrue(i.extensions.none { it.contains("emulator") || it.contains("rom") },
                   i.extensions.toString())
    }

    @Test fun `a file with nothing in it is null rather than an empty shell`() {
        assertNull(EsDeSystemInfo.parse("Launch command:\n%EMULATOR_X% %ROM%\n"))
    }

    // ── the three real cases ────────────────────────────────────────────────

    /** No metafile, no systeminfo: the system table has to carry it. */
    @Test fun `n64 is inferred from the system table alone`() {
        val d = dir("n64", "Super Mario 64.z64", "GoldenEye 007.z64")
        val p = CollectionInference.proposalFor(d, table)!!
        assertEquals("Nintendo 64", p.name.value)
        assertEquals(CollectionInference.Source.SYSTEM_TABLE, p.name.source)
        assertEquals(listOf("n64", "v64", "z64"), p.extensions.value)
        assertEquals(2, p.matchedFiles)
        assertTrue(p.confident, p.review.toString())
        assertTrue(p.because.contains("no Pegasus metadata file"), p.because)
    }

    /**
     * ES-DE calls the folder `n3ds`; ScreenScraper has never heard of it and
     * knows `3ds`. Handled by the inference's own folder aliases rather than by
     * `FuzzyMatch`, whose output is part of the RetroAchievements cache key and
     * is mirrored in the theme.
     */
    @Test fun `n3ds resolves even though no system table knows that name`() {
        val d = dir("n3ds", "Bravely Default.3ds")
        val p = CollectionInference.proposalFor(d, table)!!
        assertEquals("Nintendo 3DS", p.name.value)
        assertEquals(1, p.matchedFiles)
        assertTrue(p.confident, p.review.toString())
    }

    /**
     * `gba` on the tablet is the case a "find directories with no metafile"
     * scan would miss: it has a Logiqx datfile naming `.jud` placeholders from
     * another machine, so Pegasus reports an empty collection rather than none.
     */
    @Test fun `a stale logiqx datfile is reported, not walked past`() {
        val d = dir("gba", "Advance Wars.gba", "Golden Sun.gba", "Game Boy Advance.dat")
        val p = CollectionInference.proposalFor(d, table)!!
        assertEquals("Game Boy Advance", p.name.value)
        // The .dat is not counted as a game …
        assertEquals(2, p.candidateFiles)
        assertEquals(2, p.matchedFiles)
        // … and it is called out, because it will keep Pegasus reporting empty.
        assertTrue(p.review.any { it.contains("Logiqx") }, p.review.toString())
    }

    /**
     * The tablet's `n3ds` holds `Bravely Default.cci`, and ScreenScraper's list
     * for the 3DS is `3ds` alone — `.cci` is a real 3DS format their table
     * omits. Writing `extensions: 3ds` would have been confident and would have
     * hidden the only game in the folder.
     */
    @Test fun `a declared list matching nothing gains the files' own extensions`() {
        val d = dir("n3ds", "Bravely Default.cci")
        val p = CollectionInference.proposalFor(d, table)!!
        assertTrue(p.extensions.value.containsAll(listOf("3ds", "cci")), p.extensions.value.toString())
        assertEquals(1, p.matchedFiles)
        assertTrue(p.review.any { it.contains("were added") }, p.review.toString())
    }

    /**
     * And only when it matches nothing. A `.cue` collection with its `.bin`
     * tracks beside it matches some, and unioning there would declare every
     * track a separate game.
     */
    @Test fun `a list matching some files is left alone`() {
        val d = dir("psx", "Game.cue", "Game (Track 1).bin", "Game (Track 2).bin")
        val cue = CollectionInference.SystemLookup {
            if (it.lowercase() == "psx") CollectionInference.SystemFacts("PlayStation", listOf("cue"))
            else null
        }
        val p = CollectionInference.proposalFor(d, cue)!!
        assertEquals(listOf("cue"), p.extensions.value)
        assertEquals(1, p.matchedFiles)
        assertTrue(p.review.any { it.contains("would be left out") }, p.review.toString())
    }

    // ── the honest failures ─────────────────────────────────────────────────

    @Test fun `an unknown platform falls back to the folder name and says so`() {
        val d = dir("weirdbox", "Some Game.wbx")
        val p = CollectionInference.proposalFor(d, table)!!
        assertEquals("Weirdbox", p.name.value)
        assertEquals(CollectionInference.Source.DIRECTORY_NAME, p.name.source)
        assertEquals(listOf("wbx"), p.extensions.value)
        assertEquals(CollectionInference.Source.OBSERVED, p.extensions.source)
        assertTrue(!p.confident)
        assertTrue(p.review.any { it.contains("no system table entry") }, p.review.toString())
    }

    /** Save files sitting beside cartridges must not become the extension list. */
    @Test fun `saves and states are not mistaken for games`() {
        val d = dir("n64", "Super Mario 64.z64", "Super Mario 64.srm", "Super Mario 64.st0",
                    "boxart.png", "notes.txt")
        val p = CollectionInference.proposalFor(d, table)!!
        assertEquals(1, p.candidateFiles)
        assertEquals(1, p.matchedFiles)
        assertTrue(p.confident, p.review.toString())
    }

    /**
     * `.md` is Markdown and it is also every Mega Drive cartridge. Blocking it
     * dropped all fifteen in this library and left the collection holding
     * nothing but the backup file that repairing it had just created.
     */
    @Test fun `md is a mega drive cartridge, not just markdown`() {
        val md = CollectionInference.SystemLookup {
            if (it.lowercase() == "megadrive")
                CollectionInference.SystemFacts("Megadrive", listOf("md", "gen", "bin")) else null
        }
        val d = dir("megadrive", "Altered Beast.md", "Comix Zone.md", "README.txt")
        val p = CollectionInference.proposalFor(d, md)!!
        assertEquals(2, p.candidateFiles)
        assertEquals(2, p.matchedFiles)
    }

    /** The file `standAside` leaves behind is not a game either. */
    @Test fun `a stood-aside backup is not counted as a rom`() {
        val md = CollectionInference.SystemLookup {
            if (it.lowercase() == "megadrive")
                CollectionInference.SystemFacts("Megadrive", listOf("md")) else null
        }
        val d = dir("megadrive", "Altered Beast.md")
        File(d, "metadata.pegasus.txt").writeText("collection: Megadrive\nextensions: md\n")
        File(d, "metadata.pegasus.txt" + MetadataFile.BACKUP_SUFFIX).writeText("old")
        // Everything matches, so there is nothing to propose at all.
        assertNull(CollectionInference.proposalFor(d, md))
    }

    /** A collection that already picks its games up needs nothing. */
    @Test fun `a working collection produces no proposal`() {
        val d = dir("n64", "Super Mario 64.z64")
        File(d, "metadata.pegasus.txt").writeText(
            "collection: Nintendo 64\nshortname: n64\nextensions: z64, n64\n")
        assertNull(CollectionInference.proposalFor(d, table))
    }

    /** A declaration whose extensions match nothing is the other broken case. */
    @Test fun `a collection whose extensions match nothing is proposed again`() {
        val d = dir("n64", "Super Mario 64.z64")
        File(d, "metadata.pegasus.txt").writeText(
            "collection: Nintendo 64\nshortname: n64\nextensions: jud\n")
        val p = CollectionInference.proposalFor(d, table)!!
        assertTrue(p.because.contains("no file here has one"), p.because)
    }

    @Test fun `a directory with no game files at all is skipped`() {
        assertNull(CollectionInference.proposalFor(dir("empty"), table))
        assertNull(CollectionInference.proposalFor(dir("art", "a.png", "b.jpg"), table))
    }

    /** The sweep skips media directories and orders by what is biggest. */
    @Test fun `the sweep reports the largest first and ignores media folders`() {
        dir("n64", "a.z64", "b.z64")
        dir("n3ds", "c.3ds")
        dir("media", "d.png")
        File(root, "n64/media").apply { mkdirs(); File(this, "x.png").writeText("x") }
        val found = CollectionInference.undeclaredUnder(listOf(root), table)
        assertEquals(listOf("n64", "n3ds"), found.map { it.directory.name })
    }
}

/**
 * Reading the core, and only the core, out of ES-DE's launch commands.
 *
 * Both fixtures are verbatim from the tablet: `psx` writes the core as a bare
 * filename, `snes` as an absolute path with `%ANDROIDPACKAGE%` in it. A pattern
 * that only matched the first missed all nine of the second's.
 */
class EsDeLaunchTest {

    private val psx = """
        System name:
        psx

        Launch command:
        %EMULATOR_RETROARCH% %EXTRA_LIBRETRO%=mednafen_psx_libretro_android.so %EXTRA_ROM%=%ROM%

        Alternative launch commands:
        %EMULATOR_RETROARCH% %EXTRA_LIBRETRO%=swanstation_libretro_android.so %EXTRA_ROM%=%ROM%
        %EMULATOR_DUCKSTATION% %ACTIVITY_CLEAR_TASK% %EXTRA_bootPath%=%ROMSAF%
        %EMULATOR_EPSXE% %ACTION%=android.intent.action.MAIN %EXTRA_isoName%=%ROM%
    """.trimIndent()

    private val snes = """
        System name:
        snes

        Launch command:
        %EMULATOR_RETROARCH% %EXTRA_LIBRETRO%=/data/data/%ANDROIDPACKAGE%/cores/snes9x_libretro_android.so %EXTRA_ROM%=%ROM%

        Alternative launch commands:
        %EMULATOR_SNES9X-EXPLUS% %DATA%=%ROMSAF%
        %EMULATOR_RETROARCH% %EXTRA_LIBRETRO%=/data/data/%ANDROIDPACKAGE%/cores/bsnes_libretro_android.so %EXTRA_ROM%=%ROM%
    """.trimIndent()

    @Test fun `a bare core filename is read`() {
        val o = EsDeSystemInfo.parse(psx)!!.launchOptions
        assertEquals("RETROARCH", o[0].emulator)
        assertEquals("mednafen_psx_libretro_android.so", o[0].core)
        assertTrue(o[0].primary)
    }

    @Test fun `an absolute core path is read too`() {
        val o = EsDeSystemInfo.parse(snes)!!.launchOptions
        assertEquals("/data/data/%ANDROIDPACKAGE%/cores/snes9x_libretro_android.so", o[0].core)
    }

    /** An emulator with no core — DuckStation is not libretro — carries none. */
    @Test fun `a launch with no libretro core carries an empty one`() {
        val duck = EsDeSystemInfo.parse(psx)!!.launchOptions.first { it.emulator == "DUCKSTATION" }
        assertEquals("", duck.core)
        assertFalse(duck.primary)
    }

    /** Order is ES-DE's: the primary first, then the alternatives as listed. */
    @Test fun `the emulators come back in the order es-de prefers them`() {
        assertEquals(listOf("retroarch", "snes9xplus"),
                     EsDeSystemInfo.parse(snes)!!.launchOptions
                         .mapNotNull { EsDeSystemInfo.idForMacro(it.emulator) }.distinct())
    }

    /** An unmapped macro resolves to nothing rather than to a guess. */
    @Test fun `an emulator this project does not know is not invented`() {
        assertEquals(null, EsDeSystemInfo.idForMacro("EPSXE"))
        assertEquals("retroarch", EsDeSystemInfo.idForMacro("RetroArch"))
    }

    @Test fun `the android package placeholder is resolved against what is installed`() {
        val dir = Files.createTempDirectory("esde").toFile()
        File(dir, EsDeSystemInfo.FILE_NAME).writeText(snes)
        assertEquals("/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so",
                     EsDeSystemInfo.coreFor(dir, "retroarch", "com.retroarch.aarch64"))
        // A different emulator on the same platform has no core of its own.
        assertEquals("", EsDeSystemInfo.coreFor(dir, "snes9xplus", "com.explusalpha.Snes9xPlus"))
        dir.deleteRecursively()
    }
}
