package com.pegasus.bridge.scrapers

import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * How Android launches a Steam, Epic, GOG or Amazon game through GameNative,
 * or one of its custom games.
 *
 * The contract is not guessed. It was read out of GameNative's own
 * `AndroidManifest.xml` and `utils/IntentLaunchManager.kt` at
 * `utkarshdalal/GameNative` (GPL-3.0), where `MainActivity` is
 * `android:exported="true"` and carries an intent filter for
 * `app.gamenative.LAUNCH_GAME`:
 *
 * ```
 * action     app.gamenative.LAUNCH_GAME
 * category   android.intent.category.DEFAULT
 * component  app.gamenative/.MainActivity
 * extras
 *   app_id            int     the store's app id, must be > 0
 *   game_source       String  STEAM | CUSTOM_GAME | GOG | EPIC | AMAZON;
 *                             anything else is taken for STEAM
 *   container_config  String  optional JSON, refused above 50 000 bytes
 * ```
 *
 * ── What this deliberately is not ──────────────────────────
 *
 * A *launcher*, and nothing else. GameNative has Steam achievement support of
 * its own, and it keeps it in the app's private storage: there is no content
 * provider, no broadcast and no exported reader, so the Bridge cannot ask it
 * what a user has unlocked and must not pretend to. Achievements come from the
 * Steam Web API — [SteamAccountClient] — which answers the same way on both
 * shells and so gives the theme one contract instead of two.
 *
 * This class builds the parameters and nothing more. Constructing the `Intent`
 * needs `android.content`, which the shared code cannot import; the Android
 * shell does that with what is here.
 */
object GameNativeLaunch {

    const val PACKAGE = "app.gamenative"
    const val ACTIVITY = "app.gamenative.MainActivity"
    const val ACTION = "app.gamenative.LAUNCH_GAME"
    const val CATEGORY = "android.intent.category.DEFAULT"

    const val EXTRA_APP_ID = "app_id"
    const val EXTRA_GAME_SOURCE = "game_source"
    const val EXTRA_CONTAINER_CONFIG = "container_config"

    /**
     * The stores GameNative validates against.
     *
     * An unrecognised value is not rejected there — `IntentLaunchManager` falls
     * back to `STEAM` — but sending one anyway would mean asking for a GOG game
     * and silently getting the Steam app of the same id, which is a different
     * game. So this refuses locally instead.
     *
     * `CUSTOM_GAME` is a game the user added to GameNative by hand. Its id is
     * GameNative's own number for it, and means nothing anywhere else.
     */
    enum class Source { STEAM, EPIC, GOG, AMAZON, CUSTOM_GAME }

    /** GameNative's own cap. Larger is dropped there, so it is refused here. */
    const val MAX_CONFIG_BYTES = 50_000

    data class Request(
        val appId: Int,
        val source: Source = Source.STEAM,
        val containerConfigJson: String = ""
    ) {
        val extras: Map<String, Any> get() = buildMap {
            // An Int extra, not a String: `getIntExtra` returns -1 for a String
            // and the launch is refused for an "invalid app_id" that was correct.
            put(EXTRA_APP_ID, appId)
            put(EXTRA_GAME_SOURCE, source.name)
            if (containerConfigJson.isNotBlank())
                put(EXTRA_CONTAINER_CONFIG, containerConfigJson)
        }
    }

    sealed interface Result {
        data class Ok(val request: Request) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Validates a launch before an intent is built for it.
     *
     * Everything checked here is checked again by GameNative, and refused there
     * with a log line inside another app that nobody reading the theme will ever
     * see. Refusing locally is what lets the failure carry a reason.
     */
    fun requestFor(appId: Int, source: String = "STEAM", containerConfigJson: String = ""): Result {
        if (appId <= 0) return Result.Rejected("app_id must be a positive store id, not $appId")
        val src = Source.entries.firstOrNull { it.name.equals(source, ignoreCase = true) }
            ?: return Result.Rejected(
                "unknown game source '$source' — GameNative knows " +
                Source.entries.joinToString("/") { it.name })
        if (containerConfigJson.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES)
            return Result.Rejected("the container config is over GameNative's ${MAX_CONFIG_BYTES}-byte limit")
        return Result.Ok(Request(appId, src, containerConfigJson))
    }

    // ── A marker file ───────────────────────────────────────────────────────
    //
    // GameNative keeps a folder of marker files, one per installed game, for a
    // frontend to list: `Hollow Knight.steam`, holding `367520`. The name is
    // the game's title, the extension says which store it came from, and the
    // content is the id GameNative launches it by. It writes and removes them
    // itself. Measured on 2026-10-13 with GameNative 1.2.1, whose folder on
    // that device was Emulation/Roms/windows on the SD card.
    //
    // Pegasus launches such a "game" by handing the Bridge the file's path,
    // and the path is all it hands over. What comes of it is made here, out of
    // the constants above, the number in the file and the extension's store.
    // A path is a string from another app, and whoever can send the verb can
    // name any file: so it is held to being a marker under a folder games are
    // kept in, and nothing of the path itself reaches GameNative.

    /** The most a marker file may hold. An app id is ten digits at the longest. */
    const val MAX_MARKER_BYTES = 16

    /** The longest path looked at. */
    const val MAX_PATH_CHARS = 1024

    /** Where a marker may lie: the shared storage and the cards, as Android names them. */
    val MARKER_ROOTS: List<String> = listOf("/storage")

    /**
     * Where it may not, though under [MARKER_ROOTS]: the Bridge's own data and
     * the folders Android keeps for each app. No game is kept in either.
     */
    val FORBIDDEN_ROOTS: List<String> =
        listOf("/storage/emulated/0/PegasusData", "/storage/emulated/0/Android")

    /**
     * The store each extension stands for, the extension in lower case. Only a
     * `.steam` number is a number outside the install that wrote it: it is the
     * Steam appid. The others are GameNative's own.
     */
    private val MARKER_SOURCES: Map<String, Source> = mapOf(
        "steam"  to Source.STEAM,
        "gog"    to Source.GOG,
        "epic"   to Source.EPIC,
        "amazon" to Source.AMAZON,
        "pcgame" to Source.CUSTOM_GAME
    )

    /** Why a path was not launched. [reason] is a sentence of this file's, with nothing of the path in it. */
    enum class MarkerRefusal(val reason: String) {
        MISSING("no file was named"),
        TOO_LONG("the path is longer than $MAX_PATH_CHARS characters"),
        NUL("the path holds a NUL"),
        NOT_ABSOLUTE("the path is not absolute"),
        OUTSIDE("the path is not under a folder games are kept in"),
        FORBIDDEN("the path is under a folder no game is kept in"),
        EXTENSION("the extension is not one GameNative writes"),
        NOT_CANONICAL("the path is not the file's own: a link in it, or a . or a .."),
        NOT_A_FILE("there is no regular file at the path"),
        UNREADABLE("the file could not be read"),
        TOO_BIG("the file holds more than $MAX_MARKER_BYTES bytes"),
        NOT_AN_ID("the file does not hold an app id")
    }

    /** The store a file of this name is a marker of, or null when its extension is none of the five. */
    fun sourceOfName(name: String): Source? {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return null
        return MARKER_SOURCES[name.substring(dot + 1).lowercase(Locale.ROOT)]
    }

    /**
     * The app id a marker file's bytes hold, or null when they hold none.
     *
     * The grammar is the theme's too, which reads the same files to know which
     * Steam game each is, and the two must agree to the byte:
     *
     *  - at most [MAX_MARKER_BYTES] bytes, counted before anything is stripped;
     *  - space, tab, CR and LF stripped at both ends, and nothing else;
     *  - what is left is `[1-9][0-9]{0,9}` and no more than 2147483647.
     *
     * It is read as bytes and never as text. A digit of another script, a
     * byte-order mark and a NUL are then simply bytes that are not `0` to `9`,
     * and no decoder has a say in what they turn into.
     */
    fun markerAppId(bytes: ByteArray, length: Int = bytes.size): Int? {
        if (length > MAX_MARKER_BYTES) return null
        var start = 0
        var end = length
        while (start < end && isMarkerBlank(bytes[start])) start++
        while (end > start && isMarkerBlank(bytes[end - 1])) end--
        if (end - start !in 1..10) return null
        if (bytes[start] == '0'.code.toByte()) return null
        var value = 0L
        for (i in start until end) {
            val digit = bytes[i] - '0'.code.toByte()
            if (digit !in 0..9) return null
            value = value * 10 + digit
        }
        return if (value <= Int.MAX_VALUE) value.toInt() else null
    }

    private fun isMarkerBlank(b: Byte): Boolean =
        b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\r'.code.toByte() || b == '\n'.code.toByte()

    /** True when [path] is [root] or lies under it. Both are absolute and neither ends in a slash. */
    private fun within(path: String, root: String, ignoreCase: Boolean): Boolean =
        path.regionMatches(0, root, 0, root.length, ignoreCase) &&
            (path.length == root.length || path[root.length] == '/')

    /**
     * The launch a marker file stands for, or why there is none.
     *
     * [path] is what a caller named, and nothing of it is believed. It is
     * refused when it is missing, longer than [MAX_PATH_CHARS], holds a NUL
     * or is not absolute; when it is not under one of [allowedRoots], or is
     * under one of [forbiddenRoots]; when its extension is none of the five;
     * when it is not its own canonical form, which is what a link, a `.`, a
     * `..` and a doubled slash all come to; when no regular file is there;
     * when the file holds more than [MAX_MARKER_BYTES] bytes or something
     * that is not an app id.
     *
     * The roots are compared with the path as it was given, which the
     * canonical form is then held to be: a link out of a folder is refused
     * by that and not by the roots. The forbidden ones are compared without
     * regard to case, as the storage they are on names its files.
     *
     * No more than one byte over the limit is ever read, and the length the
     * file system gives is not asked for: a file can grow between the two.
     *
     * The roots are parameters for the tests, which have no /storage.
     */
    fun fromMarkerFile(
        path: String?,
        allowedRoots: List<String> = MARKER_ROOTS,
        forbiddenRoots: List<String> = FORBIDDEN_ROOTS
    ): Result {
        fun refused(why: MarkerRefusal) = Result.Rejected(why.reason)

        if (path.isNullOrEmpty()) return refused(MarkerRefusal.MISSING)
        if (path.length > MAX_PATH_CHARS) return refused(MarkerRefusal.TOO_LONG)
        if (path.indexOf('\u0000') >= 0) return refused(MarkerRefusal.NUL)
        if (!path.startsWith("/")) return refused(MarkerRefusal.NOT_ABSOLUTE)

        if (allowedRoots.none { path.length > it.length && within(path, it, ignoreCase = false) })
            return refused(MarkerRefusal.OUTSIDE)
        if (forbiddenRoots.any { within(path, it, ignoreCase = true) })
            return refused(MarkerRefusal.FORBIDDEN)

        val source = sourceOfName(path.substring(path.lastIndexOf('/') + 1))
            ?: return refused(MarkerRefusal.EXTENSION)

        val file = File(path)
        val canonical = try { file.canonicalPath } catch (e: Exception) { null }
        if (canonical != path) return refused(MarkerRefusal.NOT_CANONICAL)
        if (!file.isFile) return refused(MarkerRefusal.NOT_A_FILE)

        val bytes = ByteArray(MAX_MARKER_BYTES + 1)
        var held = 0
        try {
            FileInputStream(file).use { input ->
                while (held < bytes.size) {
                    val n = input.read(bytes, held, bytes.size - held)
                    if (n < 0) break
                    held += n
                }
            }
        } catch (e: Exception) {
            return refused(MarkerRefusal.UNREADABLE)
        }
        if (held > MAX_MARKER_BYTES) return refused(MarkerRefusal.TOO_BIG)

        val appId = markerAppId(bytes, held) ?: return refused(MarkerRefusal.NOT_AN_ID)
        // No container config, and nothing else a caller could have a hand in.
        return Result.Ok(Request(appId, source))
    }

    /**
     * The `am start` line the intent corresponds to.
     *
     * Not used to launch anything — it is what a Pegasus collection's `launch:`
     * field would contain on Android, and what a person needs when a launch does
     * not work and they want to try it from a shell.
     */
    fun asAmStartCommand(request: Request): String = buildString {
        append("am start -n $PACKAGE/$ACTIVITY -a $ACTION")
        append(" --ei $EXTRA_APP_ID ${request.appId}")
        append(" --es $EXTRA_GAME_SOURCE ${request.source.name}")
    }
}
