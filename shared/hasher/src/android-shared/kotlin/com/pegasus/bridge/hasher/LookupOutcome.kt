package com.pegasus.bridge.hasher

/**
 * What a lookup made of a hash, as one of four things a caller cannot mistake
 * for one another.
 *
 * They used to arrive as a [GameMetadata] that might be null, told apart by its
 * numbers: null for a request that got no usable answer, gameId 0 for a hash
 * RetroAchievements does not know, an id above a thousand million with a blank
 * title for a dump it knows and does not consider playable, any other id with
 * its title for a match. Every reader had to take them apart again, in the
 * right order, and a fifth could be written that was none of them: a real id
 * with no title. The pipeline had a branch for it, and lookups written for
 * the tests answered it.
 */
sealed interface LookupOutcome {
    /**
     * RetroAchievements has the game, and [game] describes it.
     *
     * Refused unless the id is a game's own and the title says something. An id
     * above [VirtualGameId.INCOMPATIBLE_BASE] is no game's: it belongs in
     * [IdOnly]. The title is what makes a match. Blank and not only empty,
     * because a title of spaces would be written to the metadata file and then
     * distrusted by the next scan's cache, so the same ROM would be asked about
     * and counted new every time. A lookup that learns the id and cannot learn
     * the title has failed, and says [Failed].
     */
    data class Match(val game: GameMetadata) : LookupOutcome {
        init {
            require(game.gameId in 1..VirtualGameId.INCOMPATIBLE_BASE) {
                "a match needs a game's own id, and ${game.gameId} is not one"
            }
            require(game.title.isNotBlank()) { "a match needs a title, and game ${game.gameId} came with none" }
        }
    }

    /** RetroAchievements was asked and does not know the hash. A verdict. */
    data object NotFound : LookupOutcome

    /**
     * RetroAchievements knows the hash, but only as a dump it does not consider
     * playable as it is, which it says with a [VirtualGameId]. A verdict too,
     * and not a match: there is no game to describe under [virtualId], the
     * number as it was sent. [gameId] is the real game's, and [reason] why the
     * dump is not counted as that game.
     */
    data class IdOnly(val gameId: Int, val reason: Compatibility, val virtualId: Int) : LookupOutcome

    /**
     * The request never got a usable answer. Not "RetroAchievements does not
     * know this hash": recording a failure as an answer writes a game off, and
     * an incremental rescan will never ask about it again.
     *
     * [detail] is a few words for a log or a person, and may be empty. What
     * [RaApiHashLookup] puts there holds neither the key nor where a request
     * was going: a status, a sentence of its own, or the class of the
     * exception a request ended in, without its message.
     */
    data class Failed(val cause: Cause, val detail: String = "") : LookupOutcome

    /**
     * Why a dump RetroAchievements knows is not one it lets count: the three
     * bases of [VirtualGameId]. [words] says it for a person, and is what the
     * ledger keeps as the reason.
     */
    enum class Compatibility(val words: String) {
        INCOMPATIBLE("incompatible"), UNTESTED("untested"), PATCH_REQUIRED("patch required")
    }

    companion object {
        /**
         * What the number RetroAchievements answers a hash with settles by
         * itself: 0 is a hash it does not know, and a [VirtualGameId] a dump
         * it knows and does not let count. Null for a game's own id, which
         * settles nothing yet: the game has still to be described, and
         * whoever asks does that its own way.
         *
         * The rule was written out wherever an id comes in, the lookup, the
         * recorded answers of an audit and the lookups tests are given, each
         * with the two cases in the same order.
         */
        fun ofIdAlone(gameId: Int): LookupOutcome? {
            if (gameId == 0) return NotFound
            return VirtualGameId.decode(gameId)?.let { (real, reason) -> IdOnly(real, reason, virtualId = gameId) }
        }
    }

    /** What kind of failure a [Failed] was. */
    enum class Cause {
        /** The source refused the key. Nothing after it can be a match until the key changes. */
        AUTH,
        /** A request brought nothing back, and the device says it has no connection. */
        OFFLINE,
        /**
         * The source answered, and what it said was no: a status that is not a
         * success, after every retry where one is retried, or a body that says
         * the request did not succeed.
         */
        REFUSED,
        /** No answer arrived at all, on a device that has a connection or cannot say. */
        TRANSPORT,
        /** An answer arrived with a success status, and is not an answer to what was asked. */
        MALFORMED
    }
}
