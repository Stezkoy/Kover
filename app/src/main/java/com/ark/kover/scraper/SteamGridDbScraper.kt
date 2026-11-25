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
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

class SteamGridDbScraper(
    private val settings: com.ark.kover.core.settings.SettingsRepository,
    private val httpClient: okhttp3.OkHttpClient,
) : Scraper {

    override val id = "steamgriddb"
    override val displayName = "SteamGridDB"

    private val json = Json { ignoreUnknownKeys = true;  }

    private val BASE = "https://www.steamgriddb.com/api/v2"

    override fun isConfigured(): Boolean = true

    override suspend fun search(system: String, name: String, md5: String?): MatchResult? {
        val cands = searchCandidates(system, name, md5)
        return cands.firstOrNull()?.let { fetchFor(system, it.id) }
    }

    override suspend fun searchCandidates(system: String, name: String, md5: String?): List<GameCandidate> {
        val s = settings.settings.first()
        if (s.steamGridDbToken.isBlank()) {
            android.util.Log.e("SteamGridDb", "Token not configured!")
            return emptyList()
        }
        val query = java.net.URLEncoder.encode(name, "UTF-8")
        val searchUrl = "$BASE/search/autocomplete/$query"
        android.util.Log.d("SteamGridDb", "searchCandidates: $searchUrl")
        return try {
            val resp = httpClient.newCall(Request.Builder().url(searchUrl)
                .header("Authorization", "Bearer " + s.steamGridDbToken).build()).execute()
            android.util.Log.d("SteamGridDb", "search HTTP ${resp.code}")
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            json.decodeFromString<SgdbSearchResponse>(body).data
                ?.map { g ->
                    GameCandidate(
                        id = g.id.toString(),
                        name = g.name ?: "",
                        source = id,
                        system = system,
                        matchedBy = "name",
                        confidence = if (g.verified == true) 0.95f else 0.7f,
                    )
                }
                ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("SteamGridDb", "search FAILED: ${e.message}")
            emptyList()
        }
    }

    override suspend fun fetchFor(system: String, gameId: String): MatchResult? {
        val id = gameId.toIntOrNull() ?: return null
        val s = settings.settings.first()
        val assets = buildList {
            // No limits: the editor lets the user pick any of the available artworks.
            addAll(fetchAll(s, "grids", id, "covers", "image/jpeg"))
            addAll(fetchAll(s, "heroes", id, "fanart", "image/jpeg"))
            addAll(fetchAll(s, "logos", id, "marquees", "image/png"))
            addAll(fetchAll(s, "icons", id, "icons", "image/png"))
        }
        android.util.Log.d("SteamGridDb", "fetchFor: gameId=$id, assets=${assets.size}")
        return MatchResult(
            romId = "",
            gameName = "",
            metadata = Metadata(),
            assets = assets,
            source = this.id,
            confidence = 0.7f,
            matchedBy = "name",
        )
    }

    private suspend fun fetchAll(
        s: com.ark.kover.core.settings.AppSettings,
        endpoint: String,
        gameId: Int,
        kind: String,
        defaultMime: String,
    ): List<Asset> {
        val result = mutableListOf<Asset>()
        var page = 0
        while (page < MAX_PAGES) {
            val parsed = fetchPage(s, endpoint, gameId, kind, defaultMime, page) ?: break
            result += parsed.first
            if (result.size >= parsed.second || parsed.first.size < PAGE_SIZE) break
            page++
        }
        return result
    }

    /** Returns page items and the total number reported by the API. */
    private suspend fun fetchPage(
        s: com.ark.kover.core.settings.AppSettings,
        endpoint: String,
        gameId: Int,
        kind: String,
        defaultMime: String,
        page: Int,
    ): Pair<List<Asset>, Int>? {
        return try {
            val resp = httpClient.newCall(
                Request.Builder()
                    .url("$BASE/$endpoint/game/$gameId?page=$page")
                    .header("Authorization", "Bearer " + s.steamGridDbToken)
                    .build(),
            ).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val dto = json.decodeFromString<SgdbGridsResponse>(body)
            val items = dto.data.orEmpty().mapNotNull { grid ->
                grid.url?.let { Asset(kind = kind, url = it, mime = grid.mime ?: defaultMime) }
            }
            val total = dto.total ?: (page * PAGE_SIZE + items.size)
            items to total
        } catch (e: Exception) {
            android.util.Log.e("SteamGridDb", "$endpoint page $page FAILED: ${e.message}")
            null
        }
    }

    override suspend fun fetchAssets(system: String, gameId: String, kinds: List<String>): List<Asset> = emptyList()

    // --- DTOs ---
    @Serializable data class SgdbSearchResponse(val success: Boolean? = null, val data: List<SgdbSearchResult>? = null)
    @Serializable data class SgdbSearchResult(
        val id: Int? = null, val name: String? = null,
        val verified: Boolean? = null, val types: List<String>? = null,
        val release_date: Long? = null,
    )
    @Serializable data class SgdbGridsResponse(
        val success: Boolean? = null,
        val data: List<SgdbGrid>? = null,
        val total: Int? = null,
        val page: Int? = null,
    )
    @Serializable data class SgdbGrid(
        val id: Int? = null, val url: String? = null, val thumb: String? = null,
        val mime: String? = null, val style: String? = null,
        val width: Int? = null, val height: Int? = null,
    )

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 20
    }
}
