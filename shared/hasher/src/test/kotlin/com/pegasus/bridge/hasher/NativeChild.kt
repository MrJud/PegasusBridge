package com.pegasus.bridge.hasher

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.fail

/**
 * One call into the native hasher, made in a JVM of its own.
 *
 * A file that is cut short or made to mislead can end rcheevos with a signal,
 * or leave it reading for ever. In the JVM that runs the tests the first is
 * the Gradle worker gone, with every test after it, and the second a build
 * held until its job is killed. A child process can do either and the test
 * that started it is still there to say which: [hash] starts one, waits for
 * it, kills it when it is late, and reads how it ended.
 *
 * tests/native_repro_test.py hands the same files to the C sources with no
 * JVM at all. What this adds is the library itself, the one the daemon loads,
 * entered the way the daemon enters it.
 */
internal object NativeChild {

    /** How the one call ended. */
    sealed interface Result {
        data class Ok(val hash: String, val console: Int) : Result

        /**
         * The library was asked and gave no hash. [reason] is what it said of
         * the file, as [RcheevosNative.hash] hands it on. It is never empty:
         * where rcheevos gave up without a word, the library says so.
         */
        data class NoHash(val reason: String) : Result

        /**
         * The child ended some other way than by answering. [exit] is its
         * status, and which it is depends on who ended it. A JVM that catches
         * a signal inside the library writes its report and exits with 1. A
         * process ended outright has 128 plus the signal's number: 134 when
         * the C library aborts on a heap it finds broken, which the JVM is
         * not asked about, and the same when the JVM itself dies on that heap
         * while writing its report. So a crash is known by the answer that is
         * missing and never by the status, which a child that exits with 0
         * and has said nothing shows as well. [said] is the start of what the
         * child wrote, where a report names the signal and the function, for
         * whoever reads the failure.
         */
        data class Crashed(val exit: Int, val said: String) : Result

        /** Still running after the time it was given, and killed. */
        data object TimedOut : Result
    }

    /** The library to load, in the tests' JVM and in the child alike. */
    const val LIBRARY_PROPERTY = "pegasus.bridge.nativeLibrary"

    /**
     * The class path the child is started with. Gradle's test task sets it to
     * the tests' own runtime class path (hasher/build.gradle.kts). It is not
     * read off java.class.path, which is whatever the worker was started
     * with: that Gradle puts the tests' classes there is how it works today,
     * not something it says it will keep doing.
     */
    const val CLASSPATH_PROPERTY = "pegasus.bridge.testClasspath"

    /** What the child's one line of answer begins with, among whatever else a JVM prints. */
    const val ANSWER = "native-child:"

    /** The child's status when the library would not load, which is not the file's doing. */
    const val EXIT_NO_LIBRARY = 3

    const val LIMIT_SECONDS = 30L

    /**
     * What [NativeChildMain] takes in place of a path, to end without any
     * file or the library in one of the ways a file can make it end: with a
     * status and no answer, or never. They are how NativeChildTest sees [run]
     * tell a crash and a hang apart from an answer; with the library as it
     * should be, no file makes a child do either. The third has the child
     * say which process started it and what core file it is allowed.
     */
    const val MODE_END = "--end"
    const val MODE_SLEEP = "--sleep"
    const val MODE_SELF = "--self"

    /** The shell that lowers the child's limit; where there is none, the child starts without. */
    private val SHELL = File("/bin/sh")

    /**
     * [console] as rcheevos numbers them, for the file to be hashed as that
     * console and no other; 0 leaves the choice to the file's extension.
     */
    fun hash(path: File, console: Int = 0, limitSeconds: Long = LIMIT_SECONDS): Result =
        run(listOf(path.absolutePath, console.toString()), limitSeconds)

    /**
     * Starts a child with [arguments], which [hash] makes the file's path and
     * a console, and reads how it ended. [library] is the file the child is
     * to load, the tests' own unless a test is about a library that is not
     * the hasher's.
     */
    fun run(arguments: List<String>, limitSeconds: Long = LIMIT_SECONDS, library: String? = null): Result {
        val library = library ?: System.getProperty(LIBRARY_PROPERTY)
            ?: fail("$LIBRARY_PROPERTY is not set: run this through Gradle, whose test task sets it")
        val classpath = System.getProperty(CLASSPATH_PROPERTY)
            ?: fail("$CLASSPATH_PROPERTY is not set: run this through Gradle, whose test task sets it")

        // The child's working directory, and where the JVM is told to write the
        // report of a crash: left to itself it writes hs_err_pid<n>.log into
        // the directory it runs in, which would be the module's.
        val scratch = Files.createTempDirectory("native-child").toFile()
        try {
            val out = File(scratch, "stdout")
            val err = File(scratch, "stderr")
            val java = listOf(
                File(System.getProperty("java.home"), "bin/java").path,
                "-XX:ErrorFile=${File(scratch, "hs_err.log")}",
                "-XX:-CreateCoredumpOnCrash",
                "-D$LIBRARY_PROPERTY=$library",
                "-cp", classpath,
                NativeChildMain::class.java.name
            ) + arguments
            // -XX:-CreateCoredumpOnCrash holds only for a crash the JVM
            // catches. A process the C library aborts, or a JVM that dies
            // writing its report, is ended by the kernel, which then writes
            // out the whole of it, some fifty megabytes, to wherever the
            // system keeps core files. What stops that is the process's own
            // limit, and only the process can lower it: so a shell lowers it
            // and then becomes the JVM. `exec`, so that the process started
            // here is the JVM and not a shell with the JVM under it, which
            // would be the one waited for and killed while the JVM lived on.
            val command =
                if (SHELL.canExecute()) listOf(SHELL.path, "-c", "ulimit -c 0; exec \"\$@\"", "sh") + java
                else java
            val process = ProcessBuilder(command).directory(scratch)
                // To files and not to pipes: nobody has to read a pipe while
                // the child runs for it not to fill up and stop the child.
                .redirectOutput(out)
                .redirectError(err)
                .start()

            if (!process.waitFor(limitSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
                return Result.TimedOut
            }

            val answer = out.readLines().lastOrNull { it.startsWith(ANSWER) }?.removePrefix(ANSWER)?.trim()
            val exit = process.exitValue()
            return when {
                exit == 0 && answer != null && answer.startsWith("hash ") -> {
                    val (hash, console) = answer.removePrefix("hash ").split('|', limit = 2)
                    Result.Ok(hash, console.toInt())
                }
                exit == 0 && answer != null && answer.startsWith("nohash") ->
                    Result.NoHash(answer.removePrefix("nohash").trim())
                else ->
                    Result.Crashed(exit, (out.readText() + err.readText()).trim().take(700))
            }
        } finally {
            scratch.deleteRecursively()
        }
    }
}

/**
 * The child's side of [NativeChild]: loads the library named by
 * [NativeChild.LIBRARY_PROPERTY], hashes the one file it is given, as the
 * console that follows the path or as console 0 when none does, and prints
 * one line, `native-child: hash <md5>|<console>` or `native-child: nohash`
 * with the reason after it.
 *
 * The library is loaded from the path given and no other, as the golden tests
 * load it, so that what runs here is what they ran. The call is the one the
 * daemon's hasher makes, [RcheevosNative.hash], and nothing here catches what
 * that throws: a child that never reached rcheevos ends without an answer,
 * and is a crash and not a file with no hash.
 *
 * Given one of the three modes in place of a path it loads nothing and ends
 * as that mode says, with no line of answer.
 */
internal object NativeChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args[0]) {
            // halt, not exit: nothing is to run on the way out, as nothing
            // does in a process that a signal ends.
            NativeChild.MODE_END -> Runtime.getRuntime().halt(args[1].toInt())
            NativeChild.MODE_SLEEP -> Thread.sleep(Long.MAX_VALUE)
            NativeChild.MODE_SELF -> {
                println("parent ${ProcessHandle.current().parent().map { it.pid().toString() }.orElse("unknown")}")
                // The kernel's own account of this process's limits, on a
                // system that gives one.
                val limits = File("/proc/self/limits")
                if (limits.canRead())
                    limits.readLines().filter { it.startsWith("Max core file size") }.forEach(::println)
                exitProcess(0)
            }
        }

        val library = File(System.getProperty(NativeChild.LIBRARY_PROPERTY) ?: "")
        if (NativeRomHasher.tryLoad(library) == null) {
            System.err.println("could not load $library: ${NativeRomHasher.lastError()}")
            exitProcess(NativeChild.EXIT_NO_LIBRARY)
        }
        when (val outcome = RcheevosNative.hash(args[0], args.getOrNull(1)?.toInt() ?: 0)) {
            is HashOutcome.Ok ->
                println("${NativeChild.ANSWER} hash ${outcome.result.hash}|${outcome.result.consoleId}")
            // On one line whatever the reason holds: the parent reads the
            // answer by its line.
            is HashOutcome.Failed ->
                println("${NativeChild.ANSWER} nohash ${outcome.reason.replace(Regex("[\\r\\n]+"), " ")}")
            // The native hasher has no other answer; one that came would be
            // no line here, and a crash to the parent.
            else -> System.err.println("neither a hash nor a failure: $outcome")
        }
    }
}
