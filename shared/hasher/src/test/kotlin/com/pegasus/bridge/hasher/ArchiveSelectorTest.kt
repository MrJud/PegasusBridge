package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.FuzzyMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which entry inside an archive is the ROM.
 *
 * Each case here is one the old "largest entry" rule got wrong, and got wrong
 * *silently* — it hashed whatever it picked, the hash matched nothing, and the
 * file was recorded as a game the database does not have.
 */
class ArchiveSelectorTest {

    private fun e(name: String, size: Long, dir: Boolean = false) =
        ArchiveSelector.Entry(name, size, dir)

    private fun selectOne(
        entries: List<ArchiveSelector.Entry>, archive: String, platform: String
    ): ArchiveSelector.Entry {
        val s = ArchiveSelector.select(entries, archive, platform)
        assertTrue(s is ArchiveSelector.Selection.One, "expected one entry, got $s")
        return s.entry
    }

    @Test fun `one rom beside a readme is the rom`() {
        val pick = selectOne(
            listOf(e("readme.txt", 500), e("Contra (USA).nes", 128 * 1024)),
            "Contra (USA).zip", "nes")
        assertEquals("Contra (USA).nes", pick.name)
    }

    // The readme is small, so "largest" got this right by luck. A scanned cover
    // scan or a bundled soundtrack is not small, and that is where luck runs out.
    @Test fun `a bonus file larger than the game does not win`() {
        val pick = selectOne(
            listOf(e("Contra (USA).nes", 128 * 1024),
                   e("scans/box front.png", 8L * 1024 * 1024),
                   e("soundtrack.mp3", 40L * 1024 * 1024)),
            "Contra (USA).zip", "nes")
        assertEquals("Contra (USA).nes", pick.name,
                     "the largest entry was a picture, and the old rule would have hashed it")
    }

    // The one that motivated the descriptor rule: a .cue is a few hundred bytes
    // next to a 700 MB .bin, so "largest" picks the data track every time.
    @Test fun `a cue is preferred over the bin it describes`() {
        val pick = selectOne(
            listOf(e("Final Fantasy VII (Disc 1).bin", 700L * 1024 * 1024),
                   e("Final Fantasy VII (Disc 1).cue", 300)),
            "Final Fantasy VII (Disc 1).zip", "psx")
        assertEquals("Final Fantasy VII (Disc 1).cue", pick.name)
    }

    @Test fun `the entry named after the archive wins a tie`() {
        val pick = selectOne(
            listOf(e("Super Mario World (USA).sfc", 512 * 1024),
                   e("Super Mario World (USA) [T-Ita].sfc", 700 * 1024)),
            "Super Mario World (USA).zip", "snes")
        assertEquals("Super Mario World (USA).sfc", pick.name,
                     "the translation patch dump is larger and is not the game asked for")
    }

    // The whole point: when it cannot tell, it says so instead of guessing.
    @Test fun `several plausible roms are ambiguous rather than resolved`() {
        val s = ArchiveSelector.select(
            listOf(e("Sonic 1.md", 512 * 1024),
                   e("Sonic 2.md", 1024 * 1024),
                   e("Sonic 3.md", 2048 * 1024)),
            "Sonic Collection.zip", "megadrive")
        assertTrue(s is ArchiveSelector.Selection.Ambiguous, "got $s")
        assertEquals(3, s.candidates.size)
        assertEquals("Sonic 3.md", s.candidates.first().name, "largest first, for reporting")
    }

    // `md` is Markdown to most software and a Mega Drive cartridge here. Excluding
    // it as a document extension would drop a whole platform's dumps.
    @Test fun `a mega drive md file is a rom and not a readme`() {
        val pick = selectOne(
            listOf(e("Sonic the Hedgehog (World).md", 512 * 1024), e("notes.txt", 200)),
            "Sonic the Hedgehog (World).zip", "megadrive")
        assertEquals("Sonic the Hedgehog (World).md", pick.name)
    }

    @Test fun `an included patch is never the rom`() {
        val pick = selectOne(
            listOf(e("Mother 3 (Japan).gba", 32L * 1024 * 1024),
                   e("mother3-english.ips", 48L * 1024 * 1024)),
            "Mother 3.zip", "gba")
        assertEquals("Mother 3 (Japan).gba", pick.name,
                     "the patch is bigger, and the old rule would have hashed it")
    }

    @Test fun `an extension the platform cannot run is not a candidate`() {
        val pick = selectOne(
            listOf(e("Contra (USA).nes", 128 * 1024), e("Contra (USA).sfc", 512 * 1024)),
            "Contra.zip", "nes")
        assertEquals("Contra (USA).nes", pick.name)
    }

    @Test fun `directories and empty entries are ignored`() {
        val pick = selectOne(
            listOf(e("roms/", 0, dir = true), e("empty.nes", 0),
                   e("roms/Contra (USA).nes", 128 * 1024)),
            "Contra (USA).zip", "nes")
        assertEquals("roms/Contra (USA).nes", pick.name)
    }

    @Test fun `an archive with nothing playable says so`() {
        val s = ArchiveSelector.select(
            listOf(e("cover.png", 5000), e("readme.txt", 500)), "Something.zip", "nes")
        assertTrue(s is ArchiveSelector.Selection.NoPlayableEntry, "got $s")
    }

    // An unknown platform falls back to the union of every ROM extension, which is
    // weaker but honest: it produces an ambiguity to look at, not a wrong hash.
    @Test fun `an unknown platform still resolves the single-rom case`() {
        val pick = selectOne(
            listOf(e("readme.txt", 500), e("Game.rom", 128 * 1024)),
            "Game.zip", "some-machine-nobody-has-heard-of")
        assertEquals("Game.rom", pick.name)
    }

    // A scan hands over the collection an archive is in, and the list its
    // entries are held to is the short name's, or the folder's where the
    // folder is named for one console of the family the short name stands
    // for. By the short name alone a zipped Game Gear cartridge in a folder
    // `gamegear` of a collection `mastersystem` has nothing playable in it.
    @Test fun `an archive in a collection is held to the list of what its folder holds`() {
        fun ref(shortName: String, dirName: String) =
            CollectionRef(shortName, shortName, dirName, directory = null, declaredExtensions = emptySet())
        val entries = listOf(e("readme.txt", 500), e("Lantern Keep (USA).gg", 128 * 1024))
        fun picked(shortName: String, dirName: String): String =
            when (val s = ArchiveSelector.select(entries, "Lantern Keep (USA).zip", ref(shortName, dirName))) {
                is ArchiveSelector.Selection.One -> s.entry.name
                else -> s.javaClass.simpleName
            }

        assertEquals("Lantern Keep (USA).gg", picked("mastersystem", "gamegear"), "the folder narrows the short name")
        assertEquals("NoPlayableEntry", picked("mastersystem", "mastersystem"), "a Master System collection")
        assertEquals("NoPlayableEntry", picked("mastersystem", "Sega 8-bit"), "a folder that says nothing")
        assertEquals("Lantern Keep (USA).gg", picked("gamegear", "Handhelds"), "the short name alone")
        assertEquals("Lantern Keep (USA).gg", picked("sega8", "gamegear"), "a short name nobody knows, in a folder that is")
        assertEquals("NoPlayableEntry", picked("nes", "gamegear"), "a short name that knows better than the folder")
    }

    // Every key in the platform table has to be in normalised form or it can never
    // be reached: `normalizePlatform` folds `megadrive` onto `genesis`, so an entry
    // filed under the former would be dead code that reads as coverage.
    @Test fun `every platform key is already normalised`() {
        for (platform in listOf(
            "nes", "snes", "n64", "gb", "gbc", "gba", "nds", "3ds", "gc", "wii",
            "genesis", "mastersystem", "gamegear", "sega32x", "segacd", "saturn",
            "dreamcast", "psx", "ps2", "psp", "pcengine", "atari2600", "atari7800",
            "lynx", "jaguar", "wonderswan", "ngp", "virtualboy", "colecovision",
            "msx", "3do", "c64", "amiga"
        )) {
            assertEquals(platform, FuzzyMatch.normalizePlatform(platform),
                         "'$platform' is not its own normalised form, so its entry is unreachable")
            assertTrue(ArchiveSelector.extensionsFor(platform).isNotEmpty())
        }
    }

    // The aliases a real library actually uses have to land on a real entry.
    @Test fun `the spellings a library uses reach the right table`() {
        assertTrue("md" in ArchiveSelector.extensionsFor("megadrive"))
        assertTrue("md" in ArchiveSelector.extensionsFor("genesis"))
        assertTrue("sms" in ArchiveSelector.extensionsFor("Sega Master System"))
        assertTrue("cue" in ArchiveSelector.extensionsFor("PlayStation"))
        assertTrue("lnx" in ArchiveSelector.extensionsFor("atarilynx"))
    }
}
