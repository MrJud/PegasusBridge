package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import com.pegasus.bridge.hasher.CollectionRef
import com.pegasus.bridge.hasher.GameMetadata
import com.pegasus.bridge.hasher.HashOutcome
import com.pegasus.bridge.hasher.HashRecipe
import com.pegasus.bridge.hasher.HashResult
import com.pegasus.bridge.hasher.LookupOutcome
import com.pegasus.bridge.hasher.RaHashLookup
import com.pegasus.bridge.hasher.RomHasher
import com.pegasus.bridge.hasher.RomScanPipeline
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

private const val NUL = "\u0000"

/**
 * What a test writes for a ROM: the text its hasher answers with, and a NUL
 * after it. A small file of nothing but text is a placeholder, which a scan
 * neither hashes nor asks about, and a ROM is never only text.
 */
private fun romText(content: String): String = content + NUL

/**
 * The audit over small libraries made here, with a hasher that gives a file's
 * own text for its hash in place of the native library, which the daemon does
 * not find under test. RetroAchievements is a port with nothing behind it in
 * every test, so no request leaves the machine whatever the audit does.
 */
class ScanAuditTest {

    private lateinit var root: File
    private lateinit var roms: File
    private lateinit var out: File
    // Bound and never listened on, as in BridgeDaemonTest: a request to it is
    // refused at once.
    private lateinit var nobody: java.net.Socket

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("audit-test").toFile()
        roms = File(root, "roms").apply { mkdirs() }
        out = File(root, "out/audit.tsv")
        nobody = java.net.Socket().apply { bind(java.net.InetSocketAddress("127.0.0.1", 0)) }
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        nobody.close()
        root.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    /** A file's text is its hash, on console 3, and every file read is in [read]. */
    private class TextHasher : RomHasher {
        val read = ConcurrentLinkedQueue<String>()
        override fun hash(path: String): HashResult {
            read += File(path).name
            return HashResult(File(path).readText().removeSuffix(NUL), 3)
        }
    }

    private fun rom(path: String, text: String = "hash-of-$path"): File =
        File(roms, path).apply { parentFile.mkdirs(); writeText(romText(text)) }

    private fun md5(text: String): String = java.security.MessageDigest.getInstance("MD5")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val hasher = TextHasher()
    private val deviceAsked = AtomicInteger()

    private val loaded = AtomicInteger()

    /**
     * Every audit of these tests goes through here, so that none can be handed
     * the real address: [standIn] is a server of the test's own, and without
     * one RetroAchievements is the port with nothing behind it.
     */
    private fun run(vararg args: String, offline: Boolean = false, library: RomHasher? = hasher,
                    standIn: HttpServer? = null): Int =
        ScanAudit.run(arrayOf(*args),
                      loadHasher = { loaded.incrementAndGet(); library },
                      raBaseUrl = "http://127.0.0.1:${standIn?.address?.port ?: nobody.localPort}",
                      deviceOffline = { deviceAsked.incrementAndGet(); offline })

    private fun audit(vararg more: String, offline: Boolean = false, library: RomHasher? = hasher): Int =
        run("--audit=${roms.absolutePath}", "--out=${out.absolutePath}", *more, offline = offline, library = library)

    /** A cell as the report reads it back: the letter after a backslash is the character it stands for. */
    private fun unescaped(cell: String): String = buildString {
        var i = 0
        while (i < cell.length) {
            val c = cell[i++]
            if (c != '\\') { append(c); continue }
            when (val next = cell[i++]) { 't' -> append('\t'); 'n' -> append('\n'); 'r' -> append('\r'); else -> append(next) }
        }
    }

    /** The table's rows by file name, each a map from the column's name to its cell. */
    private fun rows(): Map<String, Map<String, String>> {
        val lines = out.readText().removeSuffix("\n").split('\n').filterNot { it.startsWith("#") }
        assertEquals(ScanAudit.COLUMNS, lines.first().split('\t'), "the line that names the columns")
        val paths = lines.drop(1).map { unescaped(it.split('\t').first()) }
        assertEquals(paths.sorted(), paths, "the rows are not sorted by path")
        return lines.drop(1).associate { line ->
            val cells = line.split('\t').map(::unescaped)
            assertEquals(ScanAudit.COLUMNS.size, cells.size, line)
            cells[0].substringAfterLast(File.separatorChar) to ScanAudit.COLUMNS.zip(cells).toMap()
        }
    }

    /** Every value the lines beginning `#` give for [name], in the order they are written. */
    private fun said(name: String): List<String> = out.readLines().filter { it.substringBefore('\t') == name }
        .map { it.substringAfter('\t', "") }

    private fun comments(): Map<String, String> = out.readLines().filter { it.startsWith("#") }
        .associate { it.substringBefore('\t') to it.substringAfter('\t', "") }

    /** The lines of the log while [block] runs. */
    private fun logged(block: () -> Unit): List<String> {
        val lines = ConcurrentLinkedQueue<String>()
        BridgeLog.current = object : BridgeLog {
            override fun d(tag: String, msg: String) { lines += "$tag: $msg" }
            override fun i(tag: String, msg: String) { lines += "$tag: $msg" }
            override fun w(tag: String, msg: String, t: Throwable?) { lines += "$tag: $msg" }
            override fun e(tag: String, msg: String, t: Throwable?) { lines += "$tag: $msg" }
        }
        try { block() } finally { BridgeLog.current = NoopLog }
        return lines.toList()
    }

    // The three things the table is for: every file has a row, a row says
    // what the scan decided and what the hasher gave, and `asked` tells the
    // files whose hash went to the lookup from the ones decided without it.
    @Test fun `an audit writes one row per file and asks only the oracle`() {
        rom("snes/Known.sfc", "hash-known")
        rom("snes/Unknown.sfc", "hash-unknown")
        rom("switch/Game.iso", "never read")
        val oracle = File(root, "answers.tsv").apply {
            writeText("# answers recorded for a test\n" +
                      "hash\tgameId\tdate\tsource\ttitle\n" +
                      "HASH-KNOWN\t4242\t2026-10-09\ta test\tA Known Game\n" +
                      "hash-elsewhere\t7\t2026-10-09\ta test\n")
        }

        assertEquals(0, audit("--oracle=${oracle.absolutePath}"))

        val rows = rows()
        assertEquals(setOf("Known.sfc", "Unknown.sfc", "Game.iso"), rows.keys)
        fun cells(file: String, vararg columns: String) = columns.map { rows.getValue(file).getValue(it) }
        val told = arrayOf("platform", "extension", "state", "console", "hash", "fileMd5", "asked")
        // The MD5 is of the file and not the hasher's: the daemon's archive
        // layer is around the hasher here too, and it is what takes it.
        assertEquals(listOf("snes", "sfc", "MATCHED", "3", "hash-known", md5(romText("hash-known")), "1"),
                     cells("Known.sfc", *told))
        assertEquals(listOf("snes", "sfc", "NOT_FOUND", "3", "hash-unknown", md5(romText("hash-unknown")), "1"),
                     cells("Unknown.sfc", *told))
        // Decided from the folder's name, before the hasher or the lookup.
        assertEquals(listOf("", "iso", "UNSUPPORTED", "", "", "", "0"), cells("Game.iso", *told))
        assertEquals(File(roms, "snes/Known.sfc").canonicalPath, rows.getValue("Known.sfc")["path"])
        assertEquals("11", rows.getValue("Known.sfc")["size"])
        assertTrue(rows.getValue("Known.sfc").getValue("ms").toLong() >= 0)
        assertEquals("", rows.getValue("Game.iso")["ms"])

        assertEquals(setOf("Known.sfc", "Unknown.sfc"), hasher.read.toSet())
        val said = comments()
        assertEquals(roms.canonicalPath, said["# root"])
        assertEquals("2 recorded", said["# answers"])
        assertEquals("3 found, 3 rows", said["# files"])
        assertEquals("2 for 2 hashes", said["# lookups"])
        assertEquals("Ok=2", said["# outcomes"])
        // No request was made: one made to that port fails, and the machine
        // is asked about its connection when one does.
        assertEquals(0, deviceAsked.get())
        // The scan's own data root is gone, and the table is all that is left.
        assertEquals(listOf("audit.tsv"), out.parentFile.list()!!.toList())
    }

    // The first line of the table says what the scan reached its verdicts
    // with, and before anything else which library hashed. That is asked of
    // the hasher the audit was given, through the two the audit puts
    // around it. Left at the name a hasher has when it gives none, the line
    // would say "none" of every build, and the audit would keep its
    // verdicts under another number than a daemon with the same library.
    @Test fun `the table begins with the recipe of the hasher the scan was given`() {
        rom("snes/Known.sfc", "hash-known")
        assertEquals(0, audit())
        assertEquals("# recipe\t" + HashRecipe("none").global, out.readLines().first())

        val named = object : RomHasher {
            override val engine: String get() = "made up 1.0"
            override fun hash(path: String): HashResult = HashResult("hash-known", 3)
        }
        assertEquals(0, audit(library = named))
        assertEquals("# recipe\t" + HashRecipe("made up 1.0").global, out.readLines().first())
        assertTrue("rc=made up 1.0;" in out.readLines().first(), out.readLines().first())
    }

    // The audit's scan reads a collection's metafile as a daemon's does: an
    // extension counts where the collection declares it and nowhere else.
    @Test fun `a collection's own extensions count in an audit as they do in a scan`() {
        rom("snes/Declared.jud")
        File(roms, "snes/metadata.pegasus.txt").writeText(
            "collection: Super Nintendo\nshortname: snes\nextensions: sfc, jud\n")
        rom("nes/Undeclared.jud")
        rom("nes/Plain.nes")

        assertEquals(0, audit())

        assertEquals(setOf("Declared.jud", "Plain.nes"), rows().keys)
    }

    // What the scan takes a file's collection to be is in the table as the
    // scan handed it to the hasher: the short name the collection declares,
    // and the name of the folder it is kept in. They are one where nothing
    // declares a collection, and for a file in a folder of its own they are
    // the collection's and not that folder's.
    @Test fun `a folder declaring shortname 'whatever' is recorded with both names`() {
        rom("x/Declared.sfc")
        rom("x/A Game/Nested.sfc")
        File(roms, "x/metadata.pegasus.txt").writeText("collection: Something Else\nshortname: whatever\n")
        rom("snes/Plain.sfc")

        assertEquals(0, audit())

        val rows = rows()
        fun names(file: String) = listOf("platform", "dirName").map { rows.getValue(file).getValue(it) }
        assertEquals(listOf("whatever", "x"), names("Declared.sfc"))
        assertEquals(listOf("whatever", "x"), names("Nested.sfc"))
        assertEquals(listOf("snes", "snes"), names("Plain.sfc"))
    }

    @Test fun `a file over the limit or named to be skipped is answered for without being read`() {
        rom("snes/Small.sfc", "x".repeat(49))
        rom("snes/Large.sfc", "x".repeat(50))
        rom("snes/Crashes.sfc")
        rom("bios/pack/Firmware.bin")
        rom("biosphere/Game.bin", "hash-biosphere")

        val log = logged {
            assertEquals(0, audit("--skip-larger-than=50",
                                  "--skip=${File(roms, "snes/Crashes.sfc")}|${File(roms, "bios")}"))
        }

        val rows = rows()
        fun cells(file: String) = listOf("state", "hash", "detail", "asked").map { rows.getValue(file).getValue(it) }
        // "Larger than" and not "as large as".
        assertEquals(listOf("NOT_FOUND", "x".repeat(49), "", "1"), cells("Small.sfc"))
        assertEquals(listOf("HASH_FAILED", "", "audit: larger than 50", "0"), cells("Large.sfc"))
        assertEquals(listOf("HASH_FAILED", "", "audit: skipped", "0"), cells("Crashes.sfc"))
        assertEquals(listOf("HASH_FAILED", "", "audit: skipped", "0"), cells("Firmware.bin"))
        // A folder is skipped, not every path that begins as its name does.
        assertEquals(listOf("NOT_FOUND", "hash-biosphere", "", "1"), cells("Game.bin"))
        assertEquals(setOf("Small.sfc", "Game.bin"), hasher.read.toSet())
        assertEquals("Failed=3, Ok=2", comments()["# outcomes"])
        assertEquals("50", comments()["# skip-larger-than"])
        assertEquals(listOf(File(roms, "snes/Crashes.sfc").canonicalPath, File(roms, "bios").canonicalPath),
                     said("# skip"))
        // The line that names a file before it is read is how the one that
        // takes the process down is found: one for each file read, and none
        // for a file answered for without reading.
        assertEquals(listOf("Game.bin", "Small.sfc"),
                     log.filter { it.startsWith("ScanAudit: hashing ") }.map { File(it).name }.sorted())
    }

    // A file's name is whatever the file system lets it be, and a row is one
    // line of cells with tabs between: without the backslashes this file
    // would be two lines, the first with a cell too many.
    @Test fun `a name with a tab, a line break and a backslash in it is still one row`() {
        val odd = rom("snes/Odd\tname\n\\ here.sfc", "hash-odd")
        rom("snes/Plain.sfc")

        assertEquals(0, audit())

        val rows = rows()
        assertEquals(setOf("Odd\tname\n\\ here.sfc", "Plain.sfc"), rows.keys)
        assertEquals(odd.canonicalPath, rows.getValue(odd.name)["path"])
        assertEquals("hash-odd", rows.getValue(odd.name)["hash"])
        assertEquals("2 found, 2 rows", comments()["# files"])
    }

    // The oracle's title is for a person and may be left out, and the scan
    // takes an id with no title for a lookup that failed.
    @Test fun `an answer with no title is still a match`() {
        rom("snes/Untitled.sfc", "hash-untitled")
        val oracle = File(root, "answers.tsv").apply { writeText("hash-untitled\t77\n") }

        assertEquals(0, audit("--oracle=${oracle.absolutePath}"))

        assertEquals("MATCHED", rows().getValue("Untitled.sfc")["state"])
    }

    // The one way an audit reaches the network, tried against a port with
    // nothing behind it on a machine the test calls offline. The lookup is
    // RaApiHashLookup, which asks the machine when its request fails, and the
    // scan stops on its answer as a daemon's does.
    @Test fun `with --lookup an audit asks RetroAchievements and ends where a scan would`() {
        rom("snes/Game.sfc", "hash-game")
        val credentials = File(root, "data-root").apply { mkdirs() }

        assertEquals(1, audit("--lookup", "--data-root=${credentials.absolutePath}", offline = true))

        val row = rows().getValue("Game.sfc")
        assertEquals(listOf("API_RETRY", "hash-game", "1"), listOf("state", "hash", "asked").map { row.getValue(it) })
        assertTrue(deviceAsked.get() > 0, "the machine was never asked about its connection")
        val said = comments()
        assertEquals("retroachievements", said["# answers"])
        assertTrue(said.getValue("# stopped").startsWith("no internet connection"), said["# stopped"])
        // The data root named is where the credentials are read, and nothing
        // of the scan is put there.
        assertEquals(emptyList(), credentials.list()!!.toList())
    }

    // The other end of --lookup: RetroAchievements answers, and refuses the
    // key. What is asked with has to be what the data root named holds, the
    // scan has to stop on the refusal as a daemon's does, and the table has
    // to hold the files the stop left behind: hashed and never asked about,
    // or being read when it came.
    @Test fun `a refused key ends an audit with a row for every file the scan had reached`() {
        (1..8).forEach { rom("snes/Game$it.sfc", "hash-game-$it") }
        val later = File(root, "later").apply { mkdirs() }
        val slow = File(later, "snes/Slow.sfc").apply { parentFile.mkdirs(); writeText(romText("never hashed")) }
        val credentials = File(root, "data-root")
        Config(BridgePaths(credentials)).writeCredentials(raUser = "someone", raApiKey = "a-key")
        val before = credentials.walkTopDown().map { it.path to it.lastModified() }.toList()

        // Read to no end until the scan interrupts it, which is what a disc
        // image in the middle of its read is to a scan that stops.
        val reading = CountDownLatch(1)
        val library = object : RomHasher {
            override fun hash(path: String): HashResult {
                if (File(path).name != slow.name) return hasher.hash(path)
                reading.countDown()
                try { Thread.sleep(60_000) } catch (e: InterruptedException) {
                    throw java.io.IOException("the read of ${slow.name} was broken off")
                }
                return HashResult("read to its end", 3)
            }
        }
        val asked = ConcurrentLinkedQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dorequest.php") { exchange ->
            val body = """{"Success":true,"GameID":4242}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/API/API_GetGameExtended.php") { exchange ->
            asked += exchange.requestURI.query
            // Not before the last file is being read. The second folder is
            // walked after the first and one file is read at a time, so
            // every file of the first has been hashed by then.
            reading.await(10, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        val status = try {
            run("--audit=${roms.absolutePath}|${later.absolutePath}", "--out=${out.absolutePath}", "--lookup",
                "--hash-workers=1", "--data-root=${credentials.absolutePath}", library = library, standIn = server)
        } finally { server.stop(0) }

        assertEquals(1, status)
        assertTrue(asked.isNotEmpty() && asked.all { it == "z=someone&y=a-key&i=4242" }, asked.toString())
        assertEquals(before, credentials.walkTopDown().map { it.path to it.lastModified() }.toList())
        assertEquals(0, deviceAsked.get())

        val rows = rows()
        assertEquals((1..8).map { "Game$it.sfc" }.toSet() + slow.name, rows.keys)
        val games = rows.filterKeys { it != slow.name }
        // The ones the scan had an answer for when it stopped, a refusal,
        // and the ones it had only hashed.
        assertEquals(setOf("API_RETRY", ScanAudit.NOT_RECORDED), games.values.map { it.getValue("state") }.toSet())
        games.forEach { (name, row) ->
            assertEquals("hash-game-${name.removePrefix("Game").removeSuffix(".sfc")}", row["hash"], name)
            if (row["state"] == "API_RETRY") assertEquals("1", row["asked"], name)
        }
        // `asked` is of the hash and not of the file having one: the files
        // still waiting for a lookup when the scan stopped have a 0.
        val askedAbout = games.values.count { it["asked"] == "1" }
        assertTrue(askedAbout < games.size, "every hash was asked about")
        assertEquals("$askedAbout for $askedAbout hashes", comments()["# lookups"])
        // In no ledger, and the only word on it is the hasher's.
        assertEquals(listOf(ScanAudit.NOT_RECORDED, "", "0", "the read of Slow.sfc was broken off"),
                     listOf("state", "hash", "asked", "detail").map { rows.getValue(slow.name).getValue(it) })

        val said = comments()
        assertEquals("9 found, 9 rows", said["# files"])
        assertEquals("Ok=8, threw IOException=1", said["# outcomes"])
        assertTrue(said.getValue("# stopped").startsWith("RetroAchievements refused the API key"), said["# stopped"])
        assertEquals(listOf(roms.canonicalPath, later.canonicalPath), said("# root"))
    }

    // A scan can end by throwing, with no summary to go by: the folder it
    // writes a match down in is made a file here while the ROM is read. The
    // rows it got to are written all the same, and the status and the table
    // both say that it is not the table of a whole scan.
    @Test fun `a scan that breaks is written down as far as it got and is not a finished audit`() {
        rom("snes/Known.sfc", "hash-known")
        val oracle = File(root, "answers.tsv").apply { writeText("hash-known\t4242\n") }
        val library = object : RomHasher {
            override fun hash(path: String): HashResult {
                val dataRoot = out.parentFile.listFiles { f -> f.isDirectory }!!.single()
                File(dataRoot, "metadata").apply { deleteRecursively(); writeText("in the way") }
                return hasher.hash(path)
            }
        }

        assertEquals(1, audit("--oracle=${oracle.absolutePath}", library = library))

        val row = rows().getValue("Known.sfc")
        assertEquals(listOf(ScanAudit.NOT_RECORDED, "hash-known", "1"),
                     listOf("state", "hash", "asked").map { row.getValue(it) })
        assertEquals("the scan did not finish", comments()["# stopped"])
        assertEquals("? found, 1 rows", comments()["# files"])
        assertEquals(listOf("audit.tsv"), out.parentFile.list()!!.toList())
    }

    // An audit can end with the process, in native code, and then writes
    // nothing. What it must not leave is the table of the run before, where
    // the next thing to read it would take it for this run's.
    @Test fun `the table of an earlier run is emptied before a file is read`() {
        rom("snes/Game.sfc")
        out.parentFile.mkdirs()
        out.writeText("the table of an earlier run")
        val found = ConcurrentLinkedQueue<String>()
        val library = object : RomHasher {
            override fun hash(path: String): HashResult { found += out.readText(); return hasher.hash(path) }
        }

        assertEquals(0, audit(library = library))

        assertEquals(listOf(""), found.toList())
        assertEquals(setOf("Game.sfc"), rows().keys)
    }

    // What the process ends with, on the summary alone. No scan there is
    // leaves a file out of both the ledger and the hasher's sight, so the
    // third of these cannot be reached through an audit; it is what makes a
    // scan that one day does so fail its audit and not pass with a short
    // table.
    @Test fun `a table is the library's only when the scan ended and every file has its row`() {
        fun summary(total: Int, aborted: Boolean = false) =
            RomScanPipeline.Summary(total, total, 0, 0, 0, 0, aborted = aborted, reason = "stopped for the test")

        assertEquals(0, ScanAudit.status(summary(3), rows = 3))
        assertEquals(1, ScanAudit.status(null, rows = 3))
        assertEquals(1, ScanAudit.status(summary(3, aborted = true), rows = 3))
        assertEquals(1, ScanAudit.status(summary(3), rows = 2))
        assertEquals(1, ScanAudit.status(summary(3), rows = 4))
    }

    // What the file of answers says, as the scan is to hear it. An id of 0 is
    // an answer recorded, that RetroAchievements does not know the hash, and
    // so is the answer for a hash the file has no line for. An id above a
    // thousand million is a dump known and not playable, with the real game
    // and the reason taken out of it, and whatever title its line has is not
    // read: there is no game under that number for it to be the title of.
    @Test fun `the oracle answers a game, a miss and a virtual id as what each is`() {
        val oracle = File(root, "answers.tsv").apply {
            writeText("hash\tgameId\tdate\tsource\ttitle\n" +
                      "hash-game\t4242\t2026-10-09\ta test\tA Known Game\n" +
                      "hash-untitled\t77\n" +
                      "hash-miss\t0\t2026-10-09\ta test\n" +
                      "hash-edge\t1000000000\n" +
                      "hash-virtual\t1100001487\t2026-10-09\ta test\tA Title Nobody Reads\n")
        }
        val answers = ScanAudit.OracleLookup.read(oracle)
        fun said(hash: String): LookupOutcome = runBlocking { answers.lookup(hash) }

        assertEquals(5, answers.size)
        assertEquals(LookupOutcome.Match(GameMetadata(gameId = 4242, title = "A Known Game")), said("hash-game"))
        assertEquals(LookupOutcome.Match(GameMetadata(gameId = 77, title = "game 77")), said("hash-untitled"))
        assertEquals(LookupOutcome.NotFound, said("hash-miss"))
        assertEquals(LookupOutcome.NotFound, said("hash-nowhere"))
        // The first base itself is a game's own id, as RAWeb has it.
        assertEquals(LookupOutcome.Match(GameMetadata(gameId = 1_000_000_000, title = "game 1000000000")),
                     said("hash-edge"))
        assertEquals(LookupOutcome.IdOnly(1487, LookupOutcome.Compatibility.UNTESTED, virtualId = 1_100_001_487),
                     said("hash-virtual"))
    }

    // The scan stops on three things a lookup says of itself, and the audit's
    // lookup is around the real one: each has to come through it as it is.
    @Test fun `the counting lookup says of itself what the lookup inside says`() {
        val seven = LookupOutcome.Match(GameMetadata(gameId = 7, title = "Seven"))
        val noAnswer = LookupOutcome.Failed(LookupOutcome.Cause.OFFLINE, "nobody there")
        class Inside(override val consecutiveFailures: Int, override val authRejected: Boolean,
                     override val offline: Boolean) : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome = if (hash == "known") seven else noAnswer
        }
        val quiet = ScanAudit.CountingLookup(Inside(0, authRejected = false, offline = false))
        assertEquals(listOf(0, false, false), listOf(quiet.consecutiveFailures, quiet.authRejected, quiet.offline))
        for ((failures, rejected, offline) in listOf(Triple(5, false, false), Triple(0, true, false),
                                                     Triple(0, false, true))) {
            val counting = ScanAudit.CountingLookup(Inside(failures, rejected, offline))
            assertEquals(listOf(failures, rejected, offline),
                         listOf(counting.consecutiveFailures, counting.authRejected, counting.offline))
        }

        assertEquals<LookupOutcome>(seven, runBlocking { quiet.lookup("known") })
        assertEquals<LookupOutcome>(noAnswer, runBlocking { quiet.lookup("unknown") })
        runBlocking { quiet.lookup("known") }
        assertEquals(listOf(true, true, false), listOf("known", "unknown", "never").map(quiet::asked))
        assertEquals(listOf(3, 2), listOf(quiet.calls, quiet.distinct))
    }

    @Test fun `arguments that make no audit are refused before anything is read`() {
        rom("snes/Game.sfc")
        val folder = "--audit=${roms.absolutePath}"
        val table = "--out=${out.absolutePath}"
        val oracle = File(root, "answers.tsv")

        assertEquals(2, run(folder), "no table to write")
        assertEquals(2, run("--audit=", table), "no folder")
        assertEquals(2, run("--audit=${File(roms, "missing")}", table), "a folder that is not there")
        assertEquals(2, run(folder, table, "--skip-larger-then=5"), "a flag nobody knows")
        assertEquals(2, run(folder, table, "--skip-larger-than=5MB"), "a size that is not a number")
        assertEquals(2, run(folder, table, "--skip-larger-than=-1"), "a size below nothing")
        assertEquals(2, run(folder, table, "--skip=${File(roms, "snes/Gane.sfc")}"), "a file to skip that is not there")
        assertEquals(2, run(folder, "--audit=${File(roms, "snes")}", table), "two lists of folders")
        assertEquals(2, run(folder, table, "--skip=${File(roms, "snes")}", "--skip=${File(roms, "snes/Game.sfc")}"),
                     "two lists of what to skip")
        assertEquals(2, run(folder, table, "--oracle=${oracle.absolutePath}"), "an oracle that is not there")
        oracle.writeText("hash-a\t12\n")
        assertEquals(2, run(folder, table, "--oracle=${oracle.absolutePath}", "--lookup"), "two sources of answers")
        oracle.writeText("hash-a\t12\nhash-b\tnot a number\n")
        assertEquals(2, run(folder, table, "--oracle=${oracle.absolutePath}"), "an oracle with a line that is no answer")
        oracle.writeText("hash-a\t12\nhash-a\t13\n")
        assertEquals(2, run(folder, table, "--oracle=${oracle.absolutePath}"), "an oracle with two games for one hash")
        assertEquals(2, run(folder, table, "--keep=${oracle.absolutePath}"), "a file for the data root to keep")
        assertEquals(2, run(folder, table, "--keep="), "no folder to keep")
        // Spelt another way on purpose: it is the folder that counts.
        val daemons = File(root, "data-root")
        assertEquals(2, run(folder, table, "--data-root=${daemons.absolutePath}",
                            "--keep=${File(root, "roms/../data-root")}"), "a daemon's data root to keep")
        assertFalse(daemons.exists(), "the data root was made")

        assertEquals(0, loaded.get())
        assertFalse(out.parentFile.exists(), "something was written")
        assertEquals(emptyList(), hasher.read.toList())

        // With no library to hash with there is no audit either, and that is
        // not a mistake in the arguments.
        assertEquals(1, run(folder, table, library = null))
        assertFalse(out.exists())

        // A table that cannot be written is found out before the scan and
        // not after it: a folder where the file should be, and a file where
        // its folder should be.
        assertEquals(2, run(folder, "--out=${roms.absolutePath}"), "a folder for a table")
        assertEquals(2, run(folder, "--out=${File(roms, "snes/Game.sfc/audit.tsv")}"), "a file for the table's folder")
        assertEquals(emptyList(), hasher.read.toList())
        assertEquals(listOf("snes"), roms.list()!!.toList())
    }

    // The count is the system's, as it writes it for a thread: the first
    // line of several, and the only one that is of reads asked for.
    @Test fun `the number after rchar is what was read`() {
        assertEquals(2884L, ScanAudit.IoCounters.parse("rchar: 2884\nwchar: 0\nsyscr: 7\n"))
        assertEquals(2884L, ScanAudit.IoCounters.parse("wchar: 9\nrchar: 2884\n"))
        assertNull(ScanAudit.IoCounters.parse(""))
        assertNull(ScanAudit.IoCounters.parse("wchar: 1"))
        assertNull(ScanAudit.IoCounters.parse("rchar: a lot"))
    }

    // What the column is for: a file read to its end costs its size at the
    // least, and one the hasher made nothing of costs next to nothing. The
    // library here reads the first file whole and refuses the second
    // unopened; the archive layer around it then opens that one, to see
    // whether it can be read at all, and reads none of it.
    @Test fun `a row says how many bytes its file cost and the table adds them up`() {
        assumeTrue(ScanAudit.IoCounters.thread() != null, "no count of what a thread reads on this system")
        val size = 4 * 1024 * 1024
        val whole = File(roms, "snes/Whole.sfc").apply { parentFile.mkdirs(); writeBytes(ByteArray(size)) }
        val refused = File(roms, "snes/Refused.sfc").apply { writeBytes(ByteArray(size)) }
        val library = object : RomHasher {
            override fun hash(path: String): HashResult? =
                if (File(path).name == whole.name) HashResult("hash-of-${File(path).readBytes().size}", 3) else null
        }

        assertEquals(0, audit(library = library))

        val rows = rows()
        assertEquals(listOf("NOT_FOUND", "hash-of-$size"),
                     listOf("state", "hash").map { rows.getValue(whole.name).getValue(it) })
        assertEquals("HASH_FAILED", rows.getValue(refused.name)["state"])
        val cost = rows.mapValues { it.value.getValue("read").toLong() }
        assertTrue(cost.getValue(whole.name) >= size, "the file read whole: ${cost[whole.name]}")
        assertTrue(cost.getValue(refused.name) < 1024 * 1024, "the file not read: ${cost[refused.name]}")
        val said = comments()
        assertEquals("2 files handed, ${cost.values.sum()} bytes read for them", said["# hasher"])
        // The whole process read those bytes too, and more beside them.
        val process = said.getValue("# read")
        assertTrue(process.endsWith(" bytes by the process during the scan"), process)
        assertTrue(process.substringBefore(' ').toLong() >= cost.values.sum(), process)
    }

    // What --keep is for: the second audit starts from the ledger and the
    // metadata the first one left, so it is a rescan, and what a rescan reads
    // and asks can be counted. Its table still has a row for every file, with
    // the state the ledger kept and nothing of the hasher's, which was not
    // asked.
    @Test fun `a second audit of a kept data root hands the hasher nothing`() {
        rom("snes/Known.sfc", "hash-known")
        rom("snes/Unknown.sfc", "hash-unknown")
        val oracle = File(root, "answers.tsv").apply { writeText("hash-known\t4242\n") }
        val kept = File(root, "kept/data")
        val args = arrayOf("--oracle=${oracle.absolutePath}", "--keep=${kept.absolutePath}")

        assertEquals(0, audit(*args))

        val first = rows().mapValues { it.value.getValue("state") }
        assertEquals(mapOf("Known.sfc" to "MATCHED", "Unknown.sfc" to "NOT_FOUND"), first)
        assertEquals("2 files handed", comments().getValue("# hasher").substringBefore(','))
        assertEquals("2 for 2 hashes", comments()["# lookups"])
        assertEquals(kept.canonicalPath, comments()["# keep"])
        assertTrue(File(kept, "cache/scan-ledger.json").isFile, "the data root was not kept")
        // Nothing was made beside the table, where the root goes when none is named.
        assertEquals(listOf("audit.tsv"), out.parentFile.list()!!.toList())
        val read = hasher.read.toList()

        assertEquals(0, audit(*args))

        val rows = rows()
        assertEquals(first, rows.mapValues { it.value.getValue("state") })
        assertEquals(listOf("", "", "", ""),
                     listOf("console", "hash", "fileMd5", "read").map { rows.getValue("Known.sfc").getValue(it) })
        assertEquals("0 files handed", comments().getValue("# hasher").substringBefore(','))
        assertEquals("0 for 0 hashes", comments()["# lookups"])
        assertEquals("2 found, 2 rows", comments()["# files"])
        assertEquals(read, hasher.read.toList())
        assertTrue(File(kept, "cache/scan-ledger.json").isFile, "the data root was not kept the second time")
    }

    // The audit's hasher is between the scan and the hasher a daemon uses,
    // and what it lets through is what the audit measures. So the one inside
    // has to be handed the very arguments the scan gave, and has to give back
    // the very answer, whatever kind of answer that is: the third below is of
    // a kind this hasher takes nothing out of, as one added later would be.
    @Test fun `the recording hasher hands on what it was given and keeps what came back`() {
        val given = ConcurrentLinkedQueue<Pair<String, String>>()
        val collections = ConcurrentLinkedQueue<Pair<String, CollectionRef>>()
        val answers = mapOf(
            "Good.sfc" to HashOutcome.Ok(HashResult("hash-good", 3, fileMd5 = "md5-good", archiveEntry = "Inner.sfc")),
            "Bad.sfc" to HashOutcome.Failed("the hasher could not read Bad.sfc", retryable = false),
            "Two.zip" to HashOutcome.AmbiguousArchive(listOf("a.sfc", "b.sfc")))
        val inside = object : RomHasher {
            override fun hash(path: String): HashResult? = null
            override fun hashDetailed(path: String, platform: String): HashOutcome {
                given += path to platform
                return answers[File(path).name] ?: throw java.io.IOException("cannot read ${File(path).name}")
            }
            override fun hashDetailed(path: String, collection: CollectionRef): HashOutcome {
                collections += path to collection
                return answers.getValue("Good.sfc")
            }
        }
        val recording = ScanAudit.RecordingHasher(inside, null, emptyList())
        val files = listOf("Good.sfc", "Bad.sfc", "Two.zip", "Gone.sfc").map { rom("Super Nintendo/$it") }

        for (file in files.dropLast(1)) {
            assertTrue(answers.getValue(file.name) === recording.hashDetailed(file.path, "Super Nintendo"), file.name)
        }
        val thrown = runCatching { recording.hashDetailed(files.last().path, "Super Nintendo") }.exceptionOrNull()
        assertEquals("cannot read Gone.sfc", thrown?.message)

        assertEquals(files.map { it.path to "Super Nintendo" }, given.toList())
        val seen = recording.seen()
        fun kept(name: String) = seen.getValue(File(roms, "Super Nintendo/$name").canonicalPath)
            .let { listOf(it.platform, it.outcome, it.console, it.hash, it.fileMd5, it.archiveEntry, it.reason) }
        assertEquals(listOf("Super Nintendo", "Ok", "3", "hash-good", "md5-good", "Inner.sfc", ""), kept("Good.sfc"))
        assertEquals(listOf("Super Nintendo", "Failed", "", "", "", "", "the hasher could not read Bad.sfc"),
                     kept("Bad.sfc"))
        assertEquals(listOf("Super Nintendo", "AmbiguousArchive", "", "", "", "",
                            "AmbiguousArchive(candidates=[a.sfc, b.sfc])"), kept("Two.zip"))
        assertEquals(listOf("Super Nintendo", "threw IOException", "", "", "", "", "cannot read Gone.sfc"),
                     kept("Gone.sfc"))
        assertEquals(4, seen.size)
        // A caller that gave a platform gave no folder.
        assertEquals(setOf(""), seen.values.map { it.dirName }.toSet())

        // The call a scan makes, with the file's collection. The hasher inside
        // has to be handed the collection itself: one made again from its
        // short name would have lost the folder and what the collection
        // declares, and the audit would decide by less than a daemon does.
        val collection = CollectionRef("snes", "Super Nintendo", "Nintendo 16-bit", File(roms, "Nintendo 16-bit"),
                                       setOf("sfc", "jud"))
        val scanned = rom("Nintendo 16-bit/A Game/Scanned.jud")
        assertTrue(answers.getValue("Good.sfc") === recording.hashDetailed(scanned.path, collection))
        assertEquals(1, collections.size)
        assertEquals(scanned.path, collections.single().first)
        assertTrue(collection === collections.single().second, "the collection was taken apart on the way")
        assertEquals(4, given.size, "a call with a collection was passed on as one with a platform")
        val kept = recording.seen().getValue(scanned.canonicalPath)
        assertEquals(listOf("snes", "Nintendo 16-bit", "Ok", "hash-good"),
                     listOf(kept.platform, kept.dirName, kept.outcome, kept.hash))
    }
}
