package com.ark.kover.core.launcher

import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.ark.kover.core.matcher.NameNormalizer
import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.model.Rom
import com.ark.kover.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NeoStation (com.neogamelab.neostation).
 *
 * The library lives in `<NeoStation>/data.sqlite`:
 *  - `app_system_folders` maps ROM folder names (megadrive, psx, ngc, …) to system ids (md, ps1, gc);
 *  - `user_screenscraper_metadata` keeps title, descriptions, rating, date, developer, publisher,
 *    genre and players per ROM file name.
 *
 * Artwork lives in `<NeoStation>/media/{systemId}/{kind}/{file name without extension}.{ext}`
 * with kinds `box2d` (covers), `fanarts`, `screenshots`, `videos`, `wheels` (logos).
 *
 * ROM scanning is identical to ES-DE (same folders, same tree), so it is delegated.
 */
class NeoStationAdapter(
    private val esde: EsDeAdapter,
    private val settings: SettingsRepository,
) : LauncherAdapter {

    override val id = "neostation"
    override val displayName = "NeoStation"

    private val normalizer = NameNormalizer()

    /** NeoStation media kinds we can map to ES-DE slots. */
    private val mediaKinds = mapOf(
        "covers" to "box2d",
        "fanart" to "fanarts",
        "screenshots" to "screenshots",
        "videos" to "videos",
        "marquees" to "wheels",
    )

    override suspend fun scanRomDirectory(
        resolver: ContentResolver,
        romDirUri: Uri,
    ): Map<String, List<Rom>> {
        val result = esde.scanRomDirectory(resolver, romDirUri).toMutableMap()
        // NeoStation keeps installed Android games as packages in its database,
        // they have no ROM file in the scanned tree.
        val s = settings.settings.first()
        val dirUri = s.neostationDirUri.ifBlank { s.esdeDirUri }
        val launcherDir = runCatching { root(Uri.parse(dirUri)) }.getOrNull()
        if (launcherDir != null) {
            val apps = androidApps(launcherDir)
            if (apps.isNotEmpty()) result[ANDROID_SYSTEM] = apps
        }
        return result
    }

    // ------------------------------------------------------------- android games

    private fun isAndroid(system: String): Boolean =
        system.equals(ANDROID_SYSTEM, ignoreCase = true)

    private fun androidApps(launcherDir: File): List<Rom> {
        val database = db(launcherDir) ?: return emptyList()
        return database.use { d ->
            d.rawQuery(
                "SELECT filename, title_name FROM user_roms WHERE is_android_game = 1",
                null,
            ).use { c ->
                val list = mutableListOf<Rom>()
                while (c.moveToNext()) {
                    val pkg = c.getString(0) ?: continue
                    val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: pkg
                    val path = "$ANDROID_SYSTEM/$pkg"
                    list += Rom(
                        id = normalizer.romId(path),
                        name = title,
                        fileName = pkg,
                        relativePath = path,
                        system = ANDROID_SYSTEM,
                        extension = "apk",
                    )
                }
                list.sortedBy { it.name.lowercase() }
            }
        }
    }

    /** Package name of an installed Android game by its display title. */
    private fun androidPackage(launcherDir: File, gameName: String): String? {
        val database = db(launcherDir) ?: return null
        return database.use { d ->
            d.rawQuery(
                "SELECT filename FROM user_roms WHERE is_android_game = 1 " +
                    "AND (title_name = ? OR filename = ?) LIMIT 1",
                arrayOf(gameName, gameName),
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }
    }

    private fun androidIconDir(launcherDir: File): File =
        File(launcherDir, "media/android_games_icons")

    /** NeoStation stores Android game art in media/android/{box2d,fanarts,screenshots,wheels}. */
    private fun androidKindDir(launcherDir: File, kind: String): File? {
        val neoKind = mediaKinds[kind] ?: return null
        return File(launcherDir, "media/$ANDROID_SYSTEM/$neoKind")
    }

    // ------------------------------------------------------------------ database

    private fun root(launcherDirUri: Uri): File? = EsDePaths.resolve(launcherDirUri)

    private fun db(launcherDir: File): SQLiteDatabase? = try {
        val file = File(launcherDir, "data.sqlite")
        if (!file.isFile) {
            android.util.Log.e("NeoStation", "data.sqlite not found in $launcherDir")
            null
        } else {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        }
    } catch (e: Exception) {
        android.util.Log.e("NeoStation", "cannot open data.sqlite", e)
        null
    }

    /** ROM folder name -> NeoStation system id (megadrive -> md, psx -> ps1, …). */
    private fun systemIds(db: SQLiteDatabase): Map<String, String> {
        val map = mutableMapOf<String, String>()
        db.rawQuery("SELECT folder_name, system_id FROM app_system_folders", null).use { c ->
            while (c.moveToNext()) {
                val folder = c.getString(0)?.lowercase() ?: continue
                val id = c.getString(1) ?: continue
                map[folder] = id
            }
        }
        return map
    }

    private fun systemId(launcherDir: File, system: String): String? {
        val database = db(launcherDir) ?: return null
        return database.use { systemIds(it)[system.lowercase()] }
    }

    private fun fileName(romRelativePath: String): String =
        romRelativePath.substringAfterLast('/')

    private fun baseName(fileName: String): String = fileName.substringBeforeLast('.')

    /** ROM file name known to NeoStation for a (system, normalized game name) pair. */
    private fun romFileName(db: SQLiteDatabase, systemId: String, gameName: String): String? {
        val escaped = gameName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        db.rawQuery(
            "SELECT filename FROM user_roms WHERE app_system_id = ? AND filename LIKE ? ESCAPE '\\' " +
                "ORDER BY length(filename) LIMIT 1",
            arrayOf(systemId, "$escaped%"),
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    // ------------------------------------------------------------------ metadata

    override suspend fun readMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
    ): Metadata? = withContext(Dispatchers.IO) {
        val launcherDir = root(launcherDirUri) ?: return@withContext null
        val database = db(launcherDir) ?: return@withContext null
        val file = fileName(romRelativePath)
        database.use { d ->
            val sysId = if (isAndroid(system)) ANDROID_SYSTEM
            else systemIds(d)[system.lowercase()] ?: return@withContext null
            d.rawQuery(
                "SELECT real_name, description_en, description_es, description_fr, description_de, " +
                    "description_it, description_pt, rating, release_date, developer, publisher, " +
                    "genre, players FROM user_screenscraper_metadata " +
                    "WHERE app_system_id = ? AND filename = ?",
                arrayOf(sysId, file),
            ).use { c ->
                if (!c.moveToFirst()) return@withContext null
                val langs = linkedMapOf(
                    "en" to c.getString(1).orEmpty(),
                    "es" to c.getString(2).orEmpty(),
                    "fr" to c.getString(3).orEmpty(),
                    "de" to c.getString(4).orEmpty(),
                    "it" to c.getString(5).orEmpty(),
                    "pt" to c.getString(6).orEmpty(),
                ).filterValues { it.isNotBlank() }
                Metadata(
                    name = c.getString(0).orEmpty(),
                    desc = c.getString(1).orEmpty(),
                    descByLang = langs,
                    rating = (c.getDouble(7) / 20.0).toFloat().coerceIn(0f, 1f),
                    releaseDate = parseDate(c.getString(8)),
                    developer = c.getString(9).orEmpty(),
                    publisher = c.getString(10).orEmpty(),
                    genre = c.getString(11).orEmpty(),
                    players = c.getString(12).orEmpty(),
                )
            }
        }
    }

    override suspend fun writeMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        metadata: Metadata,
    ) = withContext(Dispatchers.IO) {
        val launcherDir = root(launcherDirUri) ?: return@withContext
        val database = db(launcherDir) ?: return@withContext
        val file = fileName(romRelativePath)
        database.use { d ->
            val sysId = if (isAndroid(system)) ANDROID_SYSTEM
            else systemIds(d)[system.lowercase()] ?: return@withContext
            // Only touch the metadata table: NeoStation's own scan columns stay untouched.
            d.execSQL(
                "INSERT OR IGNORE INTO user_screenscraper_metadata (app_system_id, filename) VALUES (?, ?)",
                arrayOf(sysId, file),
            )
            d.execSQL(
                "UPDATE user_screenscraper_metadata SET real_name = ?, description_en = ?, " +
                    "rating = ?, release_date = ?, developer = ?, publisher = ?, genre = ?, " +
                    "players = ?, is_fully_scraped = 1, updated_at = ? " +
                    "WHERE app_system_id = ? AND filename = ?",
                arrayOf(
                    emptyToNull(metadata.name),
                    emptyToNull(metadata.desc),
                    if (metadata.rating > 0f) metadata.rating * 20.0 else null,
                    isoDate(metadata.releaseDate),
                    emptyToNull(metadata.developer),
                    emptyToNull(metadata.publisher),
                    emptyToNull(metadata.genre),
                    emptyToNull(metadata.players),
                    timestamp(),
                    sysId,
                    file,
                ),
            )
            android.util.Log.d("NeoStation", "metadata written for $sysId/$file")
        }
        Unit
    }

    override suspend fun backupMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
    ): Metadata? = readMetadata(resolver, launcherDirUri, system, romRelativePath)

    override suspend fun restoreMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        metadata: Metadata,
    ) = writeMetadata(resolver, launcherDirUri, system, romRelativePath, metadata)

    // -------------------------------------------------------------------- assets

    override suspend fun readAssets(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
    ): Map<String, List<Asset>> = withContext(Dispatchers.IO) {
        val launcherDir = root(launcherDirUri) ?: return@withContext emptyMap()
        if (isAndroid(system)) {
            val found = mutableMapOf<String, List<Asset>>()
            mediaKinds.keys.forEach { kind ->
                val assets = listAndroidKind(launcherDir, kind, gameName)
                if (assets.isNotEmpty()) found[kind] = assets
            }
            return@withContext found
        }
        val sysId = systemId(launcherDir, system) ?: return@withContext emptyMap()
        mediaKinds.mapNotNull { (kind, neoKind) ->
            val assets = listKind(launcherDir, sysId, kind, neoKind, gameName)
            if (assets.isEmpty()) null else kind to assets
        }.toMap()
    }

    override suspend fun readAssetsOfKind(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
    ): List<Asset> = withContext(Dispatchers.IO) {
        val launcherDir = root(launcherDirUri) ?: return@withContext emptyList()
        if (isAndroid(system)) {
            return@withContext listAndroidKind(launcherDir, kind, gameName)
        }
        val neoKind = mediaKinds[kind] ?: return@withContext emptyList()
        val sysId = systemId(launcherDir, system) ?: return@withContext emptyList()
        listKind(launcherDir, sysId, kind, neoKind, gameName)
    }

    /**
     * Android game art: media/android/{box2d,fanarts,screenshots,wheels}/{package}.png.
     * Covers fall back to the cached app icon when the game has no boxart yet.
     */
    private fun listAndroidKind(launcherDir: File, kind: String, gameName: String): List<Asset> {
        val pkg = androidPackage(launcherDir, gameName) ?: gameName
        val dir = androidKindDir(launcherDir, kind) ?: return emptyList()
        val owned = dir.listFiles { f -> f.isFile && f.nameWithoutExtension == pkg }
            ?.sortedBy { it.name }
            ?.map { f -> Asset(kind = kind, url = f.absolutePath, mime = mimeFromExt(f.extension)) }
            .orEmpty()
        if (owned.isNotEmpty() || kind != "covers") return owned
        // No boxart yet: NeoStation itself shows the cached app icon.
        val iconDir = androidIconDir(launcherDir)
        return iconDir.listFiles { f -> f.isFile && f.nameWithoutExtension == pkg }
            ?.sortedBy { it.name }
            ?.map { f -> Asset(kind = kind, url = f.absolutePath, mime = mimeFromExt(f.extension)) }
            .orEmpty()
    }

    private fun listKind(
        launcherDir: File,
        systemId: String,
        kind: String,
        neoKind: String,
        gameName: String,
    ): List<Asset> {
        val dir = File(launcherDir, "media/$systemId/$neoKind")
        if (!dir.isDirectory) return emptyList()
        val base = knownBaseName(launcherDir, systemId, gameName)
        return dir.listFiles { f -> f.isFile && matches(f, base, gameName) }
            ?.sortedBy { it.name }
            ?.map { f -> Asset(kind = kind, url = f.absolutePath, mime = mimeFromExt(f.extension)) }
            .orEmpty()
    }

    private fun knownBaseName(launcherDir: File, systemId: String, gameName: String): String? {
        val database = db(launcherDir) ?: return null
        return database.use { d -> romFileName(d, systemId, gameName)?.let { baseName(it) } }
    }

    private fun matches(file: File, base: String?, gameName: String): Boolean {
        val name = file.nameWithoutExtension
        if (base != null && name.equals(base, ignoreCase = true)) return true
        if (base != null && name.startsWith(base, ignoreCase = true)) return true
        return name.startsWith(gameName, ignoreCase = true)
    }

    override suspend fun writeAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
        asset: Asset,
        fileBytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        val launcherDir = root(launcherDirUri) ?: return@withContext
        val dir: File
        val base: String
        if (isAndroid(system)) {
            val neoKind = mediaKinds[kind] ?: return@withContext
            base = androidPackage(launcherDir, gameName) ?: gameName
            dir = File(launcherDir, "media/$ANDROID_SYSTEM/$neoKind")
        } else {
            val neoKind = mediaKinds[kind] ?: run {
                android.util.Log.w("NeoStation", "kind '$kind' is not supported by NeoStation")
                return@withContext
            }
            val database = db(launcherDir) ?: return@withContext
            val sysId = database.use { systemIds(it)[system.lowercase()] } ?: return@withContext
            base = database.use { d ->
                romFileName(d, sysId, gameName)?.let { baseName(it) } ?: gameName
            }
            dir = File(launcherDir, "media/$sysId/$neoKind")
        }
        if (!dir.exists() && !dir.mkdirs()) return@withContext

        val ext = asset.url.substringAfterLast('/').substringAfterLast('.', "")
            .substringBefore('?')
            .ifBlank { extFromMime(asset.mime) }

        val candidates = dir.listFiles { f -> f.isFile && matches(f, base, gameName) }
            ?.sortedBy { it.name }
            .orEmpty()

        // Backups are optional (Settings -> Backups); off by default to save space.
        val backupEnabled = settings.settings.first().backupOnChange

        // Keep the ORIGINAL artwork as the backup: never overwrite an existing backup.
        if (backupEnabled && candidates.isNotEmpty()) {
            val backupDir = File(launcherDir, ".kover-backup/$system/$kind")
            val hasBackup = backupDir.listFiles { f ->
                f.isFile && f.name.startsWith(candidates.first().nameWithoutExtension)
            }?.isNotEmpty() == true
            if (!hasBackup) {
                runCatching {
                    backupDir.mkdirs()
                    val target = candidates.first()
                    File(backupDir, target.name).writeBytes(target.readBytes())
                }
            }
        }

        when {
            candidates.size == 1 -> {
                val target = candidates.first()
                if (target.extension.equals(ext, ignoreCase = true)) {
                    target.writeBytes(fileBytes)
                } else {
                    target.delete()
                    File(dir, "$base.$ext").writeBytes(fileBytes)
                }
            }
            candidates.isEmpty() -> File(dir, "$base.$ext").writeBytes(fileBytes)
            else -> {
                var index = candidates.size + 1
                var out = File(dir, "$base ($index).$ext")
                while (out.exists()) {
                    index++
                    out = File(dir, "$base ($index).$ext")
                }
                out.writeBytes(fileBytes)
            }
        }
        android.util.Log.d("NeoStation", "asset written: ${dir.parentFile?.name}/${dir.name}/$base.$ext")
        Unit
    }

    override suspend fun backupAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val launcherDir = root(launcherDirUri) ?: return@withContext null
        if (isAndroid(system)) {
            return@withContext listAndroidKind(launcherDir, kind, gameName)
                .firstOrNull()?.url?.let { File(it).readBytes() }
        }
        val neoKind = mediaKinds[kind] ?: return@withContext null
        val sysId = systemId(launcherDir, system) ?: return@withContext null
        val base = knownBaseName(launcherDir, sysId, gameName)
        val dir = File(launcherDir, "media/$sysId/$neoKind")
        dir.listFiles { f -> f.isFile && matches(f, base, gameName) }
            ?.sortedBy { it.name }
            ?.firstOrNull()
            ?.readBytes()
    }

    override suspend fun restoreAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
        fileBytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        val launcherDir = root(launcherDirUri) ?: return@withContext
        if (isAndroid(system)) {
            val pkg = androidPackage(launcherDir, gameName) ?: gameName
            val neoKind = mediaKinds[kind] ?: return@withContext
            val owned = File(launcherDir, "media/$ANDROID_SYSTEM/$neoKind")
            val iconDir = androidIconDir(launcherDir)
            val existing = owned.listFiles { f -> f.isFile && f.nameWithoutExtension == pkg }?.firstOrNull()
                ?: if (kind == "covers") {
                    iconDir.listFiles { f -> f.isFile && f.nameWithoutExtension == pkg }?.firstOrNull()
                } else null
            if (existing != null) {
                existing.writeBytes(fileBytes)
            } else {
                owned.mkdirs()
                File(owned, "$pkg.png").writeBytes(fileBytes)
            }
            return@withContext
        }
        val neoKind = mediaKinds[kind] ?: return@withContext
        val sysId = systemId(launcherDir, system) ?: return@withContext
        val base = knownBaseName(launcherDir, sysId, gameName) ?: gameName
        val dir = File(launcherDir, "media/$sysId/$neoKind")
        dir.mkdirs()
        val existing = dir.listFiles { f -> f.isFile && matches(f, base, gameName) }
            ?.sortedBy { it.name }
            ?.firstOrNull()
        if (existing != null) {
            existing.writeBytes(fileBytes)
        } else {
            File(dir, "$base.png").writeBytes(fileBytes)
        }
    }

    // -------------------------------------------------------------------- helpers

    private fun emptyToNull(value: String): String? = value.trim().ifBlank { null }

    /** "19970825T000000" -> "1997-08-25" */
    private fun isoDate(esDate: String): String? {
        if (esDate.length < 8) return null
        val digits = esDate.take(8)
        return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
    }

    /** "1997-08-25T00:00:00.000" -> "19970825T000000" */
    private fun parseDate(value: String?): String {
        if (value.isNullOrBlank() || value.length < 10) return ""
        val digits = value.take(10).replace("-", "")
        return if (digits.length == 8) "${digits}T000000" else ""
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())

    private fun mimeFromExt(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun extFromMime(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("mp4") -> "mp4"
        mime.contains("pdf") -> "pdf"
        else -> "png"
    }

    private companion object {
        /** Pseudo-system for installed Android games (NeoStation keeps them in user_roms). */
        const val ANDROID_SYSTEM = "android"
    }
}
