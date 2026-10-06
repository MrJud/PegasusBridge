package com.pegasus.bridge.daemon

import com.pegasus.bridge.daemon.HostNetwork.Link
import java.net.InetAddress
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a desktop may say it has no connection, on lists of interfaces made up
 * here: what this machine's are like is not the test's to depend on, and no
 * test may take its network away.
 *
 * It says so only when nothing could leave the machine. Every case of doubt
 * has to come out as online, because the other answer stops a scan.
 */
class HostNetworkTest {

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun link(vararg addresses: String, up: Boolean = true, loopback: Boolean = false) =
        Link(addresses.map(::address), { loopback }, { up })

    private val lo = link("127.0.0.1", "::1", loopback = true)

    private fun offline(vararg links: Link) = HostNetwork.offline { links.toList() }

    @Test fun `only the loopback is offline`() {
        assertTrue(offline(lo))
    }

    // Wi-Fi switched off or a cable pulled: the card is still listed, down,
    // and may keep the address it had.
    @Test fun `a card that is down is offline, with an address or without`() {
        assertTrue(offline(lo, link(up = false)))
        assertTrue(offline(lo, link("192.168.1.20", up = false)))
    }

    // Up, and nobody answered its request for an address.
    @Test fun `a card with only the address it gave itself is offline`() {
        assertTrue(offline(lo, link("169.254.17.4", "fe80::1c2d:3eff:fe4f:5a6b")))
        assertTrue(offline(lo, link()))
    }

    // Addresses that are not a place on any network: the one that stands for
    // "none yet", and the ones a group listens on.
    @Test fun `a card with no address of its own is offline`() {
        assertTrue(offline(lo, link("0.0.0.0")))
        assertTrue(offline(lo, link("::")))
        assertTrue(offline(lo, link("224.0.0.251")))
        assertTrue(offline(lo, link("ff05::2")))
    }

    @Test fun `a card that is up with an address is not offline, whatever kind of address`() {
        assertFalse(offline(lo, link("192.168.1.20")))
        assertFalse(offline(lo, link("10.0.0.5")))
        assertFalse(offline(lo, link("203.0.113.9")))
        assertFalse(offline(lo, link("2001:db8::7")))
        assertFalse(offline(lo, link("fe80::1c2d:3eff:fe4f:5a6b", "192.168.1.20")))
        // One among several is enough.
        assertFalse(offline(lo, link(up = false), link("169.254.17.4"), link("192.168.1.20")))
    }

    // A loopback is no way out even where it has been given an address that
    // looks like one.
    @Test fun `a loopback with an ordinary address is still offline`() {
        assertTrue(offline(link("127.0.0.1", "192.168.1.20", loopback = true)))
    }

    // The doubts. A list that could not be read, one the JVM returned as
    // nothing, and one with nothing in it, which the JVM never gives: it
    // lists the loopback at least.
    @Test fun `a list that could not be read, or is empty, is not offline`() {
        assertFalse(HostNetwork.offline { throw SocketException("No network interfaces configured") })
        assertFalse(HostNetwork.offline { null })
        assertFalse(offline())
    }

    // On Windows an adapter that goes away while it is asked throws. It had
    // an address, so it may have been the way out.
    @Test fun `a card that cannot say whether it is up is not offline`() {
        val vanished = Link(listOf(address("192.168.1.20")), { false }, { throw SocketException("gone") })
        assertFalse(offline(lo, vanished))
        val unknownKind = Link(listOf(address("192.168.1.20")), { throw SocketException("gone") }, { true })
        assertFalse(offline(lo, unknownKind))
    }

    // The flags can cost a walk of every adapter each, and are not asked of
    // an interface its addresses have already settled.
    @Test fun `an interface with no address to leave by is not asked whether it is up`() {
        var asked = 0
        val settled = Link(listOf(address("169.254.17.4")), { asked++; false }, { asked++; true })
        assertTrue(offline(lo, settled))
        assertEquals(0, asked)
    }

    // The one thing asked of the machine these tests run on: that its real
    // list can be read and has the loopback in it, with its address and its
    // flags. Whether the machine is connected is not asked.
    @Test fun `the interfaces of this machine are read, the loopback among them`() {
        val links = HostNetwork.hostLinks().orEmpty()
        assertTrue(links.any { it.isLoopback() && it.isUp() && it.addresses.any(InetAddress::isLoopbackAddress) },
                   "no loopback among ${links.size} interfaces")
    }
}
