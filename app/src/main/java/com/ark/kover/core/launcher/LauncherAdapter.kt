package com.ark.kover.core.launcher

import android.content.ContentResolver
import android.net.Uri
import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.model.Rom
import com.ark.kover.core.model.System

/**
 * A launcher adapter: reads/writes the game library for a specific launcher.
 * All methods use ContentResolver + Uri (SAF-compatible) instead of java.io.File.
 */
interface LauncherAdapter {
    val id: String
    val displayName: String

    /** Scan the ROM directory and return all ROMs grouped by system. */
    suspend fun scanRomDirectory(
        resolver: ContentResolver,
        romDirUri: Uri,
    ): Map<String, List<Rom>>

    /** Read existing metadata for a ROM from the launcher's data. */
    suspend fun readMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
    ): Metadata?

    /**
     * Batch variant for bulk tools (auto-translate): reads metadata for many ROMs of one
     * system at once. Implementations should parse the source once instead of per ROM.
     * The returned map is keyed by [Rom.relativePath].
     */
    suspend fun readMetadataForSystem(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        roms: List<Rom>,
    ): Map<String, Metadata> {
        val result = mutableMapOf<String, Metadata>()
        roms.forEach { rom ->
            readMetadata(resolver, launcherDirUri, system, rom.relativePath)
                ?.let { result[rom.relativePath] = it }
        }
        return result
    }

    /** Read existing assets for a ROM from the launcher's media folder. */
    suspend fun readAssets(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
    ): Map<String, List<Asset>>

    /**
     * Read assets of a single kind only. Implementations should override this with a
     * cheap single-directory listing instead of scanning every media folder.
     */
    suspend fun readAssetsOfKind(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
    ): List<Asset> = readAssets(resolver, launcherDirUri, system, gameName)[kind].orEmpty()

    /** Write metadata back to the launcher (e.g. update gamelist.xml). */
    suspend fun writeMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        metadata: Metadata,
    )

    /** Write an asset file to the launcher's media folder. */
    suspend fun writeAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
        asset: Asset,
        fileBytes: ByteArray,
    )

    /** Get a backup of a specific asset before overwriting. */
    suspend fun backupAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
    ): ByteArray?

    /** Get a backup of metadata before overwriting. */
    suspend fun backupMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
    ): Metadata?

    /** Restore a previously backed-up asset. */
    suspend fun restoreAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
        fileBytes: ByteArray,
    )

    /** Restore a previously backed-up metadata. */
    suspend fun restoreMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        metadata: Metadata,
    )

    /**
     * Optional: adapters that name media files after the ROM file can index the scan
     * result (normalized name -> real file name).
     */
    fun indexRoms(roms: Map<String, List<Rom>>) {}
}
