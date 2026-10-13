package com.pegasus.bridge.hasher

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reasons Android gives for the end of a process, each held by its name
 * and its number to the side of the rule it is on: an end from outside,
 * which makes no file a suspect, or one that may be a file's doing.
 *
 * The constants are numbers the compiler writes into the class, so this runs
 * on a JVM with no device. A reason that changes sides, or a number that is
 * no longer the name's, fails here and not on somebody's tablet.
 */
class ExitReasonsTest {

    private val fromOutside = mapOf(
        "PERMISSION_CHANGE" to (ApplicationExitInfo.REASON_PERMISSION_CHANGE to 8),
        "USER_REQUESTED" to (ApplicationExitInfo.REASON_USER_REQUESTED to 10),
        "USER_STOPPED" to (ApplicationExitInfo.REASON_USER_STOPPED to 11),
        "DEPENDENCY_DIED" to (ApplicationExitInfo.REASON_DEPENDENCY_DIED to 12),
        "OTHER" to (ApplicationExitInfo.REASON_OTHER to 13),
        "FREEZER" to (ApplicationExitInfo.REASON_FREEZER to 14),
        "PACKAGE_STATE_CHANGE" to (ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to 15),
        "PACKAGE_UPDATED" to (ApplicationExitInfo.REASON_PACKAGE_UPDATED to 16))

    private val maybeAFile = mapOf(
        "UNKNOWN" to (ApplicationExitInfo.REASON_UNKNOWN to 0),
        "EXIT_SELF" to (ApplicationExitInfo.REASON_EXIT_SELF to 1),
        "SIGNALED" to (ApplicationExitInfo.REASON_SIGNALED to 2),
        "LOW_MEMORY" to (ApplicationExitInfo.REASON_LOW_MEMORY to 3),
        "CRASH" to (ApplicationExitInfo.REASON_CRASH to 4),
        "CRASH_NATIVE" to (ApplicationExitInfo.REASON_CRASH_NATIVE to 5),
        "ANR" to (ApplicationExitInfo.REASON_ANR to 6),
        "INITIALIZATION_FAILURE" to (ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to 7),
        "EXCESSIVE_RESOURCE_USAGE" to (ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to 9))

    @Test fun `every reason has the number it is known by`() {
        for ((name, pair) in fromOutside + maybeAFile) assertEquals(name, pair.second, pair.first)
        assertEquals("the reasons from 0 to 16, each once", (0..16).toList(),
                     (fromOutside + maybeAFile).values.map { it.second }.sorted())
    }

    @Test fun `the eight ends from outside are those and no others`() {
        for ((name, pair) in fromOutside) assertEquals(name, true, ExitReasons.fromOutside(pair.first))
        assertEquals(fromOutside.values.map { it.first }.toSet(), ExitReasons.FROM_OUTSIDE)
    }

    @Test fun `a crash, a kill for memory and whatever is not known may be a file's doing`() {
        for ((name, pair) in maybeAFile) assertEquals(name, false, ExitReasons.fromOutside(pair.first))
        // A reason a later Android adds, and a number that is none.
        assertEquals(false, ExitReasons.fromOutside(17))
        assertEquals(false, ExitReasons.fromOutside(-1))
    }
}
