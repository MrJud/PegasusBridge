package com.pegasus.bridge.pegasus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling an app that asked for this extension from one that takes anything.
 *
 * The fixture is what the Galaxy Tab S8+ actually answered on 2026-08-22, cut
 * down to the packages that make the point. Twenty-five activities answer a
 * `.dsk` under `application/octet-stream`; twenty-four of them answer the same
 * for `.wxyzcontrol`, which is not a file format anyone has ever shipped.
 */
class IntentDiscoveryTest {

    private val colem   = IntentDiscovery.Handler("com.fms.colem", "com.fms.emulib.MainActivity", "ColEm")
    private val azimuth = IntentDiscovery.Handler("johnidis.azimuth", "johnidis.azimuth.ui.SplashScreen", "Azimuth")
    private val cpcemu  = IntentDiscovery.Handler("com.loritznet.softwarecreations.cpcemu",
                                                  "com.loritznet.softwarecreations.CPCemu", "CPCemu")
    private val rar     = IntentDiscovery.Handler("com.rarlab.rar", "com.rarlab.rar.MainActivity", "RAR")
    private val myfiles = IntentDiscovery.Handler("com.sec.android.app.myfiles",
                                                  "com.sec.android.app.myfiles.ui.MainActivity", "My Files")

    /** As measured: ColEm declares a pattern for .dsk, the rest take any octet-stream. */
    private val tablet: (String, String?) -> List<IntentDiscovery.Handler> = { ext, type ->
        when {
            type == "application/octet-stream" && ext == "dsk" -> listOf(colem, cpcemu, rar, myfiles)
            type == "application/octet-stream"                 -> listOf(cpcemu, rar, myfiles)
            type == null && ext == "dsk"                       -> listOf(azimuth)
            else                                               -> emptyList()
        }
    }

    @Test fun `the control removes everything that was not about the extension`() {
        val r = IntentDiscovery.discover(listOf("dsk"), ask = tablet)
        val specific = r.candidates.filter { it.specific }.map { it.packageName }.sorted()
        assertEquals(listOf("com.fms.colem", "johnidis.azimuth"), specific)
    }

    /**
     * CPCemu is the honest limit. It is a real emulator and it declares only a
     * MIME type, so it lands among the archivers with no signal to separate it.
     * Reported, never promoted.
     */
    @Test fun `an emulator that declares only a MIME type stays in the heap`() {
        val r = IntentDiscovery.discover(listOf("dsk"), ask = tablet)
        val cpc = r.candidates.single { it.packageName == cpcemu.packageName }
        assertFalse(cpc.specific)
        assertTrue(cpc.because, cpc.because.contains("MIME type"))
    }

    /** The size of what was removed is part of the answer, not a detail. */
    @Test fun `the control size is reported per query`() {
        val r = IntentDiscovery.discover(listOf("dsk"), ask = tablet)
        assertEquals(3, r.controlSize["application/octet-stream"])
        assertEquals(0, r.controlSize["no type"])
    }

    /** One row per package per extension, even when two queries both match. */
    @Test fun `a package answering under two types is reported once`() {
        val both: (String, String?) -> List<IntentDiscovery.Handler> = { ext, _ ->
            if (ext == "dsk") listOf(colem) else emptyList()
        }
        val r = IntentDiscovery.discover(listOf("dsk"), ask = both)
        assertEquals(1, r.candidates.count { it.packageName == colem.packageName })
        assertTrue(r.candidates.single().specific)
    }

    /** What the table already knows is shown, not hidden — it is the proof the query works. */
    @Test fun `a package already in the table is marked rather than dropped`() {
        val r = IntentDiscovery.discover(listOf("dsk"), setOf("com.fms.colem"), tablet)
        assertTrue(r.candidates.single { it.packageName == "com.fms.colem" }.known)
        assertFalse(r.candidates.single { it.packageName == "johnidis.azimuth" }.known)
    }

    /** Asking about the control itself would compare a thing with itself. */
    @Test fun `the control extension is never asked about as a real one`() {
        val r = IntentDiscovery.discover(listOf(IntentDiscovery.CONTROL), ask = tablet)
        assertTrue(r.candidates.isEmpty())
    }
}
