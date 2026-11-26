package com.ark.kover.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ark.kover.R
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LauncherDetailViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings

    fun saveDir(launcherId: String, uri: String) {
        viewModelScope.launch {
            settingsRepo.update {
                when (launcherId) {
                    "neostation" -> it.copy(neostationDirUri = uri)
                    "custom" -> it.copy(customDirUri = uri)
                    else -> it.copy(esdeDirUri = uri)
                }
            }
        }
    }

    fun autoDetectNeoStation() {
        val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "NeoStation")
        if (dir.isDirectory) saveDir("neostation", "file://${dir.absolutePath}")
    }
}

@Composable
fun LauncherDetailScreen(
    launcherId: String,
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: LauncherDetailViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current
    val currentDir = when (launcherId) {
        "neostation" -> settings.neostationDirUri
        "custom" -> settings.customDirUri
        else -> settings.esdeDirUri
    }

    val esdeDirPicker = rememberLauncherForActivityResult(
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
            viewModel.saveDir(launcherId, uri.toString())
        }
    }

    val title = when (launcherId) {
        "es-de" -> "ES-DE"
        "neostation" -> "NeoStation"
        "custom" -> "Custom"
        "cocoon" -> "Cocoon"
        "daijisho" -> "Daijisho"
        "iisu" -> "iiSU"
        "emudeck" -> "EmuDeck"
        else -> stringResource(R.string.nav_launcher)
    }

    AppScaffold(
        selectedTab = KoverTab.Launcher,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(stringResource(R.string.nav_launcher), title),
        onBack = onBack,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val importGuide = when (launcherId) {
            "cocoon" -> R.string.cocoon_info_body to R.string.cocoon_howto
            "daijisho" -> R.string.daijisho_info_body to R.string.daijisho_howto
            "iisu" -> R.string.iisu_info_body to R.string.iisu_howto
            else -> null
        }
        if (importGuide != null) {
            Text(
                text = stringResource(importGuide.first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionTitle(
                text = stringResource(R.string.launcher_howto),
                modifier = Modifier.padding(top = Dimens.Gap6),
            )
            Text(
                text = stringResource(importGuide.second),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppScaffold
        }
        if (launcherId == "custom") {
            Text(
                text = stringResource(R.string.custom_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionTitle(
            text = stringResource(
                if (launcherId == "neostation" || launcherId == "custom") {
                    R.string.launcher_data_dir
                } else {
                    R.string.launcher_dir
                },
            ),
        )
        Text(
            text = currentDir
                .takeIf { it.isNotBlank() }
                ?.let { com.ark.kover.core.launcher.EsDePaths.prettyPath(Uri.parse(it)) }
                ?: stringResource(R.string.launcher_not_set),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
        if (launcherId == "neostation") {
            Text(
                text = stringResource(R.string.neostation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionRow(
                title = stringResource(R.string.neostation_autodetect),
                subtitle = "/storage/emulated/0/NeoStation",
                onClick = { viewModel.autoDetectNeoStation() },
            )
        }
        ActionRow(
            title = stringResource(
                if (launcherId == "neostation" || launcherId == "custom") {
                    R.string.launcher_pick_data_dir
                } else {
                    R.string.launcher_pick_dir
                },
            ),
            onClick = { esdeDirPicker.launch(null) },
        )
    }
}
