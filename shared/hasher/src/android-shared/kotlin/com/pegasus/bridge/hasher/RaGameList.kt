package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * The games RetroAchievements has for one console, and the hashes it knows
 * each of them by: what `API_GetGameList.php` answers when asked with `h=1`.
 *
 * One answer holds every hash of the console, so a library is looked up in a
 * list per console and not with a request per file. The list also says of
 * each game what a match writes down, its title, icon and number of
 * achievements, so nothing else has to be asked.
 *
 * [listed] is how many games the answer held, with or without hashes, and is
 * for the log. Of a list read back from disk it is the number of lines, which
 * are the games that hold a hash: the others are not kept, having nothing a
 * hash could be looked up by.
 */
class RaGameList private constructor(
    val consoleId: Int,
    /** When the list was asked for, in seconds, by the clock of whoever asked. */
    val fetchedAt: Long,
    val consoleName: String,
    val listed: Int,
    private val byHash: Map<String, Game>
) {
    /**
     * A game as the list describes it. [numAchievements] is -1 when the list
     * gave no whole number, and [title] empty when it gave none: the hashes
     * are kept all the same, so that such a game is known to be there and is
     * not taken for a hash RetroAchievements does not have.
     */
    data class Game(val gameId: Int, val title: String, val imageIcon: String, val numAchievements: Int)

    /** Games that hold at least one hash. */
    val games: Int = byHash.values.distinctBy { it.gameId }.size

    val hashes: Int get() = byHash.size

    /**
     * The game a hash belongs to. [md5] in lowercase: the keys are, whatever
     * the answer wrote them in.
     */
    operator fun get(md5: String): Game? = byHash[md5]

    /**
     * Not older than [MAX_AGE_SECONDS], and not from more than a day ahead
     * either. A list dated in the future was written by a clock that has
     * since been set back, and would otherwise stay good until the clock
     * caught up with it, however long that is. The day is for a clock that
     * is only adjusted.
     */
    fun freshAt(now: Long): Boolean = now - fetchedAt in -DAY_SECONDS..MAX_AGE_SECONDS

    /**
     * Writes the list as [file] of [dir], whole or not at all.
     *
     * A line of text per game and not the answer's JSON: a list is read at
     * the start of every scan that needs it, on a tablet too, and a few
     * megabytes of JSON become some tens of megabytes of tree before the
     * first hash can be looked up. A line is split and forgotten.
     *
     * The counts in the first line are of what is written below it, so that
     * a file cut short is told from a whole one.
     */
    fun write(dir: File) {
        val lines = LinkedHashMap<Game, MutableList<String>>()
        for ((hash, game) in byHash) lines.getOrPut(game) { ArrayList() }.add(hash)
        val text = StringBuilder()
        text.append(MAGIC).append('\t').append(FORMAT).append('\t').append(consoleId).append('\t')
            .append(fetchedAt).append('\t').append(lines.size).append('\t').append(byHash.size).append('\t')
            .append(consoleName).append('\n')
        for ((game, itsHashes) in lines) {
            text.append(game.gameId).append('\t').append(game.numAchievements).append('\t')
                .append(game.imageIcon).append('\t')
            itsHashes.joinTo(text, ",")
            text.append('\t').append(game.title).append('\n')
        }
        BridgePaths.writeAtomic(file(dir, consoleId), text.toString())
    }

    /** What [parse] made of a body. */
    sealed interface Parsed {
        /**
         * A list, which may be of no game at all: `[]` is a whole array, and
         * what is said here is what was sent. Whether a list of nothing is to
         * be believed is for whoever asked.
         */
        class Listed(val list: RaGameList) : Parsed

        /**
         * A whole JSON array that is no list of this console's games. [why]
         * says in what it fell short and never quotes the body, which can
         * echo the request and with it the key.
         */
        class Refused(val why: String) : Parsed

        /**
         * Not a JSON array from its first character to its last: a page of
         * HTML, an object, an answer cut short, nothing.
         */
        data object NotAnArray : Parsed
    }

    companion object {
        /** The folder of the lists, under the data root's cache. */
        const val DIR = "ra-lists"

        /**
         * How long a list is answered from before it is asked for again. A
         * hash RetroAchievements adds is not found until then.
         */
        const val MAX_AGE_SECONDS = 7L * 24 * 60 * 60

        /** Of the file. A file of another number is not read, and so is asked for again. */
        const val FORMAT = 1

        private const val TAG = "RaGameList"
        private const val MAGIC = "ra-game-list"
        private const val DAY_SECONDS = 24L * 60 * 60
        private const val MAX_GAME_ID = 1_000_000_000
        private val MD5 = Regex("[0-9a-f]{32}")
        private val BREAKS = Regex("[\t\r\n]")

        fun file(dir: File, consoleId: Int) = File(dir, "$consoleId.tsv")

        /**
         * Reads what `API_GetGameList.php` answered for [consoleId].
         *
         * The whole answer is held to being that console's list before any
         * of it is believed. A list is what says a hash is not known, and a
         * miss stands for days: an answer that is half of something else
         * would write a library off.
         *
         * So one element that is not a game with an id, or that names
         * another console, refuses the lot. So does an answer in which no
         * game has a `Hashes` array, which is what the list looks like when
         * `h=1` was not honoured: every game there and no hash to find it
         * by. And so does one whose games come with hashes of which not one
         * is a hash as it is read here, which is what the list looks like
         * the day its hashes are written another way: a console that has
         * hashes, and a list that would say of each that it is not known.
         *
         * A hash is kept in lowercase. RetroAchievements compares them
         * without regard to case and its lists hold some in capitals, most
         * of them Nintendo 64's. One that is not 32 hex digits is left out.
         * One given to two games stays with the first.
         */
        fun parse(consoleId: Int, body: String, fetchedAt: Long): Parsed {
            val array = wholeArray(body) ?: return Parsed.NotAnArray
            val byId = HashMap<Int, Game>()
            val byHash = LinkedHashMap<String, Game>()
            var consoleName = ""
            var withHashes = 0
            var given = 0
            var twice = 0
            for (i in 0 until array.length()) {
                val element = array.opt(i) as? JSONObject
                    ?: return Parsed.Refused("element $i is not a game")
                val id = wholeNumber(element, "ID")?.takeIf { it in 1..MAX_GAME_ID }
                    ?: return Parsed.Refused("element $i has no game id")
                if (element.has("ConsoleID") && wholeNumber(element, "ConsoleID") != consoleId)
                    return Parsed.Refused("game $id is of another console")
                if (consoleName.isEmpty()) consoleName = text(element, "ConsoleName")
                // The first description of a game stands, should the answer
                // give the game twice, and the hashes of both are its own.
                val game = byId.getOrPut(id) {
                    Game(id, text(element, "Title"), text(element, "ImageIcon"),
                         wholeNumber(element, "NumAchievements") ?: -1)
                }
                val itsHashes = element.opt("Hashes") as? JSONArray ?: continue
                withHashes++
                given += itsHashes.length()
                for (h in 0 until itsHashes.length()) {
                    val hash = (itsHashes.opt(h) as? String)?.trim()?.lowercase(Locale.ROOT) ?: continue
                    if (!MD5.matches(hash)) continue
                    val holder = byHash.getOrPut(hash) { game }
                    if (holder.gameId != id) twice++
                }
            }
            if (array.length() > 0 && withHashes == 0) return Parsed.Refused("no game comes with its hashes")
            if (given > 0 && byHash.isEmpty()) return Parsed.Refused("none of the $given hashes given is a hash")
            if (twice > 0)
                BridgeLog.w(TAG, "console $consoleId: $twice hashes given to a second game, left with the first")
            return Parsed.Listed(RaGameList(consoleId, fetchedAt, consoleName, array.length(), byHash))
        }

        /**
         * The list [write] left for [consoleId] in [dir], or null: no file,
         * a file of another format or another console, one cut short, one
         * with a line that is not a game's. Null is a list to ask for again,
         * so nothing here throws and nothing is mended: a file that is not
         * exactly what was written is not answered from.
         *
         * The counts of the header tell a file that lost whole lines. One
         * cut inside its last line has every line it should, and the last
         * of them ends in a title that is only shorter: what tells that one
         * is the line break every line is written with, which it has lost.
         */
        fun read(dir: File, consoleId: Int): RaGameList? = try {
            file(dir, consoleId).takeIf { it.isFile && endsWithALineBreak(it) }?.bufferedReader()?.use { reader ->
                val head = reader.readLine()?.split('\t', limit = 7)?.takeIf { it.size == 7 } ?: return null
                if (head[0] != MAGIC || head[1] != FORMAT.toString() || head[2] != consoleId.toString()) return null
                val fetchedAt = head[3].toLongOrNull() ?: return null
                val games = head[4].toIntOrNull() ?: return null
                val hashes = head[5].toIntOrNull() ?: return null
                val ids = HashSet<Int>()
                val byHash = LinkedHashMap<String, Game>()
                while (true) {
                    val line = reader.readLine() ?: break
                    val f = line.split('\t', limit = 5)
                    if (f.size != 5) return null
                    val id = f[0].toIntOrNull()?.takeIf { it in 1..MAX_GAME_ID } ?: return null
                    val achievements = f[1].toIntOrNull()?.takeIf { it >= -1 } ?: return null
                    if (!ids.add(id)) return null
                    val game = Game(id, f[4], f[2], achievements)
                    for (hash in f[3].split(',')) {
                        if (!MD5.matches(hash) || byHash.put(hash, game) != null) return null
                    }
                }
                if (ids.size != games || byHash.size != hashes) return null
                RaGameList(consoleId, fetchedAt, head[6], ids.size, byHash)
            }
        } catch (e: Exception) {
            null
        }

        private fun endsWithALineBreak(file: File): Boolean = RandomAccessFile(file, "r").use {
            val length = it.length()
            if (length == 0L) return false
            it.seek(length - 1)
            it.read() == '\n'.code
        }

        /**
         * The body as a JSON array, or null when it is anything else or goes
         * on after the array's end. Read with a tokener and not with
         * `JSONArray(String)`, which on Android stops at the closing bracket
         * and takes what follows without a word.
         */
        private fun wholeArray(body: String): JSONArray? = try {
            val tokener = JSONTokener(body)
            (tokener.nextValue() as? JSONArray)?.takeIf { tokener.nextClean().code == 0 }
        } catch (e: JSONException) {
            null
        }

        /**
         * A whole number from 0 up, or null, as the lookup has always read
         * the numbers of RetroAchievements: `optInt` turns 0.5 and a missing
         * field into 0. A decimal string passes, being the same number
         * written differently.
         */
        private fun wholeNumber(obj: JSONObject, key: String): Int? =
            obj.opt(key)?.toString()?.toIntOrNull()?.takeIf { it >= 0 }

        /**
         * A field of text made fit for one field of a line: a tab or a line
         * break in it would be read back as the end of the field or of the
         * game.
         */
        private fun text(obj: JSONObject, key: String): String =
            (obj.opt(key) as? String)?.replace(BREAKS, " ")?.trim().orEmpty()
    }
}
