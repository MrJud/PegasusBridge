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
 * Only three sections are read. The launch commands are deliberately left
 * alone: they are written in ES-DE's own macro language (`%EMULATOR_RETROARCH%`,
 * `%ROMSAF%`, `%ANDROIDPACKAGE%`) whose expansion lives in ES-DE's configuration
 * rather than in the file, so resolving them would mean reimplementing another
 * program's launcher against files that may not be installed. The Bridge has its
 * own discovery for that, and it answers for what is installed *here*.
 */
object EsDeSystemInfo {

    const val FILE_NAME = "systeminfo.txt"

    data class SystemInfo(
        /** The short name, e.g. `psx`. */
        val systemName: String,
        /** The display name, e.g. `Sony PlayStation`. */
        val fullName: String,
        /** Lower-cased, no leading dot, de-duplicated. */
        val extensions: List<String>
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
        // A file with neither a name nor an extension list says nothing worth
        // preferring over the other sources, and returning an empty shell would
        // make it look like it had.
        if (system.isEmpty() && full.isEmpty() && exts.isEmpty()) return null
        return SystemInfo(system, full, exts)
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
