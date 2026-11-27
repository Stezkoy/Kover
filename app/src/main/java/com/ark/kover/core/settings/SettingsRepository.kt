package com.ark.kover.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings stored in DataStore.
 */
data class AppSettings(
    // ROM directory
    val romDirUri: String = "",
    // Launcher
    val launcherType: String = "es-de",   // "es-de", "neostation", "custom"
    val esdeDirUri: String = "",
    val neostationDirUri: String = "",
    val customDirUri: String = "",
    // Language
    val uiLanguage: String = "",
    val themeMode: String = "system",
    val themePreset: String = "system",
    val translateTarget: String = "ru",
    // Backup
    val backupOnChange: Boolean = false,
    // Scraper credentials
    // App-level keys (NeoAssets X-Client-Id/Secret, ScreenScraper dev id) come from
    // BuildConfig — see app/build.gradle.kts and local.properties (gitignored).
    val neoAssetsBearerKey: String = "",
    val steamGridDbToken: String = "",
    val rawgApiKey: String = "",
    // ScreenScraper
    val ssDevId: String = "",
    val ssDevPassword: String = "",
    val ssUser: String = "",
    val ssPassword: String = "",
    // Updates
    val updateCheckEnabled: Boolean = true,
    val lastUpdateCheckAt: Long = 0L,
    // Active scrapers (comma-separated ids); disabled scrapers are hidden in the editors
    val activeScrapers: String = "neoassets,steamgriddb,libretro,steam",
    val generatorOutputUri: String = "",
)

/** True when the scraper is enabled in settings. */
fun AppSettings.isScraperEnabled(id: String): Boolean =
    activeScrapers.split(',').any { it.trim() == id }

/** Folder with the selected launcher's data (gamelists, media, database). */
val AppSettings.launcherDirUri: String
    get() = when (launcherType) {
        "neostation" -> neostationDirUri
        "custom" -> customDirUri
        else -> esdeDirUri
    }

/** Human-readable launcher name for breadcrumbs. */
val AppSettings.launcherLabel: String
    get() = when (launcherType) {
        "neostation" -> "NeoStation"
        "custom" -> "Custom"
        else -> "ES-DE"
    }

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val naBearer = prefs[KEY_NA_BEARER] ?: ""
        val sgdbToken = prefs[KEY_SGDB_TOKEN] ?: ""
        // Legacy/placeholder keys (e.g. "DPHOkpz...") are invalid and cause HTTP 401.
        val validBearer = naBearer.trim().takeIf { it.startsWith("ns_") } ?: ""

        AppSettings(
            romDirUri = prefs[KEY_ROM_DIR] ?: "",
            launcherType = prefs[KEY_LAUNCHER] ?: "es-de",
            esdeDirUri = prefs[KEY_ESDE_DIR] ?: "",
            neostationDirUri = prefs[KEY_NEO_DIR] ?: "",
            customDirUri = prefs[KEY_CUSTOM_DIR] ?: "",
            uiLanguage = prefs[KEY_UI_LANG] ?: "",
            themeMode = prefs[KEY_THEME_MODE] ?: "system",
            themePreset = prefs[KEY_THEME_PRESET] ?: "system",
            translateTarget = prefs[KEY_TRANSLATE_TARGET] ?: "ru",
            backupOnChange = prefs[KEY_BACKUP] ?: false,
            neoAssetsBearerKey = validBearer,
            steamGridDbToken = sgdbToken,
            rawgApiKey = prefs[KEY_RAWG_KEY] ?: "",
            ssDevId = prefs[KEY_SS_DEV_ID] ?: "",
            ssDevPassword = prefs[KEY_SS_DEV_PASSWORD] ?: "",
            ssUser = prefs[KEY_SS_USER] ?: "",
            ssPassword = prefs[KEY_SS_PASSWORD] ?: "",
            updateCheckEnabled = prefs[KEY_UPDATE_CHECK] ?: true,
            lastUpdateCheckAt = prefs[KEY_LAST_UPDATE_CHECK] ?: 0L,
            activeScrapers = prefs[KEY_ACTIVE_SCRAPERS] ?: "neoassets,steamgriddb,libretro,steam",
            generatorOutputUri = prefs[KEY_GENERATOR_OUTPUT] ?: "",
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = AppSettings(
                romDirUri = prefs[KEY_ROM_DIR] ?: "",
                launcherType = prefs[KEY_LAUNCHER] ?: "es-de",
                esdeDirUri = prefs[KEY_ESDE_DIR] ?: "",
                neostationDirUri = prefs[KEY_NEO_DIR] ?: "",
                customDirUri = prefs[KEY_CUSTOM_DIR] ?: "",
                uiLanguage = prefs[KEY_UI_LANG] ?: "",
                themeMode = prefs[KEY_THEME_MODE] ?: "system",
                themePreset = prefs[KEY_THEME_PRESET] ?: "system",
                translateTarget = prefs[KEY_TRANSLATE_TARGET] ?: "ru",
                backupOnChange = prefs[KEY_BACKUP] ?: false,
                neoAssetsBearerKey = (prefs[KEY_NA_BEARER] ?: "").trim()
                    .takeIf { it.startsWith("ns_") } ?: "",
                steamGridDbToken = prefs[KEY_SGDB_TOKEN] ?: "",
                rawgApiKey = prefs[KEY_RAWG_KEY] ?: "",
                ssDevId = prefs[KEY_SS_DEV_ID] ?: "",
                ssDevPassword = prefs[KEY_SS_DEV_PASSWORD] ?: "",
                ssUser = prefs[KEY_SS_USER] ?: "",
                ssPassword = prefs[KEY_SS_PASSWORD] ?: "",
                updateCheckEnabled = prefs[KEY_UPDATE_CHECK] ?: true,
                lastUpdateCheckAt = prefs[KEY_LAST_UPDATE_CHECK] ?: 0L,
                activeScrapers = prefs[KEY_ACTIVE_SCRAPERS] ?: "neoassets,steamgriddb,libretro,steam",
                generatorOutputUri = prefs[KEY_GENERATOR_OUTPUT] ?: "",
            )
            val updated = transform(current)
            prefs[KEY_ROM_DIR] = updated.romDirUri
            prefs[KEY_LAUNCHER] = updated.launcherType
            prefs[KEY_ESDE_DIR] = updated.esdeDirUri
            prefs[KEY_NEO_DIR] = updated.neostationDirUri
            prefs[KEY_CUSTOM_DIR] = updated.customDirUri
            prefs[KEY_UI_LANG] = updated.uiLanguage
            prefs[KEY_THEME_MODE] = updated.themeMode
            prefs[KEY_THEME_PRESET] = updated.themePreset
            prefs[KEY_TRANSLATE_TARGET] = updated.translateTarget
            prefs[KEY_BACKUP] = updated.backupOnChange
            prefs[KEY_NA_BEARER] = updated.neoAssetsBearerKey
            prefs[KEY_SGDB_TOKEN] = updated.steamGridDbToken
            prefs[KEY_RAWG_KEY] = updated.rawgApiKey
            prefs[KEY_SS_DEV_ID] = updated.ssDevId
            prefs[KEY_SS_DEV_PASSWORD] = updated.ssDevPassword
            prefs[KEY_SS_USER] = updated.ssUser
            prefs[KEY_SS_PASSWORD] = updated.ssPassword
            prefs[KEY_UPDATE_CHECK] = updated.updateCheckEnabled
            prefs[KEY_LAST_UPDATE_CHECK] = updated.lastUpdateCheckAt
            prefs[KEY_ACTIVE_SCRAPERS] = updated.activeScrapers
            prefs[KEY_GENERATOR_OUTPUT] = updated.generatorOutputUri
        }
    }

    companion object {
        private val KEY_ROM_DIR = stringPreferencesKey("rom_dir_uri")
        private val KEY_LAUNCHER = stringPreferencesKey("launcher_type")
        private val KEY_ESDE_DIR = stringPreferencesKey("esde_dir_uri")
        private val KEY_NEO_DIR = stringPreferencesKey("neostation_dir_uri")
        private val KEY_CUSTOM_DIR = stringPreferencesKey("custom_dir_uri")
        private val KEY_UI_LANG = stringPreferencesKey("ui_language")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")
        private val KEY_TRANSLATE_TARGET = stringPreferencesKey("translate_target")
        private val KEY_BACKUP = androidx.datastore.preferences.core.booleanPreferencesKey("backup_on_change_v2")
        private val KEY_NA_BEARER = stringPreferencesKey("neoassets_bearer")
        private val KEY_SGDB_TOKEN = stringPreferencesKey("steamgriddb_token")
        private val KEY_RAWG_KEY = stringPreferencesKey("rawg_api_key")
        private val KEY_SS_DEV_ID = stringPreferencesKey("screenscraper_dev_id")
        private val KEY_SS_DEV_PASSWORD = stringPreferencesKey("screenscraper_dev_password")
        private val KEY_SS_USER = stringPreferencesKey("screenscraper_user")
        private val KEY_SS_PASSWORD = stringPreferencesKey("screenscraper_password")
        private val KEY_UPDATE_CHECK = androidx.datastore.preferences.core.booleanPreferencesKey("update_check_enabled")
        private val KEY_LAST_UPDATE_CHECK = androidx.datastore.preferences.core.longPreferencesKey("last_update_check")
        private val KEY_ACTIVE_SCRAPERS = stringPreferencesKey("active_scrapers")
        private val KEY_GENERATOR_OUTPUT = stringPreferencesKey("generator_output_uri")
    }
}
