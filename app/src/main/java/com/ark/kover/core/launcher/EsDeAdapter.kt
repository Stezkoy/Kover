package com.ark.kover.core.launcher

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import com.ark.kover.core.matcher.NameNormalizer
import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.model.Rom
import com.ark.kover.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import org.xmlpull.v1.XmlPullParser
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EsDeAdapter @Inject constructor(
    private val normalizer: NameNormalizer,
    private val settings: SettingsRepository,
) : LauncherAdapter {

    override val id = "es-de"
    override val displayName = "ES-DE"

    /** File extensions that are never ROMs. */
    private val excludedExtensions = setOf(
        "txt", "log", "md", "json", "xml", "csv",
        "mp3", "wav", "ogg", "flac", "m4a",
        "mp4", "avi", "mkv", "mov",
        "bak", "tmp", "ini", "cfg",
        "html", "htm", "js", "css",
        "pdf", "doc", "docx",
        "png", "jpg", "jpeg", "webp", "gif",
    )

    val mediaKinds = listOf(
        "covers", "backcovers", "fanart", "screenshots", "videos",
        "marquees", "titlescreens", "manuals", "3dboxes", "physicalmedia", "miximages",
    )

    private fun isRomFile(file: java.io.File): Boolean {
        val ext = file.extension.lowercase()
        return ext.isNotBlank() && ext !in excludedExtensions
    }

    // ------------------------------------------------------------------ scanning

    override suspend fun scanRomDirectory(
        resolver: ContentResolver,
        romDirUri: Uri,
    ): Map<String, List<Rom>> {
        val romDir = resolveToUriFile(romDirUri)
        if (romDir == null || !romDir.isDirectory) {
            android.util.Log.e("EsDeAdapter", "ROM dir not accessible: $romDirUri -> $romDir")
            return emptyMap()
        }
        val result = mutableMapOf<String, List<Rom>>()
        romDir.listFiles { f -> f.isDirectory }?.forEach { systemDir ->
            val systemId = systemDir.name.lowercase()
            val roms = systemDir.walkTopDown()
                .filter { it.isFile && isRomFile(it) }
                .mapNotNull { file ->
                    val ext = file.extension.lowercase()
                    if (ext.isBlank()) return@mapNotNull null
                    // Keep the path inside the system folder: game-per-folder layouts
                    // (Switch, PS3, ...) must stay unique and match gamelist.xml paths.
                    val relative = runCatching {
                        file.relativeTo(systemDir).invariantSeparatorsPath
                    }.getOrElse { file.name }
                    val fullPath = "$systemId/$relative"
                    Rom(
                        id = normalizer.romId(fullPath),
                        name = normalizer.normalize(file.name),
                        fileName = file.name,
                        relativePath = fullPath,
                        system = systemId,
                        extension = ext,
                        size = file.length(),
                    )
                }
                .toList()
            if (roms.isNotEmpty()) result[systemId] = roms
        }
        android.util.Log.d("EsDeAdapter", "scanRomDirectory: ${result.size} systems, ${result.values.sumOf { it.size }} ROMs")
        return result
    }

    /**
     * Resolve a tree Uri to a java.io.File path. Works for the primary volume and for
     * SD cards when MANAGE_EXTERNAL_STORAGE is granted.
     */
    private fun resolveToUriFile(uri: Uri): java.io.File? {
        return try {
            if (uri.scheme == "content" && uri.path?.contains("/tree/") == true) {
                val treeId = uri.path!!.substringAfterLast("/")
                val decodedTreeId = java.net.URLDecoder.decode(treeId, "UTF-8")
                val colonIndex = decodedTreeId.indexOf(':')
                if (colonIndex > 0) {
                    val volume = decodedTreeId.substring(0, colonIndex)
                    val path = decodedTreeId.substring(colonIndex + 1)
                    val basePath = when (volume) {
                        "primary" -> "/storage/emulated/0"
                        else -> "/storage/$volume"
                    }
                    java.io.File("$basePath/$path")
                } else {
                    java.io.File("/storage/emulated/0/$decodedTreeId")
                }
            } else if (uri.scheme == "file") {
                java.io.File(uri.path ?: "")
            } else {
                java.io.File(uri.toString())
            }
        } catch (e: Exception) {
            android.util.Log.e("EsDeAdapter", "Failed to resolve URI to File", e)
            null
        }
    }

    // ------------------------------------------------------------------ metadata

    /** gamelist.xml stores paths like "./Folder/file.nsp"; compare normalized. */
    private fun gamelistPath(raw: String): String =
        raw.removePrefix("./").removePrefix("/").replace('\\', '/').trim()

    private fun romPathInGamelist(romRelativePath: String, system: String): String =
        romRelativePath.removePrefix("$system/").replace('\\', '/')

    override suspend fun readMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
    ): Metadata? {
        val launcherDir = resolveToUriFile(launcherDirUri) ?: return null
        val gamelist = java.io.File(launcherDir, "gamelists/$system/gamelist.xml")
        if (!gamelist.isFile) return null
        val target = romPathInGamelist(romRelativePath, system)
        return try {
            val doc = parseDocument(gamelist)
            val match = doc.games.firstOrNull { entry ->
                gamelistPath(entry.field("path") ?: "").equals(target, ignoreCase = true)
            } ?: return null
            match.toMetadata()
        } catch (e: Exception) {
            android.util.Log.e("EsDeAdapter", "readMetadata failed", e)
            null
        }
    }

    override suspend fun writeMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        metadata: Metadata,
    ) {
        val launcherDir = resolveToUriFile(launcherDirUri) ?: return
        val gamelistDir = java.io.File(launcherDir, "gamelists/$system")
        if (!gamelistDir.exists() && !gamelistDir.mkdirs()) {
            android.util.Log.e("EsDeAdapter", "cannot create $gamelistDir")
            return
        }
        val gamelist = java.io.File(gamelistDir, "gamelist.xml")
        val doc = if (gamelist.isFile) parseDocument(gamelist) else ParsedGamelist()
        val target = romPathInGamelist(romRelativePath, system)

        val existing = doc.games.firstOrNull { entry ->
            gamelistPath(entry.field("path") ?: "").equals(target, ignoreCase = true)
        }
        val entry = existing ?: GamelistEntry(
            tag = "game",
            fields = mutableListOf("path" to "./$target"),
        ).also { doc.games.add(it) }

        // Update only the fields we own; every other tag (favorite, playcount, image,
        // video, md5, ...) of the entry is preserved as-is.
        entry.setField("name", metadata.name)
        entry.setField("desc", metadata.desc)
        entry.setField("rating", if (metadata.rating > 0f) metadata.rating.toString() else "")
        entry.setField("releasedate", metadata.releaseDate)
        entry.setField("developer", metadata.developer)
        entry.setField("publisher", metadata.publisher)
        entry.setField("genre", metadata.genre)
        entry.setField("players", metadata.players)

        try {
            writeDocument(gamelist, doc)
        } catch (e: Exception) {
            android.util.Log.e("EsDeAdapter", "writeMetadata failed", e)
        }
    }

    private class ParsedGamelist(
        val pre: MutableList<GamelistEntry> = mutableListOf(),
        val games: MutableList<GamelistEntry> = mutableListOf(),
    )

    /**
     * Reads gamelist.xml keeping every element: top-level tags (e.g. alternativeEmulator)
     * and all per-game tags, so writing back does not destroy data we do not own.
     */
    private fun parseDocument(file: java.io.File): ParsedGamelist {
        val doc = ParsedGamelist()
        val parser = Xml.newPullParser()
        parser.setInput(file.inputStream(), "UTF-8")
        var current: GamelistEntry? = null
        var fieldName: String? = null
        val text = StringBuilder()
        var inGameList = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    val entry = current
                    when {
                        name == "gameList" -> inGameList = true
                        inGameList && (name == "game" || name == "folder") ->
                            current = GamelistEntry(name, mutableListOf())
                        !inGameList && entry == null ->
                            current = GamelistEntry(name, mutableListOf())
                        else -> {
                            fieldName = name
                            text.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (fieldName != null) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    val entry = current
                    when {
                        name == "gameList" -> inGameList = false
                        entry != null && name == entry.tag -> {
                            if (entry.tag == "game" || entry.tag == "folder") {
                                doc.games.add(entry)
                            } else {
                                doc.pre.add(entry)
                            }
                            current = null
                        }
                        name == fieldName -> {
                            entry?.fields?.add(name to text.toString())
                            fieldName = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return doc
    }

    private fun writeDocument(file: java.io.File, doc: ParsedGamelist) {
        val serializer = Xml.newSerializer()
        file.outputStream().use { out ->
            serializer.setOutput(out, "UTF-8")
            serializer.startDocument("UTF-8", true)
            doc.pre.forEach { writeEntry(serializer, it, 0) }
            serializer.startTag(null, "gameList")
            serializer.text("\n")
            doc.games.forEach { writeEntry(serializer, it, 1) }
            serializer.text("\n")
            serializer.endTag(null, "gameList")
            serializer.endDocument()
            serializer.flush()
        }
    }

    private fun writeEntry(serializer: org.xmlpull.v1.XmlSerializer, entry: GamelistEntry, depth: Int) {
        fun indent(level: Int) = "\n" + "  ".repeat(level)
        serializer.startTag(null, entry.tag)
        entry.fields.forEach { (key, value) ->
            if (value.isNotEmpty()) {
                serializer.text(indent(depth + 1))
                serializer.startTag(null, key)
                serializer.text(value)
                serializer.endTag(null, key)
            }
        }
        serializer.text(indent(depth))
        serializer.endTag(null, entry.tag)
        serializer.text("\n")
    }

    private class GamelistEntry(
        val tag: String,
        val fields: MutableList<Pair<String, String>>,
    ) {
        fun field(key: String): String? = fields.firstOrNull { it.first == key }?.second

        fun setField(key: String, value: String) {
            val index = fields.indexOfFirst { it.first == key }
            when {
                value.isBlank() -> if (index >= 0) fields.removeAt(index)
                index >= 0 -> fields[index] = key to value
                else -> fields.add(key to value)
            }
        }

        fun toMetadata(): Metadata = Metadata(
            name = field("name").orEmpty(),
            desc = field("desc").orEmpty(),
            rating = field("rating")?.toFloatOrNull() ?: 0f,
            releaseDate = field("releasedate").orEmpty(),
            developer = field("developer").orEmpty(),
            publisher = field("publisher").orEmpty(),
            genre = field("genre").orEmpty(),
            players = field("players").orEmpty(),
        )
    }

    // -------------------------------------------------------------------- assets

    override suspend fun readAssets(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
    ): Map<String, List<Asset>> {
        val launcherDir = resolveToUriFile(launcherDirUri) ?: return emptyMap()
        val result = mutableMapOf<String, List<Asset>>()
        mediaKinds.forEach { kind ->
            val assets = listKind(launcherDir, system, kind, gameName)
            if (assets.isNotEmpty()) result[kind] = assets
        }
        return result
    }

    override suspend fun readAssetsOfKind(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
    ): List<Asset> {
        val launcherDir = resolveToUriFile(launcherDirUri) ?: return emptyList()
        return listKind(launcherDir, system, kind, gameName)
    }

    private fun listKind(
        launcherDir: java.io.File,
        system: String,
        kind: String,
        gameName: String,
    ): List<Asset> {
        val kindDir = java.io.File(launcherDir, "downloaded_media/$system/$kind")
        if (!kindDir.isDirectory) return emptyList()
        return kindDir.listFiles { f -> f.isFile && f.name.startsWith(gameName) }
            ?.sortedBy { it.name }
            ?.map { f -> Asset(kind = kind, url = f.absolutePath, mime = mimeFromExt(f.extension)) }
            .orEmpty()
    }

    override suspend fun writeAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
        asset: Asset,
        fileBytes: ByteArray,
    ) {
        val launcherDir = resolveToUriFile(launcherDirUri) ?: return
        val kindDir = java.io.File(launcherDir, "downloaded_media/$system/$kind")
        if (!kindDir.exists() && !kindDir.mkdirs()) return

        val ext = asset.url.substringAfterLast('/').substringAfterLast('.', "")
            .substringBefore('?')
            .ifBlank { extFromMime(asset.mime) }

        val candidates = kindDir.listFiles { f -> f.isFile && f.name.startsWith(gameName) }
            ?.sortedBy { it.name }
            .orEmpty()

        // Backups are optional (Settings -> Backups); off by default to save space.
        val backupEnabled = settings.settings.first().backupOnChange

        // Keep the ORIGINAL artwork as the backup: never overwrite an existing backup.
        fun ensureBackup() {
            if (!backupEnabled || candidates.isEmpty()) return
            runCatching {
                val backupDir = java.io.File(launcherDir, ".kover-backup/$system/$kind")
                val hasBackup = backupDir.listFiles { f ->
                    f.isFile && f.name.startsWith(candidates.first().nameWithoutExtension)
                }?.isNotEmpty() == true
                if (hasBackup) return
                backupDir.mkdirs()
                val target = candidates.first()
                java.io.File(backupDir, target.name).writeBytes(target.readBytes())
            }
        }

        ensureBackup()

        // One existing file for this slot: it is "the" artwork, so replace it.
        if (candidates.size == 1) {
            val target = candidates.first()
            if (target.extension.equals(ext, ignoreCase = true)) {
                target.writeBytes(fileBytes)
            } else {
                target.delete()
                java.io.File(kindDir, "$gameName.$ext").writeBytes(fileBytes)
            }
            return
        }
        // Nothing yet: create the canonical name.
        if (candidates.isEmpty()) {
            java.io.File(kindDir, "$gameName.$ext").writeBytes(fileBytes)
            return
        }
        // Several files (e.g. screenshots): add one more instead of destroying the set.
        var index = candidates.size + 1
        var out = java.io.File(kindDir, "$gameName ($index).$ext")
        while (out.exists()) {
            index++
            out = java.io.File(kindDir, "$gameName ($index).$ext")
        }
        out.writeBytes(fileBytes)
    }

    override suspend fun backupAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
    ): ByteArray? {
        val launcherDir = resolveToUriFile(launcherDirUri) ?: return null
        val kindDir = java.io.File(launcherDir, "downloaded_media/$system/$kind")
        val file = kindDir.listFiles { f -> f.isFile && f.name.startsWith(gameName) }?.firstOrNull()
            ?: return null
        return file.readBytes()
    }

    override suspend fun backupMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
    ): Metadata? = readMetadata(resolver, launcherDirUri, system, romRelativePath)

    override suspend fun restoreAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
        fileBytes: ByteArray,
    ) {
        val launcherDir = resolveToUriFile(launcherDirUri) ?: return
        val kindDir = java.io.File(launcherDir, "downloaded_media/$system/$kind")
        kindDir.mkdirs()
        val file = kindDir.listFiles { f -> f.isFile && f.name.startsWith(gameName) }?.firstOrNull()
        file?.writeBytes(fileBytes)
    }

    override suspend fun restoreMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        metadata: Metadata,
    ) = writeMetadata(resolver, launcherDirUri, system, romRelativePath, metadata)

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
}
