package com.pegasus.bridge.hasher

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden RetroAchievements hashes, from the real rcheevos.
 *
 * Every other hasher test stands a fake in for rcheevos, so none of them could
 * see the hash itself go wrong — and it did: an entry taken out of an archive
 * was handed over as `bridge_*.bin`, rcheevos chose its algorithm by that
 * extension, and an iNES ROM came back with a confident Mega Drive hash. These
 * run small synthetic ROMs through what the daemon runs, [ArchiveAwareHasher]
 * over [NativeRomHasher] with the librahasher committed in native/out, loose,
 * inside a zip and inside a 7z, and pin both the hash and the console id.
 *
 * The expected values do not come from the code under test. Each ROM carries
 * the rule rcheevos applies to its console (hash_rom.c: what follows an iNES
 * header, a whole Mega Drive cartridge, a DS header and its code blocks), which
 * is computed here; and each value is also pinned as a literal, checked once
 * against a standalone rcheevos built from the sources of v12.5.0 as upstream
 * has them, with no local patch on them and no JNI in between, as they had
 * been against v12.3.0 before it. The first test fails if the rules and the
 * literals ever part.
 *
 * A library that will not load fails these tests rather than skipping them. A
 * golden test that quietly does nothing on the machine it is meant for is worse
 * than not having one.
 */
class GoldenHashTest {

    private lateinit var dir: File

    // Lazy, so the test that holds the literals to the rules runs, and passes or
    // fails on its own merits, where the library will not load.
    private val hasher by lazy { ArchiveAwareHasher(native, File(dir, "tmp")) }

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("golden").toFile() }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    /**
     * One synthetic ROM: its bytes, what rcheevos digests of them by the
     * documented rule for its console, and the answer pinned for it.
     */
    private class Rom(
        val name: String,
        /** The Pegasus short name, which decides what ArchiveSelector accepts. */
        val platform: String,
        val bytes: ByteArray,
        val consoleId: Int,
        /** What a standalone rcheevos v12.5.0 answered for exactly these bytes. */
        val golden: String,
        /** The bytes the rule for this console digests, taken from [bytes]. */
        val hashed: (ByteArray) -> ByteArray
    ) {
        val stem: String get() = name.substringBeforeLast('.')
    }

    // Each ROM's filler is seeded apart, so two different ROMs never share a hash
    // and a swapped row cannot pass. The pairs that do share one — .sfc and .smc,
    // .z64 and .v64 — are one cartridge dumped two ways, and one hash for both is
    // the point of the rule.
    private val roms: List<Rom> by lazy {
        val snes = noise(32 * 1024, seed = 2)              // a multiple of 8 KiB: no copier header
        val n64 = noise(0x11000, seed = 4).apply {         // one 64 KiB read and a remainder
            put(0, byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40))   // big-endian, as .z64 is
        }
        listOf(
            Rom("Golden NES (World).nes", "nes", ines() + noise(16 * 1024 + 8 * 1024, seed = 1), 7,
                "3243c6ff5a3490925257e08995c7c842") { it.copyOfRange(16, it.size) },
            Rom("Golden SNES (USA).sfc", "snes", snes, 3,
                "c0fec12cef29d0b58a6ab1968aadab39") { it },
            Rom("Golden SNES Copier (USA).smc", "snes", superWildCard(snes.size) + snes, 3,
                "c0fec12cef29d0b58a6ab1968aadab39") { it.copyOfRange(512, it.size) },
            Rom("Golden GBA (Europe).gba", "gba", gba(), 5,
                "7bdc30e08b88820b308a7fc848144153") { it },
            Rom("Golden N64 (USA).z64", "n64", n64, 2,
                "c2fb958f5c5ec262626893a1d95c9843") { it },
            Rom("Golden N64 Byteswapped (USA).v64", "n64", swap16(n64), 2,
                "c2fb958f5c5ec262626893a1d95c9843") { swap16(it) },
            Rom("Golden Lynx (World).lnx", "lynx", lnx() + noise(128 * 256, seed = 5), 13,
                "961ee21225a1478715269d18be87636d") { it.copyOfRange(64, it.size) },
            Rom("Golden 7800 (USA).a78", "atari7800", a78() + noise(16 * 1024, seed = 6), 51,
                "e4283010b1b14a1059ea5b9e159fe15d") { it.copyOfRange(128, it.size) },
            // A PC Engine copier header has no magic: rcheevos spots it by the file
            // being 512 bytes more than a multiple of 8 KiB, which this one is.
            Rom("Golden PC Engine (Japan).pce", "pcengine", ByteArray(512) + noise(32 * 1024, seed = 7), 8,
                "6638840a9f735a56905c7eeea480c028") { it.copyOfRange(512, it.size) },
            Rom("Golden Mega Drive (Europe).md", "megadrive", megaDrive(), 1,
                "acbb02a72a68551446d942f6992e117b") { it },
            Rom("Golden DS (Europe).nds", "nds", nds(), 18,
                "e6005e3814b6874bc22a751d82aab28a") { ndsHashed(it) }
        )
    }

    // ---------------------------------------------------------------- golden

    @Test
    fun `the pinned hashes are what the documented rcheevos rules give`() {
        val wrong = roms.mapNotNull { rom ->
            val ruled = md5(rom.hashed(rom.bytes))
            if (ruled == rom.golden) null else "${rom.name}: the rule gives $ruled, pinned ${rom.golden}"
        }
        if (wrong.isNotEmpty()) fail("pinned hashes disagree with the rules:\n" + wrong.joinToString("\n") { "  $it" })
    }

    @Test
    fun `each ROM on its own hashes to its golden value`() {
        assertGolden("on their own", inArchive = false) { rom ->
            File(dir, rom.name).apply { writeBytes(rom.bytes) }
        }
    }

    @Test
    fun `each ROM inside a zip with a readme hashes to its golden value`() {
        assertGolden("inside a zip", inArchive = true) { rom ->
            zip(File(dir, rom.stem + ".zip"), README to readme(rom), rom.name to rom.bytes)
        }
    }

    @Test
    fun `each ROM inside a 7z with a readme hashes to its golden value`() {
        assertGolden("inside a 7z", inArchive = true) { rom ->
            sevenZ(File(dir, rom.stem + ".7z"), README to readme(rom), rom.name to rom.bytes)
        }
    }

    // A raw disc image and nothing else. rcheevos has no handler for `.img`, and
    // an entry that kept that name came back as the MD5 of the whole image with
    // console 4, its fallback for an extension it does not know. Handed over as
    // `.bin`, an image past 32 MiB is tried as a CD track, and it is the disc.
    @Test
    fun `a raw disc image named img inside a zip hashes as the disc it holds`() {
        val name = "Golden Sega CD (Japan)"
        val image = segaCdTrack(sectors = 32 * 1024 * 1024 / 2352 + 1)

        // rcheevos itself, given the image loose as a .bin, says what the disc is.
        val loose = File(dir, "$name.bin").apply { writeBytes(image) }
        assertEquals(HashResult(SEGA_CD, 9), native.hash(loose.absolutePath))

        val archive = zip(File(dir, "$name.zip"), "$name.img" to image)
        val r = assertIs<HashOutcome.Ok>(hasher.hashDetailed(archive.absolutePath, "segacd")).result
        assertEquals("$SEGA_CD|9", "${r.hash}|${r.consoleId}")
        assertEquals("$name.img", r.archiveEntry)
        assertEquals(md5(image), r.fileMd5)
    }

    /**
     * Hashes every ROM, packed by [pack], and reports every row that is wrong at
     * once: which consoles fail says more than the first one that does.
     */
    private fun assertGolden(where: String, inArchive: Boolean, pack: (Rom) -> File) {
        val wrong = roms.mapNotNull { rom ->
            val expected = "${rom.golden}|${rom.consoleId}"
            val outcome = hasher.hashDetailed(pack(rom).absolutePath, rom.platform)
            val r = (outcome as? HashOutcome.Ok)?.result
                ?: return@mapNotNull "${rom.name}: expected $expected, got $outcome"
            when {
                "${r.hash}|${r.consoleId}" != expected ->
                    "${rom.name}: expected $expected, got ${r.hash}|${r.consoleId}"
                r.fileMd5 != md5(rom.bytes) ->
                    "${rom.name}: the file MD5 ${r.fileMd5} is not the ROM's"
                inArchive && r.archiveEntry != rom.name ->
                    "${rom.name}: the entry recorded is '${r.archiveEntry}'"
                else -> null
            }
        }
        if (wrong.isNotEmpty())
            fail("${wrong.size} of ${roms.size} ROMs $where miss their golden hash:\n" +
                 wrong.joinToString("\n") { "  $it" })
    }

    // -------------------------------------------------------------- arcade
    //
    // RetroAchievements knows an arcade game by the name of its set and not by
    // what is in it: for mslug.zip rcheevos hashes "mslug" (rc_hash_arcade,
    // hash_rom.c). The archive hasher used to open the set like any other
    // zip. "arcade" has no list of what it runs, so every BIOS image a
    // non-merged set carries looked like a ROM and the set was an archive
    // nobody could decide; and a set with one `.bin` among its chips had that
    // chip copied out and hashed whole as a Mega Drive cartridge, an answer
    // that looks like any other. Both were pinned here as known gaps.
    @Test
    fun `an arcade set is hashed by its name, unopened`() {
        val bios = listOf("asia-s3.rom", "vs-bios.rom", "uni-bios_4_0.rom", "japan-j3.bin", "sp1-j3.bin")
        val chips = listOf("201-p1.p1", "201-s1.s1", "201-m1.m1", "201-v1.v1", "201-v2.v2",
                           "201-c1.c1", "201-c2.c2", "000-lo.lo", "sfix.sfix", "sm1.sm1", "sp-s2.sp1")
        val entries = (chips + bios).mapIndexed { i, entry -> entry to noise(1024, seed = 100 + i) }.toTypedArray()
        assertEquals(MSLUG, md5("mslug".toByteArray()))
        assertEquals(DOLPHIN, md5("dolphin".toByteArray()))

        // A set with the BIOS images in it, as a zip and as a 7z.
        val zipped = zip(File(dir, "arcade/mslug.zip"), *entries)
        val sevenZipped = sevenZ(File(dir, "arcade/mslug.7z"), *entries)
        // The set whose one `.bin` was taken for a cartridge.
        val single = zip(File(dir, "atomiswave/dolphin.zip"),
                         "ax0401p01.ic18" to noise(2048, seed = 201),
                         "ax0401m01.ic11" to noise(2048, seed = 202),
                         "ax0401f01.bin" to noise(4, seed = 200))
        // Bytes that are no archive at all: opened, this one would be refused.
        val noArchive = File(dir, "mame/mslug.zip").apply { parentFile.mkdirs(); writeBytes(noise(4096, seed = 203)) }
        // FBNeo runs the cartridges of some consoles as sets kept in a folder
        // named for the console, and rcheevos puts that folder into the hash.
        val inConsoleFolder = zip(File(dir, "arcade/nes/foo.zip"), "foo.prg" to noise(64, seed = 204))

        val rows = mutableListOf(
            Triple(zipped, "arcade", MSLUG),
            Triple(sevenZipped, "arcade", MSLUG),
            Triple(single, "atomiswave", DOLPHIN),
            Triple(noArchive, "mame", MSLUG),
            Triple(inConsoleFolder, "arcade", md5("nes_foo".toByteArray())))

        // The folder in the hash is the one in the path the scan came by. A
        // set reached through a link called arcade, to a folder that happens
        // to be called nes, is in arcade: followed to where it lies, the path
        // would name nes and the hash would be of "nes_mslug".
        val linked = File(dir, "nes/mslug.zip").apply { parentFile.mkdirs(); writeBytes(zipped.readBytes()) }
        val link = File(dir, "linked/arcade")
        val viaLink = try {
            link.parentFile.mkdirs()
            Files.createSymbolicLink(link.toPath(), linked.parentFile.toPath())
            File(link, "mslug.zip")
        } catch (e: Exception) {
            println("GoldenHashTest: no symbolic link could be made here (${e.javaClass.simpleName}), so none was followed")
            null
        }
        if (viaLink != null) {
            assertEquals(linked.canonicalPath, viaLink.canonicalPath, "the link leads to the folder nes")
            rows += Triple(viaLink, "arcade", MSLUG)
        }

        val wrong = rows.mapNotNull { (set, platform, expected) ->
            val where = set.relativeTo(dir).path
            val outcome = hasher.hashDetailed(set.absolutePath, platform)
            val r = (outcome as? HashOutcome.Ok)?.result
                ?: return@mapNotNull "$where: expected $expected|27, got $outcome"
            when {
                "${r.hash}|${r.consoleId}" != "$expected|27" -> "$where: expected $expected|27, got ${r.hash}|${r.consoleId}"
                // Nothing was taken out of it, and the digests beside the
                // hash are the archive's own.
                r.archiveEntry.isNotEmpty() -> "$where: the entry '${r.archiveEntry}' was taken out"
                r.fileMd5 != md5(set.readBytes()) -> "$where: the file MD5 ${r.fileMd5} is not the archive's"
                else -> null
            }
        }
        if (wrong.isNotEmpty()) fail("arcade sets not hashed by their names:\n" + wrong.joinToString("\n") { "  $it" })
        assertFalse(File(dir, "tmp").exists(), "an entry of a set was copied out")
    }

    // ---------------------------------------------------------------- discs
    //
    // A disc is its sheet plus the tracks the sheet names, and rcheevos reads
    // the tracks from beside it. ArchiveSelector rightly picks the `.cue` as
    // the entry point of an archive, but only the `.cue` was copied out, with
    // no track beside it: for a while that gave the MD5 of the sheet's text,
    // then a failure known in advance, pinned here as a known gap. The tracks
    // are taken out with the sheet now, and the archive gives what the pair
    // gives loose, which for this Sega CD disc is the MD5 of the first 512
    // bytes of sector 0 with console 9 (rc_hash_sega_cd, hash_disc.c).
    @Test
    fun `a cue and its bin inside a zip or 7z hash as the loose pair`() {
        val name = "Golden Sega CD (Japan)"
        val track = segaCdTrack()
        val cue = cueFor(name)
        assertEquals(SEGA_CD, md5(track.copyOfRange(16, 16 + 512)), "the rule for a Sega CD disc")

        // Loose, the pair hashes, and that is the answer an archive is held to.
        val loose = File(dir, "segacd").apply { mkdirs() }
        File(loose, "$name.bin").writeBytes(track)
        val looseCue = File(loose, "$name.cue").apply { writeBytes(cue) }
        val r = assertIs<HashOutcome.Ok>(hasher.hashDetailed(looseCue.absolutePath, "segacd")).result
        assertEquals("$SEGA_CD|9", "${r.hash}|${r.consoleId}")

        // The sheet of the last row spells its track in capitals, and the
        // entry is not spelt so: the track has to lie under the sheet's
        // spelling, or on a system that tells the two apart it is not found.
        val shouted = ("FILE \"${name.uppercase()}.BIN\" BINARY\r\n" +
                       "  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n").toByteArray()
        val rows = listOf(
            zip(File(dir, "$name.zip"), "$name.cue" to cue, "$name.bin" to track) to cue,
            sevenZ(File(dir, "$name.7z"), "$name.cue" to cue, "$name.bin" to track) to cue,
            // Solid, and in the order 7-Zip packs them: the track first.
            SolidSevenZ.write(File(dir, "solid/$name.7z"), README to "x".toByteArray(), "$name.bin" to track,
                              "$name.cue" to cue) to cue,
            SolidSevenZ.write(File(dir, "shouted/$name.7z"), "disc/$name.bin" to track,
                              "disc/$name.cue" to shouted) to shouted)

        val wrong = rows.mapNotNull { (archive, sheet) ->
            val where = archive.relativeTo(dir).path
            val outcome = hasher.hashDetailed(archive.absolutePath, "segacd")
            val result = (outcome as? HashOutcome.Ok)?.result
                ?: return@mapNotNull "$where: expected $SEGA_CD|9, got $outcome"
            when {
                "${result.hash}|${result.consoleId}" != "$SEGA_CD|9" ->
                    "$where: expected $SEGA_CD|9, got ${result.hash}|${result.consoleId}"
                // The digests beside the hash are the sheet's, as loose.
                result.fileMd5 != md5(sheet) -> "$where: the file MD5 ${result.fileMd5} is not the sheet's"
                result.fileMd5 == result.hash -> "$where: the hash is the MD5 of the sheet's text"
                !result.archiveEntry.endsWith("$name.cue") -> "$where: the entry recorded is '${result.archiveEntry}'"
                else -> null
            }
        }
        if (wrong.isNotEmpty()) fail("discs in archives miss their golden hash:\n" + wrong.joinToString("\n") { "  $it" })
        assertEquals(emptyList(), File(dir, "tmp").listFiles().orEmpty().map { it.name }, "a disc was left behind")
    }

    // What rcheevos itself says of a disc taken out whose sheet it reads for
    // another name than was written: two blanks after FILE, which it takes
    // for the start of a bare name. It asks for a file that was never in the
    // archive, by its path. Loose, those words mean a track that may be there
    // tomorrow; here they are a verdict, or the disc would be taken out again
    // at every scan to hear them.
    @Test
    fun `a sheet rcheevos reads for another name than was written is refused once`() {
        val name = "Golden Sega CD (Japan)"
        val sheet = ("FILE  \"$name.bin\" BINARY\r\n  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n").toByteArray()
        val archive = zip(File(dir, "$name.zip"), "$name.cue" to sheet, "$name.bin" to segaCdTrack())

        val outcome = assertIs<HashOutcome.Failed>(hasher.hashDetailed(archive.absolutePath, "segacd"))
        assertEquals("the hasher could not read '$name.cue': Could not open  \"Golden; Could not open track",
                     outcome.reason)
        assertFalse(outcome.retryable, "it would be taken out again at every scan")

        // In a collection nobody knows rcheevos tries every console a .cue
        // may be of, and each says the same with the path. They do not all
        // fit in what the library keeps of a reason, so the last path is cut
        // short wherever the room ends, and no part of it is to stay.
        val guessed = assertIs<HashOutcome.Failed>(hasher.hashDetailed(archive.absolutePath, ""))
        assertTrue(guessed.reason.startsWith("the hasher could not read '$name.cue': Could not open  \"Golden; " +
                                             "Could not open track"), guessed.reason)
        assertFalse(File.separator in guessed.reason || "bridge_" in guessed.reason, guessed.reason)
        assertFalse(guessed.retryable, "it would be taken out again at every scan")
        assertEquals(emptyList(), File(dir, "tmp").listFiles().orEmpty().map { it.name })
    }

    // ---------------------------------------------------------------- ROMs

    /** iNES: one 16 KiB PRG bank and one 8 KiB CHR bank. */
    private fun ines() = ByteArray(16).apply {
        put(0, byteArrayOf('N'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 0x1A, 1, 1))
    }

    /** A Super Wild Card copier header: size in 8 KiB units, then its AA BB signature. */
    private fun superWildCard(romSize: Int) = ByteArray(512).apply {
        put(0, byteArrayOf((romSize / 8192).toByte(), 0))
        put(8, byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 4))
    }

    private fun gba() = noise(64 * 1024, seed = 3).apply {
        put(0xA0, "GOLDENTEST\u0000\u0000")
        put(0xB2, byteArrayOf(0x96.toByte()))            // the fixed value every cartridge has
    }

    /**
     * LYNX header, 64 bytes. rcheevos compares "LYNX" with its terminating NUL,
     * which a real header supplies as the low byte of a 256-byte page size.
     */
    private fun lnx() = ByteArray(64).apply {
        put(0, "LYNX")
        put(4, byteArrayOf(0x00, 0x01))                  // bank 0: 256-byte pages
        put(8, byteArrayOf(1, 0))                        // version
        put(10, "Golden Lynx")
        put(42, "Pegasus")
    }

    /** A78 header, 128 bytes, its signature one byte in. */
    private fun a78() = ByteArray(128).apply {
        put(0, byteArrayOf(1))
        put(1, "ATARI7800")
        put(17, "Golden 7800")
        put(49, byteArrayOf(0, 0, 0x40, 0))              // 16 KiB, big-endian
        put(100, "ACTUAL CART DATA STARTS HERE")
    }

    private fun megaDrive() = noise(64 * 1024, seed = 8).apply {
        put(0x100, "SEGA MEGA DRIVE ")
    }

    /**
     * A DS image with its parts where the header says: ARM9 code at 0x4000,
     * ARM7 at 0x5800, the icon and titles at 0x6000. The rest of the header area
     * and everything after the icon are filler rcheevos must not read. It checks
     * neither the logo nor the header CRC, so neither is here.
     */
    private fun nds() = noise(0x8000, seed = 9).apply {
        put(0x00, "GOLDENTEST\u0000\u0000")
        put(0x0C, "PBGE01")
        putLe32(0x20, 0x4000); putLe32(0x2C, 0x1800)     // ARM9 offset, size
        putLe32(0x30, 0x5800); putLe32(0x3C, 0x0800)     // ARM7 offset, size
        putLe32(0x68, 0x6000)                            // icon and titles
    }

    /** rc_hash_nintendo_ds: 0x160 bytes of header, the ARM9 and ARM7 code, 0xA00 bytes of icon. */
    private fun ndsHashed(image: ByteArray): ByteArray {
        fun le32(at: Int) = (0..3).sumOf { (image[at + it].toInt() and 0xFF) shl (8 * it) }
        fun part(offset: Int, size: Int) = image.copyOfRange(offset, offset + size)
        return part(0, 0x160) + part(le32(0x20), le32(0x2C)) + part(le32(0x30), le32(0x3C)) +
               part(le32(0x68), 0xA00)
    }

    /**
     * A Sega CD data track as a real dump holds it: raw MODE1/2352 sectors, each
     * with its sync pattern, its address after the two-second lead-in, and mode 1.
     * Sector 0 opens with the disc and ROM headers rcheevos identifies it by.
     *
     * Only the first 32 sectors carry filler. Nothing rcheevos reads lies past
     * them, and zeros keep a disc-sized track quick to build and to zip.
     */
    private fun segaCdTrack(sectors: Int = 32): ByteArray {
        fun bcd(n: Int) = ((n / 10) shl 4 or (n % 10)).toByte()
        val track = ByteArray(sectors * 2352)
        for (s in 0 until sectors) {
            val at = s * 2352
            track.fill(0xFF.toByte(), at + 1, at + 11)
            val lba = s + 150
            track.put(at + 12, byteArrayOf(bcd(lba / 4500), bcd(lba / 75 % 60), bcd(lba % 75), 1))
            if (s < 32) noise(2048, seed = 300 + s).copyInto(track, at + 16)
        }
        track.put(16, "SEGADISCSYSTEM  GOLDENTEST ")
        track.put(16 + 0x100, "SEGA MEGA DRIVE ")
        return track
    }

    private fun cueFor(name: String) =
        ("FILE \"$name.bin\" BINARY\r\n" +
         "  TRACK 01 MODE1/2352\r\n" +
         "    INDEX 01 00:00:00\r\n").toByteArray()

    // ---------------------------------------------------------------- helpers

    private fun readme(rom: Rom) = "${rom.stem}\r\nSynthetic test data, not a game.\r\n".toByteArray()

    private fun zip(file: File, vararg entries: Pair<String, ByteArray>): File = file.also { f ->
        f.parentFile.mkdirs()
        ZipOutputStream(f.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    /** LZMA2, SevenZOutputFile's default and what most 7z files use. */
    private fun sevenZ(file: File, vararg entries: Pair<String, ByteArray>): File = file.also { f ->
        f.parentFile.mkdirs()
        SevenZOutputFile(f).use { out ->
            for ((name, bytes) in entries) {
                out.putArchiveEntry(SevenZArchiveEntry().apply { this.name = name })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
    }

    private companion object {
        /** md5("mslug"): the RetroAchievements hash of the Metal Slug romset. */
        const val MSLUG = "b43c8b4ec999588c04dad79bb8bcc745"

        /** md5("dolphin"): the hash of the Atomiswave set dolphin.zip. */
        const val DOLPHIN = "36cdf8b887a5cffc78dcd5c08991b993"

        /** The Sega CD disc built by segaCdTrack, as a standalone rcheevos hashed it. */
        const val SEGA_CD = "a359a33f916e15c4efd7fc52d8e1d998"

        const val README = "readme.txt"

        /**
         * Where Gradle's test task says the library is: the committed one, unless
         * it was started with -Ppegasus.nativeLibrary; see hasher/build.gradle.kts.
         */
        const val LIBRARY_PROPERTY = "pegasus.bridge.nativeLibrary"

        /**
         * The librahasher in native/out, and no other: the one installDist ships
         * and the daemon loads. A library search could find an older build first
         * and still pass, which is exactly what this must not do. CI runs these a
         * second time on a library it has just built, which it names; that one
         * is loaded by its path too.
         */
        val native: NativeRomHasher by lazy {
            val path = System.getProperty(LIBRARY_PROPERTY)
                ?: fail("$LIBRARY_PROPERTY is not set: run this through Gradle, whose test task " +
                        "points it at native/out")
            val library = File(path)
            if (!library.isFile)
                fail("no native library at $library: build it with native/build.sh")
            NativeRomHasher.resetForTests()
            NativeRomHasher.tryLoad(library)
                ?: fail("could not load $library: ${NativeRomHasher.lastError()}. The committed " +
                        "librahasher.so needs glibc 2.14 or newer; on an older system rebuild it " +
                        "with native/build.sh")
        }

        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * Deterministic filler from a xorshift stream: no two seeds give the same
         * bytes, and no run of it looks like a header by accident.
         */
        fun noise(size: Int, seed: Int): ByteArray {
            var x = seed * 0x9E3779B1.toInt() xor 0x2545F491
            return ByteArray(size) {
                x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
                (x ushr 24).toByte()
            }
        }

        /** 16-bit byte swap, the difference between a .v64 and a .z64. */
        fun swap16(bytes: ByteArray) = ByteArray(bytes.size) { bytes[it xor 1] }

        fun ByteArray.put(at: Int, bytes: ByteArray) { bytes.copyInto(this, at) }
        fun ByteArray.put(at: Int, text: String) { put(at, text.toByteArray(Charsets.US_ASCII)) }
        fun ByteArray.putLe32(at: Int, value: Int) {
            for (i in 0..3) this[at + i] = (value ushr (8 * i)).toByte()
        }
    }
}
