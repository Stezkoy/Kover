package com.ark.kover.ui.translate

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.rememberInitialFocus
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Same launcher switch as the Library screen. */
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
            settingsRepo.update { it.copy(launcherType = type) }
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

        if (launcherDir.isBlank()) {
            EmptyState(
                title = stringResource(R.string.translate_title),
                body = stringResource(R.string.translate_launcher_hint, settings.launcherLabel),
            )
            return@AppScaffold
        }

        SectionHeader(stringResource(R.string.translate_platforms))
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

        SectionHeader(stringResource(R.string.translate_target))
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            viewModel.isLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.translate_reading),
                    style = MaterialTheme.typography.labelMedium,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (games.isEmpty()) {
                    Text(
                        text = stringResource(R.string.translate_no_roms),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SectionHeader(stringResource(R.string.translate_games))
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
                                style = MaterialTheme.typography.labelMedium,
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
            WarningBanner(
                text = stringResource(R.string.translate_backup_off),
                error = true,
            )
        }
        if (launcher == "neostation" && !session.isRunning) {
            WarningBanner(
                text = stringResource(R.string.translate_neostation),
                error = false,
            )
        }

        if (session.isRunning) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
                ) {
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
                            style = MaterialTheme.typography.labelMedium,
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
                            text = stringResource(
                                R.string.translate_current,
                                session.currentGame,
                            ),
                            style = MaterialTheme.typography.bodySmall,
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
                }
            }
            PrimaryAction(
                label = stringResource(
                    if (session.isStopping) R.string.translate_stopping
                    else R.string.translate_stop,
                ),
                enabled = !session.isStopping,
                destructive = true,
                onClick = { session.cancel() },
            )
        } else {
            if (session.networkError) {
                WarningBanner(
                    text = stringResource(R.string.translate_no_internet),
                    error = true,
                )
            }
            if (session.providerError) {
                WarningBanner(
                    text = stringResource(
                        R.string.translate_provider_stopped,
                        session.failed,
                    ),
                    error = true,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PrimaryAction(
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
                DialogTextButton(
                    label = stringResource(R.string.translate_confirm_start),
                    emphasized = true,
                ) {
                    confirm = false
                    viewModel.start()
                }
            },
            dismissButton = {
                DialogTextButton(stringResource(R.string.common_cancel)) { confirm = false }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GameRow(
    rom: Rom,
    length: Int,
    status: TranslateStatus,
    target: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
    ) {
        Text(
            text = rom.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.translate_chars, length),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusPill(status = status, target = target)
    }
}

@Composable
private fun StatusPill(status: TranslateStatus, target: String) {
    val colors = MaterialTheme.colorScheme
    val container = when (status) {
        TranslateStatus.Ready -> colors.primaryContainer
        TranslateStatus.Same -> colors.surfaceVariant
        TranslateStatus.Empty -> colors.surfaceVariant.copy(alpha = 0.45f)
    }
    val content = when (status) {
        TranslateStatus.Ready -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }
    val label = when (status) {
        TranslateStatus.Ready -> stringResource(R.string.translate_status_ready)
        TranslateStatus.Empty -> stringResource(R.string.translate_status_empty)
        TranslateStatus.Same -> stringResource(R.string.translate_status_same, languageName(target))
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun WarningBanner(text: String, error: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (error) colors.errorContainer.copy(alpha = 0.60f)
        else colors.surfaceVariant.copy(alpha = 0.35f),
        contentColor = if (error) colors.onErrorContainer else colors.onSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Surface(
        color = when {
            !enabled -> colors.onSurface.copy(alpha = 0.12f)
            selected -> colors.primaryContainer
            else -> colors.surfaceVariant.copy(alpha = 0.55f)
        },
        contentColor = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            selected -> colors.onPrimaryContainer
            else -> colors.onSurfaceVariant
        },
        shape = shape,
        border = if (focused && enabled) {
            BorderStroke(Dimens.FocusBorder, colors.primary)
        } else null,
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.CardCorner)
    val container = when {
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        destructive -> colors.errorContainer
        else -> colors.primary
    }
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        destructive -> colors.onErrorContainer
        else -> colors.onPrimary
    }
    val ring = if (destructive) colors.onErrorContainer else colors.onPrimary
    Surface(
        color = container,
        contentColor = content,
        shape = shape,
        border = if (focused && enabled) BorderStroke(Dimens.FocusBorder, ring) else null,
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DialogTextButton(
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Surface(
        color = if (focused) colors.primary.copy(alpha = 0.10f) else Color.Transparent,
        contentColor = if (emphasized) colors.primary else colors.onSurfaceVariant,
        shape = shape,
        border = if (focused) BorderStroke(Dimens.FocusBorder, colors.primary) else null,
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun languageName(tag: String): String =
    Locale(tag).getDisplayLanguage()
        .takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase() }
        ?: tag.uppercase(Locale.ROOT)
