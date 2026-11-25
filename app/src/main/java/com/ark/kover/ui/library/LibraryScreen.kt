package com.ark.kover.ui.library

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ark.kover.R
import com.ark.kover.core.library.LibraryRepository
import com.ark.kover.core.model.System
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.settings.launcherLabel
import com.ark.kover.core.settings.launcherDirUri
import com.ark.kover.ui.common.EsDeSlots
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.EmptyState
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.components.rememberInitialFocus
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    val repo: LibraryRepository,
    val artRepo: com.ark.kover.core.library.SystemArtRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings

    private var scanJob: kotlinx.coroutines.Job? = null

    fun scan(resolver: ContentResolver, uri: Uri) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch { repo.scan(resolver, uri) }
    }

    /** Switches the launcher scope and drops cached covers (they differ per launcher). */
    fun setLauncher(type: String) {
        viewModelScope.launch {
            val neoDir = java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "NeoStation",
            )
            settingsRepo.update { current ->
                val dir = when {
                    type != "neostation" -> current.neostationDirUri
                    current.neostationDirUri.isNotBlank() -> current.neostationDirUri
                    neoDir.isDirectory -> "file://${neoDir.absolutePath}"
                    else -> ""
                }
                current.copy(launcherType = type, neostationDirUri = dir)
            }
            repo.clearCovers()
        }
    }
}

@Composable
fun LibraryScreen(
    onSystemClick: (String) -> Unit,
    onTabSelected: (KoverTab) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val repo = viewModel.repo
    val romDirUri = settings.romDirUri
    val firstFocus = rememberInitialFocus()
    val launcher = settings.launcherType
    val extraSystemIds = EsDeSlots.extrasFor(launcher)
    var showCocoonInfo by remember { mutableStateOf(false) }

    LaunchedEffect(romDirUri, launcher) {
        if (romDirUri.isNotBlank()) {
            // The library differs per launcher (NeoStation adds Android games), so
            // start from a clean state and restore that launcher's own cache.
            repo.clear()
            val cached = repo.loadCache(romDirUri, launcher)
            val stale = java.lang.System.currentTimeMillis() - repo.lastScanAt > 24L * 60 * 60 * 1000
            if (!cached || stale) {
                viewModel.scan(context.contentResolver, Uri.parse(romDirUri))
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.artRepo.ensureLoaded()
    }

    val platforms = repo.systems.filter {
        it.id !in EsDeSlots.nonRomSystems && it.id !in extraSystemIds
    }
    val extras = repo.systems.filter { it.id in extraSystemIds }

    AppScaffold(
        selectedTab = KoverTab.Library,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(settings.launcherLabel, stringResource(R.string.nav_library)),
        scrollable = false,
    ) {
        // Launcher scope: the library belongs to the selected launcher.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4),
        ) {
            LauncherChip(
                label = "ES-DE",
                selected = launcher == "es-de",
                onClick = { viewModel.setLauncher("es-de") },
                focusRequester = firstFocus,
            )
            LauncherChip(
                label = "NeoStation",
                selected = launcher == "neostation",
                onClick = { viewModel.setLauncher("neostation") },
            )
            LauncherChip(
                label = "Custom",
                selected = launcher == "custom",
                onClick = { viewModel.setLauncher("custom") },
            )
            LauncherChip(
                label = "Cocoon",
                selected = false,
                onClick = { showCocoonInfo = true },
            )
            if (repo.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.library_scanning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showCocoonInfo) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showCocoonInfo = false },
                title = { Text(stringResource(R.string.cocoon_info_title)) },
                text = { Text(stringResource(R.string.cocoon_info_body)) },
                confirmButton = {
                    FocusableCard(
                        onClick = { showCocoonInfo = false },
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    ) { focused ->
                        Text(
                            text = stringResource(R.string.common_close),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (focused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        }

        when {
            launcher == "neostation" && settings.launcherDirUri.isBlank() -> {
                EmptyState(
                    title = "NeoStation",
                    body = stringResource(R.string.neostation_no_folder),
                    action = {
                        ActionRow(
                            title = stringResource(R.string.common_scan),
                            onClick = { viewModel.setLauncher("neostation") },
                        )
                    },
                )
            }

            launcher == "custom" && settings.launcherDirUri.isBlank() -> {
                EmptyState(
                    title = "Custom",
                    body = stringResource(R.string.custom_no_folder),
                    action = {
                        ActionRow(
                            title = stringResource(R.string.nav_launcher),
                            onClick = { onTabSelected(KoverTab.Launcher) },
                        )
                    },
                )
            }

            romDirUri.isBlank() -> {
                EmptyState(
                    title = stringResource(R.string.library_no_folder_title),
                    body = stringResource(R.string.library_no_folder_body),
                    action = {
                        ActionRow(
                            title = stringResource(R.string.library_open_settings),
                            onClick = { onTabSelected(KoverTab.Settings) },
                        )
                    },
                )
            }

            repo.systems.isEmpty() && !repo.isScanning -> {
                EmptyState(
                    title = stringResource(R.string.library_empty_title),
                    body = stringResource(R.string.library_empty_body),
                    action = {
                        ActionRow(
                            title = stringResource(R.string.common_scan),
                            onClick = { viewModel.scan(context.contentResolver, Uri.parse(romDirUri)) },
                        )
                    },
                )
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = Dimens.Gap4),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8),
                        ) {
                            SectionTitle(
                                text = stringResource(R.string.library_systems) +
                                    if (platforms.sumOf { it.romCount } + extras.sumOf { it.romCount } > 0) {
                                        "  ·  " + stringResource(
                                            R.string.library_roms_total,
                                            platforms.sumOf { it.romCount } + extras.sumOf { it.romCount },
                                        )
                                    } else "",
                                modifier = Modifier.weight(1f),
                            )
                            FocusableCard(
                                onClick = { viewModel.scan(context.contentResolver, Uri.parse(romDirUri)) },
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) { focused ->
                                Text(
                                    text = stringResource(R.string.library_rescan),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (focused) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(platforms, key = { it.id }) { system ->
                        SystemTile(
                            system = system,
                            artUrl = viewModel.artRepo.urlFor(system.id),
                            onClick = { onSystemClick(system.id) },
                        )
                    }

                    if (extras.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionTitle(
                                text = stringResource(R.string.library_section_apps),
                                modifier = Modifier.padding(top = Dimens.Gap6),
                            )
                        }
                        items(extras, key = { it.id }) { system ->
                            SystemTile(
                                system = system,
                                artUrl = viewModel.artRepo.urlFor(system.id),
                                onClick = { onSystemClick(system.id) },
                            )
                        }
                    }
                }
                if (repo.lastScanFailed) {
                    Text(
                        text = stringResource(R.string.library_scan_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun LauncherChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    FocusableCard(
        onClick = onClick,
        selected = selected,
        modifier = Modifier.height(26.dp),
        focusRequester = focusRequester,
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

@Composable
private fun SystemTile(system: System, artUrl: String?, onClick: () -> Unit) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        contentPadding = PaddingValues(0.dp),
    ) { focused ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            if (artUrl != null) {
                coil.compose.AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
                )
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = system.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        artUrl != null -> androidx.compose.ui.graphics.Color.White
                        focused -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                Text(
                    text = stringResource(R.string.library_roms_total, system.romCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (artUrl != null) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
