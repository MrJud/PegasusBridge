package com.pegasus.bridge.ra

import com.pegasus.bridge.core.BridgeLog
import com.pegasus.bridge.core.BridgePaths
import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.SchemaVersion
import com.pegasus.bridge.core.StderrLog
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android's credential verbs, as far as they are said off the device: the
 * fields, the SteamID rule, the refusal of a browser's intent, what a steam
 * write or clear drops of the cache, and the answer a write leaves where the
 * theme reads it. The router and RaService only hand this the intent's parts
 * and the Config's write.
 */
class CredentialVerbsTest {

    private lateinit var sandbox: File
    private lateinit var cacheDir: File
    /** Where RaService has a write answered: credentials/{jobId}.json under the data root. */
    private lateinit var answer: File
    private val logged = mutableListOf<String>()

    @BeforeTest fun setUp() {
        sandbox = Files.createTempDirectory("credential-verbs").toFile()
        cacheDir = File(sandbox, "cache/steam")
        answer = File(sandbox, "credentials/steamcreds_1760400000000_4242.json")
        BridgeLog.current = object : BridgeLog {
            override fun d(tag: String, msg: String) { logged += "$tag: $msg" }
            override fun i(tag: String, msg: String) { logged += "$tag: $msg" }
            override fun w(tag: String, msg: String, t: Throwable?) { logged += "$tag: $msg ${t?.message}" }
            override fun e(tag: String, msg: String, t: Throwable?) { logged += "$tag: $msg ${t?.message}" }
        }
    }

    @AfterTest fun tearDown() {
        BridgeLog.current = StderrLog
        sandbox.deleteRecursively()
    }

    private fun fieldsOf(vararg pairs: Pair<String, String?>): Map<String, String> {
        val query = mapOf(*pairs)
        return CredentialVerbs.fields { query[it] }
    }

    /** What SteamAccountOps keeps: a schema, and two accounts' libraries and unlock lists. */
    private fun keep() {
        for (path in listOf("schema/440.english.json",
                            "$STEAM_ID/library.json", "$STEAM_ID/progress/440.json",
                            "$OTHER_ID/library.json", "$OTHER_ID/progress/620.json")) {
            File(cacheDir, path).apply { parentFile.mkdirs(); writeText("{}") }
        }
    }

    private fun kept(): List<String> =
        cacheDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(cacheDir).path }.sorted().toList()

    private val everything = listOf("$STEAM_ID/library.json", "$STEAM_ID/progress/440.json",
                                    "$OTHER_ID/library.json", "$OTHER_ID/progress/620.json",
                                    "schema/440.english.json")

    // ── The fields ──────────────────────────────────────────────────────────

    @Test fun `set-credentials takes the ten fields it took and the steam two`() {
        assertEquals(listOf("user", "apiKey", "sgdbKey", "igdbClientId", "igdbClientSecret",
                            "ssDevId", "ssDevPassword", "ssUser", "ssPassword", "ssSoftname",
                            "steamApiKey", "steamId"), CredentialVerbs.FIELDS)
    }

    @Test fun `each field is optional, an empty one is none, and nothing else is taken`() {
        assertEquals(mapOf("apiKey" to "ra-key", "steamApiKey" to KEY),
                     fieldsOf("user" to "", "apiKey" to "ra-key", "steamApiKey" to KEY,
                              "jobId" to "j1", "verb" to "x", "romm" to "r"))
        assertEquals(emptyMap(), fieldsOf())
        assertEquals(emptyMap(), fieldsOf("user" to "", "steamApiKey" to "", "steamId" to ""))
        assertEquals(listOf("steam"), CredentialVerbs.blocks(fieldsOf("steamApiKey" to KEY)))
        assertEquals(listOf("ra", "steamGridDb", "igdb", "screenScraper", "steam"),
                     CredentialVerbs.blocks(CredentialVerbs.FIELDS.associateWith { "$it-value" }
                                                + ("steamId" to STEAM_ID)))
    }

    @Test fun `a SteamID that is not seventeen digits is not written, and the key beside it is`() {
        assertEquals(mapOf("steamId" to STEAM_ID), fieldsOf("steamId" to STEAM_ID))
        for (bad in listOf(STEAM_ID.dropLast(1), STEAM_ID + "1", "7656119800000000x",
                           " $STEAM_ID".dropLast(1), "+7656119800000000",
                           // Seventeen digits, of another script.
                           "٧٦٥٦١١٩٨٠٠٠٠٠٠٠٠٠")) {
            assertEquals(emptyMap(), fieldsOf("steamId" to bad), "'$bad' was taken")
            assertEquals(mapOf("steamApiKey" to KEY), fieldsOf("steamApiKey" to KEY, "steamId" to bad))
        }
    }

    // ── A browser's intent ──────────────────────────────────────────────────

    @Test fun `an intent a browser sent is dropped, with a line that names the verb alone`() {
        for (verb in listOf(CredentialVerbs.SET, CredentialVerbs.CLEAR)) {
            assertTrue(CredentialVerbs.admits(verb, null))
            assertTrue(CredentialVerbs.admits(verb, emptySet()))
            assertTrue(CredentialVerbs.admits(verb, setOf("android.intent.category.DEFAULT")))
            assertFalse(CredentialVerbs.admits(verb, setOf(CredentialVerbs.CATEGORY_BROWSABLE)))
            assertFalse(CredentialVerbs.admits(verb, setOf("android.intent.category.DEFAULT",
                                                           "android.intent.category.BROWSABLE")))
        }
        assertEquals(listOf("set-credentials: dropped, it came through a browser",
                            "set-credentials: dropped, it came through a browser",
                            "clear-credentials: dropped, it came through a browser",
                            "clear-credentials: dropped, it came through a browser"),
                     logged.map { it.substringAfter(": ").removeSuffix(" null") })
    }

    // ── What a steam write or clear drops ───────────────────────────────────

    @Test fun `a key written over another drops every account's library and unlock lists, not the schemas`() {
        keep()
        val written = mutableListOf<Map<String, String>>()
        assertTrue(CredentialVerbs.set(fieldsOf("steamApiKey" to KEY), cacheDir, answer) { written += it })

        assertEquals(listOf(mapOf("steamApiKey" to KEY)), written)
        assertEquals(listOf("schema/440.english.json"), kept())
        assertEquals(listOf("schema"), cacheDir.list()?.toList(), "an account's folder was left behind")
    }

    @Test fun `a SteamID written drops them too`() {
        keep()
        CredentialVerbs.set(fieldsOf("steamId" to OTHER_ID), cacheDir, answer) {}
        assertEquals(listOf("schema/440.english.json"), kept())
    }

    @Test fun `a write of other blocks, or of nothing, leaves what is kept as it was`() {
        keep()
        var writes = 0
        CredentialVerbs.set(fieldsOf("user" to "someone", "apiKey" to "ra-key", "sgdbKey" to "k",
                                     "ssDevId" to "d", "igdbClientId" to "i"), cacheDir, answer) { writes++ }
        // A SteamID that is not one is not written, so it drops nothing either.
        CredentialVerbs.set(fieldsOf("user" to "someone", "steamId" to "123"), cacheDir, answer) { writes++ }
        assertEquals(listOf("ra"), updated(answered()))
        // Nothing is answered as the daemon answers it, with an error.
        assertFalse(CredentialVerbs.set(fieldsOf(), cacheDir, answer) { writes++ })
        assertEquals(mapOf("schemaVersion" to SchemaVersion.CURRENT, "status" to "error",
                           "error" to CredentialVerbs.NOTHING_TO_WRITE), answered().toMap())

        assertEquals(2, writes, "a request with nothing in it was written")
        assertEquals(everything, kept())
    }

    @Test fun `clearing the steam block drops them, clearing another or nothing does not`() {
        keep()
        assertTrue(CredentialVerbs.clear("ra", cacheDir) { true })
        assertFalse(CredentialVerbs.clear("steam", cacheDir) { false })
        assertEquals(everything, kept())

        val cleared = mutableListOf<String>()
        assertTrue(CredentialVerbs.clear("steam", cacheDir) { cleared += it; true })
        assertEquals(listOf("steam"), cleared)
        assertEquals(listOf("schema/440.english.json"), kept())

        // No data root, or nothing kept: nothing to do, and nothing made.
        assertTrue(CredentialVerbs.clear("steam", null) { true })
        assertTrue(CredentialVerbs.clear("steam", File(sandbox, "nowhere")) { true })
        assertFalse(File(sandbox, "nowhere").exists())
    }

    @Test fun `the key is in no line logged, whatever happens to it`() {
        keep()
        CredentialVerbs.admits(CredentialVerbs.SET, setOf(CredentialVerbs.CATEGORY_BROWSABLE))
        CredentialVerbs.set(fieldsOf("steamApiKey" to KEY, "steamId" to STEAM_ID, "apiKey" to KEY), cacheDir, answer) {}
        CredentialVerbs.set(fieldsOf("steamApiKey" to KEY, "steamId" to KEY), cacheDir, answer) {}
        CredentialVerbs.clear("steam", cacheDir) { true }
        CredentialVerbs.clear(KEY, cacheDir) { false }

        assertEquals(5, logged.size, logged.toString())
        assertTrue(logged.none { KEY in it }, logged.toString())
        assertTrue("set-credentials: blocks updated: [ra, steam]" in logged.map { it.substringAfter(": ") })
    }

    // ── The answer ──────────────────────────────────────────────────────────

    private fun answered(): JSONObject = JSONObject(answer.readText())

    private fun updated(json: JSONObject): List<String> =
        json.getJSONArray("updated").let { a -> (0 until a.length()).map { a.getString(it) } }

    /** Every file under the data root that holds [text] in its bytes or its name, by its path there. */
    private fun holding(text: String): List<String> =
        sandbox.walkTopDown().filter { it.isFile && (text in it.readText() || text in it.path) }
            .map { it.relativeTo(sandbox).path }.sorted().toList()

    @Test fun `a write is answered as the daemon answers it, with the blocks it wrote and no value`() {
        keep()
        val config = Config(BridgePaths(sandbox))
        val fields = fieldsOf("user" to "someone", "apiKey" to KEY, "steamApiKey" to KEY, "steamId" to STEAM_ID)
        val took = CredentialVerbs.set(fields, cacheDir, answer) { f ->
            config.writeCredentials(raUser = f["user"], raApiKey = f["apiKey"],
                                    steamApiKey = f["steamApiKey"], steamId = f["steamId"])
        }

        assertTrue(took)
        val json = answered()
        // The daemon's /credentials answer, key for key.
        assertEquals(setOf("schemaVersion", "status", "updated"), json.keySet())
        assertEquals(SchemaVersion.CURRENT, json.getInt("schemaVersion"))
        assertEquals("ok", json.getString("status"))
        assertEquals(listOf("ra", "steam"), updated(json))
        // The key landed, and it is in that file and in no other, nor in a name.
        assertEquals(KEY, config.load().steam?.apiKey)
        assertEquals(listOf("config/credentials.json"), holding(KEY))
        assertTrue(logged.none { KEY in it }, logged.toString())
        assertEquals(listOf("schema/440.english.json"), kept())
    }

    @Test fun `a write that throws is answered with an error that holds no value, and does not take`() {
        keep()
        var tried = 0
        val took = CredentialVerbs.set(fieldsOf("steamApiKey" to KEY, "steamId" to STEAM_ID), cacheDir, answer) {
            tried++
            // What org.json throws on a file it cannot read holds the file's text.
            throw JSONException("A JSONObject text must begin with '{' at 1 [character 2 line 1]: $KEY")
        }

        assertFalse(took, "a write that threw was taken for one that did not")
        assertEquals(1, tried)
        assertEquals(mapOf("schemaVersion" to SchemaVersion.CURRENT, "status" to "error",
                           "error" to CredentialVerbs.WRITE_FAILED), answered().toMap())
        assertEquals(emptyList(), holding(KEY))
        assertTrue(logged.none { KEY in it }, logged.toString())
        assertEquals(listOf("set-credentials: the write failed (JSONException)"),
                     logged.map { it.substringAfter(": ").removeSuffix(" null") })
        // Nothing was written, so what is kept still answers for the key that is there.
        assertEquals(everything, kept())
    }

    private companion object {
        const val KEY = "SENTINEL-STEAM-KEY"
        const val STEAM_ID = "76561198000000001"
        const val OTHER_ID = "76561198000000002"
    }
}
