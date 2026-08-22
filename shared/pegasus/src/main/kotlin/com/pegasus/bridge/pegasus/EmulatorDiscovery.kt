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
        val confidence: String = ""
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
            versionMatch = Regex("snes9x", RegexOption.IGNORE_CASE))
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
        flatpakList: () -> List<String> = ::installedFlatpaks,
        runner: (List<String>) -> String? = ::runForOutput
    ): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val flatpaks = runCatching(flatpakList).getOrDefault(emptyList()).toSet()

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
                if (id !in flatpaks) continue
                out += Candidate(
                    id = probe.id,
                    displayName = "${probe.displayName} (Flatpak)",
                    platforms = probe.platforms,
                    executable = id,
                    launchCommand = "flatpak run $id ${probe.argsTemplate}",
                    kind = Kind.FLATPAK,
                    // The id being installed is `flatpak list` saying so, which is
                    // a stronger statement than a file existing on PATH — but the
                    // binary inside was not run, so this is not the same as verified.
                    verified = false,
                    confidence = "installed Flatpak"
                )
                break
            }
        }
        return out.sortedWith(compareByDescending<Candidate> { it.verified }.thenBy { it.displayName })
    }

    /**
     * The candidate best suited to [platform], or null.
     *
     * Verified beats unverified, and a dedicated emulator beats RetroArch —
     * RetroArch covers everything and so is never the most specific answer, and
     * its command needs a core the discovery step cannot choose.
     */
    fun bestFor(platform: String, candidates: List<Candidate>): Candidate? {
        val norm = FuzzyMatch.normalizePlatform(platform)
        val fits = candidates.filter { norm in it.platforms }
        return fits.sortedWith(
            compareByDescending<Candidate> { it.verified }
                .thenBy { it.id == "retroarch" }
                .thenBy { it.platforms.size }
        ).firstOrNull()
    }

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
     * Bounded and killed on timeout: this executes files found on PATH, and one
     * that ignores `--version` and waits for input would otherwise hang discovery
     * for as long as the daemon lives.
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

    fun installedFlatpaks(): List<String> =
        runForOutput(listOf("flatpak", "list", "--app", "--columns=application"))
            ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
            ?: emptyList()

    private fun quoteIfNeeded(path: String) =
        if (path.any { it.isWhitespace() }) "\"$path\"" else path

    private const val TAG = "EmulatorDiscovery"
}
