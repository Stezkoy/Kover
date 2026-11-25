package com.ark.kover.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ark.kover.R
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LauncherListViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings
}

@Composable
fun LauncherListScreen(
    onLauncherClick: (String) -> Unit,
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: LauncherListViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    AppScaffold(
        selectedTab = KoverTab.Launcher,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(stringResource(R.string.nav_launcher)),
        onBack = onBack,
    ) {
        SectionTitle(text = stringResource(R.string.launcher_title))
        Text(
            text = stringResource(R.string.launcher_hint),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow(
            title = "ES-DE",
            subtitle = stringResource(R.string.launcher_dir),
            trailing = if (settings.esdeDirUri.isNotBlank()) {
                stringResource(R.string.scrapers_configured)
            } else {
                stringResource(R.string.launcher_not_set)
            },
            onClick = { onLauncherClick("es-de") },
        )
        ActionRow(
            title = "NeoStation",
            subtitle = stringResource(R.string.neostation_subtitle),
            trailing = if (settings.neostationDirUri.isNotBlank()) {
                stringResource(R.string.scrapers_configured)
            } else {
                stringResource(R.string.launcher_not_set)
            },
            onClick = { onLauncherClick("neostation") },
        )
        ActionRow(
            title = "Custom",
            subtitle = stringResource(R.string.custom_subtitle),
            trailing = if (settings.customDirUri.isNotBlank()) {
                stringResource(R.string.scrapers_configured)
            } else {
                stringResource(R.string.launcher_not_set)
            },
            onClick = { onLauncherClick("custom") },
        )
        ActionRow(
            title = "Cocoon",
            subtitle = stringResource(R.string.cocoon_subtitle),
            trailing = stringResource(R.string.cocoon_via_esde),
            onClick = { onLauncherClick("cocoon") },
        )
    }
}
