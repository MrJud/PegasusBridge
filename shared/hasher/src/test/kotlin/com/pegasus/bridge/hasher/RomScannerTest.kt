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
