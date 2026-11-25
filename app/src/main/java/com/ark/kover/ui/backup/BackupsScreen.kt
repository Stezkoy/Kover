package com.ark.kover.ui.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ark.kover.R
import com.ark.kover.core.backup.BackupEntry
import com.ark.kover.core.backup.BackupRepository
import com.ark.kover.core.backup.MetadataField
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.settings.launcherDirUri
import com.ark.kover.ui.common.slotLabel
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.EmptyState
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.components.Thumb
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class BackupsViewModel @Inject constructor(
    private val repo: BackupRepository,
    private val launcher: com.ark.kover.core.launcher.LauncherAdapter,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings

    var entries by mutableStateOf<List<BackupEntry>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var currentMetadata by mutableStateOf<com.ark.kover.core.model.Metadata?>(null)
        private set

    fun load(resolver: ContentResolver, dirUri: Uri?) {
        if (dirUri == null) return
        isLoading = true
        viewModelScope.launch {
            entries = repo.list(resolver, dirUri)
            isLoading = false
        }
    }

    /** Loads the live metadata so the dialog can show only fields that really differ. */
    fun openMetadata(resolver: ContentResolver, dirUri: Uri?, entry: BackupEntry) {
        currentMetadata = null
        if (dirUri == null) return
        viewModelScope.launch {
            currentMetadata = withContext(Dispatchers.IO) {
                launcher.readMetadata(resolver, dirUri, entry.system, entry.romPath)
            }
        }
    }

    fun restoreAsset(resolver: ContentResolver, dirUri: Uri, entry: BackupEntry, label: String) {
        viewModelScope.launch {
            val ok = repo.restoreAsset(resolver, dirUri, entry)
            message = if (ok) label else ""
            if (ok) entries = repo.list(resolver, dirUri)
        }
    }

    fun restoreMetadata(
        resolver: ContentResolver,
        dirUri: Uri,
        entry: BackupEntry,
        field: MetadataField,
        label: String,
    ) {
        viewModelScope.launch {
            val ok = repo.restoreMetadataField(resolver, dirUri, entry, field)
            message = if (ok) label else ""
            if (ok) entries = repo.list(resolver, dirUri)
        }
    }

    fun clearMessage() {
        message = ""
    }
}

@Composable
fun BackupsScreen(
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: BackupsViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val dirUri = settings.launcherDirUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    var restoreAsset by androidx.compose.runtime.remember {
        mutableStateOf<BackupEntry?>(null)
    }
    var metadataEntry by androidx.compose.runtime.remember {
        mutableStateOf<BackupEntry?>(null)
    }

    LaunchedEffect(settings.launcherDirUri) {
        viewModel.load(context.contentResolver, dirUri)
    }

    AppScaffold(
        selectedTab = KoverTab.Backups,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(stringResource(R.string.backups_title)),
        onBack = onBack,
    ) {
        if (dirUri == null) {
            EmptyState(
                title = stringResource(R.string.backups_title),
                body = stringResource(R.string.backups_no_launcher),
            )
            return@AppScaffold
        }

        if (viewModel.message.isNotBlank()) {
            Text(
                text = viewModel.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (viewModel.entries.isEmpty() && !viewModel.isLoading) {
            EmptyState(
                title = stringResource(R.string.backups_empty),
                body = stringResource(R.string.backups_empty_hint),
            )
            return@AppScaffold
        }

        val pageSize = 12
        var page by androidx.compose.runtime.remember { mutableStateOf(0) }
        val pageCount = ((viewModel.entries.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        LaunchedEffect(viewModel.entries.size) {
            if (page > pageCount - 1) page = pageCount - 1
        }
        val pageEntries = viewModel.entries.drop(page * pageSize).take(pageSize)

        pageEntries.forEach { entry ->
            if (entry.kind == "metadata") {
                ActionRow(
                    title = entry.gameName,
                    subtitle = "${stringResource(R.string.backups_meta)} · ${formatTime(entry.timestamp)}",
                    trailing = stringResource(R.string.backups_field_title),
                    onClick = {
                        metadataEntry = entry
                        viewModel.openMetadata(context.contentResolver, dirUri, entry)
                    },
                )
            } else {
                val date = formatTime(entry.timestamp)
                FocusableCard(
                    onClick = { restoreAsset = entry },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp),
                ) { focused ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8),
                    ) {
                        Thumb(
                            model = entry.file.absolutePath,
                            modifier = Modifier.width(26.dp).height(36.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.gameName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (focused) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${slotLabel(entry.kind)} · $date",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (pageCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                FocusableCard(
                    onClick = { if (page > 0) page-- },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) { focused ->
                    Text(
                        text = stringResource(R.string.backups_prev),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (page > 0) {
                            if (focused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        } else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                Text(
                    text = stringResource(R.string.backups_page, page + 1, pageCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                FocusableCard(
                    onClick = { if (page < pageCount - 1) page++ },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) { focused ->
                    Text(
                        text = stringResource(R.string.backups_next),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (page < pageCount - 1) {
                            if (focused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        } else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.backups_restore_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val assetToRestore = restoreAsset
    if (assetToRestore != null && dirUri != null) {
        val label = stringResource(R.string.backups_restored) + ": " + slotLabel(assetToRestore.kind)
        AlertDialog(
            onDismissRequest = { restoreAsset = null },
            title = { Text(assetToRestore.gameName) },
            text = {
                Text(
                    text = "${slotLabel(assetToRestore.kind)} · ${assetToRestore.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreAsset(context.contentResolver, dirUri, assetToRestore, label)
                    restoreAsset = null
                }) {
                    Text(stringResource(R.string.backups_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreAsset = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    val metaToRestore = metadataEntry
    if (metaToRestore != null && dirUri != null) {
        val previous = metaToRestore.metadata
        AlertDialog(
            onDismissRequest = { metadataEntry = null },
            title = { Text(metaToRestore.gameName) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Gap4),
                ) {
                    Text(
                        text = stringResource(R.string.backups_restore_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val restoredPrefix = stringResource(R.string.backups_restored)
                    val restoreLabel = stringResource(R.string.backups_restore)
                    val currentNow = viewModel.currentMetadata
                    val differing = MetadataField.entries.filter { field ->
                        previous != null && currentNow != null &&
                            field.from(previous) != field.from(currentNow)
                    }
                    val fields = when {
                        differing.isNotEmpty() -> differing
                        metaToRestore.changedFields.isNotEmpty() -> metaToRestore.changedFields
                        else -> MetadataField.entries
                    }
                    fields.forEach { field ->
                        val value = previous?.let { field.from(it) }.orEmpty()
                        val fieldLabel = metadataFieldLabel(field)
                        if (value.isNotBlank()) {
                            FocusableCard(
                                onClick = {
                                    viewModel.restoreMetadata(
                                        context.contentResolver,
                                        dirUri,
                                        metaToRestore,
                                        field,
                                        "$restoredPrefix: $fieldLabel",
                                    )
                                    metadataEntry = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 6.dp,
                                ),
                            ) { focused ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = fieldLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = value,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = restoreLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (focused) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { metadataEntry = null }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}

@Composable
private fun metadataFieldLabel(field: MetadataField): String = stringResource(
    when (field) {
        MetadataField.NAME -> R.string.field_name
        MetadataField.DESC -> R.string.field_desc
        MetadataField.RATING -> R.string.field_rating
        MetadataField.RELEASE -> R.string.field_release
        MetadataField.DEVELOPER -> R.string.field_developer
        MetadataField.PUBLISHER -> R.string.field_publisher
        MetadataField.GENRE -> R.string.field_genre
        MetadataField.PLAYERS -> R.string.field_players
    },
)

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
