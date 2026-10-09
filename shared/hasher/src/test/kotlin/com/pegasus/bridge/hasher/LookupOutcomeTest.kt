package com.pegasus.bridge.hasher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What a [LookupOutcome] will not hold, and the two functions the tests use
 * to go between it and the form lookups used to answer in.
 */
class LookupOutcomeTest {
    private val contra = GameMetadata(1447, "Contra", "NES", "/Images/2.png", 40)

    // A match is what gets a metadata file written and a game listed. One
    // without a title would be written and then distrusted by the next scan,
    // and one under a virtual id is a file for a game that does not exist.
    @Test fun `a match is a game's own id and a title, or it is not built`() {
        assertEquals(contra, LookupOutcome.Match(contra).game)
        assertEquals(1, LookupOutcome.Match(contra.copy(gameId = 1)).game.gameId)
        // The first base is the last id a game can have: an id is virtual above it.
        assertEquals(1_000_000_000, LookupOutcome.Match(contra.copy(gameId = 1_000_000_000)).game.gameId)

        val refused = listOf(
            contra.copy(gameId = 0), contra.copy(gameId = -1),
            contra.copy(gameId = 1_000_000_001), contra.copy(gameId = 1_100_001_487),
            contra.copy(title = ""), contra.copy(title = "   "), contra.copy(title = "\t\n"),
            GameMetadata(gameId = 1487), GameMetadata())
        for (game in refused) {
            assertFailsWith<IllegalArgumentException>("$game") { LookupOutcome.Match(game) }
        }
    }

    // The rule every lookup the tests write goes through. The fourth row is
    // the one that matters: three of those lookups answer a real id with no
    // title, and taken for a match each would throw out of its scan.
    @Test fun `an answer in the old form becomes the outcome it stood for`() {
        val rows = listOf<Pair<GameMetadata?, LookupOutcome>>(
            null to LookupOutcome.Failed(LookupOutcome.Cause.TRANSPORT),
            GameMetadata(gameId = 0) to LookupOutcome.NotFound,
            GameMetadata(gameId = 0, title = "No Game") to LookupOutcome.NotFound,
            GameMetadata(gameId = 1487) to LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED),
            GameMetadata(gameId = 1487, title = "   ") to LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED),
            GameMetadata(gameId = 1_000_000_000) to LookupOutcome.Failed(LookupOutcome.Cause.MALFORMED),
            GameMetadata(gameId = 1_000_000_001) to
                LookupOutcome.IdOnly(1, LookupOutcome.Compatibility.INCOMPATIBLE, virtualId = 1_000_000_001),
            GameMetadata(gameId = 1_100_001_487) to
                LookupOutcome.IdOnly(1487, LookupOutcome.Compatibility.UNTESTED, virtualId = 1_100_001_487),
            GameMetadata(gameId = 1_200_000_005, title = "Titled All The Same") to
                LookupOutcome.IdOnly(5, LookupOutcome.Compatibility.PATCH_REQUIRED, virtualId = 1_200_000_005),
            contra to LookupOutcome.Match(contra))
        val wrong = rows.filter { (old, outcome) -> old.asOutcome() != outcome }
            .map { (old, outcome) -> "$old: ${old.asOutcome()}, not $outcome" }
        assertEquals(emptyList(), wrong)
    }

    // And back, for the tests of the real lookup: every failure is the null
    // it was, a miss is id 0, a virtual id is the number as it was sent and
    // nothing else, a match is its game.
    @Test fun `an outcome read in the old form is what the lookup used to answer`() {
        for (cause in LookupOutcome.Cause.entries) {
            assertNull(LookupOutcome.Failed(cause, "whatever was said").asLegacy(), "$cause")
        }
        assertEquals(GameMetadata(gameId = 0), LookupOutcome.NotFound.asLegacy())
        assertEquals(GameMetadata(gameId = 1_100_001_487),
                     LookupOutcome.IdOnly(1487, LookupOutcome.Compatibility.UNTESTED, 1_100_001_487).asLegacy())
        assertEquals(contra, LookupOutcome.Match(contra).asLegacy())
    }
}
