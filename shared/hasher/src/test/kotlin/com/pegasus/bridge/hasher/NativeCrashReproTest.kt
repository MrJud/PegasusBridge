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
 * a track sheet whose fields run on. The answer wanted for each is no hash,
 * and it is asked for by name. "Did not crash" would not do: a file hashed as
 * the nothing that follows its header did not crash either, and came back
 * with a hash to look up.
 *
 * Every file is made here from the bytes that matter; none is a game.
 */
class NativeCrashReproTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("native-crash").toFile() }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun `truncated files end without a hash, a crash or a hang`() {
        // First a file that is whole. Every row below expects no hash, and no
        // hash is also what a child gives that never got as far as rcheevos:
        // the binding swallows whatever the call throws and answers null. A
        // hash for this one, and the right one, is what shows the rows to be
        // the library's own answers.
        val prg = ByteArray(16 * 1024) { (it * 31 + 7).toByte() }
        val whole = file("Whole.nes", ascii("NES\u001a") + ByteArray(12) + prg)
        assertEquals(NativeChild.Result.Ok(md5(prg), 7), NativeChild.hash(whole),
                     "a whole iNES file, hashed in a child: what follows its 16-byte header, console 7")

        // A header cut off after its magic word, for each console that takes a
        // header off before it hashes; a file shorter than the word itself;
        // and two track sheets with a field longer than its buffer.
        val rows = listOf(
            file("tiny.nes", ascii("NES\u001a") + byteArrayOf(1, 1)),
            file("tiny.fds", ascii("FDS\u001a") + byteArrayOf(0, 0)),
            file("tiny.lnx", ascii("LYNX") + byteArrayOf(0, 1)),
            file("tiny.a78", byteArrayOf(1) + ascii("ATARI7800")),
            file("tiny.cart", ascii("EmuSCV")),
            file("three.nes", ascii("NES")),
            file("digits.gdi", ascii("3\n3 45000 4 " + "9".repeat(64) + " track03.bin 0\n")),
            file("long.gdi", ascii("3\n3 45000 4 2352 " + "n".repeat(300) + " 0\n"))
        )

        val wrong = rows.mapNotNull { row ->
            val started = System.nanoTime()
            val result = NativeChild.hash(row)
            val ms = (System.nanoTime() - started) / 1_000_000
            println("NativeCrashReproTest: ${row.name} -> $result in $ms ms")
            if (result is NativeChild.Result.NoHash) null else "${row.name}: $result"
        }
        if (wrong.isNotEmpty())
            fail("${wrong.size} of ${rows.size} files did not end with no hash:\n" +
                 wrong.joinToString("\n") { "  $it" })
    }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
