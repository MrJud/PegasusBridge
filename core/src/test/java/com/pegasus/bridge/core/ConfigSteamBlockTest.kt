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
 * could be in. And that the block is not one a verb can clear.
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

    // clear-credentials is a verb any app on the device can send. The block
    // is put on a device and taken off it over the cable, so the verb is not
    // to know it. An unknown block is refused before any file is looked at,
    // which is why this needs no file either.
    @Test fun `clear-credentials does not know the steam block`() {
        assertFalse(Config.clearBlock("steam"))
    }
}
