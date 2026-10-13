package com.pegasus.bridge.hasher

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A Wii disc in a `.wbfs` container, hashed through the library as the daemon
 * hashes it.
 *
 * The reader is C (`pb_wbfs.c`), and the files made to hurt it are rows of
 * `shared/tests/native_repro_test.py`, run on a sanitized build with no JVM
 * around them. What is held here is what those rows cannot see: that the
 * library, as the JVM loads it on each system, gives a container the hash it
 * gives the disc, that the two numbers it counts come back as the note they
 * are meant to be, and that two files hashed at once share nothing.
 *
 * No hash is worked out here. The disc is made of filler, and what it has to
 * hash to is what the library answers for the same bytes as an `.iso`, which
 * rcheevos reads by itself and the golden rows hold.
 */
class NativeWbfsTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("native-wbfs").toFile() }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    // ------------------------------------------------------------- hashing

    @Test
    fun `a disc and the same disc in a wbfs hash alike`() {
        val expected = hashOfIso("Disc.iso", disc)

        val held = native.hashForConsole(file("Disc.wbfs", wbfs(blocksOf(disc))).path, WII)

        assertEquals(HashOutcome.Ok(HashResult(expected, WII)), held, "every block held: no note")
    }

    // Real files are split a little short of 4 GiB, which is no boundary of
    // a block. This one is cut inside the block of the game's first cluster.
    @Test
    fun `a wbfs split in two parts hashes as the whole one`() {
        val container = wbfs(blocksOf(disc))
        val cut = (FIRST_BLOCK + 11) * BLOCK + 0x4000
        val first = file("Split.wbfs", container.copyOfRange(0, cut))
        file("Split.wbf1", container.copyOfRange(cut, container.size))

        assertEquals(HashOutcome.Ok(HashResult(hashOfIso("Disc.iso", disc), WII)),
                     native.hashForConsole(first.path, WII))
    }

    // Dolphin makes no case of a block the table has 0 for: it reads the
    // container's own first block at the same place. Block 200 of the disc
    // is held here, which nothing reads, so that its entry in the table lies
    // under the bytes read in place of the data: without it they would be
    // zeros, and a reader that answered zeros would pass.
    @Test
    fun `blocks the container does not hold are read as Dolphin reads them`() {
        val blocks = blocksOf(disc) - setOf(11, 12) + (200 to noise(BLOCK, seed = 7))
        val container = wbfs(blocks)
        val asDolphinReads = disc.copyOf().also {
            container.copyInto(it, 11 * BLOCK, 0, BLOCK)
            container.copyInto(it, 12 * BLOCK, 0, BLOCK)
        }
        val withZeros = disc.copyOf().also { it.fill(0, 11 * BLOCK, 13 * BLOCK) }
        val expected = hashOfIso("AsRead.iso", asDolphinReads)
        assertNotEquals(hashOfIso("Zeros.iso", withZeros), expected, "the fixture tells the two readings apart")

        val result = ok(native.hashForConsole(file("Scrubbed.wbfs", container).path, WII))

        assertEquals(expected, result.hash)
        assertTrue(result.caveat.startsWith(RcheevosNative.SCRUBBED), result.caveat)
    }

    @Test
    fun `one block of two is not a scrubbed copy`() {
        val result = ok(native.hashForConsole(file("Half.wbfs", wbfs(blocksOf(disc) - 12)).path, WII))

        assertNotEquals(hashOfIso("Disc.iso", disc), result.hash)
        assertTrue(result.caveat.isNotEmpty(), "a block that was hashed is not held")
        assertFalse(result.caveat.startsWith(RcheevosNative.SCRUBBED), result.caveat)
        assertTrue(result.caveat.endsWith("not the hash of a full copy"), result.caveat)
    }

    // ------------------------------------------------------------ refusals

    @Test
    fun `a container cut short, misnumbered or pointing outside itself is refused with a reason`() {
        val good = wbfs(blocksOf(disc))
        fun spoiled(at: Int, vararg bytes: Int) = good.copyOf().also { image ->
            bytes.forEachIndexed { index, byte -> image[at + index] = byte.toByte() }
        }
        val table = SECTOR + 0x100
        val cases = listOf(
            Triple("of 15 bytes", good.copyOf(15), "ends inside its header"),
            Triple("that begins WBFT", spoiled(3, 'T'.code), "does not begin"),
            Triple("with a sector of one byte", spoiled(8, 0), "a sector of"),
            Triple("with a block of 2^255 bytes", spoiled(9, 255), "a block of"),
            Triple("that counts no sectors", spoiled(4, 0, 0, 0, 0), "counts no sectors"),
            Triple("whose first slot is empty", spoiled(12, 0), "holds no disc"),
            Triple("longer than its header says", good + ByteArray(512), "its header says"),
            Triple("that ends inside its table", spoiled(4, 0, 0, 0, 8).copyOf(0x1000), "block table"),
            Triple("whose table points past the end", spoiled(table + 2 * 20, 0xFF, 0xFF), "past the end"),
            Triple("without the disc's first block", spoiled(table, 0, 0), "first block of the disc"),
        )

        for ((index, case) in cases.withIndex()) {
            val (what, image, words) = case
            val outcome = native.hashForConsole(file("Refused $index.wbfs", image).path, WII)
            val failed = assertIs<HashOutcome.Failed>(outcome, "a container $what")
            assertTrue(words in failed.reason, "a container $what: ${failed.reason}")
            assertFalse(RcheevosNative.isTransient(failed.reason),
                        "a container $what answers the same tomorrow: ${failed.reason}")
        }
    }

    // A part that is not there may be one still being copied, so it is said
    // in the words of a file that could not be opened, and a scan asks again.
    @Test
    fun `a missing part is a failure to try again`() {
        val container = wbfs(blocksOf(disc))
        val first = file("Lone.wbfs", container.copyOf(container.size / 2))

        val failed = assertIs<HashOutcome.Failed>(native.hashForConsole(first.path, WII))

        assertTrue("Could not open part .wbf1" in failed.reason, failed.reason)
        assertTrue(RcheevosNative.isTransient(failed.reason), failed.reason)
    }

    // Left to the extension rcheevos has no console to try for a .wbfs. The
    // reader takes 0 to ask for the Wii, and any other console is the
    // caller's mistake, said here and not as a verdict on the file.
    @Test
    fun `a wbfs is a Wii disc whatever console is asked`() {
        val container = file("Disc.wbfs", wbfs(blocksOf(disc)))
        val expected = hashOfIso("Disc.iso", disc)

        assertEquals(HashOutcome.Ok(HashResult(expected, WII)), native.hashForConsole(container.path, 0))
        assertEquals(expected, native.hash(container.path)?.hash, "hash(path), which is console 0")
        val failed = assertIs<HashOutcome.Failed>(native.hashForConsole(container.path, 16))
        assertTrue("A .wbfs holds a Wii disc, and console 16 was asked for" in failed.reason, failed.reason)
    }

    // The second file's name is made in C, from the bytes of the first one's:
    // the last byte gives way to a digit. Characters of three and of four
    // bytes are before it, and have to come through whole.
    @Test
    fun `the parts of a name outside ASCII are found`() {
        val container = wbfs(blocksOf(disc))
        val cut = (FIRST_BLOCK + 3) * BLOCK + 5
        val name = "ディスク 🎮 円盤"
        val first = file("$name.wbfs", container.copyOfRange(0, cut))
        file("$name.wbf1", container.copyOfRange(cut, container.size))

        assertEquals(HashOutcome.Ok(HashResult(hashOfIso("Disc.iso", disc), WII)),
                     native.hashForConsole(first.path, WII))
    }

    // One block of 2 MiB, which holds the whole disc, at block 2200 of the
    // file: 4.3 GiB in, where an offset kept in 32 bits is back at the start.
    // The file is made sparse, by asking for that where the system has to be
    // asked, and takes the room of what is written.
    @Test
    fun `a block past 4 GiB of the container is read from where it is`() {
        val shift = 21
        val far = 2200
        val head = wbfs(mapOf(0 to disc), shift = shift, first = far, headOnly = true)
        val path = File(dir, "Far.wbfs").toPath()
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                         StandardOpenOption.SPARSE).use { channel ->
            channel.write(ByteBuffer.wrap(head), 0)
            channel.write(ByteBuffer.wrap(disc), far.toLong() shl shift)
            // The last byte of the block, so that the file is as long as its header says.
            channel.write(ByteBuffer.wrap(ByteArray(1)), ((far + 1).toLong() shl shift) - 1)
        }
        assertTrue(Files.size(path) > 1L shl 32)

        assertEquals(HashOutcome.Ok(HashResult(hashOfIso("Disc.iso", disc), WII)),
                     native.hashForConsole(path.toString(), WII))
    }

    // ------------------------------------------------------------- threads

    // rcheevos' callback for opening a file is given a path and nothing
    // else, so the reader keeps the container it is reading where the
    // callback can find it. Were that one place for the whole process, a
    // scan's four workers would read each other's discs: a hash of the wrong
    // file, or of a container already freed. Two containers that answer
    // unlike in everything, hashed by eight threads at once.
    //
    // The moment in which one call could take another's container is short
    // beside the call, most of which is reading the table. So both are in
    // blocks of 2 MiB, whose table is 9 KiB where that of 32 KiB blocks is
    // 560, and there are some thousands of calls: with one place for all
    // threads the JVM of the test ended, both times it was tried. The second
    // disc has its data in its second block, which the container does not
    // hold.
    @Test
    fun `two containers hashed at once on several threads each get their own hash and counts`() {
        val large = 21
        val later = discWith(data = 1 shl large)
        val whole = file("Whole.wbfs", wbfs(blocksOf(disc, large), shift = large))
        val half = file("Half.wbfs", wbfs(blocksOf(later, large) - 1, shift = large))
        val expected = listOf(whole, half).associateWith { native.hashForConsole(it.path, WII) }
        val (one, other) = expected.values.map { ok(it) }
        assertNotEquals(one.hash, other.hash)
        assertNotEquals(one.caveat, other.caveat)

        val start = CountDownLatch(1)
        val wrong = java.util.Collections.synchronizedList(ArrayList<String>())
        val threads = (0 until 8).map { number ->
            thread {
                val mine = if (number % 2 == 0) whole else half
                // A thread that ends on something thrown has made no call that
                // answered wrong, and is not thereby one that went well.
                try {
                    start.await()
                    repeat(500) {
                        val outcome = native.hashForConsole(mine.path, WII)
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

    // -------------------------------------------------- what the answer says

    // The library answers two fields, or four for a .wbfs. An answer of any
    // other shape is a library this code was not written for, and taking the
    // first two fields of it for a hash would drop what the rest was saying.
    @Test
    fun `an answer of three fields is no hash`() {
        val md5 = "0123456789abcdef0123456789abcdef"

        assertEquals(HashOutcome.Ok(HashResult(md5, 7)), RcheevosNative.parsed("$md5|7"))
        assertEquals(HashOutcome.Ok(HashResult(md5, 19)), RcheevosNative.parsed("$md5|19|64209|0"))
        assertTrue(ok(RcheevosNative.parsed("$md5|19|64209|63488")).caveat.startsWith(RcheevosNative.SCRUBBED))
        for (answer in listOf("$md5|19|64209", "$md5|19|64209|0|0", "$md5|19|many|0", "$md5|19|64209|-1",
                              "$md5|19||", "$md5", "|19", "$md5|nineteen")) {
            val failed = assertIs<HashOutcome.Failed>(RcheevosNative.parsed(answer), answer)
            assertTrue("which is no hash and console" in failed.reason, failed.reason)
        }
    }

    @Test
    fun `the note on a wbfs says how much of what was hashed the container does not hold`() {
        assertEquals("", RcheevosNative.wbfsCaveat(64209, 0), "no such byte was read")

        val most = RcheevosNative.wbfsCaveat(64209, 63488)
        assertTrue(most.startsWith("scrubbed copy: "), most)

        val some = RcheevosNative.wbfsCaveat(64209, 31744)
        assertFalse(some.startsWith("scrubbed copy"), some)
        assertTrue(some.endsWith("not the hash of a full copy"), some)

        // As a disc of the size they come in reads: megabytes with one decimal, and a point for it
        // whatever the language of the system.
        assertEquals("scrubbed copy: 29.1 of 31.7 MiB hashed are blocks the .wbfs does not hold; " +
                     "RetroAchievements knows a disc by a full copy",
                     RcheevosNative.wbfsCaveat(33_239_859, 30_513_562))
        assertEquals("0.5 of 31.7 MiB hashed are blocks the .wbfs does not hold: not the hash of a full copy",
                     RcheevosNative.wbfsCaveat(33_239_859, 524_288))
        // One cluster of a block that is not held is some thirty kilobytes, which a megabyte to one
        // decimal writes as 0.0: none, of bytes that there are and that made the hash another.
        assertEquals("under 0.1 of 31.7 MiB hashed are blocks the .wbfs does not hold: not the hash of a full copy",
                     RcheevosNative.wbfsCaveat(33_239_859, 31_744))
        assertEquals("0.1 of 31.7 MiB hashed are blocks the .wbfs does not hold: not the hash of a full copy",
                     RcheevosNative.wbfsCaveat(33_239_859, 2 * 31_744))
        // Half is where the word begins.
        assertTrue(RcheevosNative.wbfsCaveat(100, 50).startsWith("scrubbed copy"))
        assertFalse(RcheevosNative.wbfsCaveat(101, 50).startsWith("scrubbed copy"))
    }

    // ---------------------------------------------------------------- helpers

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    private fun ok(outcome: HashOutcome): HashResult = assertIs<HashOutcome.Ok>(outcome, "$outcome").result

    /** What the library hashes these bytes to as a plain image of a Wii disc. */
    private fun hashOfIso(name: String, image: ByteArray): String {
        val result = ok(native.hashForConsole(file(name, image).path, WII))
        assertEquals("", result.caveat, "a plain image has no note")
        return result.hash
    }

    /**
     * A Wii disc with every table inside the file, laid out as the one
     * `native_repro_test.py` makes: the magic word at 0x18, a 0 at 0x61 for a
     * disc that is encrypted, the table of partitions at 0x40000 naming an
     * update partition, which is never read, and the game's at 0x50000; the
     * region code at 0x4E000; the game's title metadata behind its header,
     * and its data, two clusters of 0x8000, at 0x58000, which is the disc's
     * blocks 11 and 12 when a block is 32 KiB. The file is 0x20 bytes longer
     * than a whole number of sectors, so that no console that reads a disc
     * by its sectors opens it.
     */
    private val disc: ByteArray by lazy { discWith(data = 0x58000) }

    /** That disc with the game's data at [data], which is then where the file ends. */
    private fun discWith(data: Int): ByteArray {
        fun be(value: Int) = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }
        val partition = 0x50000
        return ByteArray(data + 0x18020).also { image ->
            noise(0x80, seed = 1).copyInto(image, 0)
            be(0x5D1C9EA3.toInt()).copyInto(image, 0x18)
            image[0x61] = 0
            noise(4, seed = 2).copyInto(image, 0x4E000)
            (be(2) + be(0x40020 shr 2)).copyInto(image, 0x40000)
            (be(0x60000 shr 2) + be(1) + be(partition shr 2) + be(0)).copyInto(image, 0x40020)
            (be(0x208) + be(0x2C0 shr 2)).copyInto(image, partition + 0x2A4)
            noise(0x208, seed = 3).copyInto(image, partition + 0x2C0)
            (be(data shr 2) + be(0x10000 shr 2)).copyInto(image, partition + 0x2B8)
            noise(0x10000, seed = 4).copyInto(image, data)
        }
    }

    /** [image] as the blocks of 2^[shift] bytes a container keeps it in, by their number in the disc. */
    private fun blocksOf(image: ByteArray, shift: Int = 15): Map<Int, ByteArray> {
        val block = 1 shl shift
        return (0 until (image.size + block - 1) / block).associateWith {
            image.copyOfRange(it * block, minOf(image.size, (it + 1) * block))
        }
    }

    /**
     * A `.wbfs` that holds these blocks of a disc: the header, a sector on
     * the table, which has 16 bits for every block of a whole disc and says
     * which block of the container holds it, or 0 for one that is not held;
     * then the blocks one after another, from the first block past the table
     * or from [first]. With [headOnly], no more than the header and the
     * table, for a file whose blocks are written where they belong by hand.
     */
    private fun wbfs(blocks: Map<Int, ByteArray>, shift: Int = 15, first: Int? = null,
                     headOnly: Boolean = false): ByteArray {
        val block = 1 shl shift
        val entries = ((WHOLE_DISC + block - 1) shr shift).toInt()
        val tableAt = SECTOR + 0x100
        val from = first ?: ((tableAt + 2 * entries + block - 1) shr shift)
        val order = blocks.keys.sorted()
        val size = (from + order.size).toLong() shl shift
        val image = ByteArray(if (headOnly) tableAt + 2 * entries else size.toInt())
        "WBFS".toByteArray(Charsets.ISO_8859_1).copyInto(image, 0)
        val sectors = size shr SECTOR_SHIFT
        for (byte in 0 until 4) image[4 + byte] = (sectors ushr (24 - 8 * byte)).toByte()
        image[8] = SECTOR_SHIFT.toByte()
        image[9] = shift.toByte()
        image[12] = 1
        order.forEachIndexed { place, number ->
            image[tableAt + 2 * number] = ((from + place) ushr 8).toByte()
            image[tableAt + 2 * number + 1] = (from + place).toByte()
            if (!headOnly) blocks.getValue(number).copyInto(image, (from + place) * block)
        }
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

    private companion object {
        const val WII = 19

        /** A sector of the container, which is 512 bytes in every file there is. */
        const val SECTOR_SHIFT = 9
        const val SECTOR = 1 shl SECTOR_SHIFT

        /** A block of 32 KiB, the least there is, and the container block the first one held is in. */
        const val BLOCK = 0x8000
        const val FIRST_BLOCK = 18

        /** What a Wii disc is said to hold, whatever is on it: the table has an entry for each block of it. */
        const val WHOLE_DISC = 286864L * 0x8000

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
