package com.pegasus.bridge.daemon

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.NoopLog
import com.pegasus.bridge.core.StderrLog
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * A daemon keeps the credentials to their owner from the moment it starts,
 * not from the first time a key is saved: a file an older build left open to
 * every account on the machine may never be written again.
 */
class CredentialsAtStartTest {

    private lateinit var dataRoot: File
    private var daemon: BridgeDaemon? = null

    @BeforeTest fun setUp() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews(), "no POSIX permissions here")
        dataRoot = Files.createTempDirectory("credentials-start-test").toFile()
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        daemon?.stop()
        if (::dataRoot.isInitialized) dataRoot.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    private fun perms(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))
    private fun setPerms(f: File, p: String) = Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(p))

    private fun start() { daemon = BridgeDaemon(dataRoot, loadHasher = { null }).also { it.start() } }

    @Test fun `a daemon on a new data root makes its config folder its owner's only`() {
        start()
        assertEquals("rwx------", perms(File(dataRoot, "config")))
    }

    @Test fun `a daemon closes credentials an older build left open and leaves what is in them alone`() {
        val config = File(dataRoot, "config").apply { mkdirs() }
        setPerms(config, "rwxr-xr-x")
        val file = File(config, "credentials.json")
        file.writeText("""{"schemaVersion":1,"ra":{"user":"MrJud","apiKey":"RA-SECRET"}}""")
        setPerms(file, "rw-r--r--")
        file.setLastModified(1_600_000_000_000L)
        val bytes = file.readBytes()

        start()

        assertEquals("rwx------", perms(config))
        assertEquals("rw-------", perms(file))
        assertContentEquals(bytes, file.readBytes())
        assertEquals(1_600_000_000_000L, file.lastModified())
    }
}
