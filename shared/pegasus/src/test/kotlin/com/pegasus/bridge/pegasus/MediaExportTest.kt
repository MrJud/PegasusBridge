package com.pegasus.bridge.pegasus

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Putting a fetched picture where Pegasus will actually find it.
 *
 * The rules under test come from Pegasus' own source rather than its docs —
 * `providers/pegasus_media/MediaProvider.cpp` and
 * `providers/skraper/SkraperAssetsProvider.cpp` — because the docs give the
 * directory names and not the matching rule, and the matching rule is where the
 * library this was written against goes wrong: 40 artwork files across two
 * collections, none of which Pegasus can match to a ROM.
 */
class MediaExportTest {

    private lateinit var dataRoot: File
    private lateinit var library: File
    private lateinit var manifest: ExportManifest
    private lateinit var exporter: MediaExporter

    @BeforeTest fun setUp() {
        BridgeLog.current = NoopLog
        dataRoot = Files.createTempDirectory("export-data").toFile()
        library  = Files.createTempDirectory("export-lib").toFile()
        manifest = ExportManifest(File(dataRoot, ExportManifest.FILE_NAME))
        exporter = MediaExporter(manifest) { f, t -> BridgePaths.writeAtomic(f, t) }
    }

    @AfterTest fun tearDown() {
        dataRoot.deleteRecursively(); library.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun collection(name: String) = File(library, name).apply { mkdirs() }

    private fun rom(dir: File, name: String) = File(dir, name).apply { writeText("rom bytes") }

    private fun artwork(name: String, bytes: String = "picture bytes"): File {
        val d = File(dataRoot, "artwork").apply { mkdirs() }
        return File(d, name).apply { writeText(bytes) }
    }

    // ── The naming rule, which is the whole point ───────────────────────────

    // Skraper matches `completeBaseName`, and completeBaseName strips only the
    // LAST dot. "Super Mario Bros. (World).nes" is "Super Mario Bros. (World)",
    // not "Super Mario Bros" — a mistake that would silently miss every ROM whose
    // title contains a full stop, which is a great many of them.
    @Test fun `the exported name is the rom base name, dots and all`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Super Mario Bros. (World).nes")

        val r = exporter.export(artwork("ss-1245-cover-abc.png"), romFile, nes,
                                "cover", "abc", AssetLayout.Style.SKRAPER)

        assertTrue(r is MediaExporter.Outcome.Written, "got $r")
        assertEquals("Super Mario Bros. (World).png", r.target.name)
        assertEquals("box2dfront", r.target.parentFile.name)
        assertEquals("media", r.target.parentFile.parentFile.name)
    }

    // This is the defect, reproduced: art named after a sanitised title cannot be
    // matched, and art named after the ROM can.
    @Test fun `the name Pegasus would match is the rom file's, never the title's`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Castlevania III - Dracula's Curse (USA).nes")

        val r = exporter.export(artwork("ss-1.png"), romFile, nes,
                                "cover", "v1", AssetLayout.Style.SKRAPER)
                as MediaExporter.Outcome.Written

        assertEquals("Castlevania III - Dracula's Curse (USA).png", r.target.name)
        assertFalse(File(r.target.parentFile, "Castlevania III Draculas Curse.png").isFile,
                    "the sanitised-title name is the one Pegasus cannot match")
    }

    @Test fun `the native layout puts the asset type in the file name`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")

        val r = exporter.export(artwork("c.png"), romFile, nes,
                                "cover", "v1", AssetLayout.Style.NATIVE)
                as MediaExporter.Outcome.Written

        assertEquals("boxFront.png", r.target.name)
        assertEquals("Contra (USA)", r.target.parentFile.name)
        assertEquals("media", r.target.parentFile.parentFile.name)
    }

    @Test fun `each bridge kind lands in the directory its provider searches`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val expected = mapOf(
            "cover" to "box2dfront", "wheel" to "wheel",
            "wallpaper" to "fanart", "screenshot" to "screenshot", "video" to "videos")

        for ((bridgeKind, dir) in expected) {
            val ext = if (bridgeKind == "video") "mp4" else "png"
            val r = exporter.export(artwork("$bridgeKind.$ext"), romFile, nes,
                                    bridgeKind, "v-$bridgeKind", AssetLayout.Style.SKRAPER)
            assertTrue(r is MediaExporter.Outcome.Written, "$bridgeKind: got $r")
            assertEquals(dir, r.target.parentFile.name, "wrong directory for $bridgeKind")
        }
    }

    // ── Detecting which layout a collection already uses ────────────────────

    @Test fun `a collection with skraper directories is detected as skraper`() {
        val nes = collection("nes")
        File(nes, "media/box2dfront").mkdirs()
        assertEquals(AssetLayout.Style.SKRAPER, AssetLayout.detectStyle(nes))
    }

    @Test fun `a collection with per-game directories is detected as native`() {
        val nes = collection("nes")
        File(nes, "media/Contra (USA)").mkdirs()
        assertEquals(AssetLayout.Style.NATIVE, AssetLayout.detectStyle(nes))
    }

    @Test fun `a collection with no media directory takes the fallback`() {
        assertEquals(AssetLayout.Style.SKRAPER, AssetLayout.detectStyle(collection("nes")))
        assertEquals(AssetLayout.Style.NATIVE,
                     AssetLayout.detectStyle(collection("snes"), AssetLayout.Style.NATIVE))
    }

    // ── Ownership, which is what makes writing there safe at all ────────────

    @Test fun `a file the bridge did not write is left alone`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val theirs = File(nes, "media/box2dfront/Contra (USA).png").apply {
            parentFile.mkdirs(); writeText("the user's own careful scan")
        }

        val r = exporter.export(artwork("c.png"), romFile, nes,
                                "cover", "v1", AssetLayout.Style.SKRAPER)

        assertTrue(r is MediaExporter.Outcome.Occupied, "got $r")
        assertEquals("the user's own careful scan", theirs.readText())
    }

    @Test fun `a file the bridge wrote is replaced when the picture changes`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")

        exporter.export(artwork("a.png", "old picture"), romFile, nes,
                        "cover", "variant-1", AssetLayout.Style.SKRAPER)
        val r = exporter.export(artwork("b.png", "new picture"), romFile, nes,
                                "cover", "variant-2", AssetLayout.Style.SKRAPER)

        assertTrue(r is MediaExporter.Outcome.Written, "got $r")
        assertEquals("new picture", r.target.readText())
    }

    @Test fun `the same picture twice does nothing the second time`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val src = artwork("a.png", "picture")

        val first = exporter.export(src, romFile, nes, "cover", "v1",
                                    AssetLayout.Style.SKRAPER) as MediaExporter.Outcome.Written
        first.target.setLastModified(first.target.lastModified() - 60_000)
        val stamp = first.target.lastModified()

        val second = exporter.export(src, romFile, nes, "cover", "v1", AssetLayout.Style.SKRAPER)

        assertTrue(second is MediaExporter.Outcome.UpToDate, "got $second")
        assertEquals(stamp, first.target.lastModified(), "the file was rewritten for nothing")
    }

    @Test fun `replacing a foreign file happens only when asked`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        File(nes, "media/box2dfront/Contra (USA).png").apply {
            parentFile.mkdirs(); writeText("theirs")
        }

        val r = exporter.export(artwork("c.png", "ours"), romFile, nes, "cover", "v1",
                                AssetLayout.Style.SKRAPER, replaceForeign = true)

        assertTrue(r is MediaExporter.Outcome.Written, "got $r")
        assertEquals("ours", r.target.readText())
    }

    // ── Taking it back ──────────────────────────────────────────────────────

    @Test fun `revert removes exactly what was exported and nothing else`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val theirs = File(nes, "media/box2dfront/Someone Elses Game.png").apply {
            parentFile.mkdirs(); writeText("not ours")
        }
        val ours = (exporter.export(artwork("c.png"), romFile, nes, "cover", "v1",
                                    AssetLayout.Style.SKRAPER) as MediaExporter.Outcome.Written).target

        val result = exporter.revert()

        assertEquals(1, result.removed)
        assertFalse(ours.isFile, "the exported file survived the revert")
        assertTrue(theirs.isFile, "revert deleted a file the Bridge never wrote")
    }

    // Someone replacing an exported picture by hand has made a decision, and a
    // revert that deleted it would be throwing that decision away.
    @Test fun `revert leaves a file somebody has since changed`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val ours = (exporter.export(artwork("c.png"), romFile, nes, "cover", "v1",
                                    AssetLayout.Style.SKRAPER) as MediaExporter.Outcome.Written).target
        ours.writeText("a much better cover, chosen by a person")

        val result = exporter.revert()

        assertEquals(0, result.removed)
        assertEquals(1, result.changed)
        assertTrue(ours.isFile)
    }

    @Test fun `the manifest survives a restart`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        exporter.export(artwork("c.png"), romFile, nes, "cover", "v1", AssetLayout.Style.SKRAPER)
        exporter.save()

        val reopened = ExportManifest(File(dataRoot, ExportManifest.FILE_NAME))
        assertEquals(1, reopened.size)
        assertTrue(reopened.owns(File(nes, "media/box2dfront/Contra (USA).png")))
    }

    @Test fun `a kind pegasus has no slot for is reported rather than guessed at`() {
        val nes = collection("nes")
        val r = exporter.export(artwork("x.png"), rom(nes, "Contra (USA).nes"), nes,
                                "some-kind-nobody-defined", "v1")
        assertTrue(r is MediaExporter.Outcome.Unsupported, "got $r")
    }

    // A user's own picture may be in either layout, and neither counts as ours.
    @Test fun `an existing asset is found in whichever layout it uses`() {
        val nes = collection("nes")
        File(nes, "media/box2dfront/Contra (USA).png").apply {
            parentFile.mkdirs(); writeText("skraper style")
        }
        File(nes, "media/Contra (USA)/boxFront.jpg").apply {
            parentFile.mkdirs(); writeText("native style")
        }

        val found = AssetLayout.existingCandidates(nes, AssetLayout.Kind.BOX_FRONT, "Contra (USA)")
        assertEquals(2, found.size, "found: ${found.map { it.path }}")
    }
}
