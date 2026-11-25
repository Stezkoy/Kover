package com.ark.kover.ui.game

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ark.kover.core.model.GameCandidate
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.model.Rom
import com.ark.kover.core.scraper.Scraper
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.settings.launcherLabel
import com.ark.kover.core.settings.launcherDirUri
import com.ark.kover.scraper.NeoAssetsScraper
import com.ark.kover.scraper.SteamGridDbScraper
import com.ark.kover.ui.common.EsDeSlots
import com.ark.kover.ui.common.slotLabel
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.CompactTextField
import com.ark.kover.ui.components.EmptyState
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.MediaThumb
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.components.Thumb
import com.ark.kover.ui.components.VideoSurface
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

sealed interface AssetsMessage {
    data object None : AssetsMessage
    data object Searching : AssetsMessage
    data object NotFound : AssetsMessage
    data object NoScrapers : AssetsMessage
    data class Unsupported(val system: String) : AssetsMessage
    data object NoLauncherDir : AssetsMessage
    data class Applied(val kind: String) : AssetsMessage
    data object Failed : AssetsMessage
}

@HiltViewModel
class AssetEditorViewModel @Inject constructor(
    val repo: LibraryRepository,
    private val launcher: LauncherAdapter,
    private val settingsRepo: SettingsRepository,
    private val neoAssets: NeoAssetsScraper,
    private val steamGridDb: SteamGridDbScraper,
    private val screenScraper: com.ark.kover.scraper.ScreenScraperScraper,
    private val libretro: com.ark.kover.scraper.LibretroThumbnailsScraper,
    private val rawg: com.ark.kover.scraper.RawgScraper,
    private val steamStore: com.ark.kover.scraper.SteamStoreScraper,
    private val applyService: com.ark.kover.core.launcher.AssetApplyService,
) : ViewModel() {

    val settings = settingsRepo.settings
    private val allScrapers: List<Scraper> =
        listOf(neoAssets, steamGridDb, libretro, steamStore, rawg)

    private var enabledIds by mutableStateOf(
        setOf("neoassets", "steamgriddb", "libretro", "steam"),
    )
    val scrapers: List<Scraper> get() = allScrapers.filter { it.id in enabledIds }

    var scraperId by mutableStateOf("neoassets")
    val scraper: Scraper? get() = scrapers.firstOrNull { it.id == scraperId } ?: scrapers.firstOrNull()

    init {
        viewModelScope.launch {
            settings.collect { s ->
                enabledIds = s.activeScrapers.split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                if (scrapers.none { it.id == scraperId }) {
                    scraperId = scrapers.firstOrNull()?.id ?: scraperId
                }
            }
        }
        viewModelScope.launch {
            applyService.results.collect { r ->
                val rom = repo.selectedRom ?: return@collect
                if (r.system != rom.system || r.gameName != rom.name) return@collect
                if (r.kind != applyingKind) return@collect
                applyingKind = null
                if (r.success) {
                    message = AssetsMessage.Applied(r.kind)
                    val dir = settingsSnapshot.launcherDirUri
                        .takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                    resolverSnapshot?.let { loadCurrent(it, dir, rom) }
                } else {
                    message = AssetsMessage.Failed
                }
            }
        }
    }
    var query by mutableStateOf("")
    var isSearching by mutableStateOf(false)
        private set
    var candidates by mutableStateOf<List<GameCandidate>>(emptyList())
        private set
    var selectedCandidate by mutableStateOf<GameCandidate?>(null)
        private set
    var found by mutableStateOf<Map<String, List<Asset>>>(emptyMap())
        private set
    var current by mutableStateOf<Map<String, List<Asset>>>(emptyMap())
        private set
    var sourceMetadata by mutableStateOf<Metadata?>(null)
        private set
    var selectedSlot by mutableStateOf(EsDeSlots.COVERS)
    var foundKind by mutableStateOf(EsDeSlots.COVERS)
    var message by mutableStateOf<AssetsMessage>(AssetsMessage.None)
        private set
    var applyingKind by mutableStateOf<String?>(null)
        private set

    private var started = false

    /** Slots the active launcher can store (NeoStation has five, ES-DE has eleven). */
    var editorKinds: List<String> = EsDeSlots.editorKinds
        private set

    fun start(
        resolver: ContentResolver,
        launcherDirUri: Uri?,
        rom: Rom,
        initialSlot: String?,
        launcherType: String,
    ) {
        if (!started) {
            started = true
            query = rom.name
            if (rom.system in EsDeSlots.nonRomSystems && "steamgriddb" in enabledIds) {
                scraperId = "steamgriddb"
            }
            if (initialSlot != null) selectedSlot = initialSlot
        }
        editorKinds = EsDeSlots.editorKindsFor(launcherType, rom.system)
        if (selectedSlot !in editorKinds) selectedSlot = editorKinds.first()
        // No automatic scraping: quota is spent only when the user presses "Search".
        loadCurrent(resolver, launcherDirUri, rom)
    }

    private fun loadCurrent(resolver: ContentResolver, launcherDirUri: Uri?, rom: Rom) {
        if (launcherDirUri == null) return
        viewModelScope.launch {
            current = withContext(Dispatchers.IO) {
                editorKinds.associateWith { kind ->
                    launcher.readAssetsOfKind(resolver, launcherDirUri, rom.system, rom.name, kind)
                }.filterValues { it.isNotEmpty() }
            }
        }
    }

    fun changeScraper(id: String) {
        if (id == scraperId) return
        scraperId = id
        search()
    }

    fun search() {
        val rom = repo.selectedRom ?: return
        val active = scraper ?: run {
            message = AssetsMessage.NoScrapers
            return
        }
        if (!active.supportsSystem(rom.system)) {
            message = AssetsMessage.Unsupported(rom.system)
            candidates = emptyList()
            found = emptyMap()
            return
        }
        isSearching = true
        message = AssetsMessage.Searching
        candidates = emptyList()
        selectedCandidate = null
        found = emptyMap()
        sourceMetadata = null
        val term = query.ifBlank { rom.name }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                active.searchCandidates(rom.system, term, null)
            }
            if (result.isEmpty()) {
                isSearching = false
                message = AssetsMessage.NotFound
                return@launch
            }
            candidates = result
            loadCandidate(result.first())
        }
    }

    fun selectCandidate(candidate: GameCandidate) = loadCandidate(candidate)

    private fun loadCandidate(candidate: GameCandidate) {
        val rom = repo.selectedRom ?: return
        val active = scraper ?: return
        isSearching = true
        message = AssetsMessage.Searching
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { active.fetchFor(rom.system, candidate.id) }
            isSearching = false
            if (result == null) {
                message = AssetsMessage.NotFound
                return@launch
            }
            selectedCandidate = candidate
            sourceMetadata = result.metadata
            found = result.assets.groupBy { it.kind }
            message = AssetsMessage.None
            // Keep the chosen target slot; only pick a default source category
            // when the current one has no results.
            foundKind = if (found[selectedSlot].orEmpty().isNotEmpty()) selectedSlot
            else found.keys.firstOrNull() ?: selectedSlot
        }
    }

    fun apply(kind: String, asset: Asset) {
        val rom = repo.selectedRom ?: return
        val dirUri = settingsSnapshot.launcherDirUri
        if (dirUri.isBlank()) {
            message = AssetsMessage.NoLauncherDir
            return
        }
        if (resolverSnapshot == null) return
        applyingKind = kind
        // Runs in an application-wide scope: leaving the screen must not cancel the write.
        applyService.apply(
            launcherType = settingsSnapshot.launcherType,
            launcherDirUri = Uri.parse(dirUri),
            system = rom.system,
            gameName = rom.name,
            kind = kind,
            asset = asset,
        )
    }

    private var settingsSnapshot = AppSettings()
    private var resolverSnapshot: ContentResolver? = null

    fun bind(resolver: ContentResolver, settings: AppSettings) {
        resolverSnapshot = resolver
        settingsSnapshot = settings
    }
}

@Composable
fun EditAssetsScreen(
    initialSlot: String?,
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: AssetEditorViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val repo = viewModel.repo
    val rom = repo.selectedRom
    var preview by remember { mutableStateOf<Asset?>(null) }
    var showCandidates by remember { mutableStateOf(false) }

    LaunchedEffect(rom?.id, settings.launcherDirUri, settings.launcherType) {
        if (rom != null) {
            viewModel.bind(context.contentResolver, settings)
            val dir = settings.launcherDirUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            viewModel.start(context.contentResolver, dir, rom, initialSlot, settings.launcherType)
        }
    }

    if (rom == null) {
        AppScaffold(
            selectedTab = KoverTab.Library,
            onTabSelected = onTabSelected,
            breadcrumbs = listOf(stringResource(R.string.assets_title)),
            onBack = onBack,
        ) { Text(stringResource(R.string.roms_empty)) }
        return
    }

    AppScaffold(
        selectedTab = KoverTab.Library,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(
            settings.launcherLabel,
            stringResource(R.string.nav_library),
            repo.systemName(rom.system),
            rom.name,
            stringResource(R.string.assets_title),
        ),
        onBack = onBack,
        scrollable = false,
    ) {
        if (settings.launcherDirUri.isBlank()) {
            EmptyState(
                title = stringResource(R.string.launcher_not_set),
                body = stringResource(R.string.assets_no_settings),
                action = {
                    ActionRow(
                        title = stringResource(R.string.nav_launcher),
                        onClick = { onTabSelected(KoverTab.Launcher) },
                    )
                },
            )
            return@AppScaffold
        }

        // ---------------- toolbar: source chips on their own row ----------------
        if (viewModel.scrapers.isEmpty()) {
            FocusableCard(
                onClick = { onTabSelected(KoverTab.Scrapers) },
                modifier = Modifier.fillMaxWidth().height(36.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { focused ->
                Text(
                    text = stringResource(R.string.scrapers_none_enabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4),
            ) {
                items(viewModel.scrapers, key = { it.id }) { scraper ->
                    val selected = scraper.id == viewModel.scraperId
                    FocusableCard(
                        onClick = { viewModel.changeScraper(scraper.id) },
                        modifier = Modifier.height(28.dp),
                        selected = selected,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) { focused ->
                        Text(
                            text = scraper.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected || focused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---------------- query + search ----------------
        if (viewModel.scrapers.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                CompactTextField(
                    value = viewModel.query,
                    onValueChange = { viewModel.query = it },
                    placeholder = stringResource(R.string.assets_query),
                    modifier = Modifier.weight(1f),
                )
                FocusableCard(
                    onClick = { viewModel.search() },
                    modifier = Modifier.height(34.dp).width(90.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { focused ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (viewModel.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = stringResource(R.string.assets_search),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        StatusLine(viewModel.message, viewModel.isSearching)
        if (!viewModel.isSearching &&
            viewModel.scrapers.isNotEmpty() &&
            viewModel.message == AssetsMessage.None &&
            viewModel.candidates.isEmpty() &&
            viewModel.found.isEmpty()
        ) {
            Text(
                text = stringResource(R.string.assets_hint_search),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (viewModel.candidates.size > 1) {
            ActionRow(
                title = stringResource(R.string.assets_pick_game, viewModel.candidates.size),
                subtitle = viewModel.selectedCandidate?.name
                    ?: stringResource(R.string.assets_pick_candidate),
                onClick = { showCandidates = true },
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8),
        ) {
            // ---------------- slots ----------------
            Column(
                modifier = Modifier
                    .width(190.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap2),
            ) {
                SectionTitle(text = stringResource(R.string.assets_slots))
                EsDeSlots.editorKindsFor(settings.launcherType, rom?.system).forEach { kind ->
                    val currentCount = viewModel.current[kind]?.size ?: 0
                    val foundCount = viewModel.found[kind]?.size ?: 0
                    SlotRow(
                        kind = kind,
                        currentCount = currentCount,
                        foundCount = foundCount,
                        selected = kind == viewModel.selectedSlot,
                        onClick = {
                            viewModel.selectedSlot = kind
                            if (viewModel.found.containsKey(kind)) viewModel.foundKind = kind
                        },
                    )
                }
            }

            // ---------------- found artwork: source categories + results ----------------
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val categories = buildList {
                    viewModel.editorKinds.forEach { if (viewModel.found.containsKey(it)) add(it) }
                    viewModel.found.keys.forEach { if (it !in this) add(it) }
                }
                if (categories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4)) {
                        items(categories, key = { it }) { kind ->
                            val selected = kind == viewModel.foundKind
                            FocusableCard(
                                onClick = { viewModel.foundKind = kind },
                                selected = selected,
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) { focused ->
                                Text(
                                    text = slotLabel(kind) + " " +
                                        (viewModel.found[kind]?.size ?: 0),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected || focused) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                SectionTitle(
                    text = slotLabel(viewModel.foundKind) +
                        if (viewModel.found[viewModel.foundKind].orEmpty().isNotEmpty()) {
                            "  ·  " + stringResource(
                                R.string.assets_found_count,
                                viewModel.found[viewModel.foundKind]?.size ?: 0,
                            )
                        } else "",
                )
                val assets = viewModel.found[viewModel.foundKind].orEmpty()
                val hasSearched = viewModel.candidates.isNotEmpty() ||
                    viewModel.found.isNotEmpty() ||
                    viewModel.message != AssetsMessage.None
                if (assets.isEmpty() && hasSearched) {
                    Text(
                        text = stringResource(R.string.assets_empty_slot),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (assets.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        gridItems(assets, key = { it.url }) { asset ->
                            FocusableCard(
                                onClick = { preview = asset },
                                modifier = Modifier.height(96.dp),
                                contentPadding = PaddingValues(3.dp),
                            ) { focused ->
                                Box(modifier = Modifier.fillMaxSize()) {
                                    MediaThumb(
                                        kind = viewModel.foundKind,
                                        model = asset.url,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        fallbackModel = viewModel.current[EsDeSlots.SCREENSHOTS]
                                            ?.firstOrNull()?.url,
                                    )
                                    if (viewModel.applyingKind == viewModel.selectedSlot) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.align(Alignment.Center).size(18.dp),
                                            strokeWidth = 2.dp,
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

    val asset = preview
    if (asset != null) {
        AssetPreviewDialog(
            asset = asset,
            initialTarget = viewModel.selectedSlot,
            targets = EsDeSlots.kindsFor(settings.launcherType, rom?.system),
            onApply = { target ->
                viewModel.apply(target, asset)
                preview = null
            },
            onDismiss = { preview = null },
        )
    }

    if (showCandidates) {
        AlertDialog(
            onDismissRequest = { showCandidates = false },
            title = { Text(stringResource(R.string.assets_pick_candidate)) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(viewModel.candidates, key = { it.id }) { candidate ->
                        val selected = viewModel.selectedCandidate?.id == candidate.id
                        FocusableCard(
                            onClick = {
                                viewModel.selectCandidate(candidate)
                                showCandidates = false
                            },
                            selected = selected,
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) { focused ->
                            Text(
                                text = candidate.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected || focused) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCandidates = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}

/**
 * Artwork preview with an explicit target-slot chooser, so a fanart image can be
 * written into covers (or any other slot) and videos can be played before applying.
 */
@Composable
private fun AssetPreviewDialog(
    asset: Asset,
    initialTarget: String,
    targets: List<String>,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember(asset.url) {
        mutableStateOf(initialTarget.takeIf { it in targets } ?: targets.first())
    }
    val isVideo = asset.mime.startsWith("video") || asset.url.endsWith(".mp4", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(slotLabel(target)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 296.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                if (isVideo) {
                    VideoSurface(
                        url = asset.url,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                    )
                } else {
                    Thumb(
                        model = asset.url,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    text = stringResource(R.string.assets_target),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Gap4),
                ) {
                    targets.chunked(4).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4),
                        ) {
                            row.forEach { kind ->
                                val selected = kind == target
                                FocusableCard(
                                    onClick = { target = kind },
                                    selected = selected,
                                    modifier = Modifier.weight(1f).height(26.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) { focused ->
                                    Text(
                                        text = slotLabel(kind),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected || focused) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            repeat(4 - row.size) {
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(target) }) {
                Text(stringResource(R.string.assets_apply_to, slotLabel(target)))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun SlotRow(
    kind: String,
    currentCount: Int,
    foundCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = onClick,
        selected = selected,
        modifier = Modifier.fillMaxWidth().height(28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
            Text(
                text = slotLabel(kind),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    selected || focused -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (currentCount > 0) {
                Text(
                    text = currentCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (foundCount > 0) {
                Text(
                    text = "  +$foundCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(message: AssetsMessage, isSearching: Boolean) {
    if (isSearching) return
    val (text, isError) = when (message) {
        AssetsMessage.None -> return
        AssetsMessage.Searching -> return
        AssetsMessage.NotFound -> stringResource(R.string.assets_not_found) to false
        AssetsMessage.NoScrapers -> stringResource(R.string.scrapers_none_enabled) to true
        AssetsMessage.Failed -> stringResource(R.string.assets_failed) to true
        AssetsMessage.NoLauncherDir -> stringResource(R.string.assets_no_settings) to true
        is AssetsMessage.Unsupported -> stringResource(R.string.assets_source_unsupported, message.system) to false
        is AssetsMessage.Applied -> stringResource(R.string.assets_applied, slotLabel(message.kind)) to false
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        if (message is AssetsMessage.NotFound) {
            Text(
                text = stringResource(R.string.assets_not_found_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
