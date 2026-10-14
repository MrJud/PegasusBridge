package com.pegasus.bridge.scrapers

import com.pegasus.bridge.scrapers.GameNativeLaunch.MarkerRefusal
import com.pegasus.bridge.scrapers.GameNativeLaunch.Request
import com.pegasus.bridge.scrapers.GameNativeLaunch.Result
import com.pegasus.bridge.scrapers.GameNativeLaunch.Source
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A GameNative marker file read into a launch, and every path that is not one.
 *
 * The path comes from another app. So what is held here is mostly refusals:
 * each thing a path can be that is not a marker in a folder of games, with
 * the one reason it is refused for, on a file that is a good marker in every
 * other respect. A refusal that happened for some other reason would pass a
 * looser test with the check it is named after taken out.
 *
 * The folders are temporary ones. `storage` stands for /storage, and the two
 * forbidden folders lie in it where the real ones lie in the real one.
 */
class GameNativeMarkerTest {

    private lateinit var sandbox: File
    private lateinit var storage: File
    private lateinit var games: File
    private lateinit var allowed: List<String>
    private lateinit var forbidden: List<String>

    @BeforeTest fun setUp() {
        // Its canonical form: a system's temporary folder can be behind a link,
        // and every path made of it would be refused for that.
        sandbox = Files.createTempDirectory("gamenative-markers").toFile().canonicalFile
        storage = File(sandbox, "storage")
        games = File(storage, "1A2B-3C4D/Emulation/Roms/windows")
        allowed = listOf(storage.path)
        forbidden = listOf(File(storage, "emulated/0/PegasusData").path,
                           File(storage, "emulated/0/Android").path)
    }

    @AfterTest fun tearDown() {
        sandbox.deleteRecursively()
    }

    /** A file under the sandbox, with these bytes. The paths here are Android's, which begin with a slash. */
    private fun write(file: File, bytes: ByteArray): File {
        assumeTrue(File.separatorChar == '/', "paths that begin with a slash are not this system's")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private fun marker(name: String, content: String = "367520", dir: File = games): File =
        write(File(dir, name), content.toByteArray(Charsets.UTF_8))

    private fun launch(path: String?): Result = GameNativeLaunch.fromMarkerFile(path, allowed, forbidden)

    private fun ok(appId: Int, source: Source = Source.STEAM): Result = Result.Ok(Request(appId, source, ""))

    private fun refused(why: MarkerRefusal): Result = Result.Rejected(why.reason)

    // ── The grammar ─────────────────────────────────────────────────────────

    private class Row(val what: String, val bytes: ByteArray, val appId: Int?)

    private fun row(what: String, text: String, appId: Int?) = Row(what, text.toByteArray(Charsets.UTF_8), appId)

    /**
     * The rows the theme's tests have too, and then some of this file's own.
     * A row with no app id is refused.
     */
    private val rows = listOf(
        // Shared with the theme.
        row("an app id", "367520", 367520),
        row("followed by LF", "367520\n", 367520),
        row("followed by CRLF", "367520\r\n", 367520),
        row("a leading zero", "0367520", null),
        row("zero", "0", null),
        row("one more than an Int holds", "2147483648", null),
        row("letters after the digits", "12ab", null),
        row("empty", "", null),
        row("full-width digits", "\uFF13\uFF16\uFF17\uFF15", null),
        row("a byte-order mark before the digits", "\uFEFF367520", null),
        row("17 bytes, an app id and spaces", "367520" + " ".repeat(11), null),
        // This file's own.
        row("16 bytes, an app id and spaces", "367520" + " ".repeat(10), 367520),
        row("17 bytes of digits", "12345678901234567", null),
        row("the most an Int holds", "2147483647", 2147483647),
        row("one", "1", 1),
        row("spaces and tabs at both ends", " \t367520\t \r\n", 367520),
        row("nothing but blanks", " \t\r\n", null),
        row("a space inside", "36 7520", null),
        row("a line of its own before the digits and one after", "\n440\n", 440),
        row("two lines", "440\n570", null),
        row("a plus sign", "+367520", null),
        row("a minus sign", "-367520", null),
        row("ten digits over an Int", "9999999999", null),
        row("eleven digits", "10000000000", null),
        row("a decimal point", "440.0", null),
        row("a form feed after the digits", "440\u000C", null),
        row("a vertical tab after the digits", "440\u000B", null),
        row("a no-break space after the digits", "440\u00A0", null),
        row("Arabic-Indic digits", "\u0664\u0664\u0660", null),
        Row("a NUL after the digits", byteArrayOf(0x34, 0x34, 0x30, 0x00), null),
        Row("a byte with its high bit set", byteArrayOf(0x34, 0x34, 0xB0.toByte()), null)
    )

    @Test fun `the bytes of a marker are an app id or are refused, row by row`() {
        for (r in rows) assertEquals(r.appId, GameNativeLaunch.markerAppId(r.bytes), r.what)
    }

    @Test fun `the same rows, each as a file`() {
        for ((i, r) in rows.withIndex()) {
            val file = write(File(games, "row $i.steam"), r.bytes)
            val expected = when {
                r.appId != null -> ok(r.appId)
                r.bytes.size > GameNativeLaunch.MAX_MARKER_BYTES -> refused(MarkerRefusal.TOO_BIG)
                else -> refused(MarkerRefusal.NOT_AN_ID)
            }
            assertEquals(expected, launch(file.path), r.what)
        }
    }

    @Test fun `only so many of the bytes it is handed are a marker's`() {
        val bytes = "367520XXXX".toByteArray()
        assertEquals(367520, GameNativeLaunch.markerAppId(bytes, 6))
        assertNull(GameNativeLaunch.markerAppId(bytes, 7))
    }

    // ── What a launch is made of ────────────────────────────────────────────

    @Test fun `each of the five extensions is its own store`() {
        val stores = mapOf(
            "steam" to Source.STEAM, "gog" to Source.GOG, "epic" to Source.EPIC,
            "amazon" to Source.AMAZON, "pcgame" to Source.CUSTOM_GAME)
        var appId = 100
        for ((extension, source) in stores) {
            appId++
            val file = marker("A Game.$extension", "$appId")
            assertEquals(ok(appId, source), launch(file.path), extension)
        }
        // And nothing GameNative knows a store by is left without an extension.
        assertEquals(Source.entries.toSet(), stores.values.toSet())
    }

    @Test fun `the extension is compared in lower case`() {
        assertEquals(ok(367520), launch(marker("Hollow Knight.STEAM").path))
        assertEquals(ok(367520, Source.EPIC), launch(marker("Alan Wake.Epic").path))
        assertEquals(ok(367520, Source.CUSTOM_GAME), launch(marker("My Game.PcGame").path))
    }

    @Test fun `a launch holds the number, the store and nothing else`() {
        val answer = launch(marker("Hollow Knight.steam", "367520\n").path)
        assertTrue(answer is Result.Ok, "got $answer")
        assertEquals(Request(367520, Source.STEAM, ""), answer.request)
        assertEquals(
            mapOf<String, Any>(GameNativeLaunch.EXTRA_APP_ID to 367520,
                               GameNativeLaunch.EXTRA_GAME_SOURCE to "STEAM"),
            answer.request.extras)
        assertTrue(answer.request.extras[GameNativeLaunch.EXTRA_APP_ID] is Int)
        assertFalse(GameNativeLaunch.EXTRA_CONTAINER_CONFIG in answer.request.extras)
    }

    @Test fun `a custom game is a store the plain request knows too`() {
        val r = GameNativeLaunch.requestFor(7, "CUSTOM_GAME")
        assertTrue(r is Result.Ok, "got $r")
        assertEquals("CUSTOM_GAME", r.request.extras[GameNativeLaunch.EXTRA_GAME_SOURCE])
    }

    // The first three are files GameNative wrote on the device this was
    // measured on. The others are what a title can hold that a shell, a URL
    // or an intent would each read in its own way.
    @Test fun `a title is whatever the game is called`() {
        val names = listOf(
            "Hollow Knight.steam",
            "STEINS;GATE.steam",
            "Ori and the Blind Forest_ Definitive Edition.steam",
            "Game #1.steam",
            "Tom & Jerry.steam",
            "100% Orange Juice.steam",
            "50%25 off.steam",
            "C++ Quest.steam",
            "a+b & c; #2 100%.steam",
            "Pok\u00E9mon \u30B2\u30FC\u30E0.steam",
            "it's \"quoted\" \$HOME `x`.steam",
            "two.dots.steam",
            ".steam"
        )
        for ((i, name) in names.withIndex()) {
            val file = marker(name, "${1000 + i}")
            assertEquals(ok(1000 + i), launch(file.path), name)
        }
    }

    // ── Paths that are refused ──────────────────────────────────────────────

    @Test fun `no path at all is refused`() {
        assertEquals(refused(MarkerRefusal.MISSING), launch(null))
        assertEquals(refused(MarkerRefusal.MISSING), launch(""))
    }

    @Test fun `a path that is not absolute is refused`() {
        val file = marker("Hollow Knight.steam")
        assertEquals(ok(367520), launch(file.path))
        assertEquals(refused(MarkerRefusal.NOT_ABSOLUTE), launch(file.path.removePrefix("/")))
        assertEquals(refused(MarkerRefusal.NOT_ABSOLUTE), launch("Hollow Knight.steam"))
        assertEquals(refused(MarkerRefusal.NOT_ABSOLUTE), launch("./Hollow Knight.steam"))
        assertEquals(refused(MarkerRefusal.NOT_ABSOLUTE), launch("file://" + file.path))
    }

    /** A good marker whose path is exactly [length] characters, in folders made for it. */
    private fun markerOfPathLength(length: Int): File {
        var dir = games
        // A folder of a hundred characters at a time: a name has a limit of its own.
        while (length - dir.path.length - 1 > 200) dir = File(dir, "d".repeat(100))
        val name = "x".repeat(length - dir.path.length - 1 - ".steam".length) + ".steam"
        val file = File(dir, name)
        assertEquals(length, file.path.length)
        assumeTrue(File.separatorChar == '/', "paths that begin with a slash are not this system's")
        assumeTrue(dir.mkdirs() || dir.isDirectory, "this system makes no path so long")
        assumeTrue(runCatching { file.writeText("367520") }.isSuccess, "this system makes no path so long")
        return file
    }

    @Test fun `a path of 1024 characters is read and one of 1025 is refused`() {
        assertEquals(ok(367520), launch(markerOfPathLength(GameNativeLaunch.MAX_PATH_CHARS).path))
        assertEquals(refused(MarkerRefusal.TOO_LONG),
                     launch(markerOfPathLength(GameNativeLaunch.MAX_PATH_CHARS + 1).path))
    }

    @Test fun `a path with a NUL in it is refused`() {
        val file = marker("Hollow Knight.steam")
        assertEquals(refused(MarkerRefusal.NUL), launch(file.path.replace("Hollow", "Hol\u0000low")))
        // Where a C string would end, with a good marker's path before it.
        assertEquals(refused(MarkerRefusal.NUL), launch(file.path + "\u0000.steam"))
    }

    @Test fun `a path outside the folders games are kept in is refused`() {
        val file = marker("Hollow Knight.steam", dir = File(sandbox, "elsewhere"))
        assertEquals(refused(MarkerRefusal.OUTSIDE), launch(file.path))
        // The same file is read once its folder is one of them.
        assertEquals(ok(367520), GameNativeLaunch.fromMarkerFile(file.path, listOf(file.parent), forbidden))
    }

    @Test fun `a folder whose name only begins as the allowed one does is outside it`() {
        val file = marker("Hollow Knight.steam", dir = File(sandbox, "storage-of-another"))
        assertTrue(file.path.startsWith(storage.path))
        assertEquals(refused(MarkerRefusal.OUTSIDE), launch(file.path))
    }

    @Test fun `the allowed folder is not itself a marker`() {
        val root = File(sandbox, "root.steam")
        marker("Hollow Knight.steam", dir = root)
        assertEquals(refused(MarkerRefusal.OUTSIDE),
                     GameNativeLaunch.fromMarkerFile(root.path, listOf(root.path), forbidden))
    }

    @Test fun `a path under the Bridge's own data or under Android's folders is refused`() {
        val inData = marker("Hollow Knight.steam", dir = File(storage, "emulated/0/PegasusData/config"))
        val inApps = marker("Hollow Knight.steam", dir = File(storage, "emulated/0/Android/data/app.gamenative/files"))
        val atTop  = marker("Hollow Knight.steam", dir = File(storage, "emulated/0/PegasusData"))
        assertEquals(refused(MarkerRefusal.FORBIDDEN), launch(inData.path))
        assertEquals(refused(MarkerRefusal.FORBIDDEN), launch(inApps.path))
        assertEquals(refused(MarkerRefusal.FORBIDDEN), launch(atTop.path))
        // Beside them, on the same storage, a marker is a marker.
        assertEquals(ok(367520), launch(marker("Hollow Knight.steam", dir = File(storage, "emulated/0/Roms")).path))
        assertEquals(ok(367520), launch(marker("Hollow Knight.steam", dir = File(storage, "emulated/0/PegasusData-old")).path))
    }

    // That storage does not tell `Android` from `android`, and the canonical
    // form of a path keeps the case it was given in.
    @Test fun `a forbidden folder is forbidden in any case`() {
        val file = marker("Hollow Knight.steam", dir = File(storage, "emulated/0/pegasusDATA/config"))
        assertEquals(refused(MarkerRefusal.FORBIDDEN), launch(file.path))
    }

    @Test fun `an extension that is none of the five is refused`() {
        for (name in listOf("Hollow Knight.txt", "Hollow Knight", "Hollow Knight.steam.bak",
                            "Hollow Knight.steam ", "Hollow Knight.steam.", "Hollow Knight.steamx",
                            "Hollow Knight.json", "steam", "Hollow Knight.ste\u0430m")) {
            assertEquals(refused(MarkerRefusal.EXTENSION), launch(marker(name).path), name)
        }
        assertNull(GameNativeLaunch.sourceOfName("a.steam/b"))
    }

    @Test fun `a path with a dot, a double dot or a doubled slash in it is refused`() {
        val file = marker("Hollow Knight.steam")
        val dir = file.parent
        assertEquals(ok(367520), launch(file.path))
        for (path in listOf(
            "$dir/./Hollow Knight.steam",
            "$dir/../windows/Hollow Knight.steam",
            "$dir//Hollow Knight.steam",
            "${storage.path}/../storage/1A2B-3C4D/Emulation/Roms/windows/Hollow Knight.steam",
            "${storage.path}/emulated/../1A2B-3C4D/Emulation/Roms/windows/Hollow Knight.steam"
        )) {
            assertEquals(refused(MarkerRefusal.NOT_CANONICAL), launch(path), path)
        }
        // A slash too many at either end is refused before the file is looked
        // for: the one is under no folder as it is written, the other names no file.
        assertEquals(refused(MarkerRefusal.OUTSIDE), launch("/" + file.path))
        assertEquals(refused(MarkerRefusal.EXTENSION), launch(file.path + "/"))
    }

    // Written with a double dot, a path can begin as a forbidden folder does
    // and end outside it, or the other way about. Neither is read.
    @Test fun `a double dot leads neither out of a forbidden folder nor into one`() {
        marker("Hollow Knight.steam")
        marker("Hollow Knight.steam", dir = File(storage, "emulated/0/PegasusData/config"))
        assertEquals(refused(MarkerRefusal.FORBIDDEN),
            launch("${storage.path}/emulated/0/PegasusData/../../../1A2B-3C4D/Emulation/Roms/windows/Hollow Knight.steam"))
        assertEquals(refused(MarkerRefusal.NOT_CANONICAL),
            launch("${games.path}/../../../../emulated/0/PegasusData/config/Hollow Knight.steam"))
    }

    @Test fun `a link is refused, whether it is the file or a folder on the way to it`() {
        val file = marker("Hollow Knight.steam")
        val secret = marker("secret.steam", "440", dir = File(sandbox, "elsewhere"))

        val fileLink = File(games, "Linked.steam")
        Files.createSymbolicLink(fileLink.toPath(), file.toPath())
        assertEquals(refused(MarkerRefusal.NOT_CANONICAL), launch(fileLink.path))

        // Out of the allowed folder, to a file that would be read if it were followed.
        val outLink = File(games, "Out.steam")
        Files.createSymbolicLink(outLink.toPath(), secret.toPath())
        assertEquals(refused(MarkerRefusal.NOT_CANONICAL), launch(outLink.path))

        val dirLink = File(storage, "card")
        Files.createSymbolicLink(dirLink.toPath(), File(storage, "1A2B-3C4D").toPath())
        assertEquals(refused(MarkerRefusal.NOT_CANONICAL),
                     launch("${dirLink.path}/Emulation/Roms/windows/Hollow Knight.steam"))
    }

    @Test fun `what is not a regular file is refused`() {
        marker("inside.steam", dir = File(games, "A Folder.steam"))
        assertEquals(refused(MarkerRefusal.NOT_A_FILE), launch(File(games, "A Folder.steam").path))
        marker("Hollow Knight.steam")
        assertEquals(refused(MarkerRefusal.NOT_A_FILE), launch(File(games, "Not There.steam").path))
        assertEquals(refused(MarkerRefusal.NOT_A_FILE), launch(File(games, "no folder/Not There.steam").path))
    }

    // The verb reads the file on the thread the screen is drawn on, and a FIFO
    // makes whoever opens it wait: for a writer, and then for what it writes.
    // So one is refused for what it is, and nothing of it is read. This one
    // holds a good app id and has a writer, for three seconds: were it opened
    // all the same, the id would be read and the test would say so, late,
    // and would not hang.
    @Test fun `a FIFO is refused and never opened`() {
        marker("Hollow Knight.steam")
        val fifo = File(games, "A Pipe.steam")
        val made = runCatching { ProcessBuilder("mkfifo", fifo.path).start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(made && fifo.exists() && !fifo.isFile, "this system makes no FIFO")

        // Read and write, which is the one way to open a FIFO that does not wait.
        val writer = RandomAccessFile(fifo, "rw")
        val closer = Thread { runCatching { Thread.sleep(3000) }; runCatching { writer.close() } }
        try {
            writer.write("367520".toByteArray())
            closer.start()
            assertEquals(refused(MarkerRefusal.NOT_A_FILE), launch(fifo.path))
        } finally {
            closer.interrupt()
            closer.join()
            writer.close()
        }
    }

    @Test fun `a file of more than 16 bytes is refused, however much more`() {
        val big = write(File(games, "Big.steam"), ByteArray(1 shl 20) { '7'.code.toByte() })
        assertEquals(refused(MarkerRefusal.TOO_BIG), launch(big.path))
        // Digits and then a megabyte: no id is made of the part that would fit.
        val padded = write(File(games, "Padded.steam"), "440\n".toByteArray() + ByteArray(1 shl 20) { ' '.code.toByte() })
        assertEquals(refused(MarkerRefusal.TOO_BIG), launch(padded.path))
    }

    @Test fun `a file that is another kind of file by another name is refused`() {
        val json = marker("credentials.steam", """{"apiKey":"x"}""")
        assertEquals(refused(MarkerRefusal.NOT_AN_ID), launch(json.path))
    }

    // ── What a refusal says ─────────────────────────────────────────────────

    @Test fun `a refusal is a sentence of its own, with nothing of the path in it`() {
        val word = "Zyxwvut"
        val paths = listOf(
            null, "", "$word.steam", "/$word/$word.steam", "${games.path}/$word.txt",
            "${games.path}/./$word.steam", "${games.path}/$word not there.steam",
            "${storage.path}/emulated/0/Android/$word.steam", "${games.path}/$word\u0000.steam",
            "/" + word.repeat(200) + ".steam",
            marker("$word.steam", "$word").path,
            marker("$word 2.steam", word.repeat(4)).path
        )
        val reasons = MarkerRefusal.entries.map { it.reason }
        assertEquals(reasons.size, reasons.toSet().size, "two refusals with one sentence")
        val seen = mutableSetOf<String>()
        for (path in paths) {
            val answer = launch(path)
            assertTrue(answer is Result.Rejected, "got $answer for $path")
            assertTrue(answer.reason in reasons, answer.reason)
            assertFalse(answer.reason.contains(word, ignoreCase = true), answer.reason)
            seen += answer.reason
        }
        // Every refusal but the one for a file that is there and cannot be read.
        assertEquals(reasons.toSet() - MarkerRefusal.UNREADABLE.reason, seen)
    }

    // ── What the verb does with an intent ───────────────────────────────────

    private val DEFAULT = setOf("android.intent.category.DEFAULT")
    private val BROWSABLE = setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE")

    private fun verdict(categories: Set<String>?, canReadStorage: Boolean, readPath: () -> String?) =
        GameNativeLaunch.verdict(categories, canReadStorage, readPath, allowed, forbidden)

    @Test fun `a marker named by an intent is a game to start`() {
        val file = marker("Hollow Knight.steam")
        val start = GameNativeLaunch.Verdict.Start(Request(367520, Source.STEAM, ""))
        // Pegasus' launch line gives the intent no category at all.
        assertEquals(start, verdict(null, true) { file.path })
        assertEquals(start, verdict(emptySet(), true) { file.path })
        assertEquals(start, verdict(DEFAULT, true) { file.path })
    }

    @Test fun `an intent a browser sent is dropped, and its extra is never read`() {
        val file = marker("Hollow Knight.steam")
        var read = 0
        val readPath = { read++; file.path }
        assertEquals(GameNativeLaunch.Verdict.Ignore, verdict(BROWSABLE, true, readPath))
        assertEquals(GameNativeLaunch.Verdict.Ignore, verdict(BROWSABLE, false, readPath))
        assertEquals(GameNativeLaunch.Verdict.Ignore, verdict(setOf(GameNativeLaunch.CATEGORY_BROWSABLE), true, readPath))
        assertEquals(0, read)
        assertEquals("android.intent.category.BROWSABLE", GameNativeLaunch.CATEGORY_BROWSABLE)
    }

    @Test fun `without all-files access that is what is said, whatever the file`() {
        val file = marker("Hollow Knight.steam")
        assertEquals(GameNativeLaunch.Verdict.NoAccess, verdict(null, false) { file.path })
        assertEquals(GameNativeLaunch.Verdict.NoAccess, verdict(null, false) { "/nowhere/Not There.steam" })
        assertEquals(GameNativeLaunch.Verdict.NoAccess, verdict(null, false) { null })
    }

    @Test fun `an extra that throws as it is read is a file that was not named`() {
        marker("Hollow Knight.steam")
        val missing = GameNativeLaunch.Verdict.NotAGame("", MarkerRefusal.MISSING.reason)
        assertEquals(missing, verdict(null, true) { throw IllegalStateException("a parcel written to hurt") })
        assertEquals(missing, verdict(null, true) { throw ClassCastException("not a String") })
        assertEquals(missing, verdict(null, true) { null })
    }

    @Test fun `a refused file is named by its last segment and nothing before it`() {
        val file = marker("Hollow Knight.txt")
        assertEquals(GameNativeLaunch.Verdict.NotAGame("Hollow Knight.txt", MarkerRefusal.EXTENSION.reason),
                     verdict(null, true) { file.path })
        assertEquals(GameNativeLaunch.Verdict.NotAGame("credentials.json", MarkerRefusal.OUTSIDE.reason),
                     verdict(null, true) { "/data/secret folder/credentials.json" })
        assertEquals(GameNativeLaunch.Verdict.NotAGame("", MarkerRefusal.EXTENSION.reason),
                     verdict(null, true) { games.path + "/" })
    }

    @Test fun `a name is shown short, and without what is not for showing`() {
        assertEquals("", GameNativeLaunch.shownName(null))
        assertEquals("", GameNativeLaunch.shownName(""))
        assertEquals("Hollow Knight.steam", GameNativeLaunch.shownName("/storage/1A2B-3C4D/Roms/Hollow Knight.steam"))
        assertEquals("Hollow Knight.steam", GameNativeLaunch.shownName("Hollow Knight.steam"))
        assertEquals("STEINS;GATE.steam", GameNativeLaunch.shownName("/a/STEINS;GATE.steam"))
        assertEquals("Pok\u00E9mon \u30B2\u30FC\u30E0.steam", GameNativeLaunch.shownName("/a/Pok\u00E9mon \u30B2\u30FC\u30E0.steam"))
        // A line feed, a carriage return, a tab, a NUL, an escape and a delete.
        assertEquals("twolines.steam", GameNativeLaunch.shownName("/a/two\n\r\t\u0000\u001B\u007Flines.steam"))
        // A right-to-left override, a zero-width space, a byte-order mark, a line separator.
        assertEquals("gnp.exe", GameNativeLaunch.shownName("/a/\u202Egnp\u200B\uFEFF\u2028.exe"))
        // Half a surrogate pair goes, a whole one stays and counts as one.
        assertEquals("ab", GameNativeLaunch.shownName("/a/a\uD83Db"))
        assertEquals("a\uD83C\uDFAEb", GameNativeLaunch.shownName("/a/a\uD83C\uDFAEb"))

        val max = GameNativeLaunch.MAX_SHOWN_NAME
        assertEquals("x".repeat(max), GameNativeLaunch.shownName("/a/" + "x".repeat(max)))
        assertEquals("x".repeat(max) + "\u2026", GameNativeLaunch.shownName("/a/" + "x".repeat(max + 1)))
        assertEquals("x".repeat(max) + "\u2026", GameNativeLaunch.shownName("/a/" + "x".repeat(100_000)))
        assertEquals("\uD83C\uDFAE".repeat(max) + "\u2026", GameNativeLaunch.shownName("/a/" + "\uD83C\uDFAE".repeat(max + 5)))
        // What is not shown is not counted.
        assertEquals("x".repeat(max), GameNativeLaunch.shownName("/a/" + "x\u0007".repeat(max)))
    }

    // ── The folders of a real device ────────────────────────────────────────

    // No /storage on a machine that builds this, so nothing here is read: what
    // is held is which paths never get as far as the file system.
    @Test fun `left to itself it reads under storage and not under the two folders there`() {
        fun reason(path: String) = (GameNativeLaunch.fromMarkerFile(path) as Result.Rejected).reason
        assertEquals(listOf("/storage"), GameNativeLaunch.MARKER_ROOTS)
        assertEquals(MarkerRefusal.OUTSIDE.reason, reason("/sdcard/Roms/windows/Hollow Knight.steam"))
        assertEquals(MarkerRefusal.OUTSIDE.reason, reason("/data/local/tmp/Hollow Knight.steam"))
        assertEquals(MarkerRefusal.OUTSIDE.reason, reason("/mnt/media_rw/1A2B-3C4D/Hollow Knight.steam"))
        assertEquals(MarkerRefusal.OUTSIDE.reason, reason("/storage.steam"))
        assertEquals(MarkerRefusal.FORBIDDEN.reason, reason("/storage/emulated/0/PegasusData/config/credentials.steam"))
        assertEquals(MarkerRefusal.FORBIDDEN.reason, reason("/storage/emulated/0/Android/data/app.gamenative/a.steam"))
        assertEquals(MarkerRefusal.FORBIDDEN.reason, reason("/storage/emulated/0/android/data/app.gamenative/a.steam"))
    }
}
