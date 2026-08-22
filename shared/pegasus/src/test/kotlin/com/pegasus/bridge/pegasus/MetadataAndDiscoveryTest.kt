package com.pegasus.bridge.pegasus

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
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
 * Reading a collection's own metadata, and proposing a launch command for it.
 *
 * The fixture below is copied verbatim from the library this was written
 * against, including the `am start` launch line — which is an Android command
 * sitting in a Linux desktop's library, so every collection there is
 * unlaunchable and nothing says so.
 */
class MetadataAndDiscoveryTest {

    private lateinit var library: File

    @BeforeTest fun setUp() {
        BridgeLog.current = NoopLog
        library = Files.createTempDirectory("meta-lib").toFile()
    }

    @AfterTest fun tearDown() {
        library.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    /** Verbatim from `roms/nes/metadata.pegasus.txt`, `jud` extension and all. */
    private val REAL_NES = """
        collection: Nintendo Entertainment System
        shortname: nes
        extensions: bin, fds, nes, nsf, qd, rom, unf, unif, jud
        launch: am start
          -n com.explusalpha.NesEmu/com.imagine.BaseActivity
          -a android.intent.action.VIEW
          -d "{file.uri}"
          --activity-clear-task
          --activity-clear-top
          --activity-no-history
    """.trimIndent()

    private fun collection(name: String, text: String): File {
        val d = File(library, name).apply { mkdirs() }
        File(d, "metadata.pegasus.txt").writeText(text)
        return d
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    @Test fun `a real collection header is read whole`() {
        val c = MetadataFile.readCollection(collection("nes", REAL_NES))!!
        assertEquals("Nintendo Entertainment System", c.name)
        assertEquals("nes", c.shortName)
        assertTrue(c.launch.startsWith("am start"))
    }

    // The finding this parser exists for: the collection declares an extension
    // the hardcoded scanner list has never heard of. On a library using it for
    // real dumps, that platform scans as zero files and says nothing.
    @Test fun `the collection's declared extensions include ones the scanner does not know`() {
        val c = MetadataFile.readCollection(collection("nes", REAL_NES))!!
        assertEquals(listOf("bin", "fds", "nes", "nsf", "qd", "rom", "unf", "unif", "jud"),
                     c.extensions)
        assertTrue("jud" in c.extensions,
                   "the extension the scanner's own list omits must survive parsing")
    }

    // A launch command spans lines, and each continuation is indented. Folding
    // them into one string would produce something unrunnable.
    @Test fun `a multiline launch command keeps its lines`() {
        val c = MetadataFile.readCollection(collection("nes", REAL_NES))!!
        val lines = c.launch.lines()
        assertEquals(7, lines.size, "got: ${c.launch}")
        assertTrue(lines[1].contains("com.explusalpha.NesEmu"))
        assertTrue(lines.last().contains("--activity-no-history"))
    }

    @Test fun `parsing stops where the games begin`() {
        val c = MetadataFile.readCollection(collection("nes", """
            collection: NES
            shortname: nes
            extensions: nes

            game: Contra
            file: Contra (USA).nes
            developer: Konami
        """.trimIndent()))!!
        assertEquals("NES", c.name)
        assertFalse(c.raw.containsKey("developer"),
                    "a game's fields must not be read as the collection's")
    }

    @Test fun `comments and blank lines are ignored`() {
        val c = MetadataFile.readCollection(collection("nes", """
            # written by hand
            collection: NES

            # the platform
            shortname: nes
        """.trimIndent()))!!
        assertEquals("NES", c.name)
        assertEquals("nes", c.shortName)
    }

    @Test fun `a directory with no metadata file has no collection`() {
        assertNull(MetadataFile.readCollection(File(library, "empty").apply { mkdirs() }))
    }

    @Test fun `collections are found one level under each root`() {
        collection("nes", REAL_NES)
        collection("snes", "collection: SNES\nshortname: snes\nextensions: sfc")
        File(library, "nes/media/box2dfront").mkdirs()

        val found = MetadataFile.collectionsUnder(listOf(library))
        assertEquals(setOf("Nintendo Entertainment System", "SNES"), found.map { it.name }.toSet())
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    @Test fun `an overlay carries the fields it was not asked to change`() {
        val original = MetadataFile.readCollection(collection("nes", REAL_NES))!!
        val text = MetadataFile.renderCollection(
            name = original.name, shortName = original.shortName,
            launch = "/usr/bin/retroarch -L core.so \"{file.path}\"",
            preserve = original.raw)

        val reparsed = MetadataFile.readHeaderFields(text)!!
        assertEquals("Nintendo Entertainment System", reparsed["collection"])
        assertEquals("nes", reparsed["shortname"])
        // Dropping this would change which files the collection contains, and the
        // overlay is only supposed to change how they launch.
        assertTrue(reparsed["extensions"]!!.contains("jud"))
        assertTrue(reparsed["launch"]!!.startsWith("/usr/bin/retroarch"))
        assertFalse(reparsed["launch"]!!.contains("am start"),
                    "the old Android command must not survive into the overlay")
    }

    @Test fun `a rendered overlay round-trips through the parser`() {
        val text = MetadataFile.renderCollection(
            name = "PlayStation", shortName = "psx",
            launch = "duckstation-qt -batch \"{file.path}\"",
            preserve = mapOf("extensions" to "cue, bin, chd"))
        val f = File(library, "psx").apply { mkdirs() }
        File(f, "metadata.pegasus.txt").writeText(text)

        val c = MetadataFile.readCollection(f)!!
        assertEquals("PlayStation", c.name)
        assertEquals("psx", c.shortName)
        assertEquals(listOf("cue", "bin", "chd"), c.extensions)
        assertEquals("duckstation-qt -batch \"{file.path}\"", c.launch)
    }

    @Test fun `the overlay says it is safe to delete`() {
        val text = MetadataFile.renderCollection("NES", "nes", "x")
        assertTrue(text.lineSequence().first().startsWith("#"))
        assertTrue(text.contains("Safe to delete"))
    }

    // ── Discovery ───────────────────────────────────────────────────────────

    /** A fake PATH, so this passes on a machine with no emulator installed. */
    private fun fakePath(vararg binaries: String): List<File> {
        val bin = File(library, "bin").apply { mkdirs() }
        for (b in binaries) File(bin, b).apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        return listOf(bin)
    }

    @Test fun `a binary that identifies itself is verified`() {
        val found = EmulatorDiscovery.discover(
            pathDirs = fakePath("retroarch"),
            flatpakList = { emptyList() },
            runner = { cmd -> if (cmd.first().endsWith("retroarch")) "RetroArch 1.19.1" else null })

        val ra = found.single { it.id == "retroarch" }
        assertTrue(ra.verified)
        assertEquals("RetroArch 1.19.1", ra.version)
        assertEquals(EmulatorDiscovery.Kind.NATIVE, ra.kind)
    }

    // A file on PATH called `mame` that is not MAME is a different program with
    // the same name, and offering it would be worse than finding nothing.
    @Test fun `a binary whose version says something else is not verified`() {
        val found = EmulatorDiscovery.discover(
            pathDirs = fakePath("mame"),
            flatpakList = { emptyList() },
            runner = { "usage: mame [options] — some unrelated tool" })

        assertFalse(found.single { it.id == "mame" }.verified,
                    "an unrelated program answered and was believed")
    }

    @Test fun `a binary that will not answer is offered unverified rather than dropped`() {
        val found = EmulatorDiscovery.discover(
            pathDirs = fakePath("pcsx2-qt"), flatpakList = { emptyList() }, runner = { null })
        val p = found.single { it.id == "pcsx2" }
        assertFalse(p.verified)
        assertTrue(p.launchCommand.contains("pcsx2-qt"))
    }

    private fun flatpak(id: String, name: String, version: String) =
        EmulatorDiscovery.InstalledFlatpak(id, name, version)

    @Test fun `an installed flatpak is found when nothing is on path`() {
        val found = EmulatorDiscovery.discover(
            pathDirs = fakePath(),
            flatpakList = { listOf(flatpak("org.DolphinEmu.dolphin-emu", "Dolphin", "2606"),
                                   flatpak("org.videolan.VLC", "VLC", "3.0")) },
            runner = { null })

        val d = found.single { it.id == "dolphin" }
        assertEquals(EmulatorDiscovery.Kind.FLATPAK, d.kind)
        assertTrue(d.launchCommand.startsWith("flatpak run org.DolphinEmu.dolphin-emu"))
    }

    // Discovering what is installed must never start anything. Measured on a real
    // machine: `flatpak run com.snes9x.Snes9x --version` ignores the flag and
    // launches the emulator — joystick, audio device and all — while PCSX2 prints
    // nothing at all. `flatpak list` states the version without executing a byte.
    @Test fun `a flatpak is verified from metadata and never by running it`() {
        var ran = false
        val found = EmulatorDiscovery.discover(
            pathDirs = fakePath(),
            flatpakList = { listOf(flatpak("net.pcsx2.PCSX2", "PCSX2", "v2.6.3"),
                                   flatpak("com.snes9x.Snes9x", "Snes9x", "1.63")) },
            runner = { ran = true; null })

        val pcsx2 = found.single { it.id == "pcsx2" }
        assertTrue(pcsx2.verified, "a version from the metadata is a verification")
        assertEquals("v2.6.3", pcsx2.version)
        assertTrue(pcsx2.confidence.contains("PCSX2"), pcsx2.confidence)
        assertFalse(ran, "discovery executed something to identify a Flatpak")
    }

    // A Flatpak whose metadata carries no version is still installed, and still
    // worth offering — just not as something that has identified itself.
    @Test fun `a flatpak with no version is offered unverified`() {
        val found = EmulatorDiscovery.discover(
            pathDirs = fakePath(),
            flatpakList = { listOf(flatpak("io.mgba.mGBA", "mGBA", "")) },
            runner = { null })
        assertFalse(found.single { it.id == "mgba" }.verified)
    }

    @Test fun `nothing installed means nothing proposed`() {
        assertTrue(EmulatorDiscovery.discover(fakePath(), { emptyList() }, { null }).isEmpty())
    }

    // RetroArch covers every platform, so it is never the most specific answer —
    // and its command needs a core that discovery has no way to choose.
    @Test fun `a dedicated emulator beats retroarch for its own platform`() {
        val found = EmulatorDiscovery.discover(
            pathDirs = fakePath("retroarch", "duckstation-qt"),
            flatpakList = { emptyList() },
            runner = { cmd ->
                when {
                    cmd.first().endsWith("retroarch") -> "RetroArch 1.19.1"
                    cmd.first().endsWith("duckstation-qt") -> "DuckStation 0.1"
                    else -> null
                }
            })

        assertEquals("duckstation", EmulatorDiscovery.bestFor("psx", found)?.id)
        // Nothing else handles the NES here, so RetroArch is the right answer there.
        assertEquals("retroarch", EmulatorDiscovery.bestFor("nes", found)?.id)
    }

    @Test fun `a platform nothing handles has no proposal`() {
        val found = EmulatorDiscovery.discover(
            fakePath("duckstation-qt"), { emptyList() }, { "DuckStation 0.1" })
        assertNull(EmulatorDiscovery.bestFor("switch", found))
    }

    // The platform spellings a real library uses have to reach the right emulator.
    @Test fun `the library's own platform names resolve`() {
        val found = EmulatorDiscovery.discover(
            fakePath("dolphin-emu", "pcsx2-qt"), { emptyList() },
            { cmd -> if (cmd.first().contains("dolphin")) "Dolphin 5.0" else "PCSX2 1.7" })

        assertEquals("dolphin", EmulatorDiscovery.bestFor("gc", found)?.id)
        assertEquals("dolphin", EmulatorDiscovery.bestFor("wii", found)?.id)
        assertEquals("pcsx2", EmulatorDiscovery.bestFor("ps2", found)?.id)
    }

    // ── Standing aside from the user's own launch command ──────────────────
    //
    // Pegasus resolves two files declaring one collection by keeping whichever it
    // parses *last*, and it does not sort them — `find_metafiles_in` uses a bare
    // QDirIterator. So an overlay beside a file that also sets `launch` takes
    // effect only sometimes, which is worse than not writing it.

    @Test fun `a file that sets its own launch command is detected`() {
        val nes = collection("nes", REAL_NES)
        assertTrue(MetadataFile.declaresLaunch(File(nes, "metadata.pegasus.txt")))

        val quiet = collection("snes", "collection: SNES\nshortname: snes\nextensions: sfc")
        assertFalse(MetadataFile.declaresLaunch(File(quiet, "metadata.pegasus.txt")))
    }

    @Test fun `commenting out the launch block leaves everything else alone`() {
        val nes = collection("nes", REAL_NES)
        val f = File(nes, "metadata.pegasus.txt")

        assertTrue(MetadataFile.commentOutLaunch(f))

        val after = MetadataFile.readCollection(nes)!!
        assertEquals("Nintendo Entertainment System", after.name)
        assertEquals("nes", after.shortName)
        assertTrue("jud" in after.extensions, "the extension line must survive")
        assertEquals("", after.launch, "the launch command must no longer be declared")
        // Every original line is still readable, as a comment.
        assertTrue(f.readText().contains("# launch: am start"))
        assertTrue(f.readText().contains("--activity-no-history"))
    }

    @Test fun `standing aside is exactly reversible`() {
        val nes = collection("nes", REAL_NES)
        val f = File(nes, "metadata.pegasus.txt")
        val original = f.readText()

        MetadataFile.commentOutLaunch(f)
        assertTrue(File(f.path + MetadataFile.BACKUP_SUFFIX).isFile, "no backup was written")
        assertTrue(MetadataFile.restoreBackup(f))

        assertEquals(original, f.readText(), "the restored file is not what was there before")
        assertFalse(File(f.path + MetadataFile.BACKUP_SUFFIX).exists(),
                    "the backup should be consumed by the restore")
    }

    @Test fun `commenting out a file with no launch command changes nothing`() {
        val snes = collection("snes", "collection: SNES\nshortname: snes")
        val f = File(snes, "metadata.pegasus.txt")
        val before = f.readText()
        assertFalse(MetadataFile.commentOutLaunch(f))
        assertEquals(before, f.readText())
        assertFalse(File(f.path + MetadataFile.BACKUP_SUFFIX).exists())
    }

    // Exactly one file declaring `launch` is the only state with a defined result.
    @Test fun `after standing aside only the overlay declares a launch`() {
        val nes = collection("nes", REAL_NES)
        MetadataFile.commentOutLaunch(File(nes, "metadata.pegasus.txt"))
        File(nes, "zz-pegasusbridge.metadata.pegasus.txt").writeText(
            MetadataFile.renderCollection("Nintendo Entertainment System", "nes",
                "/usr/bin/retroarch \"{file.path}\""))

        val declaring = nes.listFiles { f -> f.name.endsWith(".txt") }!!
            .filter { MetadataFile.declaresLaunch(it) }
        assertEquals(1, declaring.size, "declaring launch: ${declaring.map { it.name }}")
        assertEquals("zz-pegasusbridge.metadata.pegasus.txt", declaring.single().name)
    }

    // An overlay is a second source for the same collection, and reading only
    // the collection's own file reported "no launch command" for a collection
    // that had just been given a working one.
    @Test fun `the effective launch comes from whichever file declares one`() {
        val nes = collection("nes", REAL_NES)
        MetadataFile.commentOutLaunch(File(nes, "metadata.pegasus.txt"))
        File(nes, "zz-pegasusbridge.metadata.pegasus.txt").writeText(
            MetadataFile.renderCollection("Nintendo Entertainment System", "nes",
                "flatpak run io.mgba.mGBA \"{file.path}\""))

        val c = MetadataFile.readCollection(nes)!!
        assertEquals("Nintendo Entertainment System", c.name)
        assertEquals("flatpak run io.mgba.mGBA \"{file.path}\"", c.launch)
        assertEquals("zz-pegasusbridge.metadata.pegasus.txt", c.launchFile?.name)
        assertFalse(c.ambiguousLaunch)
        // And the collection's identity still comes from the user's own file.
        assertEquals("metadata.pegasus.txt", c.file.name)
    }

    @Test fun `two files declaring a launch are reported as ambiguous`() {
        val nes = collection("nes", REAL_NES)
        File(nes, "zz-pegasusbridge.metadata.pegasus.txt").writeText(
            MetadataFile.renderCollection("Nintendo Entertainment System", "nes", "somethingelse"))

        val c = MetadataFile.readCollection(nes)!!
        assertTrue(c.ambiguousLaunch,
                   "Pegasus picks by filesystem order here, and that must be surfaced")
    }

    // The proposal must never be applied on the strength of discovery alone.
    @Test fun `discovery writes nothing`() {
        val nes = collection("nes", REAL_NES)
        EmulatorDiscovery.discover(fakePath("retroarch"), { emptyList() }, { "RetroArch 1.19.1" })
        assertEquals("metadata.pegasus.txt", nes.listFiles()!!.single().name)
        assertTrue(File(nes, "metadata.pegasus.txt").readText().contains("am start"),
                   "discovery must not have touched the user's file")
    }
}
