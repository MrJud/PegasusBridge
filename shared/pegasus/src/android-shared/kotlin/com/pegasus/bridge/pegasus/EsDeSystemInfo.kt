package com.pegasus.bridge.pegasus

import java.io.File

/**
 * ES-DE's `systeminfo.txt`, when a library happens to have one.
 *
 * A convenience, never a requirement. ES-DE drops one of these into every
 * system directory it manages, and where it exists it is the best answer
 * available: the platform's own definition, written by people who maintain it,
 * naming the extensions exactly and — uniquely among the sources here — the
 * precise libretro core for the platform, which nothing else on Android can
 * know because RetroArch's core directory is private.
 *
 * On the tablet this was written against, 167 of 171 directories had one. On a
 * library that never met ES-DE, none will, and [CollectionInference] carries on
 * with the other two sources. That is the whole contract: read it if it is
 * there, never need it.
 *
 * The format is a sequence of labelled paragraphs — a heading line ending in
 * `:`, then the value on the following lines until a blank one:
 *
 *     System name:
 *     psx
 *
 *     Full system name:
 *     Sony PlayStation
 *
 *     Supported file extensions:
 *     .bin .BIN .cbn .CBN .chd .CHD …
 *
 * The launch commands are read for exactly two things and nothing else: which
 * emulator each names, and — for RetroArch — which libretro core. Their macro
 * language (`%EMULATOR_RETROARCH%`, `%ROMSAF%`, `%ANDROIDPACKAGE%`) is *not*
 * expanded: doing that would mean reimplementing another program's launcher
 * against emulators that may not be installed, and the Bridge has its own
 * discovery which answers for what is installed *here*.
 *
 * The core is worth the exception. RetroArch keeps its cores in
 * `/data/user/0/com.retroarch/cores`, which is app-private, so nothing outside
 * RetroArch can list them — the Bridge's own proposal has to leave a `{core}`
 * hole for a person to fill. ES-DE's file names the core for the platform, in
 * order of preference, and reading one assignment out of a line is a long way
 * from resolving the line.
 */
object EsDeSystemInfo {

    const val FILE_NAME = "systeminfo.txt"

    /**
     * One way ES-DE knows to launch this platform.
     *
     * [emulator] is the macro's own name — `RETROARCH`, `SNES9X-EXPLUS` — left
     * as written, because mapping it to something installed is discovery's job
     * and not this parser's. [core] carries `%ANDROIDPACKAGE%` unexpanded for
     * the same reason: which RetroArch package is installed is not known here.
     */
    data class LaunchOption(
        val emulator: String,
        val core: String,
        /** From `Launch command:` rather than from the alternatives below it. */
        val primary: Boolean
    )

    data class SystemInfo(
        /** The short name, e.g. `psx`. */
        val systemName: String,
        /** The display name, e.g. `Sony PlayStation`. */
        val fullName: String,
        /** Lower-cased, no leading dot, de-duplicated. */
        val extensions: List<String>,
        /** Preferred first: the primary command, then the alternatives in order. */
        val launchOptions: List<LaunchOption> = emptyList()
    )

    fun findIn(dir: File): File? = File(dir, FILE_NAME).takeIf { it.isFile }

    fun read(dir: File): SystemInfo? =
        findIn(dir)?.let { f -> runCatching { parse(f.readText()) }.getOrNull() }

    fun parse(text: String): SystemInfo? {
        val sections = sections(text)
        val system = sections["system name"]?.firstOrNull()?.trim().orEmpty()
        val full = sections["full system name"]?.firstOrNull()?.trim().orEmpty()
        val exts = sections["supported file extensions"].orEmpty()
            .flatMap { it.split(' ', '\t') }
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
            .distinct()
        // Singular and plural both appear in the wild: the file ES-DE ships for
        // `psx` says "Alternative launch commands:", and the one MrJud keeps for
        // `amiga` says "Alternative launch command:". Keying on the plural alone
        // silently dropped the second core of a two-core system.
        val launches =
            (sections["launch command"].orEmpty() + sections["launch commands"].orEmpty())
                .map { it to true } +
            (sections["alternative launch commands"].orEmpty() +
             sections["alternative launch command"].orEmpty()).map { it to false }
        val options = launches.mapNotNull { (line, primary) ->
            val emu = EMULATOR_MACRO.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            LaunchOption(emu, CORE_MACRO.find(line)?.groupValues?.get(1).orEmpty(), primary)
        }

        // A file with neither a name nor an extension list says nothing worth
        // preferring over the other sources, and returning an empty shell would
        // make it look like it had.
        if (system.isEmpty() && full.isEmpty() && exts.isEmpty()) return null
        return SystemInfo(system, full, exts, options)
    }

    /**
     * The labelled paragraphs, keyed by their heading in lower case.
     *
     * A heading is a line whose last character is `:`; everything up to the next
     * blank line belongs to it. Written this way rather than with a regex per
     * field because the file has sections this does not read — `Alternative
     * launch commands:` runs to eight lines on some systems — and a parser that
     * only recognised the three it wanted would fold those into whatever came
     * before.
     */
    private val EMULATOR_MACRO = Regex("""%EMULATOR_([A-Za-z0-9_.\-]+)%""")

    /**
     * The core an ES-DE launch line names.
     *
     * Written both ways in the wild and both are accepted: a bare
     * `snes9x_libretro_android.so`, and an absolute
     * `/data/data/%ANDROIDPACKAGE%/cores/snes9x_libretro_android.so`. The `psx`
     * file on the tablet uses the first and the `snes` file the second, which is
     * how a filename-only pattern came to miss nine of them.
     */
    private val CORE_MACRO = Regex("""%EXTRA_LIBRETRO%=(\S+\.so)""")

    /**
     * ES-DE's emulator macro names, as the ids this project uses.
     *
     * Only what has been seen in a real `systeminfo.txt`. An unmapped macro
     * resolves to nothing and the proposal falls back to the Bridge's own
     * ranking, which is the right failure: guessing that some `%EMULATOR_X%` is
     * a particular installed package would write a launch line for the wrong app.
     */
    private val MACRO_TO_ID = mapOf(
        "RETROARCH" to "retroarch",
        "DUCKSTATION" to "duckstation",
        "PPSSPP" to "ppsspp",
        "DRASTIC" to "drastic",
        "MELONDS" to "melonds",
        "DOLPHIN" to "dolphin",
        "AETHERSX2" to "aethersx2",
        "FLYCAST" to "flycast",
        "LIME3DS" to "lime3ds",
        "CITRA" to "lime3ds",
        "CITRON" to "citron",
        "SNES9X-EXPLUS" to "snes9xplus",
        "MD-EMU" to "mdemu",
        "NES-EMU" to "nesemu",
        "GBC-EMU" to "gbcemu",
        "GBA-EMU" to "gbaemu",
        "MUPEN64PLUS-FZ" to "mupen64plusfz",
        "REDREAM" to "redream",
        "VITA3K" to "vita3k",
        "MGBA" to "mgba"
    )

    /** The Bridge's emulator id for an ES-DE macro name, or null. */
    fun idForMacro(macro: String): String? = MACRO_TO_ID[macro.uppercase()]

    /**
     * The core ES-DE would use to run [emulatorId] on the platform in [dir],
     * with `%ANDROIDPACKAGE%` resolved to [pkg]. Empty when it says nothing.
     *
     * Preference order is ES-DE's own: the primary command first, then the
     * alternatives as listed. `snes` offers nine RetroArch cores and the order
     * is somebody's judgement about which is best — worth keeping.
     */
    fun coreFor(dir: File, emulatorId: String, pkg: String): String =
        read(dir)?.launchOptions
            ?.firstOrNull { idForMacro(it.emulator) == emulatorId && it.core.isNotEmpty() }
            ?.core?.replace("%ANDROIDPACKAGE%", pkg)
            .orEmpty()

    /** Every emulator id ES-DE lists for the platform in [dir], preferred first. */
    fun emulatorsFor(dir: File): List<String> =
        read(dir)?.launchOptions?.mapNotNull { idForMacro(it.emulator) }?.distinct().orEmpty()

    private fun sections(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var key: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> key = null
                line.endsWith(":") && !line.startsWith("%") ->
                    key = line.dropLast(1).trim().lowercase().also { out.getOrPut(it) { mutableListOf() } }
                key != null -> out[key]!! += line
            }
        }
        return out
    }
}
