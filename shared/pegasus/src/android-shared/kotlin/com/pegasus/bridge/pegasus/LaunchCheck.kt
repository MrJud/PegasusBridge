package com.pegasus.bridge.pegasus

import java.io.File

/**
 * Whether a launch command could run on this machine.
 *
 * The first version of this answered only one question — is the command
 * `am start`, which is Android's and does not exist on a desktop — and reported
 * everything else as runnable. That is enough to notice a library imported from
 * a phone, and no help at all for the ordinary case: a launch naming an emulator
 * that has been uninstalled, a Flatpak id that was never installed, or a path
 * that has moved. All three report fine and fail when somebody presses A.
 *
 * So this resolves the executable. Cheap — a `File.isFile` or a lookup in an
 * already-gathered Flatpak list — and it is the difference between "there is a
 * launch command" and "there is a launch command that works".
 *
 * It deliberately does **not** run anything, and does not judge the arguments. A
 * launch command can legitimately be a shell pipeline, a wrapper script or a
 * `gamemoderun` prefix, and declaring one broken because it looked unusual would
 * be worse than saying nothing.
 *
 * ── Why this is shared ──
 *
 * Both shells ask the same question and get opposite answers, which is exactly
 * why there must be one copy of it. A desktop library full of `am start` lines
 * is broken; an Android library full of `flatpak run` lines is broken; and the
 * *same* collection is one or the other depending on which device is reading
 * it. Two implementations would eventually disagree about a third case, and the
 * disagreement would show up as a launch button that works on the tablet and
 * not on the desktop.
 *
 * So the rules live here and the two platform-specific lookups — is this
 * executable on `$PATH`, is this package installed — arrive as parameters.
 */
object LaunchCheck {

    enum class Verdict {
        /** The executable was found. Nothing is claimed about the arguments. */
        RUNNABLE,
        /** No launch command at all. */
        MISSING,
        /** Android's activity manager, on something that is not Android. */
        WRONG_PLATFORM,
        /** A Flatpak id that is not installed. */
        FLATPAK_NOT_INSTALLED,
        /** An `am start` naming an Android package that is not installed. */
        PACKAGE_NOT_INSTALLED,
        /** A path or a name that resolves to nothing executable. */
        NOT_FOUND,
        /** Too unusual to resolve — a pipeline, a variable, a quoted expression. */
        UNKNOWN
    }

    data class Result(val verdict: Verdict, val executable: String, val detail: String) {
        val runnable: Boolean get() = verdict == Verdict.RUNNABLE || verdict == Verdict.UNKNOWN
    }

    /** Commands that only exist on Android. */
    private val ANDROID_ONLY = setOf("am", "monkey", "pm", "cmd")

    /**
     * Prefixes that wrap the real command rather than being it.
     *
     * Skipped so a `gamemoderun retroarch …` is judged on `retroarch`. Not
     * exhaustive by design: an unrecognised wrapper falls through to a normal
     * lookup, which either finds it on PATH or answers [Verdict.UNKNOWN].
     */
    private val WRAPPERS = setOf(
        "gamemoderun", "mangohud", "env", "nice", "prime-run", "primusrun",
        "optirun", "sudo", "setsid", "nohup"
    )

    /**
     * [pathDirs] resolves a bare command on a desktop; [installedPackage]
     * answers for an Android package name. Each shell supplies the one that
     * means something where it runs and leaves the other at its default, which
     * finds nothing — a default that cannot accidentally claim a command works.
     */
    fun check(
        launch: String,
        pathDirs: List<File> = emptyList(),
        installedFlatpakIds: Set<String> = emptySet(),
        isAndroid: Boolean = System.getProperty("os.name").orEmpty().contains("Android"),
        installedPackage: (String) -> Boolean = { false }
    ): Result {
        val tokens = tokenise(launch)
        if (tokens.isEmpty()) return Result(Verdict.MISSING, "", "no launch command")

        var i = 0
        // Step past wrappers and leading VAR=value assignments.
        while (i < tokens.size &&
               (tokens[i] in WRAPPERS || tokens[i].matches(Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")))) i++
        if (i >= tokens.size) return Result(Verdict.UNKNOWN, "", "only wrappers, no command")

        val cmd = tokens[i]

        if (cmd in ANDROID_ONLY) {
            if (!isAndroid) return Result(Verdict.WRONG_PLATFORM, cmd,
                "`$cmd` is Android's and does not exist here — this collection " +
                "was set up for a phone")
            // On Android the command exists, so the question becomes the one
            // that actually decides whether pressing A does anything: is the
            // activity it names installed. A launch line pointing at an
            // uninstalled emulator is the ordinary case, not the exotic one —
            // eleven of nineteen collections on the device this was written
            // against were in exactly that state.
            val component = componentIn(tokens.drop(i + 1))
                ?: return Result(Verdict.UNKNOWN, cmd, "an `$cmd` with no -n component")
            val pkg = component.substringBefore('/')
            return if (installedPackage(pkg))
                Result(Verdict.RUNNABLE, component, "installed package")
            else Result(Verdict.PACKAGE_NOT_INSTALLED, component,
                        "the app `$pkg` is not installed")
        }

        if (cmd == "flatpak") {
            val id = flatpakIdIn(tokens.drop(i + 1))
                ?: return Result(Verdict.UNKNOWN, cmd, "a flatpak command with no application id")
            return if (id in installedFlatpakIds)
                Result(Verdict.RUNNABLE, id, "installed Flatpak")
            else Result(Verdict.FLATPAK_NOT_INSTALLED, id, "the Flatpak `$id` is not installed")
        }

        // Anything a shell would interpret rather than execute.
        if (cmd.any { it in "|&;<>$`(){}*?" })
            return Result(Verdict.UNKNOWN, cmd, "a shell expression, not resolved")

        if (cmd.contains('/')) {
            val f = File(cmd)
            return if (f.isFile && f.canExecute())
                Result(Verdict.RUNNABLE, f.absolutePath, "an executable file")
            else Result(Verdict.NOT_FOUND, cmd,
                        if (f.exists()) "`$cmd` is not executable" else "`$cmd` does not exist")
        }

        val onPath = pathDirs.map { File(it, cmd) }.firstOrNull { it.isFile && it.canExecute() }
        return if (onPath != null) Result(Verdict.RUNNABLE, onPath.absolutePath, "found on PATH")
        else Result(Verdict.NOT_FOUND, cmd, "`$cmd` is not on PATH")
    }

    /**
     * The `package/activity` of an `am start -n` command.
     *
     * `-n` is the only form that names an activity outright. A launch line that
     * relies on an implicit intent instead is left as [Verdict.UNKNOWN] rather
     * than resolved, for the same reason a shell pipeline is: guessing which
     * app would win an implicit intent is the system's job, not this one's.
     */
    internal fun componentIn(rest: List<String>): String? {
        val i = rest.indexOf("-n")
        if (i < 0) return null
        return rest.getOrNull(i + 1)?.takeIf { it.contains('/') }
    }

    /**
     * The application id of a `flatpak run` command.
     *
     * Everything between `run` and the id is an option — `--user`, `--branch=…`,
     * `--filesystem=…` — and an id always contains a dot, which no option does
     * once its leading dashes are accounted for.
     */
    private fun flatpakIdIn(rest: List<String>): String? {
        val afterRun = rest.dropWhile { it != "run" }.drop(1).ifEmpty { rest }
        return afterRun.firstOrNull { !it.startsWith("-") && it.contains('.') }
    }

    /**
     * Splits a launch command into tokens, honouring quotes.
     *
     * Needed because the interesting token can be an absolute path with spaces,
     * and Pegasus' own multi-line launch commands fold into one string with
     * newlines that are just separators.
     */
    internal fun tokenise(command: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote = ' '
        for (c in command) {
            when {
                quote != ' ' -> if (c == quote) quote = ' ' else sb.append(c)
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
