package com.pegasus.bridge.ra

import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.hasher.ScanLedger
import com.pegasus.bridge.hasher.ScanVerdicts
import org.json.JSONObject

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
 */
class RaGameFinder(private val paths: BridgePaths) {

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

    private fun read(file: java.io.File): JSONObject? = try {
        if (file.isFile) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        null
    }
}
