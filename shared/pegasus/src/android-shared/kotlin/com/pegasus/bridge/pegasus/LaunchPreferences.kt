package com.pegasus.bridge.pegasus

import com.pegasus.bridge.core.BridgeLog
import org.json.JSONObject
import java.io.File

/**
 * Which emulator the user picked, per collection and per game.
 *
 * The theme's part of this is a list and a selection: the Quick menu chooses one
 * for a whole collection, the game database overrides it for a single title.
 * Both are decisions a person made, and the architecture has one rule about
 * those — *user overrides always win and are never overwritten by a sync*.
 *
 * ── Why this is not stored in the overlay ──────────────────
 *
 * Because the overlay is generated. Every metadata export rewrites it, and a
 * choice living inside it would be lost the next time somebody scraped a
 * collection. So the choice lives here, in the Bridge's own data root, and the
 * overlay is rendered *from* it. Deleting the overlay loses nothing; deleting
 * this file is what forgets a preference.
 *
 * ── Why it stores an emulator id and not a command ─────────
 *
 * `gopher64`, not `flatpak run io.github.gopher64.gopher64 "{file.path}"`. A
 * command frozen at the moment of choosing goes stale: the Flatpak gets replaced
 * by a native package, a path moves, an argument convention changes. The id is
 * the durable half, and discovery re-derives the command every time — so an
 * emulator that has been uninstalled becomes a preference that cannot currently
 * be honoured, which is reportable, rather than a launch line that silently
 * fails.
 */
class LaunchPreferences(private val file: File) {

    private val collections = HashMap<String, String>()
    private val games = HashMap<String, String>()

    init { load() }

    private fun load() {
        if (!file.isFile) return
        try {
            val root = JSONObject(file.readText())
            if (root.optInt("schemaVersion") != SCHEMA_VERSION) {
                BridgeLog.w(TAG, "launch preferences are from schema " +
                                 "${root.optInt("schemaVersion")}; keeping them read-only")
            }
            root.optJSONObject("collections")?.let { o ->
                for (k in o.keys()) o.optString(k).takeIf { it.isNotEmpty() }?.let { collections[k] = it }
            }
            root.optJSONObject("games")?.let { o ->
                for (k in o.keys()) o.optString(k).takeIf { it.isNotEmpty() }?.let { games[k] = it }
            }
        } catch (t: Throwable) {
            // A preference is a decision somebody made, so losing the file is not
            // like losing a cache. It is still not worth failing a request over —
            // the user re-picks — but it is worth being loud about.
            BridgeLog.e(TAG, "launch preferences unreadable; they will be treated as unset", t)
        }
    }

    /** One spelling per path, so two roots reaching a file differently agree. */
    private fun key(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    fun forCollection(dir: File): String? = synchronized(this) { collections[key(dir)] }

    /**
     * The game's own choice, or null — *not* the collection's.
     *
     * Kept separate from [effectiveFor] on purpose: a UI has to be able to show
     * "inherited from the collection" differently from "chosen for this game",
     * and collapsing the two would make the difference invisible and the clear
     * action meaningless.
     */
    fun forGame(rom: File): String? = synchronized(this) { games[key(rom)] }

    /** What actually applies: the game's choice, else its collection's. */
    fun effectiveFor(rom: File, collectionDir: File): String? =
        forGame(rom) ?: forCollection(collectionDir)

    fun setCollection(dir: File, emulatorId: String) =
        synchronized(this) { collections[key(dir)] = emulatorId }

    fun setGame(rom: File, emulatorId: String) =
        synchronized(this) { games[key(rom)] = emulatorId }

    /** Back to inheriting from the collection. */
    fun clearGame(rom: File) = synchronized(this) { games.remove(key(rom)) != null }

    fun clearCollection(dir: File) = synchronized(this) { collections.remove(key(dir)) != null }

    /** Every game-level choice under [dir], keyed by the ROM's file name. */
    fun gamesIn(dir: File): Map<String, String> {
        val prefix = key(dir) + File.separator
        return synchronized(this) {
            games.filterKeys { it.startsWith(prefix) }
                .mapKeys { File(it.key).name }
        }
    }

    fun save(writeAtomic: (File, String) -> Unit) {
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("updatedAt", System.currentTimeMillis() / 1000L)
            .put("collections", JSONObject().also { o ->
                synchronized(this) { collections.forEach { (k, v) -> o.put(k, v) } } })
            .put("games", JSONObject().also { o ->
                synchronized(this) { games.forEach { (k, v) -> o.put(k, v) } } })
        runCatching { writeAtomic(file, payload.toString(2)) }
            .onFailure { BridgeLog.e(TAG, "could not write the launch preferences", it) }
    }

    /** Drops choices for ROMs that are no longer on disk. */
    fun forget(existing: Set<String>) = synchronized(this) {
        games.keys.retainAll(existing)
    }

    companion object {
        private const val TAG = "LaunchPreferences"

        /**
         * Which launch command a collection's overlay should carry.
         *
         * A recorded preference wins over whatever the overlay says now. The
         * other way round was the first version, and a demo showed what it costs:
         * choosing an emulator in the Quick menu stored the preference, then an
         * export rewrote the file with the *old* command — so the choice looked
         * remembered and did nothing until somebody also ran `/emulators/apply`.
         * A recorded choice is the more recent statement of intent.
         *
         * [overlayLaunch] remains the fallback, for a collection that had a
         * launch applied before preferences existed.
         *
         * A preference naming an emulator that is no longer installed resolves to
         * no command, and the fallback carries it — losing a working launch line
         * because a preference went stale would be the worse failure.
         */
        fun collectionLaunch(
            preferred: String?,
            commandOf: (String) -> String?,
            overlayLaunch: String
        ): String = preferred?.let(commandOf).orEmpty().takeIf { runnable(it) }.orEmpty()
            .ifEmpty { overlayLaunch }

        /**
         * Whether a resolved command is one that could actually be written.
         *
         * `{core}` is the Bridge's own placeholder, not Pegasus'. Pegasus
         * expands `{file.path}` and its siblings itself; nothing expands
         * `{core}`, so a line still carrying one reaches the emulator verbatim
         * and fails.
         *
         * Enforced here rather than at each call site because it was enforced at
         * one call site and not the other, which is how it went wrong: apply
         * refuses a launch containing `{core}` and says "pick one first", while
         * the metadata export took the same unresolved template from the same
         * preference and wrote it into the same overlay without a word. Caught
         * on the tablet, where choosing RetroArch for `snes` — the only
         * emulator installed that handles it — produced exactly that file.
         *
         * Treated like a preference naming an uninstalled emulator, for the same
         * reason given above: the overlay's existing line is carried instead,
         * because losing a launch that works is the worse failure.
         */
        internal fun runnable(command: String): Boolean = !command.contains("{core}")

        /**
         * A game's own launch line, or empty when it should not have one.
         *
         * Empty when the game made no choice, and empty when its choice is the
         * same as its collection's: an override that overrides nothing is noise
         * in a file people read.
         */
        fun gameLaunch(
            chosen: String?,
            collectionChoice: String?,
            commandOf: (String) -> String?
        ): String {
            if (chosen == null || chosen == collectionChoice) return ""
            // A per-game override that cannot run is worse than none: the
            // collection's line, which can, would be shadowed by it.
            return chosen.let(commandOf).orEmpty().takeIf { runnable(it) }.orEmpty()
        }

        const val FILE_NAME = "launch-preferences.json"
        const val SCHEMA_VERSION = 1
    }
}
