package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The list of one console: what is made of the answer of `API_GetGameList.php`,
 * and that the file it is kept in gives back the same list or none.
 *
 * A list is what says a hash is not known, and the ledger keeps that for
 * fourteen days. So the tests are mostly of what must not be taken for a
 * list: a page that is not one, half of one, another console's.
 *
 * The games and the hashes are invented. The shape of an element is the one
 * the API answers with when asked for hashes.
 */
class RaGameListTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("ra-lists").toFile()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private val a = "0123456789abcdef0123456789abcdef"
    private val b = "11111111111111111111111111111111"
    private val c = "abcdefabcdefabcdefabcdefabcdefab"
    private val d = "22222222222222222222222222222222"

    private fun game(id: Int, title: String, vararg hashes: String, console: Int = 7,
                     icon: String = "/Images/00$id.png", achievements: Any? = 12) = buildString {
        append("""{"Title":${quoted(title)},"ID":$id,"ConsoleID":$console,"ConsoleName":"Invented System",""")
        append(""""ImageIcon":${quoted(icon)},"NumAchievements":$achievements,"NumLeaderboards":0,"Points":90,""")
        append(""""DateModified":"2020-01-02 03:04:05","ForumTopicID":null,""")
        append(""""Hashes":[${hashes.joinToString(",") { quoted(it) }}]}""")
    }

    private fun quoted(text: String) = org.json.JSONObject.quote(text)

    private fun listed(body: String, console: Int = 7, at: Long = 1_000L): RaGameList =
        assertIs<RaGameList.Parsed.Listed>(RaGameList.parse(console, body, at)).list

    private val two = "[" + game(101, "Moss Kingdom", a, b) + "," + game(102, "Paper Rally", c, achievements = 0) + "]"

    @Test fun `a list is read from what API_GetGameList answers`() {
        val list = listed(two, at = 1_700_000_000L)

        assertEquals(7, list.consoleId)
        assertEquals(1_700_000_000L, list.fetchedAt)
        assertEquals("Invented System", list.consoleName)
        assertEquals(2, list.listed)
        assertEquals(2, list.games)
        assertEquals(3, list.hashes)
        assertEquals(RaGameList.Game(101, "Moss Kingdom", "/Images/00101.png", 12), list[a])
        assertEquals(list[a], list[b])
        assertEquals(RaGameList.Game(102, "Paper Rally", "/Images/00102.png", 0), list[c])
        assertNull(list[d])
    }

    @Test fun `a hash written in capitals is found by its lowercase`() {
        val list = listed("[" + game(101, "Moss Kingdom", "  ABCDEFABCDEFABCDEFABCDEFABCDEFAB ") + "]")

        assertEquals(101, list[c]?.gameId)
        assertNull(list[c.uppercase()], "the caller gives lowercase, and capitals are no key")
    }

    @Test fun `what is not a JSON array is not an array`() {
        val bodies = listOf("<html><body>Maintenance</body></html>", """{"message":"Unauthenticated."}""",
                            """{"Success":false}""", """[{"ID":1,""", "", "   ",
                            "[" + game(101, "Moss Kingdom", a) + "] and then some")
        for (body in bodies)
            assertEquals(RaGameList.Parsed.NotAnArray, RaGameList.parse(7, body, 1_000L), body)
    }

    @Test fun `an array that is no list of this console's games is refused`() {
        val bodies = listOf(
            "[1,2]",
            """[{"Title":"x"}]""",
            """[{"ID":0}]""",
            """[{"ID":1.5,"Hashes":[]}]""",
            """[{"ID":1000000001,"Hashes":[]}]""",
            "[" + game(101, "Moss Kingdom", a) + "," + game(102, "Paper Rally", c, console = 9) + "]",
            """[{"ID":101,"ConsoleID":null,"Hashes":["$a"]}]""",
        )
        for (body in bodies) {
            val refused = assertIs<RaGameList.Parsed.Refused>(RaGameList.parse(7, body, 1_000L), body)
            assertTrue(refused.why.isNotBlank())
            assertFalse(a in refused.why || "Moss" in refused.why, "the reason quotes the body: ${refused.why}")
        }
    }

    // What the answer looks like when the hashes were not sent with it: every
    // game there and nothing to find it by. Taken for a list, it makes a miss
    // of every file of the console.
    @Test fun `games that come without their hashes are no list to look a hash up in`() {
        val body = """[{"Title":"Moss Kingdom","ID":101,"ConsoleID":7,"NumAchievements":12},""" +
                   """{"Title":"Paper Rally","ID":102,"ConsoleID":7,"NumAchievements":0}]"""

        assertIs<RaGameList.Parsed.Refused>(RaGameList.parse(7, body, 1_000L))

        // One game with the array is enough, and it may be empty: the hashes
        // were sent, and this console has none yet.
        val none = listed("""[{"Title":"Moss Kingdom","ID":101,"Hashes":[]},{"Title":"Paper Rally","ID":102}]""")
        assertEquals(2, none.listed)
        assertEquals(0, none.hashes)
    }

    // The hashes were sent and not one of them is a hash as it is read here:
    // the list has changed how it writes them. Taken, it is a list with no
    // hash in it, of a console that has them.
    @Test fun `a list whose hashes are none of them hashes is no list to look a hash up in`() {
        val bodies = listOf(
            """[{"ID":101,"Title":"Moss Kingdom","Hashes":[{"MD5":"$a","Name":"Moss Kingdom (World)"}]},""" +
                """{"ID":102,"Title":"Paper Rally","Hashes":[{"MD5":"$b"}]}]""",
            """[{"ID":101,"Title":"Moss Kingdom","Hashes":["${a}01234567"]},{"ID":102,"Hashes":[]}]""",
        )
        for (body in bodies) {
            val refused = assertIs<RaGameList.Parsed.Refused>(RaGameList.parse(7, body, 1_000L), body)
            assertFalse(a in refused.why || "Moss" in refused.why, "the reason quotes the body: ${refused.why}")
        }

        // One that is a hash is enough: the others are left out, as ever.
        assertEquals(1, listed("""[{"ID":101,"Hashes":[{"MD5":"$a"},"$b"]}]""").hashes)
    }

    @Test fun `an empty array is a list of nothing`() {
        val list = listed(" [] \n")

        assertEquals(0, list.listed)
        assertEquals(0, list.games)
        assertEquals(0, list.hashes)
        assertEquals("", list.consoleName)
    }

    @Test fun `a game with no title or no count keeps its hashes`() {
        val list = listed("""[{"ID":101,"Title":"  ","NumAchievements":"many","Hashes":["$a"]},""" +
                          """{"ID":102,"Hashes":["$b"]},""" +
                          """{"ID":103,"Title":"Tin Harbour","NumAchievements":"8","Hashes":["$c"]},""" +
                          """{"ID":104,"Title":"Low Tide","NumAchievements":-3,"Hashes":["$d"]}]""")

        assertEquals(RaGameList.Game(101, "", "", -1), list[a])
        assertEquals(RaGameList.Game(102, "", "", -1), list[b])
        assertEquals(RaGameList.Game(103, "Tin Harbour", "", 8), list[c], "a count in a string is the same count")
        assertEquals(-1, list[d]?.numAchievements)
    }

    @Test fun `a hash that is not 32 hex digits is left out`() {
        val list = listed("[" + game(101, "Moss Kingdom", a, a.dropLast(1), a + "0", "g".repeat(32), "") +
                          """,{"ID":102,"Hashes":[7,null,"$b"]}]""")

        assertEquals(2, list.hashes)
        assertEquals(101, list[a]?.gameId)
        assertEquals(102, list[b]?.gameId)
    }

    @Test fun `a hash given to two games stays with the first`() {
        val list = listed("[" + game(101, "Moss Kingdom", a, b) + "," + game(102, "Paper Rally", b.uppercase(), c) + "]")

        assertEquals(101, list[b]?.gameId)
        assertEquals(102, list[c]?.gameId)
        assertEquals(3, list.hashes)
    }

    @Test fun `a list written is the list read`() {
        val body = "[" + game(101, "Moss\tKingdom\r\nII ", a, b, icon = " /Images/a\tb.png") + "," +
                   game(102, "Wyspa Żółwi – 島", b, c, achievements = "x") + "," +
                   game(103, "Nothing To Find It By") + "]"
        val before = listed(body, at = 1_700_000_000L)
        assertEquals(3, before.listed)
        assertEquals(2, before.games)
        assertEquals("Moss Kingdom  II", before[a]?.title, "a tab and a line break are spaces")
        assertEquals("/Images/a b.png", before[a]?.imageIcon)

        before.write(dir)
        val after = assertNotNull(RaGameList.read(dir, 7))

        assertEquals(1_700_000_000L, after.fetchedAt)
        assertEquals("Invented System", after.consoleName)
        assertEquals(7, after.consoleId)
        assertEquals(2, after.games)
        assertEquals(2, after.listed, "the lines, which are the games with a hash")
        assertEquals(3, after.hashes)
        for (hash in listOf(a, b, c)) assertEquals(before[hash], after[hash], hash)
        assertEquals("Wyspa Żółwi – 島", after[c]?.title)
        assertEquals(-1, after[c]?.numAchievements)
        assertEquals(101, after[b]?.gameId)
        assertEquals(3, RaGameList.file(dir, 7).readLines().size)

        // And once more, from what was read: the file is the same file.
        val first = RaGameList.file(dir, 7).readText()
        after.write(dir)
        assertEquals(first, RaGameList.file(dir, 7).readText())
    }

    @Test fun `a list of games with no hash at all is written and read back`() {
        listed("[" + game(103, "Nothing To Find It By") + "]").write(dir)

        val after = assertNotNull(RaGameList.read(dir, 7))
        assertEquals(0, after.games)
        assertEquals(0, after.hashes)
    }

    @Test fun `a file cut short, of another console or of another format is not read`() {
        listed(two).write(dir)
        val file = RaGameList.file(dir, 7)
        val whole = file.readText()
        val lines = whole.trimEnd('\n').split('\n')
        assertNotNull(RaGameList.read(dir, 7))

        fun unread(what: String, text: String) {
            file.writeText(text)
            assertNull(RaGameList.read(dir, 7), what)
        }
        unread("last line removed", lines.dropLast(1).joinToString("\n") + "\n")
        unread("last line cut", whole.dropLast(20))
        // Every line it should have, and counts that agree: only the end of
        // the last title is gone, or the line break after it.
        unread("last title cut", whole.dropLast(5))
        unread("last line break gone", whole.dropLast(1))
        unread("header's console changed", whole.replaceFirst("ra-game-list\t1\t7\t", "ra-game-list\t1\t9\t"))
        unread("format 2", whole.replaceFirst("ra-game-list\t1\t", "ra-game-list\t2\t"))
        unread("another kind of file", whole.replaceFirst("ra-game-list", "ra-game-lost"))
        unread("a hash in capitals", whole.replaceFirst(a, a.uppercase()))
        unread("a hash twice", whole.replaceFirst(c, a))
        unread("a game twice", whole + lines[1].replace(a, d).replace(b, "3".repeat(32)) + "\n")
        // With a header that counts the game once and every hash, so that
        // the counts are not what refuses it.
        unread("a game on two lines", lines[0].replaceFirst("\t2\t3\t", "\t1\t4\t") + "\n" + lines[1] + "\n" +
                                      lines[1].replace(a, c).replace(b, d) + "\n")
        unread("a game with no hash", whole.replaceFirst(c, ""))
        unread("an id that is none", whole.replaceFirst("\n101\t", "\n0\t"))
        unread("a count below -1", whole.replaceFirst("\n101\t12\t", "\n101\t-2\t"))
        unread("a line of four fields", whole.replaceFirst("\t$c\t", "\t$c "))
        unread("only a header", lines[0] + "\n")
        unread("nothing", "")
        file.writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() })
        assertNull(RaGameList.read(dir, 7), "random bytes")

        file.writeText(whole)
        assertNull(RaGameList.read(dir, 9), "the file of console 7 asked for as 9's")
        assertTrue(file.delete())
        assertNull(RaGameList.read(dir, 7), "no file")
        assertNull(RaGameList.read(File(dir, "not-there"), 7), "no folder")
        assertTrue(RaGameList.file(dir, 7).mkdirs())
        assertNull(RaGameList.read(dir, 7), "a folder where the file would be")
    }

    @Test fun `a list is good for seven days and not from the future`() {
        val at = 1_700_000_000L
        val list = listed(two, at = at)
        val day = 24L * 60 * 60

        assertTrue(list.freshAt(at))
        assertTrue(list.freshAt(at + 7 * day))
        assertFalse(list.freshAt(at + 7 * day + 1))
        assertTrue(list.freshAt(at - day), "a clock adjusted by a day is still this clock")
        assertFalse(list.freshAt(at - 2 * day), "fetched two days ahead of now")
        assertEquals(7 * day, RaGameList.MAX_AGE_SECONDS)
    }

    @Test fun `writing leaves the list and no temporary file`() {
        val lists = File(dir, "cache/${RaGameList.DIR}")
        listed(two).write(lists)
        listed(two).write(lists)

        assertEquals(listOf("7.tsv"), lists.list()?.toList())
        assertEquals(RaGameList.file(lists, 7), File(lists, "7.tsv"))
    }
}
