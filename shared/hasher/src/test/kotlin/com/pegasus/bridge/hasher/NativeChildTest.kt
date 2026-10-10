package com.pegasus.bridge.hasher

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [NativeChild] itself: that it says so when a child crashed or had to be
 * killed, and that the child leaves no core file behind.
 *
 * NativeCrashReproTest cannot show the first two. With the library as it
 * should be, every file there ends with an answer, and a NativeChild that
 * took a crash for "no hash" would pass it file by file. So the child is told
 * here to end as a crashed one does, and to never end, without any file and
 * without the library.
 */
class NativeChildTest {

    @Test
    fun `a child that ends without an answer crashed, whatever its status`() {
        // 134 is a process the C library aborted, 1 a JVM that caught a
        // signal and wrote its report, and 0 a child that simply never said.
        for (status in listOf(134, 1, 0)) {
            val result = NativeChild.run(listOf(NativeChild.MODE_END, status.toString()))
            assertIs<NativeChild.Result.Crashed>(result, "a child that ended with $status and no answer")
            assertEquals(status, result.exit, "the status of a child that ended with $status")
        }
    }

    @Test
    fun `a child still running when its time is up is killed, and said to be`() {
        val started = System.nanoTime()
        val result = NativeChild.run(listOf(NativeChild.MODE_SLEEP), limitSeconds = 1)
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0

        assertEquals(NativeChild.Result.TimedOut, result)
        assertTrue(seconds < 15, "a child given 1 second was waited on for $seconds")
        // Killed, and not only given up on. Known by the program it runs: of
        // a command as long as this one, with a class path in it, the system
        // does not hand out the arguments.
        val left = ProcessHandle.current().descendants()
            .filter { it.isAlive }
            .filter { it.info().command().map { program -> File(program).nameWithoutExtension == "java" }.orElse(false) }
            .map { it.pid() }
            .toList()
        assertEquals(emptyList(), left, "JVMs still running under this one after the child was killed")
    }

    @Test
    fun `the process that is started is the child's JVM, with no shell left over it`() {
        // The limit below is lowered by a shell, which is then to become the
        // JVM. Were it to stay and run the JVM under it, the process waited
        // for and killed here would be the shell, and a child that hangs
        // would be left running with nobody to kill it.
        val result = NativeChild.run(listOf(NativeChild.MODE_SELF))
        assertIs<NativeChild.Result.Crashed>(result, "a child that tells of itself gives no answer line")
        val parent = result.said.lines().first { it.startsWith("parent ") }.removePrefix("parent ")
        assertEquals(ProcessHandle.current().pid().toString(), parent,
                     "the process the child's JVM was started by, from: ${result.said}")
    }

    @Test
    fun `the child is allowed no core file`() {
        // The limit is read where the kernel shows it, which is Linux, and
        // there it is the limit that decides: a process the C library aborts
        // is written out whatever the JVM was told.
        assumeTrue(File("/proc/self/limits").canRead(), "no /proc/self/limits to read the limit from")

        val result = NativeChild.run(listOf(NativeChild.MODE_SELF))
        assertIs<NativeChild.Result.Crashed>(result, "a child that tells of itself gives no answer line")
        // "Max core file size        <soft>        <hard>        bytes"
        val fields = result.said.lines().first { it.startsWith("Max core file size") }
            .removePrefix("Max core file size").trim().split(Regex("\\s+"))
        assertEquals("0", fields[0], "the child's limit on a core file, from: ${result.said}")
    }
}
