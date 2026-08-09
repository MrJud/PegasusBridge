package com.pegasus.bridge.core

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {

    private lateinit var root: File
    private lateinit var paths: BridgePaths
    private lateinit var config: Config

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("bridge-config-test").toFile()
        paths = BridgePaths(root)
        paths.ensureAll()
        config = Config(paths)
        BridgeLog.current = NoopLog
    }

    @AfterTest fun tearDown() {
        root.deleteRecursively()
        BridgeLog.current = StderrLog
    }

    @Test fun `missing file yields empty credentials`() {
        val c = config.load()
        assertNull(c.ra)
        assertNull(c.steamGridDb)
        assertNull(c.igdb)
    }

    @Test fun `writes each block and reads it back`() {
        config.writeCredentials(
            raUser = "MrJud", raApiKey = "RAKEY",
            sgdbKey = "SGKEY",
            igdbClientId = "CID", igdbClientSecret = "SEC"
        )
        val c = config.load()
        assertEquals("MrJud", c.ra?.user)
        assertEquals("RAKEY", c.ra?.apiKey)
        assertEquals("SGKEY", c.steamGridDb?.apiKey)
        assertEquals("CID",   c.igdb?.clientId)
        assertEquals("SEC",   c.igdb?.clientSecret)
    }

    // The theme saves one settings field at a time, so a partial write must not
    // wipe the others. This is the defect that left SteamGridDB and IGDB keys
    // stranded in the theme's own storage and never in credentials.json.
    @Test fun `partial write leaves other blocks intact`() {
        config.writeCredentials(sgdbKey = "SGKEY", igdbClientId = "CID", igdbClientSecret = "SEC")
        config.writeCredentials(raUser = "MrJud", raApiKey = "RAKEY")
        config.writeCredentials(sgdbKey = "CHANGED")

        val c = config.load()
        assertEquals("CHANGED", c.steamGridDb?.apiKey)
        assertEquals("CID",     c.igdb?.clientId, "igdb block must survive an sgdb-only write")
        assertEquals("SEC",     c.igdb?.clientSecret)
        assertEquals("MrJud",   c.ra?.user, "ra block must survive an sgdb-only write")
        assertEquals("RAKEY",   c.ra?.apiKey)
    }

    @Test fun `blank and null values are ignored rather than stored`() {
        config.writeCredentials(sgdbKey = "SGKEY")
        config.writeCredentials(sgdbKey = "", raUser = null, raApiKey = "   ")

        val c = config.load()
        assertEquals("SGKEY", c.steamGridDb?.apiKey)
        assertNull(c.ra, "blank-only write must not create an ra block")
    }

    // Changing the IGDB credentials invalidates a token obtained with the old ones.
    @Test fun `new igdb credentials drop the cached token`() {
        config.writeCredentials(igdbClientId = "CID", igdbClientSecret = "SEC")
        config.saveToken("igdb", "TOKEN", 9_999_999_999L)
        assertEquals("TOKEN", config.load().igdb?.cachedToken)

        config.writeCredentials(igdbClientSecret = "NEWSEC")
        val c = config.load()
        assertEquals("NEWSEC", c.igdb?.clientSecret)
        assertEquals("", c.igdb?.cachedToken, "stale token must be cleared")
        assertEquals(0L, c.igdb?.cachedTokenExp)
    }

    @Test fun `corrupt file is treated as empty instead of throwing`() {
        paths.credentials.parentFile.mkdirs()
        paths.credentials.writeText("{ this is not json")
        assertNull(config.load().ra)

        // and a subsequent write still succeeds, starting fresh
        config.writeCredentials(sgdbKey = "SGKEY")
        assertEquals("SGKEY", config.load().steamGridDb?.apiKey)
    }

    // ── status / clearBlock ─────────────────────────────────────────────────
    //
    // These exist so the theme can stop keeping its own copy of the credentials:
    // it binds its settings UI to status() and logs out through clearBlock().

    @Test fun `status reports presence and the username, never the secrets`() {
        config.writeCredentials(raUser = "MrJud", raApiKey = "SECRET",
                                sgdbKey = "SGKEY")
        val st = config.status()

        assertTrue(st.getJSONObject("ra").getBoolean("configured"))
        assertEquals("MrJud", st.getJSONObject("ra").getString("user"))
        assertTrue(st.getJSONObject("steamGridDb").getBoolean("configured"))
        assertFalse(st.getJSONObject("igdb").getBoolean("configured"))

        assertFalse(st.toString().contains("SECRET"), "an api key must never appear in status")
        assertFalse(st.toString().contains("SGKEY"), "an api key must never appear in status")
    }

    // Half a credential cannot make a call, so it must not read as configured.
    @Test fun `a username without an api key is not configured`() {
        config.writeCredentials(raUser = "MrJud")
        val ra = config.status().getJSONObject("ra")
        assertFalse(ra.getBoolean("configured"))
        assertEquals("MrJud", ra.getString("user"), "the username is still reported")
    }

    @Test fun `clearing a block forgets it and leaves the others`() {
        config.writeCredentials(raUser = "MrJud", raApiKey = "KEY", sgdbKey = "SGKEY")

        assertTrue(config.clearBlock("ra"))
        assertNull(config.load().ra)
        assertEquals("SGKEY", config.load().steamGridDb?.apiKey, "unrelated blocks survive")
        assertFalse(config.status().getJSONObject("ra").getBoolean("configured"))
    }

    @Test fun `clearing an unknown block is refused rather than ignored`() {
        config.writeCredentials(sgdbKey = "SGKEY")
        assertFalse(config.clearBlock("nonsense"))
        assertEquals("SGKEY", config.load().steamGridDb?.apiKey)
    }

    // ── ScreenScraper ───────────────────────────────────────
    // Two pairs that mean different things, and a softname the API checks. Each of
    // those is a way to look configured and be refused, so each is asserted.

    @Test fun `the developer pair alone is configured, on the anonymous quota`() {
        config.writeCredentials(ssDevId = "DEV", ssDevPassword = "DEVPW")
        val ss = config.status().getJSONObject("screenScraper")
        assertTrue(ss.getBoolean("configured"), "the API answers with the developer pair")
        assertFalse(ss.getBoolean("hasUser"), "but the quota is still the anonymous one")
        assertEquals("", ss.getString("user"))
    }

    @Test fun `a member login is reported separately from the developer pair`() {
        config.writeCredentials(ssDevId = "DEV", ssDevPassword = "DEVPW",
                                ssUser = "MrJud", ssPassword = "USERPW")
        val ss = config.status().getJSONObject("screenScraper")
        assertTrue(ss.getBoolean("configured"))
        assertTrue(ss.getBoolean("hasUser"))
        assertEquals("MrJud", ss.getString("user"))
    }

    @Test fun `a member login without the developer pair is not configured`() {
        // The reverse of the case above, and the one that would otherwise read as
        // working: the API refuses every request that carries no devid.
        config.writeCredentials(ssUser = "MrJud", ssPassword = "USERPW")
        val ss = config.status().getJSONObject("screenScraper")
        assertFalse(ss.getBoolean("configured"))
        assertTrue(ss.getBoolean("hasUser"))
    }

    @Test fun `softname defaults to the registered name and can be overridden`() {
        config.writeCredentials(ssDevId = "DEV", ssDevPassword = "DEVPW")
        assertEquals(ScreenScraperCreds.DEFAULT_SOFTNAME, config.load().screenScraper?.softname,
                     "a file with no softname must still send the registered one")
        config.writeCredentials(ssSoftname = "SomethingElse")
        assertEquals("SomethingElse", config.load().screenScraper?.softname)
    }

    @Test fun `one ScreenScraper field at a time leaves the others alone`() {
        config.writeCredentials(ssDevId = "DEV", ssDevPassword = "DEVPW")
        config.writeCredentials(ssUser = "MrJud")
        val c = config.load().screenScraper
        assertEquals("DEV", c?.devId)
        assertEquals("DEVPW", c?.devPassword)
        assertEquals("MrJud", c?.ssid)
    }

    @Test fun `the ScreenScraper block can be forgotten`() {
        config.writeCredentials(ssDevId = "DEV", ssDevPassword = "DEVPW", sgdbKey = "SGKEY")
        assertTrue(config.clearBlock("screenScraper"), "it has to be a known block")
        assertNull(config.load().screenScraper)
        assertEquals("SGKEY", config.load().steamGridDb?.apiKey, "unrelated blocks survive")
    }

    @Test fun `written file carries a schema version`() {
        config.writeCredentials(sgdbKey = "SGKEY")
        val j = JSONObject(paths.credentials.readText())
        assertEquals(SchemaVersion.CURRENT, j.getInt("schemaVersion"))
        assertTrue(j.getLong("updatedAt") > 0)
    }

    // ── The developer pair a release bundle carries ─────────────────────────

    private fun defaultsFile(body: String): File =
        File(root, "app-defaults.json").also { it.writeText(body) }

    private fun withDefaults(body: String) = Config(paths, defaultsFile(body))

    private val SHIPPED = """
        { "screenScraper": { "devId": "SHIPPED", "devPassword": "SHIPPEDPW" } }
    """.trimIndent()

    @Test fun `a user with no developer pair gets the shipped one`() {
        val c = withDefaults(SHIPPED).load().screenScraper
        assertEquals("SHIPPED", c?.devId)
        assertEquals("SHIPPEDPW", c?.devPassword)
        assertEquals(ScreenScraperCreds.DEFAULT_SOFTNAME, c?.softname,
            "the registered softname is the point of shipping the pair")
    }

    // The whole reason for shipping it: the user signs in and nothing else.
    @Test fun `the member login is kept alongside the shipped pair`() {
        config.writeCredentials(ssUser = "MrJud", ssPassword = "PW")
        val c = withDefaults(SHIPPED).load().screenScraper
        assertEquals("SHIPPED", c?.devId)
        assertEquals("MrJud", c?.ssid)
        assertEquals("PW", c?.ssPassword)
    }

    @Test fun `a user's own developer pair wins`() {
        config.writeCredentials(ssDevId = "MINE", ssDevPassword = "MINEPW")
        val c = withDefaults(SHIPPED).load().screenScraper
        assertEquals("MINE", c?.devId)
        assertEquals("MINEPW", c?.devPassword)
    }

    // Uninstalling the bundle has to take the pair with it, so it must never be
    // copied into the user's file on the way past.
    @Test fun `the shipped pair is never written to disk`() {
        val cfg = withDefaults(SHIPPED)
        cfg.load()
        cfg.writeCredentials(ssUser = "MrJud", ssPassword = "PW")
        val onDisk = JSONObject(paths.credentials.readText()).getJSONObject("screenScraper")
        assertFalse(onDisk.has("devId"), "the shipped devId must not land in credentials.json")
        assertFalse(onDisk.has("devPassword"))
    }

    @Test fun `a developer build without the file behaves as before`() {
        assertNull(Config(paths, File(root, "absent.json")).load().screenScraper)
        assertNull(Config(paths).load().screenScraper)
    }

    // The flag the settings UI hides the developer fields on. `configured` cannot
    // carry it: it is true for a typed pair and a shipped one alike.
    @Test fun `the status says when the pair came with the build`() {
        val ss = withDefaults(SHIPPED).status().getJSONObject("screenScraper")
        assertTrue(ss.getBoolean("configured"))
        assertTrue(ss.getBoolean("devFromApp"))
        assertFalse(ss.getBoolean("hasUser"), "signing in is still the user's to do")
    }

    @Test fun `a typed pair is not reported as the build's`() {
        config.writeCredentials(ssDevId = "MINE", ssDevPassword = "MINEPW")
        val ss = withDefaults(SHIPPED).status().getJSONObject("screenScraper")
        assertTrue(ss.getBoolean("configured"))
        assertFalse(ss.getBoolean("devFromApp"))
    }

    @Test fun `no pair anywhere is neither configured nor from the build`() {
        val ss = config.status().getJSONObject("screenScraper")
        assertFalse(ss.getBoolean("configured"))
        assertFalse(ss.getBoolean("devFromApp"))
    }

    @Test fun `a half-written or unreadable defaults file is ignored`() {
        assertNull(withDefaults("""{ "screenScraper": { "devId": "X" } }""")
                       .load().screenScraper, "a pair needs both halves")
        assertNull(withDefaults("not json at all").load().screenScraper)
    }
}
