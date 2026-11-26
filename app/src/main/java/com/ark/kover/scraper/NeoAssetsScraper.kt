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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * NeoAssets: retro + modern console metadata with localized descriptions.
 *
 * Every request needs X-Client-Id and X-Client-Secret. The personal API key
 * (Authorization: Bearer ns_live_...) is optional: sending a malformed key
 * results in HTTP 401, so it is only attached when it looks valid.
 */
class NeoAssetsScraper @Inject constructor(
    private val settings: SettingsRepository,
    private val httpClient: OkHttpClient,
) : Scraper {

    override val id = "neoassets"
    override val displayName = "NeoAssets"

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.neoassets.dev/api/v1"

    /** ES-DE folder name -> NeoAssets system id. */
    private val systemMap = mapOf(
        "gc" to "gc",
        "ngc" to "gc",
        "switch" to "switch",
        "wii" to "wii",
        "wiiu" to null,
        "nes" to "nes",
        "famicom" to "nes",
        "snes" to "snes",
        "sfc" to "snes",
        "n64" to "n64",
        "gb" to "gb",
        "gbc" to "gbc",
        "gba" to "gba",
        "nds" to "ds",
        "ds" to "ds",
        "3ds" to "3ds",
        "ps1" to "ps1",
        "psx" to "ps1",
        "ps2" to "ps2",
        "psp" to "psp",
        "vita" to "vita",
        "genesis" to "genesis",
        "megadrive" to "genesis",
        "md" to "genesis",
        "saturn" to "saturn",
        "dreamcast" to "dc",
        "dc" to "dc",
        "gg" to "gg",
        "gamegear" to "gg",
        "sms" to "sms",
        "mastersystem" to "sms",
        "pce" to "tg16",
        "tg16" to "tg16",
        "ngp" to "ngp",
        "ngpc" to "ngpc",
        "ws" to "ws",
        "wonderswan" to "ws",
        "wsc" to "wsc",
        "vb" to "vb",
        "virtualboy" to "vb",
        "arcade" to "arc",
        "mame" to "arc",
        "fbneo" to "arc",
        "neogeo" to "neogeo",
        "scummvm" to "scummvm",
        "atari2600" to "2600",
        "atari7800" to "7800",
        "c64" to "c64",
        "amiga" to null,
    )

    /** NeoAssets media kind -> ES-DE folder. */
    private val kindMap = mapOf(
        "cover" to "covers",
        "boxfront" to "covers",
        "boxback" to "backcovers",
        "fanart" to "fanart",
        "screenshot" to "screenshots",
        "logo" to "marquees",
        "marquee" to "marquees",
        "video" to "videos",
        "manual" to "manuals",
        "titlescreen" to "titlescreens",
        "miximage" to "miximages",
    )

    /** Results from the last searches, so fetchFor() does not spend quota twice. */
    private val recent = mutableMapOf<String, MatchResult>()

    override fun isConfigured(): Boolean = true

    override fun supportsSystem(system: String): Boolean = systemMap.containsKey(system.lowercase())

    private fun systemId(system: String): String? =
        systemMap[system.lowercase()]

    override suspend fun search(system: String, name: String, md5: String?): MatchResult? {
        val candidateSystem = systemId(system) ?: run {
            android.util.Log.d("NeoAssets", "system '$system' is not in the catalog, skipping")
            return null
        }
        val s = settings.settings.first()
        val clientId = com.ark.kover.BuildConfig.NEOASSETS_CLIENT_ID
        val clientSecret = com.ark.kover.BuildConfig.NEOASSETS_CLIENT_SECRET
        if (clientId.isBlank() || clientSecret.isBlank()) {
            android.util.Log.e("NeoAssets", "app credentials are missing (BuildConfig)")
            return null
        }

        val params = buildList {
            add("system_id=$candidateSystem")
            if (!name.isBlank()) add("name=${java.net.URLEncoder.encode(name, "UTF-8")}")
            if (!md5.isNullOrBlank()) add("md5=$md5")
            add("media=cover,boxfront,boxback,fanart,screenshot,logo,video")
            add("softname=Kover")
        }.joinToString("&")
        val url = "$baseUrl/scrape/games?$params"

        val request = Request.Builder()
            .url(url)
            .header("X-Client-Id", clientId)
            .header("X-Client-Secret", clientSecret)
            .apply {
                val key = s.neoAssetsBearerKey.trim()
                if (key.startsWith("ns_")) header("Authorization", "Bearer $key")
            }
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.e("NeoAssets", "HTTP ${response.code} for $url")
                    return null
                }
                val body = response.body?.string() ?: return null
                val dto = json.decodeFromString<NeoAssetsResponse>(body)
                val game = dto.game ?: return null
                val result = mapToResult(game, system, dto.matched_by)
                game.id?.let { recent[it] = result }
                result
            }
        } catch (e: Exception) {
            android.util.Log.e("NeoAssets", "search failed", e)
            null
        }
    }

    override suspend fun searchCandidates(system: String, name: String, md5: String?): List<GameCandidate> {
        val result = search(system, name, md5) ?: return emptyList()
        val gameId = recent.entries.firstOrNull { it.value === result }?.key
            ?: recent.entries.firstOrNull { it.value.gameName == result.gameName }?.key
            ?: result.gameName
        return listOf(
            GameCandidate(
                id = gameId,
                name = result.gameName,
                source = id,
                system = system,
                matchedBy = result.matchedBy,
                releaseDate = result.metadata.releaseDate,
                confidence = result.confidence,
            ),
        )
    }

    override suspend fun fetchFor(system: String, gameId: String): MatchResult? = recent[gameId]

    /**
     * NeoAssets returns metadata and media in a single call, so this is a no-op
     * kept for interface completeness.
     */
    override suspend fun fetchAssets(system: String, gameId: String, kinds: List<String>): List<Asset> =
        recent[gameId]?.assets.orEmpty()

    data class AccountInfo(
        val subject: String?,
        val used: Int,
        val limit: Int?,
        val remaining: Int?,
        val unlimited: Boolean,
    )

    /** GET /scrape/account — free, used by the "Check connection" button. */
    suspend fun accountInfo(bearerOverride: String? = null): AccountInfo? {
        val s = settings.settings.first()
        val clientId = com.ark.kover.BuildConfig.NEOASSETS_CLIENT_ID
        val clientSecret = com.ark.kover.BuildConfig.NEOASSETS_CLIENT_SECRET
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        val request = Request.Builder()
            .url("$baseUrl/scrape/account")
            .header("X-Client-Id", clientId)
            .header("X-Client-Secret", clientSecret)
            .apply {
                // An explicitly blank override means "test as guest"; null falls back to the saved key.
                val key = bearerOverride?.trim()
                    ?: s.neoAssetsBearerKey.trim().takeIf { it.startsWith("ns_") }
                if (!key.isNullOrBlank() && key.startsWith("ns_")) {
                    header("Authorization", "Bearer $key")
                }
            }
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = json.parseToJsonElement(body).jsonObject
                val quota = root["quota"]?.jsonObject
                AccountInfo(
                    subject = root["subject"]?.jsonPrimitive?.contentOrNull,
                    used = quota?.get("used")?.jsonPrimitive?.intOrNull ?: 0,
                    limit = quota?.get("daily_limit")?.jsonPrimitive?.intOrNull,
                    remaining = quota?.get("remaining")?.jsonPrimitive?.intOrNull,
                    unlimited = quota?.get("unlimited")?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("NeoAssets", "account info failed", e)
            null
        }
    }

    private fun mapToResult(dto: GameDto, system: String, matchedBy: String?): MatchResult {
        val year = dto.release_year ?: 0
        val month = dto.release_month ?: 0
        val releaseDate = if (year > 0) {
            "%04d%02d01T000000".format(year, if (month in 1..12) month else 1)
        } else ""

        val localized = buildMap {
            dto.description_en?.takeIf { it.isNotBlank() }?.let { put("en", it) }
            dto.description_ru?.takeIf { it.isNotBlank() }?.let { put("ru", it) }
            dto.description_de?.takeIf { it.isNotBlank() }?.let { put("de", it) }
            dto.description_es?.takeIf { it.isNotBlank() }?.let { put("es", it) }
            dto.description_fr?.takeIf { it.isNotBlank() }?.let { put("fr", it) }
            dto.description_it?.takeIf { it.isNotBlank() }?.let { put("it", it) }
            dto.description_pt?.takeIf { it.isNotBlank() }?.let { put("pt", it) }
            dto.description_ja?.takeIf { it.isNotBlank() }?.let { put("ja", it) }
            dto.description_ko?.takeIf { it.isNotBlank() }?.let { put("ko", it) }
            dto.description_zh?.takeIf { it.isNotBlank() }?.let { put("zh", it) }
        }

        val metadata = Metadata(
            name = dto.name.orEmpty(),
            desc = dto.description_en.orEmpty().ifBlank { dto.description_ru.orEmpty() },
            descByLang = localized,
            rating = (dto.rating ?: 0).coerceIn(0, 10) / 10f,
            releaseDate = releaseDate,
            developer = dto.developer.orEmpty(),
            publisher = dto.publisher.orEmpty(),
            genre = dto.genre.orEmpty(),
            players = "",
        )

        val assets = dto.media.orEmpty().mapNotNull { media ->
            val url = media.url ?: return@mapNotNull null
            val targetKind = kindMap[media.kind?.lowercase()] ?: return@mapNotNull null
            Asset(
                kind = targetKind,
                url = url,
                mime = media.mime.orEmpty(),
                size = media.size ?: 0L,
            )
        }

        return MatchResult(
            romId = "",
            gameName = dto.name.orEmpty(),
            metadata = metadata,
            assets = assets,
            source = id,
            confidence = if (matchedBy == "hash") 1f else 0.8f,
            matchedBy = matchedBy ?: "name",
        )
    }

    // ------------------------------------------------------------------ DTOs

    @Serializable
    data class NeoAssetsResponse(
        val system_id: String? = null,
        val matched_by: String? = null,
        val game: GameDto? = null,
    )

    @Serializable
    data class GameDto(
        val id: String? = null,
        val name: String? = null,
        val region: String? = null,
        val description_en: String? = null,
        val description_ru: String? = null,
        val description_es: String? = null,
        val description_de: String? = null,
        val description_fr: String? = null,
        val description_it: String? = null,
        val description_pt: String? = null,
        val description_ja: String? = null,
        val description_ko: String? = null,
        val description_zh: String? = null,
        val release_year: Int? = null,
        val release_month: Int? = null,
        val publisher: String? = null,
        val developer: String? = null,
        val genre: String? = null,
        val rating: Int? = null,
        val type: String? = null,
        val rom_name: String? = null,
        val system_id: String? = null,
        val system_name: String? = null,
        val media: List<MediaDto>? = null,
    )

    @Serializable
    data class MediaDto(
        val kind: String? = null,
        val url: String? = null,
        val mime: String? = null,
        val size: Long? = null,
    )
}
