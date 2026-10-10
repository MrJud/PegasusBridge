package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import org.json.JSONObject
import java.io.File

/**
 * What happened to every file a scan looked at — not only the ones that matched.
 *
 * The only record a scan left was `metadata/<gameId>.json`, written on success.
 * Everything else — a ROM RetroAchievements does not have, a file that would not
 * hash, an archive with three plausible ROMs in it, a lookup the API refused —
 * left no trace at all, and the *absence* of a metadata file was then read as
 * "not scanned yet". So the two questions a person actually asks about a scan
 * were both unanswerable:
 *
 * - *Why is this game missing?* Because it was never hashed, or because the
 *   database does not have it, or because the API was throttled at the time. The
 *   metadata directory says the same nothing to all three.
 * - *What will the next scan do?* Everything again, since a miss is not recorded.
 *
 * ── The distinction the whole file exists for ──────────────
 *
 * [State.NOT_FOUND] is an *answer*: the source was asked and said no. It is
 * worth remembering, and worth not asking again for a while.
 *
 * [State.API_RETRY] is the source declining to answer — a timeout, a 429, a
 * quota, a refused login. Recording one of those as a miss is precisely the bug
 * that cost a whole RetroAchievements run: 85 of 913 answered, the rest refused,
 * and every refusal cached as "this game has no achievements". So it is written
 * with no TTL and always retried.
 */
class ScanLedger(private val file: File) {

    enum class State {
        /** The source answered with a game. */
        MATCHED,
        /** The source answered, and the answer was no. Cacheable, with a TTL. */
        NOT_FOUND,
        /** The file could not be hashed. Cheap to retry; the file may be fixed. */
        HASH_FAILED,
        /** The source never answered. Never cached as a verdict. */
        API_RETRY,
        /**
         * The file's collection is of a console nobody can hash for:
         * RetroAchievements has none for it, or rcheevos no algorithm. No I/O
         * was done.
         */
        UNSUPPORTED,
        /** Several entries could each be the ROM; a person has to look. */
        AMBIGUOUS_ARCHIVE,
        /**
         * The hasher knew before trying that it cannot hash this file — a disc
         * descriptor inside an archive, whose tracks are not extracted. Not a
         * broken file and not a miss: the same answer until the hasher changes.
         *
         * Also a file called `.zip` or `.7z` that does not open as one. That
         * one is broken, and stays so until it is another file.
         *
         * And a file the console of its collection was handed and refused:
         * a cartridge cut short, a disc image with no game of that console
         * on it. The console will say the same at every scan, until the
         * file is another.
         */
        UNHASHABLE,
        /**
         * The source knows the dump, as one of a game it names, and does not let
         * it count as that game: untested, incompatible, or in need of a patch.
         * An answer, with the game's own id beside it and the reason in the
         * detail. It was kept as a [NOT_FOUND] under the number the source sent,
         * above a thousand million, and a reader of the ledger could not tell a
         * dump the source holds from one it has never heard of without knowing
         * what such a number means.
         */
        KNOWN_UNSUPPORTED,
        /**
         * The collection can be hashed and this file cannot: a compressed
         * disc image this build has no reader for, a container rcheevos
         * does not read at all, a file that is no game. Known from the
         * file's name and its collection, so no I/O was done. Handed to
         * rcheevos, such a file gave the hash of its container, and was
         * asked about and kept as a game the database lacks.
         *
         * Also an archive whose one entry for the collection is such a
         * file. That much I/O was done: the archive was listed.
         */
        UNSUPPORTED_FORMAT,
        /**
         * An archive that opened, and holds nothing that is a game of its
         * collection: a patch, the artwork of a set, the files of an
         * emulator. The entries it does hold are in the detail. It was
         * hashed as the file it is, which to rcheevos is the MD5 of its
         * name, and that was asked about and kept as a miss.
         */
        NO_PLAYABLE_ENTRY;

        /**
         * Whether this outcome may be trusted on a later run at all.
         *
         * The two that may not are the two that are not verdicts. [API_RETRY] is
         * the source declining to answer, and remembering one as though it were
         * an answer is the bug that cost a whole RetroAchievements run. A
         * [HASH_FAILED] file is one the user is likely to replace, and the cost of
         * asking again is one local read.
         *
         * Kept separate from [retryAfterSeconds] rather than expressed as a TTL of
         * zero, which is what the first version did — and a rescan inside the same
         * second then found `now - checkedAt` was 0, not *greater* than 0, and
         * skipped the file. A "never cache this" written as a duration is a
         * boundary condition waiting to happen.
         */
        val cacheable: Boolean get() = this != API_RETRY && this != HASH_FAILED

        /**
         * How long this verdict stands before the file is asked about again.
         *
         * A miss keeps for two weeks: RetroAchievements gains hashes continuously,
         * so "no" is true rather than permanent, and a fortnight is short enough
         * that a newly supported game turns up without a rescan being a full one.
         *
         * An unsupported platform keeps for a season — the set of consoles the
         * service covers moves slowly, and re-deciding it costs nothing anyway
         * because it is decided before any I/O.
         *
         * A file the hasher cannot hash keeps for a month. Only a new hasher can
         * change that answer, and the change that does bumps [ALGORITHM_VERSION],
         * which redoes it at once; the TTL is the backstop for one that did not.
         *
         * A dump the source knows and does not support keeps for a month as well,
         * twice as long as a miss. A miss ends when somebody links the hash to a
         * game, which happens every day. This ends when somebody tests the dump
         * and the source changes its mind about it, which is rarer, and until
         * then the answer is the same one.
         *
         * A format nobody reads keeps for a season, as an unsupported
         * platform does and for its reason: it is decided again on every
         * scan, before any I/O, and the lists it is decided from move with
         * a build and not with the days. The one reached by listing an
         * archive is not decided again and does stand for the season.
         *
         * An archive with no game in it keeps for a month, as a file the
         * hasher cannot hash does. What would change the answer is another
         * archive under the same name, which its size and date give away,
         * or a longer list of what its platform runs, which is a new build.
         *
         * Meaningless for anything [cacheable] is false for.
         */
        val retryAfterSeconds: Long get() = when (this) {
            MATCHED           -> Long.MAX_VALUE
            NOT_FOUND         -> 14L * 24 * 60 * 60
            UNSUPPORTED       -> 90L * 24 * 60 * 60
            AMBIGUOUS_ARCHIVE -> 7L * 24 * 60 * 60
            UNHASHABLE        -> 30L * 24 * 60 * 60
            KNOWN_UNSUPPORTED -> 30L * 24 * 60 * 60
            UNSUPPORTED_FORMAT -> 90L * 24 * 60 * 60
            NO_PLAYABLE_ENTRY -> 30L * 24 * 60 * 60
            HASH_FAILED, API_RETRY -> 0
        }
    }

    data class Entry(
        val state: State,
        val checkedAt: Long,
        val fileSize: Long,
        val lastModified: Long,
        val algorithmVersion: Int,
        val gameId: Int = 0,
        /** Free text: the failure class, or the ambiguous candidates. */
        val detail: String = ""
    )

    private val entries = HashMap<String, Entry>()

    /** Counts by state for the scan just run, which is what `/jobs/{id}` reports. */
    private val tally = HashMap<State, Int>()

    /**
     * The archives the scan just run left undecided, path to candidates, kept per
     * run beside [tally] and under its lock.
     *
     * Not read back out of [entries], which is what this used to do: those keep
     * the verdicts of every root, not only the ones scanned, and a scan of one
     * collection named the ambiguous archives of all the others — archives its
     * own [tally] did not count.
     */
    private val undecided = HashMap<String, String>()

    init { load() }

    private fun load() {
        if (!file.isFile) return
        try {
            val root = JSONObject(file.readText())
            if (root.optInt("schemaVersion") != SCHEMA_VERSION) {
                BridgeLog.i(TAG, "ledger is from an older schema; starting a new one")
                return
            }
            val map = root.optJSONObject("entries") ?: return
            for (key in map.keys()) {
                val e = map.optJSONObject(key) ?: continue
                val state = runCatching { State.valueOf(e.optString("state")) }.getOrNull() ?: continue
                entries[key] = Entry(
                    state = state,
                    checkedAt = e.optLong("checkedAt"),
                    fileSize = e.optLong("fileSize"),
                    lastModified = e.optLong("lastModified"),
                    algorithmVersion = e.optInt("algorithmVersion"),
                    gameId = e.optInt("gameId"),
                    detail = e.optString("detail")
                )
            }
        } catch (t: Throwable) {
            // A ledger is a cache of decisions, never user data. Losing it costs
            // one thorough scan, so an unreadable one is simply started again.
            BridgeLog.w(TAG, "ledger unreadable, starting a new one: ${t.message}")
            entries.clear()
        }
    }

    /**
     * Whether [path] can be skipped, given what is on disk now.
     *
     * Answers false — ask again — whenever anything the verdict depended on has
     * moved: the file's size or mtime, or the version of the hashing and archive
     * selection that produced it. That last one is what makes a policy change
     * take effect on a library that has already been scanned; without it, the
     * incremental skip would preserve every decision the old rule made.
     */
    fun canSkip(path: String, size: Long, modified: Long, now: Long): Entry? {
        // Under the same lock as [record]. The producers call both at once, and an
        // unlocked read of a HashMap another thread is resizing can come back null
        // for an entry that is there — a spurious rehash at best.
        val e = synchronized(entries) { entries[path] } ?: return null
        if (!e.state.cacheable) return null
        if (e.fileSize != size || e.lastModified != modified) return null
        if (e.algorithmVersion != ALGORITHM_VERSION) return null
        val ttl = e.state.retryAfterSeconds
        if (ttl != Long.MAX_VALUE && now - e.checkedAt > ttl) return null
        return e
    }

    fun record(
        path: String,
        state: State,
        size: Long,
        modified: Long,
        now: Long,
        gameId: Int = 0,
        detail: String = ""
    ) {
        synchronized(entries) {
            entries[path] = Entry(state, now, size, modified, ALGORITHM_VERSION, gameId, detail)
        }
        count(path, state, detail)
    }

    /**
     * Counts a verdict an earlier scan reached, the one [canSkip] returned for
     * [path], in this run's summary without changing what is stored.
     *
     * The path too, not only the state: an ambiguous archive skipped this way is
     * still one this scan has to name.
     */
    fun count(path: String, settled: Entry) = count(path, settled.state, settled.detail)

    private fun count(path: String, state: State, detail: String) {
        synchronized(tally) {
            tally[state] = (tally[state] ?: 0) + 1
            if (state == State.AMBIGUOUS_ARCHIVE) undecided[path] = detail
        }
    }

    fun counts(): Map<State, Int> = synchronized(tally) { HashMap(tally) }

    /**
     * The archives the scan just run could not decide, with the candidates that
     * made it so: the ones its [counts] give as [State.AMBIGUOUS_ARCHIVE], by path.
     */
    fun ambiguousArchives(): List<Pair<String, String>> = synchronized(tally) {
        undecided.entries.map { it.key to it.value }.sortedBy { it.first }
    }

    fun save(writeAtomic: (File, String) -> Unit) {
        val map = JSONObject()
        synchronized(entries) {
            for ((path, e) in entries) {
                map.put(path, JSONObject()
                    .put("state", e.state.name)
                    .put("checkedAt", e.checkedAt)
                    .put("fileSize", e.fileSize)
                    .put("lastModified", e.lastModified)
                    .put("algorithmVersion", e.algorithmVersion)
                    .also { j -> if (e.gameId > 0) j.put("gameId", e.gameId) }
                    .also { j -> if (e.detail.isNotEmpty()) j.put("detail", e.detail) })
            }
        }
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("algorithmVersion", ALGORITHM_VERSION)
            .put("updatedAt", System.currentTimeMillis() / 1000L)
            .put("count", map.length())
            .put("entries", map)
        runCatching { writeAtomic(file, payload.toString()) }
            .onFailure { BridgeLog.w(TAG, "could not write the ledger: ${it.message}") }
    }

    /**
     * Drops the entries a scan of [roots] has shown to be stale, so the ledger
     * cannot grow for ever: those under one of [roots] that the scan did not
     * find, and those whose file is gone, wherever it was. Paths are canonical,
     * as the pipeline records them.
     *
     * Everything else stays. Keeping only what [found] holds threw away the
     * verdicts of every root the scan was not given, its misses with them, so a
     * theme that scans one collection at a time asked about the misses of all
     * the others again on every round.
     */
    fun forget(found: Set<String>, roots: List<String>) {
        val under = roots.map { it.trimEnd('/', '\\') + File.separator }
        synchronized(entries) {
            entries.keys.removeIf { path ->
                path !in found && (under.any { path.startsWith(it) } || !File(path).exists())
            }
        }
    }

    companion object {
        private const val TAG = "ScanLedger"
        const val FILE_NAME = "scan-ledger.json"
        const val SCHEMA_VERSION = 1

        /**
         * Bumped whenever hashing or archive selection changes what a file resolves
         * to, which invalidates every verdict the previous rule reached.
         *
         * 2: archive selection stopped being "the largest entry" and became
         * [ArchiveSelector] — extension filter, descriptor first, then the entry
         * named after the archive. Any archive decided under version 1 has to be
         * decided again, because the old rule could have hashed a patch or a bonus
         * disc and recorded the result as a miss.
         *
         * 3: the desktop extracted every archive entry to a temp file named
         * `.bin`, and rcheevos picks its algorithm from the extension — so a `.nes`
         * or `.nds` inside a zip was hashed as whatever a `.bin` is taken for, and
         * the miss that came back said nothing about the game. Those verdicts must
         * not outlive the fix. The cost is bounded: a match is skipped through its
         * metadata before the ledger is asked, and an unsupported platform is
         * decided before any I/O, so what gets redone is the misses and the
         * ambiguous archives.
         *
         * 4: the Android scan service had a loop of its own, which still hashed
         * the largest entry of an archive, the rule 2 retired, and wrote the
         * virtual ids it was answered into this ledger under the number that
         * stood here: 3, for verdicts 3 does not describe. The service runs the
         * pipeline now and an archive resolves there as it does on the desktop,
         * so what that loop recorded is asked about again. The number is one
         * for both shells: the desktop redoes its misses and its ambiguous
         * archives once more, with nothing changed in how it reaches them.
         *
         * The one counter for what a file resolves to. A second version kept
         * elsewhere for part of the same decision would drift from this one, and
         * bumping either would leave the other's verdicts standing.
         */
        const val ALGORITHM_VERSION = 4
    }
}
