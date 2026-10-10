package com.pegasus.bridge.daemon

import com.pegasus.bridge.hasher.GameMetadata
import com.pegasus.bridge.hasher.LookupOutcome
import com.pegasus.bridge.hasher.VirtualGameId

/**
 * The answer of a lookup written as lookups used to answer, with a
 * [GameMetadata] that might be null, as the [LookupOutcome] it stood for.
 *
 * For the lookups the tests are given in place of the real one, which were
 * written when the four answers were told apart by a game's numbers: null is
 * a request that got no answer, gameId 0 a hash RetroAchievements does not
 * know, a [VirtualGameId] a dump it knows and does not consider playable, a
 * real id with a blank title a failure, and any other id with its title a
 * match.
 *
 * A copy of the one the hasher's tests have, word for word, since the two
 * modules' tests share no code. A change to either is a change to both, and
 * LookupDoublesTest asks of this copy what the hasher's tests ask of theirs.
 */
internal fun GameMetadata?.asOutcome(): LookupOutcome {
    if (this == null) return LookupOutcome.Failed(LookupOutcome.Cause.TRANSPORT)
    return LookupOutcome.ofIdAlone(gameId)
        ?: if (title.isBlank()) LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED) else LookupOutcome.Match(this)
}
