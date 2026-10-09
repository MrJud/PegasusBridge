package com.pegasus.bridge.hasher

/**
 * The answer of a lookup written as lookups used to answer, with a
 * [GameMetadata] that might be null, as the [LookupOutcome] it stood for.
 *
 * For the lookups the tests are given in place of the real one. Most were
 * written when the four answers were told apart by a game's numbers, and what
 * each test says of the scan is said in those terms; they go through here so
 * that none of it had to be written again:
 *
 * - null is a request that got no answer;
 * - gameId 0 is a hash RetroAchievements does not know;
 * - a [VirtualGameId] is a dump it knows and does not consider playable;
 * - a real id with a blank title is a failure, an answer that is not one. Not
 *   a match: [LookupOutcome.Match] refuses to be built from it, and the
 *   pipeline used to take it for a lookup to try again;
 * - any other id, with its title, is a match.
 *
 * The daemon's tests have a copy, since the two modules' tests share no code,
 * and a test of their own that holds it to the same rows.
 */
internal fun GameMetadata?.asOutcome(): LookupOutcome {
    if (this == null) return LookupOutcome.Failed(LookupOutcome.Cause.TRANSPORT)
    val virtual = VirtualGameId.decode(gameId)
    return when {
        gameId == 0     -> LookupOutcome.NotFound
        virtual != null -> LookupOutcome.IdOnly(virtual.first, virtual.second, virtualId = gameId)
        title.isBlank() -> LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED)
        else            -> LookupOutcome.Match(this)
    }
}

/**
 * The other way: what the lookup would have answered for this before it had
 * a type to answer with. For the tests of the real lookup, which say what it
 * makes of each body RetroAchievements can send by comparing its answer with
 * null or with a [GameMetadata]. What they expect stands as it was written.
 *
 * Every failure is null, whatever its cause, and a virtual id is the number
 * alone, with no title: the tests that tell those apart read the outcome.
 */
internal fun LookupOutcome.asLegacy(): GameMetadata? = when (this) {
    is LookupOutcome.Failed  -> null
    LookupOutcome.NotFound   -> GameMetadata(gameId = 0)
    is LookupOutcome.IdOnly  -> GameMetadata(gameId = virtualId)
    is LookupOutcome.Match   -> game
}
