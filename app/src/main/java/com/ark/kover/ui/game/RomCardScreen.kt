package com.ark.kover.ui.game

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ark.kover.R
import com.ark.kover.core.launcher.LauncherAdapter
import com.ark.kover.core.library.LibraryRepository
import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.model.Rom
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.settings.launcherLabel
import com.ark.kover.core.settings.launcherDirUri
import com.ark.kover.ui.common.EsDeSlots
import com.ark.kover.ui.common.slotLabel
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.FieldLabel
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.MediaThumb
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.components.Thumb
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class RomCardViewModel @Inject constructor(
    val repo: LibraryRepository,
    private val launcher: LauncherAdapter,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val settings = settingsRepo.settings
    var metadata by mutableStateOf<Metadata?>(null)
    var assets by mutableStateOf<Map<String, List<Asset>>>(emptyMap())
    var isLoading by mutableStateOf(false)
        private set
    var isCreatingMix by mutableStateOf(false)
        private set
    var mixResult by mutableStateOf<MixResult>(MixResult.None)
        private set

    /** Slots supported by the active launcher (used when reloading after miximage). */
    private var kinds: List<String> = EsDeSlots.all

    fun load(resolver: ContentResolver, launcherDirUri: Uri, rom: Rom, kinds: List<String>) {
        this.kinds = kinds
        isLoading = true
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) {
                launcher.readMetadata(resolver, launcherDirUri, rom.system, rom.relativePath)
            }
            val loaded = withContext(Dispatchers.IO) {
                kinds.associateWith { kind ->
                    launcher.readAssetsOfKind(resolver, launcherDirUri, rom.system, rom.name, kind)
                }.filterValues { it.isNotEmpty() }
            }
            metadata = meta
            assets = loaded
            isLoading = false
        }
    }

    /** Builds a miximage from the current screenshot / marquee / box / media files. */
    fun createMixImage(resolver: ContentResolver, launcherDirUri: Uri, rom: Rom) {
        if (isCreatingMix) return
        val current = assets
        val screenshot = current["screenshots"]?.firstOrNull()
            ?: current["fanart"]?.firstOrNull()
            ?: current["covers"]?.firstOrNull()
        if (screenshot == null) {
            mixResult = MixResult.NeedSources
            return
        }
        val marquee = current["marquees"]?.firstOrNull() ?: current["titlescreens"]?.firstOrNull()
        val box3d = current["3dboxes"]?.firstOrNull()
        val cover = current["covers"]?.firstOrNull()
        val media = current["physicalmedia"]?.firstOrNull()

        isCreatingMix = true
        mixResult = MixResult.None
        viewModelScope.launch {
            // Keep composing even if the user leaves the card while it runs.
            val ok = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                val bytes = com.ark.kover.core.media.MixImageComposer.compose(
                    com.ark.kover.core.media.MixImageComposer.Sources(
                        screenshot = java.io.File(screenshot.url),
                        marquee = marquee?.let { java.io.File(it.url) },
                        box = (box3d ?: cover)?.let { java.io.File(it.url) },
                        boxIs3D = box3d != null,
                        media = media?.let { java.io.File(it.url) },
                    ),
                ) ?: return@withContext false
                runCatching {
                    launcher.writeAsset(
                        resolver,
                        launcherDirUri,
                        rom.system,
                        rom.name,
                        "miximages",
                        Asset(kind = "miximages", url = "", mime = "image/png"),
                        bytes,
                    )
                }.isSuccess
            }
            if (ok) load(resolver, launcherDirUri, rom, kinds)
            isCreatingMix = false
            mixResult = if (ok) MixResult.Created else MixResult.Failed
        }
    }
}

sealed interface MixResult {
    data object None : MixResult
    data object Created : MixResult
    data object NeedSources : MixResult
    data object Failed : MixResult
}

@Composable
fun RomCardScreen(
    onEditMetadata: () -> Unit,
    onEditAssets: (String?) -> Unit,
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: RomCardViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val repo = viewModel.repo
    val rom = repo.selectedRom
    val launcherDirUri = settings.launcherDirUri
    val slotKinds = EsDeSlots.kindsFor(settings.launcherType, rom?.system)

    LaunchedEffect(rom?.id, launcherDirUri, settings.launcherType) {
        if (rom != null && launcherDirUri.isNotBlank()) {
            viewModel.load(context.contentResolver, Uri.parse(launcherDirUri), rom, slotKinds)
        }
    }

    if (rom == null) {
        AppScaffold(
            selectedTab = KoverTab.Library,
            onTabSelected = onTabSelected,
            breadcrumbs = listOf(stringResource(R.string.nav_library)),
            onBack = onBack,
        ) {
            Text(stringResource(R.string.roms_empty), style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    val meta = viewModel.metadata
    val filledSlots = viewModel.assets.keys.count { it in slotKinds }
    val coverPath = viewModel.assets[EsDeSlots.COVERS]?.firstOrNull()?.url
    var videoUrl by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var previewSlot by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String, Asset>?>(null)
    }
    var showDescription by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var showMixDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    AppScaffold(
        selectedTab = KoverTab.Library,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(
            settings.launcherLabel,
            stringResource(R.string.nav_library),
            repo.systemName(rom.system),
            rom.name,
        ),
        onBack = onBack,
        scrollable = false,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap12),
        ) {
            // ---------------- left: metadata + actions ----------------
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8)) {
                    Thumb(model = coverPath, modifier = Modifier.width(74.dp).height(104.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = rom.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = rom.fileName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = repo.systemName(rom.system),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                ActionRow(
                    title = stringResource(R.string.rom_edit_metadata),
                    subtitle = stringResource(R.string.rom_edit_metadata_hint),
                    onClick = onEditMetadata,
                )
                ActionRow(
                    title = stringResource(R.string.rom_edit_images),
                    subtitle = stringResource(
                        R.string.rom_edit_images_hint,
                        filledSlots,
                        slotKinds.size,
                    ),
                    onClick = { onEditAssets(null) },
                )

                SectionTitle(text = stringResource(R.string.rom_metadata))
                if (meta == null) {
                    Text(
                        text = stringResource(R.string.rom_no_metadata),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8)) {
                        FieldLabel(stringResource(R.string.field_developer), meta.developer, Modifier.weight(1f))
                        FieldLabel(stringResource(R.string.field_publisher), meta.publisher, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8)) {
                        FieldLabel(stringResource(R.string.field_genre), meta.genre, Modifier.weight(1f))
                        FieldLabel(stringResource(R.string.field_players), meta.players, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8)) {
                        FieldLabel(stringResource(R.string.field_release), meta.releaseDate, Modifier.weight(1f))
                        FieldLabel(
                            stringResource(R.string.field_rating),
                            if (meta.rating > 0f) {
                                String.format(java.util.Locale.getDefault(), "%.1f", meta.rating)
                            } else "",
                            Modifier.weight(1f),
                        )
                    }
                    if (meta.desc.isNotBlank()) {
                        FocusableCard(
                            onClick = { showDescription = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) { focused ->
                            Column {
                                Text(
                                    text = stringResource(R.string.field_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = meta.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.preview_more),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (focused) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- right: artwork slots ----------------
            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                SectionTitle(text = stringResource(R.string.rom_images))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(slotKinds, key = { it }) { kind ->
                        val list = viewModel.assets[kind].orEmpty()
                        FocusableCard(
                            onClick = {
                                when {
                                    kind == EsDeSlots.MIXIMAGES -> showMixDialog = true
                                    list.isEmpty() -> onEditAssets(kind)
                                    kind == EsDeSlots.VIDEOS -> videoUrl = list.first().url
                                    else -> previewSlot = kind to list.first()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(84.dp),
                            contentPadding = PaddingValues(3.dp),
                        ) { focused ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                MediaThumb(
                                    kind = kind,
                                    model = list.firstOrNull()?.url,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    fallbackModel = viewModel.assets[EsDeSlots.SCREENSHOTS]
                                        ?.firstOrNull()?.url,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = slotLabel(kind),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (focused) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (list.isNotEmpty()) {
                                        Text(
                                            text = list.size.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val playing = videoUrl
    if (playing != null) {
        com.ark.kover.ui.components.VideoPreviewDialog(
            videoUrl = playing,
            title = slotLabel(EsDeSlots.VIDEOS),
            onDismiss = { videoUrl = null },
        )
    }

    val preview = previewSlot
    if (preview != null) {
        val (kind, asset) = preview
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { previewSlot = null },
            title = { Text(slotLabel(kind)) },
            text = {
                MediaThumb(
                    kind = kind,
                    model = asset.url,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    contentScale = ContentScale.Fit,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    previewSlot = null
                    onEditAssets(kind)
                }) { Text(stringResource(R.string.preview_edit)) }
            },
            dismissButton = {
                TextButton(onClick = { previewSlot = null }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }

    if (showMixDialog) {
        val existing = viewModel.assets[EsDeSlots.MIXIMAGES]?.firstOrNull()
        AlertDialog(
            onDismissRequest = { showMixDialog = false },
            title = { Text(slotLabel(EsDeSlots.MIXIMAGES)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Gap4)) {
                    MediaThumb(
                        kind = EsDeSlots.MIXIMAGES,
                        model = existing?.url,
                        modifier = Modifier.fillMaxWidth().height(230.dp),
                        contentScale = ContentScale.Fit,
                    )
                    val status = when (viewModel.mixResult) {
                        MixResult.Created -> stringResource(R.string.mix_created)
                        MixResult.NeedSources -> stringResource(R.string.mix_need_sources)
                        MixResult.Failed -> stringResource(R.string.mix_failed)
                        MixResult.None -> ""
                    }
                    if (status.isNotBlank()) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (viewModel.mixResult == MixResult.Failed)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.mix_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4)) {
                    TextButton(onClick = {
                        showMixDialog = false
                        onEditAssets(EsDeSlots.MIXIMAGES)
                    }) { Text(stringResource(R.string.preview_edit)) }
                    TextButton(
                        enabled = !viewModel.isCreatingMix && launcherDirUri.isNotBlank(),
                        onClick = {
                            viewModel.createMixImage(
                                context.contentResolver,
                                Uri.parse(launcherDirUri),
                                rom,
                            )
                        },
                    ) {
                        Text(
                            stringResource(
                                if (viewModel.isCreatingMix) R.string.mix_creating
                                else R.string.mix_create,
                            ),
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMixDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }

    if (showDescription && meta != null) {
        AlertDialog(
            onDismissRequest = { showDescription = false },
            title = { Text(meta.name.ifBlank { rom.name }) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(text = meta.desc, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDescription = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}
