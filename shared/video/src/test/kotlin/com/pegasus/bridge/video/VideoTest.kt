package com.pegasus.bridge.video

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

class YouTubeUrlDetectorTest {

    @Test fun `recognises the watch, short, embed and youtu-be forms`() {
        assertTrue(YouTubeUrlDetector.isYouTube("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(YouTubeUrlDetector.isYouTube("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=30"))
        assertTrue(YouTubeUrlDetector.isYouTube("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(YouTubeUrlDetector.isYouTube("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(YouTubeUrlDetector.isYouTube("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }

    @Test fun `rejects other hosts, bare channels and junk`() {
        assertFalse(YouTubeUrlDetector.isYouTube("https://vimeo.com/12345"))
        assertFalse(YouTubeUrlDetector.isYouTube("https://cdn.akamai.steamstatic.com/x.mp4"))
        assertFalse(YouTubeUrlDetector.isYouTube("https://www.youtube.com/@somechannel"))
        assertFalse(YouTubeUrlDetector.isYouTube("https://www.youtube.com/watch"), "no v= param")
        assertFalse(YouTubeUrlDetector.isYouTube(""))
        assertFalse(YouTubeUrlDetector.isYouTube("not a url at all"))
    }

    // java.net.URI throws where android.net.Uri returned an empty object, so the
    // parse has to be guarded — a malformed URL must be false, never a crash.
    @Test fun `malformed input returns false instead of throwing`() {
        assertFalse(YouTubeUrlDetector.isYouTube("https://you tube.com/watch?v=x"))
        assertFalse(YouTubeUrlDetector.isYouTube("://"))
        assertFalse(YouTubeUrlDetector.isYouTube("h ttp://youtube.com"))
    }

    @Test fun `extracts the video id from every accepted form`() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, YouTubeUrlDetector.videoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, YouTubeUrlDetector.videoId("https://www.youtube.com/watch?v=$id&list=X"))
        assertEquals(id, YouTubeUrlDetector.videoId("https://youtu.be/$id"))
        assertEquals(id, YouTubeUrlDetector.videoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, YouTubeUrlDetector.videoId("https://www.youtube.com/embed/$id"))
        assertNull(YouTubeUrlDetector.videoId("https://vimeo.com/12345"))
    }
}

class VideoRequestTest {

    @Test fun `accepts https and rejects anything else`() {
        assertEquals("https://x/y.mp4", VideoRequest.of("https://x/y.mp4")?.url)
        assertNull(VideoRequest.of("http://x/y.mp4"), "plain http must be refused")
        assertNull(VideoRequest.of("file:///etc/passwd"))
        assertNull(VideoRequest.of(""))
        assertNull(VideoRequest.of(null))
    }

    @Test fun `trims and defaults the optional fields`() {
        val r = VideoRequest.of("  https://x/y.mp4  ")!!
        assertEquals("https://x/y.mp4", r.url)
        assertEquals("", r.title)
        assertEquals("", r.gameKey)
    }

    // This normalises a filename, not a title: every alphanumeric is kept and
    // everything else dropped, so "Contra (USA)" collapses to "contrausa". The
    // pipe that separates title from platform becomes an underscore.
    @Test fun `sanitizes a game key into something filename-safe`() {
        assertEquals("supermariobros_nes", VideoRequest.sanitizeGameKey("Super Mario Bros.|nes"))
        assertEquals("contrausa", VideoRequest.sanitizeGameKey("Contra (USA)"))
        assertEquals("", VideoRequest.sanitizeGameKey(""))
        assertEquals("", VideoRequest.sanitizeGameKey("x".repeat(201)), "over-long keys are refused")
        assertFalse(VideoRequest.sanitizeGameKey("../../etc/passwd").contains("/"),
                    "path separators must not survive")
        assertFalse(VideoRequest.sanitizeGameKey("../../etc/passwd").contains("."),
                    "directory traversal must not survive")
    }
}

class VideoCallbackTest {

    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("video-cb").toFile() }
    @AfterTest  fun tearDown() { dir.deleteRecursively() }

    @Test fun `search results carry the page url the theme needs`() {
        val json = SearchCallback.okJson("contra trailer", listOf(
            YouTubeSearcher.Result("dQw4w9WgXcQ", "Contra Trailer", "Konami", 95,
                                   "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
        ))
        val o = JSONObject(json)
        assertEquals("ok", o.getString("status"))
        val r = o.getJSONArray("results").getJSONObject(0)
        assertEquals("dQw4w9WgXcQ", r.getString("videoId"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", r.getString("ytPageUrl"))
        assertEquals(95, r.getInt("durationSec"))
    }

    @Test fun `an empty result set is no_results rather than an error`() {
        assertEquals("no_results", JSONObject(SearchCallback.okJson("x", emptyList())).getString("status"))
    }

    @Test fun `download progress is clamped to the zero-one range`() {
        assertEquals(0.0, JSONObject(DownloadCallback.progressJson(-5f)).getDouble("progress"))
        assertEquals(1.0, JSONObject(DownloadCallback.progressJson(9f)).getDouble("progress"))
        assertEquals(0.5, JSONObject(DownloadCallback.progressJson(0.5f)).getDouble("progress"), 0.001)
    }

    @Test fun `error messages are truncated so a callback stays readable`() {
        val o = JSONObject(DownloadCallback.errorJson("e".repeat(2000)))
        assertEquals("error", o.getString("status"))
        assertTrue(o.getString("error").length <= 500)
    }

    @Test fun `atomic write leaves no temp file behind`() {
        val target = File(dir, "job1.json")
        DownloadCallback.atomicWrite(target, DownloadCallback.okJson("/tmp/x.mp4"))
        assertEquals("/tmp/x.mp4", JSONObject(target.readText()).getString("localPath"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
