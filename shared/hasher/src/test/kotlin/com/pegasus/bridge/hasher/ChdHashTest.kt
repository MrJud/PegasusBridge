package com.pegasus.bridge.hasher

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A disc in a `.chd`, hashed through the library as the JVM loads it.
 *
 * The reader is C (`pb_chd.c`, over libchdr), and what it must hash and
 * what it must refuse are rows of `shared/tests/native_repro_test.py`, run
 * on a sanitized build with no JVM around them. What is held here is what
 * those rows cannot see: that the library of each system, the DLL on Windows
 * among them, which no row is ever run on, reads a disc out of a `.chd` and
 * gives it the hash it gives the same disc as a cue sheet or an `.iso`; and
 * that two images hashed at once share nothing.
 *
 * The library is asked directly. A scan still turns every `.chd` away before
 * it comes to the library, and what a scan makes of one is not held here.
 */
class ChdHashTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("chd-hash").toFile() }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun `a Sega CD track hashes alike as a cue sheet and as a chd`() {
        val track = segaCdTrack()
        file("Disc.bin", track)
        val sheet = file("Disc.cue", ascii("FILE \"Disc.bin\" BINARY\r\n  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n"))
        val expected = HashOutcome.Ok(HashResult(md5(track.copyOfRange(16, 16 + 512)), SEGA_CD))
        assertEquals(expected, native.hashForConsole(sheet.path, SEGA_CD), "the rule, as the cue sheet is hashed")

        val image = file("Disc.chd", ChdImage.cd(listOf(ChdImage.Track("MODE1_RAW", track.chunked(2352)))))

        assertEquals(expected, native.hashForConsole(image.path, SEGA_CD))
        // With no console given a .chd is tried as a PlayStation, a
        // PlayStation 2 and a Dreamcast disc first: each opens the image,
        // finds no such disc in it and closes it again.
        assertEquals(expected, native.hashForConsole(image.path, 0))
    }

    @Test
    fun `a PlayStation 2 disc hashes alike as an iso, as a DVD chd and as a CD chd`() {
        val program = noise(7000, seed = 21)
        val disc = playStation2Disc("SLUS_200.21", program)
        val expected = HashOutcome.Ok(HashResult(md5(ascii("SLUS_200.21") + program), PLAYSTATION_2))
        assertEquals(expected, native.hashForConsole(file("Disc.iso", disc).path, PLAYSTATION_2),
                     "the rule, as the .iso is hashed")

        val dvd = file("Dvd.chd", ChdImage.dvd(disc))
        val small = file("Small hunks.chd", ChdImage.dvd(disc, sectorsToAHunk = 1))
        // The DVD's own sectors in the frames of a CD, which is what chdman
        // made of an .iso before it had a way to write a DVD.
        val cd = file("Cd.chd", ChdImage.cd(listOf(ChdImage.Track("MODE1", disc.chunked(2048)))))

        for (image in listOf(dvd, small, cd)) {
            assertEquals(expected, native.hashForConsole(image.path, PLAYSTATION_2), image.name)
        }
        assertEquals(expected, native.hashForConsole(dvd.path, 0), "with no console given, the second one tried")
    }

    // rcheevos hashes a program sector by sector and takes a sector that
    // does not come for the program's end. An image cut short inside the
    // program would so be given the hash of the name and of what was there,
    // which is the hash of no disc. The reader says that a hunk could not be
    // read, and the library has to make of that a call with no hash: it is
    // the library's own line, which the rows of native_repro_test.py, run on
    // another program, do not go through.
    @Test
    fun `a chd cut short inside the program gives no hash and says which hunk`() {
        val program = noise(7000, seed = 23)
        val whole = ChdImage.dvd(playStation2Disc("SLUS_200.23", program), sectorsToAHunk = 1)
        // The program is sectors 20 to 23, the last hunks of the file: the
        // first two are left, so rcheevos has begun the hash when the third
        // does not come.
        val cut = file("Cut.chd", whole.copyOf(whole.size - 3000))

        val failed = native.hashForConsole(cut.path, PLAYSTATION_2) as? HashOutcome.Failed
            ?: fail("a hash was given for an image whose program is not all there")

        assertTrue("CHD: hunk 22 could not be read" in failed.reason, failed.reason)
        assertEquals(HashOutcome.Ok(HashResult(md5(ascii("SLUS_200.23") + program), PLAYSTATION_2)),
                     native.hashForConsole(file("Whole.chd", whole).path, PLAYSTATION_2), "the same image, whole")
    }

    // Nothing of the reader is kept outside the track it has open, and
    // libchdr and the decompressors keep what they need in the image they
    // have open. Were any of it one place for the whole process, a scan's
    // workers would read each other's discs. Two images that are unlike in
    // everything, each hashed by four threads at once.
    @Test
    fun `two threads hash two chds at once and each gets its own`() {
        val track = segaCdTrack()
        val segaCd = file("One.chd", ChdImage.cd(listOf(ChdImage.Track("MODE1_RAW", track.chunked(2352)))))
        val playStation = file("Other.chd", ChdImage.dvd(playStation2Disc("SLUS_200.22", noise(9000, seed = 22))))
        val expected = mapOf(segaCd to native.hashForConsole(segaCd.path, SEGA_CD),
                             playStation to native.hashForConsole(playStation.path, PLAYSTATION_2))
        val consoles = mapOf(segaCd to SEGA_CD, playStation to PLAYSTATION_2)
        val (one, other) = expected.values.map { (it as? HashOutcome.Ok ?: fail("$it")).result }
        assertNotEquals(one.hash, other.hash)

        val start = CountDownLatch(1)
        val wrong = java.util.Collections.synchronizedList(ArrayList<String>())
        val threads = (0 until 8).map { number ->
            thread {
                val mine = if (number % 2 == 0) segaCd else playStation
                // A thread that ends on something thrown has made no call that
                // answered wrong, and is not thereby one that went well.
                try {
                    start.await()
                    repeat(300) {
                        val outcome = native.hashForConsole(mine.path, consoles.getValue(mine))
                        if (outcome != expected[mine]) wrong += "thread $number, ${mine.name}: $outcome"
                    }
                } catch (thrown: Throwable) {
                    wrong += "thread $number, ${mine.name}: $thrown"
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(TimeUnit.MINUTES.toMillis(2)) }

        assertTrue(threads.none { it.isAlive }, "every thread came back")
        assertEquals(emptyList<String>(), wrong.take(5))
    }

    // ---------------------------------------------------------------- helpers

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    private fun ByteArray.chunked(size: Int): List<ByteArray> =
        (indices step size).map { copyOfRange(it, minOf(it + size, this.size)) }

    /**
     * A Sega CD data track as a dump holds it: raw sectors of 2352 bytes,
     * each with its sync pattern, its address and mode 1, sector 0 opening
     * with the disc and ROM headers rcheevos knows the console by. The hash
     * is of the first 512 bytes of that sector's data.
     */
    private fun segaCdTrack(sectors: Int = 32): ByteArray {
        fun bcd(n: Int) = ((n / 10) shl 4 or (n % 10)).toByte()
        val track = ByteArray(sectors * 2352)
        for (s in 0 until sectors) {
            val at = s * 2352
            track.fill(0xFF.toByte(), at + 1, at + 11)
            val lba = s + 150
            byteArrayOf(bcd(lba / 4500), bcd(lba / 75 % 60), bcd(lba % 75), 1).copyInto(track, at + 12)
            noise(2048, seed = 900 + s).copyInto(track, at + 16)
        }
        ascii("SEGADISCSYSTEM  CHDTEST    ").copyInto(track, 16)
        ascii("SEGA MEGA DRIVE ").copyInto(track, 16 + 0x100)
        return track
    }

    /**
     * An ISO 9660 volume with the two files rcheevos hashes a PlayStation 2
     * disc by: the volume descriptor in sector 16, the root directory in 18,
     * SYSTEM.CNF in 19, which names the program to start, and the program
     * from 20 on. The hash is of the program's name and then its bytes.
     */
    private fun playStation2Disc(name: String, program: ByteArray): ByteArray {
        val sector = 2048
        fun le(value: Int, bytes: Int) = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
        fun record(entry: String, at: Int, size: Int): ByteArray {
            val length = 33 + entry.length + (33 + entry.length) % 2
            return ByteArray(length).also {
                it[0] = length.toByte()
                le(at, 3).copyInto(it, 2)
                le(size, 4).copyInto(it, 10)
                it[32] = entry.length.toByte()
                ascii(entry).copyInto(it, 33)
            }
        }
        val settings = ascii("BOOT2 = cdrom0:\\$name;1\r\nVER = 1.00\r\n")
        val image = ByteArray((20 + (program.size + sector - 1) / sector) * sector)
        val volume = 16 * sector
        (byteArrayOf(1) + ascii("CD001") + byteArrayOf(1)).copyInto(image, volume)
        le(sector, 2).copyInto(image, volume + 128)
        record("\u0000", 18, sector).copyInto(image, volume + 156)
        (record("SYSTEM.CNF;1", 19, settings.size) + record("$name;1", 20, program.size)).copyInto(image, 18 * sector)
        settings.copyInto(image, 19 * sector)
        program.copyInto(image, 20 * sector)
        return image
    }

    /** GoldenHashTest's filler, a xorshift stream by seed. */
    private fun noise(size: Int, seed: Int): ByteArray {
        var x = seed * 0x9E3779B1.toInt() xor 0x2545F491
        return ByteArray(size) {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            (x ushr 24).toByte()
        }
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SEGA_CD = 9
        const val PLAYSTATION_2 = 21

        /**
         * The library Gradle's test task names, loaded by its path as the
         * golden tests load it: the committed one, or the one given with
         * -Ppegasus.nativeLibrary.
         */
        val native: NativeRomHasher by lazy {
            val path = System.getProperty("pegasus.bridge.nativeLibrary")
                ?: fail("pegasus.bridge.nativeLibrary is not set: run this through Gradle, whose test " +
                        "task points it at native/out")
            NativeRomHasher.resetForTests()
            NativeRomHasher.tryLoad(File(path))
                ?: fail("could not load $path: ${NativeRomHasher.lastError()}")
        }
    }
}
