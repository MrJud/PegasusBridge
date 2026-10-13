package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.SafeUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three optional integrations, tested against the shapes their services
 * actually return.
 *
 * Every fixture below is trimmed from a real response — RomM's from the public
 * demo at `demo.romm.app`, Steam's and Spotify's from their documented shapes
 * and measured error bodies. That matters most for the failure cases, which are
 * where each of these APIs is least like the others: RomM answers JSON, Steam
 * answers **HTML** on a bad request, and Spotify answers with a bare status and
 * no body at all.
 */
class IntegrationClientsTest {

    // ── RomM ────────────────────────────────────────────────────────────────

    /** Trimmed from `GET /api/platforms` on the demo. */
    private val ROMM_PLATFORMS = """
    [{"id":1,"slug":"atari2600","name":"Atari 2600","rom_count":31,
      "igdb_id":59,"ss_id":26,"ra_id":25,"moby_id":28},
     {"id":12,"slug":"snes","name":"Super Nintendo Entertainment System","rom_count":40,
      "igdb_id":19,"ss_id":4,"ra_id":3}]
    """.trimIndent()

    /**
     * Trimmed from `GET /api/roms` on the demo — including the field that leaks.
     *
     * `ss_metadata.box2d_url` really does carry the RomM instance's own
     * ScreenScraper developer password. It is reproduced here with a sentinel so
     * the redaction has something to fail against.
     */
    private val ROMM_ROMS = """
    {"items":[{
      "id":159,"name":"16 BIT XMAS 2011: Christmas Craze",
      "fs_name":"Christmas Craze.smc","fs_name_no_ext":"Christmas Craze",
      "platform_slug":"snes","platform_id":12,
      "summary":"A SNES homebrew game.",
      "md5_hash":"0829ebfde80e7a9673fe9966a8d47b0f",
      "crc_hash":"5f7b6627",
      "sha1_hash":"7752caa676d15d4d0c42f25548c3e86128a416db",
      "ra_hash":"0829ebfde80e7a9673fe9966a8d47b0f",
      "ra_id":7791,"igdb_id":134472,"ss_id":422760,
      "metadatum":{"genres":["Arcade"],"companies":["RetroUSB","Shiru"],
                   "first_release_date":1321142400},
      "path_cover_large":"/assets/romm/resources/roms/12/159/cover/big.png?ts=2026-07-05 12:22:06",
      "path_cover_small":"/assets/romm/resources/roms/12/159/cover/small.webp",
      "path_video":"roms/12/159/video_normalized/video-normalized.mp4",
      "merged_screenshots":["/assets/romm/resources/roms/12/159/screenshots/0.jpg"],
      "ss_metadata":{"box2d_url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=someone&devpassword=SENTINEL-THEIR-PASSWORD&softname=x&jeuid=422760"}
    }],"total":40,"limit":1,"offset":0}
    """.trimIndent()

    @Test fun `romm platforms carry the ids every other source here is keyed by`() {
        val list = RommClient.parsePlatforms(ROMM_PLATFORMS).getOrThrow()
        assertEquals(2, list.size)
        val snes = list.single { it.slug == "snes" }
        assertEquals(12, snes.id)
        assertEquals(40, snes.romCount)
        // This is what lets a RomM answer cross-check a ScreenScraper one instead
        // of being a second opinion nobody can reconcile.
        assertEquals(4, snes.screenScraperId)
        assertEquals(3, snes.retroAchievementsId)
        assertEquals(19, snes.igdbId)
    }

    // The whole reason this source is worth having: the join key is a digest.
    @Test fun `a romm rom carries the same rcheevos hash the bridge computes`() {
        val page = RommClient.parseRoms(ROMM_ROMS).getOrThrow()
        val rom = page.items.single()
        assertEquals("0829ebfde80e7a9673fe9966a8d47b0f", rom.raHash)
        assertEquals("0829ebfde80e7a9673fe9966a8d47b0f", rom.md5)
        assertEquals("5f7b6627", rom.crc32)
        assertEquals(7791, rom.raId)
    }

    // The endpoint answers {items,total,limit,offset}. A caller that assumed a
    // bare array would read a library of forty as nothing at all.
    @Test fun `the rom list is a page and reports whether there is more`() {
        val page = RommClient.parseRoms(ROMM_ROMS).getOrThrow()
        assertEquals(40, page.total)
        assertEquals(1, page.items.size)
        assertTrue(page.hasMore)
    }

    @Test fun `romm metadata is parsed out of the nested blocks`() {
        val rom = RommClient.parseRoms(ROMM_ROMS).getOrThrow().items.single()
        assertEquals("16 BIT XMAS 2011: Christmas Craze", rom.name)
        assertEquals(listOf("Arcade"), rom.genres)
        assertEquals(listOf("RetroUSB", "Shiru"), rom.companies)
        assertEquals(1321142400L, rom.releaseDate)
    }

    // Measured on the live demo, not supposed: RomM hands out a third party's
    // ScreenScraper password to any API consumer.
    @Test fun `a third party's password never survives redaction`() {
        val raw = JSONObject(ROMM_ROMS).getJSONArray("items").getJSONObject(0)
        assertTrue(raw.toString().contains("SENTINEL-THEIR-PASSWORD"),
                   "the fixture should contain the leak it is testing for")

        val safe = RommClient.redactedMetadata(raw)
        assertFalse(SafeUrl.leaks(safe.toString(), "SENTINEL-THEIR-PASSWORD"),
                    "a credential belonging to the RomM instance reached the output")
        // What is left has to still be useful: the endpoint and the game id survive.
        assertTrue(safe.getJSONObject("ss_metadata").getString("box2d_url")
                       .contains("jeuid=422760"))
    }

    @Test fun `a relative media path becomes a url on that server`() {
        val c = RommClient.Credentials("https://romm.example.org/")
        assertEquals("https://romm.example.org/assets/x/cover.png",
                     RommClient.mediaUrl(c, "/assets/x/cover.png"))
        // RomM's cover fields carry a `?ts=` cache-buster containing a space,
        // which is not a valid URL until it is encoded.
        assertTrue(RommClient.mediaUrl(c, "/a/big.png?ts=2026-07-05 12:22:06")
                       .endsWith("big.png?ts=2026-07-05%2012:22:06"))
        assertEquals("", RommClient.mediaUrl(c, ""))
        assertEquals("https://cdn.example/x.png",
                     RommClient.mediaUrl(c, "https://cdn.example/x.png"))
    }

    @Test fun `romm malformed answers are refusals rather than crashes`() {
        val e = RommClient.parseRoms("not json at all").exceptionOrNull()
        assertTrue(e is RommClient.RommException)
        assertEquals(RommClient.Refusal.MALFORMED, e.refusal)
        assertFalse(e.refusal.isAnswer, "an unreadable answer is not a verdict")
    }

    // ── Steam ───────────────────────────────────────────────────────────────

    private val STEAM_OWNED = """
    {"response":{"game_count":2,"games":[
      {"appid":440,"name":"Team Fortress 2","playtime_forever":1200,
       "playtime_2weeks":30,"img_icon_url":"e3f595a92552da3d664ad00277fad2107345f743",
       "rtime_last_played":1750000000},
      {"appid":570,"name":"Dota 2","playtime_forever":0,"img_icon_url":"abc"}
    ]}}
    """.trimIndent()

    @Test fun `owned games are parsed with playtime and an icon url`() {
        val games = SteamAccountClient.parseOwnedGames(JSONObject(STEAM_OWNED)
            .getJSONObject("response").getJSONArray("games"))
        assertEquals(2, games.size)
        val tf2 = games.single { it.appId == 440 }
        assertEquals(1200, tf2.playtimeMinutes)
        assertEquals(30, tf2.playtimeLast2WeeksMinutes)
        assertTrue(tf2.iconUrl.contains("/apps/440/"), tf2.iconUrl)
        // No icon hash is not a broken URL — it is no URL.
        assertEquals("", SteamAccountClient.parseOwnedGames(
            org.json.JSONArray("""[{"appid":1,"name":"x"}]""")).single().iconUrl)
    }

    @Test fun `achievements record which are unlocked and when`() {
        val list = SteamAccountClient.parseAchievements(org.json.JSONArray("""
            [{"apiname":"TF_SCOUT_LONG_DISTANCE_RUNNER","achieved":1,"unlocktime":1600000000},
             {"apiname":"TF_SCOUT_FAST_KILLS","achieved":0,"unlocktime":0}]
        """.trimIndent()))
        assertEquals(2, list.size)
        assertTrue(list[0].unlocked)
        assertEquals(1600000000L, list[0].unlockedAt)
        assertFalse(list[1].unlocked)
    }

    // A private profile is a state to show, not a failure to retry and not an
    // empty result to cache — reporting "no achievements" would tell a user their
    // game has none when what it has is a privacy setting. It was counted as an
    // answer here once, and an answer is what gets kept: the profile would have
    // gone on being private on disk after its owner had made it public.
    @Test fun `a private profile is its own refusal, and it is not an answer to keep`() {
        assertFalse(SteamAccountClient.Refusal.PRIVATE.isAnswer)
        assertFalse(SteamAccountClient.Refusal.KEY_REFUSED.isAnswer)
        assertFalse(SteamAccountClient.Refusal.RATE_LIMITED.isAnswer)
        assertFalse(SteamAccountClient.Refusal.TRANSPORT.isAnswer)
        assertFalse(SteamAccountClient.Refusal.SERVER.isAnswer)
        assertFalse(SteamAccountClient.Refusal.MALFORMED.isAnswer)
        // A game without stats is Steam answering, and stays so.
        assertTrue(SteamAccountClient.Refusal.NO_STATS.isAnswer)
    }

    private val steamCreds = SteamAccountClient.Credentials(STEAM_KEY, "76561198000000000")

    /** Runs [block] with the client pointed at a server that answers as queued. */
    private fun <T> withSteam(block: (MockWebServer) -> T): T {
        val server = MockWebServer()
        val base = SteamAccountClient.BASE
        server.start()
        SteamAccountClient.BASE = server.url("/").toString().trimEnd('/')
        try {
            return block(server)
        } finally {
            SteamAccountClient.BASE = base
            server.shutdown()
        }
    }

    private fun steamAnswer(status: Int, contentType: String, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", contentType)
        .setBody(body)

    /** One answer of Steam's and the refusal it is, or null for an answer to read. */
    private class SteamRow(
        val name: String,
        val status: Int,
        val contentType: String,
        val body: String,
        val refusal: SteamAccountClient.Refusal?
    )

    // What Steam answers with and what it is. The rows marked "measured" are as
    // Steam sent them on 2026-10-13, status and body: to a wrong key, to no key,
    // to an account whose game details were private, and about a tool, which
    // has no stats. The last two are given at two other statuses as well, since
    // what names them is the body.
    private val PLAYER_ROWS = listOf(
        SteamRow("measured: a wrong key", 403, "text/html", STEAM_FORBIDDEN, SteamAccountClient.Refusal.KEY_REFUSED),
        SteamRow("measured: a wrong key, as the library call refuses it", 401, "text/html", STEAM_UNAUTHORIZED,
                 SteamAccountClient.Refusal.KEY_REFUSED),
        SteamRow("measured: no key", 400, "text/html", STEAM_NO_KEY, SteamAccountClient.Refusal.KEY_REFUSED),
        SteamRow("measured: a profile that is not public", 403, "application/json", STEAM_NOT_PUBLIC,
                 SteamAccountClient.Refusal.PRIVATE),
        SteamRow("a profile that is not public, at 200", 200, "application/json", STEAM_NOT_PUBLIC,
                 SteamAccountClient.Refusal.PRIVATE),
        SteamRow("a profile that is not public, at 401", 401, "application/json", STEAM_NOT_PUBLIC,
                 SteamAccountClient.Refusal.PRIVATE),
        SteamRow("measured: a tool, which has no stats", 400, "application/json", STEAM_NO_STATS,
                 SteamAccountClient.Refusal.NO_STATS),
        SteamRow("a game with no stats, at 200", 200, "application/json", STEAM_NO_STATS,
                 SteamAccountClient.Refusal.NO_STATS),
        SteamRow("a game with no stats, at 403", 403, "application/json", STEAM_NO_STATS,
                 SteamAccountClient.Refusal.NO_STATS),
        SteamRow("too many requests", 429, "text/html", "<html><body>Too Many Requests</body></html>",
                 SteamAccountClient.Refusal.RATE_LIMITED),
        SteamRow("a server error", 500, "text/html", "<html><body>Internal Server Error</body></html>",
                 SteamAccountClient.Refusal.SERVER),
        SteamRow("a server away", 503, "application/json", "{}", SteamAccountClient.Refusal.SERVER),
        SteamRow("a page where JSON was due", 200, "text/html", "<html><body>Maintenance</body></html>",
                 SteamAccountClient.Refusal.MALFORMED),
        SteamRow("nothing at all", 200, "application/json", "", SteamAccountClient.Refusal.MALFORMED),
        SteamRow("a refusal in words not known here", 403, "application/json",
                 """{"playerstats":{"error":"Something Steam began to say this year","success":false}}""",
                 SteamAccountClient.Refusal.MALFORMED),
        SteamRow("a refusal with no words", 200, "application/json",
                 """{"playerstats":{"success":false}}""", SteamAccountClient.Refusal.MALFORMED),
        SteamRow("JSON at a status that is no answer", 403, "application/json", "{}",
                 SteamAccountClient.Refusal.SERVER),
        SteamRow("JSON with no playerstats", 200, "application/json", "{}",
                 SteamAccountClient.Refusal.MALFORMED),
        SteamRow("an answer", 200, "application/json", STEAM_PLAYER, null)
    )

    // The status was judged before the body was read, and both a profile that
    // is not public and a game with no stats came out as a refused key.
    @Test fun `every answer to the player call is the refusal its body and status make it`() {
        withSteam { server ->
            for (row in PLAYER_ROWS) {
                server.enqueue(steamAnswer(row.status, row.contentType, row.body))
                val r = SteamAccountClient.playerAchievements(steamCreds, 440)
                val e = r.exceptionOrNull()
                if (row.refusal == null) {
                    assertNull(e, "${row.name}: refused as ${(e as? SteamAccountClient.SteamException)?.refusal}")
                    assertEquals(2, r.getOrThrow().achievements.size, row.name)
                    continue
                }
                assertTrue(e is SteamAccountClient.SteamException, "${row.name}: got $r")
                assertEquals(row.refusal, e.refusal, row.name)
                assertFalse(SafeUrl.leaks(e.message.orEmpty(), STEAM_KEY), "${row.name}: ${e.message}")
            }
        }
    }

    // Team Fortress 2, never started, on a public profile: HTTP 200 and all 520
    // of its achievements, each with `achieved` 0. It is an answer and not "no
    // stats", and nothing in it is unlocked. Asked with a language, each entry
    // has a `name` and a `description` beside its `apiname`.
    @Test fun `measured - a game never started is an answer that lists everything and unlocks nothing`() {
        val entries = (1..520).joinToString(",") {
            """{"apiname":"TF_ACH_$it","achieved":0,"unlocktime":0,"name":"Shown name $it","description":"How it is earned"}"""
        }
        withSteam { server ->
            server.enqueue(steamAnswer(200, "application/json",
                """{"playerstats":{"steamID":"76561198000000000","gameName":"Team Fortress 2","achievements":[$entries],"success":true}}"""))
            val stats = SteamAccountClient.playerAchievements(steamCreds, 440, "italian").getOrThrow()
            assertEquals("Team Fortress 2", stats.gameName)
            assertEquals(520, stats.achievements.size)
            assertTrue(stats.achievements.none { it.unlocked || it.unlockedAt != 0L })
            // The id is the apiname, with the shown name beside it or without.
            assertEquals("TF_ACH_1", stats.achievements.first().apiName)
        }
    }

    // What Steam sent for the schema of a tool: HTTP 200 and a game with nothing in it.
    @Test fun `measured - the schema of a tool is an empty game, read as a schema that lists nothing`() {
        withSteam { server ->
            server.enqueue(steamAnswer(200, "application/json", """{"game":{}}"""))
            val schema = SteamAccountClient.schemaFor(STEAM_KEY, 1493710).getOrThrow()
            assertEquals("", schema.gameName)
            assertEquals(emptyList(), schema.achievements)
        }
    }

    // A schema entry as Steam lists it. A hidden one comes with no description:
    // 24 of Hollow Knight's 63 did.
    @Test fun `measured - a schema entry has both pictures, and a hidden one no description`() {
        withSteam { server ->
            server.enqueue(steamAnswer(200, "application/json", """{"game":{"gameName":"Hollow Knight","gameVersion":"36",
                "availableGameStats":{"achievements":[
                  {"name":"CHARMED","defaultvalue":0,"displayName":"Incantato","hidden":0,"description":"Ottieni un amuleto",
                   "icon":"https://cdn.example/a.jpg","icongray":"https://cdn.example/a_gray.jpg"},
                  {"name":"ENDING","defaultvalue":0,"displayName":"Il finale","hidden":1,
                   "icon":"https://cdn.example/b.jpg","icongray":"https://cdn.example/b_gray.jpg"}]}}}"""))
            val schema = SteamAccountClient.schemaFor(STEAM_KEY, 367520, "italian").getOrThrow()
            assertEquals("Hollow Knight", schema.gameName)
            val (shown, hidden) = schema.achievements
            assertEquals(listOf("CHARMED", "Incantato", "Ottieni un amuleto"),
                         listOf(shown.apiName, shown.displayName, shown.description))
            assertEquals("https://cdn.example/a.jpg" to "https://cdn.example/a_gray.jpg", shown.iconUrl to shown.iconGrayUrl)
            assertFalse(shown.hidden)
            assertTrue(hidden.hidden)
            assertEquals("", hidden.description)
            assertEquals("Il finale", hidden.displayName)
        }
    }

    @Test fun `a library with neither games nor a count is private, and one with a count of none is empty`() {
        withSteam { server ->
            server.enqueue(steamAnswer(200, "application/json", """{"response":{}}"""))
            val private = SteamAccountClient.ownedGames(steamCreds).exceptionOrNull()
            assertTrue(private is SteamAccountClient.SteamException, "got $private")
            assertEquals(SteamAccountClient.Refusal.PRIVATE, private.refusal)

            server.enqueue(steamAnswer(200, "application/json", """{"response":{"game_count":0}}"""))
            assertEquals(emptyList(), SteamAccountClient.ownedGames(steamCreds).getOrThrow())

            server.enqueue(steamAnswer(200, "application/json", STEAM_OWNED))
            assertEquals(2, SteamAccountClient.ownedGames(steamCreds).getOrThrow().size)

            server.enqueue(steamAnswer(401, "text/html", STEAM_UNAUTHORIZED))
            val refused = SteamAccountClient.ownedGames(steamCreds).exceptionOrNull()
            assertTrue(refused is SteamAccountClient.SteamException, "got $refused")
            assertEquals(SteamAccountClient.Refusal.KEY_REFUSED, refused.refusal)
        }
    }

    // The message of a failed connection is the network layer's, and what it
    // was asked for is a URL with the key in it.
    @Test fun `a steam that cannot be reached is a transport refusal that names no url`() {
        val base = SteamAccountClient.BASE
        SteamAccountClient.BASE = "http://127.0.0.1:1"
        try {
            val e = SteamAccountClient.playerAchievements(steamCreds, 440).exceptionOrNull()
            assertTrue(e is SteamAccountClient.SteamException, "got $e")
            assertEquals(SteamAccountClient.Refusal.TRANSPORT, e.refusal)
            assertFalse(SafeUrl.leaks(e.message.orEmpty(), STEAM_KEY), "leaked: ${e.message}")
            assertFalse(e.message.orEmpty().contains("127.0.0.1"), "the message quotes the address: ${e.message}")
        } finally {
            SteamAccountClient.BASE = base
        }
    }

    @Test fun `both calls about a game are made in the language asked for`() {
        withSteam { server ->
            server.enqueue(steamAnswer(200, "application/json", STEAM_PLAYER))
            server.enqueue(steamAnswer(200, "application/json", """{"game":{}}"""))
            SteamAccountClient.playerAchievements(steamCreds, 440, "italian").getOrThrow()
            SteamAccountClient.schemaFor(STEAM_KEY, 440, "italian").getOrThrow()

            assertEquals("italian", server.takeRequest().requestUrl?.queryParameter("l"))
            assertEquals("italian", server.takeRequest().requestUrl?.queryParameter("l"))
        }
    }

    // Unencoded, the name below ends the parameter it is in and begins two more.
    @Test fun `a name, an id and a key reach steam as one parameter each`() {
        withSteam { server ->
            val name = "some one&key=other&steamid=1"
            val key = "SENTINEL STEAM&KEY"
            server.enqueue(steamAnswer(200, "application/json", """{"response":{"success":1,"steamid":"76561198000000000"}}"""))
            assertEquals("76561198000000000", SteamAccountClient.resolveVanity(key, name).getOrThrow())
            val resolve = server.takeRequest().requestUrl!!
            assertEquals(listOf(name), resolve.queryParameterValues("vanityurl"))
            assertEquals(listOf(key), resolve.queryParameterValues("key"))
            assertEquals(setOf("key", "vanityurl"), resolve.queryParameterNames)

            server.enqueue(steamAnswer(200, "application/json", STEAM_OWNED))
            SteamAccountClient.ownedGames(SteamAccountClient.Credentials(key, "7656&key=x")).getOrThrow()
            val owned = server.takeRequest().requestUrl!!
            assertEquals(listOf(key), owned.queryParameterValues("key"))
            assertEquals(listOf("7656&key=x"), owned.queryParameterValues("steamid"))
        }
    }

    @Test fun `the desktop launch is a steam uri and never a purchase`() {
        assertEquals("steam://rungameid/440", SteamAccountClient.desktopLaunchUri(440))
        assertEquals("steam://store/440", SteamAccountClient.storeUri(440))
    }

    @Test fun `a steam url is labelled without its key`() {
        val label = SteamAccountClient.label(
            "https://api.steampowered.com/ISteamUserStats/GetPlayerAchievements/v1/" +
            "?appid=440&key=SENTINEL-STEAM-KEY&steamid=76561198000000000")
        assertFalse(SafeUrl.leaks(label, "SENTINEL-STEAM-KEY"), "leaked: $label")
        assertTrue(label.contains("appid=440"))
    }

    // ── GameNative ──────────────────────────────────────────────────────────

    @Test fun `a valid launch produces the extras GameNative reads`() {
        val r = GameNativeLaunch.requestFor(440, "STEAM")
        assertTrue(r is GameNativeLaunch.Result.Ok, "got $r")
        assertEquals(440, r.request.extras[GameNativeLaunch.EXTRA_APP_ID])
        assertEquals("STEAM", r.request.extras[GameNativeLaunch.EXTRA_GAME_SOURCE])
    }

    // `getIntExtra` returns -1 for a String, and GameNative then refuses a launch
    // for an "invalid app_id" that was perfectly correct.
    @Test fun `the app id extra is an Int and not a String`() {
        val r = GameNativeLaunch.requestFor(440) as GameNativeLaunch.Result.Ok
        assertTrue(r.request.extras[GameNativeLaunch.EXTRA_APP_ID] is Int)
    }

    @Test fun `an unknown source is refused here rather than silently becoming steam`() {
        val r = GameNativeLaunch.requestFor(440, "ITCH")
        assertTrue(r is GameNativeLaunch.Result.Rejected, "got $r")
        assertTrue(r.reason.contains("STEAM"), r.reason)
    }

    @Test fun `a non-positive app id is refused`() {
        assertTrue(GameNativeLaunch.requestFor(0) is GameNativeLaunch.Result.Rejected)
        assertTrue(GameNativeLaunch.requestFor(-1) is GameNativeLaunch.Result.Rejected)
    }

    @Test fun `an oversized container config is refused at GameNative's own limit`() {
        val big = "x".repeat(GameNativeLaunch.MAX_CONFIG_BYTES + 1)
        val r = GameNativeLaunch.requestFor(440, "STEAM", big)
        assertTrue(r is GameNativeLaunch.Result.Rejected, "got $r")
        assertTrue(r.reason.contains("50000"), r.reason)
    }

    @Test fun `the equivalent am start line names the exported activity`() {
        val r = GameNativeLaunch.requestFor(570, "GOG") as GameNativeLaunch.Result.Ok
        val cmd = GameNativeLaunch.asAmStartCommand(r.request)
        assertTrue(cmd.contains("app.gamenative/app.gamenative.MainActivity"), cmd)
        assertTrue(cmd.contains("-a app.gamenative.LAUNCH_GAME"), cmd)
        assertTrue(cmd.contains("--ei app_id 570"), cmd)
        assertTrue(cmd.contains("--es game_source GOG"), cmd)
    }

    // ── Spotify ─────────────────────────────────────────────────────────────

    @Test fun `pkce produces a verifier inside the spec's length window`() {
        val p = SpotifyRemote.newPkce()
        assertTrue(p.verifier.length in 43..128, "length ${p.verifier.length}")
        assertTrue(p.challenge.isNotEmpty())
        assertTrue(p.verifier.all { it.isLetterOrDigit() || it in "-._~" },
                   "the verifier must be base64url with no padding: ${p.verifier}")
        assertFalse(p.challenge.contains("="), "the challenge must be unpadded")
        // Two calls must not agree, or the whole point of the exchange is gone.
        assertTrue(SpotifyRemote.newPkce().verifier != p.verifier)
    }

    @Test fun `the authorize url carries the challenge and asks only for what is used`() {
        val p = SpotifyRemote.newPkce()
        val url = SpotifyRemote.authorizeUrl(
            "client-123", "http://127.0.0.1:38700/spotify/callback", p, "state-abc")

        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=${p.challenge}"))
        assertTrue(url.contains("state=state-abc"))
        // Loopback IP literal: Spotify has refused `localhost` since April 2025.
        assertTrue(url.contains("127.0.0.1"), url)
        assertFalse(url.contains("localhost"), url)
        // Nothing about the library, the playlists or the user's identity.
        assertFalse(url.contains("playlist"), url)
        assertFalse(url.contains("user-read-private"), url)
    }

    @Test fun `a token response is read, and an error one is a refusal`() {
        val t = SpotifyRemote.parseTokens(
            """{"access_token":"AAA","refresh_token":"RRR","expires_in":3600}""", "")
        assertEquals("AAA", t.accessToken)
        assertEquals("RRR", t.refreshToken)
        assertTrue(t.isValid())

        val e = runCatching {
            SpotifyRemote.parseTokens("""{"error":"invalid_grant"}""", "")
        }.exceptionOrNull()
        assertTrue(e is SpotifyRemote.SpotifyException)
        assertEquals(SpotifyRemote.Refusal.AUTH, e.refusal)
    }

    // Spotify does not always issue a new refresh token, and dropping the old one
    // would log the user out at the next expiry for no reason.
    @Test fun `a refresh with no new refresh token keeps the old one`() {
        val t = SpotifyRemote.parseTokens(
            """{"access_token":"BBB","expires_in":3600}""", "ORIGINAL-REFRESH")
        assertEquals("ORIGINAL-REFRESH", t.refreshToken)
    }

    @Test fun `an expired token is not treated as valid`() {
        val past = SpotifyRemote.Tokens("A", "R", System.currentTimeMillis() / 1000L - 10)
        assertFalse(past.isValid())
        // A token with thirty seconds left is refused too: a call made with it
        // would very likely arrive after it had died.
        val nearly = SpotifyRemote.Tokens("A", "R", System.currentTimeMillis() / 1000L + 30)
        assertFalse(nearly.isValid())
    }

    @Test fun `the transport commands use the methods spotify documents`() {
        assertEquals("PUT", SpotifyRemote.Command.PLAY.method)
        assertEquals("PUT", SpotifyRemote.Command.PAUSE.method)
        assertEquals("POST", SpotifyRemote.Command.NEXT.method)
        assertEquals("/me/player/play", SpotifyRemote.Command.PLAY.path)
    }

    @Test fun `premium and no-device are separate refusals`() {
        // Reporting either as a generic failure sends the reader to a network
        // problem that is not there — one is a subscription, the other is a
        // client that is not running.
        assertTrue(SpotifyRemote.Refusal.NEEDS_PREMIUM != SpotifyRemote.Refusal.NO_ACTIVE_DEVICE)
        assertNotNull(SpotifyRemote.Refusal.valueOf("NEEDS_PREMIUM"))
    }

    private companion object {
        const val STEAM_KEY = "SENTINEL-STEAM-KEY"

        // As Steam sent them on 2026-10-13.
        const val STEAM_FORBIDDEN =
            "<html><head><title>Forbidden</title></head><body><h1>Forbidden</h1>Access is denied. " +
            "Retrying will not help. Please verify your <pre>key=</pre> parameter.</body></html>"
        const val STEAM_UNAUTHORIZED =
            "<html><head><title>Unauthorized</title></head><body><h1>Unauthorized</h1>Access is denied. " +
            "Retrying will not help. Please verify your <pre>key=</pre> parameter.</body></html>"
        const val STEAM_NO_KEY =
            "<html><head><title>Bad Request</title></head><body><h1>Bad Request</h1>" +
            "Required parameter 'key' is missing</body></html>"

        // As Steam sent them the same day: the first with HTTP 403 to an account
        // whose game details were private, the second with HTTP 400 about a tool,
        // whether that account's details were private or public.
        const val STEAM_NOT_PUBLIC = """{"playerstats":{"error":"Profile is not public","success":false}}"""
        const val STEAM_NO_STATS = """{"playerstats":{"error":"Requested app has no stats","success":false}}"""

        const val STEAM_PLAYER = """{"playerstats":{"steamID":"76561198000000000","gameName":"Team Fortress 2",
            "achievements":[{"apiname":"TF_SCOUT_LONG_DISTANCE_RUNNER","achieved":1,"unlocktime":1600000000},
                            {"apiname":"TF_SCOUT_FAST_KILLS","achieved":0,"unlocktime":0}],"success":true}}"""
    }
}
