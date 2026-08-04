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

    /** `$XDG_DATA_HOME/pegasus-bridge`, falling back to `~/.local/share`. */
    fun defaultDataRoot(env: Map<String, String> = System.getenv(),
                        home: String = System.getProperty("user.home")): File {
        val xdg = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() && File(it).isAbsolute }
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
