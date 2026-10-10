package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.RcConsoles
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * [hash] is the RetroAchievements one, from rcheevos, which transforms the data
 * per console before hashing — NES skips the iNES header, SNES a copier header.
 * [fileMd5] and [fileCrc32] describe the file itself, which is what ROM databases
 * match on. The two coincide on consoles rcheevos hashes whole, and differ on
 * NES and SNES, so they are not interchangeable.
 */
data class HashResult(
    val hash: String,
    val consoleId: Int,
    val fileMd5: String = "",
    val fileCrc32: String = "",
    /** Which entry inside an archive these describe, or empty for a plain file. */
    val archiveEntry: String = ""
)

/**
 * What happened when a file was hashed — not just what came out.
 *
 * `null` used to carry every kind of failure at once: an unreadable file, a
 * missing native library, and an archive holding three plausible ROMs all
 * arrived at the pipeline as "no hash", were recorded identically, and were
 * therefore indistinguishable from a game RetroAchievements does not have. The
 * scan could not explain itself because it had not been told anything.
 */
sealed interface HashOutcome {
    data class Ok(val result: HashResult) : HashOutcome

    /**
     * Several entries could each be the ROM.
     *
     * Not a failure of the file and not a miss: the archive needs a person to
     * look at it. Hashing an arbitrary one instead is what the old rule did, and
     * it produced a wrong answer that looked exactly like a right one.
     */
    data class AmbiguousArchive(val candidates: List<String>) : HashOutcome

    /**
     * The file could not be read, or the hasher could not process it.
     *
     * [retryable] false means the hasher knew before trying that it cannot hash
     * this file, and will answer the same until the hasher itself changes — a
     * disc inside an archive whose sheet is a `.ccd`, which rcheevos does not
     * read, or names a track the archive does not hold. Asking again on every
     * scan costs work and changes nothing, so the pipeline keeps that answer
     * like a verdict instead of retrying it as a file that might be fixed.
     *
     * It is false as well for a file that was read and refused by the console
     * its collection names, which will refuse it again, and for an archive
     * that does not open as one. A file that could not be opened at all is
     * never one of these: nothing has been seen of it.
     */
    data class Failed(val reason: String, val retryable: Boolean = true) : HashOutcome

    /**
     * The archive was opened and listed, and nothing in it is a game of the
     * collection: a patch and its notes, the artwork of a set, the files of
     * an emulator.
     *
     * Such an archive used to be hashed as it lay, and what rcheevos makes
     * of a zip it is told nothing about is the MD5 of its name. That is a
     * hash like any other to look at. It was asked about, and the answer,
     * no, was kept as a game the database lacks.
     */
    data class NoPlayableEntry(val reason: String) : HashOutcome

    /**
     * The file is one nobody hashes, for the collection it is in: known
     * from its name, or from the name of the one entry an archive holds for
     * the collection, a packed disc image inside a zip. Nothing was handed
     * to rcheevos, and nothing will be until the lists it was decided from
     * change.
     */
    data class UnsupportedFormat(val reason: String) : HashOutcome
}

/**
 * Computes a RetroAchievements-compatible hash for one ROM file.
 *
 * The implementation is native (rcheevos), and how it is reached differs per
 * platform: on Android the `.so` ships inside the APK and is loaded by the
 * classloader, on desktop it is a system library or a helper binary. Hence the
 * interface — the pipeline must not care.
 *
 * The native code itself is identical everywhere: the same ROM produces the same
 * hash from the x86_64 and the arm64 builds.
 */
interface RomHasher {
    fun hash(path: String): HashResult?

    /**
     * The file hashed as one console, [consoleId] as rcheevos numbers them,
     * or as whatever its extension suggests when that is 0; and when there is
     * no hash, why.
     *
     * Defaulted to [hash], with nothing to say of a failure, and the default
     * leaves the console out on purpose. A hasher that stands in for the
     * native one in a test answers one console for every file, and asked for
     * another it would have to refuse files the test is not about.
     */
    fun hashForConsole(path: String, consoleId: Int): HashOutcome =
        hash(path)?.let { HashOutcome.Ok(it) } ?: HashOutcome.Failed("")

    /**
     * Which hasher this is, down to the build: for the native one the
     * rcheevos release and the local patches on it, as the library itself
     * reports them. Two engines may answer differently for one file, so what
     * one of them said is not to be taken for the other's.
     */
    val engine: String get() = "none"

    /**
     * With the collection's short name, so an archive can be resolved by what
     * the platform actually runs rather than by which entry is biggest.
     *
     * Defaulted, so a hasher with no archive handling — every implementation of
     * the native layer — needs to know nothing about platforms.
     */
    fun hash(path: String, platform: String): HashResult? = hash(path)

    /**
     * The same, but able to say *why* there is no hash.
     *
     * Defaulted to the lossy answer so an implementation that cannot distinguish
     * the cases does not have to pretend it can.
     */
    fun hashDetailed(path: String, platform: String): HashOutcome =
        hash(path, platform)?.let { HashOutcome.Ok(it) }
            ?: HashOutcome.Failed("the hasher returned nothing")

    /**
     * The same, from a scan, which knows the file's collection and not only
     * what it is called: the folder it is kept in and what it declares too.
     *
     * Defaulted to one name alone, which is all a hasher has needed so far:
     * the short name, or the folder's where that says more of what the
     * folder holds ([CollectionRef.hasherPlatform]). One placed between the
     * scan and another hasher has to hand the collection on as it came, or
     * the one inside is told less than the scan knew.
     */
    fun hashDetailed(path: String, collection: CollectionRef): HashOutcome =
        hashDetailed(path, collection.hasherPlatform)
}

/**
 * Wraps a [RomHasher] with what a file's collection says of it: an arcade
 * set is hashed by its name and left shut, and any other zip or 7z is opened
 * for the one entry that is the game, which is copied out first, because the
 * native hasher works on plain files.
 *
 * What is done with a file is [ConsoleChoice]'s decision, from the row of
 * its collection and its name, and where the collection is known that
 * includes the console: rcheevos is told which one, and is not left to take
 * it from the extension. Which entry of an archive is copied out is
 * [ArchiveSelector]'s, by what the platform can run, then by the entry named
 * after the archive. The rule that one replaced, "the largest entry", is
 * right for one ROM plus a readme and silently wrong for a bonus disc, an
 * included patch or a `.cue`/`.bin` pair, where the biggest file is the data
 * track and the descriptor is what has to be hashed.
 *
 * An archive is never hashed as the file it is. It was, whenever it could
 * not be opened or held nothing for its platform, on the reasoning that this
 * could cost a miss and never a wrong match. But rcheevos takes a zip or a
 * 7z it is told nothing about for an arcade set and hashes its name, so the
 * answer was the MD5 of a file name, with console 27: asked about, refused,
 * and kept for a fortnight as a game the database lacks.
 *
 * A disc in an archive is a sheet and the tracks it names, and rcheevos
 * opens the tracks from beside the sheet. So where the entry chosen is a
 * `.cue` or a `.gdi`, the tracks are found among the entries
 * ([DescriptorSet]) and taken out with it, into a folder made for the one
 * disc and removed when it has been hashed. A playlist in an archive leads
 * to the entry it names first, and to that entry's disc.
 *
 * [tempDir] is where copies are made, and it is not this class's alone: on
 * Android it is the app's whole cache. Everything made in it has a name that
 * begins `bridge_`, and nothing of another name is touched.
 */
class ArchiveAwareHasher internal constructor(
    private val delegate: RomHasher,
    private val tempDir: File,
    /** How many bytes can still be written under a folder. A test says a number of its own. */
    private val usableSpace: (File) -> Long
) : RomHasher {

    constructor(delegate: RomHasher, tempDir: File) : this(delegate, tempDir, { it.usableSpace })

    // A copy is removed when its file has been hashed, in a `finally`. A
    // process that is killed runs none: the daemon stopped in the middle of
    // a scan, the app ended by the system. What it was copying stays, and a
    // disc is hundreds of megabytes. Both shells build one of these for each
    // scan, so this is where a scan starts, as far as the copies go. A day
    // old, because another scan may be running beside this one with a copy
    // of its own in the making, and none takes a day over one file.
    init {
        try {
            val stale = System.currentTimeMillis() - STALE_AFTER_MS
            tempDir.listFiles()
                ?.filter { it.name.startsWith(COPY_PREFIX) && it.lastModified() < stale }
                ?.forEach {
                    BridgeLog.i(TAG, "removing ${it.name}, left behind by an earlier scan")
                    remove(it)
                }
        } catch (e: Exception) {
            BridgeLog.w(TAG, "could not look for copies an earlier scan left behind: ${e.message}")
        }
    }

    override fun hash(path: String): HashResult? = hash(path, "")

    override fun hash(path: String, platform: String): HashResult? =
        (hashDetailed(path, platform) as? HashOutcome.Ok)?.result

    /** The delegate's: taking an archive apart changes nothing of what hashes its entry. */
    override val engine: String get() = delegate.engine

    /**
     * For a caller with a name and no collection: the name is taken for the
     * collection's, kept in a folder called the same, with nothing declared.
     */
    override fun hashDetailed(path: String, platform: String): HashOutcome =
        hashDetailed(path, CollectionRef.inferred(platform))

    /**
     * [path] is used as it came, and that matters for one kind of file. An
     * arcade set's hash is made of its name, and for some folders of the
     * folder's name too, and rcheevos reads both off the path it is handed:
     * a path followed through a link to where the file really lies could
     * name another folder, and give another hash.
     */
    override fun hashDetailed(path: String, collection: CollectionRef): HashOutcome {
        val file = File(path)
        if (!file.isFile) return HashOutcome.Failed("no such file")

        val row = RcConsoles.resolve(collection.shortName, collection.dirName)
        return planned(path, file, collection, row, namedByPlaylist = false)
    }

    /**
     * [file] as its plan says, in the collection whose row is [row].
     * [namedByPlaylist] is true for the first entry of a playlist, which is
     * planned for as any other file of the collection is.
     */
    private fun planned(
        path: String,
        file: File,
        collection: CollectionRef,
        row: RcConsoles.Row?,
        namedByPlaylist: Boolean
    ): HashOutcome {
        // The MD5 and CRC of the file the delegate is given, so that they
        // describe the ROM. Not for what a playlist names: the digests kept
        // are the playlist's, and the first disc of a game would be read to
        // its end for two numbers nobody keeps.
        val digests: (HashResult) -> HashResult =
            if (namedByPlaylist) { result -> result } else { result -> withPlainHashes(result, file) }

        return when (val plan = ConsoleChoice.choose(row, file.extension, file.length(), insideArchive = false)) {
            // A scan has turned both away before it gets here, without a
            // look at the file. These are for a caller that has not, and
            // for what a playlist names.
            is ConsoleChoice.Plan.Unsupported -> HashOutcome.UnsupportedFormat(plan.reason)
            is ConsoleChoice.Plan.UnsupportedFormat -> HashOutcome.UnsupportedFormat(plan.reason)
            ConsoleChoice.Plan.ArcadeSet -> arcadeSet(path, file)
            ConsoleChoice.Plan.OpenArchive -> archive(file, collection, row)
            ConsoleChoice.Plan.ResolvePlaylist ->
                // The reader refuses a playlist that names a playlist, so
                // this is one it let through, and it is not followed.
                if (namedByPlaylist)
                    HashOutcome.Failed("a playlist named by a playlist is not followed", retryable = false)
                else playlist(file, collection, row)
            is ConsoleChoice.Plan.Hash -> named(file.name, asConsoles(file.absolutePath, plan), digests)
            // Nothing is known of the collection: rcheevos takes the console
            // from the extension, as it did for every file before a
            // collection could say. What it answers is kept, a failure as
            // one to try again, unless the console it settled on is one
            // that is held back (guessed, below).
            ConsoleChoice.Plan.Guess -> named(file.name, guessed(file.absolutePath), digests)
        }
    }

    /**
     * [path] left to rcheevos, which takes the console from the extension.
     * Its answer is kept unless the console it settled on is one that is
     * held back ([ConsoleChoice.guessed]).
     */
    private fun guessed(path: String): HashOutcome =
        ConsoleChoice.guessed(delegate.hashForConsole(path, 0))

    /**
     * A playlist is hashed as the first file it names, read here and not by
     * rcheevos, which follows a playlist for some consoles and hashes its
     * text for the others, and for a console it is told follows one only
     * where that console has playlists. The entry is planned for with the
     * playlist's collection: a `.cue` under `megadrive` is a Sega CD disc
     * whether the scan met it or a playlist named it.
     *
     * The digests beside the hash stay the playlist's: it is the file the
     * scan has, and the one its size and date are kept for.
     *
     * A playlist that names a file which is not there may be whole again
     * tomorrow, a disc still being copied. One that lists nothing, or
     * another playlist, will say the same until it is edited, and that
     * shows in its size and date.
     */
    private fun playlist(file: File, collection: CollectionRef, row: RcConsoles.Row?): HashOutcome =
        when (val first = PlaylistReader.firstEntry(file)) {
            is PlaylistReader.Result.Refused -> HashOutcome.Failed(
                first.reason,
                retryable = first.why == PlaylistReader.Why.MISSING || first.why == PlaylistReader.Why.UNREADABLE)
            is PlaylistReader.Result.Entry ->
                when (val outcome = planned(first.file.path, first.file, collection, row, namedByPlaylist = true)) {
                    is HashOutcome.Ok -> HashOutcome.Ok(withPlainHashes(outcome.result, file))
                    // Decided from the entry's name, which the reason has not.
                    is HashOutcome.UnsupportedFormat ->
                        HashOutcome.UnsupportedFormat("${first.file.name}, the first file the playlist names: " +
                                                      outcome.reason)
                    else -> outcome
                }
        }

    /**
     * [path] hashed as the console of [plan], and when that console refuses
     * the file, as each of the plan's alternates in turn. rcheevos is never
     * left to guess for a collection that is known: its guess is the
     * extension's console, and for an extension it has never heard of the
     * Game Boy, and either way a hash comes out.
     *
     * A file that could not be opened is not tried as another console, and
     * may be tried again at the next scan ([RcheevosNative.isTransient]),
     * whichever of the consoles it was that could not open it. Anything
     * else the console said of it, it will say again, and the failure is
     * one to keep.
     *
     * With one more look before it is kept. The disc consoles have no words
     * of their own for a file they could not open: "Could not open track"
     * is what they say of it, and of an image whose size fits no kind of
     * sector too. So a file every console has refused is opened here, once,
     * and one that will not open is a failure to try again.
     */
    private fun asConsoles(path: String, plan: ConsoleChoice.Plan.Hash): HashOutcome {
        var reason = ""
        for (console in listOf(plan.console) + plan.alternates) {
            val failure = when (val outcome = delegate.hashForConsole(path, console)) {
                is HashOutcome.Failed -> outcome
                else -> return outcome
            }
            if (failure.retryable && RcheevosNative.isTransient(failure.reason)) return failure
            reason = if (console == plan.console) failure.reason else "$reason; as console $console: ${failure.reason}"
        }
        return HashOutcome.Failed(reason, retryable = !opens(File(path)))
    }

    /**
     * What the delegate answered, as the scan is to have it: a hash with
     * [digests] added, a failure under the name the scan knows the file by.
     */
    private fun named(what: String, outcome: HashOutcome, digests: (HashResult) -> HashResult): HashOutcome =
        when (outcome) {
            is HashOutcome.Ok -> HashOutcome.Ok(digests(outcome.result))
            is HashOutcome.Failed -> couldNotRead(what, outcome)
            else -> outcome
        }

    /**
     * An arcade game is known to RetroAchievements by the name of its set,
     * and its hash is the MD5 of that name (rc_hash_arcade): console 27
     * reads the path and never the file. Opened like any other archive, a
     * set showed its chips and BIOS images, each of which could have been
     * the ROM, or the one of them with an extension a cartridge has, which
     * was then hashed as a cartridge.
     *
     * The digests beside the hash are the archive's own. Nothing matches a
     * set by them, but a file whose metadata has no MD5 is taken for one
     * scanned before there were any, and read again on every scan.
     */
    private fun arcadeSet(path: String, set: File): HashOutcome =
        named(set.name, delegate.hashForConsole(path, ARCADE)) { withPlainHashes(it, set) }

    private fun archive(file: File, collection: CollectionRef, row: RcConsoles.Row?): HashOutcome =
        ArchiveReader.open(file) { opened ->
            when (opened) {
                // Not the archive its name says it is: a download cut short,
                // or some other file renamed. Neither is hashed as it lies,
                // and neither will open at the next scan unless the file is
                // another by then, which its size or its date will say.
                //
                // Unless it was the file that would not open, and not the
                // archive in it: one the scan is not allowed to read, or
                // one on a card that has just gone. That says nothing of
                // what the file is, and it is tried again.
                is ArchiveReader.Opened.Unreadable -> {
                    BridgeLog.w(TAG, "${file.name} is not a readable archive (${opened.reason})")
                    if (opens(file)) HashOutcome.Failed("not a readable archive: ${opened.reason}", retryable = false)
                    else HashOutcome.Failed("the archive could not be opened: ${opened.reason}")
                }
                is ArchiveReader.Opened.Entries ->
                    when (val pick = ArchiveSelector.select(opened.entries, file.name, collection)) {
                        is ArchiveSelector.Selection.One -> chosen(file, opened, pick.entry, row)
                        is ArchiveSelector.Selection.Ambiguous ->
                            HashOutcome.AmbiguousArchive(pick.candidates.map { it.name })
                        is ArchiveSelector.Selection.NoPlayableEntry ->
                            HashOutcome.NoPlayableEntry(nothingPlayable(pick.entries))
                    }
            }
        }

    /**
     * The one entry that is the game, or with [namedByPlaylist] the entry a
     * playlist in the archive names first. What kind of descriptor it is, is
     * asked first, and only then is it planned for by its own name. The
     * other way round a `.ccd` or a `.toc`, which are sheets and are also
     * formats rcheevos does not read, would be turned away as a format, and
     * a playlist would be a file to hash.
     */
    private fun chosen(
        archive: File,
        opened: ArchiveReader.Opened.Entries,
        entry: ArchiveSelector.Entry,
        row: RcConsoles.Row?,
        namedByPlaylist: Boolean = false
    ): HashOutcome {
        when (entry.extension) {
            // A sheet rcheevos has no reader for. The image beside it may be
            // a disc any console would hash, but which entry that is, the
            // sheet says, in words nobody here reads. It will be no
            // different at the next scan.
            in UNREAD_SHEETS -> {
                BridgeLog.w(TAG, "not hashed: '${entry.name}' in ${archive.name} is a sheet rcheevos does not read")
                return HashOutcome.Failed("'${entry.name}' in the archive is a .${entry.extension} sheet, " +
                                          "which rcheevos does not read", retryable = false)
            }
            "m3u" ->
                return if (namedByPlaylist)
                    HashOutcome.Failed("a playlist named by a playlist is not followed", retryable = false)
                else playlistInside(archive, opened, entry, row)
        }
        val sheet = entry.extension in READ_SHEETS
        return when (val plan = ConsoleChoice.choose(row, entry.extension, entry.size, insideArchive = true)) {
            // A packed disc image, say, zipped once more: copied out and
            // handed over it would come back as the hash of its container.
            // Or a sheet in a collection of cartridges, which is found out
            // here, before its disc is taken out for nothing.
            is ConsoleChoice.Plan.UnsupportedFormat ->
                HashOutcome.UnsupportedFormat("'${entry.name}' in the archive: ${plan.reason}")
            is ConsoleChoice.Plan.Unsupported ->
                HashOutcome.UnsupportedFormat(plan.reason)
            is ConsoleChoice.Plan.Hash ->
                if (sheet) disc(archive, opened, entry, plan) else extracted(archive, opened, entry, plan)
            ConsoleChoice.Plan.Guess ->
                if (sheet) disc(archive, opened, entry, null) else extracted(archive, opened, entry, null)
            // No entry gets one of these: an archive or an arcade set inside
            // an archive is refused as a format, and a playlist has been
            // followed above. Said in full so that a plan added to the list
            // does not compile until it has been given an answer here.
            ConsoleChoice.Plan.ArcadeSet, ConsoleChoice.Plan.OpenArchive, ConsoleChoice.Plan.ResolvePlaylist ->
                HashOutcome.UnsupportedFormat("'${entry.name}' in the archive is not a file to hash")
        }
    }

    /** What an archive with no game in it does hold, for whoever reads the ledger. */
    private fun nothingPlayable(entries: List<ArchiveSelector.Entry>): String {
        val names = entries.filter { !it.isDirectory }.map { it.baseName }
        if (names.isEmpty()) return "the archive holds no file"
        val shown = names.take(NAMES_SHOWN).joinToString(", ")
        val more = names.size - NAMES_SHOWN
        return "nothing in the archive is a game of this collection: $shown" +
               if (more > 0) ", and $more more" else ""
    }

    /**
     * Whether [file] can be opened for reading at all. One that cannot has
     * shown nothing of itself, so whatever was made of it is no verdict on
     * the file: it is a failure to try again, and not one to keep.
     */
    private fun opens(file: File): Boolean = try {
        file.inputStream().close()
        true
    } catch (e: Exception) {
        false
    }

    /**
     * A playlist chosen out of an archive: the game is the first entry it
     * names, which is another entry of the archive and is found among them
     * as a sheet's track is, by the last part of what the playlist writes.
     * A playlist names its discs with a folder as readily as without, and
     * unlike a track's name this one is only looked up: nothing is written
     * under it, so a folder in it leads nowhere. That entry is then what
     * [chosen] makes of it, a disc to take out whole or a single image.
     *
     * As for a playlist on a disk, the digests beside the hash are the
     * playlist's, and so is the entry they are recorded under.
     */
    private fun playlistInside(
        archive: File,
        opened: ArchiveReader.Opened.Entries,
        playlist: ArchiveSelector.Entry,
        row: RcConsoles.Row?
    ): HashOutcome {
        val text = try {
            sheetBytes(opened, playlist)
        } catch (t: Throwable) {
            RomHashIO.rethrowIfCancelled(t)
            return couldNotExtract(archive, playlist, t)
        } ?: return tooLong(playlist)
        val named = DescriptorSet.references(playlist.name, text)
            .map { it.substringAfterLast('/').substringAfterLast('\\') }
        val first = when (val match = DescriptorSet.match(named, opened.entries)) {
            is DescriptorSet.Match.Refused ->
                return HashOutcome.Failed("'${playlist.name}' in the archive: ${match.reason}", retryable = false)
            is DescriptorSet.Match.Found -> match.tracks.single().entry
        }
        return when (val outcome = chosen(archive, opened, first, row, namedByPlaylist = true)) {
            is HashOutcome.Ok -> {
                val digests = RomHashIO.copyAndDigest(text.inputStream())
                HashOutcome.Ok(outcome.result.copy(fileMd5 = digests.md5, fileCrc32 = digests.crc32,
                                                   archiveEntry = playlist.name))
            }
            else -> outcome
        }
    }

    /**
     * A `.cue` or a `.gdi` chosen out of an archive, taken out with the
     * tracks it names and hashed where it then lies.
     *
     * Taken out by itself, as any other entry is, a sheet has no tracks
     * beside it and every console fails on it. For a while it was hashed
     * all the same, as the text it is, and then it was refused unread; a
     * disc somebody had packed was a file nobody could hash either way.
     *
     * What comes out of the archive is decided before anything does. The
     * sheet is read and its tracks found among the entries; a sheet that
     * names a file the archive does not hold, or a file anywhere but beside
     * itself, is refused, and so is one this collection's console would not
     * hash as a disc. That will be so at the next scan too. Then the room
     * for it is looked at, which may be different tomorrow.
     *
     * Each track is written under the name the sheet uses for it, which is
     * the name rcheevos will open, into a folder made for this disc alone:
     * two scans, or two workers of one, never write to the same place, and
     * a name out of an archive cannot land on a file that was there before.
     * The sheet gets a name of ours. Nothing reads that name but the
     * extension, and an entry may be called what no file can be.
     *
     * The folder is removed whichever way this ends, and a cancellation
     * passes straight through.
     */
    private fun disc(
        archive: File,
        opened: ArchiveReader.Opened.Entries,
        sheet: ArchiveSelector.Entry,
        plan: ConsoleChoice.Plan.Hash?
    ): HashOutcome {
        var folder: File? = null
        try {
            val text = try {
                sheetBytes(opened, sheet)
            } catch (t: Throwable) {
                RomHashIO.rethrowIfCancelled(t)
                return couldNotExtract(archive, sheet, t)
            } ?: return tooLong(sheet)

            fun refused(reason: String): HashOutcome {
                BridgeLog.w(TAG, "not hashed: '${sheet.name}' in ${archive.name}: $reason")
                return HashOutcome.Failed("'${sheet.name}' in the archive: $reason", retryable = false)
            }
            val tracks = when (val match = DescriptorSet.match(DescriptorSet.references(sheet.name, text), opened.entries)) {
                is DescriptorSet.Match.Refused -> return refused(match.reason)
                is DescriptorSet.Match.Found -> match.tracks
            }
            val sheetName = "disc.${sheet.extension}"
            if (tracks.any { it.entry === sheet }) return refused("it names itself")
            // On a system that tells no case apart, the track would be written over the sheet.
            tracks.firstOrNull { it.name.equals(sheetName, ignoreCase = true) }
                ?.let { return refused("it names '${it.name}', which is what its own copy is called") }

            tempDir.mkdirs()
            val needed = text.size + tracks.sumOf { it.entry.size }
            val free = usableSpace(tempDir)
            if (free < needed)
                return HashOutcome.Failed("no room to take '${sheet.name}' and its tracks out of the archive: " +
                                          "$needed bytes needed, $free free")

            val made = Files.createTempDirectory(tempDir.toPath(), SET_PREFIX).toFile()
            folder = made
            val sheetFile = File(made, sheetName).apply { writeBytes(text) }
            var taking = tracks[0].entry
            try {
                opened.readMany(tracks.map { it.entry }) { entry, input ->
                    taking = entry
                    val name = tracks.first { it.entry === entry }.name
                    // No more than the listing said: the room was counted by it.
                    File(made, name).outputStream().use { RomHashIO.copy(input, it, limit = entry.size) }
                }
            } catch (t: Throwable) {
                RomHashIO.rethrowIfCancelled(t)
                return couldNotExtract(archive, taking, t)
            }

            val outcome = if (plan != null) asConsoles(sheetFile.absolutePath, plan)
                          else guessed(sheetFile.absolutePath)
            val digests = RomHashIO.copyAndDigest(text.inputStream())
            return named("'${sheet.name}'", settled(outcome, made, listOf(sheetName) + tracks.map { it.name })) {
                it.copy(fileMd5 = digests.md5, fileCrc32 = digests.crc32, archiveEntry = sheet.name)
            }
        } catch (t: Throwable) {
            RomHashIO.rethrowIfCancelled(t)
            BridgeLog.e(TAG, "archive failed: ${archive.name}", t)
            return HashOutcome.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            folder?.let { remove(it) }
        }
    }

    /**
     * What a console said of a disc taken out into [folder], as it is to be
     * kept.
     *
     * A console that cannot open a track says which, by its path, and of a
     * disc lying loose that is a failure to try again: the track may be
     * there tomorrow. Here every file the sheet names was written a moment
     * ago. If the console asked for another all the same, it reads the sheet
     * otherwise than [DescriptorSet] does, and it will again: tried at every
     * scan, that is the whole disc taken out each time to be told the same.
     * Unless a file written has gone since, with a cache that was cleared
     * under the scan, and then nothing has been found out.
     *
     * The folder's own name is taken out of the reason. It is made up anew
     * for every disc, and says nothing to whoever reads the ledger. The
     * library has room for so many bytes of reason and no more, and where
     * several consoles each named a path the last may stop in the middle of
     * the folder's name: that is a path of the folder as well, and goes too.
     */
    private fun settled(outcome: HashOutcome, folder: File, written: List<String>): HashOutcome {
        if (outcome !is HashOutcome.Failed) return outcome
        val inFolder = folder.absolutePath + File.separator
        var reason = outcome.reason.replace(inFolder, "")
        var asked = reason != outcome.reason
        val last = reason.lastIndexOf(COULD_NOT_OPEN)
        // A character cut in two reads as U+FFFD, once or more.
        val cut = if (last < 0) "" else reason.substring(last + COULD_NOT_OPEN.length).trimEnd('\uFFFD')
        if (cut.isNotEmpty() && inFolder.startsWith(cut)) {
            reason = reason.substring(0, last).trimEnd(' ', ';')
            asked = true
        }
        val whole = written.all { File(folder, it).isFile }
        return outcome.copy(reason = reason, retryable = outcome.retryable && !(asked && whole))
    }

    /**
     * The whole of a sheet or a playlist out of an archive, or null when it
     * is longer than any is ([DescriptorSet.SHEET_LIMIT]) and so is something
     * else under that name.
     */
    private fun sheetBytes(opened: ArchiveReader.Opened.Entries, sheet: ArchiveSelector.Entry): ByteArray? {
        if (sheet.size > DescriptorSet.SHEET_LIMIT) return null
        return opened.read(sheet) { input ->
            // Read to the limit and a byte past it, whatever the listing
            // said the size was: it is not taken at its word.
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (bytes.size() <= DescriptorSet.SHEET_LIMIT) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes.write(buffer, 0, count)
            }
            if (bytes.size() > DescriptorSet.SHEET_LIMIT) null else bytes.toByteArray()
        }
    }

    private fun tooLong(sheet: ArchiveSelector.Entry): HashOutcome =
        HashOutcome.Failed("'${sheet.name}' in the archive is too long to be what its name says", retryable = false)

    /**
     * An entry that would not come out of [archive]. With the cause: "could
     * not extract" alone says nothing about a zip that holds two entries of
     * one name and is otherwise fine.
     */
    private fun couldNotExtract(archive: File, entry: ArchiveSelector.Entry, t: Throwable): HashOutcome {
        BridgeLog.w(TAG, "could not extract '${entry.name}' from ${archive.name}: ${t.message}")
        return HashOutcome.Failed("could not extract '${entry.name}': ${t.message ?: t.javaClass.simpleName}")
    }

    /**
     * Copies [entry] out for rcheevos, which only reads plain files, digesting it
     * on the way so the copy is never read back.
     *
     * A cancellation passes straight through, and the copy is deleted
     * whichever way this ends.
     *
     * The copy is hashed as [plan] says, which was made for the entry's own
     * name and size, or left to rcheevos where there is none because nothing
     * is known of the collection.
     */
    private fun extracted(
        archive: File,
        opened: ArchiveReader.Opened.Entries,
        entry: ArchiveSelector.Entry,
        plan: ConsoleChoice.Plan.Hash?
    ): HashOutcome {
        var copy: File? = null
        try {
            tempDir.mkdirs()
            // The entry's own extension when rcheevos has a handler for it: left to
            // itself it picks its algorithm by it, and an iNES ROM named `.bin` gets
            // a whole-file Mega Drive hash. `.bin` for the rest, which tempSuffix
            // explains. Told a console it still reads the name, for what kind of
            // image a disc is.
            val rom = File.createTempFile(COPY_PREFIX, RomHashIO.tempSuffix(entry.name), tempDir)
            copy = rom
            val digests = try {
                opened.read(entry) { input -> rom.outputStream().use { RomHashIO.copyAndDigest(input, it) } }
            } catch (t: Throwable) {
                RomHashIO.rethrowIfCancelled(t)
                return couldNotExtract(archive, entry, t)
            }
            val outcome = if (plan != null) asConsoles(rom.absolutePath, plan)
                          else guessed(rom.absolutePath)
            return named("'${entry.name}'", outcome) {
                it.copy(fileMd5 = digests.md5, fileCrc32 = digests.crc32, archiveEntry = entry.name)
            }
        } catch (t: Throwable) {
            RomHashIO.rethrowIfCancelled(t)
            BridgeLog.e(TAG, "archive failed: ${archive.name}", t)
            return HashOutcome.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            copy?.delete()
        }
    }

    /**
     * The delegate's failure, under the name the scan knows the file by.
     *
     * The delegate was handed a path, and for an entry out of an archive a
     * temporary one, so what it says is added and not passed on alone: the
     * name first, as it always was, then the reason where there is one.
     */
    private fun couldNotRead(what: String, failure: HashOutcome.Failed): HashOutcome.Failed =
        failure.copy(reason = "the hasher could not read $what" +
                              if (failure.reason.isEmpty()) "" else ": ${failure.reason}")

    /**
     * Removes [file], and what is in it when it is a folder. A link is
     * removed and not followed: what it leads to was not made here.
     */
    private fun remove(file: File) {
        if (!Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach { remove(it) }
        file.delete()
    }

    private fun withPlainHashes(result: HashResult, romFile: File): HashResult = try {
        val digests = RomHashIO.digest(romFile)
        result.copy(fileMd5 = digests.md5, fileCrc32 = digests.crc32)
    } catch (t: Throwable) {
        RomHashIO.rethrowIfCancelled(t)
        // Costs a scraper lookup, never the RA match the scan exists for.
        BridgeLog.w(TAG, "plain hash failed: ${romFile.name}: ${t.message}")
        result
    }

    companion object {
        private const val TAG = "ArchiveAwareHasher"

        /** RC_CONSOLE_ARCADE. */
        private const val ARCADE = 27

        /** How many of an archive's entries a reason names before it counts the rest. */
        private const val NAMES_SHOWN = 5

        /** The sheets rcheevos reads, and with which the tracks they name are taken out. */
        private val READ_SHEETS = setOf("cue", "gdi")

        /**
         * The sheets it has no reader for. `mds` is one of them, though the
         * selector does not take it for a descriptor: where it is the entry
         * chosen, it is as little use as the other two.
         */
        private val UNREAD_SHEETS = setOf("ccd", "toc", "mds")

        /** What every copy made in the temporary folder is called by, and so what may be removed from it. */
        private const val COPY_PREFIX = "bridge_"

        /** The folder one disc is taken out into. */
        private const val SET_PREFIX = COPY_PREFIX + "set_"

        /** How rcheevos begins what it says of a track it could not open, the track's path after it. */
        private const val COULD_NOT_OPEN = "Could not open "

        /** How old a copy has to be before it is taken for one that was left behind. */
        private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000
    }
}
