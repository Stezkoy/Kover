package com.ark.kover.core.generator

import android.net.Uri
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.model.Rom

/** Primary art source for the pack generator. */
enum class GeneratorSource {
    NEOASSETS,
    LIBRETRO,
}

/** One of the four pack layouts. */
enum class GeneratorPreset(val folderId: String) {
    CLASSIC("classic"),
    HERO("hero"),
    ICON_COVER("icon"),
    MINIMAL("minimal"),
}

/** Output canvas. The folder suffix keeps both formats apart in one pack. */
enum class GeneratorSize(val width: Int, val height: Int, val folderSuffix: String) {
    SQUARE(1024, 1024, "1024"),
    MIX(1280, 960, "1280"),
}

/** Everything captured when the user presses Start. */
data class GeneratorRequest(
    val roms: List<Rom>,
    val presets: List<GeneratorPreset>,
    val sizes: List<GeneratorSize>,
    val source: GeneratorSource,
    val fallback: Boolean,
    val outputTreeUri: Uri,
    val overwrite: Boolean,
)

/** Metadata + asset URLs returned by the active scraper. */
data class ScrapedArt(
    val gameName: String,
    val metadata: Metadata,
    val coverUrl: String? = null,
    val screenshotUrl: String? = null,
    val logoUrl: String? = null,
    val fanartUrl: String? = null,
    val titleUrl: String? = null,
) {
    fun hasAny(): Boolean =
        coverUrl != null || screenshotUrl != null || logoUrl != null ||
            fanartUrl != null || titleUrl != null
}

/** Early-stop reasons that the UI translates. */
enum class GeneratorError {
    NETWORK,
    QUOTA,
    OUTPUT,
    NO_ROMS,
}

/** Final counters shown on the generator screen. */
data class GeneratorReport(
    val totalGames: Int = 0,
    val processedGames: Int = 0,
    val generatedFiles: Int = 0,
    val skippedExisting: Int = 0,
    val skippedNoMatch: Int = 0,
    val failedGames: Int = 0,
    /** Games where no source had a logo, so the title was drawn in its place. */
    val textLogoGames: Int = 0,
    val cancelled: Boolean = false,
    val errors: List<String> = emptyList(),
)
