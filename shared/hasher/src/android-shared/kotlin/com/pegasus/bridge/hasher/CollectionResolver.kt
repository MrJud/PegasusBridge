package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.PegasusMetafile
import java.io.File

/**
 * The collection a folder of ROMs belongs to, as far as anything says.
 *
 * [shortName] is what the collection calls its platform: `psx`, `megadrive`.
 * [name] is its full name. [dirName] is the name of the folder that is the
 * collection, which need not be the short name: a folder `sega32x` can
 * declare `megadrive`, and then the folder knows something the short name
 * does not. [directory] is that folder, and [declaredExtensions] what the
 * collection's `extensions:` lines list, in lower case.
 *
 * [source] says where it comes from. DECLARED is a metafile's `collection:`.
 * INFERRED is no metafile at all, and a guess from a folder's name, with no
 * [directory] and no extensions.
 */
data class CollectionRef(
    val shortName: String,
    val name: String,
    val dirName: String,
    val directory: File?,
    val declaredExtensions: Set<String>,
    val source: Source = Source.DECLARED
) {
    enum class Source { DECLARED, INFERRED }

    companion object {
        /** The collection a folder called [name] is taken for when nothing declares one. */
        fun inferred(name: String) = CollectionRef(
            shortName = name, name = name, dirName = name, directory = null,
            declaredExtensions = emptySet(), source = Source.INFERRED)
    }
}

/**
 * Finds the collection a folder is in: the nearest folder, itself or above
 * it, where a Pegasus metafile declares one.
 *
 * A scan took the name of a file's own folder for its platform. That holds
 * for `nes/Game.nes` and for nothing kept a level down: `psx/<game>/x.cue`
 * was platform `<game>`, the files under `switch/Switch Files/Firmware` were
 * platform `Firmware` and so not turned away as Switch files are, and the
 * extensions a collection declares counted for the files directly in its
 * folder and for no others. The first folder under a scan's root does no
 * better, because the theme hands over the folder of every game as a root.
 * What does say which collection a file is in is what says it to Pegasus.
 *
 * One of these for one scan. It remembers every folder it has been asked
 * about and every folder it passed on the way up, so the files of one folder
 * cost one look and the folders of one collection little more. It is not
 * kept longer, because nothing tells it that a metafile has been edited.
 *
 * [metafilesIn] is how a folder's metafiles are listed, for a test to count.
 */
class CollectionResolver(
    private val metafilesIn: (File) -> List<File> = PegasusMetafile::filesIn
) {
    /** What one folder's metafiles declare, read once. */
    private class Declaration(val directory: File, val blocks: List<PegasusMetafile.Block>)

    /**
     * What is known of a folder: the declaration it is under, which the
     * folders below it are under too, and its answer once it has been asked
     * for. A folder only passed on the way up has none yet. Worked out there
     * and then, the answer brought its warning with it, about a folder nobody
     * had asked about: a metafile that sends each of its collections to a
     * folder of its own was told that it lists its own folder under none.
     */
    private class Resolved(val from: Declaration?) { var ref: CollectionRef? = null }

    private val known = HashMap<String, Resolved>()
    private val reported = HashSet<String>()

    /**
     * The collection [dir] is in.
     *
     * The path is followed upward as it is written, and never the canonical
     * one: a collection reached through a link is the collection the link is
     * named for, in the library the link is in, and what the link points at
     * may be a folder of another name in a place that declares nothing.
     *
     * Where no folder up to the root declares anything, the answer is
     * [CollectionRef.inferred] from the name of [dir] itself, which is what a
     * scan has always taken.
     *
     * Synchronized, because the workers of a scan ask at once, and two that
     * ask about the same new folder should read its metafiles once.
     */
    @Synchronized
    fun collectionOf(dir: File): CollectionRef {
        val start = dir.absoluteFile.normalize()
        val passed = ArrayList<File>()
        var declaration: Declaration? = null
        var at: File? = start
        while (at != null) {
            val seen = known[at.path]
            // Asked about before, or passed before. Either it is in a
            // collection, and so is everything between here and it, or
            // nothing above it declares one.
            if (seen != null) { declaration = seen.from; break }
            passed += at
            declaration = declarationIn(at)
            if (declaration != null) break
            at = at.parentFile
        }
        for (folder in passed) known[folder.path] = Resolved(declaration)
        val resolved = known.getValue(start.path)
        return resolved.ref
            ?: (resolved.from?.let { refFor(start, it) } ?: CollectionRef.inferred(start.name)).also { resolved.ref = it }
    }

    /**
     * What the metafiles of [dir] declare, or null when none of them
     * declares a collection: a folder with no metafile, one that cannot be
     * listed, and one whose metafiles only list games, which is how a game's
     * own folder under a collection is written.
     *
     * The files in the order MetadataFile.allIn gives them, the two plain
     * names and then the others by name, so that the first collection found
     * here is the one the launch editor finds.
     */
    private fun declarationIn(dir: File): Declaration? {
        val blocks = metafilesIn(dir).flatMap { PegasusMetafile.collections(it) }
        return if (blocks.isEmpty()) null else Declaration(dir, blocks)
    }

    /**
     * The collection of [folder], which is [declared]'s folder or one under it.
     *
     * The first collection declared names it. The collection's own file and
     * an overlay beside it declare the same one, and a second block of that
     * name adds what it lists: its extensions, and a short name where the
     * first had none.
     *
     * A block of another name is a second collection in the same place, and
     * nothing here can say which of the two a folder is in, with one
     * exception. `directories:` is how a metafile says that a collection's
     * files are in another folder, so a block that lists [folder], or a
     * folder above it, has said so, and is taken before the first. Of two
     * that do, the one whose listed folder is nearer, as the nearer metafile
     * is the one that counts. The collection is then that listed folder, by
     * its name, and no longer the one the metafile is in. `extensions:` and
     * `file:` cannot choose: they tell files apart, and the question here is
     * asked of a folder.
     */
    private fun refFor(folder: File, declared: Declaration): CollectionRef {
        var home = declared.directory
        var chosen = declared.blocks.first()
        var listed = false
        for (block in declared.blocks) {
            for (directory in block.directories) {
                val target = (File(directory).takeIf { it.isAbsolute } ?: File(declared.directory, directory)).normalize()
                // Every folder that passes is [folder] or one above it, so the
                // longer path is the nearer folder. The first to list it keeps
                // it against a second that lists the same.
                if (folder.startsWith(target) && (!listed || target.path.length > home.path.length)) {
                    chosen = block; home = target; listed = true
                }
            }
        }

        // Said once for each collection passed over, and only where the first
        // was taken for want of anything better. A metafile that sends each
        // of its collections to a folder has nothing to be told.
        if (!listed) {
            for (other in declared.blocks) {
                if (other.name != chosen.name && reported.add(declared.directory.path + "\n" + other.name)) {
                    BridgeLog.w(TAG, "${declared.directory.name} declares collection '${other.name}' as well as " +
                                     "'${chosen.name}' and lists ${folder.name} under neither: " +
                                     "taken for '${chosen.name}'")
                }
            }
        }

        val same = declared.blocks.filter { it.name == chosen.name }
        return CollectionRef(
            shortName = same.firstOrNull { it.declaresShortName }?.shortName ?: chosen.shortName,
            name = chosen.name,
            dirName = home.name,
            directory = home,
            declaredExtensions = same.flatMapTo(LinkedHashSet()) { it.extensions },
            source = CollectionRef.Source.DECLARED
        )
    }

    private companion object {
        const val TAG = "CollectionResolver"
    }
}
