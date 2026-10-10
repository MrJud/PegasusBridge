package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.PegasusMetafile
import com.pegasus.bridge.core.StderrLog
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which collection a folder is in, over libraries laid out as real ones are.
 *
 * The layouts are the ones a scan got wrong while it took the name of a
 * file's own folder for its platform: a folder for each game under `psx`, a
 * game three folders down under `ps3`, firmware under `switch`, a collection
 * behind a link, a folder whose name is not its collection's short name. The
 * metafiles are real files, written as Pegasus reads them.
 */
class CollectionResolverTest {

    private lateinit var library: File
    private val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @BeforeTest fun setUp() {
        library = Files.createTempDirectory("collections").toFile()
        BridgeLog.current = object : BridgeLog by NoopLog {
            override fun w(tag: String, msg: String, t: Throwable?) { warnings += msg }
        }
    }

    @AfterTest fun tearDown() {
        library.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun folder(relative: String) = File(library, relative).apply { mkdirs() }

    private fun metafile(relative: String, text: String, name: String = "metadata.pegasus.txt"): File =
        File(folder(relative), name).apply { writeText(text.trimIndent()) }

    /** A resolver that counts the folders it lists, which is what a resolution costs. */
    private class Counted {
        val listed: MutableList<File> = Collections.synchronizedList(mutableListOf())
        val resolver = CollectionResolver { dir -> listed += dir; PegasusMetafile.filesIn(dir) }
    }

    private fun resolve(relative: String) = CollectionResolver().collectionOf(File(library, relative))

    @Test fun `a game's own folder under a collection is in that collection`() {
        metafile("psx", "collection: Sony PlayStation\nshortname: psx\nextensions: cue, chd")
        folder("psx/Invented Quest (USA)")

        val fromGame = resolve("psx/Invented Quest (USA)")
        assertEquals(CollectionRef(shortName = "psx", name = "Sony PlayStation", dirName = "psx",
                                   directory = File(library, "psx"), declaredExtensions = setOf("cue", "chd"),
                                   source = CollectionRef.Source.DECLARED), fromGame)
        assertEquals(fromGame, resolve("psx"), "the collection's own folder is in it too")
    }

    @Test fun `a folder any number of levels down is in the collection above it`() {
        metafile("ps3", "collection: Sony PlayStation 3\nshortname: ps3")
        metafile("switch", "collection: Nintendo Switch\nshortname: switch")
        folder("ps3/Invented Racer [BLES00000]/PS3_GAME/USRDIR")
        folder("switch/Switch Files/Firmware")

        val ps3 = resolve("ps3/Invented Racer [BLES00000]/PS3_GAME/USRDIR")
        assertEquals("ps3" to "ps3", ps3.shortName to ps3.dirName)
        val switch = resolve("switch/Switch Files/Firmware")
        assertEquals("switch" to "switch", switch.shortName to switch.dirName)
        assertEquals(File(library, "switch"), switch.directory)
    }

    // The library is a folder of links, each to a folder kept somewhere
    // else under another name. By its canonical path a file in one is in
    // no folder of the library at all.
    @Test fun `a collection reached through a link keeps the link's name`() {
        metafile("store/PlayStation Discs", "collection: Sony PlayStation\nshortname: psx")
        folder("store/PlayStation Discs/Invented Quest (USA)")
        folder("roms")
        val link = File(library, "roms/psx")
        val made = runCatching {
            Files.createSymbolicLink(link.toPath(), File(library, "store/PlayStation Discs").toPath())
        }
        assumeTrue(made.isSuccess, "no symbolic links here")

        val ref = resolve("roms/psx/Invented Quest (USA)")
        assertEquals("psx" to "psx", ref.shortName to ref.dirName)
        assertEquals(link, ref.directory, "the collection's folder is the link, as the library names it")
    }

    @Test fun `a folder whose only declaration is the bridge's own overlay is a collection`() {
        metafile("gba", "collection: Game Boy Advance\nshortname: gba\nextensions: gba",
                 name = "zz-pegasusbridge.metadata.pegasus.txt")

        val ref = resolve("gba")
        assertEquals(CollectionRef.Source.DECLARED, ref.source)
        assertEquals("gba", ref.shortName)
        assertEquals(setOf("gba"), ref.declaredExtensions)
    }

    // The collection's own file and an overlay beside it are two sources for
    // one collection, to Pegasus and here.
    @Test fun `an overlay of the same collection adds its extensions and a missing short name`() {
        metafile("nes", "collection: Nintendo Entertainment System\nextensions: nes, FDS")
        metafile("nes", "collection: Nintendo Entertainment System\nshortname: nes\nextensions: unf, nes",
                 name = "zz-pegasusbridge.metadata.pegasus.txt")

        val ref = resolve("nes")
        assertEquals("Nintendo Entertainment System", ref.name)
        assertEquals("nes", ref.shortName, "the short name the first file left out is the overlay's")
        assertEquals(listOf("nes", "fds", "unf"), ref.declaredExtensions.toList())
    }

    @Test fun `the first file's short name stands when an overlay gives another`() {
        metafile("nes", "collection: NES\nshortname: nes")
        metafile("nes", "collection: NES\nshortname: famicom", name = "zz.metadata.txt")
        assertEquals("nes", resolve("nes").shortName)
    }

    @Test fun `a collection that declares no short name has its name in lower case`() {
        metafile("Master System", "collection: Sega Master System\nextensions: sms")
        val ref = resolve("Master System")
        assertEquals("sega master system", ref.shortName)
        assertEquals("Master System", ref.dirName)
    }

    // A second metafile that only lists games, in the collection's folder
    // and in a game's own: neither declares anything, and neither ends the
    // way up.
    @Test fun `a metafile that only lists games is passed over`() {
        metafile("snes", "collection: Super Nintendo\nshortname: snes")
        metafile("snes", "game: Invented Kart\nfile: Invented Kart (Europe).sfc", name = "metadata.txt")
        metafile("snes/Invented Kart", "game: Invented Kart\nfile: Invented Kart (Europe).sfc")

        assertEquals("snes", resolve("snes").shortName)
        val fromGame = resolve("snes/Invented Kart")
        assertEquals("snes" to "snes", fromGame.shortName to fromGame.dirName)
    }

    // A collection kept in a folder of another: each folder is in the one
    // declared nearest above it, whichever of them is asked about first.
    @Test fun `a collection declared inside another is the one its own folders are in`() {
        metafile("psx", "collection: Sony PlayStation\nshortname: psx\nextensions: cue")
        metafile("psx/Homebrew", "collection: PlayStation Homebrew\nshortname: psxhomebrew\nextensions: exe")
        folder("psx/Homebrew/Invented Demo"); folder("psx/Invented Quest (USA)")
        val asked = listOf("psx/Homebrew/Invented Demo" to "psxhomebrew", "psx/Homebrew" to "psxhomebrew",
                           "psx/Invented Quest (USA)" to "psx", "psx" to "psx")

        for (order in listOf(asked, asked.reversed())) {
            val resolver = CollectionResolver()
            for ((relative, shortName) in order) {
                val ref = resolver.collectionOf(File(library, relative))
                assertEquals(shortName, ref.shortName, relative)
                assertEquals(if (shortName == "psx") "psx" else "Homebrew", ref.dirName, relative)
                assertEquals(setOf(if (shortName == "psx") "cue" else "exe"), ref.declaredExtensions, relative)
            }
        }
    }

    // What the folder is called and what the collection calls itself are two
    // things, and both are kept: the short name is the platform, and the
    // folder can know which of the platform's consoles it holds.
    @Test fun `a folder that declares another short name gives both names`() {
        metafile("sega32x", "collection: Sega 32X\nshortname: megadrive\nextensions: 32x")
        metafile("Sega master system", "collection: Sega Master System\nshortname: mastersystem")

        val thirtyTwo = resolve("sega32x")
        assertEquals("megadrive" to "sega32x", thirtyTwo.shortName to thirtyTwo.dirName)
        val master = resolve("Sega master system")
        assertEquals("mastersystem" to "Sega master system", master.shortName to master.dirName)
        assertEquals("Sega Master System", master.name)
    }

    @Test fun `a second collection of another name is ignored, and said once`() {
        metafile("mixed", """
            collection: Mega Drive
            shortname: megadrive
            extensions: md

            collection: Sega 32X
            shortname: sega32x
            extensions: 32x
        """)
        folder("mixed/a"); folder("mixed/b")
        val resolver = CollectionResolver()

        for (relative in listOf("mixed", "mixed/a", "mixed/b")) {
            val ref = resolver.collectionOf(File(library, relative))
            assertEquals("megadrive", ref.shortName, relative)
            assertEquals(setOf("md"), ref.declaredExtensions, "the other collection's extensions are not this one's")
        }
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("'Sega 32X'") && warnings.single().contains("'Mega Drive'"),
                   warnings.single())
    }

    // One metafile above the folders, which sends each collection to its own:
    // the way a library is written when its metafile is kept at the top.
    @Test fun `a collection that lists the folder in its directories is the folder's, before the first`() {
        metafile(".", """
            collection: Nintendo Entertainment System
            shortname: nes
            directories: nes

            collection: Super Nintendo
            shortname: snes
            extensions: sfc
            directories: carts/snes
              elsewhere
        """)
        folder("nes"); folder("carts/snes/Invented Kart"); folder("gba")

        val snes = resolve("carts/snes/Invented Kart")
        assertEquals("snes" to "snes", snes.shortName to snes.dirName)
        assertEquals(File(library, "carts/snes"), snes.directory)
        assertEquals(setOf("sfc"), snes.declaredExtensions)
        assertEquals("nes", resolve("nes").shortName)
        // A folder none of them lists is the first collection's, in the
        // metafile's own folder, and that is said.
        val gba = resolve("gba")
        assertEquals("nes" to library.name, gba.shortName to gba.dirName)
        assertTrue(warnings.any { it.contains("'Super Nintendo'") }, warnings.toString())
    }

    // The metafile's own folder is passed on the way up from every folder it
    // lists, and to pass a folder is not to ask about it: nothing is said of
    // a folder that is in neither collection until something is found in it.
    @Test fun `a metafile that sends every collection to a folder of its own is told nothing`() {
        metafile(".", """
            collection: Nintendo Entertainment System
            shortname: nes
            directories: nes

            collection: Super Nintendo
            shortname: snes
            directories: snes
        """)
        folder("nes"); folder("snes/Invented Kart")
        val resolver = CollectionResolver()

        assertEquals("nes", resolver.collectionOf(File(library, "nes")).shortName)
        assertEquals("snes", resolver.collectionOf(File(library, "snes/Invented Kart")).shortName)
        assertEquals(emptyList(), warnings.toList())
        // Asked about, it is the first collection's, and that is said.
        assertEquals("nes", resolver.collectionOf(library).shortName)
        assertEquals(1, warnings.size, warnings.toString())
    }

    // Two collections that each list a folder above the one asked about, one
    // inside the other. The nearer is the folder's, as the nearer metafile
    // would be, and not whichever the file happens to declare first.
    @Test fun `of two collections that list a folder above, the nearer folder's is taken`() {
        metafile(".", """
            collection: Cartridges
            shortname: carts
            directories: carts

            collection: Super Nintendo
            shortname: snes
            directories: carts/snes
        """)
        folder("carts/snes/Invented Kart"); folder("carts/other")

        val snes = resolve("carts/snes/Invented Kart")
        assertEquals("snes" to "snes", snes.shortName to snes.dirName)
        assertEquals(File(library, "carts/snes"), snes.directory)
        val other = resolve("carts/other")
        assertEquals("carts" to "carts", other.shortName to other.dirName)
        assertEquals(emptyList(), warnings.toList(), "each folder was listed, and nothing was passed over")
    }

    // What a scan has always taken, and still takes where nothing says
    // better: the name of the folder the file is in.
    @Test fun `with no declaration anywhere above, the collection is inferred from the folder's own name`() {
        folder("loose/Invented Quest")

        val ref = resolve("loose/Invented Quest")
        assertEquals(CollectionRef.inferred("Invented Quest"), ref)
        assertEquals(CollectionRef.Source.INFERRED, ref.source)
        assertNull(ref.directory)
        assertEquals(emptySet(), ref.declaredExtensions)
        assertEquals("loose", resolve("loose").shortName)
    }

    @Test fun `fifty files in one folder cost one resolution`() {
        metafile("psx", "collection: Sony PlayStation\nshortname: psx")
        val game = folder("psx/Invented Quest (USA)")
        val counted = Counted()

        val refs = (1..50).map { counted.resolver.collectionOf(File(game, "Track $it.bin").parentFile) }

        assertEquals(listOf(game, File(library, "psx")), counted.listed.toList(),
                     "the game's folder and the collection's, each once")
        for (ref in refs) assertSame(refs.first(), ref)
    }

    // Every folder passed on the way up is remembered, so a collection of a
    // hundred game folders reads its metafile once.
    @Test fun `the folders on the way up are remembered for the next one that passes`() {
        metafile("psx", "collection: Sony PlayStation\nshortname: psx")
        val first = folder("psx/Invented Quest (USA)/extras")
        val second = folder("psx/Another Invention (Japan)")
        val counted = Counted()

        counted.resolver.collectionOf(first)
        counted.listed.clear()
        assertEquals("psx", counted.resolver.collectionOf(second).shortName)
        assertEquals(listOf(second), counted.listed.toList(), "only the new folder is looked at")
        counted.listed.clear()
        assertEquals("psx", counted.resolver.collectionOf(first.parentFile).shortName)
        assertEquals(emptyList(), counted.listed.toList(), "a folder passed before is not looked at again")
    }

    @Test fun `folders with nothing above them are remembered as well, each by its own name`() {
        val deep = folder("loose/a/b")
        val counted = Counted()

        assertEquals("b", counted.resolver.collectionOf(deep).shortName)
        counted.listed.clear()
        assertEquals("a", counted.resolver.collectionOf(deep.parentFile).shortName)
        assertEquals("c", counted.resolver.collectionOf(File(deep.parentFile, "c")).shortName)
        assertEquals(listOf(File(deep.parentFile, "c")), counted.listed.toList())
    }

    // A root is given as a person types it.
    @Test fun `a path with dots in it is the folder it comes to`() {
        metafile("psx", "collection: Sony PlayStation\nshortname: psx")
        folder("psx/Invented Quest (USA)"); folder("other")

        val ref = CollectionResolver().collectionOf(File(library, "other/../psx/./Invented Quest (USA)"))
        assertEquals("psx", ref.shortName)
        assertEquals(File(library, "psx"), ref.directory)
    }
}
