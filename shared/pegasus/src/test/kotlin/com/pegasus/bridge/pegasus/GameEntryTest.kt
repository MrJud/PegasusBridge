package com.pegasus.bridge.pegasus

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The metadata half of the export.
 *
 * `.media/` gives Pegasus the pictures. Without this it still has no
 * description, no genre, no developer and no year, and derives every title from
 * a filename — which on a No-Intro library means the games are called
 * `Contra (USA)`.
 *
 * The constraint that shapes it, read from `PegasusMetadata.cpp`: `game:` runs
 * `create_game()`, a new object every time, and a `file:` already claimed by a
 * different object is refused. Two files declaring one ROM do not merge.
 */
class GameEntryTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("entries").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val contra = GameEntry(
        title = "Contra",
        fileName = "Contra (USA).nes",
        developer = "Konami",
        publisher = "Konami",
        genres = listOf("Sparo / Run and Gun", "Sparo"),
        description = "Nell'anno 2631, un piccolo meteorite è caduto.",
        players = "1-2",
        release = "1988",
        rating = 80)

    @Test fun `an entry carries every field Pegasus knows`() {
        val t = contra.render()
        assertTrue(t.contains("game: Contra"), t)
        assertTrue(t.contains("file: Contra (USA).nes"), t)
        assertTrue(t.contains("developer: Konami"), t)
        assertTrue(t.contains("publisher: Konami"), t)
        assertTrue(t.contains("genres: Sparo / Run and Gun, Sparo"), t)
        assertTrue(t.contains("players: 1-2"), t)
        assertTrue(t.contains("release: 1988"), t)
        assertTrue(t.contains("rating: 80%"), t)
        assertTrue(t.contains("Nell'anno 2631"), t)
    }

    // The whole entry has to survive Pegasus' own parser, or it is decoration.
    @Test fun `a rendered entry parses back as a game rather than as the header`() {
        val text = MetadataFile.renderCollectionWithGames(
            "Nintendo Entertainment System", "nes", "", listOf(contra),
            preserve = mapOf("extensions" to "nes, jud"))
        File(dir, "metadata.pegasus.txt").writeText(text)

        val c = MetadataFile.readCollection(dir)!!
        assertEquals("Nintendo Entertainment System", c.name)
        assertTrue("jud" in c.extensions)
        // The header stops at the first `game:`, so no game field may leak into it.
        assertFalse(c.raw.containsKey("developer"), "a game field was read as the collection's")
        assertFalse(c.raw.containsKey("rating"))
    }

    // A blank line ends an entry and an unindented line starts a new field, so a
    // synopsis with a paragraph break has to be escaped rather than passed through.
    @Test fun `a multi-paragraph description cannot break out of its field`() {
        val e = contra.copy(description = "Primo paragrafo.\n\nSecondo paragrafo.\n\nTerzo.")
        val t = e.render()
        for (line in t.lines().drop(1)) {
            if (line.isBlank()) continue
            if (line.startsWith("#")) continue
            val isField = Regex("^[a-z-]+ *:").containsMatchIn(line)
            val isContinuation = line.first().isWhitespace()
            assertTrue(isField || isContinuation, "line escapes its field: '$line'")
        }
        // Pegasus spells a paragraph break as a line holding a single dot.
        assertTrue(t.contains("\n  ."), t)
        assertTrue(t.contains("Secondo paragrafo."), t)
    }

    @Test fun `newlines in a one-line field are collapsed`() {
        val t = contra.copy(developer = "Konami\nIndustry Co.").render()
        assertTrue(t.contains("developer: Konami Industry Co."), t)
    }

    // Pegasus already derives a title and a file from the filename. Writing only
    // those would claim the ROM and lock out any later, better source for nothing.
    @Test fun `an entry with nothing new to say is not written`() {
        assertEquals("", GameEntry("Contra", "Contra (USA).nes").render())
        assertTrue(GameEntry("Contra", "Contra (USA).nes", developer = "Konami").render().isNotEmpty())
    }

    @Test fun `an entry with no file is never written`() {
        assertEquals("", contra.copy(fileName = "").render())
    }

    // ── The claim check ─────────────────────────────────────────────────────

    @Test fun `a rom another metafile already declares is detected`() {
        File(dir, "metadata.pegasus.txt").writeText("""
            collection: NES
            extensions: nes

            game: Contra
            file: Contra (USA).nes
            developer: Konami
        """.trimIndent())

        val claimed = GameEntry.claimedElsewhere(dir)
        assertTrue("Contra (USA).nes" in claimed, "claimed: $claimed")
    }

    @Test fun `a multi-line files block is read whole`() {
        File(dir, "metadata.pegasus.txt").writeText("""
            collection: PSX
            game: Final Fantasy VII
            files:
              FF7 (Disc 1).cue
              FF7 (Disc 2).cue
              FF7 (Disc 3).cue
            developer: Square
        """.trimIndent())

        val claimed = GameEntry.claimedElsewhere(dir)
        assertEquals(setOf("FF7 (Disc 1).cue", "FF7 (Disc 2).cue", "FF7 (Disc 3).cue"), claimed)
    }

    // Running an export twice must not have the Bridge compete with itself.
    @Test fun `the bridge's own overlay is not counted as a competitor`() {
        val overlay = File(dir, "zz-pegasusbridge.metadata.pegasus.txt")
        overlay.writeText(MetadataFile.renderCollectionWithGames("NES", "nes", "", listOf(contra)))

        assertTrue("Contra (USA).nes" in GameEntry.claimedElsewhere(dir))
        assertFalse("Contra (USA).nes" in GameEntry.claimedElsewhere(dir, ignore = overlay))
    }

    @Test fun `a collection with no game entries claims nothing`() {
        File(dir, "metadata.pegasus.txt").writeText(
            "collection: NES\nshortname: nes\nextensions: nes, jud\nlaunch: am start")
        assertTrue(GameEntry.claimedElsewhere(dir).isEmpty())
    }

    // ── Normalising what the sources actually return ────────────────────────

    @Test fun `a release date is reduced to what Pegasus accepts`() {
        assertEquals("1988", GameEntry.normaliseRelease("1988"))
        assertEquals("1988-02-09", GameEntry.normaliseRelease("1988-02-09"))
        assertEquals("1988-02", GameEntry.normaliseRelease("1988-02"))
        assertEquals("1991", GameEntry.normaliseRelease("circa 1991"))
        assertEquals("", GameEntry.normaliseRelease("sconosciuto"))
    }

    @Test fun `a rating is read from whichever scale the source used`() {
        // ScreenScraper, already converted off its twenty-point scale.
        assertEquals(80, GameEntry.normaliseRating("80/100"))
        assertEquals(75, GameEntry.normaliseRating("75"))
        assertEquals(85, GameEntry.normaliseRating("0.85"))
        assertEquals(90, GameEntry.normaliseRating("90%"))
        // Unreadable is no rating: a wrong score shown confidently is worse.
        assertEquals(0, GameEntry.normaliseRating("ottimo"))
        assertEquals(0, GameEntry.normaliseRating(""))
        assertEquals(100, GameEntry.normaliseRating("150"), "out of range is clamped")
    }
}
