package com.ark.kover.scraper

import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.GameCandidate
import com.ark.kover.core.model.MatchResult
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.scraper.Scraper
import com.ark.kover.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * RAWG (rawg.io): free personal API key, huge modern-game database.
 * Metadata + background image + screenshots.
 */
class RawgScraper @Inject constructor(
    private val settings: SettingsRepository,
    private val httpClient: OkHttpClient,
) : Scraper {

    override val id = "rawg"
    override val displayName = "RAWG"

    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://api.rawg.io/api"

    override fun isConfigured(): Boolean = true

    private suspend fun apiKey(): String = settings.settings.first().rawgApiKey.trim()

    private fun request(key: String, path: String, params: Map<String, String> = emptyMap()): Request {
        val url = "$base/$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("key", key)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).header("User-Agent", "Kover-Android").build()
    }

    private fun get(request: Request): String? = try {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.e("RAWG", "HTTP ${response.code} for ${request.url}")
                return null
            }
            response.body?.string()
        }
    } catch (e: Exception) {
        android.util.Log.e("RAWG", "request failed", e)
        null
    }

    override suspend fun search(system: String, name: String, md5: String?): MatchResult? {
        val candidate = searchCandidates(system, name, md5).firstOrNull() ?: return null
        return fetchFor(system, candidate.id)
    }

    override suspend fun searchCandidates(
        system: String,
        name: String,
        md5: String?,
    ): List<GameCandidate> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        val body = get(
            request(
                key,
                "games",
                mapOf("search" to name, "page_size" to "10"),
            ),
        ) ?: return emptyList()
        return runCatching {
            json.decodeFromString<RawgSearchResponse>(body).results.mapNotNull { game ->
                val id = game.id ?: return@mapNotNull null
                val title = game.name ?: return@mapNotNull null
                GameCandidate(
                    id = id.toString(),
                    name = title,
                    source = this.id,
                    system = system,
                    matchedBy = "name",
                    releaseDate = game.released.orEmpty(),
                    confidence = 0.8f,
                )
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun fetchFor(system: String, gameId: String): MatchResult? {
        val key = apiKey()
        if (key.isBlank()) return null
        val detailBody = get(request(key, "games/$gameId")) ?: return null
        val game = runCatching {
            json.decodeFromString<RawgGame>(detailBody)
        }.getOrNull() ?: return null

        val assets = mutableListOf<Asset>()
        game.background_image?.takeIf { it.isNotBlank() }?.let {
            assets.add(Asset(kind = "fanart", url = it, mime = "image/jpeg"))
        }
        val shotsBody = get(request(key, "games/$gameId/screenshots"))
        if (shotsBody != null) {
            runCatching {
                json.decodeFromString<RawgScreenshots>(shotsBody).results.forEach { shot ->
                    shot.image?.takeIf { it.isNotBlank() }?.let {
                        assets.add(Asset(kind = "screenshots", url = it, mime = "image/jpeg"))
                    }
                }
            }
        }

        val metadata = Metadata(
            name = game.name.orEmpty(),
            desc = game.description_raw.orEmpty(),
            rating = ((game.rating ?: 0f) / 5f).coerceIn(0f, 1f),
            releaseDate = formatDate(game.released),
            developer = game.developers.firstOrNull()?.name.orEmpty(),
            publisher = game.publishers.firstOrNull()?.name.orEmpty(),
            genre = game.genres.mapNotNull { it.name }.joinToString(", "),
            players = "",
        )

        return MatchResult(
            romId = "",
            gameName = game.name.orEmpty(),
            metadata = metadata,
            assets = assets.distinctBy { it.kind to it.url },
            source = id,
            confidence = 0.8f,
            matchedBy = "name",
        )
    }

    override suspend fun fetchAssets(system: String, gameId: String, kinds: List<String>): List<Asset> =
        emptyList()

    /** "2017-04-28" -> "20170428T000000" */
    private fun formatDate(date: String?): String {
        if (date.isNullOrBlank()) return ""
        val digits = date.replace("-", "")
        return if (digits.length >= 8) digits.take(8) + "T000000" else ""
    }

    @Serializable
    private data class RawgSearchResponse(val results: List<RawgSearchItem> = emptyList())

    @Serializable
    private data class RawgSearchItem(
        val id: Int? = null,
        val name: String? = null,
        val released: String? = null,
    )

    @Serializable
    private data class RawgGame(
        val id: Int? = null,
        val name: String? = null,
        val description_raw: String? = null,
        val rating: Float? = null,
        val released: String? = null,
        val background_image: String? = null,
        val genres: List<RawgNamed> = emptyList(),
        val developers: List<RawgNamed> = emptyList(),
        val publishers: List<RawgNamed> = emptyList(),
    )

    @Serializable
    private data class RawgNamed(val name: String? = null)

    @Serializable
    private data class RawgScreenshots(val results: List<RawgShot> = emptyList())

    @Serializable
    private data class RawgShot(val image: String? = null)
}
