package com.ark.kover.ui.generator

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ark.kover.R
import com.ark.kover.core.generator.GeneratorError
import com.ark.kover.core.generator.GeneratorPreset
import com.ark.kover.core.generator.GeneratorRequest
import com.ark.kover.core.generator.GeneratorService
import com.ark.kover.core.generator.GeneratorSize
import com.ark.kover.core.generator.GeneratorSource
import com.ark.kover.core.generator.RomCatalog
import com.ark.kover.core.launcher.EsDePaths
import com.ark.kover.core.library.LibraryRepository
import com.ark.kover.core.library.SystemArtRepository
import com.ark.kover.core.model.Rom
import com.ark.kover.core.model.System
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.scraper.LibretroThumbnailsScraper
import com.ark.kover.scraper.NeoAssetsScraper
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.FieldLabel
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.KoverButton
import com.ark.kover.ui.components.KoverButtonStyle
import com.ark.kover.ui.components.KoverChip
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.components.rememberInitialFocus
import com.ark.kover.ui.theme.Dimens
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GeneratorViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val catalog: RomCatalog,
    private val neoAssets: NeoAssetsScraper,
    private val libretro: LibretroThumbnailsScraper,
    val artRepo: SystemArtRepository,
    val service: GeneratorService,
) : ViewModel() {
    var romDirUri by mutableStateOf("")
        private set
    var isScanning by mutableStateOf(false)
        private set
    var scanFailed by mutableStateOf(false)
        private set
    var romsBySystem by mutableStateOf<Map<String, List<Rom>>>(emptyMap())
        private set
    var systems by mutableStateOf<List<System>>(emptyList())
        private set
    var selectedSystems by mutableStateOf<Set<String>>(emptySet())
        private set
    var source by mutableStateOf(GeneratorSource.NEOASSETS)
        private set
    var fallback by mutableStateOf(false)
        private set
    var presets by mutableStateOf(setOf(GeneratorPreset.ICON_COVER))
        private set
    var sizes by mutableStateOf(setOf(GeneratorSize.SQUARE))
        private set
    var outputUri by mutableStateOf<Uri?>(null)
        private set
    var overwrite by mutableStateOf(false)
        private set

    private var lastScannedUri = ""

    val settings = settingsRepo.settings

    init {
        viewModelScope.launch { artRepo.ensureLoaded() }
        viewModelScope.launch {
            settingsRepo.settings.collect { settings ->
                if (settings.romDirUri != romDirUri) {
                    romDirUri = settings.romDirUri
                    if (romDirUri.isNotBlank() && romDirUri != lastScannedUri) scan(romDirUri)
                }
                if (outputUri == null && settings.generatorOutputUri.isNotBlank()) {
                    outputUri = Uri.parse(settings.generatorOutputUri)
                }
            }
        }
    }

    private fun primary(): com.ark.kover.core.scraper.Scraper =
        if (source == GeneratorSource.NEOASSETS) neoAssets else libretro

    private fun secondary(): com.ark.kover.core.scraper.Scraper =
        if (source == GeneratorSource.NEOASSETS) libretro else neoAssets

    fun supports(systemId: String): Boolean =
        primary().supportsSystem(systemId) || (fallback && secondary().supportsSystem(systemId))

    val visibleSystems: List<System>
        get() = systems.filter { supports(it.id) }

    fun scan(uri: String = romDirUri) {
        if (uri.isBlank()) return
        lastScannedUri = uri
        viewModelScope.launch {
            isScanning = true
            scanFailed = false
            runCatching { catalog.scan(Uri.parse(uri)) }
                .onSuccess { map ->
                    romsBySystem = map
                    systems = map.entries
                        .map { System(it.key, LibraryRepository.systemDisplayName(it.key), it.value.size) }
                        .sortedBy { it.name.lowercase() }
                    selectedSystems = emptySet()
                }
                .onFailure { scanFailed = true }
            isScanning = false
        }
    }

    fun setOutput(uri: Uri) {
        outputUri = uri
        viewModelScope.launch {
            settingsRepo.update { it.copy(generatorOutputUri = uri.toString()) }
        }
    }

    fun toggleSource(next: GeneratorSource) {
        if (source == next) return
        source = next
        selectedSystems = selectedSystems.intersect(visibleSystems.map { it.id }.toSet())
    }

    fun toggleFallback() {
        fallback = !fallback
        selectedSystems = selectedSystems.intersect(visibleSystems.map { it.id }.toSet())
    }

    fun togglePreset(preset: GeneratorPreset) {
        presets = if (preset in presets) presets - preset else presets + preset
    }

    fun toggleSize(size: GeneratorSize) {
        sizes = if (size in sizes) sizes - size else sizes + size
    }

    fun toggleSystem(systemId: String) {
        selectedSystems = if (systemId in selectedSystems) {
            selectedSystems - systemId
        } else {
            selectedSystems + systemId
        }
    }

    fun selectAllSystems() {
        selectedSystems = visibleSystems.map { it.id }.toSet()
    }

    fun toggleOverwrite() {
        overwrite = !overwrite
    }

    val selectedRoms: List<Rom>
        get() = selectedSystems
            .filter { supports(it) }
            .flatMap { romsBySystem[it].orEmpty() }

    val estimatedFiles: Int
        get() = selectedRoms.size * presets.size * sizes.size

    fun start() {
        val output = outputUri ?: return
        if (service.isRunning) return
        val roms = selectedRoms
        if (roms.isEmpty()) return
        service.start(
            GeneratorRequest(
                roms = roms,
                presets = presets.toList(),
                sizes = sizes.toList(),
                source = source,
                fallback = fallback,
                outputTreeUri = output,
                overwrite = overwrite,
            ),
        )
    }

    fun cancel() = service.cancel()
}

@Composable
fun GeneratorScreen(
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: GeneratorViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current
    val firstFocus = rememberInitialFocus()
    val service = viewModel.service

    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setOutput(uri)
        }
    }

    AppScaffold(
        selectedTab = KoverTab.Generator,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(stringResource(R.string.nav_generator)),
        onBack = onBack,
    ) {
        FieldLabel(
            text = stringResource(R.string.generator_rom_folder),
            value = settings.romDirUri
                .takeIf { it.isNotBlank() }
                ?.let { EsDePaths.prettyPath(Uri.parse(it)) }
                ?: stringResource(R.string.generator_not_set),
        )
        if (viewModel.isScanning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.generator_scanning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (viewModel.romDirUri.isBlank()) {
            Text(
                text = stringResource(R.string.generator_no_folder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@AppScaffold
        }
        if (viewModel.scanFailed) {
            Text(
                text = stringResource(R.string.generator_scan_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SectionTitle(text = stringResource(R.string.generator_source))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6)) {
            KoverChip(
                label = "NeoAssets",
                selected = viewModel.source == GeneratorSource.NEOASSETS,
                onClick = { viewModel.toggleSource(GeneratorSource.NEOASSETS) },
                focusRequester = firstFocus,
            )
            KoverChip(
                label = "libretro-thumbnails",
                selected = viewModel.source == GeneratorSource.LIBRETRO,
                onClick = { viewModel.toggleSource(GeneratorSource.LIBRETRO) },
            )
        }
        val fallbackTitle = if (viewModel.source == GeneratorSource.NEOASSETS) {
            R.string.generator_fallback_libretro
        } else {
            R.string.generator_fallback_neoassets
        }
        val fallbackDesc = if (viewModel.source == GeneratorSource.NEOASSETS) {
            R.string.generator_fallback_libretro_desc
        } else {
            R.string.generator_fallback_neoassets_desc
        }
        GeneratorToggleRow(
            title = stringResource(fallbackTitle),
            subtitle = stringResource(fallbackDesc),
            checked = viewModel.fallback,
            onCheckedChange = { viewModel.toggleFallback() },
        )

        SectionTitle(text = stringResource(R.string.generator_formats))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6)) {
            KoverChip(
                label = "1024 × 1024",
                selected = GeneratorSize.SQUARE in viewModel.sizes,
                onClick = { viewModel.toggleSize(GeneratorSize.SQUARE) },
            )
            KoverChip(
                label = "1280 × 960",
                selected = GeneratorSize.MIX in viewModel.sizes,
                onClick = { viewModel.toggleSize(GeneratorSize.MIX) },
            )
        }

        SectionTitle(text = stringResource(R.string.generator_presets))
        for (row in listOf(
            listOf(GeneratorPreset.CLASSIC, GeneratorPreset.HERO),
            listOf(GeneratorPreset.ICON_COVER, GeneratorPreset.MINIMAL),
        )) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                for (preset in row) {
                    FocusableCard(
                        onClick = { viewModel.togglePreset(preset) },
                        selected = preset in viewModel.presets,
                        modifier = Modifier.weight(1f).height(96.dp),
                        contentAlignment = Alignment.CenterStart,
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                        ) {
                            Image(
                                painter = painterResource(presetPreview(preset)),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(108.dp)
                                    .height(80.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(presetTitle(preset)),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    text = stringResource(presetDesc(preset)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionTitle(text = stringResource(R.string.generator_platforms))
        if (viewModel.visibleSystems.isEmpty()) {
            Text(
                text = stringResource(R.string.generator_no_platforms),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val allSelected = viewModel.selectedSystems.size == viewModel.visibleSystems.size
            val chips: List<System?> = listOf(null) + viewModel.visibleSystems
            for (row in chips.chunked(3)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                ) {
                    for (system in row) {
                        if (system == null) {
                            PlatformTile(
                                label = stringResource(R.string.generator_select_all),
                                artUrl = null,
                                placeholder = "★",
                                selected = allSelected,
                                onClick = { viewModel.selectAllSystems() },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            PlatformTile(
                                label = "${system.name} (${system.romCount})",
                                artUrl = viewModel.artRepo.urlFor(system.id),
                                placeholder = system.name.take(1).uppercase(),
                                selected = system.id in viewModel.selectedSystems,
                                onClick = { viewModel.toggleSystem(system.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(3 - row.size) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        ActionRow(
            title = stringResource(R.string.generator_output),
            subtitle = viewModel.outputUri
                ?.let { EsDePaths.prettyPath(it) }
                ?: stringResource(R.string.generator_not_set),
            trailing = stringResource(R.string.generator_change),
            onClick = { outputPicker.launch(null) },
        )

        GeneratorToggleRow(
            title = stringResource(R.string.generator_overwrite),
            subtitle = stringResource(R.string.generator_overwrite_desc),
            checked = viewModel.overwrite,
            onCheckedChange = { viewModel.toggleOverwrite() },
        )
        Text(
            text = stringResource(
                R.string.generator_estimate,
                viewModel.selectedRoms.size,
                viewModel.estimatedFiles,
                if (viewModel.source == GeneratorSource.NEOASSETS) {
                    viewModel.selectedRoms.size
                } else {
                    viewModel.selectedSystems.size
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (!service.isRunning) {
            KoverButton(
                label = stringResource(R.string.generator_start),
                onClick = { viewModel.start() },
                modifier = Modifier.fillMaxWidth(),
                enabled = viewModel.selectedRoms.isNotEmpty() &&
                    viewModel.presets.isNotEmpty() &&
                    viewModel.sizes.isNotEmpty() &&
                    viewModel.outputUri != null,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (service.currentGame.isBlank()) {
                            stringResource(R.string.generator_preparing)
                        } else {
                            service.currentGame
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (service.totalGames > 0) {
                                service.processedGames.toFloat() / service.totalGames
                            } else 0f
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                Text(
                    text = "${service.processedGames} / ${service.totalGames}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KoverButton(
                    label = stringResource(R.string.generator_stop),
                    onClick = { viewModel.cancel() },
                    style = KoverButtonStyle.Secondary,
                    compact = true,
                    enabled = !service.isStopping,
                )
            }
        }

        service.error?.let { error ->
            Text(
                text = stringResource(generatorErrorText(error)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (service.guestMode && service.isRunning) {
            Text(
                text = stringResource(R.string.generator_guest_mode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        service.report?.let { report ->
            SectionTitle(text = stringResource(R.string.generator_report))
            Text(
                text = stringResource(
                    R.string.generator_report_line,
                    report.generatedFiles,
                    report.processedGames,
                    report.skippedExisting,
                    report.skippedNoMatch,
                    report.failedGames,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.textLogoGames > 0) {
                Text(
                    text = stringResource(R.string.generator_report_text_logo, report.textLogoGames),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.cancelled) {
                Text(
                    text = stringResource(R.string.generator_cancelled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            for (line in report.errors.take(5)) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GeneratorToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    FocusableCard(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.width(44.dp),
            )
        }
    }
}

@Composable
private fun PlatformTile(
    label: String,
    artUrl: String?,
    placeholder: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableCard(
        onClick = onClick,
        selected = selected,
        modifier = modifier.height(48.dp),
        contentAlignment = Alignment.CenterStart,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (artUrl != null) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun presetPreview(preset: GeneratorPreset): Int = when (preset) {
    GeneratorPreset.CLASSIC -> R.drawable.preset_classic
    GeneratorPreset.HERO -> R.drawable.preset_hero
    GeneratorPreset.ICON_COVER -> R.drawable.preset_icon
    GeneratorPreset.MINIMAL -> R.drawable.preset_minimal
}

private fun presetTitle(preset: GeneratorPreset): Int = when (preset) {
    GeneratorPreset.CLASSIC -> R.string.generator_preset_classic
    GeneratorPreset.HERO -> R.string.generator_preset_hero
    GeneratorPreset.ICON_COVER -> R.string.generator_preset_icon
    GeneratorPreset.MINIMAL -> R.string.generator_preset_minimal
}

private fun presetDesc(preset: GeneratorPreset): Int = when (preset) {
    GeneratorPreset.CLASSIC -> R.string.generator_preset_classic_desc
    GeneratorPreset.HERO -> R.string.generator_preset_hero_desc
    GeneratorPreset.ICON_COVER -> R.string.generator_preset_icon_desc
    GeneratorPreset.MINIMAL -> R.string.generator_preset_minimal_desc
}

private fun generatorErrorText(error: GeneratorError): Int = when (error) {
    GeneratorError.NETWORK -> R.string.generator_error_network
    GeneratorError.QUOTA -> R.string.generator_error_quota
    GeneratorError.OUTPUT -> R.string.generator_error_output
    GeneratorError.NO_ROMS -> R.string.generator_error_no_roms
}
