package com.ark.kover.ui.game

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.viewModelScope
import com.ark.kover.R
import com.ark.kover.core.launcher.LauncherAdapter
import com.ark.kover.core.library.LibraryRepository
import com.ark.kover.core.model.Metadata
import com.ark.kover.core.model.Rom
import com.ark.kover.core.scraper.Scraper
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.settings.launcherLabel
import com.ark.kover.core.settings.launcherDirUri
import com.ark.kover.core.translation.Translator
import com.ark.kover.scraper.NeoAssetsScraper
import com.ark.kover.scraper.SteamGridDbScraper
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.CompactTextField
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.components.rememberInitialFocus
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface MetaMessage {
    data object None : MetaMessage
    data object Searching : MetaMessage
    data object NotFound : MetaMessage
    data object NoScrapers : MetaMessage
    data class Unsupported(val system: String) : MetaMessage
    data object Saved : MetaMessage
    data object Failed : MetaMessage
}

@HiltViewModel
class MetadataEditorViewModel @Inject constructor(
    val repo: LibraryRepository,
    private val launcher: LauncherAdapter,
    private val settingsRepo: SettingsRepository,
    private val neoAssets: NeoAssetsScraper,
    private val steamGridDb: SteamGridDbScraper,
    private val screenScraper: com.ark.kover.scraper.ScreenScraperScraper,
    private val libretro: com.ark.kover.scraper.LibretroThumbnailsScraper,
    private val rawg: com.ark.kover.scraper.RawgScraper,
    private val steamStore: com.ark.kover.scraper.SteamStoreScraper,
    private val translator: Translator,
    private val backups: com.ark.kover.core.backup.BackupRepository,
) : ViewModel() {

    val settings = settingsRepo.settings
    private val allScrapers: List<Scraper> =
        listOf(neoAssets, steamGridDb, libretro, steamStore, rawg)

    private var enabledIds by mutableStateOf(
        setOf("neoassets", "steamgriddb", "libretro", "steam"),
    )
    val scrapers: List<Scraper> get() = allScrapers.filter { it.id in enabledIds }

    var scraperId by mutableStateOf("neoassets")
    val scraper: Scraper? get() = allScrapers.firstOrNull { it.id == scraperId && it.id in enabledIds }
        ?: scrapers.firstOrNull()

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
    }
    var query by mutableStateOf("")

    var name by mutableStateOf("")
    var desc by mutableStateOf("")
    var developer by mutableStateOf("")
    var publisher by mutableStateOf("")
    var genre by mutableStateOf("")
    var players by mutableStateOf("")
    var releaseDate by mutableStateOf("")
    var ratingText by mutableStateOf("")

    var isSearching by mutableStateOf(false)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var isTranslating by mutableStateOf(false)
        private set
    var translateTarget by mutableStateOf("ru")
        private set
    var message by mutableStateOf<MetaMessage>(MetaMessage.None)
        private set
    var source by mutableStateOf<Metadata?>(null)
        private set
    private var original by mutableStateOf<Metadata?>(null)

    private var resolver: ContentResolver? = null
    private var dirUri: Uri? = null
    private var loaded = false
    private var metadataLoaded = false

    fun start(resolver: ContentResolver, dirUri: Uri?, rom: Rom) {
        this.resolver = resolver
        this.dirUri = dirUri
        if (!loaded) {
            loaded = true
            query = rom.name
            name = rom.name
            if (rom.system in com.ark.kover.ui.common.EsDeSlots.nonRomSystems &&
                "steamgriddb" in enabledIds
            ) {
                scraperId = "steamgriddb"
            }
        }
        val r = resolver
        if (!metadataLoaded && dirUri != null) {
            metadataLoaded = true
            viewModelScope.launch {
                val meta = withContext(Dispatchers.IO) {
                    launcher.readMetadata(r, dirUri, rom.system, rom.relativePath)
                }
                meta?.let { applyToFields(it) }
            }
        }
        // No automatic scraping here either; the user presses "Search".
    }

    private fun applyToFields(meta: Metadata) {
        original = meta
        if (meta.name.isNotBlank()) name = meta.name
        desc = meta.desc
        developer = meta.developer
        publisher = meta.publisher
        genre = meta.genre
        players = meta.players
        releaseDate = meta.releaseDate
        ratingText = if (meta.rating > 0f) meta.rating.toString() else ""
    }

    fun changeScraper(id: String) {
        if (id == scraperId) return
        scraperId = id
        search()
    }

    fun search() {
        val rom = repo.selectedRom ?: return
        val active = scraper ?: run {
            message = MetaMessage.NoScrapers
            source = null
            return
        }
        if (!active.supportsSystem(rom.system)) {
            message = MetaMessage.Unsupported(rom.system)
            source = null
            return
        }
        isSearching = true
        message = MetaMessage.Searching
        source = null
        val term = query.ifBlank { rom.name }
        viewModelScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                active.searchCandidates(rom.system, term, null)
            }
            val candidate = candidates.firstOrNull()
            if (candidate == null) {
                isSearching = false
                message = MetaMessage.NotFound
                return@launch
            }
            val result = withContext(Dispatchers.IO) { active.fetchFor(rom.system, candidate.id) }
            isSearching = false
            val meta = result?.metadata
            if (meta == null || (meta.name.isBlank() && meta.desc.isBlank() && meta.developer.isBlank())) {
                message = MetaMessage.NotFound
                return@launch
            }
            source = meta.copy(name = if (meta.name.isBlank()) candidate.name else meta.name)
            message = MetaMessage.None
        }
    }

    fun useSource() {
        val meta = source ?: return
        name = meta.name.ifBlank { name }
        desc = meta.desc.ifBlank { desc }
        developer = meta.developer.ifBlank { developer }
        publisher = meta.publisher.ifBlank { publisher }
        genre = meta.genre.ifBlank { genre }
        players = meta.players.ifBlank { players }
        releaseDate = meta.releaseDate.ifBlank { releaseDate }
        if (meta.rating > 0f) ratingText = meta.rating.toString()
    }

    fun useRussianDescription() {
        val ru = source?.descByLang?.get(translateTarget).orEmpty()
        if (ru.isNotBlank()) desc = ru
    }

    fun configureTranslation(lang: String) {
        if (lang.isNotBlank()) translateTarget = lang
    }

    fun translate() {
        if (desc.isBlank()) return
        isTranslating = true
        viewModelScope.launch {
            val translated = withContext(Dispatchers.IO) { translator.translate(desc, "auto", translateTarget) }
            isTranslating = false
            if (translated.isNotBlank() && translated != desc) desc = translated
        }
    }

    fun save() {
        val rom = repo.selectedRom ?: return
        val r = resolver ?: return
        val dir = dirUri ?: return
        isSaving = true
        viewModelScope.launch {
            val meta = Metadata(
                name = name.ifBlank { rom.name },
                desc = desc.ifBlank { original?.desc.orEmpty() },
                rating = ratingText.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
                releaseDate = releaseDate,
                developer = developer,
                publisher = publisher,
                genre = genre,
                players = players,
            )
            val ok = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching {
                    val previous = launcher.readMetadata(r, dir, rom.system, rom.relativePath)
                    if (previous != null && previous != meta) {
                        backups.saveMetadataBackup(r, dir, rom.system, rom.relativePath, previous, meta)
                    }
                    launcher.writeMetadata(r, dir, rom.system, rom.relativePath, meta)
                }.isSuccess
            }
            isSaving = false
            message = if (ok) MetaMessage.Saved else MetaMessage.Failed
        }
    }
}

@Composable
fun EditMetadataScreen(
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: MetadataEditorViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val repo = viewModel.repo
    val rom = repo.selectedRom
    val firstFocus = rememberInitialFocus()

    LaunchedEffect(rom?.id, settings.launcherDirUri) {
        if (rom != null) {
            viewModel.configureTranslation(settings.translateTarget)
            val dir = settings.launcherDirUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            viewModel.start(context.contentResolver, dir, rom)
        }
    }

    if (rom == null) {
        AppScaffold(
            selectedTab = KoverTab.Library,
            onTabSelected = onTabSelected,
            breadcrumbs = listOf(stringResource(R.string.meta_title)),
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
            stringResource(R.string.meta_title),
        ),
        onBack = onBack,
        scrollable = false,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap10),
        ) {
            // ---------------- editable fields ----------------
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Gap4),
                ) {
                SectionTitle(text = stringResource(R.string.meta_current))
                CompactField(
                    label = stringResource(R.string.field_name),
                    value = viewModel.name,
                    onChange = { viewModel.name = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6)) {
                    CompactField(
                        label = stringResource(R.string.field_developer),
                        value = viewModel.developer,
                        onChange = { viewModel.developer = it },
                        modifier = Modifier.weight(1f),
                    )
                    CompactField(
                        label = stringResource(R.string.field_publisher),
                        value = viewModel.publisher,
                        onChange = { viewModel.publisher = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6)) {
                    CompactField(
                        label = stringResource(R.string.field_genre),
                        value = viewModel.genre,
                        onChange = { viewModel.genre = it },
                        modifier = Modifier.weight(1f),
                    )
                    CompactField(
                        label = stringResource(R.string.field_players),
                        value = viewModel.players,
                        onChange = { viewModel.players = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6)) {
                    CompactField(
                        label = stringResource(R.string.field_release),
                        value = viewModel.releaseDate,
                        onChange = { viewModel.releaseDate = it },
                        modifier = Modifier.weight(1f),
                    )
                    CompactField(
                        label = stringResource(R.string.field_rating),
                        value = viewModel.ratingText,
                        onChange = { viewModel.ratingText = it },
                        modifier = Modifier.width(90.dp),
                    )
                }
                OutlinedTextField(
                    value = viewModel.desc,
                    onValueChange = { viewModel.desc = it },
                    label = { Text(stringResource(R.string.field_desc), style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 130.dp),
                )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6)) {
                    SecondaryAction(
                        text = if (viewModel.isTranslating) stringResource(R.string.common_translating)
                        else stringResource(R.string.common_translate, targetLanguageName(viewModel.translateTarget)),
                        onClick = { viewModel.translate() },
                        enabled = !viewModel.isTranslating,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryAction(
                        text = if (viewModel.isSaving) stringResource(R.string.meta_saving)
                        else stringResource(R.string.common_save),
                        onClick = { viewModel.save() },
                        enabled = !viewModel.isSaving,
                        modifier = Modifier.weight(1f),
                        focusRequester = firstFocus,
                    )
                }
                MetaStatus(viewModel.message)
            }

            // ---------------- scraper panel ----------------
            Column(
                modifier = Modifier
                    .width(286.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                if (viewModel.scrapers.isEmpty()) {
                    FocusableCard(
                        onClick = { onTabSelected(KoverTab.Scrapers) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { focused ->
                        Text(
                            text = stringResource(R.string.scrapers_none_enabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (focused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                SectionTitle(
                    text = stringResource(
                        R.string.meta_from_source,
                        viewModel.scraper?.displayName.orEmpty(),
                    ),
                )
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
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4),
                ) {
                    CompactTextField(
                        value = viewModel.query,
                        onValueChange = { viewModel.query = it },
                        placeholder = stringResource(R.string.assets_query),
                        modifier = Modifier.weight(1f),
                    )
                    FocusableCard(
                        onClick = { viewModel.search() },
                        modifier = Modifier.height(34.dp).width(76.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        if (viewModel.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.assets_search), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                }

                val source = viewModel.source
                val deviceLang = java.util.Locale.getDefault().language
                val localizedDesc = source?.descByLang?.get(deviceLang).orEmpty()
                val langName = java.util.Locale(deviceLang).getDisplayLanguage()
                    .replaceFirstChar { it.uppercase() }
                if (source != null) {
                    Text(
                        text = source.name.ifBlank { viewModel.query },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (source.developer.isNotBlank()) SourceLine(stringResource(R.string.field_developer), source.developer)
                    if (source.publisher.isNotBlank()) SourceLine(stringResource(R.string.field_publisher), source.publisher)
                    if (source.genre.isNotBlank()) SourceLine(stringResource(R.string.field_genre), source.genre)
                    if (source.releaseDate.isNotBlank()) SourceLine(stringResource(R.string.field_release), source.releaseDate)
                    if (source.desc.isNotBlank()) {
                        Text(
                            text = source.desc,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ActionRow(
                        title = stringResource(R.string.meta_apply_all),
                        onClick = { viewModel.useSource() },
                    )
                    if (localizedDesc.isNotBlank()) {
                        ActionRow(
                            title = stringResource(R.string.meta_use_localized, langName),
                            subtitle = stringResource(R.string.meta_ru_available),
                            onClick = { viewModel.useRussianDescription() },
                        )
                    }
                } else if (!viewModel.isSearching && viewModel.scrapers.isNotEmpty()) {
                    val msg = viewModel.message
                    Text(
                        text = when (msg) {
                            MetaMessage.NotFound -> stringResource(R.string.meta_no_source_data)
                            is MetaMessage.Unsupported -> stringResource(
                                R.string.assets_source_unsupported,
                                msg.system,
                            )
                            else -> stringResource(R.string.assets_hint_search)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier.height(52.dp),
    )
}

@Composable
private fun SourceLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    FocusableCard(
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(Dimens.ButtonHeight),
        focusRequester = focusRequester,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) { focused ->
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

private fun targetLanguageName(tag: String): String =
    java.util.Locale(tag).getDisplayLanguage().replaceFirstChar { it.uppercase() }

@Composable
private fun MetaStatus(message: MetaMessage) {    val (text, isError) = when (message) {
        MetaMessage.None, MetaMessage.Searching -> return
        MetaMessage.NotFound -> stringResource(R.string.assets_not_found) to false
        MetaMessage.NoScrapers -> stringResource(R.string.scrapers_none_enabled) to true
        MetaMessage.Saved -> stringResource(R.string.meta_saved) to false
        MetaMessage.Failed -> stringResource(R.string.meta_failed) to true
        is MetaMessage.Unsupported -> stringResource(R.string.assets_source_unsupported, message.system) to false
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}
