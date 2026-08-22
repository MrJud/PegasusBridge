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
        /** RetroAchievements does not cover this platform. No I/O was done. */
        UNSUPPORTED,
        /** Several entries could each be the ROM; a person has to look. */
        AMBIGUOUS_ARCHIVE;

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
         * Meaningless for anything [cacheable] is false for.
         */
        val retryAfterSeconds: Long get() = when (this) {
            MATCHED           -> Long.MAX_VALUE
            NOT_FOUND         -> 14L * 24 * 60 * 60
            UNSUPPORTED       -> 90L * 24 * 60 * 60
            AMBIGUOUS_ARCHIVE -> 7L * 24 * 60 * 60
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
        val e = entries[path] ?: return null
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
        count(state)
    }

    /** Counts a state for the run's summary without changing what is stored. */
    fun count(state: State) {
        synchronized(tally) { tally[state] = (tally[state] ?: 0) + 1 }
    }

    fun counts(): Map<State, Int> = synchronized(tally) { HashMap(tally) }

    /** Files the last scan could not decide, with the candidates that made it so. */
    fun ambiguousArchives(): List<Pair<String, String>> = synchronized(entries) {
        entries.entries
            .filter { it.value.state == State.AMBIGUOUS_ARCHIVE }
            .map { it.key to it.value.detail }
            .sortedBy { it.first }
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

    /** Drops entries for files that are no longer on disk, so it cannot grow forever. */
    fun forget(paths: Set<String>) {
        synchronized(entries) { entries.keys.retainAll(paths) }
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
         */
        const val ALGORITHM_VERSION = 2
    }
}
