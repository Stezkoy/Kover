package com.ark.kover.core.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System artwork for the library cards.
 *
 * NeoAssets publishes free system art packs (public endpoint, no credentials):
 * `/api/v1/packs` returns object keys relative to https://cdn.neoassets.dev/.
 */
@Singleton
class SystemArtRepository @Inject constructor(
    private val httpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    var art by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private var started = false

    suspend fun ensureLoaded() {
        if (started) return
        started = true
        // Show the last known map immediately, then refresh it in the background.
        val cached = withContext(Dispatchers.IO) { readCache() }
        if (cached.isNotEmpty()) art = cached
        val fetched = withContext(Dispatchers.IO) { fetch() }
        if (fetched.isNotEmpty()) {
            art = fetched
            withContext(Dispatchers.IO) { writeCache(fetched) }
        }
    }

    private fun cacheFile(): File = File(context.filesDir, "system-art-cache.json")

    private fun readCache(): Map<String, String> {
        val file = cacheFile()
        if (!file.isFile) return emptyMap()
        return runCatching {
            val cache = Json { ignoreUnknownKeys = true }
                .decodeFromString<SystemArtCache>(file.readText())
            if (cache.version == 1) cache.art else emptyMap()
        }.getOrElse { emptyMap() }
    }

    private fun writeCache(map: Map<String, String>) {
        runCatching {
            val cache = SystemArtCache(art = map, savedAt = java.lang.System.currentTimeMillis())
            cacheFile().writeText(
                Json.encodeToString(SystemArtCache.serializer(), cache),
            )
        }
    }

    fun urlFor(systemId: String): String? {
        val id = systemId.lowercase()
        val key = aliases[id] ?: id
        return art[key] ?: art[id]
    }

    private fun fetch(): Map<String, String> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val listBody = get("https://api.neoassets.dev/api/v1/packs") ?: return emptyMap()
            val packs = json.decodeFromString<PacksResponse>(listBody)
            val theme = packs.themes.firstOrNull { it.folder == "neostation" }
                ?: packs.themes.firstOrNull()
                ?: return emptyMap()
            val folder = theme.folder ?: return emptyMap()

            val detailBody = get("https://api.neoassets.dev/api/v1/packs/$folder") ?: return emptyMap()
            val detail = json.decodeFromString<PackDetail>(detailBody)
            val map = mutableMapOf<String, String>()
            detail.files.forEach { file ->
                val system = file.system_id?.lowercase()
                val url = file.url
                if (!system.isNullOrBlank() && !url.isNullOrBlank() && !map.containsKey(system)) {
                    map[system] = url
                }
            }
            android.util.Log.d("SystemArt", "loaded ${map.size} system backgrounds from $folder")
            map
        } catch (e: Exception) {
            android.util.Log.e("SystemArt", "fetch failed", e)
            emptyMap()
        }
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Kover-Android")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    companion object {
        private val aliases = mapOf(
            "psx" to "ps1",
            "megadrive" to "genesis",
            "md" to "genesis",
            "ngc" to "gc",
            "sfc" to "snes",
            "famicom" to "nes",
            "dreamcast" to "dc",
            "gamegear" to "gg",
            "nds" to "ds",
            "n3ds" to "3ds",
            "saturn" to "sat",
            "mastersystem" to "sms",
            "sega32x" to "32x",
            "segacd" to "scd",
            "pcengine" to "pce",
            "pcenginecd" to "pccd",
            "wonderswan" to "ws",
            "wonderswancolor" to "wsc",
            "virtualboy" to "vb",
            "atari2600" to "2600",
            "atari5200" to "5200",
            "atari7800" to "7800",
            "intellivision" to "intv",
            "colecovision" to "cv",
            "vectrex" to "vect",
            "amstradcpc" to "cpc",
            // ES-DE app/emulator folders share the Android pack artwork.
            "androidapps" to "android",
            "androidgames" to "android",
            "emulators" to "android",
        )
    }

    @Serializable
    private data class PacksResponse(val themes: List<PackTheme> = emptyList())

    @Serializable
    private data class PackTheme(
        val folder: String? = null,
        val backgrounds: List<String> = emptyList(),
    )

    @Serializable
    private data class PackDetail(val files: List<PackFile> = emptyList())

    @Serializable
    private data class PackFile(
        val kind: String? = null,
        val system_id: String? = null,
        val url: String? = null,
    )
}

/** Disk copy of the system → background URL map (works offline on the next launch). */
@Serializable
private data class SystemArtCache(
    val version: Int = 1,
    val art: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L,
)
