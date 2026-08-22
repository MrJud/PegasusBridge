package com.pegasus.bridge.pegasus

import android.content.pm.PackageManager
import com.pegasus.bridge.core.BridgeLog

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
        val coreHints: List<String> = emptyList()
    )

    /** How much is actually known about a probe's launch line. */
    enum class Provenance(val describe: String) {
        /** Read back from the device's own package manager. */
        ON_THIS_DEVICE("component confirmed on this device"),
        /** Transcribed from a metadata file that launches games today. */
        WORKING_LIBRARY("launch line taken from a working library"),
        /** Written from documentation. Plausible, never run. */
        UNVERIFIED("launch line from documentation, never run here")
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

    /** Every package this knows how to look for — the input to the manifest check. */
    val KNOWN_PACKAGES: Set<String> = PROBES.flatMap { it.packages }.toSet()

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
        platform: String? = null
    ): List<EmulatorCandidate> {
        val out = mutableListOf<EmulatorCandidate>()
        for (probe in PROBES) {
            for (pkg in probe.packages) {
                val version = runCatching { installed(pkg) }.getOrNull() ?: continue
                out += candidate(probe, pkg, version, platform)
                // One candidate per emulator, not one per package: two RetroArch
                // builds are two ways to run the same thing, and offering both
                // asks somebody to choose between them on no information.
                break
            }
        }
        return EmulatorRanking.overall(out)
    }

    /** The same thing, against a real [PackageManager]. */
    fun discover(pm: PackageManager, platform: String? = null): List<EmulatorCandidate> =
        discover(installed = { pkg -> versionOf(pm, pkg) }, platform = platform)

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
        platform: String?
    ): EmulatorCandidate {
        val hints = coreHintsFor(probe, pkg, platform)

        return EmulatorCandidate(
            id = probe.id,
            displayName = probe.displayName,
            platforms = probe.platforms,
            executable = pkg,
            launchCommand = launchCommand(probe, pkg),
            kind = EmulatorKind.ANDROID_PACKAGE,
            // Always true here, and it costs nothing: the package manager states
            // the version without the package being run. See the class comment.
            verified = true,
            version = version,
            confidence = "installed package $pkg — ${probe.provenance.describe}",
            canReadLibrary = null,
            readabilityUnknownBecause =
                "whether $pkg can read the library depends on storage permissions " +
                "granted to it, and reading another package's app-ops needs " +
                "GET_APP_OPS_STATS, which is a signature permission",
            coreHints = if (launchCommand(probe, pkg).contains("{core}")) hints else emptyList(),
            launchVerified = probe.provenance != Provenance.UNVERIFIED
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
    private fun coreHintsFor(probe: Probe, pkg: String, platform: String?): List<String> {
        val names = platform
            ?.let { com.pegasus.bridge.core.FuzzyMatch.normalizePlatform(it) }
            ?.let { CORE_HINTS[it] }
            ?: probe.platforms.flatMap { CORE_HINTS[it].orEmpty() }.distinct()
        return names.map { "/data/data/$pkg/cores/$it" }
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
        append("  -n $pkg/${probe.component}\n")
        for (a in probe.args) append("  ${a.replace("{package}", pkg)}\n")
        append("  --activity-clear-task\n")
        append("  --activity-clear-top\n")
        append("  --activity-no-history")
    }

    private const val TAG = "AndroidEmulators"
}
