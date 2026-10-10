package com.pegasus.bridge.core

/**
 * What rcheevos can hash, and which of it each collection is.
 *
 * rcheevos picks a console from a file's extension when it is told none, and
 * that is a guess: a `.bin` is a Mega Drive cartridge, an extension it has
 * never heard of is a Game Boy one, and either way the file gets a confident
 * hash that matches nothing. A collection says what its files are. This is
 * the table that turns what a collection calls itself into the console
 * rcheevos is to be told, or into the plain answer that there is none.
 *
 * Two lists. [CONSOLES] is rcheevos' own: every id of the vendored
 * rc_consoles.h, with the way rc_hash_from_file in hash.c hashes it. The rows
 * are ours: one for each collection name we can say something about. A name
 * with no row is unknown, which is not the same as unsupported: an unknown
 * collection is still hashed by guessing, as every collection was before.
 *
 * A test reads the two C files and fails when this copy is behind them, so
 * moving to another rcheevos cannot leave it so unnoticed.
 */
object RcConsoles {

    /**
     * How rcheevos hashes a console's files, which is also whether this build
     * can: all of DISC, ROM, ZIP and ENCRYPTED is behind a compile-time switch.
     */
    enum class Algorithm {
        /** The MD5 of the file, up to 64 MiB of it. */
        WHOLE,
        /** The same, and a playlist is followed to its first entry. */
        WHOLE_M3U,
        /** The file is read into memory and a header taken off it first. */
        BUFFERED,
        /** A disc image: the hash is of what a parser finds in it. */
        DISC,
        /** A cartridge or a set with a parser of its own. */
        ROM,
        /** A zip read entry by entry. */
        ZIP,
        /** Needs keys the caller must supply. */
        ENCRYPTED,
        /** An id and nothing else: rc_hash_from_file has no case for it. */
        NONE
    }

    /** One RC_CONSOLE_ constant: [name] is what follows that prefix in the header. */
    data class Console(val id: Int, val name: String, val algorithm: Algorithm) {
        val constant: String get() = "RC_CONSOLE_$name"
    }

    /** Ids 1 to 81 of rcheevos 12.3.0, in the header's order. */
    val CONSOLES: List<Console> = listOf(
        Console(1, "MEGA_DRIVE", Algorithm.WHOLE_M3U),
        Console(2, "NINTENDO_64", Algorithm.ROM),
        Console(3, "SUPER_NINTENDO", Algorithm.BUFFERED),
        Console(4, "GAMEBOY", Algorithm.WHOLE),
        Console(5, "GAMEBOY_ADVANCE", Algorithm.WHOLE),
        Console(6, "GAMEBOY_COLOR", Algorithm.WHOLE),
        Console(7, "NINTENDO", Algorithm.BUFFERED),
        Console(8, "PC_ENGINE", Algorithm.BUFFERED),
        Console(9, "SEGA_CD", Algorithm.DISC),
        Console(10, "SEGA_32X", Algorithm.WHOLE),
        Console(11, "MASTER_SYSTEM", Algorithm.WHOLE),
        Console(12, "PLAYSTATION", Algorithm.DISC),
        Console(13, "ATARI_LYNX", Algorithm.BUFFERED),
        Console(14, "NEOGEO_POCKET", Algorithm.WHOLE),
        Console(15, "GAME_GEAR", Algorithm.WHOLE),
        Console(16, "GAMECUBE", Algorithm.DISC),
        Console(17, "ATARI_JAGUAR", Algorithm.WHOLE),
        Console(18, "NINTENDO_DS", Algorithm.ROM),
        Console(19, "WII", Algorithm.DISC),
        Console(20, "WII_U", Algorithm.NONE),
        Console(21, "PLAYSTATION_2", Algorithm.DISC),
        Console(22, "XBOX", Algorithm.NONE),
        Console(23, "MAGNAVOX_ODYSSEY2", Algorithm.WHOLE),
        Console(24, "POKEMON_MINI", Algorithm.WHOLE),
        Console(25, "ATARI_2600", Algorithm.WHOLE),
        Console(26, "MS_DOS", Algorithm.ZIP),
        Console(27, "ARCADE", Algorithm.ROM),
        Console(28, "VIRTUAL_BOY", Algorithm.WHOLE),
        Console(29, "MSX", Algorithm.WHOLE_M3U),
        Console(30, "COMMODORE_64", Algorithm.WHOLE_M3U),
        Console(31, "ZX81", Algorithm.NONE),
        Console(32, "ORIC", Algorithm.WHOLE),
        Console(33, "SG1000", Algorithm.WHOLE),
        Console(34, "VIC20", Algorithm.NONE),
        Console(35, "AMIGA", Algorithm.NONE),
        Console(36, "ATARI_ST", Algorithm.NONE),
        Console(37, "AMSTRAD_PC", Algorithm.WHOLE_M3U),
        Console(38, "APPLE_II", Algorithm.WHOLE_M3U),
        Console(39, "SATURN", Algorithm.DISC),
        Console(40, "DREAMCAST", Algorithm.DISC),
        Console(41, "PSP", Algorithm.DISC),
        Console(42, "CDI", Algorithm.NONE),
        Console(43, "3DO", Algorithm.DISC),
        Console(44, "COLECOVISION", Algorithm.WHOLE),
        Console(45, "INTELLIVISION", Algorithm.WHOLE),
        Console(46, "VECTREX", Algorithm.WHOLE),
        Console(47, "PC8800", Algorithm.WHOLE_M3U),
        Console(48, "PC9800", Algorithm.NONE),
        Console(49, "PCFX", Algorithm.DISC),
        Console(50, "ATARI_5200", Algorithm.NONE),
        Console(51, "ATARI_7800", Algorithm.BUFFERED),
        Console(52, "X68K", Algorithm.NONE),
        Console(53, "WONDERSWAN", Algorithm.WHOLE),
        Console(54, "CASSETTEVISION", Algorithm.NONE),
        Console(55, "SUPER_CASSETTEVISION", Algorithm.BUFFERED),
        Console(56, "NEO_GEO_CD", Algorithm.DISC),
        Console(57, "FAIRCHILD_CHANNEL_F", Algorithm.WHOLE),
        Console(58, "FM_TOWNS", Algorithm.NONE),
        Console(59, "ZX_SPECTRUM", Algorithm.WHOLE),
        Console(60, "GAME_AND_WATCH", Algorithm.NONE),
        Console(61, "NOKIA_NGAGE", Algorithm.NONE),
        Console(62, "NINTENDO_3DS", Algorithm.ENCRYPTED),
        Console(63, "SUPERVISION", Algorithm.WHOLE),
        // The extension table sends .2d here and .fd, .k7, .m5, .m7 and .sap to
        // the Thomson below, and rc_hash_from_file then has a case for neither.
        Console(64, "SHARPX1", Algorithm.NONE),
        Console(65, "TIC80", Algorithm.WHOLE),
        Console(66, "THOMSONTO8", Algorithm.NONE),
        Console(67, "PC6000", Algorithm.NONE),
        Console(68, "PICO", Algorithm.NONE),
        Console(69, "MEGADUCK", Algorithm.WHOLE),
        Console(70, "ZEEBO", Algorithm.NONE),
        Console(71, "ARDUBOY", Algorithm.ROM),
        Console(72, "WASM4", Algorithm.WHOLE),
        Console(73, "ARCADIA_2001", Algorithm.WHOLE),
        Console(74, "INTERTON_VC_4000", Algorithm.WHOLE),
        Console(75, "ELEKTOR_TV_GAMES_COMPUTER", Algorithm.WHOLE),
        Console(76, "PC_ENGINE_CD", Algorithm.DISC),
        Console(77, "ATARI_JAGUAR_CD", Algorithm.DISC),
        Console(78, "NINTENDO_DSI", Algorithm.ROM),
        Console(79, "TI83", Algorithm.WHOLE),
        Console(80, "UZEBOX", Algorithm.WHOLE),
        Console(81, "FAMICOM_DISK_SYSTEM", Algorithm.BUFFERED)
    )

    private val BY_ID: Map<Int, Console> = CONSOLES.associateBy { it.id }

    fun console(id: Int): Console? = BY_ID[id]

    /**
     * The classes the library is built without. Both builds, the desktop's
     * script and the Android CMake file, define RC_HASH_NO_ENCRYPTED and
     * nothing else of the kind; the test reads the two files for it.
     */
    val NOT_COMPILED: Set<Algorithm> = setOf(Algorithm.ENCRYPTED)

    /**
     * Consoles rcheevos has an algorithm for and we do not hash with: no row
     * may send a file to one. Today it is the one console whose class is not
     * compiled. An algorithm that is compiled and not to be trusted is held
     * back by being listed here too.
     */
    val HELD_BACK: Set<Int> = setOf(62)

    /** Whether a file may be handed to rcheevos as console [id]. */
    fun canHash(id: Int): Boolean {
        val console = BY_ID[id] ?: return false
        return console.algorithm != Algorithm.NONE && console.algorithm !in NOT_COMPILED &&
               id !in HELD_BACK
    }

    /**
     * What is known of one collection name.
     *
     * [key] is the name as [FuzzyMatch.normalizePlatform] gives it, so that
     * `megadrive` and `genesis` are one row and cannot drift apart.
     * [spellings] are other names the same collection goes by on disk: the
     * ones normalizePlatform already folds onto the key, kept here so that a
     * table made from the rows can list them, and the ones it does not fold,
     * `n3ds` or `vita`, which only this table brings home.
     */
    sealed interface Row {
        val key: String
        val spellings: List<String>
    }

    /** A file of [extensions] larger than [above] bytes is of [console]. */
    data class BySize(val extensions: Set<String>, val above: Long, val console: Int)

    /**
     * A collection rcheevos can hash, as [console].
     *
     * [family] is every console a file of this collection may honestly be:
     * a Game Boy cartridge kept among the Game Boy Advance ones, a Sega CD
     * image among the Mega Drive cartridges. It always holds [console].
     * [overridesByExtension] and [overridesBySize] say which member of the
     * family a file is when its extension or its size settles it, and
     * [alternates] which other console to try once when the first refuses an
     * extension both write. [arcade] sets are hashed by their file name and
     * never opened.
     */
    data class Hashable(
        override val key: String,
        override val spellings: List<String>,
        val console: Int,
        val family: Set<Int>,
        val overridesByExtension: Map<String, Int> = emptyMap(),
        val overridesBySize: List<BySize> = emptyList(),
        val alternates: Map<String, List<Int>> = emptyMap(),
        val arcade: Boolean = false
    ) : Row

    /** RetroAchievements has console [id], and rcheevos cannot hash a file for it. */
    data class NoAlgorithm(
        override val key: String,
        override val spellings: List<String>,
        val id: Int,
        val reason: String
    ) : Row

    /** RetroAchievements has no such console at all. */
    data class NotOnRa(
        override val key: String,
        override val spellings: List<String>,
        val reason: String
    ) : Row

    private const val MIB = 1024L * 1024L

    private fun hashable(
        key: String, console: Int, vararg spellings: String,
        family: Set<Int> = setOf(console),
        byExtension: Map<String, Int> = emptyMap(),
        bySize: List<BySize> = emptyList(),
        alternates: Map<String, List<Int>> = emptyMap()
    ) = Hashable(key, spellings.toList(), console, family, byExtension, bySize, alternates)

    private fun arcade(key: String, vararg spellings: String) =
        Hashable(key, spellings.toList(), console = 27, family = setOf(27), arcade = true)

    private fun noAlgorithm(key: String, id: Int, vararg spellings: String, reason: String? = null) =
        NoAlgorithm(key, spellings.toList(), id,
                    reason ?: "rcheevos has no hashing algorithm for ${BY_ID.getValue(id).constant} (id $id)")

    private fun notOnRa(key: String, vararg spellings: String) =
        NotOnRa(key, spellings.toList(), "RetroAchievements has no console for $key")

    /**
     * The rows. A doubtful name is better left out than given a row that says
     * no: a wrong "not supported" hides a collection for as long as that
     * verdict stands, and a missing row only costs the guess every file got
     * before.
     */
    val ROWS: List<Row> = listOf(
        // ── Nintendo ──
        // A disk of the Famicom Disk System is hashed as a cartridge is, and
        // RetroAchievements lists its games under a console of their own.
        hashable("nes", 7, "famicom", family = setOf(7, 81), byExtension = mapOf("fds" to 81)),
        // Game Boy cartridges are kept with the consoles that play them: the
        // Super Game Boy, the Game Boy Color, the Game Boy Advance.
        hashable("snes", 3, family = setOf(3, 4, 6)),
        hashable("gb", 4, family = setOf(4, 6)),
        hashable("gbc", 6, family = setOf(6, 4)),
        hashable("gba", 5, family = setOf(5, 4, 6)),
        hashable("n64", 2),
        hashable("nds", 18, "ds"),
        hashable("virtualboy", 28),
        hashable("pokemini", 24),
        // Both write .iso, and a disc of one is often filed with the other's:
        // when the collection's own console refuses an .iso, the other is
        // tried once. A .gcm is a GameCube disc wherever it lies.
        hashable("wii", 19, family = setOf(19, 16), byExtension = mapOf("gcm" to 16),
                 alternates = mapOf("iso" to listOf(16))),
        hashable("gc", 16, "gamecube", "ngc", family = setOf(16, 19),
                 alternates = mapOf("iso" to listOf(19))),
        // ── Sega ──
        // A Mega Drive collection is where the games of its add-ons are kept,
        // and those of the 8-bit consoles before it, which it plays through
        // an adapter. A descriptor or an .iso there is a Sega CD disc. So is
        // a .bin or an .img too large for any cartridge: 32 MiB is where
        // rcheevos itself starts taking a .bin for a CD track.
        hashable("genesis", 1, "megadrive", family = setOf(1, 9, 10, 11, 15, 33),
                 byExtension = mapOf("cue" to 9, "iso" to 9, "chd" to 9, "32x" to 10, "sms" to 11),
                 bySize = listOf(BySize(setOf("bin", "img"), 32 * MIB, 9))),
        hashable("sega32x", 10, "32x"),
        hashable("segacd", 9, "megacd"),
        hashable("mastersystem", 11, "sms", family = setOf(11, 15, 33)),
        hashable("gamegear", 15, "gg", family = setOf(15, 11)),
        hashable("sg1000", 33),
        hashable("saturn", 39),
        hashable("dreamcast", 40, "dc"),
        // ── Sony ──
        hashable("psx", 12, "ps1", "psone"),
        hashable("ps2", 21),
        hashable("psp", 41),
        // ── NEC ──
        // rcheevos hashes a CD game from its descriptor alone: handed anything
        // else as console 76 it fails, and handed a descriptor as console 8 it
        // gives the MD5 of the text.
        hashable("pcengine", 8, "tg16", family = setOf(8, 76), byExtension = mapOf("cue" to 76)),
        hashable("pcenginecd", 76, "tgcd"),
        hashable("pcfx", 49),
        // ── Atari ──
        hashable("jaguar", 17, "atarijaguar", family = setOf(17, 77), byExtension = mapOf("cue" to 77)),
        hashable("lynx", 13, "atarilynx"),
        hashable("atari2600", 25),
        hashable("atari7800", 51),
        // ── SNK ──
        hashable("ngp", 14),
        hashable("ngpc", 14),
        // ── The rest ──
        hashable("3do", 43),
        // The Adam ran ColecoVision cartridges. Console 44 is the MD5 of the
        // whole file, which is the hash such a file had before anything was
        // told a console.
        hashable("adam", 44),
        hashable("colecovision", 44),
        hashable("amstradcpc", 37, "cpc"),
        hashable("apple2", 38),
        hashable("arcadia", 73),
        hashable("arduboy", 71),
        hashable("c64", 30, "commodore64"),
        hashable("channelf", 57),
        hashable("msx", 29),
        hashable("msx2", 29),
        hashable("supervision", 63, "watara"),
        hashable("vectrex", 46),
        hashable("intellivision", 45),
        hashable("wonderswan", 53),
        hashable("wonderswancolor", 53),
        hashable("zxspectrum", 59, "spectrum"),
        // ── Arcade: a set is known by its name ──
        arcade("arcade", "mame", "fbneo", "fba"),
        arcade("atomiswave"),
        arcade("neogeo"),
        arcade("naomi"),
        arcade("cps1"),
        arcade("cps2"),
        arcade("cps3"),
        // ── A console at RetroAchievements, and no way to hash for it ──
        noAlgorithm("amiga", 35),
        noAlgorithm("cdimono1", 42, "cdi"),
        noAlgorithm("wiiu", 20),
        noAlgorithm("3ds", 62, "n3ds",
                    reason = "rcheevos' algorithm for RC_CONSOLE_NINTENDO_3DS (id 62) is not built in: " +
                             "it needs decryption keys"),
        // ── No console at RetroAchievements ──
        notOnRa("switch"),
        notOnRa("psvita", "vita"),
        notOnRa("ps3"),
        notOnRa("bbcmicro"),
        notOnRa("chailove"),
        notOnRa("cdtv"),
        notOnRa("pc"),
        notOnRa("windows"),
        notOnRa("android"),
        notOnRa("ios")
    )

    private val BY_NAME: Map<String, Row> = HashMap<String, Row>().apply {
        for (row in ROWS) {
            put(row.key, row)
            for (spelling in row.spellings) put(FuzzyMatch.normalizePlatform(spelling), row)
        }
    }

    /** The row [name] has, in whatever spelling a collection or a folder writes it. */
    fun row(name: String?): Row? = BY_NAME[FuzzyMatch.normalizePlatform(name.orEmpty())]

    /**
     * The row for a collection that declares [shortName] and is kept in a
     * folder called [dirName].
     *
     * The short name is the collection's identity and comes first. But a
     * folder `segacd` may declare `megadrive`, because that is the emulator
     * its games are launched with, and then the folder knows better than the
     * short name what a small `.bin` in it is. So the folder's row is taken
     * when the short name's family has the folder's console, and only then:
     * a folder `neogeo` that declares `ngpc` holds Neo Geo Pocket cartridges,
     * not arcade sets. A short name with no row leaves it to the folder.
     */
    fun resolve(shortName: String?, dirName: String?): Row? {
        val declared = row(shortName)
        val folder = row(dirName)
        if (declared == null) return folder
        if (declared is Hashable && folder is Hashable && folder.console in declared.family) return folder
        return declared
    }

    /**
     * [row] on one line, the same line for the same row however it was
     * written down: what a verdict reached with the row depends on, so that
     * a change to the row can be told from none. The words of a reason are
     * not in it. Sets and maps are sorted; the size rules and the alternates
     * of an extension are lists tried in order, and keep theirs.
     */
    fun describe(row: Row?): String = when (row) {
        null -> "unknown"
        is NotOnRa -> "${row.key} not on RetroAchievements"
        is NoAlgorithm -> "${row.key}=${row.id} no algorithm"
        is Hashable -> buildString {
            append(row.key).append('=').append(row.console)
            append(" family[").append(row.family.sorted().joinToString(",")).append(']')
            if (row.overridesByExtension.isNotEmpty())
                append(" ext[").append(row.overridesByExtension.toSortedMap().entries
                    .joinToString(",") { "${it.key}:${it.value}" }).append(']')
            if (row.overridesBySize.isNotEmpty())
                append(" size[").append(row.overridesBySize
                    .joinToString(",") { "${it.extensions.sorted().joinToString("+")}>${it.above}:${it.console}" })
                    .append(']')
            if (row.alternates.isNotEmpty())
                append(" alt[").append(row.alternates.toSortedMap().entries
                    .joinToString(",") { "${it.key}:${it.value.joinToString("+")}" }).append(']')
            if (row.arcade) append(" arcade")
        }
    }
}
