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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Why a scan did what it did, and what the next one will do.
 *
 * Before the ledger, the only record a scan left was a metadata file per match.
 * A ROM the database does not have, one that would not hash, an archive nobody
 * could resolve and a lookup the API refused all left the same trace — none —
 * and the absence was then read as "not scanned yet".
 *
 * The distinction that has to survive: NOT_FOUND is an answer and may be
 * remembered; API_RETRY is the source declining to answer and must never be.
 * Confusing the two is what cost a whole RetroAchievements run.
 */
class ScanLedgerTest {

    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("ledger-data").toFile()
        romRoot  = Files.createTempDirectory("ledger-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private class ContentHasher : RomHasher {
        val calls = AtomicInteger()
        override fun hash(path: String): HashResult? {
            calls.incrementAndGet()
            val t = File(path).readText().trim()
            if (t == "UNHASHABLE") return null
            return HashResult(t, 7, fileMd5 = "md5-$t", fileCrc32 = "crc-$t")
        }
    }

    /** Says no to everything. A verdict, not a refusal. */
    private class SaysNo : RaHashLookup {
        val calls = AtomicInteger()
        override suspend fun lookup(hash: String): GameMetadata? {
            calls.incrementAndGet(); return GameMetadata(gameId = 0)
        }
    }

    /** Never answers. A refusal, not a verdict. */
    private class NeverAnswers : RaHashLookup {
        val calls = AtomicInteger()
        override suspend fun lookup(hash: String): GameMetadata? {
            calls.incrementAndGet(); return null
        }
    }

    private fun rom(platform: String, name: String, content: String): File {
        val dir = File(romRoot, platform).apply { mkdirs() }
        return File(dir, name).apply { writeText(content) }
    }

    private fun pipeline(h: RomHasher, l: RaHashLookup) =
        RomScanPipeline(paths, h, l, throttleMs = { 0L })

    private fun ledgerJson(): JSONObject =
        JSONObject(File(paths.cache, ScanLedger.FILE_NAME).readText())

    private fun ledgerEntry(f: File): JSONObject? =
        ledgerJson().getJSONObject("entries").optJSONObject(f.canonicalPath)

    // ── A verdict is remembered ─────────────────────────────────────────────

    @Test fun `a rom the source does not have is recorded as not found`(): Unit = runBlocking {
        val f = rom("nes", "Homebrew.nes", "hash-unknown")
        val s = pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.NOT_FOUND])
        assertEquals("NOT_FOUND", ledgerEntry(f)!!.getString("state"))
    }

    @Test fun `a recorded miss is not asked about again on the next scan`(): Unit = runBlocking {
        rom("nes", "Homebrew.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        val h2 = ContentHasher(); val l2 = SaysNo()
        val s2 = pipeline(h2, l2).scan(listOf(romRoot.absolutePath))

        assertEquals(0, l2.calls.get(), "a miss inside its TTL cost a network call")
        assertEquals(0, h2.calls.get(), "a miss inside its TTL cost a hash")
        assertEquals(1, s2.states[ScanLedger.State.NOT_FOUND])
    }

    // The exact bug this project has already paid for once: a refusal cached as
    // a verdict writes the game off, and an incremental rescan never asks again.
    @Test fun `a source that did not answer is asked again next time`(): Unit = runBlocking {
        val f = rom("nes", "Game.nes", "hash-x")
        pipeline(ContentHasher(), NeverAnswers()).scan(listOf(romRoot.absolutePath))
        assertEquals("API_RETRY", ledgerEntry(f)!!.getString("state"))

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "a refusal was cached as if it were an answer")
    }

    @Test fun `an unhashable file is recorded and retried rather than written off`(): Unit = runBlocking {
        val f = rom("nes", "Broken.nes", "UNHASHABLE")
        val s = pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.HASH_FAILED])
        assertEquals("HASH_FAILED", ledgerEntry(f)!!.getString("state"))

        // The file may be replaced by a good dump; a hash failure keeps no TTL.
        val h2 = ContentHasher()
        pipeline(h2, SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(1, h2.calls.get(), "a hash failure must be retried")
    }

    @Test fun `an unsupported platform is recorded without any io`(): Unit = runBlocking {
        val f = rom("switch", "Something.nes", "hash-x")
        val h = ContentHasher()
        val s = pipeline(h, SaysNo()).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.UNSUPPORTED])
        assertEquals(0, h.calls.get())
        assertEquals("UNSUPPORTED", ledgerEntry(f)!!.getString("state"))
    }

    @Test fun `a match is recorded with the game it matched`(): Unit = runBlocking {
        val f = rom("nes", "Contra (USA).nes", "hash-ctra")
        val found = object : RaHashLookup {
            override suspend fun lookup(hash: String) =
                GameMetadata(1447, "Contra", "NES", "/i.png", 40)
        }
        val s = pipeline(ContentHasher(), found).scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.MATCHED])
        val e = ledgerEntry(f)!!
        assertEquals("MATCHED", e.getString("state"))
        assertEquals(1447, e.getInt("gameId"))
    }

    // ── An archive nobody can resolve ───────────────────────────────────────

    @Test fun `an ambiguous archive is a diagnostic and not a miss`(): Unit = runBlocking {
        val dir = File(romRoot, "megadrive").apply { mkdirs() }
        val zip = File(dir, "Sonic Collection.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Sonic 1.md")); z.write(ByteArray(512)); z.closeEntry()
            z.putNextEntry(ZipEntry("Sonic 2.md")); z.write(ByteArray(1024)); z.closeEntry()
        }
        val l = SaysNo()
        val s = pipeline(ArchiveAwareHasher(ContentHasher(), File(dataRoot, "tmp")), l)
            .scan(listOf(romRoot.absolutePath))

        assertEquals(1, s.states[ScanLedger.State.AMBIGUOUS_ARCHIVE])
        assertNull(s.states[ScanLedger.State.NOT_FOUND],
                   "an archive nobody could resolve was recorded as a game the database lacks")
        assertEquals(0, l.calls.get(), "no lookup should be spent on a digest of nothing")
        assertEquals(1, s.ambiguousArchives.size)
        assertTrue(s.ambiguousArchives.first().second.contains("Sonic 1.md"),
                   "the candidates must be named, or nobody knows which file to open")
    }

    // ── Invalidation ────────────────────────────────────────────────────────

    @Test fun `a replaced file is asked about again despite a stored verdict`(): Unit = runBlocking {
        val f = rom("nes", "Game.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        f.writeText("hash-something-else-entirely")
        f.setLastModified(f.lastModified() + 60_000)

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "the replaced file kept the old verdict")
    }

    // What makes a policy change take effect on a library already scanned. Without
    // it, every decision the old archive rule made would be preserved forever.
    // The version just before the current one, because that is the ledger a real
    // library carries into an upgrade.
    @Test fun `a verdict from an older algorithm version is redone`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        val file = File(paths.cache, ScanLedger.FILE_NAME)
        val j = JSONObject(file.readText())
        val entries = j.getJSONObject("entries")
        entries.keys().forEach { k ->
            entries.getJSONObject(k).put("algorithmVersion", ScanLedger.ALGORITHM_VERSION - 1)
        }
        file.writeText(j.toString())

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "a verdict from the old rule was kept")
    }

    @Test fun `a miss older than its ttl is asked about again`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        val file = File(paths.cache, ScanLedger.FILE_NAME)
        val j = JSONObject(file.readText())
        val entries = j.getJSONObject("entries")
        val old = BridgePaths.epochSeconds() - 30L * 24 * 60 * 60
        entries.keys().forEach { k -> entries.getJSONObject(k).put("checkedAt", old) }
        file.writeText(j.toString())

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "a thirty-day-old miss was still trusted")
    }

    @Test fun `entries for files that are gone are dropped`(): Unit = runBlocking {
        val a = rom("nes", "A.nes", "hash-a")
        rom("nes", "B.nes", "hash-b")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(2, ledgerJson().getInt("count"))

        a.delete()
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(1, ledgerJson().getInt("count"), "the deleted file's entry lived on")
    }

    @Test fun `a corrupt ledger is started again rather than failing the scan`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-unknown")
        paths.cache.mkdirs()
        File(paths.cache, ScanLedger.FILE_NAME).writeText("{ not json at all")

        val s = pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))
        assertEquals(1, s.total)
        assertEquals(1, s.states[ScanLedger.State.NOT_FOUND])
    }

    @Test fun `a ledger from an older schema is discarded`(): Unit = runBlocking {
        rom("nes", "Game.nes", "hash-unknown")
        pipeline(ContentHasher(), SaysNo()).scan(listOf(romRoot.absolutePath))

        val file = File(paths.cache, ScanLedger.FILE_NAME)
        file.writeText(JSONObject(file.readText()).put("schemaVersion", 99).toString())

        val l2 = SaysNo()
        pipeline(ContentHasher(), l2).scan(listOf(romRoot.absolutePath))
        assertEquals(1, l2.calls.get(), "a ledger from an unknown schema was trusted")
    }

    // A cache hit already means the file matched; the ledger must agree rather
    // than reporting it as never having been looked at.
    @Test fun `a cached match is still counted as matched`(): Unit = runBlocking {
        rom("nes", "Contra (USA).nes", "hash-ctra")
        val found = object : RaHashLookup {
            override suspend fun lookup(hash: String) =
                GameMetadata(1447, "Contra", "NES", "/i.png", 40)
        }
        pipeline(ContentHasher(), found).scan(listOf(romRoot.absolutePath))

        val s2 = pipeline(ContentHasher(), found).scan(listOf(romRoot.absolutePath))
        assertEquals(1, s2.cachedHits)
        assertEquals(1, s2.states[ScanLedger.State.MATCHED])
    }
}
