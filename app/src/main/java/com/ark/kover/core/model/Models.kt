package com.ark.kover.core.model

import kotlinx.serialization.Serializable

/**
 * A ROM file discovered in the user's ROM directory.
 */
@Serializable
data class Rom(
    val id: String,              // hash of relative path
    val name: String,            // normalized game name (without extension/region/tags)
    val fileName: String,        // original file name
    val relativePath: String,    // path relative to ROM root, e.g. "switch/Mario Kart 8.nsp"
    val system: String,          // system/platform id, e.g. "switch", "snes", "wiiu"
    val extension: String,       // file extension: nsp, sfc, zip, ...
    val size: Long = 0L,
    val md5: String? = null,     // computed lazily, cached in Room
)

/**
 * A game system/platform (folder of ROMs).
 */
@Serializable
data class System(
    val id: String,              // folder name: "switch", "snes", "wiiu"
    val name: String,            // display name: "Nintendo Switch"
    val romCount: Int = 0,
)

/**
 * Metadata for a game (mirrors ES-DE gamelist.xml fields).
 */
data class Metadata(
    val name: String = "",
    val desc: String = "",
    /** Localized descriptions shipped by the source (lang code -> text); never written to gamelist.xml. */
    val descByLang: Map<String, String> = emptyMap(),
    val rating: Float = 0f,
    val releaseDate: String = "",   // "20210923T000000"
    val developer: String = "",
    val publisher: String = "",
    val genre: String = "",
    val players: String = "",
)

/**
 * A media asset (image, video, PDF).
 */
data class Asset(
    val kind: String,            // cover, fanart, screenshot, video, logo, manual, ...
    val url: String,             // remote URL or local path
    val mime: String = "",
    val size: Long = 0L,
    val lang: String = "",       // for descriptions: "en", "ru", ...
)

/**
 * A lightweight candidate returned by a search, before the user picks one.
 */
data class GameCandidate(
    val id: String,              // unique per result (remote id)
    val name: String,
    val source: String,          // "steamgriddb", "neoassets"
    val system: String,
    val matchedBy: String = "name",
    val releaseDate: String = "",
    val confidence: Float = 1f,
)

/**
 * Result of matching a ROM to a known game via a scraper.
 */
data class MatchResult(
    val romId: String,
    val gameName: String,
    val metadata: Metadata,
    val assets: List<Asset>,
    val source: String,          // "steamgriddb", "neoassets", ...
    val confidence: Float = 1f,  // 0..1
    val matchedBy: String = "name", // "name" or "hash"
)

/**
 * Language code for translations.
 */
enum class Language(val code: String, val label: String) {
    EN("en", "English"),
    RU("ru", "Русский"),
    DE("de", "Deutsch"),
    ES("es", "Español"),
    FR("fr", "Français"),
    JP("ja", "日本語"),
    KO("ko", "한국어"),
    ZH("zh", "简体中文"),
}
