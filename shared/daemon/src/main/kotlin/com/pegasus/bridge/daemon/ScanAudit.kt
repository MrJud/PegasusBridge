package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.hasher.ArchiveAwareHasher
import com.pegasus.bridge.hasher.CollectionRef
import com.pegasus.bridge.hasher.DeviceConnection
import com.pegasus.bridge.hasher.GameMetadata
import com.pegasus.bridge.hasher.HashOutcome
import com.pegasus.bridge.hasher.HashRecipe
import com.pegasus.bridge.hasher.HashResult
import com.pegasus.bridge.hasher.LookupOutcome
import com.pegasus.bridge.hasher.NativeRomHasher
import com.pegasus.bridge.hasher.RETROACHIEVEMENTS_URL
import com.pegasus.bridge.hasher.RaApiHashLookup
import com.pegasus.bridge.hasher.RaHashLookup
import com.pegasus.bridge.hasher.RomHasher
import com.pegasus.bridge.hasher.RomScanPipeline
import com.pegasus.bridge.hasher.RomScanner
import com.pegasus.bridge.hasher.ScanLedger
import com.pegasus.bridge.hasher.VirtualGameId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One scan of the folders it is given, run as a daemon runs it and written
 * down as a table: a row for every file, with what the hasher made of it, what
 * the ledger says of it at the end, and whether its hash was asked about.
 *
 *     --audit=<root>[|<root>...] --out=<tsv>
 *         [--oracle=<tsv>] [--lookup] [--skip-larger-than=<bytes>]
 *         [--skip=<path>[|<path>...]] [--hash-workers=N] [--data-root=<dir>]
 *         [--keep=<dir>]
 *
 * It is how a change to hashing is measured on a real library before and
 * after: `shared/tools/audit_report.py` reads the table, counts it by
 * collection and state, finds the hashes that cannot be right, and compares it
 * with the table of an earlier run. The script this replaces was written
 * beside the repository and was lost with the measurements it had made.
 *
 * What is measured has to be the scan and not a likeness of it, so nothing of
 * the scan is done here. The pipeline is the one [BridgeDaemon.buildScanPipeline]
 * gives a daemon, the hasher is the library a daemon would load, inside the
 * [ArchiveAwareHasher] a daemon puts around it, and the states are read back
 * from the ledger the pipeline saved. The two things put in are a hasher that
 * passes every call on and keeps what came back ([RecordingHasher]), and a
 * lookup that answers from a file ([OracleLookup]).
 *
 * Nothing is asked of RetroAchievements unless `--lookup` says so. Without it
 * a hash is known when the file given as `--oracle` has it, and is answered as
 * one the source does not know when it has not, or when there is no such file:
 * NOT_FOUND in an audit says that the oracle has no answer, and nothing about
 * the game.
 *
 * The scan gets a data root of its own, made beside the table and deleted when
 * the table is written: a ledger left from another run would have the files
 * skipped, and the user's own must not hear of an audit. Being beside the
 * table also puts the copies taken out of archives on the disk the caller
 * chose for the output, and not in a temporary directory that may be memory.
 * With `--lookup` the credentials are read, and only read, from the data root
 * a daemon started on the same arguments would use.
 *
 * `--keep=<dir>` is for measuring what a scan does with what an earlier one
 * left: the scan's data root is that folder, made when it is not there and
 * not deleted. A second audit with the same folder is a rescan, and an audit
 * killed and started again with it is a scan that resumes. The table of such a
 * run has `console`, `hash`, `fileMd5` and `read` empty for every file the
 * scan skipped: those are what the hasher gave, and it was not asked. The
 * folder may not be the data root a daemon would use, for the reason above.
 *
 * `read` is what a file cost: the bytes the thread that hashed it asked the
 * system for while it did ([IoCounters]). The `# hasher` line adds the column
 * up, and `# read` is the same count for the whole process over the scan,
 * which has the ledger, the metadata files and the classes loaded in it too.
 */
object ScanAudit {

    /** What an argument begins with when the process is to audit and not to serve. */
    const val FLAG = "--audit="

    private const val TAG = "ScanAudit"

    /** The table's columns, in the order they are written. */
    internal val COLUMNS = listOf("path", "platform", "dirName", "extension", "size", "state", "console",
                                  "hash", "fileMd5", "archiveEntry", "detail", "asked", "ms", "read")

    /** What the command line came to. */
    private class Request(
        val roots: List<File>,
        val out: File,
        val oracle: File?,
        val lookup: Boolean,
        val largerThan: Long?,
        val skip: List<File>,
        val keep: File?
    )

    /**
     * Runs the audit [args] ask for and returns the status the process ends
     * with: 0 when every file found has its row, 1 when the scan stopped
     * early, could not be run or left a file out, 2 when the arguments make no
     * audit or the table cannot be written where they say.
     *
     * The three parameters after [args] are the ones [BridgeDaemon] has for
     * the same reason: a test hands over a hasher of its own, and stands in
     * for RetroAchievements and for the machine's connection.
     */
    fun run(
        args: Array<String>,
        loadHasher: () -> RomHasher? = { BridgeDaemon.nativeHasher() },
        raBaseUrl: String = RETROACHIEVEMENTS_URL,
        deviceOffline: () -> Boolean = { HostNetwork.offline() }
    ): Int {
        val request = try { parse(args) } catch (e: IllegalArgumentException) {
            System.err.println("audit: ${e.message}")
            return 2
        }
        // Read before anything is scanned: an oracle that cannot be read would
        // otherwise be found out after an hour of hashing.
        val oracle = try { request.oracle?.let { OracleLookup.read(it) } } catch (e: Exception) {
            System.err.println("audit: ${request.oracle}: ${e.message}")
            return 2
        }
        // The count, and the data root the credentials are in, as a daemon
        // started on these arguments would have them.
        val asDaemon = BridgeDaemon.fromArgs(args)

        val native = loadHasher()
        if (native == null) {
            System.err.println("audit: no ROM hasher (${NativeRomHasher.lastError()})")
            return 1
        }

        // Opened before the scan, as the oracle is read before it: a table
        // that cannot be written, a folder given for it say, was found out
        // when the library had been hashed, and as an exception. Emptied by
        // the opening too, and meant to be: were the process to die in native
        // code, the table an earlier run left there would pass for this one's.
        val outFile = request.out.absoluteFile
        try {
            outFile.parentFile.mkdirs()
            FileOutputStream(outFile).close()
        } catch (e: IOException) {
            System.err.println("audit: the table cannot be written: ${e.message}")
            return 2
        }
        val dataRoot = request.keep?.absoluteFile?.also { it.mkdirs() }
            ?: Files.createTempDirectory(outFile.parentFile.toPath(), outFile.name + ".data").toFile()
        try {
            val paths = DaemonPaths.bridgePaths(dataRoot)
            val hasher = RecordingHasher(ArchiveAwareHasher(native, File(dataRoot, "tmp")),
                                         request.largerThan, request.skip.map(::canonical))
            val source: RaHashLookup = if (request.lookup) {
                val ra = Config(BridgePaths(asDaemon.dataRoot), DaemonPaths.appDefaultsFile()).load().ra
                RaApiHashLookup(ra?.user.orEmpty(), ra?.apiKey.orEmpty(), raBaseUrl,
                                DeviceConnection(deviceOffline))
            } else oracle ?: OracleLookup(emptyMap())
            val lookup = CountingLookup(source)
            val pipeline = BridgeDaemon.buildScanPipeline(paths, hasher, lookup, asDaemon.hashWorkers)

            val roots = request.roots.map { it.absolutePath }
            var summary: RomScanPipeline.Summary? = null
            val readBefore = IoCounters.process()
            try {
                summary = runBlocking {
                    pipeline.scan(roots) { p -> println("[${p.processed}/${p.total}] ${p.currentFile}") }
                }
            } catch (t: Throwable) {
                // The pipeline saves its ledger on the way out of a scan that
                // broke, so the rows it got to are still worth writing.
                System.err.println("audit: the scan did not finish: ${t.message ?: t.javaClass.simpleName}")
            }
            // Taken here whether the scan ended or threw: what it read until
            // then is what the rows it got to cost.
            val readAfter = IoCounters.process()
            val readByProcess = if (readBefore != null && readAfter != null) maxOf(0L, readAfter - readBefore)
                                else null

            val rows = rows(File(paths.cache, ScanLedger.FILE_NAME), hasher.seen(), lookup)
            // The recipe the scan kept its verdicts under: made, as the scan
            // makes its own, from what the hasher it was given says it is.
            outFile.writeText(table(HashRecipe(hasher.engine).global, request, oracle, summary,
                                    hasher.seen(), lookup, rows, readByProcess))

            println("audit: ${summary?.total ?: "?"} files found, ${rows.size} rows, " +
                    "${lookup.calls} lookups for ${lookup.distinct} hashes")
            println("audit: wrote $outFile")
            return status(summary, rows.size)
        } finally {
            // A root the caller named is the caller's, and keeping it is what
            // it was named for.
            if (request.keep == null) dataRoot.deleteRecursively()
        }
    }

    /**
     * What the process ends with, for the summary a scan gave, or none when
     * it threw, and the number of rows written.
     *
     * On its own so that the last case can be tested: no scan there is today
     * leaves a file without a row, and the day one does is the day a table
     * short of a file must not pass for the library's.
     */
    internal fun status(summary: RomScanPipeline.Summary?, rows: Int): Int = when {
        summary == null -> 1
        summary.aborted -> { System.err.println("audit: the scan stopped early: ${summary.reason}"); 1 }
        rows != summary.total -> { System.err.println("audit: ${summary.total} files and $rows rows"); 1 }
        else -> 0
    }

    /**
     * Refuses what it does not know. A daemon passes over a flag it has never
     * heard of; here a misspelt `--skip-larger-than` would be an audit that
     * reads every disc image in the library, and says nothing of why it takes
     * the afternoon.
     */
    private fun parse(args: Array<String>): Request {
        fun value(flag: String) = args.firstOrNull { it.startsWith(flag) }?.removePrefix(flag)
        fun files(flag: String) = value(flag).orEmpty().split('|').filter { it.isNotBlank() }.map(::File)

        val known = listOf(FLAG, "--out=", "--oracle=", "--skip-larger-than=", "--skip=",
                           "--hash-workers=", "--data-root=", "--keep=")
        args.firstOrNull { a -> a != "--lookup" && known.none { a.startsWith(it) } }
            ?.let { throw IllegalArgumentException("unknown argument $it") }
        // The first of two would be taken and the second dropped without a
        // word, and the second --skip= is the file that crashes the library.
        known.firstOrNull { flag -> args.count { it.startsWith(flag) } > 1 }?.let {
            val hint = if (it == FLAG || it == "--skip=") ": one list, its paths joined with |" else ""
            throw IllegalArgumentException("$it is given more than once$hint")
        }

        val roots = files(FLAG)
        require(roots.isNotEmpty()) { "no folder to audit in $FLAG" }
        // A folder that is not there scans as an empty one, and the table of a
        // library whose disk was not mounted would be a table of nothing.
        roots.firstOrNull { !it.isDirectory }?.let { throw IllegalArgumentException("not a folder: $it") }
        val out = value("--out=")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: throw IllegalArgumentException("no table to write: give --out=<file>")
        val oracle = value("--oracle=")?.let(::File)
        val lookup = "--lookup" in args
        require(!(lookup && oracle != null)) { "--lookup and --oracle= are two answers to one question; give one" }
        val largerThan = value("--skip-larger-than=")?.let {
            it.toLongOrNull()?.takeIf { n -> n >= 0 }
                ?: throw IllegalArgumentException("--skip-larger-than= takes a number of bytes, not '$it'")
        }
        // A path that is not there skips nothing, and is a path mistyped: the
        // file it was meant for would be read.
        val skip = files("--skip=")
        skip.firstOrNull { !it.exists() }?.let { throw IllegalArgumentException("nothing to skip at $it") }
        val keep = value("--keep=")?.let {
            require(it.isNotBlank()) { "--keep= takes a folder" }
            File(it)
        }
        if (keep != null) {
            require(!keep.isFile) { "--keep= takes a folder, and $keep is a file" }
            // The scan writes its ledger and its metadata files there, and
            // the user's own must not hear of an audit.
            require(canonical(keep) != canonical(BridgeDaemon.fromArgs(args).dataRoot)) {
                "--keep= is the data root of a daemon: an audit keeps its own"
            }
        }
        return Request(roots, out, oracle, lookup, largerThan, skip, keep)
    }

    /** One spelling per file, the one the pipeline keys its ledger by. */
    private fun canonical(file: File): String = RomScanner.canonical(file)

    /**
     * A row for every file the ledger has, and for any the hasher was handed
     * that the ledger has not, which is a file the scan dropped when it was
     * stopped. Sorted by path, so that two tables of one library line up.
     *
     * `platform` and `dirName` are what the scan handed the hasher with the
     * file, the short name of the file's collection and the name of the
     * folder that collection is kept in, and are empty for a file the hasher
     * was never asked about. They are not worked out here from the folder:
     * that would be this file's opinion, and what the scan takes a file's
     * collection to be is one of the things being measured.
     *
     * `asked` is 1 when the file's hash was put to the lookup. One request
     * answers for every file with the same hash, and each of them has a 1.
     *
     * `read` is empty for a file the hasher was not handed, and on a system
     * that does not count what a thread reads.
     */
    private fun rows(ledgerFile: File, seen: Map<String, RecordingHasher.Seen>,
                     lookup: CountingLookup): List<List<String>> {
        // No file at all when the scan found nothing to look at.
        val entries = if (ledgerFile.isFile) JSONObject(ledgerFile.readText()).optJSONObject("entries") else null
        val paths = sortedSetOf<String>().apply {
            entries?.keys()?.forEach { add(it) }
            addAll(seen.keys)
        }
        return paths.map { path ->
            val entry = entries?.optJSONObject(path)
            val s = seen[path]
            val file = File(path)
            listOf(
                path,
                s?.platform.orEmpty(),
                s?.dirName.orEmpty(),
                file.extension.lowercase(),
                (entry?.optLong("fileSize") ?: file.length()).toString(),
                entry?.optString("state") ?: NOT_RECORDED,
                s?.console.orEmpty(),
                s?.hash.orEmpty(),
                s?.fileMd5.orEmpty(),
                s?.archiveEntry.orEmpty(),
                // The hasher's own word where the ledger has none: a file whose
                // read was broken off by the end of the scan is in no ledger.
                entry?.optString("detail").orEmpty().ifEmpty { s?.reason.orEmpty() },
                if (s != null && s.hash.isNotEmpty() && lookup.asked(s.hash)) "1" else "0",
                s?.ms?.toString().orEmpty(),
                s?.read?.toString().orEmpty()
            )
        }
    }

    /**
     * The table as text: lines beginning `#` that say what run this was, the
     * names of the columns, and the rows. Tabs between cells; a tab, a line
     * break or a backslash inside one is written with a backslash before it,
     * so that a row is one line whatever a file is called.
     *
     * The `#` lines are for a person and for the report's grouping by folder.
     * A comparison of two tables passes over them: they hold counts that
     * follow from the rows, and a time.
     *
     * The first of them is [recipe], what the scan reached its verdicts
     * with ([HashRecipe.global]): the library that hashed, and a number for
     * each table a file is judged by. Two tables whose rows differ are of
     * two builds, and this line says in what the builds differ.
     */
    private fun table(recipe: String, request: Request, oracle: OracleLookup?,
                      summary: RomScanPipeline.Summary?,
                      seen: Map<String, RecordingHasher.Seen>, lookup: CountingLookup,
                      rows: List<List<String>>, readByProcess: Long?): String {
        val head = mutableListOf<List<String>>()
        head += listOf("# recipe", recipe)
        request.roots.forEach { head += listOf("# root", canonical(it)) }
        request.largerThan?.let { head += listOf("# skip-larger-than", it.toString()) }
        request.skip.forEach { head += listOf("# skip", canonical(it)) }
        head += listOf("# answers", when {
            request.lookup -> "retroachievements"
            oracle != null -> "${oracle.size} recorded"
            else           -> "none"
        })
        head += listOf("# files", "${summary?.total ?: "?"} found, ${rows.size} rows")
        head += listOf("# lookups", "${lookup.calls} for ${lookup.distinct} hashes")
        head += listOf("# outcomes", seen.values.groupingBy { it.outcome }.eachCount()
            .toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" })
        // Every file the hasher was handed, with the ones the audit answered
        // for itself: those cost nothing and say so in their row.
        val counted = seen.values.mapNotNull { it.read }
        head += listOf("# hasher", "${seen.size} files handed, " +
            if (seen.isNotEmpty() && counted.isEmpty()) "bytes not counted on this system"
            else "${counted.sum()} bytes read for them")
        readByProcess?.let { head += listOf("# read", "$it bytes by the process during the scan") }
        request.keep?.let { head += listOf("# keep", canonical(it)) }
        if (summary == null) head += listOf("# stopped", "the scan did not finish")
        else if (summary.aborted) head += listOf("# stopped", summary.reason)
        head += listOf("# written", (System.currentTimeMillis() / 1000L).toString())
        return (head + listOf(COLUMNS) + rows)
            .joinToString("\n", postfix = "\n") { row -> row.joinToString("\t", transform = ::cell) }
    }

    private fun cell(text: String): String = buildString {
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    /** The state of a file the hasher was handed and the ledger does not have. */
    internal const val NOT_RECORDED = "NOT_RECORDED"

    /**
     * Passes every call to [delegate] as it came and keeps, for each file,
     * what it was asked with and what it answered.
     *
     * It overrides every `hashDetailed` there is and hands the arguments on
     * untouched. One that took a call apart and put it together again would
     * give the hasher inside something other than what the scan gave, and
     * the audit would then decide differently from a daemon without a word.
     * A `hashDetailed` added to [RomHasher] has to be added here.
     *
     * The answer is kept by the name of its class, and only what an
     * [HashOutcome.Ok] holds is taken apart. A kind of outcome that did not
     * exist when this was written is recorded under its own name with its
     * text, and nothing here has to be told of it.
     *
     * Two kinds of file are answered for without being read, both as a
     * failure whose reason begins "audit:": one larger than [largerThan],
     * because a library's disc images take hours and most audits are about
     * something else, and one named in [skip], a file or a folder, because
     * native code that crashes or never returns on one file takes the whole
     * process with it, and that file has to be stepped round until it is
     * fixed.
     */
    internal class RecordingHasher(
        private val delegate: RomHasher,
        private val largerThan: Long?,
        private val skip: List<String>
    ) : RomHasher {

        /**
         * [platform] is the short name of the collection the scan gave, and
         * [dirName] the name of that collection's folder; a caller that
         * gave a platform and no collection has no folder to record.
         */
        class Seen(val platform: String, val dirName: String, val outcome: String, val console: String,
                   val hash: String, val fileMd5: String, val archiveEntry: String, val reason: String,
                   val ms: Long, val read: Long?)

        private val seen = ConcurrentHashMap<String, Seen>()

        /** What was recorded, by the canonical path of each file. */
        fun seen(): Map<String, Seen> = HashMap(seen)

        // The pipeline calls none of the three; passed on, so that a caller
        // that does gets the answer it would have had.
        override fun hash(path: String): HashResult? = delegate.hash(path)
        override fun hash(path: String, platform: String): HashResult? = delegate.hash(path, platform)
        override fun hashForConsole(path: String, consoleId: Int): HashOutcome =
            delegate.hashForConsole(path, consoleId)

        // The scan reads this for the recipe it keeps its verdicts under.
        // Left to the interface it would say "none" for any library, and an
        // audit would keep its verdicts under another number than a daemon.
        override val engine: String get() = delegate.engine

        override fun hashDetailed(path: String, platform: String): HashOutcome =
            recorded(path, platform, "") { delegate.hashDetailed(path, platform) }

        // The one a scan calls. The collection goes on as the object it came
        // as: made again from its short name, the hasher inside would be told
        // of a folder by that name, with nothing declared.
        override fun hashDetailed(path: String, collection: CollectionRef): HashOutcome =
            recorded(path, collection.shortName, collection.dirName) { delegate.hashDetailed(path, collection) }

        private fun recorded(path: String, platform: String, dirName: String,
                             ask: () -> HashOutcome): HashOutcome {
            val key = canonical(File(path))
            val started = System.nanoTime()
            // The scan makes this call as one blocking call on one thread, and
            // the library's own reads are made on it too, so the thread's
            // count before and after is what the file cost. It is bytes asked
            // of the system, whether they came from the disk or from memory.
            val readBefore = IoCounters.thread()
            fun keep(outcome: String, result: HashResult?, reason: String) {
                val readAfter = IoCounters.thread()
                seen[key] = Seen(platform, dirName, outcome, result?.consoleId?.toString().orEmpty(),
                                 result?.hash.orEmpty(), result?.fileMd5.orEmpty(),
                                 result?.archiveEntry.orEmpty(), reason,
                                 (System.nanoTime() - started) / 1_000_000,
                                 if (readBefore != null && readAfter != null) maxOf(0L, readAfter - readBefore)
                                 else null)
            }
            val outcome = refusal(key)?.let { HashOutcome.Failed(it) } ?: try {
                // The last of these lines before a crash names the files that
                // were being read, as many as there are hash workers.
                BridgeLog.d(TAG, "hashing $path")
                ask()
            } catch (t: Throwable) {
                // The pipeline turns this into a failure of its own, or stops
                // on it. Either way the file was asked about.
                keep("threw ${t.javaClass.simpleName}", null, t.message.orEmpty())
                throw t
            }
            val name = outcome.javaClass.simpleName
            when (outcome) {
                is HashOutcome.Ok     -> keep(name, outcome.result, "")
                is HashOutcome.Failed -> keep(name, null, outcome.reason)
                else                  -> keep(name, null, outcome.toString())
            }
            return outcome
        }

        private fun refusal(canonicalPath: String): String? = when {
            skip.any { canonicalPath == it ||
                       canonicalPath.startsWith(it.trimEnd(File.separatorChar) + File.separator) } ->
                "audit: skipped"
            largerThan != null && File(canonicalPath).length() > largerThan ->
                "audit: larger than $largerThan"
            else -> null
        }
    }

    /**
     * How many bytes have been asked of the system so far, by the calling
     * thread or by the whole process: the number after `rchar:` in the `io`
     * file Linux keeps under /proc for each. It counts what `read` and its
     * kin returned, native code's reads with the rest, and counts a byte that
     * came from memory as one that came from the disk.
     *
     * Null where there is no such file, which is every system but Linux, or
     * where it cannot be read; the audit then leaves the column empty.
     */
    internal object IoCounters {
        fun parse(text: String): Long? = text.lineSequence()
            .firstOrNull { it.startsWith("rchar:") }?.substringAfter(':')?.trim()?.toLongOrNull()

        fun thread(): Long? = of("/proc/thread-self/io")
        fun process(): Long? = of("/proc/self/io")

        private fun of(path: String): Long? = try { parse(File(path).readText()) } catch (e: Exception) { null }
    }

    /**
     * Answers from a file of hashes whose game is already known, so that an
     * audit asks nobody: `hash`, `gameId`, then the date and the source of
     * the answer, which are for a person, then a title, which may be left
     * out. Tabs between them; a line beginning `#` and a first line that
     * names the columns are passed over.
     *
     * A hash the file does not have is answered as one RetroAchievements
     * does not know. That is the only answer that lets the scan go on to its
     * end and keeps the file's row, and the table says where its answers
     * came from. An id of 0 is that answer written down, and an id above a
     * thousand million is handed back as the [VirtualGameId] it is.
     *
     * A real id is given a title even where the file has none: a match has
     * to have one, and an id without is a lookup that failed.
     */
    internal class OracleLookup(private val answers: Map<String, LookupOutcome>) : RaHashLookup {

        val size: Int get() = answers.size

        override suspend fun lookup(hash: String): LookupOutcome = answers[hash] ?: LookupOutcome.NotFound

        companion object {
            /** Throws, naming the line, on one that is not an answer: an oracle half read is a wrong one. */
            fun read(file: File): OracleLookup {
                val answers = LinkedHashMap<String, LookupOutcome>()
                val ids = HashMap<String, Int>()
                file.readLines().forEachIndexed { index, line ->
                    if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                    val cells = line.split('\t')
                    val hash = cells[0].trim().lowercase()
                    if (hash == "hash") return@forEachIndexed
                    val gameId = cells.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
                    require(hash.isNotEmpty() && gameId != null) {
                        "line ${index + 1} is not a hash and a game id: $line"
                    }
                    val known = ids.put(hash, gameId)
                    require(known == null || known == gameId) {
                        "line ${index + 1} gives $hash a second game, $gameId after $known"
                    }
                    val title = cells.getOrNull(4)?.trim().orEmpty().ifEmpty { "game $gameId" }
                    answers[hash] = LookupOutcome.ofIdAlone(gameId)
                        ?: LookupOutcome.Match(GameMetadata(gameId = gameId, title = title))
                }
                return OracleLookup(answers)
            }
        }
    }

    /**
     * Remembers every hash [inner] was asked about, and is otherwise [inner]:
     * the three things a scan stops on are passed through, so that with
     * `--lookup` an audit ends where a daemon's scan would.
     */
    internal class CountingLookup(private val inner: RaHashLookup) : RaHashLookup {
        private val askedAbout = ConcurrentHashMap<String, AtomicInteger>()

        override suspend fun lookup(hash: String): LookupOutcome {
            askedAbout.computeIfAbsent(hash) { AtomicInteger() }.incrementAndGet()
            return inner.lookup(hash)
        }

        override val consecutiveFailures: Int get() = inner.consecutiveFailures
        override val authRejected: Boolean get() = inner.authRejected
        override val offline: Boolean get() = inner.offline

        fun asked(hash: String): Boolean = askedAbout.containsKey(hash)
        val calls: Int get() = askedAbout.values.sumOf { it.get() }
        val distinct: Int get() = askedAbout.size
    }
}
