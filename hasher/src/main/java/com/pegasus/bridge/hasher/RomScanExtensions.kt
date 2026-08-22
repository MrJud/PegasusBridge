package com.pegasus.bridge.hasher

import java.io.File

/**
 * Which extensions a scan counts as ROMs in a given directory.
 *
 * The answer belongs to the collection: a Pegasus metadata file declares an
 * `extensions:` line, and it is the authority — `RomScanner`'s built-in list is
 * only what to use when nobody said. The daemon wires this up directly, by
 * handing [RomScanner.scan] a resolver that reads the collection.
 *
 * The Android shell cannot do the same thing in the same place. Reading a
 * metadata file means `MetadataFile`, which lives in the `pegasus` module, and
 * `pegasus` reaches this module already — `pegasus → media → hasher`. Depending
 * back on it would close a cycle Gradle refuses to build.
 *
 * So the resolver is installed from the one place that already sees both:
 * `DataLayerApp`, the Application object, which is this shell's composition root
 * and the counterpart of `BridgeDaemon` on the desktop. Left uninstalled — in a
 * unit test, or if a future entry point forgets — the default is the built-in
 * list, which is the behaviour there was before this existed.
 */
object RomScanExtensions {

    @Volatile
    private var resolver: ((File) -> Set<String>)? = null

    /** Called once, from the Application object, before any scan can run. */
    fun install(resolver: (File) -> Set<String>) {
        this.resolver = resolver
    }

    /**
     * The resolver to hand [RomScanner.scan].
     *
     * A failing resolver answers the built-in list rather than propagating: a
     * malformed metadata file in one directory must not end the scan of a
     * library, and "we could not read what this collection declares" is a
     * reason to fall back, not to stop.
     */
    val forScan: (File) -> Set<String>
        get() = { dir ->
            runCatching { resolver?.invoke(dir) }.getOrNull() ?: RomScanner.ROM_EXTENSIONS
        }
}
