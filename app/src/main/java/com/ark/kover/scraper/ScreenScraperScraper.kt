package com.ark.kover.scraper

import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.GameCandidate
import com.ark.kover.core.model.MatchResult
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.scraper.Scraper
import com.ark.kover.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * ScreenScraper.fr scraper.
 *
 * Requires a developer id/secret (from screenscraper.fr) plus an optional user account.
 * Everything is configured in Settings → Scrapers → ScreenScraper.
 * Docs: https://www.screenscraper.fr/webapi2.php
 */
class ScreenScraperScraper @Inject constructor(
    private val settings: SettingsRepository,
    private val httpClient: OkHttpClient,
) : Scraper {

    override val id = "screenscraper"
    override val displayName = "ScreenScraper"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val base = "https://api.screenscraper.fr/api2"

    /** ES-DE folder -> part of the ScreenScraper system name. */
    private val systemAliases = mapOf(
        "gc" to "gamecube",
        "switch" to "switch",
        "wii" to "wii",
        "wiiu" to "wii u",
        "nes" to "nes",
        "snes" to "super nintendo",
        "n64" to "nintendo 64",
        "gb" to "game boy",
        "gbc" to "game boy color",
        "gba" to "game boy advance",
        "nds" to "nintendo ds",
        "3ds" to "nintendo 3ds",
        "ps1" to "playstation",
        "psx" to "playstation",
        "ps2" to "playstation 2",
        "psp" to "playstation portable",
        "genesis" to "megadrive",
        "megadrive" to "megadrive",
        "saturn" to "saturn",
        "dc" to "dreamcast",
        "gg" to "game gear",
        "sms" to "master system",
        "pce" to "pc engine",
        "neogeo" to "neo-geo",
        "ngp" to "neo geo pocket",
        "arcade" to "arcade",
        "mame" to "arcade",
        "atari2600" to "atari 2600",
        "c64" to "commodore 64",
    )

    /** ScreenScraper media type -> ES-DE folder. */
    private val mediaKinds = mapOf(
        "box-2d" to "covers",
        "box-2d-back" to "backcovers",
        "ss" to "screenshots",
        "sstitle" to "titlescreens",
        "wheel" to "marquees",
        "wheel-hd" to "marquees",
        "fanart" to "fanart",
        "video" to "videos",
        "manuel" to "manuals",
        "support-2d" to "physicalmedia",
        "box-3d" to "3dboxes",
    )

    private var systems: List<Pair<Int, String>>? = null
    private val pending = mutableMapOf<String, MatchResult>()

    override fun isConfigured(): Boolean = true

    override fun supportsSystem(system: String): Boolean =
        systemAliases.containsKey(system.lowercase())

    private suspend fun credentials(): Creds? {
        val s = settings.settings.first()
        // App-level developer keys ship in BuildConfig; settings are a manual override.
        val devId = com.ark.kover.BuildConfig.SCREENSCRAPER_DEV_ID.ifBlank { s.ssDevId }.trim()
        val devPassword = com.ark.kover.BuildConfig.SCREENSCRAPER_DEV_PASSWORD
            .ifBlank { s.ssDevPassword }.trim()
        if (devId.isBlank() || devPassword.isBlank()) return null
        return Creds(
            devId = devId,
            devPassword = devPassword,
            user = s.ssUser.trim(),
            password = s.ssPassword.trim(),
        )
    }

    private fun url(
        creds: Creds,
        endpoint: String,
        params: Map<String, String>,
    ) = "$base/$endpoint".toHttpUrl().newBuilder().apply {
        addQueryParameter("devid", creds.devId)
        addQueryParameter("devpassword", creds.devPassword)
        addQueryParameter("softname", "Kover")
        addQueryParameter("output", "json")
        if (creds.user.isNotBlank()) {
            addQueryParameter("ssid", creds.user)
            addQueryParameter("sspassword", creds.password)
        }
        params.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()

    private fun request(url: okhttp3.HttpUrl): JsonElement? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Kover-Android")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.e("ScreenScraper", "HTTP ${response.code} for ${response.request.url}")
                    return null
                }
                val body = response.body?.string() ?: return null
                json.parseToJsonElement(body)
            }
        } catch (e: Exception) {
            android.util.Log.e("ScreenScraper", "request failed", e)
            null
        }
    }

    private suspend fun resolveSystemId(system: String): Int? {
        val alias = systemAliases[system.lowercase()] ?: return null
        val cached = systems
        if (cached != null) {
            return cached.firstOrNull { it.second.lowercase().contains(alias) }?.first
        }
        val creds = credentials() ?: return null
        val root = request(url(creds, "systemesListe.php", emptyMap())) ?: return null
        val list = root.asObject()?.get("response")?.asObject()?.get("systemes")?.asArray() ?: return null
        val parsed = list.mapNotNull { element ->
            val obj = element.asObject() ?: return@mapNotNull null
            val id = obj["id"]?.asString()?.toIntOrNull() ?: return@mapNotNull null
            val names = obj["noms"]?.asArray().orEmpty()
                .mapNotNull { it.asObject()?.get("text")?.asString() }
            id to names.joinToString(" ")
        }
        systems = parsed
        return parsed.firstOrNull { it.second.lowercase().contains(alias) }?.first
    }

    override suspend fun search(system: String, name: String, md5: String?): MatchResult? {
        val candidate = searchCandidates(system, name, md5).firstOrNull() ?: return null
        return fetchFor(system, candidate.id)
    }

    override suspend fun searchCandidates(system: String, name: String, md5: String?): List<GameCandidate> {
        val creds = credentials() ?: return emptyList()
        val systemId = resolveSystemId(system) ?: return emptyList()
        val params = buildMap {
            put("recherche", name)
            put("systemeid", systemId.toString())
        }
        val root = request(url(creds, "jeuRecherche.php", params)) ?: return emptyList()
        val games = root.asObject()?.get("response")?.asObject()?.get("jeux")?.asArray() ?: return emptyList()
        return games.mapNotNull { element ->
            val obj = element.asObject() ?: return@mapNotNull null
            val id = obj["id"]?.asString() ?: return@mapNotNull null
            val title = obj["noms"]?.asArray().orEmpty()
                .mapNotNull { it.asObject()?.get("text")?.asString() }
                .firstOrNull()
                .orEmpty()
            if (title.isBlank()) return@mapNotNull null
            GameCandidate(
                id = id,
                name = title,
                source = this.id,
                system = system,
                matchedBy = "name",
                confidence = 0.8f,
            )
        }
    }

    override suspend fun fetchFor(system: String, gameId: String): MatchResult? {
        val creds = credentials() ?: return null
        val root = request(url(creds, "jeuInfos.php", mapOf("gameid" to gameId))) ?: return null
        val game = root.asObject()?.get("response")?.asObject()?.get("jeu")?.asObject() ?: return null

        val names = game["noms"]?.asArray().orEmpty()
        val title = names.firstOrNull { it.asObject()?.get("region")?.asString() == "us" }
            ?.asObject()?.get("text")?.asString()
            ?: names.firstOrNull()?.asObject()?.get("text")?.asString()
            ?: ""

        val synopsis = game["synopsis"]?.asArray().orEmpty()
        val descEn = synopsis.firstOrNull { it.asObject()?.get("langue")?.asString() == "en" }
            ?.asObject()?.get("text")?.asString().orEmpty()
        val descRu = synopsis.firstOrNull { it.asObject()?.get("langue")?.asString() == "ru" }
            ?.asObject()?.get("text")?.asString().orEmpty()
        val desc = descEn.ifBlank {
            synopsis.firstOrNull()?.asObject()?.get("text")?.asString().orEmpty()
        }

        val date = game["dates"]?.asArray().orEmpty()
            .firstOrNull { it.asObject()?.get("region")?.asString() == "eu" }
            ?.asObject()?.get("text")?.asString().orEmpty()
        val releaseDate = date.replace("-", "").let { if (it.length >= 8) it.take(8) + "T000000" else "" }

        val note = game["note"]?.asString()?.toFloatOrNull() ?: 0f

        val metadata = Metadata(
            name = title,
            desc = desc,
            descByLang = buildMap {
                if (descEn.isNotBlank()) put("en", descEn)
                if (descRu.isNotBlank()) put("ru", descRu)
            },
            rating = (note / 20f).coerceIn(0f, 1f),
            releaseDate = releaseDate,
            developer = game["developpeur"]?.asObject()?.get("text")?.asString().orEmpty(),
            publisher = game["editeur"]?.asObject()?.get("text")?.asString().orEmpty(),
            genre = game["genre"]?.asObject()?.get("text")?.asString().orEmpty(),
            players = game["joueurs"]?.asObject()?.get("text")?.asString().orEmpty(),
        )

        val assets = game["medias"]?.asArray().orEmpty().mapNotNull { element ->
            val obj = element.asObject() ?: return@mapNotNull null
            val type = obj["type"]?.asString()?.lowercase() ?: return@mapNotNull null
            val kind = mediaKinds[type] ?: return@mapNotNull null
            val mediaUrl = obj["url"]?.asString() ?: return@mapNotNull null
            Asset(
                kind = kind,
                url = mediaUrl,
                mime = obj["format"]?.asString().orEmpty(),
            )
        }.distinctBy { it.kind to it.url }

        val result = MatchResult(
            romId = "",
            gameName = title,
            metadata = metadata,
            assets = assets,
            source = id,
            confidence = 0.9f,
            matchedBy = "name",
        )
        pending[gameId] = result
        return result
    }

    override suspend fun fetchAssets(system: String, gameId: String, kinds: List<String>): List<Asset> =
        pending[gameId]?.assets.orEmpty()

    private data class Creds(
        val devId: String,
        val devPassword: String,
        val user: String,
        val password: String,
    )

    // --- JSON helpers ---

    private fun JsonElement?.asObject(): JsonObject? = this?.let { runCatching { it.jsonObject }.getOrNull() }
    private fun JsonObject.obj(key: String): JsonObject? = this[key]?.asObject()
    private fun JsonElement?.asArray(): JsonArray? = this?.let { runCatching { it.jsonArray }.getOrNull() }
    private fun JsonElement?.asString(): String? = this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}
