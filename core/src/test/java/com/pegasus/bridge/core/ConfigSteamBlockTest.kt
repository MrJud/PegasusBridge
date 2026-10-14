package com.pegasus.bridge.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The steam block as this shell's Config reports and clears it.
 *
 * Config here is an object over `/sdcard/PegasusData`, so on a machine that
 * builds the app there is no file for it to read. What can be held from here
 * is the report of a block that is not there: that `status` has an entry for
 * Steam, and that the entry has no field a key could be in. And the clearing
 * of the block, in a file of the test's own.
 */
class ConfigSteamBlockTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `status has a steam entry of presence and id, with no field for the key`() {
        assumeFalse("a Bridge's own credentials file is on this machine",
                    Paths.CREDENTIALS.exists() || Paths.LEGACY_HASHER.exists())

        assertNull(Config.load().steam)
        val steam = Config.status().getJSONObject("steam")
        assertEquals(setOf("configured", "hasKey", "steamId"), steam.keySet())
        assertFalse(steam.getBoolean("configured"))
        assertFalse(steam.getBoolean("hasKey"))
        assertEquals("", steam.getString("steamId"))
    }

    // A key stored without a SteamID is not configured, but the theme must not
    // read it as no key: it would ask again for the half that is there.
    @Test fun `a steam key without a SteamID is reported as a key, not as configured`() {
        val st = Config.status(Credentials(steam = SteamCreds(KEY, "")))
        val steam = st.getJSONObject("steam")
        assertFalse(steam.getBoolean("configured"))
        assertTrue(steam.getBoolean("hasKey"))
        assertEquals("", steam.getString("steamId"))
        assertFalse(KEY in st.toString())
    }

    @Test fun `a steam key and a SteamID are configured`() {
        val st = Config.status(Credentials(steam = SteamCreds(KEY, "76561198000000001")))
        val steam = st.getJSONObject("steam")
        assertTrue(steam.getBoolean("configured"))
        assertTrue(steam.getBoolean("hasKey"))
        assertEquals("76561198000000001", steam.getString("steamId"))
        assertFalse(KEY in st.toString())
    }

    @Test fun `no steam key is neither a key nor configured, a SteamID alone included`() {
        for (c in listOf(Credentials(), Credentials(steam = SteamCreds("", "76561198000000001")))) {
            val steam = Config.status(c).getJSONObject("steam")
            assertFalse(steam.getBoolean("configured"))
            assertFalse(steam.getBoolean("hasKey"))
        }
    }

    // The theme's settings write the block through set-credentials and take
    // it away through clear-credentials, as the RetroAchievements one.
    @Test fun `clear-credentials takes the steam block away and leaves the others`() {
        assertEquals(setOf("ra", "steamGridDb", "igdb", "screenScraper", "steam"), Config.KNOWN_BLOCKS)
        val file = tmp.newFile("credentials.json")
        file.writeText(JSONObject()
            .put("ra", JSONObject().put("user", "someone").put("apiKey", "ra-key"))
            .put("steam", JSONObject().put("apiKey", KEY).put("steamId", "76561198000000001"))
            .toString())

        assertFalse(Config.clearBlock("romm", file))
        assertTrue(KEY in file.readText())

        assertTrue(Config.clearBlock("steam", file))
        val left = JSONObject(file.readText())
        assertFalse(left.has("steam"))
        assertEquals("ra-key", left.getJSONObject("ra").getString("apiKey"))
    }

    // Android's org.json writes the text it could not read into the message
    // of its exception, and RaService logs an exception with its message. So
    // a file that is not an object is read as an empty one, as the daemon
    // reads it, and nothing is thrown.
    @Test fun `a file that cannot be read is cleared without an exception`() {
        val file = tmp.newFile("credentials.json")
        for (text in listOf("""{"steam":{"apiKey":"$KEY","steamId":"7656""",
                            """[{"steam":{"apiKey":"$KEY"}}]""", "")) {
            file.writeText(text)
            assertFalse(Config.readOrEmpty(file).has("steam"))
            assertTrue(Config.clearBlock("steam", file))
            assertFalse(KEY in file.readText())
        }
    }

    private companion object {
        const val KEY = "SENTINEL-STEAM-KEY"
    }
}
