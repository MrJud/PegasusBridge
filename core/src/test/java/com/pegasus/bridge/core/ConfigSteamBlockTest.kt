package com.pegasus.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * The steam block as this shell's Config reports it.
 *
 * Config here is an object over `/sdcard/PegasusData`, so on a machine that
 * builds the app there is no file for it to read and none it can write. What
 * can be held from here is the report of a block that is not there: that
 * `status` has an entry for Steam, and that the entry has no field a key
 * could be in. And that the block is one clear-credentials can take away.
 */
class ConfigSteamBlockTest {

    @Test fun `status has a steam entry of presence and id, with no field for the key`() {
        assumeFalse("a Bridge's own credentials file is on this machine",
                    Paths.CREDENTIALS.exists() || Paths.LEGACY_HASHER.exists())

        assertNull(Config.load().steam)
        val steam = Config.status().getJSONObject("steam")
        assertEquals(setOf("configured", "steamId"), steam.keySet())
        assertFalse(steam.getBoolean("configured"))
        assertEquals("", steam.getString("steamId"))
    }

    // The theme's settings write the block through set-credentials and take
    // it away through clear-credentials, as the RetroAchievements one. A
    // block clearBlock knows is cleared in the file, which there is none of
    // here, so it is the set that is held: steam in it, and nothing new.
    @Test fun `clear-credentials knows the steam block`() {
        assertEquals(setOf("ra", "steamGridDb", "igdb", "screenScraper", "steam"), Config.KNOWN_BLOCKS)
        assertFalse(Config.clearBlock("romm"))
    }
}
