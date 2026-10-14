package com.pegasus.bridge.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `credentials.json` holds every key the user has, and on the desktop it is
 * its owner's only, in a folder only its owner can open. An older build wrote
 * both with the umask's permissions: a file every account on the machine could
 * read wherever the folder let it in.
 */
class CredentialsOwnerOnlyTest {

    private lateinit var root: File
    private lateinit var paths: BridgePaths
    private val logged = mutableListOf<String>()
    private val realPosix = BridgePaths.posix

    @BeforeTest fun setUp() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews(), "no POSIX permissions here")
        root = Files.createTempDirectory("bridge-owner-test").toFile()
        paths = BridgePaths(root)
        BridgeLog.current = object : BridgeLog {
            override fun d(tag: String, msg: String) { logged += msg }
            override fun i(tag: String, msg: String) { logged += msg }
            override fun w(tag: String, msg: String, t: Throwable?) { logged += msg }
            override fun e(tag: String, msg: String, t: Throwable?) { logged += msg }
        }
    }

    @AfterTest fun tearDown() {
        BridgePaths.posix = realPosix
        BridgePaths.watch = null
        BridgeLog.current = StderrLog
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun perms(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))
    private fun setPerms(f: File, p: String) = Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(p))

    private val OLD = """{"schemaVersion":1,"ra":{"user":"MrJud","apiKey":"RA-SECRET"}}"""

    /** The folder and the file as a build before this one left them. */
    private fun leftByOlderBuild(): File {
        paths.config.mkdirs()
        setPerms(paths.config, "rwxr-xr-x")
        paths.credentials.writeText(OLD)
        setPerms(paths.credentials, "rw-r--r--")
        return paths.credentials
    }

    @Test fun `a first write makes the folder and the file their owner's only`() {
        Config(paths).writeCredentials(sgdbKey = "SG-SECRET")

        assertEquals("rwx------", perms(paths.config))
        assertEquals("rw-------", perms(paths.credentials))
        assertEquals("SG-SECRET", Config(paths).load().steamGridDb?.apiKey)
        assertEquals(listOf("credentials.json"), paths.config.list()!!.toList())
    }

    // Made owner-only, not changed to it after the secret is in: the hook sees
    // the temporary file as soon as it is open, before its first byte, and
    // again with all of it written. A temporary file a cut-off write left
    // open to others is not written over.
    @Test fun `the temporary file is never open to others while it is written`() {
        val seen = mutableListOf<String>()
        BridgePaths.watch = { f -> seen += "${f.name} ${perms(f)} ${f.length()}" }
        val config = Config(paths)

        config.writeCredentials(raUser = "MrJud", raApiKey = "RA-SECRET")
        config.writeCredentials(sgdbKey = "SG-SECRET")
        File(paths.config, "credentials.json.tmp").also { it.writeText("left by a write that was cut off") }
            .also { setPerms(it, "rw-r--r--") }
        config.saveToken("igdb", "TOKEN-SECRET", 99L)

        assertEquals(6, seen.size, "every write is seen before and after its bytes")
        seen.forEachIndexed { i, s ->
            assertTrue(s.startsWith("credentials.json.tmp rw------- "), s)
            if (i % 2 == 0) assertTrue(s.endsWith(" 0"), "seen with something in it already: $s")
        }
        assertEquals("rw-------", perms(paths.credentials))
        assertEquals(listOf("credentials.json"), paths.config.list()!!.toList())
        assertEquals("TOKEN-SECRET", config.load().igdb?.cachedToken)
    }

    @Test fun `credentials an older build left open are closed by the next write, every block kept`() {
        leftByOlderBuild()

        Config(paths).writeCredentials(sgdbKey = "SG-SECRET")

        assertEquals("rwx------", perms(paths.config))
        assertEquals("rw-------", perms(paths.credentials))
        val c = Config(paths).load()
        assertEquals("RA-SECRET", c.ra?.apiKey)
        assertEquals("SG-SECRET", c.steamGridDb?.apiKey)
    }

    // What the daemon asks for as it starts. The file's bytes and its time are
    // what they were: only who may read it changes, and the log names the file
    // and the folder and nothing in them.
    @Test fun `keepToOwner closes them without rewriting the file and logs no value`() {
        val f = leftByOlderBuild()
        f.setLastModified(1_600_000_000_000L)
        val bytes = f.readBytes()

        Config(paths).keepToOwner()

        assertEquals("rwx------", perms(paths.config))
        assertEquals("rw-------", perms(f))
        assertContentEquals(bytes, f.readBytes())
        assertEquals(1_600_000_000_000L, f.lastModified())
        assertTrue(logged.any { it.startsWith("${paths.config} was open to others") }, "$logged")
        assertTrue(logged.any { it.startsWith("$f was open to others") }, "$logged")
        assertTrue(logged.none { "SECRET" in it || "MrJud" in it }, "$logged")

        logged.clear()
        Config(paths).keepToOwner()
        assertEquals(emptyList(), logged, "nothing to say once they are closed")
    }

    // A config folder that is not there yet is made owner-only, not made and
    // then narrowed.
    @Test fun `keepToOwner makes a missing config folder its owner's only`() {
        Config(paths).keepToOwner()
        assertEquals("rwx------", perms(paths.config))
        assertEquals(emptyList(), paths.config.list()!!.toList())
        assertEquals(emptyList(), logged)
    }

    // Windows, and any file system that keeps no POSIX permissions: the
    // writes and the start go on as they did, and nothing is changed or fails.
    @Test fun `where there are no POSIX permissions nothing changes and nothing fails`() {
        BridgePaths.posix = { false }
        val f = leftByOlderBuild()

        Config(paths).keepToOwner()
        assertEquals("rwxr-xr-x", perms(paths.config))
        assertEquals("rw-r--r--", perms(f))

        Config(paths).writeCredentials(sgdbKey = "SG-SECRET")
        val plain = File(root, "plain").apply { writeText("x") }
        assertEquals("rwxr-xr-x", perms(paths.config))
        assertEquals(perms(plain), perms(f), "the file is what any file is made with here")
        val c = Config(paths).load()
        assertEquals("RA-SECRET", c.ra?.apiKey)
        assertEquals("SG-SECRET", c.steamGridDb?.apiKey)

        val fresh = BridgePaths(File(root, "fresh"))
        Config(fresh).writeCredentials(sgdbKey = "SG-SECRET")
        val plainDir = File(root, "plainDir").apply { mkdirs() }
        assertEquals(perms(plainDir), perms(fresh.config))
        assertEquals(perms(plain), perms(fresh.credentials))
        assertTrue(logged.none { "open to others" in it || "could not keep" in it }, "$logged")
    }

    /** A file outside the config folder, the way another account would leave one to be written into. */
    private fun outside(name: String = "elsewhere.json"): File =
        File(root, name).apply { writeText("""{"note":"not the credentials"}"""); setPerms(this, "rw-r--r--") }

    private fun link(at: File, to: File) = Files.createSymbolicLink(at.toPath(), to.toPath()).toFile()

    // The temporary file is made by the call that opens it, and anything at its
    // name by then is an error: a link another account puts there between the
    // removal and the making is not followed into a file of theirs, nor is what
    // it points to narrowed. One there before the write is removed, as a link.
    @Test fun `nothing at the temporary name is followed or written into`() {
        val target = outside()
        val before = target.readBytes()
        paths.config.mkdirs()
        val tmp = File(paths.config, "credentials.json.tmp")
        link(tmp, target)

        assertFailsWith<FileAlreadyExistsException> { BridgePaths.writeNew(tmp, "RA-SECRET", durable = false) }
        assertContentEquals(before, target.readBytes())
        assertEquals("rw-r--r--", perms(target))

        Config(paths).writeCredentials(raUser = "MrJud", raApiKey = "RA-SECRET")
        assertContentEquals(before, target.readBytes())
        assertEquals("rw-r--r--", perms(target))
        assertFalse(Files.isSymbolicLink(paths.credentials.toPath()))
        assertEquals("rw-------", perms(paths.credentials))
        assertEquals(listOf("credentials.json"), paths.config.list()!!.toList())
        assertEquals("RA-SECRET", Config(paths).load().ra?.apiKey)
    }

    // Where neither move can be made (here the folder is made read-only once
    // the temporary file is written) the target is written in place, as any
    // write does then: a plain file of this account's, narrowed before the
    // bytes go in, and never what a link at its name points to.
    @Test fun `written in place, only a plain file of its owner's and narrowed first`() {
        val config = Config(paths)
        config.writeCredentials(sgdbKey = "OLD-SECRET")
        setPerms(paths.credentials, "rw-r--r--")
        val seen = mutableListOf<String>()
        BridgePaths.watch = { f ->
            seen += "${f.name} ${perms(f)}"
            if (seen.size == 2) setPerms(paths.config, "r-x------")
        }
        try {
            config.writeCredentials(sgdbKey = "SG-SECRET")
        } finally {
            setPerms(paths.config, "rwx------")
        }
        assertEquals("credentials.json rw-------", seen[2], "$seen")
        assertEquals("rw-------", perms(paths.credentials))
        assertEquals("SG-SECRET", Config(paths).load().steamGridDb?.apiKey)

        File(paths.config, "credentials.json.tmp").delete()
        paths.credentials.delete()
        val target = outside()
        val before = target.readBytes()
        link(paths.credentials, target)
        seen.clear()
        try {
            assertFailsWith<IOException> { config.writeCredentials(sgdbKey = "SG-SECRET") }
        } finally {
            setPerms(paths.config, "rwx------")
        }
        assertContentEquals(before, target.readBytes())
        assertEquals("rw-r--r--", perms(target))
        assertTrue(Files.isSymbolicLink(paths.credentials.toPath()))
    }

    // A link at the file's name, or at the folder's, may point to anybody's
    // file: what it points to is left as it is, and the log says so.
    @Test fun `keepToOwner does not follow a link to narrow what it points to`() {
        val target = outside()
        paths.config.mkdirs()
        link(paths.credentials, target)

        Config(paths).keepToOwner()
        assertEquals("rw-r--r--", perms(target))
        assertTrue(logged.any { it.startsWith("${paths.credentials} is a link") }, "$logged")

        val elsewhere = File(root, "elsewhere-config").apply { mkdirs() }
        setPerms(elsewhere, "rwxr-xr-x")
        val linked = BridgePaths(File(root, "linked")).also { it.root.mkdirs() }
        link(linked.config, elsewhere)
        Config(linked).keepToOwner()
        assertEquals("rwxr-xr-x", perms(elsewhere))
        assertTrue(logged.any { it.startsWith("${linked.config} is a link") }, "$logged")
    }

    // A write an older build cut off left its temporary file, every key in it,
    // with the umask's permissions; only a next write would remove it.
    @Test fun `keepToOwner narrows a temporary file a cut-off write left behind`() {
        leftByOlderBuild()
        val tmp = File(paths.config, "credentials.json.tmp").apply { writeText(OLD) }
        setPerms(tmp, "rw-r--r--")

        Config(paths).keepToOwner()

        assertEquals("rw-------", perms(tmp))
        assertEquals(OLD, tmp.readText())
        assertTrue(logged.none { "SECRET" in it }, "$logged")
    }
}
