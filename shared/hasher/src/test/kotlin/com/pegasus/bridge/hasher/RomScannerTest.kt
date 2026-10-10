package com.pegasus.bridge.hasher

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which files a scan even considers.
 *
 * Written after a measured failure on the Galaxy Tab S8+: `amiga`,
 * `amstradcpc` and `apple2` each held one real dump and each scanned as **zero
 * files**, so nothing was hashed and no lookup was made. Two causes, both here:
 * the Android shell was compiling its own older copy of this class, and the
 * built-in extension list had no home-computer formats in it.
 */
class RomScannerTest {

    private lateinit var root: File

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("romscan").toFile()
    }

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    private fun touch(relative: String): File {
        val f = File(root, relative)
        f.parentFile?.mkdirs()
        f.writeText("x")
        return f
    }

    private fun scan(extensionsFor: (File) -> Set<String> = { RomScanner.ROM_EXTENSIONS }) =
        RomScanner.scan(listOf(root.path), extensionsFor).map { it.name }.sorted()

    /** The three platforms that scanned as nothing on the tablet. */
    @Test fun `home computer dumps are recognised without being declared`() {
        touch("Superfrog_Disk3.adf")
        touch("Robocop.dsk")
        touch("Rainy Day Games.po")
        assertEquals(listOf("Rainy Day Games.po", "Robocop.dsk", "Superfrog_Disk3.adf"), scan())
    }

    /**
     * The collection is the authority, and the union is the point: a directory
     * that declares an extension nobody built in still gets scanned, and its
     * archives are not lost for having gone unmentioned.
     */
    @Test fun `a declared extension the built-in list never heard of is scanned`() {
        touch("weird.jud")
        touch("normal.zip")
        assertEquals(emptyList(), scan().filter { it.endsWith(".jud") })

        val declared = { _: File -> RomScanner.ROM_EXTENSIONS + setOf("jud") }
        assertEquals(listOf("normal.zip", "weird.jud"), scan(declared))
    }

    /** Artwork directories cost real time and hold no ROMs. */
    @Test fun `media directories are not walked`() {
        touch("game.nes")
        touch("media/game.png")
        touch("images/box.zip")
        assertEquals(listOf("game.nes"), scan())
    }

    /**
     * Unless the root itself is the one named `media` — a person whose game
     * directory is literally called that would otherwise lose the whole library.
     */
    @Test fun `a root named media is still scanned`() {
        val media = File(root, "media").apply { mkdirs() }
        File(media, "game.nes").writeText("x")
        assertEquals(listOf("game.nes"),
                     RomScanner.scan(listOf(media.path)).map { it.name })
    }

    /** A resolver that throws must not take the scan down with it. */
    @Test fun `a failing resolver is not fatal`() {
        touch("game.nes")
        val results = RomScanner.scan(listOf(root.path)) { error("no metadata here") }
        // The scan still completes; whether this file survives depends on the
        // caller's fallback, so assert only that nothing was thrown.
        assertTrue(results.size <= 1)
    }

    // The same of the scan that asks about collections. A folder whose
    // metafiles cannot be listed is the folder it is called, as every folder
    // was before anything was asked, and an answer given in place of the
    // collection's that cannot be had is the built-in list.
    @Test fun `a folder that cannot be resolved is taken for its name and the scan goes on`() {
        touch("psx/metadata.pegasus.txt").writeText("collection: PlayStation\nshortname: psx\nextensions: jud\n")
        touch("psx/Declared.jud")
        touch("locked/game.nes")
        touch("locked/weird.jud")
        val resolver = CollectionResolver { dir ->
            if (dir.name == "locked") error("no listing here") else com.pegasus.bridge.core.PegasusMetafile.filesIn(dir)
        }

        val found = RomScanner.scanWithCollections(listOf(root.path), resolver)

        assertEquals(mapOf("Declared.jud" to "psx", "game.nes" to "locked"),
                     found.associate { it.file.name to it.collection.shortName })
        assertEquals(CollectionRef.Source.INFERRED, found.single { it.file.name == "game.nes" }.collection.source)

        val overridden = RomScanner.scanWithCollections(listOf(root.path), CollectionResolver()) { dir ->
            if (dir.name == "locked") error("no answer here") else setOf("jud")
        }
        assertEquals(listOf("Declared.jud", "game.nes"), overridden.map { it.file.name }.sorted())
    }

    // ── Markdown, or a Mega Drive cartridge ─────────────────────────────────

    // `.md` was in the built-in list, so every README.md under a library was
    // a game: the ones of a BIOS pack, of a folder of mods, of a tool kept
    // beside the discs it patches. It is a ROM where the collection says so,
    // by listing it or by being one that holds Mega Drive cartridges, and
    // the second is asked of the console table by both of the collection's
    // names, as a file's console is.
    @Test fun `md is a ROM only in a Mega Drive collection or where declared`() {
        fun collection(folder: String, name: String, shortName: String, extensions: String) =
            touch("$folder/metadata.pegasus.txt")
                .writeText("collection: $name\nshortname: $shortName\nextensions: $extensions\n")

        // Listed by the collection, and found in a game's folder under it too.
        collection("library/megadrive", "Mega Drive", "megadrive", "md, gen")
        touch("library/megadrive/Lantern Keep (USA).md")
        touch("library/megadrive/Other Game (USA)/Other Game (USA).md")
        // Not listed, in collections of the Mega Drive's family: a folder
        // named for an add-on whose collection calls itself megadrive, and a
        // folder megadrive whose collection goes by a name the table has
        // never heard of.
        collection("library/sega32x", "Sega 32X", "megadrive", "32x")
        touch("library/sega32x/Third Game (USA).md")
        collection("library/genesis", "Sixteen Bits", "sixteenbits", "gen")
        touch("library/genesis/Fourth Game (USA).md")
        // Listed, by a collection of no console anybody knows.
        collection("library/fanmade", "Fan-made", "fanmade", "md")
        touch("library/fanmade/Fifth Game.md")
        // No metafile at all: the folder is the collection its name says.
        touch("loose/megadrive/Sixth Game (USA).md")
        // The names ES-DE gives the Mega Drive's other regions are the Mega
        // Drive's: with no metafile, and with one that lists no extension.
        touch("loose/megadrivejp/Seventh Game (Japan).md")
        touch("library/genesiswide/metadata.pegasus.txt")
            .writeText("collection: Genesis Wide\nshortname: genesiswide\n")
        touch("library/genesiswide/Eighth Game (USA).md")

        // Everywhere else it is a readme, beside files that are found.
        collection("library/switch", "Nintendo Switch", "switch", "nsp, xci")
        touch("library/switch/Mods/README.md")
        touch("library/switch/Mods/mod.zip")
        collection("library/psx", "PlayStation", "psx", "cue, bin")
        touch("library/psx/Some Game/README.md")
        touch("library/psx/Some Game/Some Game.cue")
        // A collection of the 8-bit consoles has no Mega Drive in it, and a
        // folder named for an add-on is, by itself, of that add-on alone.
        collection("library/gamegear", "Sega 8-bit", "mastersystem", "gg, sms")
        touch("library/gamegear/README.md")
        touch("loose/segacd/README.md")
        // With no metafile above it a folder under megadrive is not megadrive.
        touch("loose/megadrive/Docs/README.md")
        touch("loose/tools/README.md")

        val expected = listOf(
            "library/fanmade/Fifth Game.md",
            "library/genesis/Fourth Game (USA).md",
            "library/genesiswide/Eighth Game (USA).md",
            "library/megadrive/Lantern Keep (USA).md",
            "library/megadrive/Other Game (USA)/Other Game (USA).md",
            "library/psx/Some Game/Some Game.cue",
            "library/sega32x/Third Game (USA).md",
            "library/switch/Mods/mod.zip",
            "loose/megadrive/Sixth Game (USA).md",
            "loose/megadrivejp/Seventh Game (Japan).md")

        val found = RomScanner.scanWithCollections(listOf(root.path), CollectionResolver())
        assertEquals(expected, found.map { it.file.relativeTo(root).invariantSeparatorsPath }.sorted())

        // An answer given in place of the collection's is all there is, for
        // `md` as for the rest; and the walk that knows no collections has
        // no `md` unless its caller says so.
        val overridden = RomScanner.scanWithCollections(listOf(root.path), CollectionResolver()) { RomScanner.ROM_EXTENSIONS }
        assertEquals(listOf("Some Game.cue", "mod.zip"), overridden.map { it.file.name }.sorted())
        assertEquals(listOf("Some Game.cue", "mod.zip"), scan())
        assertTrue("md" !in RomScanner.ROM_EXTENSIONS)
    }

    // The console table has a rule for each of these two, and a rule for a
    // file no scan picks up is no rule: a Famicom disk under `nes` is the
    // Disk System's, and a Neo Geo cartridge in one file is the one file of
    // an arcade collection that is hashed by what it holds. Neither was in
    // the list, so each was found only where a collection listed it.
    @Test fun `a Famicom disk and a Neo Geo cartridge in one file are picked up without being listed`() {
        touch("nes/Disk (Japan).fds")
        touch("arcade/brawler.neo")
        touch("somewhere/Disk (Japan).fds")

        assertEquals(listOf("arcade/brawler.neo", "nes/Disk (Japan).fds", "somewhere/Disk (Japan).fds"),
                     RomScanner.scanWithCollections(listOf(root.path), CollectionResolver())
                         .map { it.file.relativeTo(root).invariantSeparatorsPath }.sorted())
    }

    // ── The same tree reached more than once ────────────────────────────────

    @Test fun `repeated roots do not repeat files`() {
        touch("nes/First.nes")
        touch("snes/Second.sfc")
        val once = RomScanner.scan(listOf(root.path))

        assertEquals(once, RomScanner.scan(listOf(root.path, root.path)))
    }

    @Test fun `an ancestor root followed by its child preserves the first discovery order`() {
        touch("nes/First.nes")
        touch("snes/Second.sfc")
        val once = RomScanner.scan(listOf(root.path))

        assertEquals(once, RomScanner.scan(listOf(root.path, File(root, "nes").path)))
    }

    @Test fun `a child root followed by its ancestor keeps the child first`() {
        val child = touch("nes/First.nes")
        val sibling = touch("snes/Second.sfc")

        assertEquals(listOf(child, sibling),
                     RomScanner.scan(listOf(child.parent, root.path)))
    }

    /**
     * What the theme actually sends: the folder of each game it knows, beside
     * the collection those folders live in, in whatever order its games came.
     * The de-duplication must fold the overlap without losing a game — and the
     * two folders here share a prefix, which a check on string prefixes would
     * wrongly read as one inside the other.
     */
    @Test fun `per-game roots beside their collection each count once and none is lost`() {
        touch("psx/Final Fantasy/Final Fantasy.cue")
        touch("psx/Final Fantasy/Final Fantasy.bin")
        touch("psx/Final Fantasy IX/Final Fantasy IX.cue")
        touch("psx/Final Fantasy IX/Final Fantasy IX.bin")
        touch("psx/Loose.chd")
        val all = listOf("Final Fantasy IX.bin", "Final Fantasy IX.cue",
                         "Final Fantasy.bin", "Final Fantasy.cue", "Loose.chd")
        val ff = File(root, "psx/Final Fantasy").path
        val ff9 = File(root, "psx/Final Fantasy IX").path
        val psx = File(root, "psx").path

        for (roots in listOf(listOf(ff, ff9, psx), listOf(psx, ff, ff9), listOf(ff9, psx, ff))) {
            val found = RomScanner.scan(roots).map { it.name }
            assertEquals(all, found.sorted(), "roots in the order $roots")
        }
        // On their own, with no collection to cover for them, both games still count.
        assertEquals(4, RomScanner.scan(listOf(ff, ff9)).size)
    }

    @Test fun `symlink root aliases preserve the first path only`() {
        val source = touch("nes/First.nes")
        val alias = File(root, "nes-alias")
        symlink(alias, source.parentFile)

        val throughAlias = File(alias, source.name)
        assertEquals(listOf(throughAlias),
                     RomScanner.scan(listOf(alias.path, source.parent)))
    }

    @Test fun `file aliases have a single canonical identity`() {
        val source = touch("nes/First.nes")
        val alias = File(root, "alias.nes")
        symlink(alias, source)

        val found = RomScanner.scan(listOf(root.path))
        assertEquals(1, found.size)
        assertEquals(source.canonicalPath, found.single().canonicalPath)
    }

    /**
     * A link pointing back up the tree. `walkTopDown` follows links, so without
     * the visited set it went round until the path held too many links to
     * resolve, finding the same ROM once per lap: 41 times, measured here.
     */
    @Test fun `a symlink cycle is walked once`() {
        touch("nes/First.nes")
        val loop = File(root, "nes/back-to-the-top")
        symlink(loop, root)

        // Removed straight after, because deleteRecursively follows links too.
        try {
            assertEquals(listOf("First.nes"), scan())
        } finally {
            Files.deleteIfExists(loop.toPath())
        }
    }

    @Test fun `unsupported extensions and missing roots remain ignored`() {
        val supported = touch("nes/First.NES")
        touch("nes/readme.txt")

        assertEquals(listOf(supported),
                     RomScanner.scan(listOf(File(root, "missing").path, root.path)))
    }

    private fun symlink(alias: File, target: File) {
        try {
            Files.createSymbolicLink(alias.toPath(), target.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are unavailable: ${e.message}")
        } catch (e: IOException) {
            assumeTrue(false, "Symbolic links are unavailable: ${e.message}")
        } catch (e: SecurityException) {
            assumeTrue(false, "Symbolic links are unavailable: ${e.message}")
        }
    }
}
