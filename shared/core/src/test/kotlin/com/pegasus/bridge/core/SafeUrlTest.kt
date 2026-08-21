package com.pegasus.bridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeUrlTest {

    // The exact URL RaHashLookup logged on retry exhaustion. `y` is the API key.
    private val RA_URL =
        "https://retroachievements.org/API/API_GetGameExtended.php?z=someuser&y=SENTINELKEY123&i=1446"

    // The exact shape of a ScreenScraper call: two secrets, one URL.
    private val SS_URL =
        "https://api.screenscraper.fr/api2/jeuInfos.php?devid=dev&devpassword=SENTINELDEV" +
        "&softname=PegasusBridge&output=json&ssid=me&sspassword=SENTINELMEMBER&md5=abc"

    @Test fun `the retroachievements api key never survives redaction`() {
        val out = SafeUrl.redact(RA_URL)
        assertFalse(SafeUrl.leaks(out, "SENTINELKEY123"), "leaked: $out")
        assertTrue(out.contains("i=1446"), "the game id is what makes the line useful: $out")
        assertTrue(out.contains("z=someuser"), "the username is not a secret: $out")
    }

    @Test fun `both screenscraper passwords are redacted, and nothing else is`() {
        val out = SafeUrl.redact(SS_URL)
        assertFalse(SafeUrl.leaks(out, "SENTINELDEV"), "devpassword leaked: $out")
        assertFalse(SafeUrl.leaks(out, "SENTINELMEMBER"), "sspassword leaked: $out")
        assertTrue(out.contains("devid=dev"))
        assertTrue(out.contains("md5=abc"))
        assertTrue(out.contains("softname=PegasusBridge"))
    }

    @Test fun `redaction is case insensitive on the parameter name`() {
        val out = SafeUrl.redact("https://x/y?ApiKey=SECRET&Token=SECRET2&DevPassword=SECRET3")
        assertFalse(SafeUrl.leaks(out, "SECRET"), "leaked: $out")
    }

    @Test fun `a url with no query comes back unchanged`() {
        assertEquals("https://example.com/a/b", SafeUrl.redact("https://example.com/a/b"))
    }

    @Test fun `a fragment is kept and not mistaken for a parameter`() {
        val out = SafeUrl.redact("https://x/y?token=SECRET&a=1#frag")
        assertFalse(SafeUrl.leaks(out, "SECRET"))
        assertTrue(out.endsWith("#frag"), out)
        assertTrue(out.contains("a=1"))
    }

    // A value that happens to contain '=' must not shift the name boundary.
    @Test fun `a base64 value with padding is still redacted whole`() {
        val out = SafeUrl.redact("https://x/y?access_token=YWJjZA==&i=7")
        assertFalse(SafeUrl.leaks(out, "YWJjZA"), "leaked: $out")
        assertTrue(out.contains("i=7"))
    }

    @Test fun `label keeps the endpoint and drops the query secrets`() {
        val out = SafeUrl.label(RA_URL)
        assertFalse(SafeUrl.leaks(out, "SENTINELKEY123"), "leaked: $out")
        assertTrue(out.startsWith("retroachievements.org/API/API_GetGameExtended.php"), out)
        assertTrue(out.contains("i=1446"))
    }

    @Test fun `label of a url with only secrets is just the endpoint`() {
        assertEquals("x/y", SafeUrl.label("https://x/y?token=abc&apikey=def"))
    }

    // A blank sentinel means the test forgot to set one; answering true would
    // mark every log line as a leak and hide the real ones.
    @Test fun `a blank secret does not count as leaked`() {
        assertFalse(SafeUrl.leaks("anything at all", ""))
    }

    @Test fun `secret parameter names are recognised regardless of case`() {
        assertTrue(SafeUrl.isSecretParam("Y"))
        assertTrue(SafeUrl.isSecretParam("SSPASSWORD"))
        assertTrue(SafeUrl.isSecretParam("client_secret"))
        assertFalse(SafeUrl.isSecretParam("md5"))
        assertFalse(SafeUrl.isSecretParam("systemeid"))
    }

    @Test fun `authorization headers are known to be secret`() {
        assertTrue(SafeUrl.isSecretHeader("Authorization"))
        assertTrue(SafeUrl.isSecretHeader("x-api-key"))
        assertFalse(SafeUrl.isSecretHeader("User-Agent"))
    }
}
