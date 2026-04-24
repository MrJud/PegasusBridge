package com.pegasus.bridge.hasher

import android.util.Log

object NativeHasher {

    private const val TAG = "NativeHasher"

    init {
        System.loadLibrary("rahasher")
    }

    fun hash(path: String): HashResult? {
        return try {
            val raw = hashFile(path) ?: return null
            val parts = raw.split("|", limit = 2)
            if (parts.size == 2) HashResult(hash = parts[0], consoleId = parts[1].toIntOrNull() ?: 0)
            else null
        } catch (e: Exception) {
            Log.e(TAG, "Hash failed for $path", e)
            null
        }
    }

    private external fun hashFile(path: String): String?
}

data class HashResult(val hash: String, val consoleId: Int)
