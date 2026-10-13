package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.BridgePaths
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.fail

/**
 * One scan of a folder, made in a JVM of its own, with a hasher that can end
 * that JVM on a file it is told the name of.
 *
 * What a scan does after one whose process died can be tried in the tests'
 * own JVM only from a trace written by hand. Here the process does die, in
 * the hasher, with the scan's other files in whatever state a real death
 * leaves them, and what the next process finds on disk is what a device
 * would hold. No native library is loaded: the hasher is a few lines of
 * Kotlin, and the way it ends the process is the JVM's own.
 *
 * [NativeChild] is the like of this for one call into the library.
 */
internal object ScanChild {

    /** How the hasher ends the process when it is handed the file named. */
    enum class On {
        /** It does not: every file is hashed. */
        NOTHING,
        /** As a signal ends a process: at once, and nothing runs on the way out. */
        HALT,
        /** Never answers, for the parent to kill the process when it sees fit. */
        SLEEP,
        /** As a service that is told to stop: the JVM runs its shutdown hooks and goes. */
        EXIT
    }

    /** The child's status when its hasher halted it. */
    const val HALTED = 37

    /** What the child prints when its scan came back. */
    const val DONE = "scan-child: done"

    const val LIMIT_SECONDS = 60L

    /**
     * Starts a scan of [roms] with the data root [data]. The hasher writes
     * "> name" to [handed] as it is given a file and "< name" when it has
     * answered, a line at a time, and does [on] when the file is called
     * [name]. What the child prints goes to [out].
     */
    fun start(data: File, roms: File, handed: File, out: File, on: On = On.NOTHING, name: String = ""): Process {
        val classpath = System.getProperty(NativeChild.CLASSPATH_PROPERTY)
            ?: fail("${NativeChild.CLASSPATH_PROPERTY} is not set: run this through Gradle, whose test task sets it")
        return ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path, "-cp", classpath,
            ScanChildMain::class.java.name, data.path, roms.path, handed.path, on.name, name)
            .directory(data.parentFile)
            // To a file and not to a pipe, which a child that nobody reads
            // from fills up and then waits on.
            .redirectErrorStream(true).redirectOutput(out)
            .start()
    }

    /** The same, waited for. Answers the child's status, and fails when it is still running after the limit. */
    fun run(data: File, roms: File, handed: File, out: File, on: On = On.NOTHING, name: String = ""): Int {
        val process = start(data, roms, handed, out, on, name)
        if (!process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            fail("the scan's process was still running after $LIMIT_SECONDS s: ${out.readText().takeLast(700)}")
        }
        return process.exitValue()
    }
}

/** The child's side of [ScanChild]: data root, ROM folder, the file of what was handed, what to do and on which file. */
internal object ScanChildMain {
    /** How long a child told to exit takes over it: time for several hashes to end and others to begin. */
    private const val LINGER_MS = 400L

    @JvmStatic
    fun main(args: Array<String>) {
        val handed = File(args[2])
        val on = ScanChild.On.valueOf(args[3])
        val name = args[4]
        // A line is one write to a file opened for it, so that it is with
        // the system before the process ends on the line after.
        fun note(line: String) = FileOutputStream(handed, true).use { it.write("$line\n".toByteArray()) }

        val hasher = object : RomHasher {
            override fun hash(path: String): HashResult? {
                val file = File(path)
                note("> ${file.name}")
                if (file.name == name) when (on) {
                    ScanChild.On.HALT -> Runtime.getRuntime().halt(ScanChild.HALTED)
                    ScanChild.On.SLEEP -> Thread.sleep(Long.MAX_VALUE)
                    ScanChild.On.EXIT -> exitProcess(0)
                    ScanChild.On.NOTHING -> Unit
                }
                // Long enough that a file begun beside this one would have
                // its line between this file's two.
                Thread.sleep(20)
                val text = file.readText().trim { it <= ' ' }
                note("< ${file.name}")
                return HashResult(text, 7)
            }
        }
        val lookup = object : RaHashLookup {
            override suspend fun lookup(hash: String): LookupOutcome = LookupOutcome.NotFound
        }
        // A JVM told to go is not gone at once: every hook has its turn,
        // and a daemon's own takes its time over the server. The hashes in
        // flight go on meanwhile, end, and others are begun.
        if (on == ScanChild.On.EXIT) Runtime.getRuntime().addShutdownHook(Thread { Thread.sleep(LINGER_MS) })
        runBlocking {
            RomScanPipeline(BridgePaths(File(args[0])), hasher, lookup, ledgerSaveEveryMs = 0)
                .scan(listOf(args[1]))
        }
        println(ScanChild.DONE)
        exitProcess(0)
    }
}
