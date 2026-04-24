package com.pegasus.bridge.media.sources

import com.pegasus.bridge.core.HttpClient
import org.json.JSONObject

// Port di CoverScraperService.js — sezione IGN (~line 164-300)
// Persisted query hashes copiati verbatim dal JS — sono version-pinned, non generarli.
object IgnClient {

    private const val GQL = "https://mollusk.apis.ign.com/graphql"

    // Copiati verbatim da CoverScraperService.js — non modificare
    private const val HASH_SEARCH  = "e1c2e012a21b4a98aaa618ef1b43eb0cafe9136303274a34f5d9ea4f2446e884"
    private const val HASH_DETAILS = "b9c48f45a7390ecd157229419dc9a2acb48de90c0f255b667076befb38338de6"
    private const val HASH_GALLERY = "06204b0f0871f8382e3adab7d1c59399e6c17ac94bff575c20a12ebf9d880b86"

    private val HEADERS = mapOf(
        "apollographql-client-name"    to "kraken",
        "apollographql-client-version" to "v0.67.0",
        "apollo-require-preflight"     to "true"
    )

    data class IgnGame(
        val title: String,
        val slug: String,
        val id: String,
        val coverUrl: String,
        val platforms: List<String>
    )

    data class IgnDetails(
        val title: String,
        val coverUrl: String,
        val description: String,
        val genres: List<String>,
        val score: Double?
    )

    private fun buildUrl(operationName: String, variables: JSONObject, hash: String): String {
        val ext = JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash))
        return "$GQL?operationName=$operationName" +
               "&variables=${java.net.URLEncoder.encode(variables.toString(), "UTF-8")}" +
               "&extensions=${java.net.URLEncoder.encode(ext.toString(), "UTF-8")}"
    }

    // Mirrors searchIGN()
    fun search(term: String): Result<List<IgnGame>> {
        val vars = JSONObject()
            .put("term", term)
            .put("count", 20)
            .put("objectType", "Game")
        val url = buildUrl("SearchObjectsByName", vars, HASH_SEARCH)
        return HttpClient.get(url, HEADERS).map { body ->
            val resp = JSONObject(body)
            resp.optJSONObject("errors")?.let { throw Exception(it.toString()) }
            val objects = resp
                .optJSONObject("data")
                ?.optJSONObject("searchObjectsByName")
                ?.optJSONArray("objects") ?: return@map emptyList()

            (0 until objects.length()).map { i ->
                val o = objects.getJSONObject(i)
                val platforms = mutableListOf<String>()
                o.optJSONArray("objectRegions")?.let { regions ->
                    for (r in 0 until regions.length()) {
                        val releases = regions.getJSONObject(r).optJSONArray("releases") ?: continue
                        for (rl in 0 until releases.length()) {
                            val pa = releases.getJSONObject(rl).optJSONArray("platformAttributes") ?: continue
                            for (p in 0 until pa.length()) {
                                val name = pa.getJSONObject(p).optString("name")
                                if (name.isNotEmpty() && name !in platforms) platforms += name
                            }
                        }
                    }
                }
                IgnGame(
                    title    = o.optJSONObject("metadata")?.optJSONObject("names")?.optString("name") ?: "",
                    slug     = o.optString("slug"),
                    id       = o.optString("id"),
                    coverUrl = o.optJSONObject("primaryImage")?.optString("url") ?: "",
                    platforms = platforms
                )
            }
        }
    }

    // Mirrors getIGNDetails()
    fun getDetails(slug: String): Result<IgnDetails> {
        val vars = JSONObject()
            .put("slug", slug)
            .put("objectType", "Game")
            .put("region", "us")
            .put("state", "Published")
        val url = buildUrl("ObjectSelectByTypeAndSlug", vars, HASH_DETAILS)
        return HttpClient.get(url, HEADERS).map { body ->
            val resp = JSONObject(body)
            val g = resp.optJSONObject("data")?.optJSONObject("objectSelectByTypeAndSlug")
                ?: throw Exception("Not found")

            val desc = g.optJSONObject("metadata")?.optJSONObject("descriptions")?.let {
                it.optString("long").ifEmpty { it.optString("short") }
            } ?: ""

            val genres = g.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }
            } ?: emptyList()

            IgnDetails(
                title       = g.optJSONObject("metadata")?.optJSONObject("names")?.optString("name") ?: "",
                coverUrl    = g.optJSONObject("primaryImage")?.optString("url") ?: "",
                description = desc,
                genres      = genres,
                score       = g.optJSONObject("primaryReview")?.let {
                    if (it.has("score")) it.getDouble("score") else null
                }
            )
        }
    }

    // Mirrors getIGNImages()
    fun getImages(slug: String): Result<List<String>> {
        val vars = JSONObject()
            .put("slug", slug)
            .put("objectType", "Game")
            .put("count", 10)
        val url = buildUrl("ObjectImageGallery", vars, HASH_GALLERY)
        return HttpClient.get(url, HEADERS).map { body ->
            val resp = JSONObject(body)
            val imgs = resp
                .optJSONObject("data")
                ?.optJSONObject("objectSelectByTypeAndSlug")
                ?.optJSONObject("imageGallery")
                ?.optJSONArray("images") ?: return@map emptyList()
            (0 until imgs.length()).mapNotNull { imgs.getJSONObject(it).optString("url").ifEmpty { null } }
        }
    }
}
