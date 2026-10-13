package com.pegasus.bridge.hasher

import java.io.File

/**
 * How many bytes have been asked of the system so far, by the calling
 * thread or by the whole process: the number after `rchar:` in the `io`
 * file Linux keeps under /proc for each. It counts what `read` and its
 * kin returned, native code's reads with the rest, and counts a byte that
 * came from memory as one that came from the disk.
 *
 * Null where there is no such file, which is every system but Linux, or
 * where it cannot be read: the system keeps the file of an Android app to
 * itself unless the app is one that can be debugged. An audit then leaves
 * its column empty, and the metrics of a scan have no count of bytes.
 */
object IoCounters {
    /**
     * A count as the system wrote it down, and what taking it cost: the
     * length of the text, which the system adds to the count once it has
     * handed the text over.
     */
    class Count(val bytes: Long, val own: Int)

    fun parse(text: String): Long? = text.lineSequence()
        .firstOrNull { it.startsWith("rchar:") }?.substringAfter(':')?.trim()?.toLongOrNull()

    fun thread(): Count? = of("/proc/thread-self/io")
    fun process(): Count? = of("/proc/self/io")

    /**
     * What was read from one count to the next, or null when either is
     * missing. Taking the first count is a read like any other, of some
     * hundred bytes, and is in the second: left in, every file the
     * hasher was handed cost that much more than it had, and one that
     * was never opened, an arcade set, did not come to 0.
     */
    fun between(before: Count?, after: Count?): Long? =
        if (before == null || after == null) null
        else maxOf(0L, after.bytes - before.bytes - before.own)

    private fun of(path: String): Count? = try {
        val text = File(path).readBytes()
        parse(String(text, Charsets.US_ASCII))?.let { Count(it, text.size) }
    } catch (e: Exception) { null }
}
