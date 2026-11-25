package com.ark.kover.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ark.kover.R
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.settings.isScraperEnabled
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScraperListViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.update { current ->
                val ids = current.activeScrapers
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toMutableSet()
                if (enabled) ids.add(id) else ids.remove(id)
                current.copy(activeScrapers = ids.joinToString(","))
            }
        }
    }
}

@Composable
fun ScraperListScreen(
    onScraperClick: (String) -> Unit,
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: ScraperListViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    AppScaffold(
        selectedTab = KoverTab.Scrapers,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(stringResource(R.string.nav_scrapers)),
        onBack = onBack,
    ) {
        SectionTitle(text = stringResource(R.string.scrapers_title))
        Text(
            text = stringResource(R.string.scrapers_toggle_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ScraperRow(
            title = "NeoAssets",
            subtitle = stringResource(R.string.about_neoassets_desc),
            enabled = settings.isScraperEnabled("neoassets"),
            configured = settings.neoAssetsBearerKey.trim().startsWith("ns_"),
            onToggle = { viewModel.setEnabled("neoassets", it) },
            onClick = { onScraperClick("neoassets") },
        )
        ScraperRow(
            title = "SteamGridDB",
            subtitle = stringResource(R.string.about_sgdb_desc),
            enabled = settings.isScraperEnabled("steamgriddb"),
            configured = settings.steamGridDbToken.isNotBlank(),
            onToggle = { viewModel.setEnabled("steamgriddb", it) },
            onClick = { onScraperClick("steamgriddb") },
        )
        ScraperRow(
            title = "libretro-thumbnails",
            subtitle = stringResource(R.string.libretro_desc),
            enabled = settings.isScraperEnabled("libretro"),
            configured = true,
            onToggle = { viewModel.setEnabled("libretro", it) },
            onClick = { onScraperClick("libretro") },
        )
        ScraperRow(
            title = "Steam Store",
            subtitle = stringResource(R.string.steam_desc),
            enabled = settings.isScraperEnabled("steam"),
            configured = true,
            onToggle = { viewModel.setEnabled("steam", it) },
            onClick = { onScraperClick("steam") },
        )
        ScraperRow(
            title = "RAWG",
            subtitle = stringResource(R.string.rawg_desc),
            enabled = settings.isScraperEnabled("rawg"),
            configured = settings.rawgApiKey.isNotBlank(),
            onToggle = { viewModel.setEnabled("rawg", it) },
            onClick = { onScraperClick("rawg") },
        )

        SectionTitle(
            text = stringResource(R.string.scrapers_soon_section),
            modifier = Modifier.padding(top = Dimens.Gap6),
        )
        SoonRow("ScreenScraper", stringResource(R.string.about_screenscraper_desc))
    }
}

@Composable
private fun ScraperRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    configured: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = { onToggle(!enabled) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (focused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(
                            if (configured) R.string.scrapers_configured
                            else R.string.scrapers_not_configured,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (configured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = null,
                modifier = Modifier.width(44.dp),
            )
            FocusableCard(
                onClick = onClick,
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { buttonFocused ->
                Text(
                    text = stringResource(R.string.scrapers_configure),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (buttonFocused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SoonRow(title: String, subtitle: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.scrapers_soon),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
