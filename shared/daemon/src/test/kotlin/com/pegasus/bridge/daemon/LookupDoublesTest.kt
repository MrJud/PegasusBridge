package com.pegasus.bridge.daemon

import com.pegasus.bridge.hasher.GameMetadata
import com.pegasus.bridge.hasher.LookupOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule the lookups of this module's tests go through, row for row what
 * the hasher's LookupOutcomeTest asks of the function this one is a copy of.
 *
 * Two copies can come apart, and nothing here would have said so: no lookup
 * of the daemon's tests answers a real id with no title, so that line of the
 * copy could turn a failure into a miss with every test still passing, and
 * wait for the first lookup written to rely on it.
 */
class LookupDoublesTest {
    @Test fun `an answer in the old form becomes the outcome it stood for`() {
        val contra = GameMetadata(1447, "Contra", "NES", "/Images/2.png", 40)
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
}
