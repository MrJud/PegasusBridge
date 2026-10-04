package com.pegasus.bridge.hasher

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
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

    @Test
    fun `a ROM misnamed as an archive still gets its own hashes`() {
        // The extraction fails and the file is hashed as-is; the plain hashes
        // must describe that same fallback content.
        val rom = File(dir, "notreally.7z").apply { writeText("abc") }
        val r = ArchiveAwareHasher(FixedHasher(), tempDir).hash(rom.absolutePath)!!
        assertEquals(abcMd5, r.fileMd5)
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
