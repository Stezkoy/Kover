package com.ark.kover.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ark.kover.BuildConfig
import com.ark.kover.R
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.update.UpdateRepository
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.theme.AppThemePreset
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    val updates: UpdateRepository,
) : ViewModel() {
    val settings = settingsRepo.settings

    fun saveRomDir(uri: String) {
        viewModelScope.launch { settingsRepo.update { it.copy(romDirUri = uri) } }
    }

    fun saveTranslateTarget(lang: String) {
        viewModelScope.launch { settingsRepo.update { it.copy(translateTarget = lang) } }
    }

    fun saveTheme(mode: String) {
        viewModelScope.launch { settingsRepo.update { it.copy(themeMode = mode) } }
    }

    fun saveAccent(id: String) {
        viewModelScope.launch { settingsRepo.update { it.copy(themePreset = id) } }
    }

    fun saveLanguage(tag: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(uiLanguage = tag) }
        }
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.update { it.copy(updateCheckEnabled = enabled) } }
    }

    fun setBackupEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.update { it.copy(backupOnChange = enabled) } }
    }

    fun checkUpdates() {
        viewModelScope.launch { updates.check(silent = false) }
    }

    fun downloadUpdate() {
        viewModelScope.launch { updates.download() }
    }

    fun installUpdate() {
        updates.install()
    }
}

private fun applyLocale(context: Context, tag: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val manager = context.getSystemService(LocaleManager::class.java) ?: return
    manager.applicationLocales =
        if (tag.isBlank()) LocaleList.getEmptyLocaleList()
        else LocaleList.forLanguageTags(tag)
}

@Composable
fun GeneralSettingsScreen(
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: GeneralSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val manageStorageGranted = Environment.isExternalStorageManager()

    val storagePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { }

    val romDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.saveRomDir(uri.toString())
        }
    }

    AppScaffold(
        selectedTab = KoverTab.Settings,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(stringResource(R.string.nav_settings)),
        onBack = onBack,
    ) {
        // ---------------- files ----------------
        if (!manageStorageGranted) {
            SectionTitle(text = stringResource(R.string.settings_storage))
            Text(
                text = stringResource(R.string.settings_storage_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FocusableCard(
                onClick = {
                    val intent = android.content.Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    ).apply { data = Uri.parse("package:${context.packageName}") }
                    runCatching { storagePermission.launch(intent) }
                },
                modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeight),
            ) { focused ->
                Text(
                    text = stringResource(R.string.settings_storage_allow),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        SectionTitle(text = stringResource(R.string.settings_rom_dir))
        Text(
            text = settings.romDirUri
                .takeIf { it.isNotBlank() }
                ?.let { com.ark.kover.core.launcher.EsDePaths.prettyPath(Uri.parse(it)) }
                ?: stringResource(R.string.launcher_not_set),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        ActionRow(
            title = stringResource(R.string.settings_pick_rom_dir),
            onClick = { romDirPicker.launch(null) },
        )

        // ---------------- backups ----------------
        SectionTitle(text = stringResource(R.string.settings_backups))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
        ) {
            Switch(
                checked = settings.backupOnChange,
                onCheckedChange = { viewModel.setBackupEnabled(it) },
            )
            Text(
                text = stringResource(R.string.settings_backups_space_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        // ---------------- appearance ----------------
        SectionTitle(text = stringResource(R.string.settings_theme))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4)) {
            OptionChip(stringResource(R.string.theme_system), settings.themeMode == "system") {
                viewModel.saveTheme("system")
            }
            OptionChip(stringResource(R.string.theme_light), settings.themeMode == "light") {
                viewModel.saveTheme("light")
            }
            OptionChip(stringResource(R.string.theme_dark), settings.themeMode == "dark") {
                viewModel.saveTheme("dark")
            }
        }

        SectionTitle(text = stringResource(R.string.settings_theme_preset))
        AppThemePreset.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4)) {
                row.forEach { preset ->
                    ThemeRow(
                        preset = preset,
                        selected = settings.themePreset == preset.id,
                        onClick = { viewModel.saveAccent(preset.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // ---------------- language ----------------
        SectionTitle(text = stringResource(R.string.settings_language))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4)) {
            val current = settings.uiLanguage
            OptionChip(stringResource(R.string.language_system), current.isBlank()) {
                viewModel.saveLanguage("")
                applyLocale(context, "")
            }
            OptionChip("English", current == "en") {
                viewModel.saveLanguage("en")
                applyLocale(context, "en")
            }
            OptionChip("Русский", current == "ru") {
                viewModel.saveLanguage("ru")
                applyLocale(context, "ru")
            }
            OptionChip("中文", current == "zh") {
                viewModel.saveLanguage("zh")
                applyLocale(context, "zh")
            }
        }

        // ---------------- translation ----------------
        SectionTitle(text = stringResource(R.string.settings_translation))
        Text(
            text = stringResource(R.string.settings_translation_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4)) {
            TranslateLanguages.forEach { (code, label) ->
                OptionChip(label, settings.translateTarget == code) {
                    viewModel.saveTranslateTarget(code)
                }
            }
        }

        // ---------------- updates ----------------
        SectionTitle(text = stringResource(R.string.settings_updates))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
        ) {
            Switch(
                checked = settings.updateCheckEnabled,
                onCheckedChange = { viewModel.setAutoUpdate(it) },
            )
            Text(
                text = stringResource(R.string.updates_auto),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            FocusableCard(
                onClick = { viewModel.checkUpdates() },
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { focused ->
                Text(
                    text = stringResource(R.string.updates_check),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        val latest = viewModel.updates.latest
        val checking = viewModel.updates.isChecking
        Text(
            text = when {
                checking -> stringResource(R.string.updates_checking)
                latest == null || !viewModel.updates.isUpdateAvailable() ->
                    stringResource(R.string.updates_current_only, viewModel.updates.currentVersion)
                else -> stringResource(R.string.updates_current_and_new, viewModel.updates.currentVersion, latest.version)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (viewModel.updates.isUpdateAvailable()) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (viewModel.updates.isUpdateAvailable()) {
            val url = latest?.url.orEmpty()
            if (viewModel.updates.isDownloading) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { viewModel.updates.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.updates_downloading,
                        (viewModel.updates.progress * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (viewModel.updates.downloadedFile != null) {
                ActionRow(
                    title = stringResource(R.string.updates_install),
                    onClick = { viewModel.installUpdate() },
                )
            } else {
                ActionRow(
                    title = stringResource(R.string.updates_download),
                    subtitle = if (latest?.apkUrl == null) stringResource(R.string.updates_no_apk) else null,
                    onClick = { viewModel.downloadUpdate() },
                )
            }
            if (viewModel.updates.downloadError != null) {
                Text(
                    text = stringResource(R.string.updates_download_error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ActionRow(
                title = stringResource(R.string.updates_open),
                onClick = {
                    if (url.isNotBlank()) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    Uri.parse(url),
                                ),
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusableCard(
        onClick = onClick,
        selected = selected,
        modifier = Modifier.height(26.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected || focused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeRow(
    preset: AppThemePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableCard(
        onClick = onClick,
        selected = selected,
        modifier = modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(preset.dot, CircleShape),
            )
            Text(
                text = themePresetLabel(preset),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected || focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun themePresetLabel(preset: AppThemePreset): String = stringResource(
    when (preset) {
        AppThemePreset.SYSTEM -> R.string.theme_system_accent
        AppThemePreset.FOREST -> R.string.theme_forest
        AppThemePreset.RETRO -> R.string.theme_retro
        AppThemePreset.ARCADE -> R.string.theme_arcade
        AppThemePreset.OCEAN -> R.string.theme_ocean
        AppThemePreset.RUBY -> R.string.theme_ruby
        AppThemePreset.NORD -> R.string.theme_nord
        AppThemePreset.MONO -> R.string.theme_mono
    },
)

private val TranslateLanguages = listOf(
    "ru" to "RU",
    "de" to "DE",
    "es" to "ES",
    "fr" to "FR",
    "it" to "IT",
    "pt" to "PT",
    "ja" to "JA",
    "ko" to "KO",
    "zh" to "ZH",
)
