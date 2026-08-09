package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgePaths
import java.io.File

/**
 * Where the daemon keeps things on a desktop.
 *
 * Android hardcodes `/sdcard/PegasusData`; desktop follows the XDG spec instead
 * of inventing a location, so the data lands where a user expects and survives
 * a theme reinstall.
 */
object DaemonPaths {

    /**
     * Where the data lives.
     *
     * Windows gets `%LOCALAPPDATA%\pegasus-bridge`. XDG means nothing there, and
     * following it anyway would land the data in `C:\Users\<you>\.local\share`:
     * it works, but it is not where anything else on that machine looks. Choosing
     * now is free — there is no Windows install yet whose data this would move.
     *
     * Everywhere else, unchanged: `$XDG_DATA_HOME/pegasus-bridge`, falling back to
     * `~/.local/share`.
     *
     * `os` is a parameter so both branches are reachable from either platform. The
     * absoluteness check is spelled out rather than left to `File.isAbsolute`,
     * which answers differently per host: a JVM on Windows calls `/custom/share`
     * relative, so the POSIX branch silently fell through to the fallback and the
     * test for it could only ever pass on Linux.
     */
    fun defaultDataRoot(env: Map<String, String> = System.getenv(),
                        home: String = System.getProperty("user.home"),
                        os: String = System.getProperty("os.name")): File {
        if (os.lowercase().contains("win")) {
            val local = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }
                ?: File(home, "AppData\\Local").path
            return File(local, "pegasus-bridge")
        }
        val xdg = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() && it.startsWith("/") }
        return File(xdg ?: File(home, ".local/share").path, "pegasus-bridge")
    }

    /**
     * Written on startup so a client can find the daemon without a fixed port.
     *
     * This is the one file a theme still has to read: everything after it is
     * plain HTTP. Placed inside the data root, which the theme already knows.
     */
    fun endpointFile(dataRoot: File) = File(dataRoot, "daemon.json")

    /**
     * Where to look for the native hasher, in order: an explicit override, then
     * next to the running jar, then the build output, then the system path.
     */
    fun nativeLibraryCandidates(env: Map<String, String> = System.getenv()): List<File> {
        val out = mutableListOf<File>()
        env["PEGASUS_BRIDGE_NATIVE"]?.takeIf { it.isNotBlank() }?.let { out += File(it) }

        val jarDir = runCatching {
            File(BridgeRouter::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        }.getOrNull()
        if (jarDir != null) {
            out += File(jarDir, libName())
            out += File(jarDir, "native/${libName()}")
        }
        out += File("build/native/${libName()}")
        return out.filter { it.isFile }
    }

    /**
     * The defaults file package.sh writes into a built bundle, or null in a build that
     * has none — a plain `gradle run`, or a release packaged without the environment
     * set. It sits beside the jars because it belongs to the application, not to the
     * user: the data root survives an uninstall and this must not.
     */
    fun appDefaultsFile(): File? {
        val jarDir = runCatching {
            File(BridgeRouter::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        }.getOrNull() ?: return null
        return File(jarDir, "app-defaults.json").takeIf { it.isFile }
    }

    fun libName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "rahasher.dll"
            os.contains("mac") -> "librahasher.dylib"
            else               -> "librahasher.so"
        }
    }

    fun bridgePaths(dataRoot: File) = BridgePaths(dataRoot).also { it.ensureAll() }
}
