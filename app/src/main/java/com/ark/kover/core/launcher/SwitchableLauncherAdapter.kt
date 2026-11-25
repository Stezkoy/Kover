package com.ark.kover.core.launcher

import android.content.ContentResolver
import android.net.Uri
import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.model.Rom
import com.ark.kover.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Picks the active launcher adapter from settings on every call, so switching
 * ES-DE ↔ NeoStation needs no app restart.
 */
class SwitchableLauncherAdapter(
    private val settings: SettingsRepository,
    private val esde: EsDeAdapter,
    private val neostation: NeoStationAdapter,
    private val custom: CustomAdapter,
) : LauncherAdapter {

    override val id = "launcher"
    override val displayName = "Launcher"

    /** Concrete adapter for a launcher type (used to pin a write to the current launcher). */
    fun adapterFor(launcherType: String): LauncherAdapter = when (launcherType) {
        "neostation" -> neostation
        "custom" -> custom
        else -> esde
    }

    private suspend fun pick(): LauncherAdapter =
        adapterFor(settings.settings.first().launcherType)

    override fun indexRoms(roms: Map<String, List<Rom>>) {
        esde.indexRoms(roms)
        neostation.indexRoms(roms)
        custom.indexRoms(roms)
    }

    override suspend fun scanRomDirectory(
        resolver: ContentResolver,
        romDirUri: Uri,
    ): Map<String, List<Rom>> = pick().scanRomDirectory(resolver, romDirUri)

    override suspend fun readMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
    ): Metadata? = pick().readMetadata(resolver, launcherDirUri, system, romRelativePath)

    override suspend fun readAssets(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
    ): Map<String, List<Asset>> = pick().readAssets(resolver, launcherDirUri, system, gameName)

    override suspend fun readAssetsOfKind(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
    ): List<Asset> = pick().readAssetsOfKind(resolver, launcherDirUri, system, gameName, kind)

    override suspend fun writeMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        metadata: Metadata,
    ) = pick().writeMetadata(resolver, launcherDirUri, system, romRelativePath, metadata)

    override suspend fun writeAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
        asset: Asset,
        fileBytes: ByteArray,
    ) = pick().writeAsset(resolver, launcherDirUri, system, gameName, kind, asset, fileBytes)

    override suspend fun backupAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
    ): ByteArray? = pick().backupAsset(resolver, launcherDirUri, system, gameName, kind)

    override suspend fun backupMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
    ): Metadata? = pick().backupMetadata(resolver, launcherDirUri, system, romRelativePath)

    override suspend fun restoreAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
        fileBytes: ByteArray,
    ) = pick().restoreAsset(resolver, launcherDirUri, system, gameName, kind, fileBytes)

    override suspend fun restoreMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        metadata: Metadata,
    ) = pick().restoreMetadata(resolver, launcherDirUri, system, romRelativePath, metadata)
}
