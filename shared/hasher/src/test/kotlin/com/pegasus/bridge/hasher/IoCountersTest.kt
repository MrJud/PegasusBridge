package com.pegasus.bridge.hasher

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IoCountersTest {

    // The count is the system's, as it writes it for a thread: the first
    // line of several, and the only one that is of reads asked for.
    @Test fun `the number after rchar is what was read`() {
        assertEquals(2884L, IoCounters.parse("rchar: 2884\nwchar: 0\nsyscr: 7\n"))
        assertEquals(2884L, IoCounters.parse("wchar: 9\nrchar: 2884\n"))
        assertNull(IoCounters.parse(""))
        assertNull(IoCounters.parse("wchar: 1"))
        assertNull(IoCounters.parse("rchar: a lot"))
    }

    // Taking the count is itself a read of some hundred bytes, which the
    // system adds once it has given the number out. A file's cost is the
    // difference between two counts, and must not have the first of them in
    // it: nothing read in between is 0.
    @Test fun `taking the count costs the file nothing`() {
        assumeTrue(IoCounters.thread() != null, "no count of what a thread reads on this system")
        val before = IoCounters.thread()
        val after = IoCounters.thread()
        assertEquals(0L, IoCounters.between(before, after), "the count of the count itself")
        assertNull(IoCounters.between(null, after))
        assertNull(IoCounters.between(before, null))
    }
}
