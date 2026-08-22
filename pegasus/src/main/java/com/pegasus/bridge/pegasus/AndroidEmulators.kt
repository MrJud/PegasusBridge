package com.pegasus.bridge.pegasus

import android.content.pm.PackageManager
import com.pegasus.bridge.core.BridgeLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * What emulators are installed on this device.
 *
 * The Android answer to [EmulatorDiscovery], which is not compiled into the APK
 * and could not be: it reads `$PATH`, runs `flatpak list` and executes binaries
 * to ask their version, and none of those three things exists here. What is
 * shared is everything downstream — [EmulatorCandidate], [EmulatorRanking] and
 * the JSON — so a theme reads one shape on both shells.
 *
 * ── Three ways this is *better* than the desktop's, and one way it is worse ──
 *
 * Better, because the package manager is authoritative. The desktop has to
 * execute a binary to find out whether the thing named `snes9x` on `$PATH` is
 * Snes9x, and one of the emulators it probed answered `--version` by starting
 * up and opening an audio device. Here, a package either is installed or is
 * not, and it states its own version without being run. Every candidate this
 * produces is therefore [EmulatorCandidate.verified], and nothing is executed
 * to establish it.
 *
 * Worse, in one specific place: whether an emulator can actually *read* the
 * library. On the desktop that is answerable — run `sh` inside the Flatpak and
 * try. Here it depends on whether the other app holds `MANAGE_EXTERNAL_STORAGE`,
 * and reading another package's granted app-ops needs `GET_APP_OPS_STATS`,
 * which is a signature permission this app has no business holding. So
 * [EmulatorCandidate.canReadLibrary] is left null and
 * [EmulatorCandidate.readabilityUnknownBecause] says why, rather than a guess
 * being dressed up as a measurement.
 *
 * ── Package visibility ──
 *
 * From API 30 an app cannot see arbitrary packages. Every package named in
 * [PROBES] is therefore declared in this module's manifest under `<queries>`,
 * and `AndroidEmulatorsManifestTest` fails the build if the two lists ever
 * disagree — which they would, silently, the first time somebody adds a probe
 * and forgets, and the symptom would be an emulator that is installed and
 * invisible.
 */
object AndroidEmulators {

    /**
     * One emulator this knows how to look for.
     *
     * [packages] is in preference order, because the same emulator ships under
     * several ids — RetroArch alone has a 64-bit build, a 32-bit build and an
     * aarch64 build, and a device can have more than one.
     */
    data class Probe(
        val id: String,
        val displayName: String,
        val platforms: List<String>,
        val packages: List<String>,
        /** The activity, in the form `am start -n <package>/<this>` wants. */
        val component: String,
        /**
         * The lines between the component and the trailing activity flags.
         *
         * Written out as Pegasus writes them, one argument per line, because
         * that is the form these were read from: every template here was taken
         * from a `metadata.pegasus.txt` that launches on a real device, not from
         * documentation.
         */
        val args: List<String>,
        /**
         * Where this launch line came from.
         *
         * Not decoration. Most of these were transcribed from a
         * `metadata.pegasus.txt` that launches on a real device, and a few were
         * written from documentation and have never been run. The difference is
         * the difference between a proposal somebody can accept and one they
         * should try first, and it is exactly the distinction the desktop's
         * `verified` makes about the *emulator*. This makes it about the
         * *command*, which is the part that can be wrong while the package is
         * perfectly installed.
         *
         * Earned the hard way: Lime3DS was first given
         * `.features.settings.ui.SettingsActivity` — a real class, and the
         * settings screen. The package manager reported the app installed and
         * verified, discovery proposed it confidently, and the launch would
         * have opened preferences instead of a game. Asking the device settled
         * it: Lime3DS is a Citra fork and keeps Citra's class names.
         */
        val provenance: Provenance,
        /** Conventional libretro cores, when [args] leaves a `{core}` behind. */
        val coreHints: List<String> = emptyList(),
        /**
         * Whether [displayName] was chosen deliberately rather than carried as
         * a fallback.
         *
         * The built-in table's names are fallbacks: the app's own label is the
         * better answer, which is how `io.github.lime3ds.android` correctly
         * shows as Azahar. A name written in `emulators.json` is not a
         * fallback — somebody typed it — so it wins over the label instead.
         */
        val nameIsExplicit: Boolean = false
    )

    /** How much is actually known about a probe's launch line. */
    enum class Provenance(val describe: String) {
        /** Read back from the device's own package manager. */
        ON_THIS_DEVICE("component confirmed on this device"),
        /** Transcribed from a metadata file that launches games today. */
        WORKING_LIBRARY("launch line taken from a working library"),
        /** Written from documentation. Plausible, never run. */
        UNVERIFIED("launch line from documentation, never run here"),
        /**
         * Supplied by whoever owns the device, in `config/emulators.json`.
         *
         * Trusted as far as it goes and no further: nobody here has run it
         * either. It is separated from [UNVERIFIED] so a review screen can say
         * "this is yours" rather than "this came from documentation", which are
         * different things to a person deciding whether to accept a proposal.
         */
        USER_SUPPLIED("launch line from your emulators.json"),
        /**
         * Installed, and there is no known way to hand it a game.
         *
         * Not a gap in this table — a property of the emulator. Linkboy exports
         * one activity, `VIEW` on its own `linkboy://` scheme and `MAIN`, and
         * nothing for `file://` or `content://`; Egg NS the same with
         * `gamesir://`. Both keep their own libraries. Recognising them is still
         * worth doing, because "we can see it and cannot drive it" is a
         * different answer from silence — which is what they got until somebody
         * pointed out that his GBA emulator was missing.
         */
        NO_KNOWN_LAUNCH("installed, but it declares no way to be handed a game")
    }

    /**
     * The emulators, and the launch line each one wants.
     *
     * The templates are transcribed from a working Android library rather than
     * invented: `{file.path}`, `{file.uri}` and `{file.documenturi}` are not
     * interchangeable, and which one an emulator accepts is a property of the
     * emulator that only trying it establishes. DraStic takes a `file://` URI
     * and PPSSPP a document URI; giving either the other's produces a launch
     * that opens the emulator on no game at all.
     */
    val PROBES: List<Probe> = listOf(
        Probe("retroarch", "RetroArch",
            listOf("nes", "snes", "n64", "gb", "gbc", "gba", "genesis", "mastersystem",
                   "gamegear", "psx", "pcengine", "atari2600", "atari7800", "lynx",
                   "wonderswan", "ngp", "virtualboy", "colecovision", "msx", "arcade",
                   "segacd", "sega32x", "neogeo", "3ds", "nds", "dreamcast"),
            packages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32"),
            component = ".browser.retroactivity.RetroActivityFuture",
            // A libretro core is required and is per-platform, so this is a
            // template with a hole in it rather than a runnable line — the same
            // hole the desktop leaves, for the same reason.
            args = listOf(
                "-e ROM {file.path}",
                "-e LIBRETRO {core}",
                "-e CONFIGFILE /storage/emulated/0/Android/data/{package}/files/retroarch.cfg",
                "-e QUITFOCUS"),
            provenance = Provenance.WORKING_LIBRARY),

        Probe("ppsspp", "PPSSPP", listOf("psp"),
            packages = listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"),
            component = ".PpssppActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.documenturi}\""),
            provenance = Provenance.ON_THIS_DEVICE),

        Probe("drastic", "DraStic", listOf("nds"),
            packages = listOf("com.dsemu.drastic"),
            component = ".DraSticActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.ON_THIS_DEVICE),

        Probe("melonds", "melonDS", listOf("nds"),
            packages = listOf("me.magnum.melonds"),
            component = ".ui.romlist.RomListActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.UNVERIFIED),

        Probe("duckstation", "DuckStation", listOf("psx"),
            packages = listOf("com.github.stenzek.duckstation"),
            component = ".EmulationActivity",
            args = listOf("-e bootPath \"{file.path}\"", "--ez resumeState 0"),
            provenance = Provenance.WORKING_LIBRARY),

        Probe("dolphin", "Dolphin", listOf("gc", "wii"),
            packages = listOf("org.dolphinemu.dolphinemu"),
            component = ".ui.main.MainActivity",
            args = listOf("-a android.intent.action.VIEW", "-e AutoStartFile \"{file.path}\""),
            provenance = Provenance.WORKING_LIBRARY),

        Probe("aethersx2", "AetherSX2", listOf("ps2"),
            packages = listOf("xyz.aethersx2.android"),
            component = ".EmulationActivity",
            args = listOf("-a android.intent.action.MAIN", "-e bootPath \"{file.documenturi}\""),
            provenance = Provenance.WORKING_LIBRARY),

        Probe("flycast", "Flycast", listOf("dreamcast", "atomiswave", "naomi"),
            packages = listOf("com.flycast.emulator"),
            component = "com.reicast.emulator.MainActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.WORKING_LIBRARY),

        // A Citra fork, and it kept Citra's package names inside its own
        // application id — so the component is `org.citra.…` even though the
        // app is `io.github.lime3ds.android`. Read off the tablet, not guessed.
        Probe("lime3ds", "Lime3DS", listOf("3ds", "n3ds"),
            packages = listOf("io.github.lime3ds.android", "org.citra.citra_emu"),
            component = "org.citra.citra_emu.activities.EmulationActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.ON_THIS_DEVICE),

        Probe("citron", "Citron", listOf("switch"),
            packages = listOf("org.citron.citron_emu"),
            component = ".activities.EmulationActivity",
            args = listOf("-a android.intent.action.VIEW", "-d {file.uri}"),
            provenance = Provenance.WORKING_LIBRARY),

        Probe("snes9xplus", "Snes9x EX+", listOf("snes"),
            packages = listOf("com.explusalpha.Snes9xPlus"),
            component = "com.imagine.BaseActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.WORKING_LIBRARY),

        Probe("mdemu", "MD.emu", listOf("genesis", "segacd", "sega32x", "mastersystem"),
            packages = listOf("com.explusalpha.MdEmu"),
            component = "com.imagine.BaseActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.WORKING_LIBRARY),

        Probe("nesemu", "NES.emu", listOf("nes"),
            packages = listOf("com.explusalpha.NesEmu"),
            component = "com.imagine.BaseActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.WORKING_LIBRARY),

        Probe("gbcemu", "GBC.emu", listOf("gb", "gbc"),
            packages = listOf("com.explusalpha.GbcEmu"),
            component = "com.imagine.BaseActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.WORKING_LIBRARY),

        Probe("gbaemu", "GBA.emu", listOf("gba"),
            packages = listOf("com.explusalpha.GbaEmu"),
            component = "com.imagine.BaseActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.UNVERIFIED),

        Probe("mupen64plusfz", "Mupen64Plus FZ", listOf("n64"),
            packages = listOf("org.mupen64plusae.v3.fzurita",
                              "org.mupen64plusae.v3.fzurita.pro"),
            component = "paulscode.android.mupen64plusae.SplashActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.UNVERIFIED),

        Probe("redream", "Redream", listOf("dreamcast"),
            packages = listOf("io.recompiled.redream"),
            component = ".MainActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.UNVERIFIED),

        Probe("eka2l1", "EKA2L1", listOf("symbian", "ngage"),
            packages = listOf("com.github.eka2l1"),
            component = ".emu.EmulatorActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.UNVERIFIED),

        Probe("vita3k", "Vita3K", listOf("psvita", "vita"),
            packages = listOf("org.vita3k.emulator"),
            component = ".Emulator",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.UNVERIFIED),

        /*
         * Linkboy takes a game by *title*, not by path.
         *
         * Reading its manifest said it could not be driven at all: one exported
         * activity, `VIEW` on `linkboy://` and `MAIN`, nothing accepting a file.
         * That reading was wrong twice over — an explicit component bypasses
         * intent filters anyway, and the scheme turned out to be a real route
         * with a host, `linkboy://emulator/`.
         *
         * What it wants in that segment took four tries against the device. Not
         * the absolute path, not a `content://` document URI from
         * externalstorage — both of those it parses and echoes back with "could
         * not find game". It keeps its own library, added folder by folder
         * through SAF, and looks a game up by the name it lists: the filename
         * with the extension taken off. `{file.basename}` is exactly that, so
         * the line needs no percent-encoding and no help from the Bridge.
         *
         * No `-n`: the implicit form on its own scheme is what was verified
         * working, and it is the app's documented door.
         */
        Probe("linkboy", "Linkboy", listOf("gba", "gb", "gbc"),
            packages = listOf("com.pixelrespawn.linkboy"),
            component = "",
            args = listOf("-a android.intent.action.VIEW",
                          "-d \"linkboy://emulator/{file.basename}\""),
            provenance = Provenance.ON_THIS_DEVICE),

        Probe("eggns", "Egg NS", listOf("switch"),
            packages = listOf("com.xiaoji.egggame"),
            component = "com.xj.app.DeepLinkRouterActivity",
            args = emptyList(),
            provenance = Provenance.NO_KNOWN_LAUNCH),

        Probe("mgba", "mGBA", listOf("gba", "gb", "gbc"),
            packages = listOf("io.mgba"),
            component = ".GameActivity",
            args = listOf("-a android.intent.action.VIEW", "-d \"{file.uri}\""),
            provenance = Provenance.UNVERIFIED)
    )

    /**
     * Conventional libretro core filenames, per normalised platform.
     *
     * Hints for the `{core}` RetroArch leaves behind. Marked as hints all the
     * way through — [EmulatorCandidate.coreHints], not `cores` — because the
     * core directory is app-private and nothing here has looked inside it.
     *
     * Bare filenames here; [coreHintsFor] turns them into the absolute paths
     * RetroArch actually wants. The one launch line in the library that runs
     * RetroArch today spells it in full —
     * `-e LIBRETRO /data/data/com.retroarch/cores/genesis_plus_gx_wide_libretro_android.so`
     * — and a bare name was offered until that line was read back.
     */
    private val CORE_HINTS: Map<String, List<String>> = mapOf(
        "nes" to listOf("fceumm_libretro_android.so", "nestopia_libretro_android.so"),
        "snes" to listOf("snes9x_libretro_android.so", "bsnes_libretro_android.so"),
        "n64" to listOf("mupen64plus_next_libretro_android.so", "parallel_n64_libretro_android.so"),
        "gb" to listOf("gambatte_libretro_android.so"),
        "gbc" to listOf("gambatte_libretro_android.so"),
        "gba" to listOf("mgba_libretro_android.so", "vba_next_libretro_android.so"),
        "nds" to listOf("melonds_libretro_android.so", "desmume_libretro_android.so"),
        "genesis" to listOf("genesis_plus_gx_libretro_android.so", "picodrive_libretro_android.so"),
        "segacd" to listOf("genesis_plus_gx_libretro_android.so", "picodrive_libretro_android.so"),
        "sega32x" to listOf("picodrive_libretro_android.so"),
        "mastersystem" to listOf("genesis_plus_gx_libretro_android.so"),
        "gamegear" to listOf("genesis_plus_gx_libretro_android.so"),
        "psx" to listOf("swanstation_libretro_android.so", "pcsx_rearmed_libretro_android.so"),
        "pcengine" to listOf("mednafen_pce_fast_libretro_android.so"),
        "atari2600" to listOf("stella_libretro_android.so"),
        "atari7800" to listOf("prosystem_libretro_android.so"),
        "lynx" to listOf("handy_libretro_android.so"),
        "wonderswan" to listOf("mednafen_wswan_libretro_android.so"),
        "ngp" to listOf("mednafen_ngp_libretro_android.so"),
        "virtualboy" to listOf("mednafen_vb_libretro_android.so"),
        "colecovision" to listOf("bluemsx_libretro_android.so"),
        "msx" to listOf("bluemsx_libretro_android.so"),
        "arcade" to listOf("fbneo_libretro_android.so", "mame2003_plus_libretro_android.so"),
        "neogeo" to listOf("fbneo_libretro_android.so"),
        "3ds" to listOf("citra_libretro_android.so"),
        "dreamcast" to listOf("flycast_libretro_android.so")
    )

    /**
     * Every package the *built-in* table looks for — the input to the manifest
     * check. Deliberately not including anything from `emulators.json`: the
     * manifest is fixed at build time and cannot know about a file written
     * afterwards, which is the whole point of [visibilityWarning].
     */
    val KNOWN_PACKAGES: Set<String> = PROBES.flatMap { it.packages }.toSet()

    // ── emulators.json ──────────────────────────────────────────────────────

    const val CONFIG_FILE = "emulators.json"
    const val CONFIG_SCHEMA_VERSION = 1

    /** What reading the file produced, including what it refused and why. */
    data class Config(
        val probes: List<Probe> = emptyList(),
        val rejected: List<String> = emptyList(),
        /**
         * Entries that were kept, with something wrong worth saying out loud.
         *
         * Separate from [rejected] because they are not the same fact and were
         * once reported as one: a `{core}` nobody can fill used to be listed
         * under "rejected" while the entry was added anyway, which told the
         * reader their emulator had been dropped when it had not.
         */
        val warnings: List<String> = emptyList(),
        val note: String = "",
        /**
         * Cores by platform, from the file's top-level `coreHints`.
         *
         * Separate from a probe's own [Probe.coreHints] because a core is a
         * property of the *platform*, not of the emulator: RetroArch needs a
         * different one for every system it runs, so a flat per-emulator list
         * cannot express what it needs. Keyed by normalised platform name, and
         * a platform named here replaces the built-in list rather than adding
         * to it — the reason to write one is usually that the built-in is
         * wrong, not that it is missing.
         */
        val coreHints: Map<String, List<String>> = emptyMap()
    )

    /**
     * The emulator table, with `config/emulators.json` merged over it.
     *
     * An entry whose `id` matches a built-in **replaces** it — that is how a
     * wrong launch line gets corrected without waiting for a new APK. An
     * unknown id is added. Order is preserved so a replacement keeps the
     * built-in's position rather than jumping to the end of a review screen.
     *
     * A bad entry is dropped and named; it never takes the rest of the file
     * with it. A file that is entirely unreadable leaves the built-in table
     * exactly as it was, because the alternative — no emulators at all — is a
     * worse answer to a misplaced comma.
     */
    fun probesWith(config: Config): List<Probe> {
        if (config.probes.isEmpty()) return PROBES
        val byId = LinkedHashMap<String, Probe>()
        for (p in PROBES) byId[p.id] = p
        for (p in config.probes) byId[p.id] = p
        return byId.values.toList()
    }

    /**
     * Parses `emulators.json`.
     *
     * Shape, with only `id`, `platforms`, `packages` required — an entry with
     * no `args` is one that can be recognised but not driven, which is a legal
     * thing to want to record:
     *
     *     {
     *       "schemaVersion": 1,
     *       "coreHints": { "3do": ["opera_libretro_android.so"] },
     *       "emulators": [
     *         { "id": "myboy", "displayName": "My Boy!",
     *           "platforms": ["gba"], "packages": ["com.fastemulator.gba"],
     *           "component": ".EmulatorActivity",
     *           "args": ["-a android.intent.action.VIEW", "-d \"{file.uri}\""],
     *           "coreHints": [] }
     *       ]
     *     }
     *
     * The top-level `coreHints` is keyed by platform and is the one that can
     * teach this build a core it was compiled without — `3do` and `apple2` had
     * no entry in [CORE_HINTS], and before this existed neither could be given
     * one without a new APK. An emulator's own `coreHints` is a fallback for
     * when nothing is keyed by platform; see [coreHintsFor] for the order.
     */
    fun parseConfig(text: String): Config {
        val rejected = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val root = runCatching { JSONObject(text) }.getOrElse {
            return Config(note = "emulators.json is not valid JSON: ${it.message}")
        }
        val schema = root.optInt("schemaVersion", CONFIG_SCHEMA_VERSION)
        if (schema > CONFIG_SCHEMA_VERSION)
            return Config(note = "emulators.json is schema $schema and this build reads " +
                                 "$CONFIG_SCHEMA_VERSION — left alone rather than half-read")

        // Read before the emulators, because whether an entry's `{core}` can
        // ever be filled depends on what this map says.
        val hints = LinkedHashMap<String, List<String>>()
        root.optJSONObject("coreHints")?.let { obj ->
            for (key in obj.keys()) {
                val platform = com.pegasus.bridge.core.FuzzyMatch.normalizePlatform(key)
                if (platform.isEmpty()) { rejected += "coreHints key '$key' is not a platform"; continue }
                val cores = obj.optJSONArray(key).toStringList()
                if (cores.isEmpty()) {
                    rejected += "coreHints for '$key' names no cores — " +
                                "remove the key to keep the built-in list, or name one"
                    continue
                }
                hints[platform] = cores
            }
        }

        val arr = root.optJSONArray("emulators") ?: JSONArray()
        val out = mutableListOf<Probe>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { rejected += "entry $i is not an object"; continue }
            val id = o.optString("id").trim()
            if (id.isEmpty()) { rejected += "entry $i has no id"; continue }
            val packages = o.optJSONArray("packages").toStringList()
            if (packages.isEmpty()) { rejected += "'$id' names no packages"; continue }
            val platforms = o.optJSONArray("platforms").toStringList()
                .map { com.pegasus.bridge.core.FuzzyMatch.normalizePlatform(it) }
                .filter { it.isNotEmpty() }
            if (platforms.isEmpty()) { rejected += "'$id' names no platforms"; continue }
            val args = o.optJSONArray("args").toStringList()
            val ownHints = o.optJSONArray("coreHints").toStringList()
            // A `{core}` nothing can fill would be written into somebody's
            // library as a literal. "Nothing" means all three sources: the
            // entry's own hints, the file's per-platform map, and the built-in
            // table — a corrected RetroArch line needs none of its own, because
            // the platforms it names are already covered.
            if (args.any { it.contains("{core}") } && ownHints.isEmpty() &&
                platforms.none { hints[it]?.isNotEmpty() == true || CORE_HINTS[it]?.isNotEmpty() == true })
                warnings += "'$id' leaves {core} in its args and nothing names a core for " +
                            platforms.joinToString("/") + " — add a top-level coreHints entry " +
                            "for one of those platforms, or coreHints to '$id' itself"
            out += Probe(
                id = id,
                displayName = o.optString("displayName").ifEmpty { id },
                nameIsExplicit = o.optString("displayName").isNotEmpty(),
                platforms = platforms,
                packages = packages,
                component = o.optString("component"),
                args = args,
                provenance = if (args.isEmpty()) Provenance.NO_KNOWN_LAUNCH
                             else Provenance.USER_SUPPLIED,
                coreHints = ownHints)
        }
        return Config(probes = out, rejected = rejected, warnings = warnings, coreHints = hints)
    }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList()
        else (0 until length()).map { optString(it).trim() }.filter { it.isNotEmpty() }

    /**
     * Why an emulator added to `emulators.json` may still not be found.
     *
     * From API 30 a package the manifest does not name in `<queries>` is
     * invisible, and `getPackageInfo` throws the same exception for it as for
     * one that is genuinely absent. The built-in table's packages are all
     * declared; a package added afterwards cannot be, because the manifest was
     * fixed when the APK was built. Null when there is nothing to warn about.
     */
    fun visibilityWarning(config: Config, canSeeAllPackages: Boolean = false): String? {
        // With QUERY_ALL_PACKAGES held there is nothing to warn about, and a
        // warning about a solved problem is noise a reader learns to skip.
        if (canSeeAllPackages) return null
        val added = config.probes.flatMap { it.packages }.filter { it !in KNOWN_PACKAGES }
        if (added.isEmpty()) return null
        return "emulators.json names ${added.size} package(s) the app's manifest does not " +
               "declare under <queries>: ${added.joinToString(", ")}. Android hides undeclared " +
               "packages from API 30, so these can be installed and still not be found. This " +
               "build does not hold QUERY_ALL_PACKAGES, which is what covers them."
    }

    /**
     * Every emulator installed on this device, most trustworthy first.
     *
     * [installed] is injected so the whole thing is testable with no emulator
     * present — the same reason the desktop injects its `pathDirs` and `runner`.
     * It answers a version string for a package that is installed, and null for
     * one that is not.
     */
    fun discover(
        installed: (String) -> String? = { null },
        platform: String? = null,
        /** `emulators.json`, already parsed. Empty leaves the built-in table alone. */
        config: Config = Config(),
        /**
         * What the installed app calls itself, when that can be asked.
         *
         * Worth asking. `io.github.lime3ds.android` is labelled **Azahar** on
         * this tablet: Azahar is what Lime3DS became, and it kept the package id
         * so installs would carry over. The probe table said "Lime3DS", which
         * was recognised correctly and named wrongly, and the person looking for
         * Azahar reasonably concluded it had been missed. The app's own label is
         * the answer that stays right when a project renames itself.
         */
        labelOf: (String) -> String? = { null },
        /**
         * The activity a launcher would start for a package, when it has one.
         *
         * Only used for an emulator that cannot be handed a game: opening it on
         * its own library is the whole of what can be offered, and the activity
         * has to come from the system because these are exactly the packages
         * the table knows no component for.
         */
        launcherOf: (String) -> String? = { null }
    ): List<EmulatorCandidate> {
        val out = mutableListOf<EmulatorCandidate>()
        for (probe in probesWith(config)) {
            for (pkg in probe.packages) {
                val version = runCatching { installed(pkg) }.getOrNull() ?: continue
                out += candidate(probe, pkg, version, platform,
                                 runCatching { labelOf(pkg) }.getOrNull(), config,
                                 runCatching { launcherOf(pkg) }.getOrNull())
                // One candidate per emulator, not one per package: two RetroArch
                // builds are two ways to run the same thing, and offering both
                // asks somebody to choose between them on no information.
                break
            }
        }
        return EmulatorRanking.overall(out)
    }

    /** The same thing, against a real [PackageManager]. */
    fun discover(
        pm: PackageManager,
        platform: String? = null,
        config: Config = Config()
    ): List<EmulatorCandidate> =
        discover(installed = { pkg -> versionOf(pm, pkg) },
                 platform = platform,
                 config = config,
                 labelOf = { pkg -> labelOf(pm, pkg) },
                 launcherOf = { pkg -> launcherOf(pm, pkg) })

    /** The activity a launcher would start for [pkg], or null when it has none. */
    fun launcherOf(pm: PackageManager, pkg: String): String? = try {
        pm.getLaunchIntentForPackage(pkg)?.component?.className
    } catch (t: Throwable) {
        null
    }

    /** What [pkg] calls itself, or null when it cannot be asked. */
    fun labelOf(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /**
     * The installed version of [pkg], or null when it is not installed.
     *
     * A package with no `versionName` — which is legal — still counts as
     * installed and answers the empty string, because "installed and did not
     * say which version" is a different fact from "not installed" and the two
     * must not collapse into one.
     */
    fun versionOf(pm: PackageManager, pkg: String): String? = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0).versionName.orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (t: Throwable) {
        // A package that is filtered out by visibility rules throws the same
        // NameNotFoundException, so this catches only the genuinely unexpected.
        BridgeLog.w(TAG, "could not ask about $pkg: ${t.message}")
        null
    }

    private fun candidate(
        probe: Probe,
        pkg: String,
        version: String,
        platform: String?,
        label: String? = null,
        config: Config = Config(),
        launcherActivity: String? = null
    ): EmulatorCandidate {
        val hints = coreHintsFor(probe, pkg, platform, config)

        return EmulatorCandidate(
            id = probe.id,
            // A name somebody wrote in emulators.json wins; otherwise the app's
            // own label, which is how Lime3DS correctly shows as Azahar; and the
            // table's name only as a last resort.
            displayName = if (probe.nameIsExplicit) probe.displayName
                          else label?.takeIf { it.isNotBlank() } ?: probe.displayName,
            platforms = probe.platforms,
            executable = pkg,
            launchCommand = if (probe.provenance == Provenance.NO_KNOWN_LAUNCH) ""
                            else launchCommand(probe, pkg),
            kind = EmulatorKind.ANDROID_PACKAGE,
            // Always true here, and it costs nothing: the package manager states
            // the version without the package being run. See the class comment.
            verified = true,
            version = version,
            confidence = "installed package $pkg" +
                (if (label != null && !label.equals(probe.displayName, true))
                     " (known here as ${probe.displayName})" else "") +
                " — ${probe.provenance.describe}",
            canReadLibrary = null,
            readabilityUnknownBecause =
                "whether $pkg can read the library depends on storage permissions " +
                "granted to it, and reading another package's app-ops needs " +
                "GET_APP_OPS_STATS, which is a signature permission",
            coreHints = if (probe.provenance != Provenance.NO_KNOWN_LAUNCH &&
                            launchCommand(probe, pkg).contains("{core}")) hints else emptyList(),
            launchVerified = probe.provenance == Provenance.ON_THIS_DEVICE ||
                             probe.provenance == Provenance.WORKING_LIBRARY,
            // Only for the ones that cannot be handed a game. An emulator with a
            // real launch line has no use for this, and offering both would
            // invite a caller to pick the wrong one.
            appLaunchCommand =
                if (probe.provenance == Provenance.NO_KNOWN_LAUNCH && !launcherActivity.isNullOrBlank())
                    appLaunchCommand(pkg, launcherActivity) else ""
        )
    }

    /**
     * The cores to offer for [probe], as absolute paths under [pkg]'s own
     * private core directory — which is where RetroArch keeps them and where
     * the library's one working RetroArch line points.
     *
     * Narrowed to [platform] when the caller named one; otherwise every core
     * the probe's platforms could want, which is what a general listing needs.
     */
    /**
     * The conventional cores for [emulatorId] on [platform], most usual first.
     *
     * Public because a batch link resolves one collection at a time and must ask
     * per platform. Asking the platform-less [discover] instead returns the union
     * across everything RetroArch handles, whose first entry is the NES core —
     * which is how `gba` and `n64` were both once offered `fceumm`.
     *
     * Takes [config] because the answer can now come out of `emulators.json`,
     * and because an emulator that only exists in that file is not in [PROBES]
     * at all — asking the built-in table about it returned nothing.
     */
    fun coreHintsFor(emulatorId: String, pkg: String, platform: String,
                     config: Config = Config()): List<String> =
        probesWith(config).firstOrNull { it.id == emulatorId }
            ?.let { coreHintsFor(it, pkg, platform, config) }.orEmpty()

    /**
     * The cores to offer for [probe], as absolute paths under [pkg]'s own
     * private core directory — which is where RetroArch keeps them and where
     * the library's one working RetroArch line points.
     *
     * When [platform] is named the answer is that platform's, in order: the
     * file's `coreHints`, then the built-in [CORE_HINTS], then the probe's own.
     * **A platform with no core anywhere answers nothing.** It used to fall
     * through to the union instead, so asking for 3DO's core returned all 27
     * built-in cores headed by the NES one — "no hints for this platform" and
     * "no platform named" gave the same answer, and the first of them was a
     * core that cannot load a 3DO disc.
     *
     * With no [platform] the union is still right: that is a general listing,
     * and it is what a review screen showing every emulator wants.
     */
    private fun coreHintsFor(probe: Probe, pkg: String, platform: String?,
                             config: Config = Config()): List<String> {
        val names = if (platform != null) {
            val p = com.pegasus.bridge.core.FuzzyMatch.normalizePlatform(platform)
            config.coreHints[p] ?: CORE_HINTS[p] ?: probe.coreHints
        } else {
            (probe.platforms.flatMap { config.coreHints[it] ?: CORE_HINTS[it].orEmpty() } +
                probe.coreHints).distinct()
        }
        return names.map { "/data/data/$pkg/cores/$it" }
    }

    /**
     * The line that opens an app on nothing, in the same multi-line form.
     *
     * A launcher intent and not the bare component: an activity started without
     * MAIN/LAUNCHER can come up in a state its author never meant to be entered
     * cold. `--activity-clear-task` is kept for the same reason it is on every
     * other line here — otherwise the emulator resumes wherever it was left.
     */
    fun appLaunchCommand(pkg: String, activity: String): String = buildString {
        append("am start\n")
        append("  -a android.intent.action.MAIN\n")
        append("  -c android.intent.category.LAUNCHER\n")
        append("  -n $pkg/$activity\n")
        append("  --activity-clear-task\n")
        append("  --activity-clear-top")
    }

    /**
     * The `am start` line for a probe, in the multi-line form Pegasus writes.
     *
     * The three trailing flags are on every launch command in a working library
     * and are not decoration: without `--activity-clear-task` an emulator
     * relaunched from Pegasus resumes its previous game instead of loading the
     * one that was picked.
     */
    fun launchCommand(probe: Probe, pkg: String): String = buildString {
        append("am start\n")
        // An empty component means the app is reached through its own URI
        // scheme rather than by naming an activity — Linkboy is the case that
        // required it, and naming its activity is not what was verified.
        if (probe.component.isNotEmpty()) append("  -n $pkg/${probe.component}\n")
        for (a in probe.args) append("  ${a.replace("{package}", pkg)}\n")
        append("  --activity-clear-task\n")
        append("  --activity-clear-top\n")
        append("  --activity-no-history")
    }

    private const val TAG = "AndroidEmulators"
}
