package com.ark.kover.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ark.kover.R
import com.ark.kover.core.update.UpdateRepository
import com.ark.kover.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * Popup shown once a newer release is found: download in-app with progress,
 * then hand the APK to the system installer.
 */
@Composable
fun UpdateDialog(updates: UpdateRepository) {
    val info = updates.latest
    var dismissedVersion by remember { mutableStateOf<String?>(null) }
    if (info == null || !updates.isUpdateAvailable() || dismissedVersion == info.version) return

    val scope = rememberCoroutineScope()
    val downloaded = updates.downloadedFile
    AlertDialog(
        onDismissRequest = { dismissedVersion = info.version },
        title = { Text(stringResource(R.string.updates_dialog_title, info.version)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                Text(
                    text = stringResource(R.string.updates_current, updates.currentVersion),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        text = info.notes.take(1200),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (updates.isDownloading) {
                    LinearProgressIndicator(
                        progress = { updates.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.updates_downloading,
                            (updates.progress * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                updates.downloadError?.let {
                    Text(
                        text = if (info.apkUrl == null) stringResource(R.string.updates_no_apk)
                        else stringResource(R.string.updates_download_error),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Gap4)) {
                if (downloaded != null && !updates.isDownloading) {
                    DialogButton(stringResource(R.string.updates_install)) {
                        updates.install()
                        dismissedVersion = info.version
                    }
                } else if (!updates.isDownloading) {
                    DialogButton(stringResource(R.string.updates_download)) {
                        scope.launch { updates.download() }
                    }
                }
            }
        },
        dismissButton = {
            DialogButton(stringResource(R.string.updates_later)) {
                dismissedVersion = info.version
            }
        },
    )
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
