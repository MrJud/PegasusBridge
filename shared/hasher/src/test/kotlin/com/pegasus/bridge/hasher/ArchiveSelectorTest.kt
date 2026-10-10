package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.FuzzyMatch
import com.pegasus.bridge.core.RcConsoles
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

    // A game of two discs packed with its playlist. Only the playlist says
    // which disc is the first, and no disc platform listed `m3u`: the two
    // sheets were left to compete, and nothing could choose between them.
    @Test fun `a playlist is preferred over the sheets of the discs it lists`() {
        val entries = listOf(e("Multi.m3u", 16), e("d1.cue", 80), e("d1.bin", 700L * 1024 * 1024),
                             e("d2.cue", 80), e("d2.bin", 650L * 1024 * 1024))
        assertEquals("Multi.m3u", selectOne(entries, "Lantern Keep (USA).zip", "psx").name)
        // Named after the archive or not, and in a collection nobody knows.
        assertEquals("Multi.m3u", selectOne(entries, "d1.zip", "psx").name)
        assertEquals("Multi.m3u", selectOne(entries, "Lantern Keep (USA).zip", "").name)
    }

    @Test fun `every platform whose games come on discs takes a playlist`() {
        val entries = listOf(e("Multi.m3u", 16), e("d1.cue", 80), e("d2.cue", 80))
        val wrong = listOf("segacd", "saturn", "dreamcast", "psx", "ps2", "psp", "pcengine", "pcenginecd", "3do")
            .filter { (ArchiveSelector.select(entries, "x.zip", it) as? ArchiveSelector.Selection.One)?.entry?.name != "Multi.m3u" }
        assertEquals(emptyList(), wrong)
    }

    // A sheet rcheevos reads stands above one it does not, and one sheet of
    // a kind is the entry point whatever lies beside it.
    @Test fun `among descriptors a cue or a gdi comes before a ccd or a toc`() {
        assertEquals("Disc.cue", selectOne(listOf(e("Disc.cue", 80), e("Disc.bin", 4096)), "Disc.zip", "psx").name)
        assertEquals("Disc.ccd",
                     selectOne(listOf(e("Disc.ccd", 900), e("Disc.img", 4096), e("Disc.sub", 96)), "Disc.zip", "psx").name)
        assertEquals("Disc.cue",
                     selectOne(listOf(e("Disc.ccd", 900), e("Disc.img", 4096), e("Disc.cue", 80)), "Disc.zip", "psx").name)
        assertEquals("Disc.gdi",
                     selectOne(listOf(e("Disc.toc", 900), e("Disc.gdi", 90), e("Disc.bin", 4096)), "Other.zip", "").name)
    }

    // Two discs and no playlist: nobody can say which is the game, and the
    // question is between the two sheets. The tracks are not candidates.
    @Test fun `two cues and no playlist are ambiguous between the cues only`() {
        val entries = listOf(e("d1.cue", 80), e("d1.bin", 700L * 1024 * 1024),
                             e("d2.cue", 90), e("d2.bin", 650L * 1024 * 1024))
        val s = ArchiveSelector.select(entries, "Lantern Keep (USA).zip", "psx")
        assertTrue(s is ArchiveSelector.Selection.Ambiguous, "got $s")
        assertEquals(listOf("d2.cue", "d1.cue"), s.candidates.map { it.name })
    }

    // The sheet named after the archive is the game and the other a bonus
    // disc. Its track bears the same name, and while tracks competed for
    // the name with the sheets there were two of that name, and no choice.
    @Test fun `among several sheets the one named after the archive is chosen, whatever its track is called`() {
        val entries = listOf(e("Lantern Keep (USA).cue", 80), e("Lantern Keep (USA).bin", 700L * 1024 * 1024),
                             e("Lantern Keep (USA) (Bonus).cue", 90), e("Lantern Keep (USA) (Bonus).bin", 100L * 1024 * 1024))
        assertEquals("Lantern Keep (USA).cue", selectOne(entries, "Lantern Keep (USA).zip", "psx").name)
        assertEquals("Lantern Keep (USA) (Bonus).cue", selectOne(entries, "lantern keep (usa) (bonus).7z", "psx").name)
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
        assertEquals("Lantern Keep (USA).gg", picked("gamegear", "Handhelds"), "the short name alone")
        assertEquals("Lantern Keep (USA).gg", picked("sega8", "gamegear"), "a short name nobody knows, in a folder that is")
        assertEquals("NoPlayableEntry", picked("nes", "gamegear"), "a short name that knows better than the folder")
        // A Master System collection is where Game Gear cartridges are kept
        // too, by the console table, and a scan hashes a loose one there as
        // what it is. So does it a zipped one, whatever the folder is called.
        assertEquals("Lantern Keep (USA).gg", picked("mastersystem", "mastersystem"), "a Master System collection")
        assertEquals("Lantern Keep (USA).gg", picked("mastersystem", "Sega 8-bit"), "a folder that says nothing")
    }

    // The console table and the lists here were two opinions on what a
    // collection holds. A scan hashes a loose `.gb` under `snes`, a `.32x`
    // under `megadrive`, a `.min` under `pokemini`, each as the console the
    // table's row gives it; the same cartridge in a zip was held to a list
    // that had never heard of the row's family, and the archive held nothing
    // playable. Twenty-four names of collections had such an extension.
    //
    // Every extension rcheevos gives one console, and every one a row sends
    // somewhere itself: wherever the plan for the loose file is to hash it
    // as that console, a console of the collection's family, the same file
    // as the one entry of an archive there is the game. An extension whose
    // own console nobody can hash for is passed over: loose it is hashed as
    // the collection's console for want of a better, as any name is, and
    // that is no evidence of what the collection holds.
    @Test fun `what a collection hashes loose by its row is playable in its archives`() {
        val wrong = mutableListOf<String>()
        var checked = 0
        for (row in RcConsoles.ROWS.filterIsInstance<RcConsoles.Hashable>().filter { !it.arcade }) {
            val told = RomHashIO.RC_SINGLE.filterValues { it != null && it in row.family }.keys +
                       row.overridesByExtension.keys + row.overridesBySize.flatMap { it.extensions }
            for (name in listOf(row.key) + row.spellings) {
                for (extension in told) {
                    val plan = ConsoleChoice.choose(row, extension, Long.MAX_VALUE, insideArchive = true)
                    if (plan !is ConsoleChoice.Plan.Hash || plan.foreign) continue
                    checked++
                    val picked = ArchiveSelector.select(listOf(e("readme.txt", 500), e("Game.$extension", 4096)),
                                                        "Game.zip", CollectionRef.inferred(name))
                    if ((picked as? ArchiveSelector.Selection.One)?.entry?.name != "Game.$extension")
                        wrong += "$name: Game.$extension is hashed loose as console ${plan.console}, and zipped it is $picked"
                }
            }
        }
        assertEquals(emptyList(), wrong)
        assertTrue(checked > 100, "only $checked pairs of a collection and an extension were looked at")
    }

    // The cases the rule was written for, by name, and the ones it must
    // leave alone: a file of a console outside the family is still no game
    // of the collection.
    @Test fun `an archive holds what its collection's family holds, and no more`() {
        fun picked(platform: String, vararg names: String): String =
            when (val s = ArchiveSelector.select(names.map { e(it, 4096) }, "Game.zip", CollectionRef.inferred(platform))) {
                is ArchiveSelector.Selection.One -> s.entry.name
                else -> s.javaClass.simpleName
            }
        val expected = listOf(
            Triple("megadrive", listOf("Game.32x"), "Game.32x"),
            Triple("megadrive", listOf("Game.sms"), "Game.sms"),
            Triple("megadrive", listOf("Game.gg"), "Game.gg"),
            Triple("megadrive", listOf("Game.iso"), "Game.iso"),
            // A Sega CD disc: the sheet leads, and its track is no cartridge.
            Triple("megadrive", listOf("Game.bin", "Game.cue"), "Game.cue"),
            Triple("megadrive", listOf("Disc 1.cue", "Disc 2.cue", "Game.m3u"), "Game.m3u"),
            Triple("megadrivejp", listOf("Game.md"), "Game.md"),
            Triple("snes", listOf("Game.gb"), "Game.gb"),
            Triple("gba", listOf("Game.gbc"), "Game.gbc"),
            Triple("mastersystem", listOf("Game.gg"), "Game.gg"),
            Triple("pokemini", listOf("Game.min"), "Game.min"),
            Triple("supervision", listOf("Game.sv"), "Game.sv"),
            Triple("zxspectrum", listOf("Game.tzx"), "Game.tzx"),
            Triple("channelf", listOf("Game.chf"), "Game.chf"),
            Triple("wii", listOf("Game.gcm"), "Game.gcm"),
            Triple("nes", listOf("Game.gg"), "NoPlayableEntry"),
            Triple("snes", listOf("Game.sms"), "NoPlayableEntry"),
            Triple("psx", listOf("Game.nes"), "NoPlayableEntry"),
            // A cartridge collection gains no sheet, and so no playlist.
            Triple("nes", listOf("Game.m3u"), "NoPlayableEntry"),
            Triple("nes", listOf("Game.cue"), "NoPlayableEntry"))

        assertEquals(emptyList(), expected.mapNotNull { (platform, names, entry) ->
            picked(platform, *names.toTypedArray()).takeIf { it != entry }?.let { "$platform $names: $it, expected $entry" }
        })
    }

    // A scan picks up, loose, what a collection lists in `extensions:`, and
    // hashes it as the collection's console. The selector never looked at
    // the line, so the same file zipped was no game of the collection. An
    // archive listed there is not one: none is opened inside another.
    @Test fun `what a collection declares is playable in its archives, but for an archive`() {
        fun ref(shortName: String, vararg declared: String) =
            CollectionRef(shortName, shortName, shortName, directory = null, declaredExtensions = declared.toSet())
        fun picked(collection: CollectionRef, name: String): String =
            when (val s = ArchiveSelector.select(listOf(e("readme.txt", 500), e(name, 4096)), "Game.zip", collection)) {
                is ArchiveSelector.Selection.One -> s.entry.name
                else -> s.javaClass.simpleName
            }

        assertEquals("NoPlayableEntry", picked(ref("nes"), "Game.prototype"))
        assertEquals("Game.prototype", picked(ref("nes", "nes", "prototype"), "Game.prototype"))
        assertEquals("Game.j64x", picked(ref("a machine nobody has heard of", "j64x"), "Game.j64x"))
        assertEquals("NoPlayableEntry", picked(ref("nes", "nes", "zip", "7z"), "Inner.zip"))
        assertEquals("NoPlayableEntry", picked(ref("nes", "nes", "zip", "7z"), "Inner.7z"))
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
