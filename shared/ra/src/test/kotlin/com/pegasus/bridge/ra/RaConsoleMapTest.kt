package com.pegasus.bridge.ra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The answers of the console map, by name.
 *
 * A wrong console id sends every lookup for that platform to the wrong
 * catalogue, and the failure looks like "no match" rather than an error — so the
 * mapping is asserted rather than trusted. It was trusted once: the table was a
 * copy of the theme's RAConsoleMap.js, and these tests asked only about ids
 * that happened to be right.
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

    // Each of these was another console's id: Pokémon Mini's, PC Engine CD's
    // and the Neo Geo Pocket's.
    @Test fun `wii, 3ds and neogeo have their own ids`() {
        assertEquals(19, RaConsoleMap.consoleId("wii"))
        assertEquals(62, RaConsoleMap.consoleId("3ds"))
        assertEquals(62, RaConsoleMap.consoleId("n3ds"), "the name the collection has on disk")
        assertEquals(27, RaConsoleMap.consoleId("neogeo"))
        assertEquals(14, RaConsoleMap.consoleId("ngp"))
        assertEquals("ngp", RaConsoleMap.pegasusShortName("Neo Geo Pocket"))
        assertEquals("Neo Geo", RaConsoleMap.consoleName("neogeo"), "a set is an arcade game and the collection keeps its name")
        assertEquals("neogeo", RaConsoleMap.pegasusShortName("Neo Geo"))
        assertEquals("NG", RaConsoleMap.shortLabel("Neo Geo"))
        assertEquals("Arcade", RaConsoleMap.consoleName("atomiswave"))
    }

    // The index of matched games holds a platform as normalizePlatform folds
    // it, `lynx` for a collection called `atarilynx`, and the table had only
    // the one spelling.
    @Test fun `a collection is found under each of its names`() {
        assertEquals(13, RaConsoleMap.consoleId("lynx"))
        assertEquals(13, RaConsoleMap.consoleId("atarilynx"))
        assertEquals(17, RaConsoleMap.consoleId("jaguar"))
        assertEquals(17, RaConsoleMap.consoleId("atarijaguar"))
        assertEquals(1,  RaConsoleMap.consoleId("genesis"))
        assertEquals(44, RaConsoleMap.consoleId("adam"))
        assertEquals(43, RaConsoleMap.consoleId("3do"))
        assertEquals(35, RaConsoleMap.consoleId("amiga"), "no algorithm to hash with, and a catalogue to match in")

        assertEquals("Atari Lynx", RaConsoleMap.consoleName("lynx"))
        assertEquals("Atari Lynx", RaConsoleMap.consoleName("atarilynx"))
        assertEquals("Mega Drive", RaConsoleMap.consoleName("genesis"))
        assertEquals("Mega Drive", RaConsoleMap.consoleName("megadrive"))
        // A console nobody has seen the name of has an id and is shown under the collection's.
        assertEquals("jaguar", RaConsoleMap.consoleName("jaguar"))
        assertEquals("adam", RaConsoleMap.consoleName("adam"))
    }

    // A name that is no key as written is tried as normalizePlatform folds it.
    @Test fun `a name written the long way reaches its console`() {
        assertEquals(11, RaConsoleMap.consoleId("Sega Master System"))
        assertEquals(12, RaConsoleMap.consoleId("PlayStation"))
        assertEquals(3,  RaConsoleMap.consoleId("Super Nintendo"))
        assertEquals(0,  RaConsoleMap.consoleId("Nintendo Switch"))
    }

    @Test fun `the names RetroAchievements shows have a label and a collection`() {
        assertEquals("3do", RaConsoleMap.pegasusShortName("3DO Interactive Multiplayer"))
        assertEquals("3DO", RaConsoleMap.shortLabel("3DO Interactive Multiplayer"))
        assertEquals("3DO Interactive Multiplayer", RaConsoleMap.consoleName("3do"))
        assertEquals("channelf", RaConsoleMap.pegasusShortName("Fairchild Channel F"))
        assertEquals("CHF", RaConsoleMap.shortLabel("Fairchild Channel F"))
        assertEquals("arduboy", RaConsoleMap.pegasusShortName("Arduboy"))
        assertEquals("ARD", RaConsoleMap.shortLabel("Arduboy"))
        // The spelling of the collection on disk, as the theme's script had it.
        assertEquals("megadrive", RaConsoleMap.pegasusShortName("Genesis"))
        assertEquals("atarilynx", RaConsoleMap.pegasusShortName("Atari Lynx"))
        assertEquals("Neo Geo Pocket", RaConsoleMap.shortLabel("Neo Geo Pocket"), "no label, so the name")
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
        assertEquals(0, RaConsoleMap.consoleId("psvita"))
        assertEquals(0, RaConsoleMap.consoleId("weirdbox"))
        assertEquals("switch", RaConsoleMap.consoleName("switch"))
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
