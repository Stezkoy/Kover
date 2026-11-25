package com.ark.kover.scraper

import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.GameCandidate
import com.ark.kover.core.model.MatchResult
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.scraper.Scraper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * libretro-thumbnails: no API key at all.
 *
 * Each system is a GitHub repository with Named_Boxarts / Named_Snaps / Named_Titles.
 * The file list is loaded once per system through the GitHub trees API (cached in memory),
 * then images are served straight from raw.githubusercontent.com.
 */
class LibretroThumbnailsScraper @Inject constructor(
    private val httpClient: OkHttpClient,
) : Scraper {

    override val id = "libretro"
    override val displayName = "libretro-thumbnails"

    private val json = Json { ignoreUnknownKeys = true }

    private val repos = mapOf(
        "nes" to "Nintendo_-_Nintendo_Entertainment_System",
        "famicom" to "Nintendo_-_Nintendo_Entertainment_System",
        "snes" to "Nintendo_-_Super_Nintendo_Entertainment_System",
        "sfc" to "Nintendo_-_Super_Nintendo_Entertainment_System",
        "n64" to "Nintendo_-_Nintendo_64",
        "gc" to "Nintendo_-_GameCube",
        "ngc" to "Nintendo_-_GameCube",
        "wii" to "Nintendo_-_Wii",
        "wiiu" to "Nintendo_-_Wii_U",
        "switch" to "Nintendo_-_Switch",
        "gb" to "Nintendo_-_Game_Boy",
        "gbc" to "Nintendo_-_Game_Boy_Color",
        "gba" to "Nintendo_-_Game_Boy_Advance",
        "nds" to "Nintendo_-_Nintendo_DS",
        "3ds" to "Nintendo_-_Nintendo_3DS",
        "ps1" to "Sony_-_PlayStation",
        "psx" to "Sony_-_PlayStation",
        "ps2" to "Sony_-_PlayStation_2",
        "psp" to "Sony_-_PlayStation_Portable",
        "genesis" to "Sega_-_Mega_Drive_-_Genesis",
        "megadrive" to "Sega_-_Mega_Drive_-_Genesis",
        "md" to "Sega_-_Mega_Drive_-_Genesis",
        "saturn" to "Sega_-_Saturn",
        "dc" to "Sega_-_Dreamcast",
        "gg" to "Sega_-_Game_Gear",
        "sms" to "Sega_-_Master_System_-_Mark_III",
        "pce" to "NEC_-_PC_Engine_-_TurboGrafx_16",
        "neogeo" to "SNK_-_Neo_Geo",
        "arcade" to "FBNeo_-_Arcade",
        "c64" to "Commodore_-_64",
        "atari2600" to "Atari_-_2600",
    )

    private val categories = mapOf(
        "Named_Boxarts" to "covers",
        "Named_Snaps" to "screenshots",
        "Named_Titles" to "titlescreens",
        "Named_Logos" to "marquees",
    )

    private data class Entry(val category: String, val name: String, val path: String)

    private val cache = mutableMapOf<String, List<Entry>>()
    private val pending = mutableMapOf<String, MatchResult>()

    override fun isConfigured(): Boolean = true

    override fun supportsSystem(system: String): Boolean = repos.containsKey(system.lowercase())

    override suspend fun search(system: String, name: String, md5: String?): MatchResult? {
        val candidate = searchCandidates(system, name, md5).firstOrNull() ?: return null
        return fetchFor(system, candidate.id)
    }

    override suspend fun searchCandidates(
        system: String,
        name: String,
        md5: String?,
    ): List<GameCandidate> {
        val entries = loadEntries(system) ?: return emptyList()
        val match = bestMatch(entries.map { it.name }.distinct(), name) ?: return emptyList()
        val assets = collectAssets(system, entries, name)
        if (assets.isEmpty()) return emptyList()
        pending[match] = MatchResult(
            romId = "",
            gameName = match,
            metadata = Metadata(name = match),
            assets = assets,
            source = id,
            confidence = 0.75f,
            matchedBy = "name",
        )
        return listOf(
            GameCandidate(
                id = match,
                name = match,
                source = id,
                system = system,
                matchedBy = "name",
                confidence = 0.75f,
            ),
        )
    }

    /**
     * Picks the best file in every category independently: libretro sometimes names
     * boxarts and screenshots differently ("GoldenEye 007" vs "007 - GoldenEye").
     */
    private fun collectAssets(system: String, entries: List<Entry>, query: String): List<Asset> {
        return categories.mapNotNull { (category, kind) ->
            val categoryEntries = entries.filter { it.category == category }
            val best = bestMatch(categoryEntries.map { it.name }.distinct(), query)
                ?: return@mapNotNull null
            val entry = categoryEntries.firstOrNull { it.name == best } ?: return@mapNotNull null
            Asset(kind = kind, url = rawUrl(system, entry.path), mime = "image/png")
        }
    }

    override suspend fun fetchFor(system: String, gameId: String): MatchResult? {
        pending[gameId]?.let { return it }
        val entries = loadEntries(system) ?: return null
        val assets = entries
            .filter { it.name == gameId }
            .mapNotNull { entry ->
                val kind = categories[entry.category] ?: return@mapNotNull null
                Asset(kind = kind, url = rawUrl(system, entry.path), mime = "image/png")
            }
            .distinctBy { it.kind to it.url }
        if (assets.isEmpty()) return null
        val result = MatchResult(
            romId = "",
            gameName = gameId,
            metadata = Metadata(name = gameId),
            assets = assets,
            source = id,
            confidence = 0.75f,
            matchedBy = "name",
        )
        pending[gameId] = result
        return result
    }

    override suspend fun fetchAssets(system: String, gameId: String, kinds: List<String>): List<Asset> =
        pending[gameId]?.assets.orEmpty()

    private fun loadEntries(system: String): List<Entry>? {
        val id = system.lowercase()
        cache[id]?.let { return it }
        val repo = repos[id] ?: return null
        val url = "https://api.github.com/repos/libretro-thumbnails/$repo/git/trees/master?recursive=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Kover-Android")
            .header("Accept", "application/vnd.github+json")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.e("Libretro", "tree HTTP ${response.code} for $repo")
                    return null
                }
                val body = response.body?.string() ?: return null
                val tree = json.decodeFromString<TreeResponse>(body)
                val entries = tree.tree.mapNotNull { item ->
                    val path = item.path ?: return@mapNotNull null
                    val category = path.substringBefore('/', "")
                    if (category !in categories) return@mapNotNull null
                    val file = path.substringAfter('/', "")
                    Entry(
                        category = category,
                        name = file.substringBeforeLast('.'),
                        path = path,
                    )
                }
                cache[id] = entries
                android.util.Log.d("Libretro", "$repo: ${entries.size} entries")
                entries
            }
        } catch (e: Exception) {
            android.util.Log.e("Libretro", "tree failed for $repo", e)
            null
        }
    }

    /** Picks the closest file name for the ROM name. */
    private fun bestMatch(names: List<String>, query: String): String? {
        val target = normalize(query)
        if (target.isBlank()) return null
        val scored = names.mapNotNull { name ->
            val normalized = normalize(name)
            val score = when {
                normalized == target -> 100
                normalized.startsWith(target) -> 80
                normalized.contains(target) -> 60
                target.contains(normalized) && normalized.length > 3 -> 40
                else -> 0
            } + regionBonus(name)
            if (score > 0) name to score else null
        }
        return scored.maxByOrNull { it.second }?.first
    }

    private fun regionBonus(name: String): Int = when {
        name.contains("(USA)", ignoreCase = true) -> 5
        name.contains("(Europe)", ignoreCase = true) -> 4
        name.contains("(World)", ignoreCase = true) -> 4
        name.contains("(Japan)", ignoreCase = true) -> 2
        else -> 0
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("\\s*\\[[^]]*]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun rawUrl(system: String, path: String): String {
        val repo = repos[system.lowercase()] ?: return ""
        val encoded = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/$encoded"
    }

    @Serializable
    private data class TreeResponse(val tree: List<TreeItem> = emptyList())

    @Serializable
    private data class TreeItem(val path: String? = null, val type: String? = null)
}
