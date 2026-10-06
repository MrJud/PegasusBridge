package com.pegasus.bridge.hasher

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a scan makes of the three things a device can say about its connection
 * when a request has failed. The clock is the test's: the rule is about how
 * long the device has gone on saying one of them.
 */
class OfflineVerdictTest {

    private var nowMs = 1_000_000L
    private val verdict = OfflineVerdict { TimeUnit.MILLISECONDS.toNanos(nowMs) }

    // Airplane mode: there is nothing a request could have gone out on, and
    // nothing to wait for.
    @Test fun `no network is offline the first time it is said`() {
        assertTrue(verdict.offline(LinkState.NONE))
        assertTrue(verdict.offline(LinkState.NONE))
    }

    @Test fun `a network that works is never offline, however often a request fails`() {
        repeat(5) {
            assertFalse(verdict.offline(LinkState.PRESENT))
            nowMs += 60_000
        }
    }

    // The four attempts of one lookup: at once, and after 1, 3 and 7 seconds
    // of back-off. The first three are tried again, as on any network. By the
    // fourth the device has gone on saying it for longer than it takes to try
    // a network, and that settles it.
    @Test fun `a network not found to work is offline once that has lasted from one failure to another`() {
        assertFalse(verdict.offline(LinkState.UNVALIDATED), "said once: the network may only just have been joined")
        nowMs += 1_000
        assertFalse(verdict.offline(LinkState.UNVALIDATED))
        nowMs += 2_000
        assertFalse(verdict.offline(LinkState.UNVALIDATED), "3 s is within the time a network takes to be tried")
        nowMs += 4_000
        assertTrue(verdict.offline(LinkState.UNVALIDATED))
        assertTrue(verdict.offline(LinkState.UNVALIDATED))
    }

    @Test fun `the wait is five seconds, to the millisecond`() {
        assertEquals(5_000L, OfflineVerdict.CONFIRM_MS)
        assertFalse(verdict.offline(LinkState.UNVALIDATED))
        nowMs += OfflineVerdict.CONFIRM_MS - 1
        assertFalse(verdict.offline(LinkState.UNVALIDATED))
        nowMs += 1
        assertTrue(verdict.offline(LinkState.UNVALIDATED))
    }

    // A long time between two failures is not a long time unvalidated. The
    // network was found to work in between, or was not there at all and is a
    // new one now: either way it starts again from nothing.
    @Test fun `the wait starts again after anything else has been said`() {
        for (between in listOf(LinkState.PRESENT, LinkState.NONE)) {
            val v = OfflineVerdict { TimeUnit.MILLISECONDS.toNanos(nowMs) }
            assertFalse(v.offline(LinkState.UNVALIDATED), "$between")
            nowMs += 60_000
            v.offline(between)
            nowMs += 60_000
            assertFalse(v.offline(LinkState.UNVALIDATED), "$between: counted from before the network changed")
            nowMs += OfflineVerdict.CONFIRM_MS
            assertTrue(v.offline(LinkState.UNVALIDATED), "$between")
        }
    }

    // The device is asked only when a request fails, so of itself it never
    // says that a network it has its doubts about is carrying requests. The
    // lookup does, with each answer, and the next failure is the first again.
    @Test fun `the wait starts again after a request has been answered`() {
        assertFalse(verdict.offline(LinkState.UNVALIDATED))
        nowMs += 10 * 60_000
        verdict.answered()
        assertFalse(verdict.offline(LinkState.UNVALIDATED), "counted from before a request was answered")
        nowMs += OfflineVerdict.CONFIRM_MS - 1
        assertFalse(verdict.offline(LinkState.UNVALIDATED))
        nowMs += 1
        assertTrue(verdict.offline(LinkState.UNVALIDATED))
    }

    // What Android's four answers come to, every way they can be combined.
    // No network is settled by that alone. With one, anything Android does
    // not say or cannot be believed about is a network that works: the
    // capabilities it would not give, a VPN whatever it claims. Only a
    // network that is not a VPN and has not passed Android's test is doubted.
    @Test fun `only a default network that is no VPN and has not passed the test of Android is doubted`() {
        for (capabilities in listOf(false, true)) for (vpn in listOf(false, true)) for (validated in listOf(false, true)) {
            val said = "capabilities=$capabilities vpn=$vpn validated=$validated"
            assertEquals(LinkState.NONE, LinkState.from(false, capabilities, vpn, validated), "no network, $said")
            val expected = if (capabilities && !vpn && !validated) LinkState.UNVALIDATED else LinkState.PRESENT
            assertEquals(expected, LinkState.from(true, capabilities, vpn, validated), said)
        }
    }
}
