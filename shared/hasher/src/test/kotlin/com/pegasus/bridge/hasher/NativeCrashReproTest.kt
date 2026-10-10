package com.pegasus.bridge.hasher

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Files that used to end the process, handed to the committed library through
 * its JNI, each in a JVM of its own ([NativeChild]).
 *
 * The golden tests show the library right about files that are what they say.
 * These are files that are not: a download that stopped after its first bytes,
 * a track sheet whose fields run on, a disc image whose tables say more than
 * the file holds, a playlist that names itself. The answer wanted for each is
 * no hash, and it is asked for by name. "Did not crash" would not do: a file
 * hashed as the nothing that follows its header did not crash either, and
 * came back with a hash to look up, and so did a disc of 1496 bytes with a
 * program of a gigabyte.
 *
 * Each file is handed over twice where there is a console it claims to be
 * of: with no console named, for rcheevos to go by the extension, and with
 * that console forced, which is how a scan that knows the file's collection
 * will ask. The two ways in meet only inside rcheevos, and a guard on one of
 * them is not a guard on the other.
 *
 * One test hands over no file but a path that names none, which under one
 * console did what these files did.
 *
 * Every file is made here from the bytes that matter; none is a game.
 */
class NativeCrashReproTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("native-crash").toFile() }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun `truncated files end without a hash, a crash or a hang`() {
        // First a file that is whole. Every row below expects no hash, and a
        // library that gave no hash to anything would pass them all. A hash
        // for this one, and the right one, is what shows the rows to be
        // answers about the files.
        val prg = ByteArray(16 * 1024) { (it * 31 + 7).toByte() }
        val whole = file("Whole.nes", ascii("NES\u001a") + ByteArray(12) + prg)
        assertEquals(NativeChild.Result.Ok(md5(prg), 7), NativeChild.hash(whole),
                     "a whole iNES file, hashed in a child: what follows its 16-byte header, console 7")
        // And that a console named here is the console asked: as a Super
        // Nintendo cartridge the same file is hashed header and all. Were the
        // number lost on the way to the child, the forced rows below would be
        // the rows above them run twice.
        assertEquals(NativeChild.Result.Ok(md5(whole.readBytes()), 3), NativeChild.hash(whole, console = 3),
                     "the same file hashed in a child as console 3: all of it")

        // A header cut off after its magic word, for each console that takes a
        // header off before it hashes; a file shorter than the word itself;
        // and two track sheets with a field longer than its buffer.
        val tinyNes = file("tiny.nes", ascii("NES\u001a") + byteArrayOf(1, 1))
        val threeNes = file("three.nes", ascii("NES"))
        val rows = listOf(
            tinyNes to 0,
            file("tiny.fds", ascii("FDS\u001a") + byteArrayOf(0, 0)) to 0,
            file("tiny.lnx", ascii("LYNX") + byteArrayOf(0, 1)) to 0,
            file("tiny.a78", byteArrayOf(1) + ascii("ATARI7800")) to 0,
            file("tiny.cart", ascii("EmuSCV")) to 0,
            threeNes to 0,
            file("digits.gdi", ascii("3\n3 45000 4 " + "9".repeat(64) + " track03.bin 0\n")) to 0,
            file("long.gdi", ascii("3\n3 45000 4 2352 " + "n".repeat(300) + " 0\n")) to 0,
            // As the NES, by number.
            tinyNes to 7,
            threeNes to 7
        )

        assertNoHash(rows)
    }

    @Test
    fun `disc images and playlists that say more than they hold end without a hash, a crash or a hang`() {
        // The whole file first, as above and for the same reason.
        val prg = ByteArray(16 * 1024) { (it * 17 + 3).toByte() }
        val whole = file("Whole.nes", ascii("NES\u001a") + ByteArray(12) + prg)
        assertEquals(NativeChild.Result.Ok(md5(prg), 7), NativeChild.hash(whole),
                     "a whole iNES file, hashed in a child: what follows its 16-byte header, console 7")

        val wii = hex("5D1C9EA3")          // at 0x18 of a Wii disc; its table of partitions is at 0x40000
        // A Wii image that holds all that is read on the way to its table,
        // the last of which is the region code at 0x4E000, and so is refused
        // for what the table says.
        val wiiSize = 0x4E020
        val gamecube = hex("C2339F3D")     // at 0x1C of a GameCube disc
        // 1496 bytes that end with the header of a program said to be at
        // 0x500, whose segments' sizes are the 18 words from 0x590.
        fun program(segments: Int, size: String) = image(0x5D8, 0x1C to gamecube, 0x420 to hex("00000500"),
            *Array(segments) { 0x590 + it * 4 to hex(size) })

        // Two playlists that name each other: the second is not hashed for
        // itself, the first leads to it.
        file("b.m3u", ascii("a.m3u\n"))

        // The table of partitions: none; more than there is room to count;
        // two counts that add up to 1 in 32 bits; and no table, the file
        // ending long before it.
        val wiiImages = listOf(
            file("wii-no-partitions.iso", image(wiiSize, 0x18 to wii)),
            file("wii-partition-count.iso", image(wiiSize, 0x18 to wii, 0x40000 to hex("20000000"))),
            file("wii-count-wraps.iso",
                 image(wiiSize, 0x18 to wii, 0x40000 to hex("FFFFFFFF"), 0x40008 to hex("00000002"))),
            file("wii-1024.iso", image(1024, 0x18 to wii)),
            // An image that ends with its table, whose fourth group names one
            // partition, at the start of the file: the region code is not
            // there, and was hashed as the four bytes the stack held.
            file("wii-table-and-no-more.iso", image(0x40020, 0x18 to wii, 0x40018 to hex("00000001"))),
            // One partition at 0x50000, its data at 0x58000 and said to be
            // 16 GiB long, of which the file holds one cluster of 0x8000
            // bytes. The clusters that are not there were hashed as that one,
            // a thousand times over.
            file("wii-cluster-missing.iso",
                 image(0x60020, 0x18 to wii, 0x40000 to hex("00000001" + "00013808"),
                       wiiSize to hex("00014000" + "00000000"),
                       0x502B8 to hex("00016000" + "FFFFFFFF")))
        )
        // One sector of an OperaFS volume, which is what a 3DO disc is,
        // with blocks of 2048 bytes and its root directory in block 16.
        val opera = file("opera-short.iso",
            image(2048, 0 to hex("015A5A5A5A5A01"), 0x4C to hex("00000800"), 0x64 to hex("00000010")))
        val playlists = listOf(
            file("self.m3u", ascii("self.m3u\n")),
            file("a.m3u", ascii("b.m3u\n"))
        )
        val gamecubeImages = listOf(
            // A GameCube disc's program with one segment of a gigabyte, and
            // with 18 of four.
            file("dol-one-gigabyte.iso", program(1, "40000000")),
            file("dol-eighteen.iso", program(18, "FFFFFFFF")),
            // The two above end before the word that says how long the disc's
            // header is, and are refused there. This one is whole up to its
            // program's header, which is all that follows the disc's, and
            // says the gigabyte again: refused where the segment is read.
            file("dol-whole-header.iso",
                 image(0x2460 + 0xD8, 0x1C to gamecube, 0x420 to hex("00002460"),
                       0x2460 + 0x90 to hex("40000000")))
        )

        assertNoHash(
            (wiiImages + opera + playlists + gamecubeImages).map { it to 0 } +
            // And each as the console it is made to look like, by number: the
            // Wii, the 3DO, the GameCube, and for the playlists the
            // PlayStation, whose discs are the ones listed that way.
            wiiImages.map { it to 19 } + (opera to 43) + playlists.map { it to 12 } +
            gamecubeImages.map { it to 16 }
        )
    }

    // Not a file made to mislead, this once, but a path: one that ends where
    // a folder's name does. An arcade set is hashed by its file's name with
    // the extension taken off, and of a name of no letters rcheevos takes off
    // one more than there are, so the length it hashed was the largest there
    // is and the process ended reading memory that was not its own. The
    // library turns such a path away before rcheevos hears of it; rcheevos
    // itself is as it was, which tests/native_repro_test.py keeps on record.
    @Test
    fun `a path that ends in a separator is turned away before it can end the process`() {
        // That console 27 in a child is the arcade hash, and so the rows
        // below are about the lines that crashed: the name, and no file read.
        assertEquals(NativeChild.Result.Ok(md5(ascii("mslug")), 27),
                     NativeChild.run(listOf(File(dir, "mslug.zip").path, "27")),
                     "a set that is not there, hashed in a child as console 27: the MD5 of its name")

        // Each stroke rcheevos reads as the end of a folder's name, on a
        // folder that is there, on one that is not, and with nothing before
        // it; and one of them with no console named, which crashed nothing
        // and is refused all the same.
        val rows = listOf(
            "/" to 27,
            dir.path + "/" to 27,
            File(dir, "mslug.zip").path + "/" to 27,
            "C:\\roms\\" to 27,
            dir.path + "/" to 0
        )
        val wrong = rows.mapNotNull { (path, console) ->
            val result = NativeChild.run(listOf(path, console.toString()))
            println("NativeCrashReproTest: $path as console $console -> $result")
            if (result == NativeChild.Result.NoHash("The path names no file")) null
            else "$path as console $console: $result"
        }
        if (wrong.isNotEmpty())
            fail("${wrong.size} of ${rows.size} paths were not turned away:\n" + wrong.joinToString("\n") { "  $it" })
    }

    /**
     * Hashes each file in a child of its own, as the console beside it or as
     * its extension suggests where that is 0, and fails, naming them, if any
     * ended otherwise than with no hash.
     */
    private fun assertNoHash(rows: List<Pair<File, Int>>) {
        val wrong = rows.mapNotNull { (row, console) ->
            val started = System.nanoTime()
            val result = NativeChild.hash(row, console)
            val ms = (System.nanoTime() - started) / 1_000_000
            println("NativeCrashReproTest: ${row.name} as console $console -> $result in $ms ms")
            if (result is NativeChild.Result.NoHash) null else "${row.name} as console $console: $result"
        }
        if (wrong.isNotEmpty())
            fail("${wrong.size} of ${rows.size} rows did not end with no hash:\n" +
                 wrong.joinToString("\n") { "  $it" })
    }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    /** [size] bytes of nothing, with each of [parts] put where it says. */
    private fun image(size: Int, vararg parts: Pair<Int, ByteArray>): ByteArray {
        val bytes = ByteArray(size)
        for ((at, part) in parts) part.copyInto(bytes, at)
        return bytes
    }

    private fun hex(digits: String) = digits.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
