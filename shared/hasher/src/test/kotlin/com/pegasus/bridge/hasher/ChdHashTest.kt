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
 * The first tests ask the library directly. The ones after them go the way
 * a scan's hasher goes ([ArchiveAwareHasher]), which plans for the file by
 * its collection first: a `.chd` out of an archive and one a playlist names,
 * a file that is called `.chd` and is none, one that is not there, and a
 * disc of the console that asks for a disc's largest track.
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

    // An archive's entry is copied out to be hashed, under a name that ends
    // as the entry's does, and the reader knows a .chd by that ending. The
    // digests beside the hash are of the entry, which is the ROM.
    @Test
    fun `a chd in an archive is taken out and hashed as the disc it holds`() {
        val program = noise(7000, seed = 24)
        val image = ChdImage.dvd(playStation2Disc("SLUS_200.24", program))
        val packed = File(dir, "Disc.zip").also { zip ->
            java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("Disc.chd")); z.write(image); z.closeEntry()
            }
        }

        val result = (planning.hashDetailed(packed.path, "ps2") as? HashOutcome.Ok ?: fail("no hash")).result

        assertEquals(md5(ascii("SLUS_200.24") + program) to PLAYSTATION_2, result.hash to result.consoleId)
        assertEquals("Disc.chd" to md5(image), result.archiveEntry to result.fileMd5)
        assertEquals(emptyList(), File(dir, "tmp").walkTopDown().filter { it.isFile }.toList(), "left of the copy")
    }

    // The first file a playlist names is planned for as any file of the
    // collection is, and a .chd is then the disc.
    @Test
    fun `a playlist that names a chd is hashed as that disc`() {
        val program = noise(7000, seed = 25)
        file("Game (Disc 1).chd", ChdImage.dvd(playStation2Disc("SLUS_200.25", program)))
        val playlist = file("Game.m3u", ascii("Game (Disc 1).chd\r\nGame (Disc 2).chd\r\n"))

        val result = (planning.hashDetailed(playlist.path, "ps2") as? HashOutcome.Ok ?: fail("no hash")).result

        assertEquals(md5(ascii("SLUS_200.25") + program) to PLAYSTATION_2, result.hash to result.consoleId)
    }

    // The reader refuses the file by its first bytes and says so, and
    // rcheevos then says of the track what it says of any it could not
    // open. The file does open, so this is a verdict to keep: it will be
    // no CHD at the next scan either.
    @Test
    fun `a file called chd that is no CHD is kept as one the console refused, with the reader's reason`() {
        val fake = file("Fake.chd", noise(4096, seed = 26))

        assertEquals(HashOutcome.Failed("the hasher could not read Fake.chd: " +
                                        "CHD: the file does not begin as a CHD does; Could not open track",
                                        retryable = false),
                     planning.hashDetailed(fake.path, "ps2"))
    }

    // A file the walk found and that is gone when its turn comes, with the
    // card it was on. The scan's hasher looks before it asks, and says so.
    // Gone between that look and the call, the file is the reader's to
    // miss: it opens the image through rcheevos' reader of files and says
    // what that says, in the words a failure is known by as one to try
    // again, and not the words of an image it refused.
    @Test
    fun `a chd that is not there is tried again`() {
        val gone = File(dir, "Gone.chd")
        assertEquals(HashOutcome.Failed("no such file", retryable = true), planning.hashDetailed(gone.path, "ps2"))

        for (console in listOf(PLAYSTATION_2, 0)) {
            val failed = native.hashForConsole(gone.path, console) as? HashOutcome.Failed
                ?: fail("a file that is not there was answered for")
            assertTrue(failed.retryable && RcheevosNative.isTransient(failed.reason), "console $console: $failed")
            assertTrue(failed.reason.startsWith("Could not open file"), failed.reason)
        }
    }

    // Where nothing is known of the collection rcheevos tries a .chd as
    // each console it knows one of, and the first that finds its disc in
    // the image answers. A PlayStation 2 disc is one of the ten a .chd is
    // read as, and its hash is kept. The list ends with the PC-FX, which is
    // one of them too: its hash is kept left to guess, and in a collection
    // of that console the file is read and hashed, where it was turned away
    // by its name.
    @Test
    fun `left to guess, a chd of one of the ten consoles keeps its hash, a PC-FX disc among them`() {
        val program = noise(7000, seed = 27)
        val known = file("Known.chd", ChdImage.dvd(playStation2Disc("SLUS_200.27", program)))
        val kept = (planning.hashDetailed(known.path, "attic") as? HashOutcome.Ok ?: fail("no hash")).result
        assertEquals(md5(ascii("SLUS_200.27") + program) to PLAYSTATION_2, kept.hash to kept.consoleId)

        // A PC-FX disc as rcheevos knows one: the marker in the first
        // sector, and in the second where the program lies and how long
        // it is. The hash is of the first 128 bytes of the second sector
        // and then the program's sectors.
        val track = rawTrack(32)
        ascii("PC-FX:Hu_CD-ROM").copyInto(track, 16)
        byteArrayOf(2, 0, 0, 0, 1, 0, 0, 0).copyInto(track, 2352 + 16 + 32)
        val expected = md5(track.copyOfRange(2352 + 16, 2352 + 16 + 128) +
                           track.copyOfRange(2 * 2352 + 16, 2 * 2352 + 16 + 2048)) to PC_FX
        val other = file("Other.chd", ChdImage.cd(listOf(ChdImage.Track("MODE1_RAW", track.chunked(2352)))))
        val guess = native.hashForConsole(other.path, 0) as? HashOutcome.Ok ?: fail("the library gave no hash")
        assertEquals(expected, guess.result.hash to guess.result.consoleId, "the console the library settles on")

        for (collection in listOf("attic", "pcfx")) {
            val result = (planning.hashDetailed(other.path, collection) as? HashOutcome.Ok
                ?: fail("no hash in $collection")).result
            assertEquals(expected, result.hash to result.consoleId, collection)
        }
    }

    // The PC-FX's hash asks for the largest track of a disc, and
    // RetroAchievements' hasher reads that where the last track lies. On a
    // disc with its data between tracks of audio it finds no PC-FX there,
    // rcheevos goes on to track 2, and the disc is hashed as from its cue
    // sheet. That is the library's own way through the reader, on every
    // system that loads it.
    @Test
    fun `a PC-FX disc whose data track lies between two of audio hashes from a chd as from its cue sheet`() {
        val (data, hash) = pcFxTrack()
        val audio = noise(30 * 2352, seed = 29)
        file("Fx-1.bin", audio); file("Fx-2.bin", data); file("Fx-3.bin", audio)
        val sheet = file("Fx.cue", ascii("FILE \"Fx-1.bin\" BINARY\r\n  TRACK 01 AUDIO\r\n    INDEX 01 00:00:00\r\n" +
                                         "FILE \"Fx-2.bin\" BINARY\r\n  TRACK 02 MODE1/2048\r\n    INDEX 01 00:00:00\r\n" +
                                         "FILE \"Fx-3.bin\" BINARY\r\n  TRACK 03 AUDIO\r\n    INDEX 01 00:00:00\r\n"))
        val expected = HashOutcome.Ok(HashResult(hash, PC_FX))
        assertEquals(expected, native.hashForConsole(sheet.path, PC_FX), "the rule, as the cue sheet is hashed")

        val image = file("Fx.chd", ChdImage.cd(listOf(ChdImage.Track("AUDIO", audio.chunked(2352)),
                                                      ChdImage.Track("MODE1", data.chunked(2048)),
                                                      ChdImage.Track("AUDIO", audio.chunked(2352)))))

        assertEquals(expected, native.hashForConsole(image.path, PC_FX))
        val scanned = (planning.hashDetailed(image.path, "pcfx") as? HashOutcome.Ok ?: fail("no hash")).result
        assertEquals(hash to PC_FX, scanned.hash to scanned.consoleId)
    }

    // With the data track first and audio after it the same read lands on
    // the audio, and track 2 is audio as well. RetroAchievements' hasher
    // gives no hash for such an image, and neither does the reader, though
    // the cue sheet of the same disc is hashed: the answer is that
    // hasher's, kept where it is not the disc's.
    @Test
    fun `a PC-FX disc whose data track is the first gets no hash from a chd, as from RetroAchievements' hasher`() {
        val (data, hash) = pcFxTrack()
        val audio = noise(30 * 2352, seed = 30)
        file("First-1.bin", data); file("First-2.bin", audio)
        val sheet = file("First.cue",
                         ascii("FILE \"First-1.bin\" BINARY\r\n  TRACK 01 MODE1/2048\r\n    INDEX 01 00:00:00\r\n" +
                               "FILE \"First-2.bin\" BINARY\r\n  TRACK 02 AUDIO\r\n    INDEX 01 00:00:00\r\n"))
        assertEquals(HashOutcome.Ok(HashResult(hash, PC_FX)), native.hashForConsole(sheet.path, PC_FX),
                     "the cue sheet of the same disc")

        val image = file("First.chd", ChdImage.cd(listOf(ChdImage.Track("MODE1", data.chunked(2048)),
                                                         ChdImage.Track("AUDIO", audio.chunked(2352)))))

        val failed = native.hashForConsole(image.path, PC_FX) as? HashOutcome.Failed
            ?: fail("a hash for a disc RetroAchievements' hasher gives none for")
        assertTrue("Not a PC-FX CD" in failed.reason, failed.reason)
        assertEquals(HashOutcome.Failed("the hasher could not read First.chd: Not a PC-FX CD", retryable = false),
                     planning.hashDetailed(image.path, "pcfx"))
    }

    // What the reader refuses is a verdict to keep where the collection
    // says which console the disc is of: that console read the file and
    // will refuse it again. Left to guess it is not one, for a .chd as for
    // any file: a failure of the guess is tried again at every scan, and
    // each console on rcheevos' list has then opened the image and been
    // told the same, which is what the reason shows.
    @Test
    fun `left to guess, a chd the reader refuses is a failure tried again and not a verdict`() {
        val fake = file("Fake.chd", noise(4096, seed = 28))
        val refused = "CHD: the file does not begin as a CHD does; Could not open track"

        val guessed = planning.hashDetailed(fake.path, "attic") as? HashOutcome.Failed ?: fail("a hash for no CHD")
        assertTrue(guessed.retryable, "$guessed")
        assertTrue(guessed.reason.startsWith("the hasher could not read Fake.chd: $refused; $refused"), guessed.reason)

        assertEquals(HashOutcome.Failed("the hasher could not read Fake.chd: $refused", retryable = false),
                     planning.hashDetailed(fake.path, "ps2"), "the same file where the console is known")
    }

    // ---------------------------------------------------------------- helpers

    /** The library as a scan asks it: behind the plan made for the file's collection. */
    private val planning: ArchiveAwareHasher by lazy { ArchiveAwareHasher(native, File(dir, "tmp")) }

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
        val track = rawTrack(sectors)
        ascii("SEGADISCSYSTEM  CHDTEST    ").copyInto(track, 16)
        ascii("SEGA MEGA DRIVE ").copyInto(track, 16 + 0x100)
        return track
    }

    /**
     * A PC-FX data track of 24 plain sectors of 2048 bytes, and what a disc
     * made of it hashes to: the console's name opens sector 0, sector 1
     * says after 32 bytes of title that the program begins at sector 4 and
     * is two sectors long, and sector 16 opens as a volume descriptor
     * does, which is what rcheevos tells the size of a sheet's sectors by.
     */
    private fun pcFxTrack(): Pair<ByteArray, String> {
        val track = ByteArray(24 * 2048)
        val program = noise(2 * 2048, seed = 31)
        ascii("PC-FX:Hu_CD-ROM").copyInto(track, 0)
        ascii("CHD TEST").copyInto(track, 2048)
        byteArrayOf(4, 0, 0, 0, 2, 0, 0, 0).copyInto(track, 2048 + 32)
        program.copyInto(track, 4 * 2048)
        (byteArrayOf(1) + ascii("CD001") + byteArrayOf(1)).copyInto(track, 16 * 2048)
        return track to md5(track.copyOfRange(2048, 2048 + 128) + program)
    }

    /** Raw sectors of 2352 bytes, each with its sync pattern, its address and mode 1, and noise for its data. */
    private fun rawTrack(sectors: Int): ByteArray {
        fun bcd(n: Int) = ((n / 10) shl 4 or (n % 10)).toByte()
        val track = ByteArray(sectors * 2352)
        for (s in 0 until sectors) {
            val at = s * 2352
            track.fill(0xFF.toByte(), at + 1, at + 11)
            val lba = s + 150
            byteArrayOf(bcd(lba / 4500), bcd(lba / 75 % 60), bcd(lba % 75), 1).copyInto(track, at + 12)
            noise(2048, seed = 900 + s).copyInto(track, at + 16)
        }
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
        const val PC_FX = 49

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
