package com.pegasus.bridge.hasher

import java.io.File
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
}
