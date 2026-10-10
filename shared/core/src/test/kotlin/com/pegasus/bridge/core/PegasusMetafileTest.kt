package com.pegasus.bridge.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the header of a Pegasus metafile is read as, and how much of the file
 * is read to learn it.
 *
 * The files are written as people and tools write them: with a byte order
 * mark, with Windows line ends, with one `file:` for each game, and with
 * every game of the collection listed under the three lines that matter.
 */
class PegasusMetafileTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("metafile").toFile()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun read(text: String) = PegasusMetafile.collections(ByteArrayInputStream(text.toByteArray()))

    /** Counts what is asked of it and what it gives, which is how far a reader went into the file. */
    private class Counting(private val inner: InputStream) : InputStream() {
        var given = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) given++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) given += it }
    }

    @Test fun `one collection is one block with what its lines list`() {
        val blocks = read("""
            # written by hand
            collection: Sony PlayStation
            shortname: psx
            extensions: CUE, chd,
              m3u
              pbp
            extension: iso
            ignore-extensions: sbi, Bin
            directories: discs
              /elsewhere/psx
            launch: emulator
              --fullscreen "{file.path}"
        """.trimIndent())

        assertEquals(listOf(PegasusMetafile.Block(
            name = "Sony PlayStation", shortName = "psx",
            extensions = listOf("cue", "chd", "m3u", "pbp", "iso"),
            ignoreExtensions = listOf("sbi", "bin"),
            directories = listOf("discs", "/elsewhere/psx"),
            declaresShortName = true)), blocks)
    }

    // What Pegasus calls a collection that does not say: its name in lower case.
    @Test fun `a collection with no shortname is called by its name in lower case`() {
        val block = read("collection: Sega Master System\nextensions: sms").single()
        assertEquals("sega master system", block.shortName)
        assertFalse(block.declaresShortName)
    }

    // The flat reader the launch editor has would give the second name and the
    // second list for both.
    @Test fun `two collection lines give two blocks, each with its own lines`() {
        val blocks = read("""
            collection: Mega Drive
            shortname: megadrive
            extensions: md, gen

            collection: Sega 32X
            extensions: 32x
            directories: 32x
        """.trimIndent())

        assertEquals(listOf("Mega Drive", "Sega 32X"), blocks.map { it.name })
        assertEquals(listOf("megadrive", "sega 32x"), blocks.map { it.shortName })
        assertEquals(listOf(listOf("md", "gen"), listOf("32x")), blocks.map { it.extensions })
        assertEquals(listOf(emptyList(), listOf("32x")), blocks.map { it.directories })
    }

    @Test fun `repeated file lines are all kept, in order, as they are written`() {
        val block = read("""
            collection: Catalogue
            shortname: mastersystem
            file: Alpha Quest (World) (Rev 1).sms
            file: Beta Racer (Europe).sms
            files: sub/Gamma Fighter.sms
              Delta's Trap (USA, Europe).sms
            file: Alpha Quest (World) (Rev 1).sms
        """.trimIndent()).single()

        assertEquals(listOf("Alpha Quest (World) (Rev 1).sms", "Beta Racer (Europe).sms", "sub/Gamma Fighter.sms",
                            "Delta's Trap (USA, Europe).sms", "Alpha Quest (World) (Rev 1).sms"), block.files)
        assertEquals(emptyList(), block.extensions)
    }

    // An extension twice is one extension, whichever spelling of the line it is on.
    @Test fun `extensions written on several lines are one list without repeats`() {
        val block = read("collection: NES\nextensions: nes, fds\nextension: NES\nextensions: unf").single()
        assertEquals(listOf("nes", "fds", "unf"), block.extensions)
    }

    @Test fun `a byte order mark is not part of the first name`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    "collection: NES\nshortname: nes\n".toByteArray()
        val block = PegasusMetafile.collections(ByteArrayInputStream(bytes)).single()
        assertEquals("NES" to "nes", block.name to block.shortName)
    }

    @Test fun `windows line ends are not part of any value`() {
        val block = read("collection: NES\r\nshortname: nes\r\nextensions: nes,\r\n  fds\r\nfile: A.nes\r\n\r\ngame: A\r\nfile: A.nes\r\n")
            .single()
        assertEquals(PegasusMetafile.Block("NES", "nes", listOf("nes", "fds"), files = listOf("A.nes"),
                                           declaresShortName = true), block)
    }

    // How a game's own folder under a collection is written, and what the
    // Bridge's overlay is not: entries for games and no collection above them.
    @Test fun `a file that only lists games declares nothing`() {
        assertEquals(emptyList(), read("game: Alpha Quest\nfile: Alpha Quest.sms\ncollection: Too Late\n"))
        assertEquals(emptyList(), read(""))
        assertEquals(emptyList(), read("# nothing but a comment\n\n"))
    }

    @Test fun `the header ends at the first game, whatever its fields are called`() {
        val block = read("""
            collection: NES
            shortname: nes

            game: Alpha Quest
            file: Alpha Quest (USA).nes
            extensions: zip
            shortname: other
        """.trimIndent()).single()

        assertEquals(PegasusMetafile.Block("NES", "nes", declaresShortName = true), block)
    }

    // Pegasus refuses such lines too: there is nothing yet for them to describe.
    @Test fun `lines above the first collection belong to nothing`() {
        val block = read("shortname: stray\nextensions: zip\ncollection: NES\n").single()
        assertEquals(PegasusMetafile.Block("NES", "nes"), block)
    }

    @Test fun `a name is read in any case and a collection with no name is not one`() {
        val blocks = read("Collection: NES\nShortName: nes\nEXTENSIONS: nes\ncollection:\nextensions: fds\n")
        assertEquals(listOf(PegasusMetafile.Block("NES", "nes", listOf("nes", "fds"), declaresShortName = true)), blocks)
    }

    // The point of reading by hand. A metafile lists every game under its
    // header, and a reader that fills a buffer first takes 8 KiB or 16 of
    // it before it gives a line.
    @Test fun `a long file with a short header is read for less than 8 KiB`() {
        val text = buildString {
            append("collection: NES\nshortname: nes\nextensions: nes\n")
            var n = 0
            while (length < 600 * 1024) {
                append("\ngame: Invented Game ${n++}\nfile: Invented Game $n (World).nes\ndeveloper: Nobody\n")
            }
        }
        val counted = Counting(ByteArrayInputStream(text.toByteArray()))

        val block = PegasusMetafile.collections(counted).single()

        assertEquals(PegasusMetafile.Block("NES", "nes", listOf("nes"), declaresShortName = true), block)
        assertTrue(counted.given < 8 * 1024, "${counted.given} bytes were read of ${text.length}")
    }

    // A file with a metafile's name and something else in it: no game ever
    // comes, and it is not read to its end to learn that.
    @Test fun `a file with no end to its header is given up at the limit`() {
        val head = "collection: NES\nshortname: nes\n".toByteArray()
        val counted = Counting(ByteArrayInputStream(head + ByteArray(3 shl 20) { 'x'.code.toByte() }))

        val block = PegasusMetafile.collections(counted).single()

        assertEquals("NES" to "nes", block.name to block.shortName)
        assertTrue(counted.given <= PegasusMetafile.HEADER_LIMIT + 4096, "${counted.given} bytes were read")
    }

    @Test fun `a file that cannot be read declares nothing and throws nothing`() {
        assertEquals(emptyList(), PegasusMetafile.collections(File(dir, "metadata.pegasus.txt")))
        val folder = File(dir, "metadata.txt").apply { mkdirs() }
        assertEquals(emptyList(), PegasusMetafile.collections(folder))
    }

    @Test fun `a file on disk is read as its bytes are`() {
        val file = File(dir, "metadata.pegasus.txt").apply { writeText("collection: NES\nshortname: nes\n") }
        assertEquals(listOf(PegasusMetafile.Block("NES", "nes", declaresShortName = true)),
                     PegasusMetafile.collections(file))
    }

    // Pegasus' own test, is_metadata_file: the two names, and anything that
    // ends in a dot and one of them. By the letter.
    @Test fun `a metafile is one of two names or a name that ends in one`() {
        val yes = listOf("metadata.pegasus.txt", "metadata.txt", "zz-pegasusbridge.metadata.pegasus.txt",
                         "snes.metadata.txt", ".metadata.txt")
        val no = listOf("metadata.pegasus.txt.pegasusbridge-backup", "Metadata.txt", "xmetadata.txt",
                        "metadata.pegasus.txt.disabled", "collections.txt", "metadata", "")
        assertEquals(yes, yes.filter(PegasusMetafile::isMetafile))
        assertEquals(emptyList(), no.filter(PegasusMetafile::isMetafile))
    }

    @Test fun `a folder's metafiles are the two names first and then the others by name`() {
        for (name in listOf("zz-pegasusbridge.metadata.pegasus.txt", "metadata.txt", "aa.metadata.txt",
                            "metadata.pegasus.txt", "notes.txt", "metadata.pegasus.txt.pegasusbridge-backup")) {
            File(dir, name).writeText("collection: X\n")
        }
        // A folder is not a file, whatever it is called.
        File(dir, "bb.metadata.txt").mkdirs()

        assertEquals(listOf("metadata.pegasus.txt", "metadata.txt", "aa.metadata.txt",
                            "zz-pegasusbridge.metadata.pegasus.txt"),
                     PegasusMetafile.filesIn(dir).map { it.name })
        assertEquals(emptyList(), PegasusMetafile.filesIn(File(dir, "not there")))
        assertEquals(emptyList(), PegasusMetafile.filesIn(File(dir, "notes.txt")))
    }
}
