package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What a reader that is not a scan is told about one file: the scan's verdict
 * while it still describes the file, and otherwise which of two things is
 * wrong, since they are answered differently. A file the scan has seen and
 * has no verdict for is not to be given the game of a file named like it; a
 * file it has never seen may be.
 *
 * The files are invented, and written through [ScanLedger] as a scan writes
 * them.
 */
class ScanVerdictsTest {

    private lateinit var dataRoot: File
    private lateinit var romRoot: File
    private lateinit var paths: BridgePaths
    private val nes = CollectionRef.inferred("nes")

    @BeforeTest fun setUp() {
        dataRoot = Files.createTempDirectory("verdicts-data").toFile()
        romRoot  = Files.createTempDirectory("verdicts-roms").toFile()
        paths = BridgePaths(dataRoot); paths.ensureAll()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dataRoot.deleteRecursively(); romRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun rom(name: String, content: String = name) =
        File(romRoot, name).apply { parentFile.mkdirs(); writeText(content + "\u0000") }

    /** Saves a ledger holding each of [entries] under the real path of its file, as a scan does. */
    private fun scanned(vararg entries: Triple<File, ScanLedger.State, Int>) {
        val ledger = ScanLedger(File(paths.cache, ScanLedger.FILE_NAME))
        for ((file, state, gameId) in entries)
            ledger.record(RomScanner.canonical(file), nes, state, file.length(), file.lastModified(), now = 1000,
                          gameId = gameId)
        ledger.save { f, text -> BridgePaths.writeAtomic(f, text) }
    }

    private fun verdict(file: File) = ScanVerdicts.of(paths.cache, file.path)

    // A frontend gives the path as the library wrote it, and a library is
    // often a folder of links. The ledger knows the file by where it lies.
    @Test fun `a verdict is found under the path a link leads to`() {
        val file = rom("lying/nes/Moss Kingdom.nes")
        val link = File(romRoot, "linked")
        try {
            Files.createSymbolicLink(link.toPath(), file.parentFile.toPath())
        } catch (e: Exception) {
            assumeTrue(false, "Symbolic links are unavailable: ${e.message}")
        }
        scanned(Triple(file, ScanLedger.State.MATCHED, 11))

        val settled = assertIs<ScanVerdicts.Verdict.Settled>(verdict(File(link, "Moss Kingdom.nes")))

        assertEquals(ScanLedger.State.MATCHED, settled.entry.state)
        assertEquals(11, settled.entry.gameId)
    }

    @Test fun `a file that is no longer the one the scan saw is unsettled`() {
        val file = rom("nes/Moss Kingdom.nes")
        scanned(Triple(file, ScanLedger.State.MATCHED, 11))
        assertIs<ScanVerdicts.Verdict.Settled>(verdict(file))

        file.writeText("another dump under the same name, and a longer one\u0000")
        assertEquals<ScanVerdicts.Verdict>(ScanVerdicts.Verdict.Unsettled, verdict(file))

        // The same size and another date is another file as well.
        val dated = rom("nes/Paper Rally.nes")
        scanned(Triple(dated, ScanLedger.State.NOT_FOUND, 0))
        assertEquals(true, dated.setLastModified(dated.lastModified() - 60_000))
        assertEquals<ScanVerdicts.Verdict>(ScanVerdicts.Verdict.Unsettled, verdict(dated))
    }

    // A card that is out, a disk not mounted: nothing says the verdict is
    // wrong, and the ledger keeps it until a scan forgets the file.
    @Test fun `a file that is gone keeps its verdict`() {
        val file = rom("nes/Moss Kingdom.nes")
        scanned(Triple(file, ScanLedger.State.NOT_FOUND, 0))
        assertEquals(true, file.delete())

        assertEquals(ScanLedger.State.NOT_FOUND, assertIs<ScanVerdicts.Verdict.Settled>(verdict(file)).entry.state)
    }

    @Test fun `a lookup that never got an answer is unsettled`() {
        val unanswered = rom("nes/Moss Kingdom.nes")
        val unread = rom("nes/Paper Rally.nes")
        scanned(Triple(unanswered, ScanLedger.State.API_RETRY, 0), Triple(unread, ScanLedger.State.HASH_FAILED, 0))

        assertEquals<ScanVerdicts.Verdict>(ScanVerdicts.Verdict.Unsettled, verdict(unanswered))
        assertEquals<ScanVerdicts.Verdict>(ScanVerdicts.Verdict.Unsettled, verdict(unread))
    }

    @Test fun `a file the ledger has never held is unseen`() {
        scanned(Triple(rom("nes/Moss Kingdom.nes"), ScanLedger.State.MATCHED, 11))

        assertEquals<ScanVerdicts.Verdict>(ScanVerdicts.Verdict.Unseen, verdict(rom("nes/Paper Rally.nes")))
        assertEquals<ScanVerdicts.Verdict>(ScanVerdicts.Verdict.Unseen, verdict(File(romRoot, "nes/Never There.nes")))
    }

    @Test fun `no ledger is unseen`() {
        assertEquals<ScanVerdicts.Verdict>(ScanVerdicts.Verdict.Unseen, verdict(rom("nes/Moss Kingdom.nes")))
    }

    // The ledger is parsed once and kept. The second one holds an entry
    // more, so it is longer whatever the clock says: a test that told the
    // two apart by their date alone would pass or fail by the millisecond.
    @Test fun `the ledger is read again once a scan has saved it`() {
        val first = rom("nes/Moss Kingdom.nes")
        val second = rom("nes/Paper Rally.nes")
        scanned(Triple(first, ScanLedger.State.MATCHED, 11))
        assertIs<ScanVerdicts.Verdict.Settled>(verdict(first))
        assertEquals<ScanVerdicts.Verdict>(ScanVerdicts.Verdict.Unseen, verdict(second))
        val before = File(paths.cache, ScanLedger.FILE_NAME).length()

        scanned(Triple(first, ScanLedger.State.MATCHED, 11), Triple(second, ScanLedger.State.MATCHED, 12))
        assertEquals(true, File(paths.cache, ScanLedger.FILE_NAME).length() > before)

        assertEquals(12, assertIs<ScanVerdicts.Verdict.Settled>(verdict(second)).entry.gameId)
    }
}
