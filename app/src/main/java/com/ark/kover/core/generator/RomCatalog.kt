package com.ark.kover.core.generator

import android.net.Uri
import com.ark.kover.core.launcher.EsDePaths
import com.ark.kover.core.matcher.NameNormalizer
import com.ark.kover.core.model.Rom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the configured ROM folder without touching any launcher adapter.
 *
 * The pack generator must work even when no launcher is configured, so it only
 * needs the generic ROM root: one directory per system, ROM files below it.
 */
@Singleton
class RomCatalog @Inject constructor(
    private val normalizer: NameNormalizer,
) {
    private val excludedExtensions = setOf(
        "txt", "log", "md", "json", "xml", "csv",
        "mp3", "wav", "ogg", "flac", "m4a",
        "mp4", "avi", "mkv", "mov",
        "bak", "tmp", "ini", "cfg",
        "html", "htm", "js", "css",
        "pdf", "doc", "docx",
        "png", "jpg", "jpeg", "webp", "gif",
    )

    /** Launcher extras and service folders that are not game systems. */
    private val skippedSystems = setOf(
        "androidapps", "androidgames", "emulators", "desktop", "tools",
    )

    suspend fun scan(romDirUri: Uri): Map<String, List<Rom>> = withContext(Dispatchers.IO) {
        val root = EsDePaths.resolve(romDirUri)
        if (root == null || !root.isDirectory) {
            android.util.Log.e("Generator", "ROM dir not accessible: $romDirUri")
            return@withContext emptyMap()
        }
        val result = mutableMapOf<String, List<Rom>>()
        root.listFiles { file -> file.isDirectory }?.forEach { systemDir ->
            val systemId = systemDir.name.lowercase()
            if (systemId.startsWith(".") || systemId in skippedSystems) return@forEach
            val roms = systemDir.walkTopDown()
                .filter { file ->
                    val ext = file.extension.lowercase()
                    file.isFile && ext.isNotBlank() && ext !in excludedExtensions
                }
                .mapNotNull { file ->
                    val ext = file.extension.lowercase()
                    val relative = runCatching {
                        file.relativeTo(systemDir).invariantSeparatorsPath
                    }.getOrElse { file.name }
                    Rom(
                        id = normalizer.romId("$systemId/$relative"),
                        name = normalizer.normalize(file.name),
                        fileName = file.name,
                        relativePath = "$systemId/$relative",
                        system = systemId,
                        extension = ext,
                        size = file.length(),
                    )
                }
                .sortedBy { it.fileName.lowercase() }
                .toList()
            if (roms.isNotEmpty()) result[systemId] = roms
        }
        android.util.Log.d(
            "Generator",
            "scan: ${result.size} systems, ${result.values.sumOf { it.size }} ROMs",
        )
        result
    }
}
