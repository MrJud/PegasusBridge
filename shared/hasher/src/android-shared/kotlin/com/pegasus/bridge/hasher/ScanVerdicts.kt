package com.pegasus.bridge.hasher

import java.io.File

/**
 * What the last scan settled about one file, for a reader that is not a scan:
 * the ledger asked by path, with no hashing and no collection.
 *
 * A scan holds a verdict to the recipe of its hasher and to the verdict's
 * age ([ScanLedger.canSkip]). Neither is looked at here: this reader has no
 * hasher, and does not know which collection the file is of. What it is
 * given is the scan's last word on the file, which stands until a scan has
 * another.
 */
object ScanVerdicts {

    sealed interface Verdict {
        /** The scan reached a verdict, and the file is still the one it saw, or is gone. */
        class Settled(val entry: ScanLedger.Entry) : Verdict

        /**
         * The ledger holds the file and what it holds is no verdict now: the
         * lookup never got an answer, the file would not hash, or it is
         * another file than the scan saw, by its size or its date.
         */
        data object Unsettled : Verdict

        /** No scan has recorded this file, or there is no ledger. */
        data object Unseen : Verdict
    }

    private class Read(val ledger: ScanLedger, val length: Long, val modified: Long)

    // One parsed ledger per file. A ledger is some megabytes for a large
    // library and is asked about once per game opened, so it is parsed again
    // only when a scan has saved it since.
    private val read = HashMap<String, Read>()

    /**
     * The verdict on [file] in the ledger under [cacheDir].
     *
     * [file] is the path as a frontend gives it, links not followed. The
     * ledger is keyed by the real path, so that is what is looked up first,
     * and the path as given after it, for a file that is gone and whose real
     * path can no longer be had.
     *
     * A file that is gone keeps its verdict: the ledger holds it until a
     * scan forgets it, and nothing about the file contradicts it.
     */
    fun of(cacheDir: File, file: String): Verdict {
        val ledger = ledgerOf(File(cacheDir, ScanLedger.FILE_NAME)) ?: return Verdict.Unseen
        val key = RomScanner.canonical(File(file))
        // Making the path real is a look at the disk, and for a path the
        // ledger has never held it is the only one: the file's size and
        // date are asked for an entry and not before.
        val entry = ledger.entry(key) ?: ledger.entry(file) ?: return Verdict.Unseen
        if (!entry.state.cacheable) return Verdict.Unsettled
        val onDisk = File(key)
        if (onDisk.exists() && (onDisk.length() != entry.fileSize || onDisk.lastModified() != entry.lastModified))
            return Verdict.Unsettled
        return Verdict.Settled(entry)
    }

    private fun ledgerOf(file: File): ScanLedger? = synchronized(read) {
        if (!file.isFile) return null
        val key = file.absolutePath
        val length = file.length()
        val modified = file.lastModified()
        val kept = read[key]
        if (kept != null && kept.length == length && kept.modified == modified) return kept.ledger
        ScanLedger(file).also { read[key] = Read(it, length, modified) }
    }
}
