package com.ark.kover.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ark.kover.R
import com.ark.kover.core.update.UpdateRepository
import com.ark.kover.ui.theme.Dimens
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Popup shown once a newer release is found: download in-app with a visible
 * progress bar, then hand the APK to the system installer.
 *
 * Release notes are not rendered here (they take too much room) — a link to
 * the release page is shown instead.
 */
@Composable
fun UpdateDialog(updates: UpdateRepository) {
    val info = updates.latest
    var dismissedVersion by remember { mutableStateOf<String?>(null) }
    if (info == null || !updates.isUpdateAvailable() || dismissedVersion == info.version) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloaded = updates.downloadedFile

    AlertDialog(
        onDismissRequest = { dismissedVersion = info.version },
        title = { Text(stringResource(R.string.updates_dialog_title, info.version)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                Text(
                    text = stringResource(R.string.updates_current, updates.currentVersion),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (updates.isDownloading) {
                    if (updates.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { updates.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = when {
                                updates.downloadedBytes == 0L ->
                                    stringResource(R.string.updates_connecting)
                                updates.totalBytes > 0 -> stringResource(
                                    R.string.updates_downloading,
                                    (updates.progress * 100).toInt(),
                                )
                                else -> stringResource(
                                    R.string.updates_downloading_size,
                                    mb(updates.downloadedBytes),
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (updates.totalBytes > 0) {
                            Text(
                                text = stringResource(
                                    R.string.updates_download_size,
                                    mb(updates.downloadedBytes),
                                    mb(updates.totalBytes),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (downloaded != null && !updates.isDownloading) {
                    Text(
                        text = stringResource(R.string.updates_downloaded),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
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

                DialogButton(
                    label = stringResource(R.string.updates_changelog),
                    accent = true,
                    onClick = {
                        if (info.url.isNotBlank()) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(info.url)),
                                )
                            }
                        }
                    },
                )
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

private fun mb(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f", bytes / 1048576.0)

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
