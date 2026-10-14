package com.pegasus.bridge.core

import java.io.File

/**
 * What the Bridge keeps of a Steam account on disk, as far as dropping it
 * goes, and the rule a SteamID is held to.
 *
 * The `steam-account` source, SteamAccountOps, writes and reads these files,
 * and lies with the scrapers. Whoever writes or clears the steam block has to
 * drop them too, and on Android that is RaService, which cannot see the
 * scrapers. So the one definition is here, in the folder every module of both
 * shells compiles, and SteamAccountOps.forget is this.
 *
 * Under `cache/steam` of the data root:
 *
 *     schema/<appId>.<language>.json       every account's; kept
 *     <steamId>/library.json               the account's; dropped
 *     <steamId>/progress/<appId>.json      the account's; dropped
 */
object SteamAccountCache {

    /** The folder of everything kept, under the data root's cache. */
    const val DIR = "steam"

    /**
     * Held by SteamAccountOps from the look at the disk to the write, and by
     * [forget]. One object for the process, so that a drop and a request on
     * another thread do not meet halfway.
     */
    val lock = Any()

    /** A progress file's name, or that of one a write was stopped in the middle of. */
    private val KEPT_PROGRESS = Regex("[0-9]{1,10}\\.json(\\.tmp)?")

    // Spelled out, and not `\d`: on Android that takes the digits of every script.
    fun isSteamId(s: String): Boolean = s.length == 17 && s.all { it in '0'..'9' }

    /**
     * Drops what is kept of every account under [cacheDir], the folder [DIR]
     * of the data root's cache: the libraries and the unlock lists. The
     * schemas stay, being every account's.
     *
     * Only the files SteamAccountOps writes are deleted, by their names, in
     * folders named as a SteamID is. Nothing else is touched, and a folder
     * goes only when that leaves it empty. A null [cacheDir], or one that is
     * not there, is nothing to do.
     */
    fun forget(cacheDir: File?) {
        if (cacheDir == null) return
        synchronized(lock) {
            for (account in cacheDir.listFiles().orEmpty()) {
                if (!isSteamId(account.name) || !account.isDirectory) continue
                File(account, "library.json").delete()
                File(account, "library.json.tmp").delete()
                val progress = File(account, "progress")
                for (f in progress.listFiles().orEmpty())
                    if (KEPT_PROGRESS.matches(f.name)) f.delete()
                progress.delete()
                account.delete()
            }
        }
    }
}
