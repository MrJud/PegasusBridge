package com.pegasus.bridge.media

import com.pegasus.bridge.core.Paths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * That `steam-account` is a source of this shell's dispatcher, and that what
 * it answers is SteamAccountOps' answer.
 *
 * What that answer is in every state is held by SteamAccountOpsTest, in
 * shared/, on the same file this build compiles. Here there is the one state
 * that asks Steam nothing: no steam block, which is what a machine that
 * builds the app has, there being no `/sdcard/PegasusData` on it.
 */
class SteamAccountSourceTest {

    // On a machine that does have a credentials file there, these would read it,
    // and with a steam block in it they would ask Steam. They are for a build
    // machine and do not run on any other.
    @Before fun noInstallOnThisMachine() {
        assumeFalse("a Bridge's own credentials file is on this machine",
                    Paths.CREDENTIALS.exists() || Paths.LEGACY_HASHER.exists())
    }

    @Test fun `steam-account is routed, and with no steam block answers that it is not set up`() {
        val requests = listOf(
            "library" to emptyMap(),
            "achievements" to mapOf("appId" to "440"),
            "progress" to mapOf("appIds" to "440,570", "lang" to "it")
        )
        for ((op, params) in requests) {
            val answer = ScrapeSourceDispatcher.run("steam-account", op, params)
            // Not empty, which is what MediaService writes "ok" for.
            assertFalse(op, answer.isEmpty())
            val results = answer.results as JSONObject
            assertEquals(op, "steam", results.getString("namespace"))
            assertEquals(op, "not_configured", results.getString("state"))
            assertFalse(op, results.getString("message").isEmpty())
        }
    }

    @Test fun `a request that is wrong in itself is thrown, as every source throws it`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScrapeSourceDispatcher.run("steam-account", "friends", emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScrapeSourceDispatcher.run("steam-account", "achievements", mapOf("appId" to "../x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScrapeSourceDispatcher.run("steam-account", "progress", mapOf("appIds" to "440,x"))
        }
    }
}
