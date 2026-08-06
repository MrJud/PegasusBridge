package com.pegasus.bridge.hasher

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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

    /** Stands in for rcheevos: returns a fixed hash, ignoring the bytes. */
    private class FixedHasher(private val value: String = "RCHEEVOS") : RomHasher {
        var lastPath: String? = null
        override fun hash(path: String): HashResult? {
            lastPath = path
            return if (File(path).exists()) HashResult(value, 7) else null
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

    private fun md5Of(f: File): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
}
