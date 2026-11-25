package com.ark.kover.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ark.kover.R

/** ES-DE artwork folders Kover writes to, in display order. */
object EsDeSlots {
    const val COVERS = "covers"
    const val VIDEOS = "videos"
    const val MANUALS = "manuals"
    const val SCREENSHOTS = "screenshots"
    const val MIXIMAGES = "miximages"

    val all: List<String> = listOf(
        "covers", "backcovers", "fanart", "screenshots", "videos",
        "marquees", "titlescreens", "manuals", "3dboxes", "physicalmedia", "miximages",
    )

    /** Extra categories offered by scrapers that have no ES-DE folder of their own. */
    val sourceOnly: List<String> = listOf("icons")

    /** Slots shown in the artwork editor (real folders + scraper-only categories). */
    val editorKinds: List<String> = all + sourceOnly

    /** Systems that hold apps/emulators instead of games (ES-DE "systems"). */
    val nonRomSystems: Set<String> = setOf(
        "androidapps", "androidgames", "emulators", "desktop", "tools",
    )

    /** Slots NeoStation can display: box2d, fanarts, screenshots, videos, wheels. */
    val neostationKinds: List<String> = listOf(
        "covers", "fanart", "screenshots", "videos", "marquees",
    )

    /** Extra (non-ROM) sections shown for a launcher. */
    fun extrasFor(launcherType: String): Set<String> = when (launcherType) {
        "neostation" -> setOf("android")
        "custom" -> emptySet()
        else -> nonRomSystems
    }

    /** Slots shown on the ROM card for a launcher (Android games have four art kinds). */
    fun kindsFor(launcherType: String, system: String? = null): List<String> = when {
        system == "android" -> neostationKinds.filter { it != "videos" }
        launcherType == "neostation" -> neostationKinds
        else -> all
    }

    /** Slots shown in the artwork editor for a launcher. */
    fun editorKindsFor(launcherType: String, system: String? = null): List<String> = when {
        system == "android" -> neostationKinds.filter { it != "videos" }
        launcherType == "neostation" -> neostationKinds
        else -> editorKinds
    }
}

@Composable
fun slotLabel(kind: String): String = stringResource(
    when (kind) {
        "covers" -> R.string.slot_covers
        "backcovers" -> R.string.slot_backcovers
        "fanart" -> R.string.slot_fanart
        "screenshots" -> R.string.slot_screenshots
        "videos" -> R.string.slot_videos
        "marquees" -> R.string.slot_marquees
        "titlescreens" -> R.string.slot_titlescreens
        "manuals" -> R.string.slot_manuals
        "3dboxes" -> R.string.slot_3dboxes
        "physicalmedia" -> R.string.slot_physicalmedia
        "miximages" -> R.string.slot_miximages
        "icons" -> R.string.slot_icons
        else -> R.string.common_none
    },
)
