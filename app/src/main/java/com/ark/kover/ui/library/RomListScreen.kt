package com.ark.kover.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ark.kover.R
import com.ark.kover.core.library.LibraryRepository
import com.ark.kover.core.model.Rom
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.settings.launcherLabel
import com.ark.kover.core.settings.launcherDirUri
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.EmptyState
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.components.Thumb
import com.ark.kover.ui.components.rememberInitialFocus
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class RomListViewModel @Inject constructor(
    val repo: LibraryRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings
}

private enum class RomViewMode { Grid, List }

@Composable
fun RomListScreen(
    system: String,
    onRomClick: (Rom) -> Unit,
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: RomListViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val repo = viewModel.repo
    val roms = repo.roms(system)
    val firstFocus = rememberInitialFocus()
    var viewMode by rememberSaveable { mutableStateOf(RomViewMode.Grid) }

    AppScaffold(
        selectedTab = KoverTab.Library,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(
            settings.launcherLabel,
            stringResource(R.string.nav_library),
            repo.systemName(system),
        ),
        onBack = onBack,
        scrollable = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
        ) {
            SectionTitle(
                text = repo.systemName(system) + "  ·  " + stringResource(R.string.library_roms_total, roms.size),
                modifier = Modifier.weight(1f),
            )
            ViewModeChip(
                label = stringResource(R.string.rom_view_grid),
                selected = viewMode == RomViewMode.Grid,
                onClick = { viewMode = RomViewMode.Grid },
            )
            ViewModeChip(
                label = stringResource(R.string.rom_view_list),
                selected = viewMode == RomViewMode.List,
                onClick = { viewMode = RomViewMode.List },
            )
        }

        if (roms.isEmpty()) {
            EmptyState(
                title = repo.systemName(system),
                body = stringResource(R.string.roms_empty),
            )
            return@AppScaffold
        }

        when (viewMode) {
            RomViewMode.Grid -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                gridItems(roms, key = { it.id }) { rom ->
                    RomTile(
                        rom = rom,
                        coverPath = coverPath(rom, repo, settings, context.contentResolver),
                        onClick = { onRomClick(rom) },
                        focusRequester = if (rom.id == roms.first().id) firstFocus else null,
                    )
                }
            }

            RomViewMode.List -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(roms, key = { it.id }) { rom ->
                    RomRow(
                        rom = rom,
                        coverPath = coverPath(rom, repo, settings, context.contentResolver),
                        onClick = { onRomClick(rom) },
                        focusRequester = if (rom.id == roms.first().id) firstFocus else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun coverPath(
    rom: Rom,
    repo: LibraryRepository,
    settings: AppSettings,
    resolver: android.content.ContentResolver,
): String? {
    var cover by remember(rom.id) {
        mutableStateOf(if (repo.isCoverKnown(rom)) repo.cachedCover(rom) else null)
    }
    LaunchedEffect(rom.id, settings.launcherDirUri) {
        if (!repo.isCoverKnown(rom) && settings.launcherDirUri.isNotBlank()) {
            cover = repo.resolveCover(rom, resolver, android.net.Uri.parse(settings.launcherDirUri))
        }
    }
    return cover
}

@Composable
private fun RomTile(
    rom: Rom,
    coverPath: String?,
    onClick: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester?,
) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(142.dp),
        focusRequester = focusRequester,
        contentPadding = PaddingValues(3.dp),
    ) { focused ->
        Column(modifier = Modifier.fillMaxSize()) {
            Thumb(
                model = coverPath,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Text(
                text = rom.name,
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun RomRow(
    rom: Rom,
    coverPath: String?,
    onClick: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester?,
) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        focusRequester = focusRequester,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp),
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8),
        ) {
            Thumb(model = coverPath, modifier = Modifier.width(28.dp).height(38.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rom.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rom.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatSize(rom.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ViewModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = onClick,
        selected = selected,
        modifier = Modifier.height(26.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected || focused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1048576.0)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
}
