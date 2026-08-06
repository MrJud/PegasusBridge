package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RomScanPipelineTest {

    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths

    /**
     * Hashes a file to its own content, so tests control matching exactly.
     *
     * It also fills the plain hashes, because in production every hasher reaches
     * the pipeline wrapped in [ArchiveAwareHasher], which supplies them. A double
     * that left them empty would look like pre-plain-hash metadata and be
     * rescanned forever.
     */
    private class ContentHasher : RomHasher {
        val calls = AtomicInteger()
        override fun hash(path: String): HashResult? {
            calls.incrementAndGet()
            val f = File(path)
            if (!f.exists()) return null
            val text = f.readText().trim()
            if (text == "UNHASHABLE") return null
            return HashResult(text, 7, fileMd5 = "md5-$text", fileCrc32 = "crc-$text")
        }
    }

    private class MapLookup(private val map: Map<String, GameMetadata>) : RaHashLookup {
        val calls = AtomicInteger()
        override suspend fun lookup(hash: String): GameMetadata? {
            calls.incrementAndGet()
            return map[hash] ?: GameMetadata(gameId = 0)
        }
    }

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("hasher-data").toFile()
        romRoot  = Files.createTempDirectory("hasher-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun rom(platform: String, name: String, content: String): File {
        val dir = File(romRoot, platform).apply { mkdirs() }
        return File(dir, name).apply { writeText(content) }
    }

    private val catalogue = mapOf(
        "hash-smb"  to GameMetadata(1446, "Super Mario Bros.", "NES", "/Images/1.png", 76),
        "hash-ctra" to GameMetadata(1447, "Contra", "NES", "/Images/2.png", 40)
    )

    private fun pipeline(h: RomHasher, l: RaHashLookup) =
        RomScanPipeline(paths, h, l, throttleMs = { 0L })

    @Test fun `matched roms produce metadata and a discovery index`() = runBlocking {
        rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        rom("nes", "Contra (USA).nes", "hash-ctra")

        val s = pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        assertEquals(2, s.total)
        assertEquals(2, s.newEntries)
        assertEquals(2, s.indexed)

        val meta = JSONObject(paths.metadata("1446").readText())
        assertEquals("Super Mario Bros.", meta.getString("title"))
        assertEquals("nes", meta.getString("platform"))
        assertEquals(76, meta.getJSONObject("ra").getInt("total"))
        assertEquals("hash-smb", meta.getJSONObject("rom").getString("hash"))

        val index = JSONObject(paths.discoveryIndex.readText())
        assertEquals(2, index.getInt("count"))
        assertTrue(index.getJSONObject("byKey").has("supermariobros|nes"),
                   "reverse lookup key missing: ${index.getJSONObject("byKey").keys().asSequence().toList()}")
    }

    // RA's dorequest can answer Success with an id the Web API does not know: a
    // Virtual Console Metroid dump returns 1100001487, whose GetGameExtended is
    // empty. Without a title there is no usable match, so nothing should be
    // written and the count must not include it.
    @Test fun `an id with no title is not treated as a match`() = runBlocking {
        rom("nes", "Metroid (Europe) (Virtual Console).nes", "hash-phantom")
        val phantom = object : RaHashLookup {
            override suspend fun lookup(hash: String) = GameMetadata(gameId = 1100001487)
        }

        val s = pipeline(ContentHasher(), phantom).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.total)
        assertEquals(0, s.newEntries, "a titleless id must not count as a new entry")
        assertEquals(0, s.indexed)
        assertEquals(0, paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.size,
                     "no junk metadata file should be left on disk")
    }

    @Test fun `unmatched roms are counted but write no metadata`() = runBlocking {
        rom("nes", "Homebrew Thing.nes", "hash-unknown")
        val s = pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        assertEquals(1, s.total)
        assertEquals(0, s.newEntries)
        assertEquals(0, paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.size)
    }

    // A second scan of an unchanged library must not hash or hit the network again.
    @Test fun `unchanged files are skipped on a rescan`() = runBlocking {
        rom("nes", "Super Mario Bros. (World).nes", "hash-smb")

        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        val h2 = ContentHasher(); val l2 = MapLookup(catalogue)
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s2.cachedHits)
        assertEquals(0, s2.newEntries)
        assertEquals(0, h2.calls.get(), "unchanged file must not be re-hashed")
        assertEquals(0, l2.calls.get(), "unchanged file must not hit the API")
        assertEquals(1, s2.indexed, "the index must still list it")
    }

    // Metadata written before the plain hashes existed carries no fileMd5. The
    // incremental skip must not preserve that gap forever, or a library already
    // scanned once would never gain the field a scraper needs.
    @Test fun `metadata without a plain hash is rescanned once`() = runBlocking {
        rom("nes", "Super Mario Bros. (World).nes", "hash-smb")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        // Strip the field, imitating a file from the previous schema.
        val meta = paths.metadata.listFiles { f -> !f.name.startsWith("_") }!!.first()
        val j = JSONObject(meta.readText())
        j.getJSONObject("rom").remove("fileMd5")
        meta.writeText(j.toString(2))

        val h2 = ContentHasher()
        val s2 = pipeline(h2, MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        assertEquals(0, s2.cachedHits, "stale-schema metadata must not count as a cache hit")
        assertEquals(1, h2.calls.get(), "the file must be hashed again to backfill")
        val after = JSONObject(meta.readText()).getJSONObject("rom")
        assertEquals("md5-hash-smb", after.getString("fileMd5"))
        assertEquals("crc-hash-smb", after.getString("fileCrc32"))
    }

    @Test fun `an edited file is rescanned`() = runBlocking {
        val f = rom("nes", "Game.nes", "hash-smb")
        pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        f.writeText("hash-ctra")
        f.setLastModified(f.lastModified() + 10_000)

        val h2 = ContentHasher()
        val s2 = pipeline(h2, MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        assertEquals(0, s2.cachedHits)
        assertEquals(1, h2.calls.get(), "changed file must be re-hashed")
    }

    @Test fun `platforms retroachievements does not cover are skipped before hashing`() = runBlocking {
        rom("switch", "Something.nes", "hash-smb")
        rom("psvita", "Other.nes", "hash-ctra")
        rom("nes",    "Real.nes", "hash-smb")

        val h = ContentHasher()
        val s = pipeline(h, MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))

        assertEquals(3, s.total)
        assertEquals(2, s.skippedPlatforms)
        assertEquals(1, h.calls.get(), "only the supported platform should be hashed")
    }

    // Several files sharing a hash should cost one network call, not one each.
    @Test fun `identical hashes are looked up once`() = runBlocking {
        rom("nes", "Copy A.nes", "hash-smb")
        rom("nes", "Copy B.nes", "hash-smb")
        rom("nes", "Copy C.nes", "hash-smb")

        val l = MapLookup(catalogue)
        pipeline(ContentHasher(), l).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l.calls.get(), "duplicate hashes must be de-duplicated")
    }

    @Test fun `a file the hasher cannot read does not abort the scan`() = runBlocking {
        rom("nes", "Broken.nes", "UNHASHABLE")
        rom("nes", "Good.nes", "hash-smb")

        val s = pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        assertEquals(2, s.total)
        assertEquals(1, s.newEntries, "the readable ROM must still be processed")
    }

    @Test fun `progress is reported and reaches the total`() = runBlocking {
        repeat(5) { rom("nes", "Game$it.nes", "hash-smb") }
        val seen = mutableListOf<RomScanPipeline.Progress>()
        val s = pipeline(ContentHasher(), MapLookup(catalogue))
            .scan(listOf(romRoot.absolutePath)) { seen += it }

        assertEquals(5, s.total)
        assertTrue(seen.isNotEmpty(), "no progress reported")
        assertEquals(5, seen.last().processed)
        assertEquals(1.0, seen.last().fraction)
    }

    @Test fun `a missing root is ignored rather than failing`() = runBlocking {
        rom("nes", "Game.nes", "hash-smb")
        val s = pipeline(ContentHasher(), MapLookup(catalogue))
            .scan(listOf(romRoot.absolutePath, "/does/not/exist"))
        assertEquals(1, s.total)
    }

    @Test fun `an empty library still writes a valid index`() = runBlocking {
        val s = pipeline(ContentHasher(), MapLookup(catalogue)).scan(listOf(romRoot.absolutePath))
        assertEquals(0, s.total)
        assertTrue(paths.discoveryIndex.isFile)
        assertEquals(0, JSONObject(paths.discoveryIndex.readText()).getInt("count"))
    }

    @Test fun `the throttle hook is honoured`() = runBlocking {
        rom("nes", "Game.nes", "hash-smb")
        var asked = 0
        RomScanPipeline(paths, ContentHasher(), MapLookup(catalogue), throttleMs = { asked++; 0L })
            .scan(listOf(romRoot.absolutePath))
        assertTrue(asked > 0, "throttle hook was never consulted")
    }

    @Test fun `zip archives are hashed via their largest entry`() = runBlocking {
        val dir = File(romRoot, "nes").apply { mkdirs() }
        val zip = File(dir, "Packed.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("readme.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("game.nes"));   z.write("hash-smb".toByteArray()); z.closeEntry()
        }
        val tmp = Files.createTempDirectory("hasher-tmp").toFile()
        val s = RomScanPipeline(paths, ArchiveAwareHasher(ContentHasher(), tmp),
                                MapLookup(catalogue), throttleMs = { 0L })
            .scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.newEntries, "the ROM inside the zip should have matched")
        assertFalse(tmp.listFiles()?.any { it.name.startsWith("bridge_") } ?: false,
                    "temp extraction files must be cleaned up")
        tmp.deleteRecursively()
    }
}
