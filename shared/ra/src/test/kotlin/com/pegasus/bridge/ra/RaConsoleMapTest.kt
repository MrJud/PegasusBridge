package com.pegasus.bridge.ra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parity with the theme's RAConsoleMap.js, which this replaces.
 *
 * A wrong console id sends every lookup for that platform to the wrong
 * catalogue, and the failure looks like "no match" rather than an error — so the
 * mapping is asserted rather than trusted.
 */
class RaConsoleMapTest {

    @Test fun `console ids match the ones RetroAchievements uses`() {
        assertEquals(1,  RaConsoleMap.consoleId("genesis"))
        assertEquals(1,  RaConsoleMap.consoleId("megadrive"), "both names map to the same console")
        assertEquals(2,  RaConsoleMap.consoleId("n64"))
        assertEquals(3,  RaConsoleMap.consoleId("snes"))
        assertEquals(4,  RaConsoleMap.consoleId("gb"))
        assertEquals(5,  RaConsoleMap.consoleId("gba"))
        assertEquals(6,  RaConsoleMap.consoleId("gbc"))
        assertEquals(7,  RaConsoleMap.consoleId("nes"))
        assertEquals(12, RaConsoleMap.consoleId("psx"))
    }

    @Test fun `lookups are case-insensitive`() {
        assertEquals(7, RaConsoleMap.consoleId("NES"))
        assertEquals(7, RaConsoleMap.consoleId("Nes"))
    }

    // 0 is the "no RetroAchievements support" signal the callers check for.
    @Test fun `an unknown platform is zero, not an exception`() {
        assertEquals(0, RaConsoleMap.consoleId("switch"))
        assertEquals(0, RaConsoleMap.consoleId(""))
        assertEquals(0, RaConsoleMap.consoleId(null))
    }

    @Test fun `console names round-trip to short names`() {
        assertEquals("NES", RaConsoleMap.consoleName("nes"))
        assertEquals("nes", RaConsoleMap.pegasusShortName("NES"))
        assertEquals("nes", RaConsoleMap.pegasusShortName("NES/Famicom"),
                     "RA writes the console both ways")
    }

    @Test fun `an unmapped name falls through instead of vanishing`() {
        assertEquals("weirdbox", RaConsoleMap.consoleName("weirdbox"))
        assertEquals("someconsole", RaConsoleMap.pegasusShortName("Some Console"))
    }

    @Test fun `every mapped platform has a usable id`() {
        assertTrue(RaConsoleMap.knownConsoleIds().all { it > 0 })
        assertTrue(RaConsoleMap.knownConsoleIds().size >= 20)
    }
}
