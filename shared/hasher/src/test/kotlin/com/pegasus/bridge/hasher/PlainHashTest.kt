package com.pegasus.bridge.hasher

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plain file hashes [ArchiveAwareHasher] adds alongside the rcheevos one,
 * for databases that match by file rather than by title.
 */
class PlainHashTest {

    private lateinit var dir: File
    private lateinit var tempDir: File

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("plainhash").toFile()
        tempDir = File(dir, "tmp")
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    /**
     * Stands in for rcheevos: returns a fixed hash, and records the file it was
     * handed — its path, and its bytes at that moment, since a temporary copy is
     * gone by the time a test can look.
     */
    private class FixedHasher(private val value: String = "RCHEEVOS") : RomHasher {
        var lastPath: String? = null
        var lastBytes: ByteArray? = null
        var calls = 0
        override fun hash(path: String): HashResult? {
            calls++
            lastPath = path
            val f = File(path)
            if (!f.exists()) return null
            lastBytes = f.readBytes()
            return HashResult(value, 7)
        }
    }

    // "abc" has well-known digests, so these are checked against constants
    // rather than against a second implementation of the same arithmetic.
    private val abcMd5 = "900150983cd24fb0d6963f7d28e17f72"
    private val abcCrc = "352441c2"

    @Test
    fun `plain md5 and crc are computed for a bare file`() {
        val rom = File(dir, "game.gb").apply { writeText("abc") }
        val r = ArchiveAwareHasher(FixedHasher(), tempDir).hash(rom.absolutePath)!!
        assertEquals("RCHEEVOS", r.hash)
        assertEquals(abcMd5, r.fileMd5)
        assertEquals(abcCrc, r.fileCrc32)
    }

    @Test
    fun `the rcheevos hash is left untouched`() {
        val rom = File(dir, "game.gb").apply { writeText("abc") }
        val r = ArchiveAwareHasher(FixedHasher("NOT-AN-MD5"), tempDir).hash(rom.absolutePath)!!
        assertEquals("NOT-AN-MD5", r.hash)
        assertNotEquals(r.hash, r.fileMd5)
    }

    @Test
    fun `an archive hashes its contents, not the container`() {
        val rom = File(dir, "game.zip")
        ZipOutputStream(rom.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("game.gb"))
            zos.write("abc".toByteArray())
            zos.closeEntry()
        }
        val r = ArchiveAwareHasher(FixedHasher(), tempDir).hash(rom.absolutePath)!!
        // The inner ROM's digests, never the zip's own — a scraper asked about
        // the container would match nothing.
        assertEquals(abcMd5, r.fileMd5)
        assertEquals(abcCrc, r.fileCrc32)
        assertNotEquals(md5Of(rom), r.fileMd5)
    }

    // rcheevos picks its algorithm from the extension. Handed `.bin`, this would be
    // hashed whole, the way a Mega Drive cartridge is — iNES header included, which
    // `.nes` would have skipped — and the hash would match nothing.
    @Test
    fun `an extracted ROM keeps the extension rcheevos chooses its algorithm by`() {
        val rom = zip("game.zip", "folder/game.NES" to "abc".toByteArray())
        val native = FixedHasher()
        val r = ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)!!
        val handed = File(native.lastPath!!)
        assertEquals("nes", handed.extension)
        assertEquals(tempDir.canonicalFile, handed.parentFile.canonicalFile)
        // Copied and digested in one pass: the copy must still be whole, and
        // closed, by the time rcheevos opens it.
        assertContentEquals("abc".toByteArray(), native.lastBytes)
        assertEquals(abcMd5, r.fileMd5)
        assertEquals(abcCrc, r.fileCrc32)
        assertEquals("folder/game.NES", r.archiveEntry)
        assertFalse(handed.exists(), "temporary ROM is removed after hashing")
    }

    // The other side of the same rule: rcheevos has no handler for `.img`, and
    // would hash the whole image. Named `.bin`, a raw disc image over 32 MiB is
    // tried as a CD track first; GoldenHashTest shows the hash that gives.
    @Test
    fun `a raw disc image named img is handed to rcheevos as a bin`() {
        val rom = zip("Disc.zip", "Disc.img" to "abc".toByteArray())
        val native = FixedHasher()
        val r = ArchiveAwareHasher(native, tempDir).hashDetailed(rom.absolutePath, "psx")
        assertEquals("bin", File(native.lastPath!!).extension)
        assertContentEquals("abc".toByteArray(), native.lastBytes)
        assertEquals("Disc.img", (r as HashOutcome.Ok).result.archiveEntry)
    }

    // A descriptor taken out alone leaves its tracks behind, so rcheevos cannot
    // hash it. That is known from the listing: nothing is copied out (the
    // temporary directory is only made for a copy) and rcheevos is not asked.
    @Test
    fun `a disc descriptor in an archive is not extracted or handed to rcheevos`() {
        for ((descriptor, platform) in listOf("Disc.cue" to "psx", "Disc.gdi" to "dreamcast",
                                              "Disc.m3u" to "", "Disc.ccd" to "segacd", "Disc.toc" to "saturn")) {
            val rom = zip("${descriptor.substringAfter('.')}.zip",
                          descriptor to "FILE \"Disc.bin\" BINARY".toByteArray(),
                          "Disc.bin" to ByteArray(4096))
            val native = FixedHasher()
            val outcome = ArchiveAwareHasher(native, tempDir).hashDetailed(rom.absolutePath, platform)
            assertEquals(HashOutcome.Failed(ArchiveAwareHasher.DESCRIPTOR_IN_ARCHIVE, retryable = false),
                         outcome, descriptor)
            assertEquals(0, native.calls, descriptor)
            assertFalse(tempDir.exists(), "$descriptor was copied out")
        }
    }

    // Two entries called `game.nes`, an empty leftover first. The selector refuses
    // the empty one; finding its choice again by name used to land on the leftover
    // and record the digest of nothing as the ROM's.
    @Test
    fun `a 7z hands over the entry the selector chose, not the first with its name`() {
        val rom = sevenZ("game.7z", "game.nes" to ByteArray(0), "game.nes" to "abc".toByteArray())
        val native = FixedHasher()
        val r = ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)!!
        assertContentEquals("abc".toByteArray(), native.lastBytes)
        assertEquals("nes", File(native.lastPath!!).extension)
        assertEquals(abcMd5, r.fileMd5)
        assertEquals(abcCrc, r.fileCrc32)
    }

    // The same pair in a zip, ROM first. java.util.zip finds an entry by name and
    // answers with the last, so the leftover went to rcheevos as the ROM, and its
    // empty digest was recorded. The zip is refused instead, and says why.
    @Test
    fun `a zip whose chosen entry shares its name is refused, not read as the other`() {
        val rom = File(dir, "game.zip").also { f ->
            ZipArchiveOutputStream(f).use { out ->
                for (bytes in listOf("abc".toByteArray(), ByteArray(0))) {
                    out.putArchiveEntry(ZipArchiveEntry("game.nes")); out.write(bytes); out.closeArchiveEntry()
                }
            }
        }
        val native = FixedHasher()
        val outcome = ArchiveAwareHasher(native, tempDir).hashDetailed(rom.absolutePath, "nes")
        assertEquals(HashOutcome.Failed("could not extract 'game.nes': game.zip holds 2 entries named " +
                                        "'game.nes', and a zip entry can only be read by its name"), outcome)
        assertEquals(0, native.calls)
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `archive cancellation is propagated and removes the temporary ROM`() {
        val rom = zip("game.zip", "game.gb" to "abc".toByteArray())
        var calls = 0
        val native = object : RomHasher {
            override fun hash(path: String): HashResult? {
                calls++
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertFailsWith<CancellationException> {
            ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)
        }
        assertEquals(1, calls, "cancellation must not retry the archive as a bare file")
        assertTrue(tempDir.listFiles()!!.isEmpty())
    }

    // How runInterruptible delivers a cancelled scan. The copy checks before every
    // 64 KiB, so it stops before rcheevos is reached at all.
    @Test
    fun `an interrupt stops the extraction and leaves nothing behind`() {
        val rom = zip("game.zip", "game.gb" to ByteArray(200_000))
        val native = FixedHasher()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> {
                ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, native.calls, "neither the entry nor the container reaches the hasher")
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    // A 7z is read through a FileChannel, which an interrupt closes. That arrives
    // as an IOException while listing — which used to mean "not an archive", and
    // was answered by hashing the whole container.
    @Test
    fun `an interrupt while opening a 7z is not taken for an unreadable archive`() {
        val rom = sevenZ("game.7z", "game.gb" to "abc".toByteArray())
        val native = FixedHasher()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> {
                ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath)
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, native.calls)
    }

    // It was hashed as it lay, on the reasoning that an extension is a claim
    // and a renamed ROM a real game. But what rcheevos is handed is still
    // called `.7z` or `.zip`, and it hashes the name of such a file as an
    // arcade set's: the answer was never the ROM's.
    @Test
    fun `a ROM misnamed as an archive is refused, not hashed as a container`() {
        for (name in listOf("notreally.7z", "notreally.zip")) {
            val rom = File(dir, name).apply { writeText("abc") }
            val native = FixedHasher()
            val outcome = ArchiveAwareHasher(native, tempDir).hashDetailed(rom.absolutePath, "nes")
            assertTrue(outcome is HashOutcome.Failed && !outcome.retryable &&
                       outcome.reason.startsWith("not a readable archive: "), "$name: $outcome")
            assertEquals(0, native.calls, "$name was handed to rcheevos")
            assertNull(ArchiveAwareHasher(native, tempDir).hash(rom.absolutePath), "$name through hash(path)")
        }
    }

    // A file that would not open is another matter from an archive that
    // would not: nothing of it has been seen. One the scan is not allowed to
    // read, or one on a card taken out a moment ago, may be as good a game
    // as any, and kept as a file that cannot be hashed it was not looked at
    // again for a month.
    @Test
    fun `an archive that could not be opened at all is tried again`() {
        val locked = zip("Locked.zip", "Locked.nes" to "abc".toByteArray())
        if (!locked.setReadable(false, false) || runCatching { locked.inputStream().close() }.isSuccess) {
            println("PlainHashTest: no file could be made unreadable here, so none was tried")
            return
        }
        val native = FixedHasher()
        val outcome = ArchiveAwareHasher(native, tempDir).hashDetailed(locked.absolutePath, "nes")
        assertTrue(outcome is HashOutcome.Failed && outcome.retryable &&
                   outcome.reason.startsWith("the archive could not be opened: "), "$outcome")
        assertEquals(0, native.calls, "it was handed to rcheevos")
    }

    // An archive that opens and has no game in it for its platform was the
    // other file hashed as a container. It is its own answer now, and says
    // what the archive does hold.
    @Test
    fun `an archive with no game in it says what it holds and is not hashed`() {
        val native = FixedHasher()
        val hasher = ArchiveAwareHasher(native, tempDir)

        val patch = zip("patch.zip", "fix/patch.7z" to "abc".toByteArray(), "notes.txt" to "abc".toByteArray())
        assertEquals(HashOutcome.NoPlayableEntry("nothing in the archive is a game of this collection: patch.7z"),
                     hasher.hashDetailed(patch.absolutePath, "ps2"))

        val many = zip("many.zip", *(1..7).map { "chip$it.rom" to "abc".toByteArray() }.toTypedArray())
        assertEquals(HashOutcome.NoPlayableEntry("nothing in the archive is a game of this collection: " +
                                                 "chip1.rom, chip2.rom, chip3.rom, chip4.rom, chip5.rom, and 2 more"),
                     hasher.hashDetailed(many.absolutePath, "nes"))

        // Only a readme, and nothing at all.
        val readme = zip("readme.zip", "readme.txt" to "abc".toByteArray())
        assertEquals(HashOutcome.NoPlayableEntry("nothing in the archive is a game of this collection: readme.txt"),
                     hasher.hashDetailed(readme.absolutePath, "nes"))
        val empty = zip("empty.zip")
        assertEquals(HashOutcome.NoPlayableEntry("the archive holds no file"),
                     hasher.hashDetailed(empty.absolutePath, "nes"))

        assertEquals(0, native.calls)
        assertFalse(tempDir.exists(), "an entry was copied out")
    }

    // The entry an archive holds for its platform is planned for by its own
    // name, as a file lying loose is. A packed disc image that has been
    // zipped as well was copied out and handed over, and what came back was
    // the hash of the image's container.
    @Test
    fun `an entry of a format nobody reads is not copied out`() {
        val native = FixedHasher()
        val hasher = ArchiveAwareHasher(native, tempDir)

        val packed = zip("Disc.zip", "Disc.chd" to "abc".toByteArray(), "readme.txt" to "abc".toByteArray())
        assertEquals(HashOutcome.UnsupportedFormat("'Disc.chd' in the archive: " +
                                                   ".chd is a format this build has no reader for"),
                     hasher.hashDetailed(packed.absolutePath, "ps2"))
        val converted = zip("Other.zip", "Other.cso" to "abc".toByteArray())
        assertEquals(HashOutcome.UnsupportedFormat("'Other.cso' in the archive: .cso is a format rcheevos does not read"),
                     hasher.hashDetailed(converted.absolutePath, "psp"))

        assertEquals(0, native.calls)
        assertFalse(tempDir.exists(), "an entry was copied out")
    }

    // A caller that is not a scan and hands over a file a scan would have
    // turned away gets the same answer, and the file is not read.
    @Test
    fun `a file or a collection nobody can hash is refused by the hasher too`() {
        val native = FixedHasher()
        val hasher = ArchiveAwareHasher(native, tempDir)
        val wrong = listOf(
            Triple("ps2", "Disc.chd", ".chd is a format this build has no reader for"),
            Triple("arcade", "chip.bin", "an arcade set is a .zip or a .7z, and this is a .bin"),
            Triple("switch", "Game.zip", "RetroAchievements has no console for switch"),
            Triple("amiga", "Disk.adf", "rcheevos has no hashing algorithm for RC_CONSOLE_AMIGA (id 35)")
        ).mapNotNull { (platform, name, reason) ->
            val file = File(dir, name).apply { writeText("abc") }
            val outcome = hasher.hashDetailed(file.absolutePath, platform)
            if (outcome == HashOutcome.UnsupportedFormat(reason)) null else "$platform/$name: $outcome"
        }
        assertEquals(emptyList(), wrong)
        assertEquals(0, native.calls)
    }

    @Test
    fun `no result means no hashes`() {
        val hasher = ArchiveAwareHasher(FixedHasher(), tempDir)
        assertNull(hasher.hash(File(dir, "missing.gb").absolutePath))
    }

    @Test
    fun `hashes cover the whole file, not a prefix`() {
        val a = File(dir, "a.gb").apply { writeBytes(ByteArray(200_000) { 0 }) }
        val b = File(dir, "b.gb").apply {
            writeBytes(ByteArray(200_000) { 0 }.also { it[199_999] = 1 })
        }
        val hasher = ArchiveAwareHasher(FixedHasher(), tempDir)
        val ra = hasher.hash(a.absolutePath)!!
        val rb = hasher.hash(b.absolutePath)!!
        // Differing only in the last byte, across several read buffers.
        assertNotEquals(ra.fileMd5, rb.fileMd5)
        assertNotEquals(ra.fileCrc32, rb.fileCrc32)
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File =
        File(dir, name).also { f ->
            ZipOutputStream(f.outputStream()).use { zos ->
                for ((entry, bytes) in entries) {
                    zos.putNextEntry(ZipEntry(entry)); zos.write(bytes); zos.closeEntry()
                }
            }
        }

    /** Unlike ZipOutputStream, this lets two entries share a name. */
    private fun sevenZ(name: String, vararg entries: Pair<String, ByteArray>): File =
        File(dir, name).also { f ->
            SevenZOutputFile(f).use { out ->
                for ((entry, bytes) in entries) {
                    out.putArchiveEntry(SevenZArchiveEntry().apply { this.name = entry })
                    out.write(bytes)
                    out.closeArchiveEntry()
                }
            }
        }

    private fun md5Of(f: File): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
}
