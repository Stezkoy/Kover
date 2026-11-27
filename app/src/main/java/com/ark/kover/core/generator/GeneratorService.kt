package com.ark.kover.core.generator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.ark.kover.core.media.GeneratorArt
import com.ark.kover.core.media.GeneratorComposer
import com.ark.kover.core.model.MatchResult
import com.ark.kover.core.model.Rom
import com.ark.kover.core.network.hasInternet
import com.ark.kover.core.scraper.Scraper
import com.ark.kover.scraper.LibretroThumbnailsScraper
import com.ark.kover.scraper.NeoAssetsScraper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pack generator in an application-wide scope.
 *
 * The launcher adapter is intentionally not used: the job reads ROM names from
 * [RomCatalog], scrapes artwork from scratch through the selected scraper, and
 * writes only to the SAF pack folder.
 */
@Singleton
class GeneratorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val neoAssets: NeoAssetsScraper,
    private val libretro: LibretroThumbnailsScraper,
    private val composer: GeneratorComposer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val dirCache = mutableMapOf<String, DocumentFile>()

    var isRunning by mutableStateOf(false)
        private set
    var isStopping by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var cancelled by mutableStateOf(false)
        private set
    var totalGames by mutableStateOf(0)
        private set
    var processedGames by mutableStateOf(0)
        private set
    var generatedFiles by mutableStateOf(0)
        private set
    var skippedExisting by mutableStateOf(0)
        private set
    var skippedNoMatch by mutableStateOf(0)
        private set
    var failedGames by mutableStateOf(0)
        private set
    var textLogoGames by mutableStateOf(0)
        private set
    var currentGame by mutableStateOf("")
        private set
    var error by mutableStateOf<GeneratorError?>(null)
        private set
    var guestMode by mutableStateOf(false)
        private set
    var report by mutableStateOf<GeneratorReport?>(null)
        private set

    private var neoDelayMs = 500L
    private val errors = mutableListOf<String>()

    fun start(request: GeneratorRequest) {
        if (isRunning) return
        if (request.roms.isEmpty()) {
            error = GeneratorError.NO_ROMS
            finished = true
            report = GeneratorReport()
            return
        }
        if (!context.hasInternet()) {
            error = GeneratorError.NETWORK
            finished = true
            report = GeneratorReport(totalGames = request.roms.size)
            return
        }
        val root = DocumentFile.fromTreeUri(context, request.outputTreeUri)
        if (root == null || !root.isDirectory || !root.canWrite()) {
            error = GeneratorError.OUTPUT
            finished = true
            report = GeneratorReport(totalGames = request.roms.size)
            return
        }

        dirCache.clear()
        errors.clear()
        totalGames = request.roms.size
        processedGames = 0
        generatedFiles = 0
        skippedExisting = 0
        skippedNoMatch = 0
        failedGames = 0
        textLogoGames = 0
        currentGame = ""
        cancelled = false
        finished = false
        isStopping = false
        error = null
        guestMode = false
        report = null
        isRunning = true

        job = scope.launch {
            var wasCancelled = false
            try {
                val needsNeo = request.source == GeneratorSource.NEOASSETS ||
                    (request.fallback && request.source != GeneratorSource.NEOASSETS)
                if (needsNeo) {
                    val account = withContext(Dispatchers.IO) {
                        runCatching { neoAssets.accountInfo() }.getOrNull()
                    }
                    guestMode = account?.subject?.equals("guest", ignoreCase = true) == true
                    neoDelayMs = if (guestMode) 2100L else 350L
                    val remaining = account?.remaining
                    if (request.source == GeneratorSource.NEOASSETS &&
                        remaining != null && remaining <= 0
                    ) {
                        error = GeneratorError.QUOTA
                        return@launch
                    }
                }

                val needed = neededKinds(request.presets)
                for ((_, systemRoms) in request.roms.groupBy { it.system }) {
                    for (rom in systemRoms) {
                        ensureActive()
                        currentGame = rom.name
                        try {
                            withContext(Dispatchers.IO) {
                                generateOne(root, request, rom.system, rom, needed)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failedGames++
                            addError("${rom.fileName}: ${e.message.orEmpty()}")
                            android.util.Log.e("Generator", "failed ${rom.fileName}", e)
                        }
                        processedGames++
                    }
                }
            } catch (e: CancellationException) {
                wasCancelled = true
            } catch (e: Exception) {
                error = GeneratorError.OUTPUT
                addError(e.message.orEmpty())
                android.util.Log.e("Generator", "generator stopped", e)
            } finally {
                cancelled = wasCancelled
                currentGame = ""
                isRunning = false
                isStopping = false
                finished = true
                report = GeneratorReport(
                    totalGames = totalGames,
                    processedGames = processedGames,
                    generatedFiles = generatedFiles,
                    skippedExisting = skippedExisting,
                    skippedNoMatch = skippedNoMatch,
                    failedGames = failedGames,
                    textLogoGames = textLogoGames,
                    cancelled = cancelled,
                    errors = errors.toList(),
                )
            }
        }
    }

    fun cancel() {
        if (!isRunning) return
        isStopping = true
        job?.cancel()
    }

    private suspend fun generateOne(
        root: DocumentFile,
        request: GeneratorRequest,
        system: String,
        rom: Rom,
        needed: Set<String>,
    ) {
        val primary: Scraper = if (request.source == GeneratorSource.NEOASSETS) neoAssets else libretro
        val secondary: Scraper = if (request.source == GeneratorSource.NEOASSETS) libretro else neoAssets

        var art = scrape(primary, rom, primary === neoAssets)
        var extra: ScrapedArt? = null
        if (request.fallback && (art == null || missing(art, needed))) {
            extra = scrape(secondary, rom, secondary === neoAssets)
            art = merge(art, extra)
        }
        if (art == null || !art.hasAny()) {
            skippedNoMatch++
            return
        }

        var bitmaps = downloadArt(art, needed)
        if (request.fallback && missingBitmaps(bitmaps, needed)) {
            // A URL can be present but fail to download (dead CDN object, timeout).
            // Give the fallback source a chance to fill only those pieces.
            if (extra == null) extra = scrape(secondary, rom, secondary === neoAssets)
            if (extra != null) {
                val retryKinds = needed.filter { bitmapFor(bitmaps, it) == null }
                bitmaps = mergeBitmaps(bitmaps, downloadArt(extra, retryKinds.toSet()))
            }
        }
        if (bitmaps.cover == null && bitmaps.screenshot == null &&
            bitmaps.logo == null && bitmaps.fanart == null
        ) {
            recycle(bitmaps)
            skippedNoMatch++
            return
        }
        if (bitmaps.logo == null && bitmaps.title.isNotBlank()) {
            textLogoGames++
        }

        try {
            val baseName = packBaseName(rom)
            for (preset in request.presets) {
                for (size in request.sizes) {
                    val dir = outputDir(root, preset, size, system) ?: run {
                        error = GeneratorError.OUTPUT
                        throw IllegalStateException("cannot create output folder")
                    }
                    val existing = dir.findFile("$baseName.png")
                    if (!request.overwrite && existing != null) {
                        skippedExisting++
                        continue
                    }
                    val bitmap = withContext(Dispatchers.Default) {
                        composer.compose(preset, size, bitmaps)
                    }
                    if (bitmap == null) {
                        failedGames++
                        addError("${rom.fileName}: ${preset.folderId}/${size.folderSuffix}")
                        continue
                    }
                    val written = withContext(Dispatchers.IO) {
                        writePng(dir, existing, "$baseName.png", bitmap)
                    }
                    bitmap.recycle()
                    if (written) {
                        generatedFiles++
                    } else {
                        failedGames++
                        addError("${rom.fileName}: cannot write ${preset.folderId}/${size.folderSuffix}")
                    }
                }
            }
        } finally {
            recycle(bitmaps)
        }
    }

    private suspend fun scrape(
        scraper: Scraper,
        rom: Rom,
        isNeo: Boolean,
    ): ScrapedArt? {
        val candidates = runCatching {
            withContext(Dispatchers.IO) {
                scraper.searchCandidates(rom.system, rom.name)
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            addError("${rom.fileName}: ${it.message.orEmpty()}")
            emptyList()
        }
        if (isNeo) delay(neoDelayMs)
        val candidate = candidates.firstOrNull() ?: return null
        val result = runCatching {
            withContext(Dispatchers.IO) {
                scraper.fetchFor(rom.system, candidate.id)
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            addError("${rom.fileName}: ${it.message.orEmpty()}")
            null
        }
        if (isNeo) delay(neoDelayMs)
        val scraped = result?.toScraped()?.let {
            if (it.gameName.isBlank()) it.copy(gameName = rom.name) else it
        }
        android.util.Log.d(
            "Generator",
            "scrape ${scraper.id} ${rom.fileName}: " +
                "cover=${scraped?.coverUrl != null} shot=${scraped?.screenshotUrl != null} " +
                "logo=${scraped?.logoUrl != null} fanart=${scraped?.fanartUrl != null} " +
                "assets=${result?.assets?.joinToString { it.kind }.orEmpty()}",
        )
        return scraped
    }

    private fun MatchResult.toScraped(): ScrapedArt {
        var cover: String? = null
        var screenshot: String? = null
        var logo: String? = null
        var fanart: String? = null
        var title: String? = null
        for (asset in assets) {
            when (asset.kind) {
                "covers" -> if (cover == null) cover = asset.url
                "screenshots" -> if (screenshot == null) screenshot = asset.url
                "marquees" -> if (logo == null) logo = asset.url
                "fanart" -> if (fanart == null) fanart = asset.url
                "titlescreens" -> if (title == null) title = asset.url
            }
        }
        if (screenshot == null) screenshot = title
        return ScrapedArt(
            gameName = gameName,
            metadata = metadata,
            coverUrl = cover,
            screenshotUrl = screenshot,
            logoUrl = logo,
            fanartUrl = fanart,
            titleUrl = title,
        )
    }

    private fun merge(first: ScrapedArt?, second: ScrapedArt?): ScrapedArt? {
        if (first == null) return second
        if (second == null) return first
        return first.copy(
            coverUrl = first.coverUrl ?: second.coverUrl,
            screenshotUrl = first.screenshotUrl ?: second.screenshotUrl,
            logoUrl = first.logoUrl ?: second.logoUrl,
            fanartUrl = first.fanartUrl ?: second.fanartUrl,
            titleUrl = first.titleUrl ?: second.titleUrl,
        )
    }

    private fun missing(art: ScrapedArt, needed: Set<String>): Boolean =
        ("cover" in needed && art.coverUrl == null) ||
            ("screenshot" in needed && art.screenshotUrl == null) ||
            ("logo" in needed && art.logoUrl == null) ||
            ("fanart" in needed && art.fanartUrl == null)

    private fun bitmapFor(art: GeneratorArt, kind: String): Bitmap? = when (kind) {
        "cover" -> art.cover
        "screenshot" -> art.screenshot
        "logo" -> art.logo
        "fanart" -> art.fanart
        else -> null
    }

    private fun missingBitmaps(art: GeneratorArt, needed: Set<String>): Boolean =
        needed.any { bitmapFor(art, it) == null }

    private fun mergeBitmaps(first: GeneratorArt, second: GeneratorArt): GeneratorArt =
        GeneratorArt(
            cover = first.cover ?: second.cover,
            screenshot = first.screenshot ?: second.screenshot,
            logo = first.logo ?: second.logo,
            fanart = first.fanart ?: second.fanart,
            title = first.title.ifBlank { second.title },
        )

    private fun neededKinds(presets: List<GeneratorPreset>): Set<String> {
        val kinds = mutableSetOf("cover", "logo")
        if (presets.any { it == GeneratorPreset.CLASSIC || it == GeneratorPreset.HERO ||
                it == GeneratorPreset.MINIMAL }
        ) {
            kinds.add("screenshot")
        }
        if (presets.any { it == GeneratorPreset.HERO }) kinds.add("fanart")
        return kinds
    }

    private suspend fun downloadArt(art: ScrapedArt, needed: Set<String>): GeneratorArt {
        val jobs = mutableListOf<Pair<String, String>>()
        if ("cover" in needed) art.coverUrl?.let { jobs += "cover" to it }
        if ("screenshot" in needed) art.screenshotUrl?.let { jobs += "screenshot" to it }
        if ("logo" in needed) art.logoUrl?.let { jobs += "logo" to it }
        if ("fanart" in needed) art.fanartUrl?.let { jobs += "fanart" to it }

        val loaded = coroutineScope {
            jobs.map { (kind, url) ->
                async { kind to download(url) }
            }.awaitAll().toMap()
        }
        android.util.Log.d(
            "Generator",
            "downloadArt: " + loaded.entries.joinToString { "${it.key}=${it.value != null}" },
        )
        return GeneratorArt(
            cover = loaded["cover"],
            screenshot = loaded["screenshot"],
            logo = loaded["logo"],
            fanart = loaded["fanart"],
            title = art.gameName,
        )
    }

    private suspend fun download(url: String): Bitmap? = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            try {
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                response.use {
                    if (!it.isSuccessful) return@use
                    val body = it.body ?: return@use
                    val bytes = body.bytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                        sample *= 2
                    }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (decoded != null) return@withContext decoded
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Generator", "download failed (try ${attempt + 1}): $url", e)
            }
            if (attempt == 0) delay(350)
        }
        null
    }

    private fun outputDir(
        root: DocumentFile,
        preset: GeneratorPreset,
        size: GeneratorSize,
        system: String,
    ): DocumentFile? {
        val presetName = "${preset.folderId}-${size.folderSuffix}"
        val key = "$presetName/$system"
        dirCache[key]?.let { if (it.isDirectory) return it }
        val presetDir = ensureDir(root, presetName) ?: return null
        val systemDir = ensureDir(presetDir, system) ?: return null
        dirCache[key] = systemDir
        return systemDir
    }

    private fun ensureDir(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.let { if (it.isDirectory) return it }
        return runCatching { parent.createDirectory(name) }.getOrNull()
    }

    private fun writePng(
        dir: DocumentFile,
        existing: DocumentFile?,
        name: String,
        bitmap: Bitmap,
    ): Boolean {
        val target = existing ?: runCatching {
            dir.createFile("image/png", name)
        }.getOrNull() ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } == true
        }.getOrDefault(false)
    }

    private fun packBaseName(rom: Rom): String {
        val raw = rom.fileName.substringBeforeLast('.')
        val safe = raw
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .trimEnd('.')
            .take(120)
        return safe.ifBlank {
            rom.name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
                .ifBlank { "game" }
        }
    }

    private fun recycle(art: GeneratorArt) {
        art.cover?.recycle()
        art.screenshot?.recycle()
        art.logo?.recycle()
        art.fanart?.recycle()
    }

    private fun addError(message: String) {
        if (errors.size < 20) errors += message.take(180)
    }
}
