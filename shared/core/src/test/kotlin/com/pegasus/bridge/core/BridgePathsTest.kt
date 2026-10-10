package com.pegasus.bridge.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BridgePathsTest {

    private lateinit var root: File
    private lateinit var paths: BridgePaths

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("bridge-paths-test").toFile()
        paths = BridgePaths(root)
    }

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun `ensureAll creates every directory the contract names`() {
        paths.ensureAll()
        listOf(paths.config, paths.metadata, paths.media, paths.search, paths.searchRa,
               paths.scrape, paths.download, paths.pending, paths.done,
               paths.profile, paths.completion)
            .forEach { assertTrue(it.isDirectory, "${it.name} not created") }
    }

    @Test fun `layout is rooted where it was told, not hardcoded`() {
        assertEquals(File(root, "scrape/job1.json"), paths.scrape("job1"))
        assertEquals(File(root, "done/job1.done"),   paths.done("job1"))
        assertEquals(File(root, "config/credentials.json"), paths.credentials)
        assertEquals(File(root, "metadata/_index.json"), paths.discoveryIndex)
    }

    // Qt's QML XMLHttpRequest cannot tell a missing file from an empty one over
    // file://: both report status 0 with an empty body, and only a non-empty file
    // reports 200. A zero-byte marker is therefore invisible to the theme, which
    // is why every job used to look finished on the first poll.
    @Test fun `done marker is not empty`() {
        paths.markDone("job1")
        val marker = paths.done("job1")
        assertTrue(marker.isFile, "marker not created")
        assertTrue(marker.length() > 0, "marker must carry content to be visible to QML")
        assertTrue(marker.readText().contains("job1"))
    }

    @Test fun `atomic write leaves no temp file behind`() {
        val target = File(root, "sub/out.json")
        BridgePaths.writeAtomic(target, """{"a":1}""")
        assertEquals("""{"a":1}""", target.readText())
        assertTrue(File(root, "sub").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun `atomic write overwrites an existing file`() {
        val target = File(root, "out.json")
        BridgePaths.writeAtomic(target, "first")
        BridgePaths.writeAtomic(target, "second")
        assertEquals("second", target.readText())
    }

    // The ledger is rewritten many times in one scan, always over itself.
    @Test fun `atomic write over an existing file leaves the new text and no temp file`() {
        val target = File(root, "cache/ledger.json")
        BridgePaths.writeAtomic(target, "first, and the longer of the two")
        BridgePaths.writeAtomic(target, "second")
        assertEquals("second", target.readText())
        assertEquals(listOf("ledger.json"), File(root, "cache").list()!!.toList())
    }

    // A target the move cannot replace, here a folder that is not empty, is
    // refused as it always was: by the write in place, and with its reason.
    @Test fun `atomic write onto something that cannot be replaced still fails`() {
        val target = File(root, "taken").apply { mkdirs() }
        File(target, "inside").writeText("x")
        assertFailsWith<java.io.IOException> { BridgePaths.writeAtomic(target, "text") }
        assertTrue(File(target, "inside").isFile)
    }
}
