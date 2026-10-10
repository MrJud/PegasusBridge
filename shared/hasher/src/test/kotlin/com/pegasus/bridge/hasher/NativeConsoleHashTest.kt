package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The library's two functions, called as the daemon calls them: a file hashed
 * as the console it is said to be, and the reason when there is no hash.
 *
 * The golden tests ask what a file hashes to when rcheevos is left to go by
 * its extension, which was the only question the library used to take. These
 * ask the ones that came with [RcheevosNative]: that a console named is the
 * console hashed, that a path arrives as the bytes the file system has, that
 * what rcheevos said of a file comes back with the failure and to the thread
 * that asked, and that the library is the build this code expects.
 *
 * The calls are made in this JVM, so every file here is one rcheevos is known
 * to return from; the files it used not to return from are
 * NativeCrashReproTest's, each in a JVM of its own. Expected hashes are worked
 * out here from the bytes, by the rule rcheevos documents for the console.
 */
class NativeConsoleHashTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("native-console").toFile() }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    // ------------------------------------------------------------ consoles

    @Test
    fun `an iNES file hashes without its header under console 7 and under the iterator`() {
        val rom = file("Console Test (World).nes", ines + prg)
        val expected = HashOutcome.Ok(HashResult(md5(prg), 7))

        assertEquals(expected, native.hashForConsole(rom.path, 7), "named as the NES")
        assertEquals(expected, native.hashForConsole(rom.path, 0), "left to its extension")
        assertEquals(expected.result, native.hash(rom.path), "hash(path), which is console 0")
    }

    // The same bytes as a Super Nintendo cartridge are hashed whole: no copier
    // header is taken off a file of this size. A native hasher that left the
    // console to the interface's default, which drops it, would answer as the
    // NES again.
    @Test
    fun `console 3 on the same file gives a different hash`() {
        val rom = file("Console Test (World).nes", ines + prg)

        assertEquals(HashOutcome.Ok(HashResult(md5(ines + prg), 3)), native.hashForConsole(rom.path, 3))
    }

    // A disc, reached the long way round: the playlist names the sheet, the
    // sheet names the track, and the console is one the extension m3u alone
    // would not have been tried as first. The hash is the first 512 bytes of
    // the track's first sector (rc_hash_sega_cd), and it is the one the golden
    // tests pin for the same track.
    @Test
    fun `a playlist naming a cue hashes the disc under console 9`() {
        val name = "Golden Sega CD (Japan)"
        val track = segaCdTrack()
        file("$name.bin", track)
        file("$name.cue", ascii("FILE \"$name.bin\" BINARY\r\n  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n"))
        val playlist = file("$name.m3u", ascii("$name.cue\n"))

        assertEquals(SEGA_CD, md5(track.copyOfRange(16, 16 + 512)), "the rule for a Sega CD disc")
        assertEquals(HashOutcome.Ok(HashResult(SEGA_CD, 9)), native.hashForConsole(playlist.path, 9))
    }

    // An arcade game is known by the name of its set, and the hash is the MD5
    // of that name. A set is a zip, so the first file here would be refused
    // by anything that opened it, and the second is not there to open.
    @Test
    fun `console 27 hashes the name and never opens the file`() {
        val noise = file("mslug.zip", ByteArray(4096) { (it * 13 + 5).toByte() })
        val absent = File(dir, "nowhere/mslug.zip")
        val expected = HashOutcome.Ok(HashResult(md5(ascii("mslug")), 27))

        assertEquals(MSLUG, md5(ascii("mslug")))
        assertEquals(expected, native.hashForConsole(noise.path, 27), "bytes that are no zip")
        assertEquals(expected, native.hashForConsole(absent.path, 27), "no file at all")
    }

    // ------------------------------------------------------------ the path

    // The path crosses into C as its UTF-8. Handed over as a string it would
    // arrive in the JVM's own variant, where the die below is six bytes and
    // not the four the file system has.
    @Test
    fun `a path with an accent and a non-BMP character hashes`() {
        val stem = "Señal 🎲 (World)"

        // As an arcade set the name itself is what is hashed, so the bytes
        // that arrived are the ones in the answer, whatever this system
        // makes of a file name.
        assertEquals(HashOutcome.Ok(HashResult(md5(stem.toByteArray(Charsets.UTF_8)), 27)),
                     native.hashForConsole(File(dir, "$stem.zip").path, 27),
                     "the name as an arcade set's: the MD5 of its UTF-8")

        // And the file is found by them. Only where the JVM writes file names
        // as UTF-8 too: under another encoding the file this test makes has
        // another name on disk than the one it asks for.
        val names = Charset.forName(System.getProperty("sun.jnu.encoding") ?: "UTF-8")
        if (!"$stem.nes".toByteArray(names).contentEquals("$stem.nes".toByteArray(Charsets.UTF_8))) {
            println("NativeConsoleHashTest: file names are written as $names here, so no file was made")
            return
        }
        val rom = file("$stem.nes", ines + prg)
        assertEquals(HashOutcome.Ok(HashResult(md5(prg), 7)), native.hashForConsole(rom.path, 0))
    }

    @Test
    fun `a call that names no file or no console is turned away with a reason`() {
        val rom = file("Console Test (World).nes", ines + prg)

        val wrong = listOf(
            Triple("", 0, "The path is empty"),
            // C ends a path at its first NUL. Let through, this one would be
            // the ROM above, and its hash the answer for a file of another name.
            Triple(rom.path + "\u0000.zip", 0, "The path has a NUL in it"),
            Triple(rom.path, -1, "Console -1 is not one of 0 to 255"),
            Triple(rom.path, 256, "Console 256 is not one of 0 to 255"),
            // A path that ends where a folder's name does. rcheevos reads
            // both strokes as that end on every system, so to it none of
            // these has a file's name; let through, the folder that is there
            // came back with a hash and a console, as if it were a game. As
            // an arcade set, whose hash is of the name, any of them ended the
            // process: those are NativeCrashReproTest's, in a JVM of their own.
            Triple(dir.path + "/", 0, "The path names no file"),
            Triple(dir.path + "/", 7, "The path names no file"),
            Triple(File(dir, "nowhere").path + "/", 0, "The path names no file"),
            Triple("C:\\roms\\", 7, "The path names no file"),
            Triple("/", 0, "The path names no file")
        ).mapNotNull { (path, console, reason) ->
            val outcome = native.hashForConsole(path, console)
            if (outcome == HashOutcome.Failed(reason)) null
            else "${path.replace("\u0000", "\\0")} as console $console: expected $reason, got $outcome"
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))

        // No path at all, and nowhere to write the reason. Kotlin does not
        // let either be said, so the function is called as Java would.
        val raw = RcheevosNative::class.java.getMethod(
            "hashForConsole", ByteArray::class.java, Int::class.javaPrimitiveType, ByteArray::class.java)
        val reason = ByteArray(64)
        assertNull(raw.invoke(null, null, 0, reason), "no path")
        assertEquals("No path was given", text(reason))
        assertNull(raw.invoke(null, File(dir, "absent.gb").path.toByteArray(), 0, null), "nowhere to write the reason")
    }

    // ------------------------------------------------------------ the reason

    @Test
    fun `a missing file fails with rcheevos' reason`() {
        val absent = File(dir, "Absent (World).nes")

        for (console in listOf(0, 7)) {
            val outcome = assertIs<HashOutcome.Failed>(native.hashForConsole(absent.path, console),
                                                       "a file that is not there, as console $console")
            assertTrue("Could not open file" in outcome.reason, "as console $console: ${outcome.reason}")
            assertTrue(outcome.retryable, "a file that could not be opened may be there tomorrow")
        }
    }

    // rcheevos hashes whatever follows the header it takes off, and where
    // nothing does it answers the MD5 of nothing, with a console, as if it
    // were a game. One answer for every such file, and a real one to look up.
    @Test
    fun `an empty file is a failure, not the MD5 of nothing`() {
        val empty = file("Empty (World).gb", ByteArray(0))
        // A copier's header of 512 bytes is told by the file's size alone.
        val headerOnly = file("Header Only (World).sfc", ByteArray(512))

        // What the library itself says, so that the rows below are known to
        // be about this answer and not about a file rcheevos refused.
        assertEquals("${RcheevosNative.MD5_OF_NOTHING}|4",
                     RcheevosNative.hashForConsole(empty.path.toByteArray(), 0, ByteArray(64)))
        assertEquals(RcheevosNative.MD5_OF_NOTHING, md5(ByteArray(0)))

        val wrong = listOf(empty to 0, empty to 4, empty to 3, headerOnly to 0, headerOnly to 3)
            .mapNotNull { (rom, console) ->
                val outcome = native.hashForConsole(rom.path, console)
                if (outcome is HashOutcome.Failed && "the MD5 of no bytes" in outcome.reason) null
                else "${rom.name} as console $console: $outcome"
            }
        if (wrong.isNotEmpty()) fail("answered otherwise than as a failure:\n" + wrong.joinToString("\n"))
        assertNull(native.hash(empty.path), "hash(path) of an empty file")
    }

    // The reason is written into a buffer the caller brings, so a call has
    // nowhere to leave it but with the caller. Kept in the library between
    // the call and a second one that fetched it, or in one place for every
    // thread, one file's reason would be given out for another's.
    @Test
    fun `two threads failing for different reasons each get their own reason`() {
        val absent = File(dir, "Absent (World).gb")
        val short = file("Short (World).nes", ines.copyOf(6))
        val rounds = 2000

        fun reasons(path: String, start: CountDownLatch, into: MutableList<String>) = thread {
            start.await()
            repeat(rounds) {
                into += (native.hashForConsole(path, 0) as? HashOutcome.Failed)?.reason ?: "not a failure"
            }
        }
        val start = CountDownLatch(1)
        val ofAbsent = ArrayList<String>(rounds)
        val ofShort = ArrayList<String>(rounds)
        val threads = listOf(reasons(absent.path, start, ofAbsent), reasons(short.path, start, ofShort))
        start.countDown()
        threads.forEach { it.join(TimeUnit.MINUTES.toMillis(2)) }
        assertTrue(threads.none { it.isAlive }, "both threads ended")

        assertEquals(mapOf("Could not open file" to rounds), ofAbsent.groupingBy { it }.eachCount(),
                     "what the thread hashing a missing file was told")
        assertEquals(mapOf("File is not longer than a NES or FDS header (16 bytes)" to rounds),
                     ofShort.groupingBy { it }.eachCount(),
                     "what the thread hashing a header cut short was told")
    }

    // The sheet names a track that is not there, and rcheevos says so with
    // the path it looked for: "Could not open <folder>/ñandú.bin; Could not
    // open track". The buffer is cut to end inside the first letter of the
    // track's name.
    @Test
    fun `a reason longer than its buffer is cut short and still ends`() {
        val sheet = file("Cut.cue", "FILE \"ñandú.bin\" BINARY\r\n  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n"
            .toByteArray(Charsets.UTF_8))
        val path = sheet.path.toByteArray(Charsets.UTF_8)
        fun reasonIn(size: Int) = ByteArray(size) { 0x7F }.also {
            assertNull(RcheevosNative.hashForConsole(path, 9, it), "a sheet whose track is missing")
        }
        val upToTheName = "Could not open ${dir.path}${File.separator}".toByteArray(Charsets.UTF_8)

        assertEquals(String(upToTheName, Charsets.UTF_8) + "ñandú.bin; Could not open track",
                     text(reasonIn(upToTheName.size + 64)), "with room for all of it")
        // Room for one byte of the name, and the NUL: that byte would be the
        // first half of ñ, which is no character, so the text ends before it.
        assertContentEquals(upToTheName + byteArrayOf(0, 0x7F), reasonIn(upToTheName.size + 2),
                            "cut between two characters, ended, and nothing written past the end")
        assertContentEquals(ascii("Could n") + byteArrayOf(0), reasonIn(8), "cut inside a word")
        assertContentEquals(byteArrayOf(0), reasonIn(1), "room for the end alone")
        assertContentEquals(ByteArray(0), reasonIn(0), "no room at all")
    }

    // The library keeps 1023 bytes of what rcheevos says of one file, however
    // large the buffer the caller brings. Left to its extension a sheet is
    // tried as one console after another, and each says the same two things
    // of a track that is not there, so a long name fills those bytes before
    // the consoles run out. The name here is chosen for the last three of
    // them to be the first three bytes of one of its dice, which have four:
    // the text is to end before that die, and with no word of what the next
    // console said in the three bytes that leaves free.
    @Test
    fun `a reason longer than the library keeps ends between two characters`() {
        val kept = 1023
        val upToTheName = "Could not open ${dir.path}${File.separator}"
        fun said(name: String): ByteArray {
            val once = "$upToTheName$name.bin; Could not open track; ".toByteArray(Charsets.UTF_8)
            return generateSequence { once }.take(kept / once.size + 1).reduce { all, more -> all + more }.copyOf(kept)
        }
        val die = "🎲".toByteArray(Charsets.UTF_8)
        val name = (0..3).flatMap { letters -> (30..60).map { dice -> "a".repeat(letters) + "🎲".repeat(dice) } }
            .firstOrNull { said(it).copyOfRange(kept - 3, kept).contentEquals(die.copyOf(3)) }
            ?: fail("no name puts the first three bytes of a die last of $kept after '$upToTheName'")
        val whole = said(name).copyOf(kept - 3)
        assertTrue(decodes(whole) && !decodes(said(name)), "the name was chosen for the cut to fall inside a character")

        val sheet = file("Kept.cue", "FILE \"$name.bin\" BINARY\r\n  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n"
            .toByteArray(Charsets.UTF_8))
        for (size in listOf(kept + 1, 2000)) {
            val reason = ByteArray(size) { 0x7F }
            assertNull(RcheevosNative.hashForConsole(sheet.path.toByteArray(Charsets.UTF_8), 0, reason),
                       "a sheet whose track is missing")
            val written = reason.copyOf(reason.indexOf(0))
            assertTrue(decodes(written), "in a buffer of $size bytes the reason ends with " +
                written.takeLast(4).joinToString(" ") { "%02x".format(it) } + ", which is part of a character")
            assertContentEquals(whole, written, "in a buffer of $size bytes: all that was kept, less the die cut short")
        }
    }

    // rcheevos gives some files up and says nothing: a playlist with no line
    // in it that names a file, when the console is left to the extension, and
    // one hashed as a console that has no playlists. A failure with nothing
    // after it reads in the scan's table as a hasher that was never asked, so
    // the library says that much itself.
    @Test
    fun `a file rcheevos gives up on without a word still comes back with a reason`() {
        val silent = HashOutcome.Failed("rcheevos gave no hash and no reason")
        val wrong = listOf(
            file("Empty.m3u", ByteArray(0)) to 0,
            file("Blank.m3u", ascii("\r\n\n")) to 0,
            file("Heading.m3u", ascii("#EXTM3U\n")) to 0,
            // The Nintendo 64, which has cartridges and no playlists.
            file("Listed.m3u", ascii("Disc 1.cue\n")) to 2
        ).mapNotNull { (playlist, console) ->
            val outcome = native.hashForConsole(playlist.path, console)
            if (outcome == silent) null else "${playlist.name} as console $console: $outcome"
        }
        if (wrong.isNotEmpty()) fail("expected $silent:\n" + wrong.joinToString("\n"))

        // Where rcheevos does say why, those are the words, and these are not added to them.
        assertEquals(HashOutcome.Failed("Failed to get first item from playlist"),
                     native.hashForConsole(File(dir, "Empty.m3u").path, 12), "the same file as a PlayStation disc")
    }

    // What the scan is told is ArchiveAwareHasher's answer. It names the file
    // as the scan knows it, which for an entry out of an archive is not the
    // temporary copy rcheevos was handed, and then gives the reason.
    @Test
    fun `what rcheevos said of a file reaches the scan under the file's own name`() {
        val reason = "File is not longer than a NES or FDS header (16 bytes)"
        val short = file("Short (World).nes", ines.copyOf(6))
        val archive = File(dir, "Short (World).zip").also { zip ->
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry(short.name)); it.write(short.readBytes()); it.closeEntry()
            }
        }
        val hasher = ArchiveAwareHasher(native, File(dir, "tmp"))

        assertEquals(HashOutcome.Failed(reason), native.hashForConsole(short.path, 0), "rcheevos' own words")
        // In a collection whose console is known the console has spoken,
        // and will say the same of this file at every scan.
        assertEquals(HashOutcome.Failed("the hasher could not read ${short.name}: $reason", retryable = false),
                     hasher.hashDetailed(short.path, "nes"), "loose")
        assertEquals(HashOutcome.Failed("the hasher could not read '${short.name}': $reason", retryable = false),
                     hasher.hashDetailed(archive.path, "nes"), "out of a zip")
        // In one nothing is known of, rcheevos went by the extension, and
        // the failure is one to try again, as it always was.
        assertEquals(HashOutcome.Failed("the hasher could not read ${short.name}: $reason"),
                     hasher.hashDetailed(short.path, "somewhere"), "loose, in a collection nobody knows")
        assertEquals(HashOutcome.Failed("the hasher could not read '${short.name}': $reason"),
                     hasher.hashDetailed(archive.path, "somewhere"), "out of a zip, in a collection nobody knows")

        // A hasher with nothing to say of a failure adds nothing: no colon
        // with nothing after it.
        val silent = object : RomHasher { override fun hash(path: String): HashResult? = null }
        assertEquals(HashOutcome.Failed("the hasher could not read ${short.name}"),
                     ArchiveAwareHasher(silent, File(dir, "tmp")).hashDetailed(short.path, "nes"))

        // And a hasher that knows asking again will change nothing is taken
        // at its word: the name goes in front of what it said, and whether
        // the file is worth another try stays as it said.
        val decided = object : RomHasher {
            override fun hash(path: String): HashResult? = null
            override fun hashForConsole(path: String, consoleId: Int): HashOutcome =
                HashOutcome.Failed("not a cartridge", retryable = false)
        }
        val settled = ArchiveAwareHasher(decided, File(dir, "tmp"))
        assertEquals(HashOutcome.Failed("the hasher could not read ${short.name}: not a cartridge", retryable = false),
                     settled.hashDetailed(short.path, "nes"), "loose, and not to be tried again")
        assertEquals(HashOutcome.Failed("the hasher could not read '${short.name}': not a cartridge", retryable = false),
                     settled.hashDetailed(archive.path, "nes"), "out of a zip, and not to be tried again")
    }

    // The same through a whole scan, with the library a daemon loads. Six
    // bytes are few enough to be a placeholder and are not one: two of them
    // are bytes no text has, so the file goes on to rcheevos, which refuses
    // it. Under `nes` the console has spoken and the refusal is kept; in a
    // folder nothing is known of it is a failure to try again. Either way
    // there is no hash, and so nothing to ask about. Before the library said
    // no to a file no longer than its header, this one was the MD5 of no
    // bytes, asked about as a game.
    @Test
    fun `a truncated NES file gives no hash and no request`(): Unit = runBlocking {
        val cut = byteArrayOf(0x4E, 0x45, 0x53, 0x1A, 0x01, 0x01)
        val roms = File(dir, "roms")
        val known = File(roms, "nes/Cut Short (World).nes").apply { parentFile.mkdirs(); writeBytes(cut) }
        val unknown = File(roms, "somewhere/Cut Short (World).nes").apply { parentFile.mkdirs(); writeBytes(cut) }
        val paths = BridgePaths(File(dir, "data"))
        val asked = AtomicInteger()
        val lookup = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome {
                asked.incrementAndGet()
                return LookupOutcome.NotFound
            }
        }
        val log = BridgeLog.current
        BridgeLog.current = NoopLog
        try {
            val s = RomScanPipeline(paths, ArchiveAwareHasher(native, File(dir, "tmp")), lookup, throttleMs = { 0L })
                .scan(listOf(roms.absolutePath))

            assertEquals(2, s.total)
            assertEquals(mapOf(ScanLedger.State.UNHASHABLE to 1, ScanLedger.State.HASH_FAILED to 1), s.states)
            assertEquals(2, s.hashFailed)
            assertEquals(0, asked.get(), "the source was asked")
            val entries = JSONObject(File(paths.cache, ScanLedger.FILE_NAME).readText()).getJSONObject("entries")
            val reason = "the hasher could not read Cut Short (World).nes: " +
                         "File is not longer than a NES or FDS header (16 bytes)"
            fun kept(f: File) = entries.getJSONObject(f.canonicalPath)
                .let { it.getString("state") to it.getString("detail") }
            assertEquals(listOf("UNHASHABLE" to reason, "HASH_FAILED" to reason), listOf(kept(known), kept(unknown)))
        } finally {
            BridgeLog.current = log
        }
    }

    // ------------------------------------------- the collection's console
    //
    // What a scan gets, through ArchiveAwareHasher: the file hashed as the
    // console its collection says. Left to itself rcheevos takes the console
    // from the extension, tries one after another where several write it,
    // and for an extension it has never heard of answers as the Game Boy.

    @Test
    fun `a file is hashed as its collection's console and not as its extension suggests`() {
        val cartridge = noise(8 * 1024, seed = 40)
        val hasher = ArchiveAwareHasher(native, File(dir, "tmp"))

        // What the extension alone comes to: three that rcheevos has no
        // console for, and one it takes for a Mega Drive cartridge.
        for ((name, console) in listOf("x.gen" to 4, "x.sms" to 4, "x.j64" to 4, "x.bin" to 1))
            assertEquals(HashOutcome.Ok(HashResult(md5(cartridge), console)),
                         native.hashForConsole(file(name, cartridge).path, 0), "$name left to its extension")

        val zipped = File(dir, "mastersystem/Zipped.zip").also { zip ->
            zip.parentFile.mkdirs()
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("Zipped.sms")); it.write(cartridge); it.closeEntry()
            }
        }
        val rows = listOf(
            Triple(inCollection("megadrive", "x.gen", cartridge), "megadrive", "${md5(cartridge)}|1"),
            Triple(inCollection("mastersystem", "x.sms", cartridge), "mastersystem", "${md5(cartridge)}|11"),
            Triple(inCollection("atarijaguar", "x.j64", cartridge), "atarijaguar", "${md5(cartridge)}|17"),
            Triple(inCollection("adam", "x.bin", cartridge), "adam", "${md5(cartridge)}|44"),
            // A cartridge of another console that has strayed in is still
            // what its extension says, where the extension says one thing:
            // hashed as the NES, with the header taken off.
            Triple(inCollection("snes", "x.nes", ines + prg), "snes", "${md5(prg)}|7"),
            // An entry out of an archive is copied to a file called .bin,
            // since rcheevos has no handler for .sms, and a .bin left to
            // its extension is a Mega Drive cartridge.
            Triple(zipped, "mastersystem", "${md5(cartridge)}|11"),
            // And where nothing is known of the collection, the guess.
            Triple(inCollection("somewhere", "x.gen", cartridge), "somewhere", "${md5(cartridge)}|4"))

        val wrong = rows.mapNotNull { (rom, collection, expected) ->
            val where = rom.relativeTo(dir).path
            val outcome = hasher.hashDetailed(rom.path, collection)
            val r = (outcome as? HashOutcome.Ok)?.result ?: return@mapNotNull "$where: expected $expected, got $outcome"
            val bytes = if (rom === zipped) cartridge else rom.readBytes()
            when {
                "${r.hash}|${r.consoleId}" != expected -> "$where: expected $expected, got ${r.hash}|${r.consoleId}"
                r.fileMd5 != md5(bytes) -> "$where: the file MD5 ${r.fileMd5} is not the ROM's"
                else -> null
            }
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
    }

    // rcheevos follows a playlist for the consoles that have them, hashes
    // its text for the others, and told a console follows one only where
    // that console has playlists. So the first entry is read here, and
    // hashed as the collection says a file of its name is: a sheet in a
    // Mega Drive collection is a Sega CD disc, and the disc is the one the
    // golden tests pin.
    @Test
    fun `a playlist is hashed as the first file it names, in the playlist's collection`() {
        val hasher = ArchiveAwareHasher(native, File(dir, "tmp"))
        val track = segaCdTrack()
        val sheet = ascii("FILE \"x.bin\" BINARY\r\n  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n")
        inCollection("megadrive", "x.bin", track)
        inCollection("megadrive", "x.cue", sheet)
        val disc = inCollection("megadrive", "x.m3u", ascii("#EXTM3U\r\nx.cue\r\ny.cue\r\n"))

        val ofDisc = assertIs<HashOutcome.Ok>(hasher.hashDetailed(disc.path, "megadrive")).result
        assertEquals("$SEGA_CD|9", "${ofDisc.hash}|${ofDisc.consoleId}")
        assertTrue(ofDisc.hash != md5(sheet) && ofDisc.hash != md5(disc.readBytes()),
                   "the hash is of the text of the sheet or of the playlist")
        // The digests beside it are of the file the scan has.
        assertEquals(md5(disc.readBytes()), ofDisc.fileMd5)

        // A console that has no playlists of its own, and hashes whole
        // whatever file it is told is one of its cartridges.
        val cartridge = noise(8 * 1024, seed = 41)
        inCollection("gba", "y.gba", cartridge)
        val listed = inCollection("gba", "x.m3u", ascii("y.gba\n"))

        val ofCartridge = assertIs<HashOutcome.Ok>(hasher.hashDetailed(listed.path, "gba")).result
        assertEquals("${md5(cartridge)}|5", "${ofCartridge.hash}|${ofCartridge.consoleId}")
        assertEquals(md5(listed.readBytes()), ofCartridge.fileMd5)

        // And the file named is hashed as the collection's console, as it
        // would be had the scan met it: rcheevos, following the playlist by
        // itself, has no console for an .sms and answers as the Game Boy.
        inCollection("mastersystem", "y.sms", cartridge)
        val eightBit = inCollection("mastersystem", "x.m3u", ascii("y.sms\n"))

        assertEquals(HashOutcome.Ok(HashResult(md5(cartridge), 4)), native.hashForConsole(eightBit.path, 0),
                     "the playlist left to rcheevos")
        val ofEightBit = assertIs<HashOutcome.Ok>(hasher.hashDetailed(eightBit.path, "mastersystem")).result
        assertEquals("${md5(cartridge)}|11", "${ofEightBit.hash}|${ofEightBit.consoleId}")

        // A playlist may name an archive, a disk of a home computer zipped
        // as it was sold. It is opened as any archive of the collection is,
        // for the one entry that is the game. rcheevos, handed the playlist,
        // would hash the name of the zip as an arcade set's.
        File(dir, "mastersystem/z.zip").let { zip ->
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("z.sms")); it.write(cartridge); it.closeEntry()
                it.putNextEntry(ZipEntry("readme.txt")); it.write(ascii("read me")); it.closeEntry()
            }
        }
        val ofZipped = inCollection("mastersystem", "zipped.m3u", ascii("z.zip\n"))

        val ofEntry = assertIs<HashOutcome.Ok>(hasher.hashDetailed(ofZipped.path, "mastersystem")).result
        assertEquals("${md5(cartridge)}|11", "${ofEntry.hash}|${ofEntry.consoleId}")
        assertEquals(md5(ofZipped.readBytes()), ofEntry.fileMd5)
    }

    // A playlist that leads nowhere is answered for from its text, and
    // nothing is handed to rcheevos: one that names itself used to be a
    // call that never came back.
    @Test
    fun `a playlist that names no file to hash fails without a native call`() {
        val counting = object : RomHasher {
            var calls = 0
            override fun hash(path: String): HashResult? { calls++; return native.hash(path) }
            override fun hashForConsole(path: String, consoleId: Int): HashOutcome {
                calls++
                return native.hashForConsole(path, consoleId)
            }
        }
        val hasher = ArchiveAwareHasher(counting, File(dir, "tmp"))
        inCollection("psx", "Packed.chd", noise(64, seed = 42))

        val rows = listOf(
            inCollection("psx", "self.m3u", ascii("self.m3u\n"))
                to HashOutcome.Failed("the playlist names another playlist, self.m3u", retryable = false),
            inCollection("psx", "other.m3u", ascii("Discs/first.m3u\n"))
                to HashOutcome.Failed("the playlist names another playlist, first.m3u", retryable = false),
            inCollection("psx", "empty.m3u", ascii("#EXTM3U\n\n"))
                to HashOutcome.Failed("the playlist lists no file", retryable = false),
            // The disc may be on its way: a copy still running lists it first.
            inCollection("psx", "gone.m3u", ascii("Disc 1.cue\n"))
                to HashOutcome.Failed("the playlist names Disc 1.cue, which is not there", retryable = true),
            inCollection("psx", "packed.m3u", ascii("Packed.chd\n"))
                to HashOutcome.UnsupportedFormat("Packed.chd, the first file the playlist names: " +
                                                 ".chd is a format this build has no reader for"))
        val wrong = rows.mapNotNull { (playlist, expected) ->
            val outcome = hasher.hashDetailed(playlist.path, "psx")
            if (outcome == expected) null else "${playlist.name}: expected $expected, got $outcome"
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
        assertEquals(0, counting.calls, "calls into the library")

        // Nor does a collection nobody knows the console of have its
        // playlists handed over as they are.
        assertEquals(HashOutcome.Failed("the playlist names another playlist, self.m3u", retryable = false),
                     hasher.hashDetailed(File(dir, "psx/self.m3u").path, "somewhere"))
        assertEquals(0, counting.calls)
    }

    // What a console says of a file it is handed, it says at every scan: a
    // file of the wrong kind in a disc collection is kept as one that
    // cannot be hashed. "Could not open track" is among those answers,
    // though it reads like a file that is missing: it is what a disc
    // console says of an image whose size fits no kind of sector. Only a
    // file that could not be opened, a sheet's track that is not there, is
    // tried again.
    //
    // And a disc image that is there and will not open, which the scan is
    // not allowed to read, say. A disc console has the same three words for
    // it as for the image of no sector size, so the file is opened once
    // more before the answer is kept.
    @Test
    fun `what a console refuses is kept, and what could not be opened is tried again`() {
        val hasher = ArchiveAwareHasher(native, File(dir, "tmp"))
        inCollection("psx", "Lost.cue",
                     ascii("FILE \"Lost.bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n"))
        val locked = inCollection("psx", "Locked.bin", noise(2352 * 4, seed = 49))
        val shut = locked.setReadable(false, false) && runCatching { locked.inputStream().close() }.isFailure
        if (!shut) println("NativeConsoleHashTest: no file could be made unreadable here, so none was tried")

        val rows = listOfNotNull(
            if (shut) Triple(locked, "Could not open track", true) else null,
            // Opens as sectors of 2048 bytes, and is no disc of the console.
            Triple(inCollection("3do", "bios.bin", noise(4096, seed = 43)), "Not a 3DO CD", false),
            // 5000 bytes are no whole number of sectors of any size.
            Triple(inCollection("3do", "other.bin", noise(5000, seed = 44)), "Could not open track", false),
            // The audio track of a disc, met by a scan as a file of its own.
            Triple(inCollection("psx", "Track 2.bin", noise(2352 * 4, seed = 45)),
                   "Could not locate primary executable", false),
            Triple(File(dir, "psx/Lost.cue"), "Lost.bin; Could not open track", true),
            Triple(File(dir, "psx/Absent.bin"), "no such file", true))

        val wrong = rows.mapNotNull { (rom, said, again) ->
            val collection = rom.parentFile.name
            val outcome = hasher.hashDetailed(rom.path, collection)
            when {
                outcome !is HashOutcome.Failed -> "$collection/${rom.name}: $outcome"
                said !in outcome.reason -> "$collection/${rom.name}: said '${outcome.reason}'"
                outcome.retryable != again ->
                    "$collection/${rom.name}: retryable is ${outcome.retryable}: ${outcome.reason}"
                else -> null
            }
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
    }

    // The rule itself, over what the library and its JNI file can say. The
    // messages are rcheevos' own, and the tests above meet four of them on
    // real files.
    @Test
    fun `only a file that could not be opened is worth another try`() {
        val rows = listOf(
            "" to true,
            "Could not open file" to true,
            "Could not open playlist" to true,
            "Could not open /roms/psx/Some Game/Some Game (Track 1).bin; Could not open track" to true,
            "Could not open C:\\roms\\psx\\Some Game.bin; Could not open track" to true,
            "Could not open /roms/psx/track 01.bin; Could not open track" to true,
            "No console mapping specified for xyz file extension - trying full file hash; Could not open file" to true,
            "Not a 3DO CD; Could not open file" to true,
            "Could not open track; Could not open file" to true,
            "Could not open track" to false,
            "Could not open track; Could not open track" to false,
            "Not a supported Wii file; Could not open track" to false,
            "Not a 3DO CD" to false,
            "File is not longer than a NES or FDS header (16 bytes)" to false,
            "Failed to get first item from playlist" to false,
            "rcheevos gave no hash and no reason" to false,
            "The path names no file" to false,
            "The path is empty" to false,
            "nothing in the file is hashed for console 4: the answer was the MD5 of no bytes" to false)
        val wrong = rows.filter { (reason, expected) -> RcheevosNative.isTransient(reason) != expected }
        assertEquals(emptyList(), wrong)
    }

    // The console is the plan's and the plan is made of the file's size
    // too. A hasher that keeps what it was asked stands in for the library:
    // a disc image of two consoles, each as large as a disc, is nothing to
    // build for a test.
    @Test
    fun `the consoles a file is tried as are its plan's, once each and in its order`() {
        class Told(private val answers: Map<Int, HashOutcome>) : RomHasher {
            val asked = mutableListOf<Int>()
            override fun hash(path: String): HashResult? = null
            override fun hashForConsole(path: String, consoleId: Int): HashOutcome {
                asked += consoleId
                return answers[consoleId] ?: HashOutcome.Ok(HashResult("hash-as-$consoleId", consoleId))
            }
        }
        fun tried(collection: String, rom: File,
                  answers: Map<Int, HashOutcome> = emptyMap()): Pair<List<Int>, HashOutcome> {
            val told = Told(answers)
            val outcome = ArchiveAwareHasher(told, File(dir, "tmp")).hashDetailed(rom.path, collection)
            return told.asked.toList() to outcome
        }
        fun hash(outcome: HashOutcome) = (outcome as? HashOutcome.Ok)?.result?.hash ?: outcome.toString()
        val notWii = HashOutcome.Failed("Not a supported Wii file")
        val notGameCube = HashOutcome.Failed("Not a Gamecube disc")
        val disc = inCollection("wii", "Disc.iso", noise(64, seed = 46))

        // Both consoles write .iso, and a disc of one is often filed with
        // the other's: refused by the collection's own, it is tried once
        // as the other.
        tried("wii", disc).let { (asked, outcome) ->
            assertEquals(listOf(19) to "hash-as-19", asked to hash(outcome), "a Wii disc among the Wii's")
        }
        tried("wii", disc, mapOf(19 to notWii)).let { (asked, outcome) ->
            assertEquals(listOf(19, 16) to "hash-as-16", asked to hash(outcome), "a GameCube disc among the Wii's")
        }
        tried("gc", disc, mapOf(16 to notGameCube)).let { (asked, outcome) ->
            assertEquals(listOf(16, 19) to "hash-as-19", asked to hash(outcome), "a Wii disc among the GameCube's")
        }
        tried("wii", disc, mapOf(19 to notWii, 16 to notGameCube)).let { (asked, outcome) ->
            assertEquals(listOf(19, 16), asked, "a disc of neither")
            assertEquals(HashOutcome.Failed("the hasher could not read Disc.iso: Not a supported Wii file; " +
                                            "as console 16: Not a Gamecube disc", retryable = false), outcome)
        }
        // A file that could not be opened is no likelier to open as
        // another console, and is tried again at the next scan.
        tried("wii", disc, mapOf(19 to HashOutcome.Failed("Could not open file"))).let { (asked, outcome) ->
            assertEquals(listOf(19), asked, "a disc that could not be opened")
            assertEquals(HashOutcome.Failed("the hasher could not read Disc.iso: Could not open file"), outcome)
        }
        // Nor is one that the first console read and refused, and the second
        // could not open: the card it is on went between the two.
        tried("wii", disc, mapOf(19 to notWii, 16 to HashOutcome.Failed("Could not open file"))).let { (asked, outcome) ->
            assertEquals(listOf(19, 16), asked, "a disc that could not be opened the second time")
            assertEquals(HashOutcome.Failed("the hasher could not read Disc.iso: Could not open file"), outcome)
        }
        // One console and no other where the collection has no second.
        tried("psx", inCollection("psx", "Disc.iso", noise(64, seed = 47)),
              mapOf(12 to HashOutcome.Failed("Not a PlayStation disc"))).let { (asked, outcome) ->
            assertEquals(listOf(12), asked, "a disc the one console refuses")
            assertEquals(HashOutcome.Failed("the hasher could not read Disc.iso: Not a PlayStation disc",
                                            retryable = false), outcome)
        }
        // Never the guess, for a collection that is known: console 0 is
        // for one nothing is known of.
        assertEquals(listOf(0), tried("somewhere", disc).first, "a collection nobody knows")

        // A .bin in a Mega Drive collection is a cartridge, and one too
        // large for any cartridge is the track of a Sega CD disc. The file
        // is all zeros, and is never written out in full.
        val small = inCollection("megadrive", "Small.bin", noise(64, seed = 48))
        val large = File(dir, "megadrive/Large.bin").apply {
            RandomAccessFile(this, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        }
        assertEquals(listOf(1), tried("megadrive", small).first, "a cartridge")
        assertEquals(listOf(9), tried("megadrive", large).first, "a file larger than any cartridge")
    }

    // ------------------------------------------------------------ the engine

    // The library puts its version together from the headers it was compiled
    // with, and the Kotlin holds it to a literal. Here both are held to the
    // headers in the tree, so that a patch added to the C, or a new rcheevos,
    // is a library to build again and a literal to change, and neither can be
    // forgotten.
    @Test
    fun `the library reports the version the sources define`() {
        val cpp = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "hasher/src/main/cpp") }
            .firstOrNull { File(it, "pb_patchlevel.h").isFile }
            ?: fail("no hasher/src/main/cpp above ${File("").absolutePath}")
        fun defined(header: String, name: String): String =
            Regex("""^\s*#\s*define\s+$name\s+(\d+)\s*$""", RegexOption.MULTILINE)
                .find(File(cpp, header).readText())?.groupValues?.get(1)
                ?: fail("$header does not define $name as a number")

        val sources = listOf("MAJOR", "MINOR", "PATCH")
            .joinToString(".") { defined("rcheevos/src/rc_version.h", "RCHEEVOS_VERSION_$it") } +
            "+pb" + defined("pb_patchlevel.h", "PB_RCHEEVOS_PATCHLEVEL")

        assertEquals(sources, RcheevosNative.version(), "the library, against the headers in the tree")
        assertEquals(sources, RcheevosNative.EXPECTED_VERSION, "the version the Kotlin expects")
        assertEquals(sources, native.engine, "the hasher's engine")
        assertEquals(sources, ArchiveAwareHasher(native, File(dir, "tmp")).engine, "handed on by the archive hasher")
    }

    // The daemon is jars and a library, installed side by side, and a library
    // left over from an older build loads as well as the right one: loading
    // asks for no function by name. So the loader asks for the version before
    // it hands a hasher out. Any library without the two functions shows it,
    // and the JDK ships small ones; a child JVM, because this one has the
    // right library loaded already and would answer through that.
    @Test
    fun `a library without the two functions is not handed out as a hasher`() {
        val foreign = listOf("rmi", "prefs", "attach")
            .flatMap { name -> listOf("lib", "bin").map { File(System.getProperty("java.home"), "$it/${System.mapLibraryName(name)}") } }
            .firstOrNull { it.isFile }
        assumeTrue(foreign != null, "this JDK ships none of the small libraries the test borrows")
        val rom = file("Console Test (World).nes", ines + prg)

        val result = NativeChild.run(listOf(rom.path, "0"), library = foreign!!.path)

        // Not loaded, said so, and for want of the function: not a file the
        // library could not be read from, and not a hasher that then threw at
        // the first ROM, which is a child that ends with another status.
        val ended = assertIs<NativeChild.Result.Crashed>(result, "a child told to load ${foreign.name}")
        assertEquals(NativeChild.EXIT_NO_LIBRARY, ended.exit, ended.said)
        assertTrue("could not load" in ended.said && "RcheevosNative.version" in ended.said, ended.said)
    }

    // Every hasher that stands in for the native one in a test implements
    // hash(path) and nothing else. The two members added for the native one
    // are defaulted so that all of them still answer: for any console as for
    // none, and with an engine that is nobody's.
    @Test
    fun `a hasher that only hashes answers for any console and names no engine`() {
        val fixed = object : RomHasher {
            override fun hash(path: String): HashResult? =
                if (path.endsWith(".nes")) HashResult("hash-of-${File(path).name}", 7) else null
        }

        assertEquals(HashOutcome.Ok(HashResult("hash-of-A.nes", 7)), fixed.hashForConsole("A.nes", 0))
        assertEquals(HashOutcome.Ok(HashResult("hash-of-A.nes", 7)), fixed.hashForConsole("A.nes", 3),
                     "the console is not the default's to refuse")
        assertEquals(HashOutcome.Failed(""), fixed.hashForConsole("A.txt", 0))
        assertEquals("none", fixed.engine)
        assertEquals("none", ArchiveAwareHasher(fixed, File(dir, "tmp")).engine)
    }

    // ---------------------------------------------------------------- helpers

    /** An iNES header: one 16 KiB PRG bank, no CHR. */
    private val ines = ascii("NES\u001a") + byteArrayOf(1, 0) + ByteArray(10)

    private val prg = ByteArray(16 * 1024) { (it * 29 + 11).toByte() }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    /** A file in a folder named as its collection is, as a library keeps it. */
    private fun inCollection(collection: String, name: String, bytes: ByteArray) =
        File(dir, "$collection/$name").apply { parentFile.mkdirs(); writeBytes(bytes) }

    /**
     * The Sega CD data track GoldenHashTest builds, byte for byte, so that the
     * hash it pins is the one expected here: raw MODE1/2352 sectors with their
     * sync pattern and address, the disc and ROM headers in sector 0.
     */
    private fun segaCdTrack(sectors: Int = 32): ByteArray {
        fun bcd(n: Int) = ((n / 10) shl 4 or (n % 10)).toByte()
        val track = ByteArray(sectors * 2352)
        for (s in 0 until sectors) {
            val at = s * 2352
            track.fill(0xFF.toByte(), at + 1, at + 11)
            val lba = s + 150
            byteArrayOf(bcd(lba / 4500), bcd(lba / 75 % 60), bcd(lba % 75), 1).copyInto(track, at + 12)
            noise(2048, seed = 300 + s).copyInto(track, at + 16)
        }
        ascii("SEGADISCSYSTEM  GOLDENTEST ").copyInto(track, 16)
        ascii("SEGA MEGA DRIVE ").copyInto(track, 16 + 0x100)
        return track
    }

    /** GoldenHashTest's filler, a xorshift stream by seed. */
    private fun noise(size: Int, seed: Int): ByteArray {
        var x = seed * 0x9E3779B1.toInt() xor 0x2545F491
        return ByteArray(size) {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            (x ushr 24).toByte()
        }
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    /** What the library wrote into [reason], up to the NUL that ends it. */
    private fun text(reason: ByteArray) = String(reason, 0, reason.indexOf(0), Charsets.UTF_8)

    /** Whether [bytes] are UTF-8 from end to end, with no character cut short. */
    private fun decodes(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)); true
    } catch (e: CharacterCodingException) {
        false
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** md5("mslug"), as GoldenHashTest has it. */
        const val MSLUG = "b43c8b4ec999588c04dad79bb8bcc745"

        /** The disc segaCdTrack builds, as GoldenHashTest pins it. */
        const val SEGA_CD = "a359a33f916e15c4efd7fc52d8e1d998"

        /**
         * The library Gradle's test task names, loaded by its path as the
         * golden tests load it: the committed one, or the one given with
         * -Ppegasus.nativeLibrary.
         */
        val native: NativeRomHasher by lazy {
            val path = System.getProperty("pegasus.bridge.nativeLibrary")
                ?: fail("pegasus.bridge.nativeLibrary is not set: run this through Gradle, whose test " +
                        "task points it at native/out")
            NativeRomHasher.resetForTests()
            NativeRomHasher.tryLoad(File(path))
                ?: fail("could not load $path: ${NativeRomHasher.lastError()}")
        }
    }
}
