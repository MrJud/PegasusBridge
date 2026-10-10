package com.pegasus.bridge.hasher

import com.pegasus.bridge.hasher.PlaylistReader.Why
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The first entry of a playlist, written the ways playlists are: by a tool
 * on Windows, by hand, with a comment on top, or left empty.
 */
class PlaylistReaderTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("playlist").toFile()
    }

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    private fun disc(path: String): File =
        File(dir, path).apply { parentFile.mkdirs(); writeText("a disc") }

    private fun playlist(text: String, name: String = "Game.m3u"): File =
        File(dir, name).apply { writeBytes(text.toByteArray()) }

    private fun entry(playlist: File): File = when (val result = PlaylistReader.firstEntry(playlist)) {
        is PlaylistReader.Result.Entry -> result.file
        is PlaylistReader.Result.Refused -> fail("refused: $result")
    }

    private fun refused(playlist: File): PlaylistReader.Result.Refused =
        when (val result = PlaylistReader.firstEntry(playlist)) {
            is PlaylistReader.Result.Refused -> result.also { assertTrue(it.reason.isNotBlank()) }
            is PlaylistReader.Result.Entry -> fail("an entry: ${result.file}")
        }

    @Test fun `the first entry is the first line that names a file`() {
        val one = disc("Game (Disc 1).cue")
        disc("Game (Disc 2).cue")
        assertEquals(one, entry(playlist("Game (Disc 1).cue\nGame (Disc 2).cue\n")))
        assertEquals(one, entry(playlist("Game (Disc 1).cue")), "no line end after the only entry")
    }

    @Test fun `a comment first is passed over`() {
        val one = disc("Game (Disc 1).cue")
        assertEquals(one, entry(playlist("#EXTM3U\n# the first disc\nGame (Disc 1).cue\n")))
    }

    @Test fun `blank lines first are passed over, and an entry is trimmed`() {
        val one = disc("Game (Disc 1).cue")
        assertEquals(one, entry(playlist("\n   \n\t\n  Game (Disc 1).cue \t\nGame (Disc 2).cue\n")))
    }

    @Test fun `Windows line ends are not part of a name`() {
        val one = disc("Game (Disc 1).cue")
        assertEquals(one, entry(playlist("#EXTM3U\r\nGame (Disc 1).cue\r\nGame (Disc 2).cue\r\n")))
    }

    @Test fun `a byte order mark is not part of a name or of a comment`() {
        val one = disc("Game (Disc 1).cue")
        assertEquals(one, entry(playlist("\uFEFFGame (Disc 1).cue\n")))
        assertEquals(one, entry(playlist("\uFEFF#EXTM3U\nGame (Disc 1).cue\n")))
    }

    @Test fun `an entry in a folder below is found from the playlist's own`() {
        val one = disc("discs/one/Game (Disc 1).cue")
        assertEquals(one, entry(playlist("discs/one/Game (Disc 1).cue\n")))
        assertEquals(one.canonicalFile, entry(playlist("./discs/one/Game (Disc 1).cue\n")).canonicalFile)
    }

    @Test fun `backslashes between folders are read as separators`() {
        val one = disc("discs/one/Game (Disc 1).cue")
        assertEquals(one, entry(playlist("discs\\one\\Game (Disc 1).cue\r\n")))
        assertEquals(one.canonicalFile, entry(playlist(".\\discs\\one\\Game (Disc 1).cue\r\n")).canonicalFile)
    }

    @Test fun `an absolute entry is taken as written`() {
        val elsewhere = Files.createTempDirectory("playlist-elsewhere").toFile()
        try {
            val one = File(elsewhere, "Game (Disc 1).cue").apply { writeText("a disc") }
            assertEquals(one, entry(playlist(one.absolutePath + "\n")))
        } finally {
            elsewhere.deleteRecursively()
        }
        // A path of another system is absolute too, and is simply not here.
        assertEquals(Why.MISSING, refused(playlist("C:\\roms\\psx\\Game (Disc 1).cue\r\n")).why)
        // Not even when the playlist's own folder has a file down that very path, which only a
        // system that takes `C:` for a folder's name can make: it is not what the entry names.
        val below = File(dir, "C:/roms/psx")
        if (below.mkdirs()) {
            File(below, "Game (Disc 1).cue").writeText("a disc")
            assertEquals(Why.MISSING, refused(playlist("C:\\roms\\psx\\Game (Disc 1).cue\r\n")).why)
        }
    }

    @Test fun `an entry that is not there is refused as missing`() {
        disc("Game (Disc 2).cue")
        val result = refused(playlist("Game (Disc 1).cue\nGame (Disc 2).cue\n"))
        assertEquals(Why.MISSING, result.why)
        assertTrue("Game (Disc 1).cue" in result.reason, result.reason)
        // The second entry is there, and is not the game's hash.
        File(dir, "folder.cue").mkdirs()
        assertEquals(Why.MISSING, refused(playlist("folder.cue\n")).why, "a folder is not a disc")
    }

    @Test fun `a playlist that names itself or another playlist is refused as nested`() {
        assertEquals(Why.NESTED, refused(playlist("Game.m3u\n")).why)
        assertEquals(Why.NESTED, refused(playlist("./Game.m3u\n")).why)
        playlist("Game (Disc 1).cue\n", name = "Other.m3u")
        disc("Game (Disc 1).cue")
        assertEquals(Why.NESTED, refused(playlist("Other.m3u\n")).why)
        assertEquals(Why.NESTED, refused(playlist("OTHER.M3U\n")).why)
        // Nested whether or not it is there: what it would lead to is not looked at.
        assertEquals(Why.NESTED, refused(playlist("nowhere/Absent.m3u\n")).why)
    }

    @Test fun `an empty file and one of comments alone are refused as empty`() {
        assertEquals(Why.EMPTY, refused(playlist("")).why)
        assertEquals(Why.EMPTY, refused(playlist("\n\r\n   \n")).why)
        assertEquals(Why.EMPTY, refused(playlist("#EXTM3U\n# nothing yet\n")).why)
        assertEquals(Why.EMPTY, refused(playlist("\uFEFF")).why)
    }

    @Test fun `a playlist that cannot be read is refused as unreadable`() {
        assertEquals(Why.UNREADABLE, refused(File(dir, "Absent.m3u")).why)
        assertEquals(Why.UNREADABLE, refused(File(dir, "folder.m3u").apply { mkdirs() }).why)
    }

    /**
     * A playlist is a few lines. A file with that name and megabytes in it is
     * something else, and is given up on where the limit is, with nothing
     * taken from a line the limit cut in two.
     */
    @Test fun `no entry is looked for past the first 64 KiB`() {
        val limit = PlaylistReader.READ_LIMIT
        assertEquals(64 * 1024, limit)
        /** Comment lines of exactly [bytes] bytes, the last one ended. */
        fun comments(bytes: Int): String {
            val line = "# " + "x".repeat(98) + "\n"
            val rest = bytes % line.length
            return (line.repeat(bytes / line.length) + if (rest == 0) "" else "#".repeat(rest - 1) + "\n")
                .also { assertEquals(bytes, it.length) }
        }
        val one = disc("Game (Disc 1).cue")

        assertEquals(one, entry(playlist(comments(limit - 100) + "Game (Disc 1).cue\n")), "under the limit")
        assertEquals(Why.EMPTY, refused(playlist(comments(limit) + "Game (Disc 1).cue\n")).why, "past the limit")

        // An entry that starts before the limit and ends after it is half a
        // name, and is not looked for even when a file is called so.
        disc("Game (Dis")
        assertEquals(Why.EMPTY, refused(playlist(comments(limit - 9) + "Game (Disc 1).cue\n")).why)

        // One that ends exactly at the limit is whole: when the file ends
        // there, and when the next byte ends the line. Not when the name goes on.
        assertEquals(one, entry(playlist(comments(limit - 17) + "Game (Disc 1).cue")))
        assertEquals(one, entry(playlist(comments(limit - 17) + "Game (Disc 1).cue\nGame (Disc 2).cue\n")))
        assertEquals(one, entry(playlist(comments(limit - 17) + "Game (Disc 1).cue\r\n")))
        assertEquals(Why.EMPTY, refused(playlist(comments(limit - 17) + "Game (Disc 1).cue.bak\n")).why)
    }
}
