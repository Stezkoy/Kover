package com.ark.kover.core.launcher

import android.content.ContentResolver
import android.net.Uri
import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.model.Rom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * "Custom" launcher: Kover owns the data folder and writes its own tidy structure.
 *
 * ```
 * <folder>/
 * ├── gamelists/{system}/gamelist.xml     one file per system, full metadata fields
 * └── media/{system}/{kind}/{rom name without extension}.png
 * ```
 *
 * One file per slot, named after the ROM file itself (e.g. `007 - GoldenEye (USA).png`),
 * so art can never collide between regions or revisions. The XML is Kover's own format:
 * a readable list of games with a relative path and metadata.
 */
class CustomAdapter(
    private val esde: EsDeAdapter,
    private val settings: com.ark.kover.core.settings.SettingsRepository,
) : LauncherAdapter {

    override val id = "custom"
    override val displayName = "Custom"

    private val mediaKinds = listOf(
        "covers", "backcovers", "fanart", "screenshots", "videos",
        "marquees", "titlescreens", "manuals", "3dboxes", "physicalmedia", "miximages",
    )

    /** "system|normalized name" -> original ROM file name. */
    private val romIndex = mutableMapOf<String, String>()

    override fun indexRoms(roms: Map<String, List<Rom>>) {
        romIndex.clear()
        roms.forEach { (system, list) ->
            list.forEach { rom -> romIndex["$system|${rom.name}"] = rom.fileName }
        }
        android.util.Log.d("Custom", "indexed ${romIndex.size} ROM names")
    }

    override suspend fun scanRomDirectory(
        resolver: ContentResolver,
        romDirUri: Uri,
    ): Map<String, List<Rom>> {
        val map = esde.scanRomDirectory(resolver, romDirUri)
        indexRoms(map)
        return map
    }

    // ------------------------------------------------------------------ helpers

    private fun root(launcherDirUri: Uri): File? = EsDePaths.resolve(launcherDirUri)

    private fun gamelistFile(root: File, system: String): File =
        File(root, "gamelists/$system/gamelist.xml")

    /** Relative path inside the system folder, e.g. "007 - GoldenEye (USA).z64". */
    private fun pathInSystem(system: String, romRelativePath: String): String =
        romRelativePath.removePrefix("$system/").replace('\\', '/')

    /** Full ROM file name without its last extension: "007 - GoldenEye (USA).z64" -> "…(USA)". */
    private fun baseName(fileName: String): String = fileName.substringBeforeLast('.')

    private fun resolveFileName(system: String, gameName: String): String =
        romIndex["$system|$gameName"] ?: gameName

    // ------------------------------------------------------------------ metadata

    override suspend fun readMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
    ): Metadata? = withContext(Dispatchers.IO) {
        val root = root(launcherDirUri) ?: return@withContext null
        val doc = parse(gamelistFile(root, system)) ?: return@withContext null
        val rel = pathInSystem(system, romRelativePath)
        val game = findGame(doc, rel) ?: return@withContext null
        metadata(game)
    }

    override suspend fun readMetadataForSystem(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        roms: List<Rom>,
    ): Map<String, Metadata> = withContext(Dispatchers.IO) {
        val root = root(launcherDirUri) ?: return@withContext emptyMap()
        val doc = parse(gamelistFile(root, system)) ?: return@withContext emptyMap()
        val games = doc.getElementsByTagName("game")
        val byPath = mutableMapOf<String, Element>()
        for (i in 0 until games.length) {
            val game = games.item(i) as? Element ?: continue
            val path = text(game, "path").removePrefix("./")
            if (path.isNotBlank()) byPath[path] = game
        }
        roms.mapNotNull { rom ->
            byPath[pathInSystem(system, rom.relativePath)]?.let { rom.relativePath to metadata(it) }
        }.toMap()
    }

    private fun metadata(game: Element): Metadata = Metadata(
        name = text(game, "name"),
        desc = text(game, "description"),
        rating = text(game, "rating").toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
        releaseDate = text(game, "releasedate"),
        developer = text(game, "developer"),
        publisher = text(game, "publisher"),
        genre = text(game, "genre"),
        players = text(game, "players"),
    )

    override suspend fun writeMetadata(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        romRelativePath: String,
        metadata: Metadata,
    ) = withContext(Dispatchers.IO) {
        val root = root(launcherDirUri) ?: return@withContext
        val file = gamelistFile(root, system)
        val doc = parse(file) ?: newDocument()
        val rel = pathInSystem(system, romRelativePath)
        val game = findGame(doc, rel) ?: createGame(doc, rel)

        setText(doc, game, "name", metadata.name)
        setText(doc, game, "description", metadata.desc)
        setText(doc, game, "rating", if (metadata.rating > 0f) metadata.rating.toString() else "")
        setText(doc, game, "releasedate", metadata.releaseDate)
        setText(doc, game, "developer", metadata.developer)
        setText(doc, game, "publisher", metadata.publisher)
        setText(doc, game, "genre", metadata.genre)
        setText(doc, game, "players", metadata.players)

        save(file, doc)
        android.util.Log.d("Custom", "metadata written for $system/$rel")
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
        val root = root(launcherDirUri) ?: return@withContext emptyMap()
        mediaKinds.mapNotNull { kind ->
            val assets = listKind(root, system, kind, gameName)
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
        val root = root(launcherDirUri) ?: return@withContext emptyList()
        listKind(root, system, kind, gameName)
    }

    private fun listKind(root: File, system: String, kind: String, gameName: String): List<Asset> {
        val dir = File(root, "media/$system/$kind")
        if (!dir.isDirectory) return emptyList()
        val base = baseName(resolveFileName(system, gameName))
        return dir.listFiles { f -> f.isFile && matches(f, base) }
            ?.sortedBy { it.name }
            ?.map { f -> Asset(kind = kind, url = f.absolutePath, mime = mimeFromExt(f.extension)) }
            .orEmpty()
    }

    private fun matches(file: File, base: String): Boolean {
        val name = file.nameWithoutExtension
        return name.equals(base, ignoreCase = true) ||
            name.startsWith("$base (", ignoreCase = true)
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
        val root = root(launcherDirUri) ?: return@withContext
        val dir = File(root, "media/$system/$kind")
        if (!dir.exists() && !dir.mkdirs()) return@withContext

        val base = baseName(resolveFileName(system, gameName))
        val ext = asset.url.substringAfterLast('/').substringAfterLast('.', "")
            .substringBefore('?')
            .ifBlank { extFromMime(asset.mime) }

        val candidates = dir.listFiles { f -> f.isFile && matches(f, base) }
            ?.sortedBy { it.name }
            .orEmpty()

        // Backups are optional (Settings -> Backups); off by default to save space.
        val backupEnabled = settings.settings.first().backupOnChange

        // Keep the ORIGINAL artwork as the backup: never overwrite an existing backup.
        if (backupEnabled && candidates.isNotEmpty()) {
            val backupDir = File(root, ".kover-backup/$system/$kind")
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
        android.util.Log.d("Custom", "asset written: media/$system/$kind/$base.$ext")
        Unit
    }

    override suspend fun backupAsset(
        resolver: ContentResolver,
        launcherDirUri: Uri,
        system: String,
        gameName: String,
        kind: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val root = root(launcherDirUri) ?: return@withContext null
        val dir = File(root, "media/$system/$kind")
        val base = baseName(resolveFileName(system, gameName))
        dir.listFiles { f -> f.isFile && matches(f, base) }
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
        val root = root(launcherDirUri) ?: return@withContext
        val dir = File(root, "media/$system/$kind")
        dir.mkdirs()
        val base = baseName(resolveFileName(system, gameName))
        val existing = dir.listFiles { f -> f.isFile && matches(f, base) }
            ?.sortedBy { it.name }
            ?.firstOrNull()
        if (existing != null) {
            existing.writeBytes(fileBytes)
        } else {
            File(dir, "$base.png").writeBytes(fileBytes)
        }
    }

    // ----------------------------------------------------------------------- XML

    private fun newDocument(): Document {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        doc.appendChild(doc.createElement("gamelist"))
        return doc
    }

    private fun parse(file: File): Document? {
        if (!file.isFile) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.newDocumentBuilder().parse(file)
        }.getOrNull()
    }

    private fun findGame(doc: Document, relPath: String): Element? {
        val games = doc.getElementsByTagName("game")
        for (i in 0 until games.length) {
            val game = games.item(i) as? Element ?: continue
            val path = text(game, "path")
            if (path == relPath || path == "./$relPath") return game
        }
        return null
    }

    private fun createGame(doc: Document, relPath: String): Element {
        val game = doc.createElement("game")
        val path = doc.createElement("path")
        path.textContent = relPath
        game.appendChild(path)
        doc.documentElement.appendChild(game)
        return game
    }

    private fun text(game: Element, tag: String): String {
        val nodes = game.getElementsByTagName(tag)
        if (nodes.length == 0) return ""
        return nodes.item(0)?.textContent.orEmpty().trim()
    }

    private fun setText(doc: Document, game: Element, tag: String, value: String) {
        val nodes = game.getElementsByTagName(tag)
        val existing = if (nodes.length > 0) nodes.item(0) as? Element else null
        if (value.isBlank()) {
            if (existing != null) game.removeChild(existing)
            return
        }
        val element = existing ?: doc.createElement(tag).also { game.appendChild(it) }
        element.textContent = value
    }

    private fun save(file: File, doc: Document) {
        file.parentFile?.mkdirs()
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.transform(DOMSource(doc), StreamResult(file))
    }

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
