package com.pegasus.bridge.scrapers

import com.pegasus.bridge.core.Config
import com.pegasus.bridge.core.HttpClient
import com.pegasus.bridge.core.ScreenScraperCreds
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ScreenScraper — the only source here that identifies a game by what the file *is*
 * rather than by what it is called.
 *
 * Every other scraper is asked "which game is named this?" and the answer has to be
 * matched by title, which is where a retro library loses games: IGN calls DuckTales
 * "Disney's DuckTales" and an unattended rule rightly refuses it. libretro improved on
 * that by matching the ROM's *file name*. This one matches the file's **hash**, which
 * survives a rename.
 *
 * ── Two credential pairs, and they are not interchangeable ──
 *
 * `devid`/`devpassword` identify the application; the API refuses every request without
 * them. `ssid`/`sspassword` are a member login and only lift the quota off the anonymous
 * floor. `softname` is checked against the name registered with the devid and a mismatch
 * is refused, so it is not decoration — [Config] defaults it to the registered value.
 *
 * ── What identifies a game, per system ─────────────────────
 *
 * For cartridge and disc systems the hashes do it: `md5`, `crc`, `sha1`, plus the file
 * size. [com.pegasus.bridge.hasher.HashResult] already carries `fileMd5` and
 * `fileCrc32` — and note those describe the ROM *inside* an archive, not the archive,
 * which is right here and wrong for MAME.
 *
 * For **arcade**, ScreenScraper is keyed by the MAME romset short name, so the identity
 * is `romnom=pacman.zip` and the hashes must not be sent: they would describe the
 * extracted ROM and match nothing. This is why arcade works here where libretro cannot —
 * libretro's MAME repository is keyed by MAME *descriptions*, which no ROM set carries.
 */
object ScreenScraperClient {

    // `internal var` rather than const so the tests can point it at a MockWebServer and
    // exercise the parsers without a network or a quota.
    internal var BASE = "https://api.screenscraper.fr/api2"

    /**
     * One request at a time, for the whole process.
     *
     * The live account reports `maxThreads: 1`, and that is a limit the API states
     * rather than one anybody estimated. Pacing the collection scraper down to one game
     * in flight is not enough on its own: the Game Database is a second caller and can
     * fire while a run is going, so the guarantee has to live where every call passes.
     * The cost of getting this wrong is not a slow scan — RetroAchievements answered 85
     * requests of 913 and refused the rest, and the refusals were cached as answers.
     */
    private val gate = ReentrantLock(true)

    /** What the API says about the account, which is also the only way to prove the credentials. */
    data class Quota(
        val user: String,
        val maxThreads: Int,
        val requestsToday: Int,
        val maxRequestsPerDay: Int,
        val maxRequestsPerMinute: Int
    )

    /**
     * Why a call did not produce a game — and, crucially, whether it was an *answer*.
     *
     * [NOT_FOUND] is the database saying "no such ROM", which is true and stays true:
     * it may be cached, and a rerun need not ask again. Everything else is the API
     * declining to answer, and caching one of those as "not found" is precisely the bug
     * that cost a whole RetroAchievements scan. The two must never share a value.
     */
    enum class Refusal {
        NOT_FOUND,          // the only one that means "no"
        LOGIN,              // wrong devid/devpassword, or the wrong password of the two
        SOFTNAME,           // not the name registered with the devid
        CLOSED,             // API closed for everyone — nothing to do with this user
        THREADS,            // 429: too many at once
        DAILY_QUOTA,        // 430: the day's allowance is gone
        UNKNOWN_ROMS,       // 431: too many unrecognised ROMs — stop, do not grind on
        TRANSPORT,          // never reached the API at all
        OTHER;

        /** True only for a verdict the database actually gave. */
        val isAnswer: Boolean get() = this == NOT_FOUND

        /** Whether carrying on through a library would just deepen the hole. */
        val isFatalToARun: Boolean
            get() = this == UNKNOWN_ROMS || this == DAILY_QUOTA || this == CLOSED ||
                    this == LOGIN || this == SOFTNAME
    }

    class ScreenScraperException(message: String, val refusal: Refusal) : Exception(message)

    /** [format] is the file extension the API reports, needed to name a download. */
    data class Media(
        val type: String,
        val region: String,
        val url: String,
        val format: String = ""
    )

    data class Game(
        val id: String,
        val title: String,
        val publisher: String,
        val developer: String,
        val players: String,
        val releaseYear: String,
        val genres: List<String>,
        val description: String,
        val rating: String,
        val media: List<Media>
    )

    /** One line of `systemesListe.php`, which is what turns a guess into a table. */
    data class SsSystem(val id: Int, val names: List<String>, val extensions: List<String>)

    /**
     * The credential parameters every call carries.
     *
     * The member login is appended **only when both halves are present**: sending an
     * `ssid` with no `sspassword` is not a smaller request, it is a rejected one.
     */
    private fun authParams(c: ScreenScraperCreds): String {
        val sb = StringBuilder()
        sb.append("devid=").append(enc(c.devId))
        sb.append("&devpassword=").append(enc(c.devPassword))
        sb.append("&softname=").append(enc(c.softname))
        sb.append("&output=json")
        if (c.ssid.isNotEmpty() && c.ssPassword.isNotEmpty()) {
            sb.append("&ssid=").append(enc(c.ssid))
            sb.append("&sspassword=").append(enc(c.ssPassword))
        }
        return sb.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun creds(config: Config): Result<ScreenScraperCreds> {
        val c = config.load().screenScraper
        return when {
            c == null || c.devId.isEmpty() || c.devPassword.isEmpty() ->
                Result.failure(Exception("missing screenScraper.devId/devPassword in credentials.json"))
            else -> Result.success(c)
        }
    }

    /**
     * Verifies the credentials and reports the quota.
     *
     * This is the cheapest call the API has and the only one whose failure means
     * "your credentials are wrong" rather than "that game is not in the database", so
     * it is what a settings screen should use to prove a login — the same job
     * `refreshRaProfile` does for RetroAchievements.
     */
    fun userInfo(config: Config): Result<Quota> {
        val c = creds(config).getOrElse { return Result.failure(it) }
        val url = "$BASE/ssuserInfos.php?" + authParams(c)
        // Patient, like the other two, and here it matters most: this is the call whose
        // failure the login card reports as *"your credentials are wrong"*. On the
        // impatient client a slow ScreenScraper — which it is, regularly — produced
        // "timeout", and a working account was told it had a bad password. Measured:
        // the same credentials answered in under a second on one attempt and took more
        // than ten on the next.
        //
        // `getRaw` rather than `get` for the same reason as everywhere else: a refusal
        // arrives in the body, and folding a non-2xx into `HTTP <code>` throws away the
        // one sentence that says which of the two passwords is in the wrong box.
        val resp = gate.withLock { HttpClient.getRaw(url, patient = true) }.getOrElse {
            return Result.failure(ScreenScraperException(
                "could not reach ScreenScraper: ${it.message}", Refusal.TRANSPORT))
        }
        return parseUserInfo(resp.body)
    }

    // ── The search that is not a search ──────────────────────────────────────

    /**
     * Identifies one ROM. This is the whole point of the source.
     *
     * Callers pass the ROM's own digests — of the file *inside* an archive, since that
     * is what No-Intro and Redump list — or, for MAME, its [romName] and nothing else.
     * The two are not alternatives to try in turn: sending hashes for an arcade set
     * describes the extracted ROM and matches nothing, so a caller that sends both has
     * made the arcade case strictly worse. [forSystemsMatchedByName] decides which,
     * and the dispatcher asks it rather than guessing per call site.
     *
     * [systemeId] is optional for a hash lookup — the digest is unique enough on its
     * own — and required for a name lookup, where `pacman` means nothing without it.
     *
     * [lang] and the ROM's region steer which of several descriptions and pictures come
     * back first; neither changes whether the game is found.
     */
    fun jeuInfos(
        config: Config,
        md5: String = "",
        crc: String = "",
        size: Long = 0,
        romName: String = "",
        systemeId: Int = 0,
        lang: String = "en"
    ): Result<Game> {
        val c = creds(config).getOrElse { return Result.failure(it) }
        if (md5.isEmpty() && crc.isEmpty() && romName.isEmpty())
            return Result.failure(ScreenScraperException(
                "nothing to identify the ROM by — no hash and no file name", Refusal.OTHER))

        val sb = StringBuilder("$BASE/jeuInfos.php?").append(authParams(c))
        sb.append("&romtype=rom")
        if (systemeId > 0) sb.append("&systemeid=").append(systemeId)
        // The name goes on every request, hashes or not: it costs nothing and it is what
        // the API falls back to when the digests are unknown to it.
        if (romName.isNotEmpty()) sb.append("&romnom=").append(enc(romName))
        if (md5.isNotEmpty()) sb.append("&md5=").append(enc(md5))
        if (crc.isNotEmpty()) sb.append("&crc=").append(enc(crc))
        if (size > 0) sb.append("&romtaille=").append(size)

        val resp = gate.withLock { HttpClient.getRaw(sb.toString(), patient = true) }.getOrElse {
            return Result.failure(ScreenScraperException(
                "could not reach ScreenScraper: ${it.message}", Refusal.TRANSPORT))
        }
        return parseJeuInfos(resp.body, romName, lang)
    }

    /**
     * ScreenScraper is keyed by the MAME romset short name on these, not by content.
     *
     * `romnom=pacman.zip` is the identity, and it is why arcade works here where the
     * libretro thumbnails cannot: those are keyed by MAME *descriptions*, which no ROM
     * set carries. Sending a hash instead would describe whatever the zip holds and
     * match nothing at all.
     */
    fun forSystemsMatchedByName(shortName: String): Boolean =
        ScreenScraperSystemMap.matchedByName(shortName)

    internal fun parseJeuInfos(body: String, romName: String, lang: String): Result<Game> {
        val root = try { JSONObject(body) } catch (e: Exception) {
            return Result.failure(refusalFrom(body))
        }
        val jeu = root.optJSONObject("response")?.optJSONObject("jeu")
        // A body that parses but carries no game is a refusal too — the same trap as
        // `ssuser`, where valid JSON with no account block would otherwise read as a
        // successful login with an empty username.
            ?: return Result.failure(refusalFrom(body))

        return Result.success(Game(
            id          = jeu.optString("id"),
            title       = pickRegional(jeu.optJSONArray("noms"), regionOrder(romName), "text")
                            .ifEmpty { romName },
            publisher   = jeu.optJSONObject("editeur")?.optString("text").orEmpty(),
            developer   = jeu.optJSONObject("developpeur")?.optString("text").orEmpty(),
            players     = jeu.optJSONObject("joueurs")?.optString("text").orEmpty(),
            releaseYear = pickRegional(jeu.optJSONArray("dates"), regionOrder(romName), "text")
                            .take(4),
            genres      = parseGenres(jeu.optJSONArray("genres"), lang),
            description = pickByLanguage(jeu.optJSONArray("synopsis"), lang),
            rating      = jeu.optJSONObject("note")?.optString("text").orEmpty(),
            media       = parseMedia(jeu.optJSONArray("medias"))
        ))
    }

    /**
     * Which regional variant to prefer, taken from the ROM's own name.
     *
     * A No-Intro file says which release it is — `(USA)`, `(Europe)`, `(Japan)` — and
     * that is a better answer than a fixed order, because the box art of a European
     * release is not the box art of the American one. The fixed tail is the fallback for
     * the majority of files that say nothing, and `wor` leads it: a world release is the
     * one picture that is right for everybody.
     */
    internal fun regionOrder(romName: String): List<String> {
        val n = romName.lowercase()
        val first = when {
            n.contains("(usa") || n.contains("(us)") || n.contains(", usa")  -> "us"
            n.contains("(europe") || n.contains("(eu)")                       -> "eu"
            n.contains("(japan") || n.contains("(jp)")                        -> "jp"
            n.contains("(world")                                             -> "wor"
            n.contains("(france") || n.contains("(fr)")                      -> "fr"
            n.contains("(germany")                                           -> "de"
            n.contains("(italy")                                             -> "it"
            n.contains("(spain")                                             -> "sp"
            else                                                             -> ""
        }
        val tail = listOf("wor", "us", "eu", "jp", "fr", "de", "sp", "it", "ss", "")
        return if (first.isEmpty()) tail else listOf(first) + tail.filter { it != first }
    }

    /**
     * The first entry whose `region` appears earliest in [order].
     *
     * Anything with an unlisted region still counts, last: a picture from a region
     * nobody asked about beats no picture at all, and refusing it would drop entire
     * releases for the sake of tidiness.
     */
    internal fun pickRegional(arr: JSONArray?, order: List<String>, field: String): String {
        if (arr == null || arr.length() == 0) return ""
        var best: String = ""
        var bestRank = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString(field)
            if (text.isEmpty()) continue
            val region = o.optString("region")
            val rank = order.indexOf(region).let { if (it < 0) order.size else it }
            if (rank < bestRank) { bestRank = rank; best = text }
        }
        return best
    }

    /** Same idea for `synopsis`, which is keyed by `langue` rather than by region. */
    internal fun pickByLanguage(arr: JSONArray?, lang: String): String {
        if (arr == null || arr.length() == 0) return ""
        val order = listOf(lang.lowercase(), "en", "fr")
        var best = ""
        var bestRank = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text")
            if (text.isEmpty()) continue
            val rank = order.indexOf(o.optString("langue")).let { if (it < 0) order.size else it }
            if (rank < bestRank) { bestRank = rank; best = text }
        }
        return best
    }

    /**
     * Genre names in the caller's language.
     *
     * Each genre carries its own `noms` array, one entry per language, so the shape is
     * a list of lists. `nomcourt` is the untranslated fallback rather than a synonym —
     * using it when the language is present would produce a mix of French and English
     * in one field.
     */
    internal fun parseGenres(arr: JSONArray?, lang: String): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val name = pickByLanguage(g.optJSONArray("noms"), lang)
                .ifEmpty { g.optString("nomcourt") }
            if (name.isNotEmpty() && !out.contains(name)) out.add(name)
        }
        return out
    }

    internal fun parseMedia(arr: JSONArray?): List<Media> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Media>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val url = m.optString("url")
            if (url.isEmpty()) continue
            out.add(Media(
                type   = m.optString("type"),
                region = m.optString("region"),
                url    = url,
                format = m.optString("format")
            ))
        }
        return out
    }

    /**
     * ScreenScraper's `note` is **out of 20**, and nothing downstream knows that.
     *
     * The theme's `ScraperMatch.normalizeScore` handles two scales, /10 and /100, and
     * chooses between them by whether the number exceeds 10 — a rule that is right for
     * IGN and IGDB and silently wrong here: an 18/20 handed over untouched becomes
     * 0.18, so one of the best-reviewed games on a system displays as 18 %. Converting
     * to a percentage string keeps that shared function correct for all three sources
     * instead of teaching it a third special case.
     */
    internal fun scoreOutOf20(note: String): String {
        val n = note.trim().toDoubleOrNull() ?: return ""
        if (n <= 0) return ""
        return "${Math.round(n / 20.0 * 100)}/100"
    }

    // ── Media ────────────────────────────────────────────────────────────────

    /**
     * The media types that serve each of the theme's four categories, best first.
     *
     * Screenshots share the wallpaper slot by an explicit decision, so `ss` and
     * `sstitle` appear under both — the in-game frame and the title screen. `fanart`
     * leads wallpaper because it is the only one drawn at anything like a full-screen
     * size; a NES screenshot is 256×240 and a 7.5× upscale.
     */
    fun mediaTypesFor(kind: String): List<String> = when (kind) {
        "cover"      -> listOf("box-2D", "box-2D-side", "box-3D", "support-2D")
        "wheel"      -> listOf("wheel-hd", "wheel", "wheel-carbon", "wheel-steel", "screenmarquee")
        "wallpaper"  -> listOf("fanart", "ss", "sstitle")
        "screenshot" -> listOf("ss", "sstitle", "fanart")
        "video"      -> listOf("video-normalized", "video")
        else         -> emptyList()
    }

    /**
     * The best media of a kind, or null.
     *
     * Type order decides first and region second — a `box-2D` from the wrong region is
     * still a cover, while a `box-3D` from the right one is a different picture.
     */
    fun pickMedia(media: List<Media>, kind: String, romName: String): Media? {
        val order = regionOrder(romName)
        for (type in mediaTypesFor(kind)) {
            val candidates = media.filter { it.type == type }
            if (candidates.isEmpty()) continue
            return candidates.minByOrNull { m ->
                order.indexOf(m.region).let { if (it < 0) order.size else it }
            }
        }
        return null
    }

    /**
     * Fetches one media to a local file and answers with its path.
     *
     * This is not an optimisation, it is the reason the theme can use this source at
     * all: a ScreenScraper media URL carries `devid`, `devpassword` and `sspassword` in
     * its query string, and handing one to the theme would write the credentials into
     * `custom_covers_map` on disk. The Bridge owns the keys; what crosses the seam is a
     * file path.
     */
    fun fetchMedia(config: Config, media: Media, target: File): Result<File> {
        creds(config).getOrElse { return Result.failure(it) }
        return gate.withLock { HttpClient.download(media.url, target) }
            .map { target }
            .recoverCatching { throw ScreenScraperException(
                "could not download the ${media.type}: ${it.message}", Refusal.TRANSPORT) }
    }

    // ── The system table ─────────────────────────────────────────────────────

    /**
     * Every system the API knows, with its numeric id.
     *
     * Exists so the short-name table is checked against the API rather than written
     * from memory: an id that is merely plausible fails as a plain "no such game",
     * which is indistinguishable from a ROM the database really does not have.
     */
    fun systems(config: Config): Result<List<SsSystem>> {
        val c = creds(config).getOrElse { return Result.failure(it) }
        val url = "$BASE/systemesListe.php?" + authParams(c)
        val resp = gate.withLock { HttpClient.getRaw(url, patient = true) }.getOrElse {
            return Result.failure(ScreenScraperException(
                "could not reach ScreenScraper: ${it.message}", Refusal.TRANSPORT))
        }
        return parseSystems(resp.body)
    }

    internal fun parseSystems(body: String): Result<List<SsSystem>> {
        val root = try { JSONObject(body) } catch (e: Exception) {
            return Result.failure(refusalFrom(body))
        }
        val arr = root.optJSONObject("response")?.optJSONArray("systemes")
            ?: return Result.failure(refusalFrom(body))
        val out = mutableListOf<SsSystem>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("id").toIntOrNull() ?: continue
            val noms = s.optJSONObject("noms")
            val names = mutableListOf<String>()
            if (noms != null) {
                for (k in noms.keys()) {
                    val v = noms.optString(k)
                    // `noms_commun` and `nom_recalbox` are comma-separated alias lists,
                    // and they are where a Pegasus short name actually turns up.
                    v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { if (!names.contains(it)) names.add(it) }
                }
            }
            val ext = s.optString("extensions").split(',')
                .map { it.trim() }.filter { it.isNotEmpty() }
            out.add(SsSystem(id, names, ext))
        }
        return Result.success(out)
    }

    internal fun parseUserInfo(body: String): Result<Quota> {
        // ScreenScraper answers a bad login with plain text, not JSON — so a parse
        // failure here is a credential failure, and saying "unreadable response" would
        // send the reader looking for a network problem that is not there.
        val root = try { JSONObject(body) } catch (e: Exception) {
            return Result.failure(refusalFrom(body))
        }
        val ssuser = root.optJSONObject("response")?.optJSONObject("ssuser")
            ?: return Result.failure(refusalFrom(body))
        return Result.success(
            Quota(
                user                 = ssuser.optString("id"),
                maxThreads           = ssuser.optString("maxthreads").toIntOrNull() ?: 1,
                requestsToday        = ssuser.optString("requeststoday").toIntOrNull() ?: 0,
                maxRequestsPerDay    = ssuser.optString("maxrequestsperday").toIntOrNull() ?: 0,
                maxRequestsPerMinute = ssuser.optString("maxrequestspermin").toIntOrNull() ?: 0
            )
        )
    }

    /**
     * Turns the API's plain-text refusals into something a settings screen can show.
     *
     * These are the ones worth naming, because each sends the reader somewhere
     * different: a wrong devid is a typo, a closed API is nothing to do with the user,
     * and a wrong member login still leaves the developer pair working.
     */
    internal fun explainPlainText(body: String): String = refusalFrom(body).message.orEmpty()

    /**
     * Reads a refusal out of the response body, and says which kind it is.
     *
     * **Order matters here and the order is not alphabetical.** The three quota
     * messages all contain the word "quota", and the one that must be recognised first
     * is 431 — too many unrecognised ROMs — because it is the only one that means
     * *stop*: it is triggered by scanning a library full of files the database does not
     * know, which is exactly what the test libraries are, and grinding on through it
     * gets the account throttled rather than eventually succeeding.
     *
     * The distinction that matters most is the last one: "no such ROM" is an answer and
     * may be remembered; every other line here is the API declining to answer and must
     * be asked again next time. Letting those two share a value is what made the
     * RetroAchievements damage permanent.
     */
    internal fun refusalFrom(body: String): ScreenScraperException {
        val t = body.trim().take(300)
        val lower = t.lowercase()
        val (kind, message) = when {
            t.isEmpty() ->
                Refusal.OTHER to "ScreenScraper returned nothing"

            lower.contains("erreur de login") || lower.contains("error de login")
                || lower.contains("login error") ->
                Refusal.LOGIN to
                "ScreenScraper rejected the login — check which password belongs to the developer pair"

            lower.contains("softname") ->
                Refusal.SOFTNAME to
                "ScreenScraper rejected the softname — it must match the one registered with the devid"

            lower.contains("api totalement fermé") || lower.contains("api closed")
                || lower.contains("fermé") ->
                Refusal.CLOSED to
                "ScreenScraper has closed the API for now — not a credential problem"

            // 431, and it goes before the other two: it is the one that means stop.
            lower.contains("non reconnu") || lower.contains("nonrecognized")
                || lower.contains("not recognized") ->
                Refusal.UNKNOWN_ROMS to
                "ScreenScraper has seen too many unrecognised ROMs from this account — stop and try later"

            // 429
            lower.contains("threads") || lower.contains("simultané") ->
                Refusal.THREADS to "ScreenScraper: too many requests at once"

            // 430
            lower.contains("quota") || lower.contains("journalier") ->
                Refusal.DAILY_QUOTA to "ScreenScraper: the daily quota is used up"

            lower.contains("non trouvé") || lower.contains("non trouvee")
                || lower.contains("not found") || lower.contains("aucun jeu") ->
                Refusal.NOT_FOUND to "ScreenScraper does not have this ROM"

            else -> Refusal.OTHER to "ScreenScraper: $t"
        }
        return ScreenScraperException(message, kind)
    }
}
