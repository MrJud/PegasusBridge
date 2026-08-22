package com.pegasus.bridge

import android.app.Application
import com.pegasus.bridge.core.Paths
import com.pegasus.bridge.hasher.RomScanExtensions
import com.pegasus.bridge.hasher.RomScanner
import com.pegasus.bridge.pegasus.MetadataFile

class DataLayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Paths.ensureAll()

        // What a collection says it contains beats the scanner's built-in list.
        // Installed here because this is the only place that sees both modules;
        // see RomScanExtensions for why the hasher cannot ask by itself.
        //
        // A union rather than a replacement, matching the daemon exactly: a
        // collection that forgets to list `zip` should not thereby lose its
        // archives, and one that spells an extension of its own should not need
        // a new APK to be seen.
        RomScanExtensions.install { dir ->
            val declared = runCatching { MetadataFile.readCollection(dir)?.extensions }
                .getOrNull().orEmpty()
            if (declared.isEmpty()) RomScanner.ROM_EXTENSIONS
            else RomScanner.ROM_EXTENSIONS + declared.map { it.lowercase() }
        }
    }
}
