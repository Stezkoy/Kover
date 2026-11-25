package com.ark.kover.core.backup

import android.content.ContentResolver
import android.net.Uri
import com.ark.kover.core.launcher.EsDePaths
import com.ark.kover.core.launcher.LauncherAdapter
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class BackupEntry(
    val system: String,
    val kind: String,
    val fileName: String,
    val romPath: String,
    val gameName: String,
    val timestamp: Long,
    val metadata: Metadata? = null,
    val file: File,
    /** Fields that actually differ from the current values (empty for old backups). */
    val changedFields: List<MetadataField> = emptyList(),
)

/** Restorable metadata fields. */
enum class MetadataField(val key: String) {
    NAME("name"),
    DESC("desc"),
    RATING("rating"),
    RELEASE("releaseDate"),
    DEVELOPER("developer"),
    PUBLISHER("publisher"),
    GENRE("genre"),
    PLAYERS("players");

    fun from(meta: Metadata): String = when (this) {
        NAME -> meta.name
        DESC -> meta.desc
        RATING -> if (meta.rating > 0f) meta.rating.toString() else ""
        RELEASE -> meta.releaseDate
        DEVELOPER -> meta.developer
        PUBLISHER -> meta.publisher
        GENRE -> meta.genre
        PLAYERS -> meta.players
    }

    fun apply(previous: Metadata, current: Metadata): Metadata = when (this) {
        NAME -> current.copy(name = previous.name)
        DESC -> current.copy(desc = previous.desc)
        RATING -> current.copy(rating = previous.rating)
        RELEASE -> current.copy(releaseDate = previous.releaseDate)
        DEVELOPER -> current.copy(developer = previous.developer)
        PUBLISHER -> current.copy(publisher = previous.publisher)
        GENRE -> current.copy(genre = previous.genre)
        PLAYERS -> current.copy(players = previous.players)
    }
}

/**
 * Disk backups under ES-DE/.kover-backup:
 *
 *   .kover-backup/metadata/{system}/{romHash}.json   — previous metadata of a ROM
 *   .kover-backup/{system}/{kind}/{file}             — previous artwork files
 *
 * Restore is always per item: one artwork file or one metadata field.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val launcher: LauncherAdapter,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // ------------------------------------------------------------------ listing

    suspend fun list(resolver: ContentResolver, launcherDirUri: Uri): List<BackupEntry> =
        withContext(Dispatchers.IO) {
            val root = EsDePaths.resolve(launcherDirUri) ?: return@withContext emptyList()
            val backupRoot = File(root, ".kover-backup")
            if (!backupRoot.isDirectory) return@withContext emptyList()

            val result = mutableListOf<BackupEntry>()

            // Metadata snapshots.
            File(backupRoot, "metadata").listFiles { f -> f.isDirectory }?.forEach { systemDir ->
                systemDir.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { file ->
                    runCatching {
                        val dto = json.decodeFromString<MetadataBackupDto>(file.readText())
                        result.add(
                            BackupEntry(
                                system = systemDir.name,
                                kind = "metadata",
                                fileName = dto.path.substringAfterLast('/'),
                                romPath = dto.path,
                                gameName = dto.metadata.name.ifBlank { dto.path.substringAfterLast('/') },
                                timestamp = dto.timestamp,
                                metadata = dto.metadata.toMetadata(),
                                file = file,
                                changedFields = dto.changed.mapNotNull { key ->
                                    MetadataField.entries.firstOrNull { it.key == key }
                                },
                            ),
                        )
                    }
                }
            }

            // Artwork backups: every other folder at the root is a system id.
            backupRoot.listFiles { f -> f.isDirectory && f.name != "metadata" }?.forEach { systemDir ->
                systemDir.listFiles { f -> f.isDirectory }?.forEach { kindDir ->
                    kindDir.listFiles { f -> f.isFile }?.forEach { file ->
                        result.add(
                            BackupEntry(
                                system = systemDir.name,
                                kind = kindDir.name,
                                fileName = file.name,
                                romPath = "",
                                gameName = file.nameWithoutExtension,
                                timestamp = file.lastModified(),
                                file = file,
                            ),
                        )
                    }
                }
            }

            result.sortedByDescending { it.timestamp }
        }

    // ------------------------------------------------------------------ metadata

    suspend fun saveMetadataBackup(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        previous: Metadata,
        current: Metadata,
    ) = withContext(Dispatchers.IO) {
        if (!settings.settings.first().backupOnChange) return@withContext
        val root = EsDePaths.resolve(launcherDirUri) ?: return@withContext
        val changed = MetadataField.entries
            .filter { it.from(previous) != it.from(current) }
            .map { it.key }
        if (changed.isEmpty()) return@withContext
        val dir = File(root, ".kover-backup/metadata/$system").apply { mkdirs() }
        val file = File(dir, "${hashKey(romRelativePath)}.json")
        // Keep the FIRST snapshot so the user can always restore the original values.
        if (file.exists()) return@withContext
        val dto = MetadataBackupDto(
            path = romRelativePath,
            timestamp = System.currentTimeMillis(),
            changed = changed,
            metadata = MetadataDto.from(previous),
        )
        runCatching { file.writeText(json.encodeToString(MetadataBackupDto.serializer(), dto)) }
    }

    suspend fun restoreMetadataField(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        entry: BackupEntry,
        field: MetadataField,
    ): Boolean = withContext(Dispatchers.IO) {
        val previous = entry.metadata ?: return@withContext false
        val current = launcher.readMetadata(resolver, launcherDirUri, entry.system, entry.romPath)
            ?: Metadata()
        val updated = field.apply(previous, current)
        runCatching {
            launcher.writeMetadata(resolver, launcherDirUri, entry.system, entry.romPath, updated)
        }.isSuccess
    }

    // ------------------------------------------------------------------- artwork

    suspend fun restoreAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        entry: BackupEntry,
    ): Boolean = withContext(Dispatchers.IO) {
        val root = EsDePaths.resolve(launcherDirUri) ?: return@withContext false
        val targetDir = File(root, "downloaded_media/${entry.system}/${entry.kind}").apply { mkdirs() }
        // Remove every file that currently occupies this slot (including renamed variants),
        // then put the backed-up original back.
        val base = entry.gameName.substringBefore(" (").substringBefore(" [").trim()
        targetDir.listFiles { f ->
            f.isFile && (f.name.startsWith(entry.gameName) ||
                (base.isNotBlank() && f.name.startsWith(base)))
        }?.forEach { runCatching { it.delete() } }
        val target = File(targetDir, entry.fileName)
        runCatching { entry.file.copyTo(target, overwrite = true) }.isSuccess
    }

    private fun hashKey(relativePath: String): String {
        val readable = relativePath.replace(Regex("[^a-zA-Z0-9]"), "_").take(60)
        return "${readable}_${relativePath.hashCode().toUInt().toString(16)}"
    }

    // --------------------------------------------------------------------- DTOs

    @Serializable
    private data class MetadataBackupDto(
        val path: String,
        val timestamp: Long,
        val changed: List<String> = emptyList(),
        val metadata: MetadataDto,
    )

    @Serializable
    private data class MetadataDto(
        val name: String = "",
        val desc: String = "",
        val rating: Float = 0f,
        val releaseDate: String = "",
        val developer: String = "",
        val publisher: String = "",
        val genre: String = "",
        val players: String = "",
    ) {
        fun toMetadata() = Metadata(
            name = name,
            desc = desc,
            rating = rating,
            releaseDate = releaseDate,
            developer = developer,
            publisher = publisher,
            genre = genre,
            players = players,
        )

        companion object {
            fun from(meta: Metadata) = MetadataDto(
                name = meta.name,
                desc = meta.desc,
                rating = meta.rating,
                releaseDate = meta.releaseDate,
                developer = meta.developer,
                publisher = meta.publisher,
                genre = meta.genre,
                players = meta.players,
            )
        }
    }
}
