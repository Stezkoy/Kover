package com.ark.kover.scraper

import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.GameCandidate
import com.ark.kover.core.model.MatchResult
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.scraper.Scraper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/**
 * Steam Store: no API key at all.
 *
 * storesearch finds app ids, appdetails returns description, genres, developers,
 * release date, header image and screenshots. Works for games that exist on Steam.
 */
class SteamStoreScraper @Inject constructor(
    private val httpClient: OkHttpClient,
) : Scraper {

    override val id = "steam"
    override val displayName = "Steam Store"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun isConfigured(): Boolean = true

    override suspend fun search(system: String, name: String, md5: String?): MatchResult? {
        val candidate = searchCandidates(system, name, md5).firstOrNull() ?: return null
        return fetchFor(system, candidate.id)
    }

    override suspend fun searchCandidates(
        system: String,
        name: String,
        md5: String?,
    ): List<GameCandidate> {
        val url = "https://store.steampowered.com/api/storesearch/".toHttpUrl().newBuilder()
            .addQueryParameter("term", name)
            .addQueryParameter("l", "english")
            .addQueryParameter("cc", "us")
            .build()
        val body = get(url) ?: return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["items"]?.jsonArray.orEmpty().mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                GameCandidate(
                    id = id,
                    name = title,
                    source = this.id,
                    system = system,
                    matchedBy = "name",
                    confidence = 0.75f,
                )
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun fetchFor(system: String, gameId: String): MatchResult? {
        val url = "https://store.steampowered.com/api/appdetails".toHttpUrl().newBuilder()
            .addQueryParameter("appids", gameId)
            .addQueryParameter("l", "english")
            .addQueryParameter("cc", "us")
            .build()
        val body = get(url) ?: return null
        val game = runCatching {
            json.parseToJsonElement(body).jsonObject[gameId]
                ?.jsonObject?.get("data")?.jsonObject
        }.getOrNull() ?: return null

        val name = game["name"]?.jsonPrimitive?.content.orEmpty()
        val descHtml = game["about_the_game"]?.jsonPrimitive?.content
            ?: game["detailed_description"]?.jsonPrimitive?.content.orEmpty()
        val desc = stripHtml(descHtml)

        val assets = mutableListOf<Asset>()
        game["header_image"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
            assets += Asset(kind = "fanart", url = it, mime = "image/jpeg")
        }
        game["background_raw"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
            assets += Asset(kind = "fanart", url = it, mime = "image/jpeg")
        }
        game["screenshots"]?.jsonArray.orEmpty().forEach { shot ->
            val path = shot.jsonObject["path_full"]?.jsonPrimitive?.content ?: return@forEach
            assets += Asset(kind = "screenshots", url = path, mime = "image/jpeg")
        }

        val developers = game["developers"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.content }.joinToString(", ")
        val publishers = game["publishers"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.content }.joinToString(", ")
        val genres = game["genres"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["description"]?.jsonPrimitive?.content }
            .joinToString(", ")
        val release = game["release_date"]?.jsonObject?.get("date")?.jsonPrimitive?.content.orEmpty()
        val metacritic = game["metacritic"]?.jsonObject?.get("score")?.jsonPrimitive?.content
            ?.toFloatOrNull()

        val metadata = Metadata(
            name = name,
            desc = desc,
            rating = ((metacritic ?: 0f) / 100f).coerceIn(0f, 1f),
            releaseDate = parseReleaseDate(release),
            developer = developers,
            publisher = publishers,
            genre = genres,
            players = "",
        )

        return MatchResult(
            romId = "",
            gameName = name,
            metadata = metadata,
            assets = assets.distinctBy { it.kind to it.url },
            source = id,
            confidence = 0.75f,
            matchedBy = "name",
        )
    }

    override suspend fun fetchAssets(system: String, gameId: String, kinds: List<String>): List<Asset> =
        emptyList()

    private fun get(url: okhttp3.HttpUrl): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) Kover")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.e("SteamStore", "HTTP ${response.code} for $url")
                return null
            }
            response.body?.string()
        }
    } catch (e: Exception) {
        android.util.Log.e("SteamStore", "request failed", e)
        null
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** "28 Apr, 2017" / "Apr 28, 2017" -> "20170428T000000" */
    private fun parseReleaseDate(value: String): String {
        if (value.isBlank()) return ""
        val patterns = listOf("d MMM, yyyy", "MMM d, yyyy", "yyyy-MM-dd", "d MMM yyyy")
        for (pattern in patterns) {
            runCatching {
                val date = SimpleDateFormat(pattern, Locale.ENGLISH).parse(value)
                if (date != null) {
                    return SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(date) + "T000000"
                }
            }
        }
        return ""
    }
}
