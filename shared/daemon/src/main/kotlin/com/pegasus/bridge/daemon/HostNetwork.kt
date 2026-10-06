package com.pegasus.bridge.daemon

import java.net.InetAddress
import java.net.NetworkInterface

/**
 * What a JVM can know of the machine's connection without asking anything
 * outside it, for a scan whose request has just failed.
 *
 * It can know one thing for certain, and that is all this says: no interface
 * that is up has an address a packet could leave the machine from. A laptop
 * with Wi-Fi off and no cable is that, and so is one whose router handed out
 * no address. Everything else is "cannot tell", and is answered as online,
 * which leaves a scan to retry and count failures as it always has. An
 * interface with an address says nothing about what is at the other end of
 * it, and the bridge a container runtime or a virtual machine keeps up looks
 * from here like a network card: on a machine with one of those running, a
 * dead connection is not noticed. Telling them apart by name would be a guess,
 * since a bridge is the real way out on some machines, and a wrong "offline"
 * stops a scan that had nothing wrong with it.
 */
internal object HostNetwork {

    /**
     * One interface, as much of it as the question needs. The two flags are
     * asked for and not held, because each can cost a walk of every adapter on
     * Windows and can throw, and most interfaces are settled by their
     * addresses alone.
     */
    class Link(
        val addresses: List<InetAddress>,
        val isLoopback: () -> Boolean,
        val isUp: () -> Boolean
    )

    /**
     * True only when it is certain that nothing can leave the machine. False
     * for a list that could not be read, and for an empty one: the JVM lists
     * the loopback at the least, so a list with nothing in it has not looked.
     */
    fun offline(links: () -> List<Link>? = ::hostLinks): Boolean = try {
        val all = links()
        !all.isNullOrEmpty() && all.none(::mayCarryTraffic)
    } catch (e: Exception) {
        false
    }

    private fun mayCarryTraffic(link: Link): Boolean =
        link.addresses.any(::leavesTheMachine) &&
        // An interface that cannot say whether it is up is taken to be: it
        // went away while it was being asked, which is no proof of anything.
        try { !link.isLoopback() && link.isUp() } catch (e: Exception) { true }

    // Link-local is what an interface gives itself when nothing answered its
    // request for an address, 169.254.x.x and fe80::, and reaches the cable
    // and no further.
    private fun leavesTheMachine(a: InetAddress): Boolean =
        !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isAnyLocalAddress && !a.isMulticastAddress

    /** The machine's own interfaces, the aliases of each beside it. */
    internal fun hostLinks(): List<Link>? =
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.flatMap { sequenceOf(it) + it.subInterfaces.asSequence() }
            ?.map { nif -> Link(nif.interfaceAddresses.mapNotNull { it.address }, nif::isLoopback, nif::isUp) }
            ?.toList()
}
