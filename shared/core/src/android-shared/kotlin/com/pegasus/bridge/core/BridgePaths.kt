package com.pegasus.bridge.core

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the Bridge keeps its data. The Android build passes `/sdcard/PegasusData`;
 * the desktop daemon passes an XDG location. Nothing below this class should ever
 * name a directory itself.
 *
 * Android keeps its `object Paths`, which hardcodes that path and so could not
 * exist on desktop at all, and its services go on naming directories through it.
 * The code both shells compile takes one of these instead; on Android that is
 * `Paths.bridge`, built over the same root.
 */
class BridgePaths(val root: File) {

    val config     = File(root, "config")
    val metadata   = File(root, "metadata")
    val media      = File(root, "media")
    val search     = File(root, "search")
    val searchRa   = File(root, "search-ra")
    val scrape     = File(root, "scrape")
    val download   = File(root, "download")
    val pending    = File(root, "pending")
    val done       = File(root, "done")
    val profile    = File(root, "profile")
    val completion = File(root, "completion")
    /** Answers worth keeping but safe to lose — nothing here is user data. */
    val cache      = File(root, "cache")
    /**
     * Pictures the Bridge fetched on the theme's behalf.
     *
     * Separate from [media], which holds *descriptions* of media as JSON. These are the
     * bytes, and they exist because some sources authenticate their media URLs: a
     * ScreenScraper picture URL carries the developer password in its query string, so
     * the URL can never cross into the theme and a file path goes instead.
     */
    val artwork    = File(root, "artwork")

    /**
     * Pictures the Bridge moved out of the way, never ones it deleted.
     *
     * Replacing a cover in the user's library means setting theirs aside, not
     * destroying it. An image costs a few hundred kilobytes; a box scan somebody
     * made themselves does not come back. So "replace" is a move, this is where
     * it moves to, and [com.pegasus.bridge.pegasus.ExportManifest] remembers
     * which original belongs to which replacement so a revert can undo the pair.
     */
    val replaced   = File(root, "replaced")

    val credentials = File(config, "credentials.json")

    fun metadata(gameId: String)   = File(metadata,   "$gameId.json")
    fun media(gameId: String)      = File(media,      "$gameId.json")
    fun search(jobId: String)      = File(search,     "$jobId.json")
    fun searchRa(jobId: String)    = File(searchRa,   "$jobId.json")
    fun scrape(jobId: String)      = File(scrape,     "$jobId.json")
    fun download(jobId: String)    = File(download,   "$jobId.json")
    fun pending(jobId: String)     = File(pending,    "$jobId.json")
    fun done(jobId: String)        = File(done,       "$jobId.done")
    fun profile(user: String)      = File(profile,    "$user.json")
    fun completion(user: String)   = File(completion, "$user.json")
    fun cache(name: String)        = File(cache,      name)
    fun artwork(name: String)      = File(artwork,    name)

    /** The discovery index the hasher builds: games[] plus a byKey{} reverse map. */
    val discoveryIndex = File(metadata, "_index.json")

    fun ensureAll() {
        listOf(config, metadata, media, search, searchRa, scrape, download,
               pending, done, profile, completion, cache, artwork, replaced).forEach { it.mkdirs() }
    }

    /**
     * Marks a job finished.
     *
     * The marker carries content on purpose. Qt's QML `XMLHttpRequest` cannot
     * distinguish a missing file from an empty one over `file://` — both report
     * status 0 with an empty body, and only a non-empty file reports 200. The
     * original implementation created the marker with `createNewFile()`, so the
     * theme's completion check could never see it and every job looked finished
     * immediately. Writing a byte or two makes the check work.
     */
    fun markDone(jobId: String) {
        done.mkdirs()
        writeAtomic(done(jobId), """{"jobId":"$jobId","finishedAt":${epochSeconds()}}""")
    }

    companion object {
        fun epochSeconds(): Long = System.currentTimeMillis() / 1000L

        /**
         * Write via temp + rename so a reader never sees a half-written file,
         * and a process killed in the middle leaves the old file or the new
         * one, whole.
         *
         * By a move that is asked to be atomic and to replace. `File.renameTo`
         * alone, which this was, does not replace an existing file on
         * Windows: every write but the first fell through to rewriting the
         * target in place, where a kill leaves half a file. That mattered
         * less while a file was written once as a job ended; the scan's
         * ledger is now rewritten while the scan runs.
         *
         * What follows a move that fails is what there was before, for a
         * filesystem that cannot do one: the plain rename, then the rewrite
         * in place.
         *
         * [durable] is for a file that has to be there after a power cut as
         * well. The move guards against a kill, where the system goes on and
         * writes out what the process left it. It does not guard against a
         * cut: nothing has put the temporary file's bytes on disk when the
         * name is moved onto it, and a filesystem may record the move first,
         * so the name can come back holding an empty or a short file in place
         * of the whole one it replaced. A durable write forces the bytes to
         * disk before the move, and after it the directory, which is where
         * the move itself is recorded. A file that cannot be forced is not
         * moved, and the write fails with the old file in place. A directory
         * that cannot is let go: Windows has no way to do it, and Android's
         * shared storage may refuse.
         *
         * It is asked for by the scan's ledger and by nothing else, and is
         * not the default on purpose. A force waits for the storage, each
         * time, and this function also writes a metadata file for every
         * match and, in the daemon, the record of a running job for every
         * report it is given. Forced, those would wear a tablet's flash and
         * slow a scan, to keep a file the next scan puts back and one nobody
         * needs after a cut.
         *
         * [ownerOnly] is for a file that holds a secret, and only the
         * desktop's credentials ask for it. Its folder is made, or narrowed
         * to, one only its owner can open, and the temporary file is made
         * with no permission for anyone else before a byte is written to it:
         * made so, not changed to it after, which would leave the secret open
         * to every account on the machine for as long as the write took. The
         * move keeps what the file was made with. A temporary file that a
         * write cut off left behind is removed first rather than written
         * over, since what it was made with is not known. Where the file
         * system has no POSIX permissions, as on Windows, the write is the
         * one any other is.
         */
        fun writeAtomic(target: File, content: String, durable: Boolean = false, ownerOnly: Boolean = false) {
            if (ownerOnly) target.absoluteFile.parentFile?.let(::ownerOnlyDir) else target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            if (ownerOnly) tmp.delete()
            write(tmp, content, durable, ownerOnly)
            try {
                Files.move(tmp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                if (!tmp.renameTo(target)) {
                    write(target, content, durable, ownerOnly)
                    tmp.delete()
                }
            }
            if (durable) target.absoluteFile.parentFile?.let { dir -> runCatching { force.directory(dir) } }
        }

        // The text as writeText writes it, and forced before the file is
        // closed when the write is a durable one. An owner-only file is made
        // so before it is opened.
        private fun write(file: File, content: String, durable: Boolean, ownerOnly: Boolean) {
            if (!durable && !ownerOnly) return file.writeText(content)
            if (ownerOnly) makeOwnerOnly(file)
            FileOutputStream(file).use { out ->
                if (ownerOnly) watch?.invoke(file)
                out.write(content.toByteArray())
                if (ownerOnly) watch?.invoke(file)
                if (durable) force.file(file, out.fd)
            }
        }

        private fun makeOwnerOnly(file: File) {
            val path = file.toPath()
            if (!posix(path)) return
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_FILE))
            } catch (_: FileAlreadyExistsException) {
                // The target, written in place where the move could not
                // replace it, or a file made in the meantime: narrowed before
                // a byte goes into it. A folder is left to the write to refuse.
                if (file.isFile) keepToOwner(file)
            }
        }

        /**
         * Makes [dir] a folder only its owner can open: made so when it is
         * not there, its parents as `mkdirs` makes them, and narrowed when it
         * is there and open wider. Where the file system has no POSIX
         * permissions it is only made.
         */
        fun ownerOnlyDir(dir: File) {
            val path = dir.toPath()
            if (!posix(path)) { dir.mkdirs(); return }
            if (!dir.isDirectory) {
                dir.parentFile?.mkdirs()
                try {
                    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(OWNER_DIR))
                    return
                } catch (_: FileAlreadyExistsException) {
                    // Made in the meantime by something else: narrowed below.
                }
            }
            keepToOwner(dir)
        }

        /**
         * Takes away whatever [file], a file or a folder, lets anybody but its
         * owner do, and says so in the log, with its name and nothing of what
         * it holds. Its content is neither read nor written, and the owner's
         * own permissions stay as they are. True when there was something to
         * take away and it was taken.
         *
         * Nothing where there is no such file, nor where the file system has
         * no POSIX permissions. Where they cannot be changed (a file somebody
         * else owns, a mount that sets its own) a warning, and nothing fails.
         */
        fun keepToOwner(file: File): Boolean {
            val path = file.toPath()
            if (!file.exists() || !posix(path)) return false
            return try {
                val was = Files.getPosixFilePermissions(path)
                val now = was - OTHERS
                if (now == was) return false
                Files.setPosixFilePermissions(path, now)
                BridgeLog.i(TAG, "$file was open to others (${PosixFilePermissions.toString(was)}):" +
                                 " now only its owner's (${PosixFilePermissions.toString(now)})")
                true
            } catch (e: Exception) {
                BridgeLog.w(TAG, "could not keep $file to its owner: ${e.javaClass.simpleName}")
                false
            }
        }

        /**
         * Whether the file system a [Path] is on keeps POSIX permissions.
         * Windows does not, and there an owner-only write is a plain one. A
         * var for the reason [force] is one: no machine a test runs on lacks
         * them, and what is done where they are missing has to be seen all
         * the same.
         */
        @Volatile var posix: (Path) -> Boolean = { "posix" in it.fileSystem.supportedFileAttributeViews() }

        /**
         * Shown each file an owner-only write writes, once it is open and
         * before a byte goes into it, and again once all of it is in: the
         * moments its permissions matter, which nothing else shows a test.
         * Null outside one.
         */
        @Volatile var watch: ((File) -> Unit)? = null

        private const val TAG = "BridgePaths"
        private val OWNER_FILE = PosixFilePermissions.fromString("rw-------")
        private val OWNER_DIR  = PosixFilePermissions.fromString("rwx------")
        private val OTHERS = setOf(
            PosixFilePermission.GROUP_READ,  PosixFilePermission.GROUP_WRITE,  PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)

        /**
         * What a durable [writeAtomic] asks of the system. A var as
         * [BridgeLog.current] is, and for the same reason: nothing else
         * shows a test that a file was forced, or when, and a test can put
         * in one that refuses.
         */
        @Volatile var force: Force = Force()
    }

    open class Force {
        /** [file] is open for writing as [fd], with all of its text written. */
        open fun file(file: File, fd: FileDescriptor) = fd.sync()

        /** Throws where a directory cannot be opened or forced. */
        open fun directory(dir: File) {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
