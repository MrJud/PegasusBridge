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
    private lateinit var quarantine: File
    private lateinit var manifest: ExportManifest
    private lateinit var exporter: MediaExporter

    @BeforeTest fun setUp() {
        BridgeLog.current = NoopLog
        dataRoot = Files.createTempDirectory("export-data").toFile()
        library  = Files.createTempDirectory("export-lib").toFile()
        quarantine = File(dataRoot, "replaced")
        manifest = ExportManifest(File(dataRoot, ExportManifest.FILE_NAME))
        exporter = MediaExporter(manifest, { f, t -> BridgePaths.writeAtomic(f, t) }, quarantine)
    }

    /** Explicit COLLECTION, because most of these tests predate the Bridge root. */
    private fun into(dir: File) =
        MediaExporter.Destination.InCollection(dir, AssetLayout.Root.COLLECTION)

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

        val r = exporter.export(artwork("ss-1245-cover-abc.png"), romFile, into(nes),
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

        val r = exporter.export(artwork("ss-1.png"), romFile, into(nes),
                                "cover", "v1", AssetLayout.Style.SKRAPER)
                as MediaExporter.Outcome.Written

        assertEquals("Castlevania III - Dracula's Curse (USA).png", r.target.name)
        assertFalse(File(r.target.parentFile, "Castlevania III Draculas Curse.png").isFile,
                    "the sanitised-title name is the one Pegasus cannot match")
    }

    @Test fun `the native layout puts the asset type in the file name`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")

        val r = exporter.export(artwork("c.png"), romFile, into(nes),
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
            val r = exporter.export(artwork("$bridgeKind.$ext"), romFile, into(nes),
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

        val r = exporter.export(artwork("c.png"), romFile, into(nes),
                                "cover", "v1", AssetLayout.Style.SKRAPER)

        assertTrue(r is MediaExporter.Outcome.Occupied, "got $r")
        assertEquals("the user's own careful scan", theirs.readText())
    }

    @Test fun `a file the bridge wrote is replaced when the picture changes`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")

        exporter.export(artwork("a.png", "old picture"), romFile, into(nes),
                        "cover", "variant-1", AssetLayout.Style.SKRAPER)
        val r = exporter.export(artwork("b.png", "new picture"), romFile, into(nes),
                                "cover", "variant-2", AssetLayout.Style.SKRAPER)

        assertTrue(r is MediaExporter.Outcome.Written, "got $r")
        assertEquals("new picture", r.target.readText())
    }

    @Test fun `the same picture twice does nothing the second time`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val src = artwork("a.png", "picture")

        val first = exporter.export(src, romFile, into(nes), "cover", "v1",
                                    AssetLayout.Style.SKRAPER) as MediaExporter.Outcome.Written
        first.target.setLastModified(first.target.lastModified() - 60_000)
        val stamp = first.target.lastModified()

        val second = exporter.export(src, romFile, into(nes), "cover", "v1", AssetLayout.Style.SKRAPER)

        assertTrue(second is MediaExporter.Outcome.UpToDate, "got $second")
        assertEquals(stamp, first.target.lastModified(), "the file was rewritten for nothing")
    }

    // ── Replacing, which must never mean destroying ─────────────────────────

    @Test fun `replacing sets the original aside instead of overwriting it`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        File(nes, "media/box2dfront/Contra (USA).png").apply {
            parentFile.mkdirs(); writeText("a box scan somebody made themselves")
        }

        val r = exporter.export(artwork("c.png", "ours"), romFile, into(nes), "cover", "v1",
                                AssetLayout.Style.SKRAPER,
                                onConflict = MediaExporter.Conflict.REPLACE_KEEPING_ORIGINAL)

        assertTrue(r is MediaExporter.Outcome.Replaced, "got $r")
        assertEquals("ours", r.target.readText())
        assertTrue(r.preserved.isFile, "the original was destroyed rather than kept")
        assertEquals("a box scan somebody made themselves", r.preserved.readText())
        assertTrue(r.preserved.absolutePath.startsWith(quarantine.absolutePath),
                   "the original must be kept in the Bridge's data root, not the library")
    }

    @Test fun `reverting a replacement puts the original back where it was`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val theirPath = File(nes, "media/box2dfront/Contra (USA).png").apply {
            parentFile.mkdirs(); writeText("theirs")
        }
        exporter.export(artwork("c.png", "ours"), romFile, into(nes), "cover", "v1",
                        AssetLayout.Style.SKRAPER,
                        onConflict = MediaExporter.Conflict.REPLACE_KEEPING_ORIGINAL)
        assertEquals("ours", theirPath.readText())

        val result = exporter.revert()

        assertEquals(1, result.removed)
        assertEquals(1, result.restored)
        assertTrue(theirPath.isFile, "the original was not put back")
        assertEquals("theirs", theirPath.readText())
    }

    // Two originals for one game must not collide inside the safety net — losing
    // a picture inside the thing that exists to keep it would be the worst way.
    @Test fun `a second original for the same path gets its own place in the quarantine`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val target = File(nes, "media/box2dfront/Contra (USA).png")

        target.apply { parentFile.mkdirs(); writeText("first original") }
        val a = exporter.export(artwork("a.png", "ours-1"), romFile, into(nes), "cover", "v1",
            AssetLayout.Style.SKRAPER,
            onConflict = MediaExporter.Conflict.REPLACE_KEEPING_ORIGINAL) as MediaExporter.Outcome.Replaced

        // Somebody puts a different picture back by hand, then we replace again.
        manifest.remove(target.absolutePath)
        target.writeText("second original")
        val b = exporter.export(artwork("b.png", "ours-2"), romFile, into(nes), "cover", "v2",
            AssetLayout.Style.SKRAPER,
            onConflict = MediaExporter.Conflict.REPLACE_KEEPING_ORIGINAL) as MediaExporter.Outcome.Replaced

        assertTrue(a.preserved.absolutePath != b.preserved.absolutePath, "the quarantine collided")
        assertEquals("first original", a.preserved.readText())
        assertEquals("second original", b.preserved.readText())
    }

    @Test fun `a mirror writes the same tree somewhere Pegasus will not read`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val elsewhere = File(library, "somewhere-else").apply { mkdirs() }

        val r = exporter.export(artwork("c.png"), romFile,
                                MediaExporter.Destination.Mirror(elsewhere),
                                "cover", "v1") as MediaExporter.Outcome.Written

        assertTrue(r.target.absolutePath.startsWith(elsewhere.absolutePath), r.target.path)
        assertEquals("Contra (USA).png", r.target.name)
        assertEquals("box2dfront", r.target.parentFile.name)
        // And it has not touched the collection at all.
        assertFalse(File(nes, "media").exists(), "a mirror must not write into the library")
    }

    // ── Taking it back ──────────────────────────────────────────────────────

    @Test fun `revert removes exactly what was exported and nothing else`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val theirs = File(nes, "media/box2dfront/Someone Elses Game.png").apply {
            parentFile.mkdirs(); writeText("not ours")
        }
        val ours = (exporter.export(artwork("c.png"), romFile, into(nes), "cover", "v1",
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
        val ours = (exporter.export(artwork("c.png"), romFile, into(nes), "cover", "v1",
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
        exporter.export(artwork("c.png"), romFile, into(nes), "cover", "v1", AssetLayout.Style.SKRAPER)
        exporter.save()

        val reopened = ExportManifest(File(dataRoot, ExportManifest.FILE_NAME))
        assertEquals(1, reopened.size)
        assertTrue(reopened.owns(File(nes, "media/box2dfront/Contra (USA).png")))
    }

    @Test fun `a kind pegasus has no slot for is reported rather than guessed at`() {
        val nes = collection("nes")
        val r = exporter.export(artwork("x.png"), rom(nes, "Contra (USA).nes"), into(nes),
                                "some-kind-nobody-defined", "v1")
        assertTrue(r is MediaExporter.Outcome.Unsupported, "got $r")
    }

    // A user's own picture may be in either layout, and neither counts as ours.
    // ── The Bridge's own root, which is the default ─────────────────────────
    //
    // `.media/` is read by both providers and read *last* by both, so the
    // Bridge fills gaps and never displaces. That is what makes the collision
    // question stop arising rather than being answered.

    @Test fun `the default root is the bridge's own, not the collection's`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")

        val r = exporter.export(artwork("c.png"), romFile,
                                MediaExporter.Destination.InCollection(nes),
                                "cover", "v1", AssetLayout.Style.SKRAPER)
                as MediaExporter.Outcome.Written

        assertEquals(".media", r.target.parentFile.parentFile.name)
        assertEquals("box2dfront", r.target.parentFile.name)
        assertEquals("Contra (USA).png", r.target.name)
    }

    // The whole point: with separate roots there is nothing to collide with, so
    // the user's picture is never even a candidate for being moved.
    @Test fun `the user's media directory is untouched and uncontested`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val theirs = File(nes, "media/box2dfront/Contra (USA).png").apply {
            parentFile.mkdirs(); writeText("the user's own careful scan")
        }

        val r = exporter.export(artwork("c.png", "ours"), romFile,
                                MediaExporter.Destination.InCollection(nes),
                                "cover", "v1", AssetLayout.Style.SKRAPER)

        assertTrue(r is MediaExporter.Outcome.Written, "got $r")
        assertEquals("the user's own careful scan", theirs.readText())
        assertEquals("ours", r.target.readText())
        assertEquals(0, quarantine.walkTopDown().count { it.isFile },
                     "nothing should have needed setting aside")
    }

    @Test fun `skraper root is refused for a collection using the native layout`() {
        assertFalse(AssetLayout.Root.SKRAPER.isReadBy(AssetLayout.Style.NATIVE))
        assertTrue(AssetLayout.Root.SKRAPER.isReadBy(AssetLayout.Style.SKRAPER))
        // The two that both providers read work either way.
        for (style in AssetLayout.Style.entries) {
            assertTrue(AssetLayout.Root.BRIDGE.isReadBy(style))
            assertTrue(AssetLayout.Root.COLLECTION.isReadBy(style))
        }
    }

    // Choosing the wrong root must cost a move, not sixty more API requests.
    @Test fun `migrating moves the files and keeps the manifest in step`() {
        val nes = collection("nes")
        val romFile = rom(nes, "Contra (USA).nes")
        val before = exporter.export(artwork("c.png"), romFile, into(nes),
                                     "cover", "v1", AssetLayout.Style.SKRAPER)
                     as MediaExporter.Outcome.Written
        assertEquals("media", before.target.parentFile.parentFile.name)

        val m = exporter.migrate(AssetLayout.Root.BRIDGE)

        assertEquals(1, m.moved)
        assertEquals(0, m.failed)
        assertFalse(before.target.isFile, "the old file was left behind")
        val moved = File(nes, ".media/box2dfront/Contra (USA).png")
        assertTrue(moved.isFile, "the file did not arrive")
        assertTrue(manifest.owns(moved), "the manifest still points at the old path")
        assertFalse(manifest.owns(before.target))
    }

    @Test fun `migrating twice is a no-op the second time`() {
        val nes = collection("nes")
        exporter.export(artwork("c.png"), rom(nes, "Contra (USA).nes"), into(nes),
                        "cover", "v1", AssetLayout.Style.SKRAPER)
        exporter.migrate(AssetLayout.Root.BRIDGE)
        val again = exporter.migrate(AssetLayout.Root.BRIDGE)
        assertEquals(0, again.moved)
        assertEquals(1, again.alreadyThere)
    }

    // A folder that is not one of Pegasus' three is invisible to it, and saying
    // so is the only honest thing a mirror can do.
    @Test fun `a mirror is not nested under a pegasus root name`() {
        val nes = collection("nes")
        val elsewhere = File(library, "media bridge").apply { mkdirs() }
        val r = exporter.export(artwork("c.png"), rom(nes, "Contra (USA).nes"),
                                MediaExporter.Destination.Mirror(elsewhere),
                                "cover", "v1") as MediaExporter.Outcome.Written
        assertEquals(elsewhere, r.target.parentFile.parentFile)
        assertEquals("box2dfront", r.target.parentFile.name)
    }

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
