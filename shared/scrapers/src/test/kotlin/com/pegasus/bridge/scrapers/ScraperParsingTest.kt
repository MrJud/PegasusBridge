package com.pegasus.bridge.scrapers

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the response parsers against payloads shaped like the real ones
 * (captured from the live APIs on 2026-08-04), without touching the network.
 *
 * These parsers are the contract with four third-party services; a silent shape
 * change is exactly the sort of thing that made the scraper look broken before.
 */
class ScraperParsingTest {

    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')

    // ── SteamGridDB ─────────────────────────────────────────────────────────

    @Test fun `sgdb search parses the data array`() {
        server.enqueue(MockResponse().setBody("""
            {"success":true,"data":[
              {"id":38372,"name":"Castlevania III: Dracula's Curse","types":["eshop"],"verified":true},
              {"id":5440565,"name":"Castlevania Chronicles III","types":[],"verified":false}
            ]}
        """.trimIndent()))
        val old = SteamGridDbClient.BASE
        try {
            SteamGridDbClient.BASE = url()
            val games = SteamGridDbClient.search("Castlevania III", "key").getOrThrow()
            assertEquals(2, games.size)
            assertEquals(38372, games[0].id)
            assertEquals("Castlevania III: Dracula's Curse", games[0].name)
            assertEquals(listOf("eshop"), games[0].types)
            assertTrue(games[0].verified)
        } finally { SteamGridDbClient.BASE = old }
    }

    @Test fun `sgdb reports no results when success is false`() {
        server.enqueue(MockResponse().setBody("""{"success":false,"errors":["nope"]}"""))
        val old = SteamGridDbClient.BASE
        try {
            SteamGridDbClient.BASE = url()
            assertEquals(0, SteamGridDbClient.search("x", "key").getOrThrow().size)
        } finally { SteamGridDbClient.BASE = old }
    }

    @Test fun `sgdb sends the api key as a bearer token`() {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":[]}"""))
        val old = SteamGridDbClient.BASE
        try {
            SteamGridDbClient.BASE = url()
            SteamGridDbClient.search("x", "SECRETKEY").getOrThrow()
            assertEquals("Bearer SECRETKEY", server.takeRequest().getHeader("Authorization"))
        } finally { SteamGridDbClient.BASE = old }
    }

    // ── Steam Store ─────────────────────────────────────────────────────────

    @Test fun `steam assets prefer hls then dash then legacy mp4`() {
        server.enqueue(MockResponse().setBody("""
            {"440":{"success":true,"data":{
              "name":"Team Fortress 2",
              "header_image":"https://cdn/header.jpg",
              "background_raw":"https://cdn/bg.jpg",
              "screenshots":[{"id":1,"path_thumbnail":"https://cdn/t1.jpg","path_full":"https://cdn/f1.jpg"}],
              "movies":[
                {"id":10,"name":"Trailer","thumbnail":"https://cdn/th.jpg",
                 "hls_h264":"http://cdn/v.m3u8","dash_h264":"http://cdn/v.mpd",
                 "mp4":{"480":"http://cdn/480.mp4","max":"http://cdn/max.mp4"}}
              ]}}}
        """.trimIndent()))
        val old = SteamStoreClient.BASE
        try {
            SteamStoreClient.BASE = url()
            val a = SteamStoreClient.getAssets(440).getOrThrow()
            assertEquals("Team Fortress 2", a.name)
            assertEquals(1, a.screenshots.size)
            val m = a.movies.single()
            // http is rewritten to https, and hls wins the priority order
            assertEquals("https://cdn/v.m3u8", m.hls)
            assertEquals("https://cdn/v.mpd", m.dash)
            assertEquals("https://cdn/v.m3u8", m.mp4, "hls must take priority")
            // legacy fields stay populated for the theme's quality selector
            assertEquals("https://cdn/480.mp4", m.mp4_480)
            assertEquals("https://cdn/max.mp4", m.mp4_max)
        } finally { SteamStoreClient.BASE = old }
    }

    @Test fun `steam assets fall back to legacy mp4 when no hls or dash`() {
        server.enqueue(MockResponse().setBody("""
            {"440":{"success":true,"data":{"name":"X","movies":[
              {"id":1,"name":"T","thumbnail":"t","mp4":{"480":"http://cdn/480.mp4","max":"http://cdn/max.mp4"}}
            ]}}}
        """.trimIndent()))
        val old = SteamStoreClient.BASE
        try {
            SteamStoreClient.BASE = url()
            assertEquals("https://cdn/480.mp4", SteamStoreClient.getAssets(440).getOrThrow().movies.single().mp4)
        } finally { SteamStoreClient.BASE = old }
    }

    @Test fun `steam reports failure when success is false`() {
        server.enqueue(MockResponse().setBody("""{"440":{"success":false}}"""))
        val old = SteamStoreClient.BASE
        try {
            SteamStoreClient.BASE = url()
            assertTrue(SteamStoreClient.getAssets(440).isFailure)
        } finally { SteamStoreClient.BASE = old }
    }

    // ── IGN ─────────────────────────────────────────────────────────────────

    @Test fun `ign search flattens platforms across regions and releases`() {
        server.enqueue(MockResponse().setBody("""
            {"data":{"searchObjectsByName":{"objects":[
              {"slug":"metroid-fusion","id":"abc",
               "metadata":{"names":{"name":"Metroid Fusion"}},
               "primaryImage":{"url":"https://ign/cover.jpg"},
               "objectRegions":[{"releases":[
                 {"platformAttributes":[{"name":"Game Boy Advance"},{"name":"Wii U"}]},
                 {"platformAttributes":[{"name":"Wii U"}]}
               ]}]}
            ]}}}
        """.trimIndent()))
        val old = IgnClient.GQL
        try {
            IgnClient.GQL = url()
            val g = IgnClient.search("Metroid Fusion").getOrThrow().single()
            assertEquals("Metroid Fusion", g.title)
            assertEquals("metroid-fusion", g.slug)
            assertEquals("https://ign/cover.jpg", g.coverUrl)
            assertEquals(listOf("Game Boy Advance", "Wii U"), g.platforms, "duplicates must collapse")
        } finally { IgnClient.GQL = old }
    }

    @Test fun `ign details prefer the long description`() {
        server.enqueue(MockResponse().setBody("""
            {"data":{"objectSelectByTypeAndSlug":{
              "metadata":{"names":{"name":"Contra"},"descriptions":{"long":"Long text","short":"Short"}},
              "primaryImage":{"url":"https://ign/c.jpg"},
              "genres":[{"name":"Action"},{"name":"Shooter"}],
              "primaryReview":{"score":8.5}}}}
        """.trimIndent()))
        val old = IgnClient.GQL
        try {
            IgnClient.GQL = url()
            val d = IgnClient.getDetails("contra").getOrThrow()
            assertEquals("Contra", d.title)
            assertEquals("Long text", d.description)
            assertEquals(listOf("Action", "Shooter"), d.genres)
            assertEquals(8.5, d.score)
        } finally { IgnClient.GQL = old }
    }

    // ── IGDB ────────────────────────────────────────────────────────────────

    @Test fun `igdb rewrites image urls to the requested size`() {
        server.enqueue(MockResponse().setBody("""
            [{"id":1,"url":"//images.igdb.com/igdb/image/upload/t_thumb/co64pi.jpg","width":264,"height":374}]
        """.trimIndent()))
        val old = IgdbClient.BASE
        try {
            IgdbClient.BASE = url()
            val cover = IgdbClient.getCovers(6351, "cid", "token").getOrThrow().single()
            assertEquals("https://images.igdb.com/igdb/image/upload/t_1080p/co64pi.jpg", cover.url)
            assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big/co64pi.jpg", cover.thumb)
            assertEquals(264, cover.width)
        } finally { IgdbClient.BASE = old }
    }

    @Test fun `igdb details pull developer and publisher out of involved companies`() {
        server.enqueue(MockResponse().setBody("""
            [{"id":1119,"name":"Castlevania III","summary":"Summary text",
              "genres":[{"name":"Platform"}],
              "involved_companies":[
                {"company":{"name":"Konami"},"developer":true,"publisher":false},
                {"company":{"name":"Konami USA"},"developer":false,"publisher":true}],
              "game_modes":[{"name":"Single player"}],
              "first_release_date":628560000,
              "aggregated_rating":84.5}]
        """.trimIndent()))
        val old = IgdbClient.BASE
        try {
            IgdbClient.BASE = url()
            val d = IgdbClient.getDetails(1119, "cid", "token").getOrThrow()
            assertEquals("Castlevania III", d.title)
            assertEquals("Summary text", d.description)
            assertEquals("Konami", d.developer)
            assertEquals("Konami USA", d.publisher)
            assertEquals("84/100", d.score)
            assertEquals(1989, d.releaseYear)
            assertEquals(listOf("Single player"), d.gameModes)
        } finally { IgdbClient.BASE = old }
    }

    @Test fun `igdb sends client id and bearer token`() {
        server.enqueue(MockResponse().setBody("[]"))
        val old = IgdbClient.BASE
        try {
            IgdbClient.BASE = url()
            IgdbClient.getCovers(1, "MYCLIENT", "MYTOKEN").getOrThrow()
            val req = server.takeRequest()
            assertEquals("MYCLIENT", req.getHeader("Client-ID"))
            assertEquals("Bearer MYTOKEN", req.getHeader("Authorization"))
        } finally { IgdbClient.BASE = old }
    }
}
