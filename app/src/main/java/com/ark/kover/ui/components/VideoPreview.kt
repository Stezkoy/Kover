package com.ark.kover.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ark.kover.R

/**
 * In-app video preview. Works for local ES-DE files and remote scraper URLs.
 */
@Composable
fun VideoPreviewDialog(
    videoUrl: String,
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            VideoSurface(
                url = videoUrl,
                modifier = Modifier.fillMaxWidth().height(230.dp),
            )
        },
        confirmButton = {
            if (confirmLabel != null && onConfirm != null) {
                TextButton(onClick = onConfirm) { Text(confirmLabel) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }
        },
        dismissButton = {
            if (confirmLabel != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
