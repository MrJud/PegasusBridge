package com.pegasus.bridge.hasher

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Several entries read out of one archive in one go, which is what a disc
 * needs: its sheet and every track the sheet names.
 */
class ArchiveReaderTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("archivereader").toFile() }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val first = ByteArray(70_000) { (it * 7).toByte() }
    private val second = ByteArray(90_000) { (it * 13 + 1).toByte() }
    private val sheet = "FILE \"Track 1.bin\" BINARY\r\n".toByteArray()

    /** What [ask] picks out of the listing of [archive] is asked for, and what the sink was handed comes back. */
    private fun readMany(
        archive: File,
        ask: (List<ArchiveSelector.Entry>) -> List<ArchiveSelector.Entry>
    ): List<Pair<String, ByteArray>> = ArchiveReader.open(archive) { opened ->
        val listing = assertIs<ArchiveReader.Opened.Entries>(opened)
        val handed = mutableListOf<Pair<String, ByteArray>>()
        listing.readMany(ask(listing.entries)) { entry, input -> handed += entry.name to input.readBytes() }
        handed
    }

    private fun assertHanded(expected: List<Pair<String, ByteArray>>, handed: List<Pair<String, ByteArray>>) {
        assertEquals(expected.map { it.first }, handed.map { it.first })
        for ((want, got) in expected.zip(handed)) assertContentEquals(want.second, got.second, want.first)
    }

    // The sheet lies last, where 7-Zip puts it, and is asked for first: the
    // entries still come in the order they lie in, each with its own bytes,
    // and the one nobody asked for is not handed over.
    @Test fun `several entries of a solid 7z are read once each, in the order they lie in`() {
        val archive = SolidSevenZ.write(File(dir, "Disc.7z"),
            "Track 1.bin" to first, "Track 2.bin" to second, "Notes.bin" to ByteArray(5_000), "Disc.cue" to sheet)

        val handed = readMany(archive) { entries -> listOf(entries[3], entries[1], entries[0], entries[3]) }
        assertHanded(listOf("Track 1.bin" to first, "Track 2.bin" to second, "Disc.cue" to sheet), handed)

        // One entry, reached past the three before it.
        assertHanded(listOf("Disc.cue" to sheet), readMany(archive) { listOf(it[3]) })
    }

    @Test fun `several entries of a zip are read in the order they lie in`() {
        val archive = File(dir, "Disc.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, bytes) in listOf("Disc.cue" to sheet, "Track 1.bin" to first, "Track 2.bin" to second)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        assertHanded(listOf("Disc.cue" to sheet, "Track 2.bin" to second),
                     readMany(archive) { listOf(it[2], it[0]) })
    }

    // The two refusals a single read has hold for several: an entry is one
    // of this listing itself, and a zip entry that shares its name is not
    // read, because what came back could be the other one.
    @Test fun `an entry that is not of the listing, or shares its name in a zip, is not read`() {
        val archive = File(dir, "Twice.zip")
        ZipArchiveOutputStream(archive).use { out ->
            for ((name, bytes) in listOf("Disc.cue" to sheet, "Track 1.bin" to first, "Track 1.bin" to second)) {
                out.putArchiveEntry(ZipArchiveEntry(name)); out.write(bytes); out.closeArchiveEntry()
            }
        }
        val refused = assertFailsWith<IOException> { readMany(archive) { listOf(it[0], it[1]) } }
        assertEquals("Twice.zip holds 2 entries named 'Track 1.bin', and a zip entry can only be read by its name",
                     refused.message)

        val stranger = assertFailsWith<IllegalArgumentException> {
            readMany(archive) { listOf(ArchiveSelector.Entry("Disc.cue", sheet.size.toLong())) }
        }
        assertEquals("'Disc.cue' is not an entry of this listing", stranger.message)
    }
}
