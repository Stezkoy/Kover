package com.ark.kover.core.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ark.kover.core.launcher.LauncherAdapter
import com.ark.kover.core.model.Rom
import com.ark.kover.core.model.System
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the scanned library plus the ROM currently being edited.
 *
 * Scanning a big ROM tree is expensive, so the result is kept here for the whole
 * session, saved to disk after a scan and restored on the next launch.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val launcher: LauncherAdapter,
    private val settings: com.ark.kover.core.settings.SettingsRepository,
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    var isScanning by mutableStateOf(false)
        private set
    var lastScanFailed by mutableStateOf(false)
        private set
    var lastScanAt by mutableStateOf(0L)
        private set
    var systems by mutableStateOf<List<System>>(emptyList())
        private set
    var romsBySystem by mutableStateOf<Map<String, List<Rom>>>(emptyMap())
        private set

    /** ROM opened in the editor, survives navigation between editor sub-pages. */
    var selectedRom by mutableStateOf<Rom?>(null)

    /** rom.relativePath -> cover path (null = checked, no cover). */
    private val coverCache = mutableStateMapOf<String, String>()

    val hasData: Boolean get() = systems.isNotEmpty()

    fun totalRoms(): Int = romsBySystem.values.sumOf { it.size }

    suspend fun scan(resolver: ContentResolver, romDirUri: Uri): Boolean {
        isScanning = true
        lastScanFailed = false
        val launcherType = settings.settings.first().launcherType
        return try {
            val map = withContext(Dispatchers.IO) {
                launcher.scanRomDirectory(resolver, romDirUri)
            }
            romsBySystem = map
            launcher.indexRoms(map)
            systems = map.entries
                .sortedBy { it.key }
                .map { System(id = it.key, name = systemDisplayName(it.key), romCount = it.value.size) }
            coverCache.clear()
            lastScanAt = java.lang.System.currentTimeMillis()
            saveCache(romDirUri.toString(), launcherType, map)
            hasData
        } catch (e: Exception) {
            android.util.Log.e("LibraryRepository", "scan failed", e)
            lastScanFailed = true
            false
        } finally {
            isScanning = false
        }
    }

    /**
     * Restores the previous scan from disk (same ROM folder). Returns true when a
     * cache was found, so the library can be shown before/without a new scan.
     */
    suspend fun loadCache(romDirUri: String, launcherType: String): Boolean = withContext(Dispatchers.IO) {
        if (romDirUri.isBlank() || hasData) return@withContext false
        val file = cacheFile()
        if (!file.isFile) return@withContext false
        val cache = runCatching {
            json.decodeFromString<LibraryCache>(file.readText())
        }.getOrNull() ?: return@withContext false
        if (cache.version != 4 || cache.romDirUri != romDirUri ||
            cache.launcherType != launcherType
        ) {
            return@withContext false
        }
        romsBySystem = cache.roms
        launcher.indexRoms(cache.roms)
        systems = cache.roms.entries
            .sortedBy { it.key }
            .map { System(id = it.key, name = systemDisplayName(it.key), romCount = it.value.size) }
        lastScanAt = cache.scannedAt
        android.util.Log.d("LibraryRepository", "cache loaded: ${romsBySystem.size} systems")
        true
    }

    private suspend fun saveCache(
        romDirUri: String,
        launcherType: String,
        roms: Map<String, List<Rom>>,
    ) = withContext(Dispatchers.IO) {
            runCatching {
                val cache = LibraryCache(
                    version = 4,
                    romDirUri = romDirUri,
                    launcherType = launcherType,
                    scannedAt = java.lang.System.currentTimeMillis(),
                    roms = roms,
                )
                cacheFile().writeText(json.encodeToString(LibraryCache.serializer(), cache))
            }
        }

    private fun cacheFile(): File = File(context.filesDir, "library-cache.json")

    fun roms(system: String): List<Rom> =
        romsBySystem[system].orEmpty().sortedBy { it.name.lowercase() }

    fun systemName(id: String): String =
        systems.firstOrNull { it.id == id }?.name ?: systemDisplayName(id)

    fun cachedCover(rom: Rom): String? = coverCache[rom.relativePath]?.ifBlank { null }

    fun isCoverKnown(rom: Rom): Boolean = coverCache.containsKey(rom.relativePath)

    fun putCover(rom: Rom, path: String?) {
        if (path == null) {
            // Remember the miss so we do not re-list the folder on every recomposition.
            coverCache[rom.relativePath] = ""
        } else {
            coverCache[rom.relativePath] = path
        }
    }

    suspend fun resolveCover(rom: Rom, resolver: ContentResolver, launcherDirUri: Uri): String? {
        val known = coverCache[rom.relativePath]
        if (known != null) return known.ifBlank { null }
        val path = runCatching {
            launcher.readAssetsOfKind(resolver, launcherDirUri, rom.system, rom.name, "covers")
                .firstOrNull()?.url
        }.getOrNull()
        putCover(rom, path)
        return path
    }

    fun clear() {
        systems = emptyList()
        romsBySystem = emptyMap()
        coverCache.clear()
        selectedRom = null
        lastScanAt = 0L
    }

    /** Drop cached cover paths when the launcher scope changes. */
    fun clearCovers() {
        coverCache.clear()
    }

    companion object {
        private val NAMES = mapOf(
            "switch" to "Nintendo Switch",
            "gc" to "Nintendo GameCube",
            "wii" to "Nintendo Wii",
            "wiiu" to "Nintendo Wii U",
            "nes" to "NES",
            "snes" to "Super Nintendo",
            "n64" to "Nintendo 64",
            "gb" to "Game Boy",
            "gbc" to "Game Boy Color",
            "gba" to "Game Boy Advance",
            "nds" to "Nintendo DS",
            "3ds" to "Nintendo 3DS",
            "ps1" to "PlayStation",
            "psx" to "PlayStation",
            "ps2" to "PlayStation 2",
            "psp" to "PlayStation Portable",
            "genesis" to "Sega Genesis",
            "megadrive" to "Sega Mega Drive",
            "saturn" to "Sega Saturn",
            "dc" to "Dreamcast",
            "gg" to "Game Gear",
            "sms" to "Sega Master System",
            "arcade" to "Arcade",
            "mame" to "MAME",
            "fbneo" to "FinalBurn Neo",
            "pce" to "PC Engine",
            "ngp" to "Neo Geo Pocket",
            "ws" to "WonderSwan",
            "vb" to "Virtual Boy",
            "scummvm" to "ScummVM",
            "ports" to "Ports",
            "androidapps" to "Android Apps",
            "androidgames" to "Android Games",
            "emulators" to "Emulators",
            "desktop" to "Desktop",
            "tools" to "Tools",
            "android" to "Android Games",
            "pc" to "PC",
        )

        fun systemDisplayName(id: String): String =
            NAMES[id.lowercase()] ?: id.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** On-disk copy of the last scan, so a cold start does not rescan the whole tree. */
@Serializable
private data class LibraryCache(
    val version: Int,
    val romDirUri: String,
    val launcherType: String,
    val scannedAt: Long,
    val roms: Map<String, List<Rom>>,
)
