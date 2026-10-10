package com.pegasus.bridge.core

import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.SyncFailedException
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
        BridgePaths.force = BridgePaths.Force()
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

    // ── a write that has to outlast a power cut ─────────────────────────────

    /**
     * Sees what a durable write forces, and what was on disk at that moment:
     * the text of the file being forced and the text under the target's name.
     * It forces as the real one does, so the tests that use it run the calls
     * a scan makes.
     */
    private class SeenForce(private val target: File) : BridgePaths.Force() {
        val seen = mutableListOf<String>()
        private fun now() = if (target.isFile) target.readText() else "nothing"

        override fun file(file: File, fd: FileDescriptor) {
            seen += "file ${file.name} holding '${file.readText()}' while the target holds '${now()}'"
            super.file(file, fd)
        }
        override fun directory(dir: File) {
            seen += "directory ${dir.name} while the target holds '${now()}'"
            super.directory(dir)
        }
    }

    // The order is the point. Forced after the move, the bytes are no safer
    // than they were: the name is already on a file that may not be on disk.
    @Test fun `a durable write forces the file before the move and the directory after it`() {
        val target = File(root, "cache/ledger.json")
        BridgePaths.writeAtomic(target, "first")
        val force = SeenForce(target).also { BridgePaths.force = it }

        BridgePaths.writeAtomic(target, "second", durable = true)

        assertEquals(listOf(
            "file ledger.json.tmp holding 'second' while the target holds 'first'",
            "directory cache while the target holds 'second'"), force.seen)
        assertEquals("second", target.readText())
        assertEquals(listOf("ledger.json"), File(root, "cache").list()!!.toList())
    }

    // The record of a running job goes through the same function for every
    // report, and a force each time is what the parameter is there to avoid.
    @Test fun `a write that is not asked to be durable forces nothing`() {
        val target = File(root, "pending/job1.json")
        val force = SeenForce(target).also { BridgePaths.force = it }
        BridgePaths.writeAtomic(target, "first")
        BridgePaths.writeAtomic(target, "second")
        assertEquals(emptyList(), force.seen)
        assertEquals("second", target.readText())
    }

    // Windows cannot force a directory and Android's shared storage may refuse
    // to: the file is whole and in place by then, and the write has succeeded.
    @Test fun `a directory that cannot be forced does not fail a durable write`() {
        val target = File(root, "cache/ledger.json")
        BridgePaths.writeAtomic(target, "first")
        BridgePaths.force = object : BridgePaths.Force() {
            override fun directory(dir: File) = throw IOException("no force for a directory here")
        }
        BridgePaths.writeAtomic(target, "second", durable = true)
        assertEquals("second", target.readText())
        assertEquals(listOf("ledger.json"), File(root, "cache").list()!!.toList())
    }

    // A file that is not known to be on disk must not take the place of one
    // that is. The failure is the caller's to log, and the ledger's save does.
    @Test fun `a file that cannot be forced is not moved onto the one there is`() {
        val target = File(root, "cache/ledger.json")
        BridgePaths.writeAtomic(target, "first")
        BridgePaths.force = object : BridgePaths.Force() {
            override fun file(file: File, fd: FileDescriptor) = throw SyncFailedException("not on disk")
        }
        assertFailsWith<SyncFailedException> { BridgePaths.writeAtomic(target, "second", durable = true) }
        assertEquals("first", target.readText())
    }

    // The fall-back is the one a plain write has: a target the move cannot
    // replace is refused by the write in place, with its reason.
    @Test fun `a durable write onto something that cannot be replaced still fails`() {
        val target = File(root, "taken").apply { mkdirs() }
        File(target, "inside").writeText("x")
        assertFailsWith<IOException> { BridgePaths.writeAtomic(target, "text", durable = true) }
        assertTrue(File(target, "inside").isFile)
    }
}
