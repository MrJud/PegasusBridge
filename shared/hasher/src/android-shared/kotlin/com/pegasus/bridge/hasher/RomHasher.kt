package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.RcConsoles
import java.io.File

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
     * disc descriptor inside an archive, whose tracks are not extracted. Asking
     * again on every scan costs work and changes nothing, so the pipeline keeps
     * that answer like a verdict instead of retrying it as a file that might be
     * fixed.
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
 */
class ArchiveAwareHasher(
    private val delegate: RomHasher,
    private val tempDir: File
) : RomHasher {

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
            // collection could say. What it answers is kept as it is, a
            // failure as one to try again.
            ConsoleChoice.Plan.Guess -> named(file.name, delegate.hashForConsole(file.absolutePath, 0), digests)
        }
    }

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
     * The one entry that is the game. A descriptor is asked for first, and
     * only an entry that is none is planned for by its own name. The other
     * way round a `.ccd` or a `.toc`, which are descriptors and are also
     * formats rcheevos does not read, would be turned away as a format, and
     * a playlist would be followed to files that were not taken out with it.
     */
    private fun chosen(
        archive: File,
        opened: ArchiveReader.Opened.Entries,
        entry: ArchiveSelector.Entry,
        row: RcConsoles.Row?
    ): HashOutcome {
        if (entry.extension in ArchiveSelector.DESCRIPTOR_EXTENSIONS) return descriptorAlone(archive, entry)
        return when (val plan = ConsoleChoice.choose(row, entry.extension, entry.size, insideArchive = true)) {
            // A packed disc image, say, zipped once more: copied out and
            // handed over it would come back as the hash of its container.
            is ConsoleChoice.Plan.UnsupportedFormat ->
                HashOutcome.UnsupportedFormat("'${entry.name}' in the archive: ${plan.reason}")
            is ConsoleChoice.Plan.Unsupported ->
                HashOutcome.UnsupportedFormat(plan.reason)
            is ConsoleChoice.Plan.Hash -> extracted(archive, opened, entry, plan)
            ConsoleChoice.Plan.Guess -> extracted(archive, opened, entry, null)
            // No entry gets one of these today: an archive or an arcade set
            // inside an archive is refused as a format, and a playlist is a
            // descriptor. Said in full so that a plan added to the list does
            // not compile until it has been given an answer here.
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
     * A disc descriptor ([ArchiveSelector.DESCRIPTOR_EXTENSIONS]) chosen out of
     * an archive, which cannot be hashed yet and is not tried.
     *
     * A descriptor names its tracks and rcheevos reads them from beside it, but
     * only the descriptor would be extracted: every console rcheevos tries for it
     * fails, and the answer came back as a failure the next scan retried. In a
     * solid 7z the descriptor usually comes after its tracks, so each of those
     * attempts decompressed the disc ahead of it first — some 25 s for one PSX
     * disc, on every scan. The outcome is known from the listing alone, so
     * nothing is extracted and rcheevos is not called. The fix proper, Phase 2,
     * is extracting the tracks too.
     */
    private fun descriptorAlone(archive: File, entry: ArchiveSelector.Entry): HashOutcome {
        BridgeLog.w(TAG, "not hashed: '${entry.name}' in ${archive.name} is a disc descriptor, " +
                         "and its tracks are not extracted from an archive yet")
        return HashOutcome.Failed(DESCRIPTOR_IN_ARCHIVE, retryable = false)
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
            val rom = File.createTempFile("bridge_", RomHashIO.tempSuffix(entry.name), tempDir)
            copy = rom
            val digests = try {
                opened.read(entry) { input -> rom.outputStream().use { RomHashIO.copyAndDigest(input, it) } }
            } catch (t: Throwable) {
                RomHashIO.rethrowIfCancelled(t)
                BridgeLog.w(TAG, "could not extract '${entry.name}' from ${archive.name}: ${t.message}")
                // With the cause: "could not extract" alone says nothing about a zip
                // that holds two entries of one name and is otherwise fine.
                return HashOutcome.Failed("could not extract '${entry.name}': ${t.message ?: t.javaClass.simpleName}")
            }
            val outcome = if (plan != null) asConsoles(rom.absolutePath, plan)
                          else delegate.hashForConsole(rom.absolutePath, 0)
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

        /** The reason a disc descriptor chosen out of an archive is not hashed. */
        const val DESCRIPTOR_IN_ARCHIVE = "disc descriptor inside an archive: its tracks are not extracted yet"
    }
}
