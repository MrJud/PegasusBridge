package com.pegasus.bridge.hasher

import java.util.concurrent.TimeUnit

/**
 * What a platform says of its connection when a lookup asks, which is after a
 * request of its own has failed and never before one.
 */
enum class LinkState {
    /**
     * No network: airplane mode, or Wi-Fi off with no mobile data. The one
     * answer that needs no second look, since there is nothing a request
     * could have gone out on.
     */
    NONE,

    /**
     * A network the platform has not found to lead anywhere: a router whose
     * own line is down, a page that wants a sign-in first. Android puts
     * "connected, no internet" beside such a network.
     *
     * Not certain the way [NONE] is. Every network is in this state for a
     * moment after it is joined, until the platform has tried it, and one that
     * keeps the platform's own test from getting out stays in it while
     * everything else gets through. [OfflineVerdict] is what makes of it.
     */
    UNVALIDATED,

    /** A network the platform has found to work, or anything it cannot tell about. */
    PRESENT;

    companion object {
        /**
         * What Android's answers about its default network come to. They are
         * taken as plain values, read by the Android module and decided here,
         * where the decision has tests.
         *
         * [network] is whether there is a default network at all. Without one
         * nothing else is looked at: it is what airplane mode gives, and Wi-Fi
         * off with no mobile data, and a network Android keeps this app from
         * using, which comes to the same for a request.
         *
         * [capabilities] is whether Android would say anything of that
         * network. It does not when the network went away between the two
         * questions, and that is no evidence of anything.
         *
         * [vpn] settles it as [PRESENT] whatever else is said. Android does
         * not test a VPN unless the VPN asks for it, and marks it validated
         * whatever is under it; and on Android 8 one that carries only some
         * routes has no INTERNET capability while the internet works.
         *
         * [validated] is NET_CAPABILITY_VALIDATED: Android having reached its
         * own test addresses over this network, and what it goes by when it
         * writes "connected, no internet" under a Wi-Fi name. Missing, it is
         * [UNVALIDATED]. NET_CAPABILITY_INTERNET is not asked for: the default
         * network always has it, the VPN above apart, since it says what a
         * network is for and not whether it works. Nor is [validated] proof of
         * a connection, since Android takes a while to notice that one has
         * gone. A request that fails on such a network is retried, as it was.
         */
        fun from(network: Boolean, capabilities: Boolean, vpn: Boolean, validated: Boolean): LinkState = when {
            !network      -> NONE
            !capabilities -> PRESENT
            vpn           -> PRESENT
            validated     -> PRESENT
            else          -> UNVALIDATED
        }
    }
}

/**
 * Whether a failed request is to be put down to the connection, from what the
 * platform says each time one fails. One for a scan.
 *
 * [LinkState.NONE] is offline the first time it is said. [LinkState.UNVALIDATED]
 * is offline only once it has been said twice, [CONFIRM_MS] or more apart,
 * with nothing else said and no request answered in between. That is two
 * requests failed, nothing heard from outside from one to the other, and the
 * platform of the same mind at both, the second after longer than it takes to
 * try a network just joined. A request that fails as the tablet moves from one
 * access point to the next is therefore tried again, as it always was, and
 * finds the network tried and working. A router with no line out stops the
 * scan at the lookup's last attempt, seven seconds in, where it used to take
 * eight lookups, and is called what it is.
 *
 * [answered] is why the lookup tells of every answer it gets. The platform is
 * asked only when a request fails, so without it the wait would run from the
 * first failure of a scan to any later one: on a network that never passes
 * the platform's test and carries requests all the same, a request that fails
 * ten minutes and two hundred answers after the first would not be tried
 * again, and the scan would stop for want of a connection it was using.
 *
 * What is left is such a network on which one lookup fails all four of its
 * attempts, over those seven seconds, while nothing else is answered: that
 * scan stops at one failure and not at eight, and says no connection, which is
 * what the device itself has been saying of that network all along. The next
 * scan resumes it.
 */
class OfflineVerdict(private val nanoTime: () -> Long = System::nanoTime) {

    // When the platform first said UNVALIDATED, with nothing else said and
    // nothing answered since.
    private var unvalidatedSince: Long? = null

    // Two lookups run at a time, and each asks from its own thread.
    @Synchronized
    fun offline(state: LinkState): Boolean = when (state) {
        // Both forget the wait. A network that comes up after there was none
        // has yet to be tried by the platform, and is given the whole of it.
        LinkState.NONE -> { unvalidatedSince = null; true }
        LinkState.PRESENT -> { unvalidatedSince = null; false }
        LinkState.UNVALIDATED -> {
            val now = nanoTime()
            val since = unvalidatedSince ?: now.also { unvalidatedSince = it }
            now - since >= TimeUnit.MILLISECONDS.toNanos(CONFIRM_MS)
        }
    }

    /**
     * A request was answered, whatever the answer said. The network carried
     * it, so whatever the platform went on to say of that network starts from
     * nothing at the next request that fails.
     */
    @Synchronized
    fun answered() { unvalidatedSince = null }

    companion object {
        /**
         * Longer than a network just joined goes untried: Android gives its
         * test three seconds before it falls back to another address. And
         * shorter than the seven seconds of back-off a lookup has, so that the
         * last of its four attempts is the one that settles it.
         */
        const val CONFIRM_MS = 5_000L
    }
}
