package com.pegasus.bridge.hasher

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The names a disc descriptor holds, and the entries of an archive they
 * are. No file is made: the sheets are text and the archives are listings.
 */
class DescriptorSetTest {

    private fun e(name: String, size: Long = 1000) = ArchiveSelector.Entry(name, size)

    private fun names(sheet: String, text: String) = DescriptorSet.references(sheet, text.toByteArray())

    private fun refusal(references: List<String>, vararg entries: String): String =
        assertIs<DescriptorSet.Match.Refused>(DescriptorSet.match(references, entries.map { e(it) })).reason

    @Test fun `a cue with two FILE lines names both, in order`() {
        assertEquals(
            listOf("Lantern.bin", "Lantern2.bin"),
            names("Lantern.cue",
                  "FILE Lantern.bin BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n" +
                  "FILE Lantern2.bin BINARY\n  TRACK 02 AUDIO\n    INDEX 00 00:00:00\n    INDEX 01 00:02:00\n"))
    }

    // As a dumping tool writes them: quoted, with spaces and brackets in the
    // name, and with the line ends of the system it ran on.
    @Test fun `quoted names with spaces are whole, with either kind of line end`() {
        val expected = listOf("Lantern Keep (USA) (Track 1).bin", "Lantern Keep (USA) (Track 2).bin")
        for (end in listOf("\r\n", "\n")) {
            assertEquals(
                expected,
                names("Lantern Keep (USA).cue",
                      "REM made by a tool$end" +
                      "FILE \"Lantern Keep (USA) (Track 1).bin\" BINARY$end  TRACK 01 MODE2/2352$end    INDEX 01 00:00:00$end" +
                      "FILE \"Lantern Keep (USA) (Track 2).bin\" BINARY$end  TRACK 02 AUDIO$end    INDEX 01 00:00:00$end"),
                "line end ${end.length}")
        }
    }

    @Test fun `a cue is read as rcheevos reads it`() {
        val rows = listOf(
            "file \"a.bin\" binary" to listOf("a.bin"),                        // the keyword in any case
            "   FILE \"a.bin\" BINARY" to listOf("a.bin"),                     // indented
            "FILE \"a.bin\" BINARY\nFILE \"a.bin\" WAVE" to listOf("a.bin"),   // each name once
            "FILE \"a.bin" to listOf("a.bin"),                                 // a quote never closed
            "FILE \"\" BINARY" to listOf(""),                                  // for match to refuse
            "FILE \"../a.bin\" BINARY" to listOf("../a.bin"),                  // likewise
            "FILENAME \"a.bin\"\nPROFILE x\nTRACK 01 AUDIO" to emptyList(),    // words that only begin alike
            "" to emptyList()
        )
        val wrong = rows.mapNotNull { (text, expected) ->
            val got = names("x.cue", text)
            if (got == expected) null else "${text.replace("\n", "\\n")}: expected $expected, got $got"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test fun `a gdi with three tracks names the file of each`() {
        val text = "3\r\n" +
                   "1 0 4 2352 track01.bin 0\r\n" +
                   "2 756 0 2352 \"track 02.raw\" 0\r\n" +
                   "3 45000 4 2352 track03.bin 0\r\n"
        assertEquals(listOf("track01.bin", "track 02.raw", "track03.bin"), names("Lantern Keep.gdi", text))
        // The first line is the count, whatever it looks like, and a line
        // that stops before its file names none.
        assertEquals(listOf("b.bin"), names("x.GDI", "1 0 4 2352 a.bin 0\n2 0 4 2352 b.bin 0\n3 0 4 2352\n\n"))
    }

    @Test fun `an m3u names its first entry, and nothing else names anything`() {
        assertEquals(listOf("Lantern Keep (Disc 1).cue"),
                     names("Lantern Keep.m3u", "\uFEFF# two discs\r\n\r\nLantern Keep (Disc 1).cue\r\nLantern Keep (Disc 2).cue\r\n"))
        assertEquals(emptyList(), names("Empty.m3u", "# nothing\n\n"))
        assertEquals(emptyList(), names("Disc.ccd", "[CloneCD]\nVersion=3\n"))
        assertEquals(emptyList(), names("Disc.toc", "CD_ROM\nFILE \"a.bin\" 0\n"))
    }

    @Test fun `each name is found among the entries, whatever the case and the folder`() {
        val entries = listOf(e("Lantern Keep/Lantern Keep.cue", 120), e("Lantern Keep/LANTERN KEEP (Track 1).BIN"),
                             e("Lantern Keep/Lantern Keep (Track 2).bin"), e("readme.txt", 10))
        val found = assertIs<DescriptorSet.Match.Found>(
            DescriptorSet.match(listOf("Lantern Keep (Track 2).bin", "Lantern Keep (track 1).bin"), entries))
        // In the sheet's order, each under the name the sheet uses, and
        // the entry is the listing's own object, which is what is read by.
        assertEquals(listOf("Lantern Keep (Track 2).bin", "Lantern Keep (track 1).bin"), found.tracks.map { it.name })
        assertSame(entries[2], found.tracks[0].entry)
        assertSame(entries[1], found.tracks[1].entry)
    }

    @Test fun `an m3u naming a cue leads to that cue's entry`() {
        val entries = listOf(e("Multi.m3u", 20), e("d1.cue", 80), e("d1.bin"), e("d2.cue", 80), e("d2.bin"))
        val first = names("Multi.m3u", "d1.cue\nd2.cue\n")
        val found = assertIs<DescriptorSet.Match.Found>(DescriptorSet.match(first, entries))
        assertSame(entries[1], found.tracks.single().entry)
    }

    @Test fun `a missing track is refused`() {
        assertEquals("it names 'Lost.bin', which is not in the archive",
                     refusal(listOf("Here.bin", "Lost.bin"), "Disc.cue", "Here.bin"))
        // A folder of that name is no file.
        val folder = listOf(ArchiveSelector.Entry("Lost.bin", 0, isDirectory = true))
        assertEquals("it names 'Lost.bin', which is not in the archive",
                     assertIs<DescriptorSet.Match.Refused>(DescriptorSet.match(listOf("Lost.bin"), folder)).reason)
    }

    // The tracks are written out under the names the sheet uses. A name is
    // one file beside the sheet or nothing is written at all, even when the
    // archive does hold an entry the name could be read as.
    @Test fun `a name that leads out of the sheet's folder is refused`() {
        val outside = "which is not a file beside it"
        val rows = listOf(
            "tracks/x.bin" to "it names 'tracks/x.bin', $outside",
            "../x.bin" to "it names '../x.bin', $outside",
            "..\\x.bin" to "it names '..\\x.bin', $outside",
            "tracks\\x.bin" to "it names 'tracks\\x.bin', $outside",
            "/var/x.bin" to "it names '/var/x.bin', $outside",
            "C:\\discs\\x.bin" to "it names 'C:\\discs\\x.bin', $outside",
            "C:x.bin" to "it names 'C:x.bin', $outside",
            ".." to "it names '..', $outside",
            "." to "it names '.', $outside",
            "x\u0000.bin" to "it names 'x?.bin', which is not a name a file can have",
            "x\uFFFD.bin" to "it names 'x\uFFFD.bin', which is not a name a file can have",
            "" to "it names a file with no name",
            // One byte more than a file's name can be: the letter is two.
            "\u00e9".repeat(126) + ".bin" to "it names a file by 256 bytes, and no file has a name of more than 255"
        )
        val wrong = rows.mapNotNull { (name, expected) ->
            val got = DescriptorSet.match(listOf(name), listOf(e("tracks/x.bin"), e("x.bin"), e(name)))
            if (got == DescriptorSet.Match.Refused(expected)) null else "'$name': $got"
        }
        assertEquals(emptyList(), wrong)

        // And the longest a name can be is one.
        val longest = "\u00e9".repeat(125) + "x.bin"
        assertIs<DescriptorSet.Match.Found>(DescriptorSet.match(listOf(longest), listOf(e(longest))))
    }

    // A track is written under the name its sheet gives it, and on Windows
    // some names are not files: what is written to them goes to a port or
    // nowhere, and what reads them waits. Nothing here runs on Windows, so
    // the test says which system it is asking about.
    @Test fun `a name Windows keeps for a device is refused there, and is a file's anywhere else`() {
        val devices = listOf("CON", "con.bin", "Prn.bin", "AUX.iso", "nul.bin", "NUL .bin", "nul.tar.bin",
                             "COM1.bin", "com9", "COM0.bin", "COM\u00b2.bin", "LPT1.bin", "lpt9.raw", "CONIN$", "conout$.bin")
        val wrong = devices.mapNotNull { name ->
            val there = DescriptorSet.match(listOf(name), listOf(e(name)), onWindows = true)
            val elsewhere = DescriptorSet.match(listOf(name), listOf(e(name)), onWindows = false)
            val expected = DescriptorSet.Match.Refused("it names '$name', which on Windows is a device and no file")
            if (there == expected && elsewhere is DescriptorSet.Match.Found) null
            else "'$name': on Windows $there, elsewhere $elsewhere"
        }
        assertEquals(emptyList(), wrong)

        // And names that only begin as one does are files on Windows too.
        val files = listOf("CONSOLE.bin", "con1.bin", "COM10.bin", "COM.bin", "LPT.bin", "LPTA.bin", "null.bin",
                           "aux2.bin", "track.con", "x.nul", "Disc (COM1).bin")
        assertEquals(emptyList(), files.filter {
            DescriptorSet.match(listOf(it), listOf(e(it)), onWindows = true) !is DescriptorSet.Match.Found
        })

        // Left to itself it goes by the system it runs on.
        val here = DescriptorSet.match(listOf("nul.bin"), listOf(e("nul.bin")))
        if (File.separatorChar == '\\') assertIs<DescriptorSet.Match.Refused>(here)
        else assertIs<DescriptorSet.Match.Found>(here)
    }

    @Test fun `a name that is two entries, or two names for one entry, is refused`() {
        assertEquals("it names 'Track.bin', and the archive holds 2 files of that name",
                     refusal(listOf("Track.bin"), "one/Track.bin", "two/TRACK.BIN"))
        assertEquals("it names one file twice, as 'Track.bin' and as 'track.BIN'",
                     refusal(listOf("Track.bin", "track.BIN"), "Track.bin"))
        assertEquals("it names no file", refusal(emptyList(), "Track.bin"))
    }
}
