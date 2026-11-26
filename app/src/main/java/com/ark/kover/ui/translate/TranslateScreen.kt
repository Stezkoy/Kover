package com.ark.kover.ui.translate

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ark.kover.R
import com.ark.kover.core.launcher.SwitchableLauncherAdapter
import com.ark.kover.core.library.LibraryRepository
import com.ark.kover.core.model.Rom
import com.ark.kover.core.network.hasInternet
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.settings.launcherDirUri
import com.ark.kover.core.settings.launcherLabel
import com.ark.kover.core.translation.TranslationSession
import com.ark.kover.core.translation.effectiveDescription
import com.ark.kover.core.translation.looksLikeTargetLanguage
import com.ark.kover.ui.common.EsDeSlots
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.EmptyState
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.components.rememberInitialFocus
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max

private val TranslateLanguages = listOf(
    "ru", "de", "es", "fr", "it", "pt", "ja", "ko", "zh",
)

enum class TranslateStatus { Ready, Empty, Same }

/** One row per game on the screen; at most this many rows are rendered at a time. */
private const val PageSize = 8

@HiltViewModel
class TranslateViewModel @Inject constructor(
    val repo: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val launchers: SwitchableLauncherAdapter,
    private val draft: TranslateDraft,
    @ApplicationContext private val appContext: Context,
    val session: TranslationSession,
) : ViewModel() {
    val settings = settingsRepo.settings

    var selectedSystems by mutableStateOf<Set<String>>(emptySet())
        private set
    var target by mutableStateOf("ru")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var games by mutableStateOf<List<Rom>>(emptyList())
        private set
    var statuses by mutableStateOf<Map<String, TranslateStatus>>(emptyMap())
        private set
    var sourceLengths by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var page by mutableStateOf(0)
        private set

    private var resolver: ContentResolver? = null
    private var dirUri: Uri? = null
    private var launcherType = "es-de"
    private var loadedFor: String? = null
    private var analysisJob: Job? = null
    private var refreshToken = 0
    private var targetLoaded = false
    private val connectivity =
        appContext.getSystemService(ConnectivityManager::class.java)

    /** Clears a stale "no internet" report as soon as the network is back. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            clearNoInternetIfOnline()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            clearNoInternetIfOnline()
        }
    }

    private fun clearNoInternetIfOnline() {
        viewModelScope.launch {
            if (!session.isRunning && session.networkError && appContext.hasInternet()) {
                session.clearReport()
            }
        }
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        viewModelScope.launch {
            settings.collect { s ->
                if (!targetLoaded) {
                    targetLoaded = true
                    target = s.translateTarget
                }
            }
        }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    /** Restores the library for the active launcher (cache first, scan only when stale). */
    fun ensureLibrary(
        context: Context,
        romDirUri: String,
        launcherDirUri: String,
        launcher: String,
    ) {
        launcherType = launcher
        resolver = context.contentResolver
        dirUri = launcherDirUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (draft.launcher != launcher) {
            draft.launcher = launcher
            draft.selectedSystems = emptySet()
            draft.page = 0
            if (!session.isRunning) session.clearReport()
        }
        // Coming back to the tab keeps the platforms selected while the job was running.
        if (selectedSystems != draft.selectedSystems) selectedSystems = draft.selectedSystems
        if (page != draft.page) page = draft.page
        if (romDirUri.isBlank()) return
        if (loadedFor == launcher && repo.hasData) return
        loadedFor = launcher
        viewModelScope.launch {
            repo.clear()
            games = emptyList()
            statuses = emptyMap()
            sourceLengths = emptyMap()
            isLoading = selectedSystems.isNotEmpty()
            val cached = repo.loadCache(romDirUri, launcher)
            val stale = System.currentTimeMillis() - repo.lastScanAt > 24L * 60 * 60 * 1000
            if (!cached || stale) {
                withContext(Dispatchers.IO) {
                    repo.scan(context.contentResolver, Uri.parse(romDirUri))
                }
            }
        }
    }

    /** Same launcher switch as the Library screen, including NeoStation folder discovery. */
    fun setLauncher(type: String) {
        if (session.isRunning || type == launcherType) return
        session.clearReport()
        draft.launcher = type
        draft.selectedSystems = emptySet()
        draft.page = 0
        selectedSystems = emptySet()
        page = 0
        games = emptyList()
        statuses = emptyMap()
        sourceLengths = emptyMap()
        loadedFor = null
        viewModelScope.launch {
            val neoDir = File(android.os.Environment.getExternalStorageDirectory(), "NeoStation")
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

    fun chooseTarget(code: String) {
        if (session.isRunning || code == target) return
        target = code
        page = 0
        draft.page = 0
        session.clearReport()
        viewModelScope.launch { settingsRepo.update { it.copy(translateTarget = code) } }
    }

    fun toggleSystem(id: String) {
        if (session.isRunning) return
        selectedSystems = if (id in selectedSystems) selectedSystems - id else selectedSystems + id
        draft.selectedSystems = selectedSystems
        page = 0
        draft.page = 0
        session.clearReport()
    }

    fun changePage(value: Int) {
        page = value.coerceAtLeast(0)
        draft.page = page
    }

    /**
     * Reads descriptions for the selected platforms in one pass per system and computes
     * the status of every game. The batch adapter method is what keeps a 300-game system
     * from re-parsing the whole gamelist 300 times.
     */
    fun refresh() {
        val resolver = resolver ?: return
        val dir = dirUri ?: return
        val launcher = launchers.adapterFor(launcherType)
        val selected = selectedSystems.toList()
        val token = ++refreshToken
        analysisJob?.cancel()
        if (selected.isEmpty()) {
            games = emptyList()
            statuses = emptyMap()
            sourceLengths = emptyMap()
            isLoading = false
            if (!session.isRunning) session.clearReport()
            return
        }

        val romsBySystem = selected.associateWith { repo.roms(it) }
        games = selected.sorted().flatMap { romsBySystem[it].orEmpty() }
        isLoading = true
        analysisJob = viewModelScope.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    selected.associateWith { system ->
                        launcher.readMetadataForSystem(
                            resolver,
                            dir,
                            system,
                            romsBySystem[system].orEmpty(),
                        )
                    }
                }
                if (token != refreshToken) return@launch
                val result = mutableMapOf<String, TranslateStatus>()
                val lengths = mutableMapOf<String, Int>()
                games.forEach { rom ->
                    val text = metadata[rom.system]?.get(rom.relativePath)
                        ?.effectiveDescription().orEmpty()
                    lengths[rom.id] = text.length
                    result[rom.id] = when {
                        text.isBlank() -> TranslateStatus.Empty
                        looksLikeTargetLanguage(text, target) -> TranslateStatus.Same
                        else -> TranslateStatus.Ready
                    }
                }
                statuses = result
                sourceLengths = lengths
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AutoTranslate", "status refresh failed", e)
            } finally {
                if (token == refreshToken) isLoading = false
            }
        }
    }

    fun statusOf(rom: Rom): TranslateStatus = statuses[rom.id] ?: TranslateStatus.Empty

    fun sourceLength(rom: Rom): Int = sourceLengths[rom.id] ?: 0

    fun start() {
        val dir = dirUri ?: return
        session.start(launcherType, dir, games, target)
    }
}

@Composable
fun TranslateScreen(
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: TranslateViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val repo = viewModel.repo
    val session = viewModel.session
    val firstFocus = rememberInitialFocus()
    val launcher = settings.launcherType
    val romDirUri = settings.romDirUri
    val launcherDir = settings.launcherDirUri
    val backupOn = settings.backupOnChange
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(romDirUri, launcherDir, launcher) {
        viewModel.ensureLibrary(context, romDirUri, launcherDir, launcher)
    }
    LaunchedEffect(
        repo.systems,
        viewModel.selectedSystems,
        viewModel.target,
        launcherDir,
        session.isRunning,
    ) {
        if (!session.isRunning) viewModel.refresh()
    }

    val platforms = repo.systems.filter {
        it.id !in EsDeSlots.nonRomSystems && it.id !in EsDeSlots.extrasFor(launcher)
    }
    val games = viewModel.games
    val pageCount = max(1, (games.size + PageSize - 1) / PageSize)
    val page = viewModel.page.coerceIn(0, pageCount - 1)
    val visible = games.drop(page * PageSize).take(PageSize)
    val ready = games.count { viewModel.statusOf(it) == TranslateStatus.Ready }
    val empty = games.count { viewModel.statusOf(it) == TranslateStatus.Empty }
    val same = games.count { viewModel.statusOf(it) == TranslateStatus.Same }

    AppScaffold(
        selectedTab = KoverTab.Translate,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(settings.launcherLabel, stringResource(R.string.translate_title)),
        onBack = onBack,
    ) {
        if (romDirUri.isBlank()) {
            EmptyState(
                title = stringResource(R.string.translate_title),
                body = stringResource(R.string.translate_settings_hint),
            )
            return@AppScaffold
        }
        if (launcherDir.isBlank()) {
            EmptyState(
                title = stringResource(R.string.translate_title),
                body = stringResource(R.string.translate_launcher_hint),
            )
            return@AppScaffold
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4),
        ) {
            Chip(
                label = "ES-DE",
                selected = launcher == "es-de",
                enabled = !session.isRunning,
                focusRequester = firstFocus,
                onClick = { viewModel.setLauncher("es-de") },
            )
            Chip(
                label = "NeoStation",
                selected = launcher == "neostation",
                enabled = !session.isRunning,
                onClick = { viewModel.setLauncher("neostation") },
            )
            Chip(
                label = "Custom",
                selected = launcher == "custom",
                enabled = !session.isRunning,
                onClick = { viewModel.setLauncher("custom") },
            )
        }

        SectionTitle(stringResource(R.string.translate_platforms))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4),
        ) {
            platforms.forEach { system ->
                Chip(
                    label = stringResource(
                        R.string.translate_platform_count,
                        repo.systemName(system.id),
                        system.romCount,
                    ),
                    selected = system.id in viewModel.selectedSystems,
                    enabled = !session.isRunning,
                    onClick = { viewModel.toggleSystem(system.id) },
                )
            }
        }

        SectionTitle(stringResource(R.string.translate_target))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4),
        ) {
            TranslateLanguages.forEach { code ->
                Chip(
                    label = code.uppercase(Locale.ROOT),
                    selected = viewModel.target == code,
                    enabled = !session.isRunning,
                    onClick = { viewModel.chooseTarget(code) },
                )
            }
        }

        when {
            viewModel.selectedSystems.isEmpty() -> Text(
                text = stringResource(R.string.translate_no_platforms),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            viewModel.isLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.translate_reading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Text(
                    text = stringResource(
                        R.string.translate_summary,
                        ready,
                        empty,
                        same,
                        languageName(viewModel.target),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (games.isEmpty()) {
                    Text(
                        text = stringResource(R.string.translate_no_roms),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SectionTitle(stringResource(R.string.translate_games))
                    visible.forEach { rom ->
                        GameRow(
                            rom = rom,
                            length = viewModel.sourceLength(rom),
                            status = viewModel.statusOf(rom),
                            target = viewModel.target,
                        )
                    }
                    if (pageCount > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                        ) {
                            Chip(
                                label = stringResource(R.string.translate_prev),
                                selected = false,
                                enabled = page > 0,
                                onClick = { viewModel.changePage(page - 1) },
                            )
                            Text(
                                text = stringResource(R.string.translate_page, page + 1, pageCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Chip(
                                label = stringResource(R.string.translate_next),
                                selected = false,
                                enabled = page < pageCount - 1,
                                onClick = { viewModel.changePage(page + 1) },
                            )
                        }
                    }
                }
            }
        }

        if (!backupOn && ready > 0 && !session.isRunning) {
            Text(
                text = stringResource(R.string.translate_backup_off),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (launcher == "neostation" && !session.isRunning) {
            Text(
                text = stringResource(R.string.translate_neostation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (session.isRunning) {
            LinearProgressIndicator(
                progress = {
                    if (session.total > 0) {
                        session.processed.toFloat() / session.total
                    } else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        R.string.translate_running,
                        session.processed,
                        session.total,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = stringResource(
                        R.string.translate_target_now,
                        languageName(session.target),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.currentGame.isNotBlank()) {
                Text(
                    text = stringResource(R.string.translate_current, session.currentGame),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(
                    if (session.isStopping) R.string.translate_stopping
                    else R.string.translate_continue_hint,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(
                label = stringResource(
                    if (session.isStopping) R.string.translate_stopping
                    else R.string.translate_stop,
                ),
                enabled = !session.isStopping,
                onClick = { session.cancel() },
            )
        } else {
            if (session.networkError) {
                Text(
                    text = stringResource(R.string.translate_no_internet),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (session.providerError) {
                Text(
                    text = stringResource(
                        R.string.translate_provider_stopped,
                        session.failed,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (session.finished && (session.processed > 0 || !session.networkError)) {
                Text(
                    text = if (session.cancelled || session.networkError || session.providerError) {
                        stringResource(
                            R.string.translate_stopped,
                            session.translated,
                            session.total,
                        )
                    } else {
                        stringResource(
                            R.string.translate_done,
                            session.translated,
                            session.skippedSame,
                            session.skippedEmpty,
                            session.failed,
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            PrimaryButton(
                label = stringResource(R.string.translate_start),
                enabled = ready > 0 && !viewModel.isLoading,
                onClick = { confirm = true },
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.translate_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Gap6)) {
                    Text(
                        stringResource(
                            R.string.translate_confirm_body,
                            languageName(viewModel.target),
                        ),
                    )
                    Text(
                        stringResource(
                            if (backupOn) R.string.translate_confirm_backup_on
                            else R.string.translate_confirm_backup_off,
                        ),
                        color = if (backupOn) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                    if (launcher == "neostation") {
                        Text(
                            stringResource(R.string.translate_neostation),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                DialogButton(stringResource(R.string.translate_confirm_start)) {
                    confirm = false
                    viewModel.start()
                }
            },
            dismissButton = {
                DialogButton(stringResource(R.string.common_cancel)) { confirm = false }
            },
        )
    }
}

@Composable
private fun GameRow(
    rom: Rom,
    length: Int,
    status: TranslateStatus,
    target: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
    ) {
        Text(
            text = rom.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.translate_chars, length),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when (status) {
                TranslateStatus.Ready -> stringResource(R.string.translate_status_ready)
                TranslateStatus.Empty -> stringResource(R.string.translate_status_empty)
                TranslateStatus.Same -> stringResource(R.string.translate_status_same, languageName(target))
            },
            style = MaterialTheme.typography.labelSmall,
            color = when (status) {
                TranslateStatus.Ready -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            selected -> accent.copy(alpha = 0.18f)
            focused -> accent.copy(alpha = 0.10f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = if (focused && enabled) Dimens.FocusBorder else 1.dp,
            color = when {
                focused && enabled -> accent
                selected -> accent.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    selected || focused -> accent
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth().height(38.dp),
        selected = enabled,
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                focused -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun DialogButton(
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (focused || accent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun languageName(tag: String): String =
    Locale(tag).getDisplayLanguage()
        .takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase() }
        ?: tag.uppercase(Locale.ROOT)
