package com.pegasus.bridge.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeVersionTest {
    // The desktop build cannot read the app's build file, so the number is
    // written twice. Raised in one place alone, a request would go on naming
    // the release before.
    @Test fun `the version is the one the Android app is built as`() {
        val build = File("../../app/build.gradle.kts")
        assertTrue(build.isFile, "the build file of the Android app is not at ${build.path}")

        val names = Regex("""versionName\s*=\s*"([^"]*)"""").findAll(build.readText()).map { it.groupValues[1] }.toList()

        assertEquals(listOf(BridgeVersion.NAME), names)
    }
}
