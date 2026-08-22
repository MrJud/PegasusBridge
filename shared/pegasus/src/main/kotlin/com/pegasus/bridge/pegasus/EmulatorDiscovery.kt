package com.pegasus.bridge.pegasus

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.FuzzyMatch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Which emulators are actually installed, and what launch line each would need.
 *
 * The motivating observation is not hypothetical. On the machine this was
 * written against, every collection carries an Android launch command:
 *
 *     launch: am start
 *       -n com.explusalpha.NesEmu/com.imagine.BaseActivity
 *       -a android.intent.action.VIEW
 *       -d "{file.uri}"
 *
 * `am start` is not a thing on a Linux desktop, so *every* collection in that
 * library is unlaunchable, and no error anywhere says so. Producing a correct
 * desktop launch line is the concrete job here.
 *
 * ── The rule that shapes everything below ──────────────────
 *
 * **Propose, never apply.** A launch command is the single field that can leave
 * a library unplayable if it is wrong, and it lives in a file the user edits by
 * hand. So discovery answers with candidates and stops. Writing happens only
 * after somebody has chosen, and what gets written is a Bridge-owned overlay
 * rather than an edit to their file.
 *
 * ── And why a candidate is verified rather than guessed ────
 *
 * A path existing means a file exists. It does not mean the file is the emulator
 * whose name it carries, and it does not say which version. So each candidate is
 * *run*, for its version string, and a candidate that will not identify itself
 * is reported as unverified rather than quietly offered as though it had.
 */
object EmulatorDiscovery {

    /**
     * [verified] is true only when the executable answered a version probe.
     * [confidence] carries how it was found, because "on PATH" and "guessed from
     * a Flatpak id" deserve different trust from the person reviewing the list.
     */
    data class Candidate(
        val id: String,
        val displayName: String,
        val platforms: List<String>,
        val executable: String,
        val launchCommand: String,
        val kind: Kind,
        val verified: Boolean,
        val version: String = "",
        val confidence: String = "",
        /**
         * Whether this candidate can actually read the library.
         *
         * Null when it was not checked, which is the honest answer for a native
         * binary: it runs unsandboxed and reads whatever the user can.
         *
         * For a Flatpak it is the difference between installed and usable, and
         * the two are not the same. Measured on a real install: PCSX2 ships with
         * `filesystems=xdg-config/kdeglobals:ro;xdg-run/gamescope-0:ro` and
         * Snes9x with `filesystems=home`, while the library sits on an external
         * mount under `/run/media`. Both were installed, both were verified, and
         * neither could open a single ROM. A proposal that launches an emulator
         * onto a file it cannot see is worse than no proposal.
         */
        val canReadLibrary: Boolean? = null,
        /** What to run to fix [canReadLibrary], when it is false. */
        val grantCommand: String = ""
    )

    enum class Kind { NATIVE, FLATPAK, APPIMAGE, DESKTOP_ENTRY, ANDROID_PACKAGE }

    /**
     * One emulator this knows how to look for.
     *
     * [versionArgs] is what the binary is asked to prove itself with, and
     * [versionMatch] is what its answer has to contain — a `--version` that
     * prints something unrelated is a different program with the same name.
     */
    private data class Probe(
        val id: String,
        val displayName: String,
        val platforms: List<String>,
        val binaries: List<String>,
        val flatpakIds: List<String> = emptyList(),
        val versionArgs: List<String> = listOf("--version"),
        val versionMatch: Regex? = null,
        /** `{file.path}` is Pegasus' placeholder for the ROM's absolute path. */
        val argsTemplate: String = "\"{file.path}\""
    )

    private val PROBES = listOf(
        Probe("retroarch", "RetroArch",
            listOf("nes", "snes", "n64", "gb", "gbc", "gba", "genesis", "mastersystem",
                   "gamegear", "psx", "pcengine", "atari2600", "atari7800", "lynx",
                   "wonderswan", "ngp", "virtualboy", "colecovision", "msx", "arcade"),
            binaries = listOf("retroarch"),
            flatpakIds = listOf("org.libretro.RetroArch"),
            versionMatch = Regex("RetroArch", RegexOption.IGNORE_CASE),
            // A libretro core is required and is per-platform, so this is a
            // template with a hole in it rather than a runnable line. The review
            // step is where the core gets chosen — guessing one would produce a
            // command that fails at launch with a message about a missing file.
            argsTemplate = "-L {core} \"{file.path}\""),
        Probe("dolphin", "Dolphin", listOf("gc", "wii"),
            binaries = listOf("dolphin-emu", "dolphin-emu-nogui"),
            flatpakIds = listOf("org.DolphinEmu.dolphin-emu"),
            versionMatch = Regex("dolphin", RegexOption.IGNORE_CASE),
            argsTemplate = "-b -e \"{file.path}\""),
        Probe("pcsx2", "PCSX2", listOf("ps2"),
            binaries = listOf("pcsx2-qt", "pcsx2", "PCSX2"),
            flatpakIds = listOf("net.pcsx2.PCSX2"),
            versionMatch = Regex("pcsx2", RegexOption.IGNORE_CASE),
            argsTemplate = "-batch \"{file.path}\""),
        Probe("ppsspp", "PPSSPP", listOf("psp"),
            binaries = listOf("PPSSPPSDL", "PPSSPPQt", "ppsspp"),
            flatpakIds = listOf("org.ppsspp.PPSSPP"),
            versionMatch = Regex("ppsspp", RegexOption.IGNORE_CASE)),
        Probe("duckstation", "DuckStation", listOf("psx"),
            binaries = listOf("duckstation-qt", "duckstation-nogui", "duckstation"),
            flatpakIds = listOf("org.duckstation.DuckStation"),
            versionMatch = Regex("duckstation", RegexOption.IGNORE_CASE),
            argsTemplate = "-batch \"{file.path}\""),
        Probe("mame", "MAME", listOf("arcade"),
            binaries = listOf("mame", "mame64"),
            flatpakIds = listOf("org.mamedev.MAME"),
            versionMatch = Regex("MAME"),
            // MAME wants the romset short name and its own rom path, not a file.
            argsTemplate = "-rompath \"{file.dir}\" \"{file.basename}\""),
        Probe("melonds", "melonDS", listOf("nds"),
            binaries = listOf("melonDS", "melonds"),
            flatpakIds = listOf("net.kuribo64.melonDS"),
            versionMatch = Regex("melonds", RegexOption.IGNORE_CASE)),
        Probe("mgba", "mGBA", listOf("gba", "gb", "gbc"),
            binaries = listOf("mgba-qt", "mgba"),
            flatpakIds = listOf("io.mgba.mGBA"),
            versionMatch = Regex("mgba", RegexOption.IGNORE_CASE)),
        Probe("flycast", "Flycast", listOf("dreamcast"),
            binaries = listOf("flycast"),
            flatpakIds = listOf("org.flycast.Flycast"),
            versionMatch = Regex("flycast", RegexOption.IGNORE_CASE)),
        Probe("snes9x", "Snes9x", listOf("snes"),
            binaries = listOf("snes9x-gtk", "snes9x"),
            flatpakIds = listOf("com.snes9x.Snes9x"),
            versionMatch = Regex("snes9x", RegexOption.IGNORE_CASE)),
        // gopher64 first among the N64 options: it is the one that takes a ROM
        // path as a plain argument. Mupen64Plus proper needs a video plugin named
        // on the command line, and RMG and M64Py are front-ends that expect to be
        // driven by hand rather than handed a file.
        Probe("gopher64", "Gopher64", listOf("n64"),
            binaries = listOf("gopher64"),
            flatpakIds = listOf("io.github.gopher64.gopher64"),
            versionMatch = Regex("gopher64", RegexOption.IGNORE_CASE)),
        Probe("mupen64plus", "Mupen64Plus", listOf("n64"),
            binaries = listOf("mupen64plus"),
            flatpakIds = listOf("com.github.Rosalie241.RMG"),
            versionMatch = Regex("mupen64plus", RegexOption.IGNORE_CASE)),
        Probe("blastem", "BlastEm", listOf("genesis", "sega32x", "segacd"),
            binaries = listOf("blastem"),
            flatpakIds = listOf("com.retrodev.blastem"),
            versionMatch = Regex("blastem", RegexOption.IGNORE_CASE))
    )

    /**
     * Everything installed, most trustworthy first.
     *
     * [pathDirs] and [runner] are injected so the whole thing is testable without
     * an emulator installed — which matters, because a test that only passes on a
     * machine that happens to have RetroArch is not a test.
     */
    fun discover(
        pathDirs: List<File> = systemPath(),
        flatpakList: () -> List<InstalledFlatpak> = ::installedFlatpaks,
        runner: (List<String>) -> String? = ::runForOutput,
        /**
         * The library roots a candidate has to be able to read.
         *
         * Empty skips the check entirely, which is what a caller with no library
         * configured should get — inventing a path to test against would produce
         * a confident answer about nothing.
         */
        libraryRoots: List<File> = emptyList(),
        sandboxReader: (String, File) -> Boolean = ::flatpakCanRead
    ): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val flatpaks = runCatching(flatpakList).getOrDefault(emptyList()).associateBy { it.id }

        for (probe in PROBES) {
            var found = false
            for (binary in probe.binaries) {
                val exe = pathDirs.map { File(it, binary) }
                    .firstOrNull { it.isFile && it.canExecute() } ?: continue
                val version = probeVersion(exe.absolutePath, probe, runner)
                out += Candidate(
                    id = probe.id,
                    displayName = probe.displayName,
                    platforms = probe.platforms,
                    executable = exe.absolutePath,
                    launchCommand = "${quoteIfNeeded(exe.absolutePath)} ${probe.argsTemplate}",
                    kind = Kind.NATIVE,
                    verified = version != null,
                    version = version.orEmpty(),
                    confidence = "on PATH at ${exe.parent}"
                )
                found = true
                break
            }
            if (found) continue

            for (id in probe.flatpakIds) {
                val installed = flatpaks[id] ?: continue
                out += Candidate(
                    id = probe.id,
                    displayName = "${probe.displayName} (Flatpak)",
                    platforms = probe.platforms,
                    executable = id,
                    launchCommand = "flatpak run $id ${probe.argsTemplate}",
                    kind = Kind.FLATPAK,
                    // Verified from metadata, and deliberately *not* by running it.
                    // `flatpak run <id> --version` was tried and is not safe as a
                    // probe: mGBA answers, PCSX2 prints nothing, and Snes9x ignores
                    // the flag and **starts the emulator** — joystick init, audio
                    // device, the lot. Discovering what is installed must not launch
                    // anything, and `flatpak list` already states the id, the name
                    // and the version without executing a byte.
                    verified = installed.version.isNotEmpty(),
                    version = installed.version,
                    confidence = "installed Flatpak (${installed.name})",
                    canReadLibrary = if (libraryRoots.isEmpty()) null
                                     else libraryRoots.all { sandboxReader(id, it) },
                    grantCommand = libraryRoots.joinToString("; ") {
                        "flatpak override --user --filesystem=\"${it.absolutePath}\" $id"
                    }
                )
                break
            }
        }
        return out.sortedWith(compareByDescending<Candidate> { it.verified }.thenBy { it.displayName })
    }

    /**
     * Every candidate that handles [platform], best first.
     *
     * All of them, not just the winner. "Propose, never apply" is not honoured by
     * a proposal whose alternatives are invisible — that is a decision made for
     * somebody and shown to them afterwards. A review screen needs the list, and
     * an apply takes whatever the person picked out of it.
     *
     * The order, and the reason for each step:
     *
     * 1. **Can it read the library.** An emulator that cannot open the ROM fails
     *    at the moment somebody presses A, whatever else is true of it.
     * 2. **Did it identify itself.** A binary that answered a version probe, or a
     *    Flatpak whose metadata names one.
     * 3. **Is it RetroArch.** Last among equals: it covers every platform, so it
     *    is never the most specific answer, and its command still needs a core
     *    that discovery has no way to choose.
     * 4. **How many platforms it claims.** Fewer means more specialised, and a
     *    specialist is the better default for its own system.
     */
    fun rankedFor(platform: String, candidates: List<Candidate>): List<Candidate> {
        val norm = FuzzyMatch.normalizePlatform(platform)
        return candidates.filter { norm in it.platforms }.sortedWith(
            compareByDescending<Candidate> { it.canReadLibrary != false }
                .thenByDescending { it.verified }
                .thenBy { it.id == "retroarch" }
                .thenBy { it.platforms.size }
                .thenBy { it.displayName }
        )
    }

    /** Why a candidate sits where it does, in one sentence a person can read. */
    fun rankReason(c: Candidate, position: Int): String = when {
        c.canReadLibrary == false -> "cannot read the library as installed"
        position == 0 && c.id == "retroarch" -> "the only one installed for this platform"
        c.id == "retroarch" -> "covers everything, so never the most specific choice"
        !c.verified -> "installed, but it did not identify itself"
        position == 0 -> "dedicated to this platform, and verified"
        else -> "also handles this platform"
    }

    /** The best candidate for [platform], or null. Shorthand over [rankedFor]. */
    fun bestFor(platform: String, candidates: List<Candidate>): Candidate? =
        rankedFor(platform, candidates).firstOrNull()

    private fun probeVersion(
        exe: String,
        probe: Probe,
        runner: (List<String>) -> String?
    ): String? {
        val output = runner(listOf(exe) + probe.versionArgs) ?: return null
        val line = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val match = probe.versionMatch ?: return line
        // A `--version` that prints something unrelated is a different program
        // wearing the same name, and offering it would be worse than finding nothing.
        return if (match.containsMatchIn(output)) line else null
    }

    /**
     * Runs a command for its output, or null.
     *
     * Bounded and killed on timeout, because this executes files found on PATH
     * and one of them will eventually ignore `--version` and do something else
     * instead. Measured on a real install: `snes9x-gtk` under Flatpak ignores the
     * flag and starts the emulator, opening a joystick and an audio device. The
     * timeout contains that; the Flatpak path avoids it altogether by reading
     * metadata rather than executing anything.
     */
    fun runForOutput(command: List<String>, timeoutSeconds: Long = 5): String? = try {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        p.outputStream.close()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else text
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        BridgeLog.d(TAG, "could not run ${command.firstOrNull()}: ${t.message}")
        null
    }

    fun systemPath(env: Map<String, String> = System.getenv()): List<File> =
        env["PATH"].orEmpty().split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }.map(::File).filter { it.isDirectory }

    /** What `flatpak list` states about one installed application. */
    data class InstalledFlatpak(val id: String, val name: String, val version: String)

    /**
     * Every installed Flatpak application, user scope and system scope.
     *
     * Both, because they are separate installations and an emulator can be in
     * either — a `--user` install needs no root, so it is the one a person
     * without sudo actually ends up with, and querying only the system scope
     * would miss it entirely.
     */
    /**
     * Whether a Flatpak's sandbox can read [path].
     *
     * Asked by running `sh` *inside* the sandbox rather than by parsing
     * `filesystems=` out of the permissions, because the answer depends on
     * overrides, on `:ro` suffixes, on portals and on which of `home`, `host`
     * and an explicit path happen to overlap. Reading a directory entry settles
     * all of it at once, and it launches a shell rather than the emulator.
     */
    fun flatpakCanRead(appId: String, path: File): Boolean {
        val out = runForOutput(
            listOf("flatpak", "run", "--user", "--command=sh", appId,
                   "-c", "test -r '${path.absolutePath}' && echo READABLE"),
            timeoutSeconds = 30) ?: return false
        return out.contains("READABLE")
    }

    fun installedFlatpaks(): List<InstalledFlatpak> {
        val out = LinkedHashMap<String, InstalledFlatpak>()
        for (scope in listOf(listOf("--user"), listOf("--system"))) {
            val text = runForOutput(
                listOf("flatpak", "list") + scope +
                listOf("--app", "--columns=application,name,version")) ?: continue
            for (line in text.lineSequence()) {
                val cols = line.split('\t').map { it.trim() }
                val id = cols.getOrNull(0).orEmpty()
                if (id.isEmpty() || !id.contains('.')) continue
                out.putIfAbsent(id, InstalledFlatpak(
                    id = id,
                    name = cols.getOrNull(1).orEmpty(),
                    version = cols.getOrNull(2).orEmpty()))
            }
        }
        return out.values.toList()
    }

    private fun quoteIfNeeded(path: String) =
        if (path.any { it.isWhitespace() }) "\"$path\"" else path

    private const val TAG = "EmulatorDiscovery"
}
