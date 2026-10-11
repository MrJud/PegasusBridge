package com.pegasus.bridge.ra

import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.RcConsoles
import com.pegasus.bridge.hasher.LookupOutcome
import com.pegasus.bridge.hasher.RETROACHIEVEMENTS_URL
import com.pegasus.bridge.hasher.RaApiHashLookup
import com.pegasus.bridge.hasher.RaGameList
import com.pegasus.bridge.hasher.ScanLedger
import com.pegasus.bridge.hasher.ScanVerdicts
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Which RetroAchievements game a file of the library is, in the one order
 * both shells answer in.
 *
 * The scan has hashed the library and knows each file's game for certain, or
 * that it has none. A match used to be made from the file's name all the
 * same: its key looked up in the index, where two dumps of one name share a
 * key, and then its title against a console's list. So the file itself comes
 * first here, by its path, and a name is what is left for a file the scan
 * cannot speak for.
 *
 * The name is looked up in the list of the platform's own console, the one
 * a scan keeps on disk, and a list that cannot be had is an answer of its
 * own. It used to be a no: with RetroAchievements down, or no key set,
 * every game the scan had not matched was shown as having no achievements.
 *
 * One finder is kept for as long as its shell runs. What it remembers is
 * which lists could not be had in the last minute.
 *
 * [fetchBudgetMs] is how long one question may wait for a list. The lookup
 * that fetches it is a scan's, which asks four times and waits out a 429 for
 * as long as it is told: right for a scan, and minutes on the worker of a
 * request a theme gives up on after twenty seconds.
 */
class RaGameFinder(
    private val paths: BridgePaths,
    private val raBaseUrl: String = RETROACHIEVEMENTS_URL,
    private val clock: () -> Long = BridgePaths::epochSeconds,
    private val fetchBudgetMs: Long = 12_000
) {

    /** Whose list of which console. The key is here to tell two accounts apart, and goes nowhere. */
    private data class Source(val user: String, val apiKey: String, val console: Int)

    private class Remembered(val failed: RaMatcher.Answer.Failed, val at: Long)

    // One question at a time for a list: those that arrive together for the
    // same console make one request, and the rest find the list on disk or
    // the failure remembered. Per list and not one lock for all, so that a
    // source that is slow for one console holds nobody who asks for another.
    private val asking = ConcurrentHashMap<Source, Mutex>()
    private val failures = ConcurrentHashMap<Source, Remembered>()

    /**
     * The whole answer: [withoutName], and the name where that has none.
     *
     * Nothing is asked of RetroAchievements while the ledger or the index
     * answers, for a platform it has no console for, for a console nobody
     * can hash for, whose list holds no hash and is refused as no list, or
     * while the console's list on disk is fresh. With no key nothing is
     * asked either, and that is said before the disk is read, so that the
     * answer does not turn on which lists happen to be there.
     */
    suspend fun find(title: String, platformShortName: String, file: String?, user: String, apiKey: String,
                     minScore: Double = 0.60): RaMatcher.Answer {
        withoutName(title, platformShortName, file)?.let { return it }

        val console = RaConsoleMap.consoleId(platformShortName)
        if (console <= 0) return RaMatcher.Answer.None(RaMatcher.NO_CONSOLE)
        if (RcConsoles.resolve(platformShortName, null) is RcConsoles.NoAlgorithm)
            return RaMatcher.Answer.None(RaMatcher.UNSUPPORTED)
        if (user.isBlank() || apiKey.isBlank())
            return RaMatcher.Answer.Failed(RaMatcher.NO_KEY, "No RetroAchievements API key is set")

        val list = when (val had = listFor(Source(user, apiKey, console))) {
            is Had.Not -> return had.failed
            is Had.Got -> had.list
        }
        return RaMatcher.fromList(list.all, title, platformShortName, file, minScore)
            ?.let { RaMatcher.Answer.Found(it) }
            ?: RaMatcher.Answer.None(RaMatcher.NO_NAME_MATCH)
    }

    private sealed interface Had {
        class Got(val list: RaGameList) : Had
        class Not(val failed: RaMatcher.Answer.Failed) : Had
    }

    /**
     * The list of a console, or why there is none, which is remembered for
     * [FAILURE_MEMORY_S]: a source that is down costs one attempt a minute
     * however many games are opened.
     *
     * A new lookup for each question. It keeps a failure for as long as it
     * lives, and one kept longer would never ask again.
     *
     * What a failure says is the lookup's own detail, a status or the kind
     * of an exception. It never holds the address asked, and so not the key.
     */
    private suspend fun listFor(source: Source): Had = asking.getOrPut(source) { Mutex() }.withLock {
        val now = clock()
        failures[source]?.let { if (now - it.at in 0 until FAILURE_MEMORY_S) return@withLock Had.Not(it.failed) }

        val listing = withTimeoutOrNull(fetchBudgetMs) {
            RaApiHashLookup(source.user, source.apiKey, File(paths.cache, RaGameList.DIR), raBaseUrl, clock)
                .gamesOf(source.console)
        }
        val failed = when (listing) {
            is RaApiHashLookup.Listing.Had -> {
                failures.remove(source)
                return@withLock Had.Got(listing.list)
            }
            null -> sourceDown("no answer within ${fetchBudgetMs / 1000} s")
            is RaApiHashLookup.Listing.Failed ->
                if (listing.why.cause == LookupOutcome.Cause.AUTH)
                    RaMatcher.Answer.Failed(RaMatcher.KEY_REFUSED, "RetroAchievements refused the API key")
                else sourceDown(listing.why.detail)
        }
        failures[source] = Remembered(failed, now)
        Had.Not(failed)
    }

    private fun sourceDown(detail: String) = RaMatcher.Answer.Failed(
        RaMatcher.SOURCE_DOWN, "RetroAchievements did not answer" + if (detail.isBlank()) "" else ": $detail")

    /**
     * The answer that needs no name, or null when the name has to decide.
     *
     * A file the scan has settled is answered by what it settled: its game,
     * or that it has none and why. This is asked of the first file a
     * frontend lists for a game, and a no for that file is a no for the
     * game, even when another file of it matched.
     *
     * The index is read only where the ledger cannot speak: for a file no
     * scan has recorded, for a match written before a match carried its
     * game, and when no path was sent. A file the scan has seen and has no
     * verdict for now is not looked up there: the key of a file is made of
     * its name, and the game under it is that of another file named alike.
     * Neither is one the scan saw and could not hash. Both go to the name,
     * which answers as a guess and says so.
     */
    fun withoutName(title: String, platformShortName: String, file: String?): RaMatcher.Answer? {
        if (!file.isNullOrBlank()) {
            when (val verdict = ScanVerdicts.of(paths.cache, file)) {
                ScanVerdicts.Verdict.Unsettled -> return null
                ScanVerdicts.Verdict.Unseen -> Unit
                is ScanVerdicts.Verdict.Settled -> when (verdict.entry.state) {
                    ScanLedger.State.MATCHED ->
                        if (verdict.entry.gameId > 0)
                            return RaMatcher.Answer.Found(scanned(verdict.entry.gameId, title, platformShortName))
                    ScanLedger.State.NOT_FOUND, ScanLedger.State.KNOWN_UNSUPPORTED ->
                        return RaMatcher.Answer.None(RaMatcher.NOT_IN_DATABASE)
                    ScanLedger.State.UNSUPPORTED -> return RaMatcher.Answer.None(RaMatcher.UNSUPPORTED)
                    ScanLedger.State.PLACEHOLDER -> return RaMatcher.Answer.None(RaMatcher.PLACEHOLDER)
                    // Seen and not hashed, so nothing is known of the game:
                    // such a file may well be one RetroAchievements has.
                    ScanLedger.State.UNSUPPORTED_FORMAT, ScanLedger.State.UNHASHABLE,
                    ScanLedger.State.NO_PLAYABLE_ENTRY, ScanLedger.State.AMBIGUOUS_ARCHIVE,
                    // Never settled, and named so that a new state has to be placed.
                    ScanLedger.State.API_RETRY, ScanLedger.State.HASH_FAILED -> return null
                }
            }
        }
        return RaMatcher.fromIndex(index(), title, platformShortName, file)?.let { RaMatcher.Answer.Found(it) }
    }

    /**
     * A game the scan matched, as its metadata file describes it. The title
     * and the platform asked with stand in for what that file does not say:
     * it is the ledger that vouches for the game, and a file that cannot be
     * read does not take the answer back.
     */
    private fun scanned(gameId: Int, title: String, platformShortName: String): RaMatcher.Match {
        val meta = read(paths.metadata(gameId.toString()))
        return RaMatcher.Match(
            gameId = gameId,
            title = meta?.optString("title").orEmpty().ifBlank { title },
            consoleName = RaConsoleMap.consoleName(meta?.optString("platform").orEmpty().ifBlank { platformShortName }),
            score = 1.0,
            method = "hash_index",
            onDevice = true
        )
    }

    private fun index(): JSONObject? = read(paths.discoveryIndex)

    private fun read(file: File): JSONObject? = try {
        if (file.isFile) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val FAILURE_MEMORY_S = 60L
    }
}
