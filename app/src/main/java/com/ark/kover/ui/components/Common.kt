package com.ark.kover.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ark.kover.ui.theme.Dimens

/**
 * A card that can be driven by touch, D-pad and the A button.
 * Shows a primary-colored focus ring so the gamepad cursor is always visible.
 */
@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(Dimens.CardCorner),
    focusRequester: FocusRequester? = null,
    contentAlignment: Alignment = Alignment.Center,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = Dimens.CardHPadding, vertical = Dimens.CardVPadding),
    content: @Composable androidx.compose.foundation.layout.BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    Surface(
        shape = shape,
        color = when {
            focused -> accent.copy(alpha = if (selected) 0.22f else 0.14f)
            selected -> accent.copy(alpha = 0.20f)
            else -> colors.surfaceVariant.copy(alpha = 0.55f)
        },
        contentColor = colors.onSurface,
        border = if (focused) BorderStroke(Dimens.FocusBorder, accent) else null,
        modifier = modifier
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Box(modifier = Modifier.padding(contentPadding), contentAlignment = contentAlignment) {
            content(focused)
        }
    }
}

/**
 * M3 filter/choice chip: tonal idle, primary-container selection, focus ring for gamepad.
 * Selection never changes the label width (no checkmark, no font weight switch).
 */
@Composable
fun KoverChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
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
        border = if (focused && enabled) BorderStroke(Dimens.FocusBorder, colors.primary) else null,
        modifier = modifier
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

enum class KoverButtonStyle { Primary, Secondary, Text }

/** Filled/tonal/text action button with an M3 focus ring and disabled state. */
@Composable
fun KoverButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: KoverButtonStyle = KoverButtonStyle.Primary,
    destructive: Boolean = false,
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = if (style == KoverButtonStyle.Text) RoundedCornerShape(8.dp)
    else RoundedCornerShape(Dimens.CardCorner)
    val container = when {
        !enabled -> if (style == KoverButtonStyle.Text) Color.Transparent
        else colors.onSurface.copy(alpha = 0.12f)
        style == KoverButtonStyle.Text ->
            if (focused) colors.primary.copy(alpha = 0.10f) else Color.Transparent
        destructive -> colors.errorContainer
        style == KoverButtonStyle.Primary -> colors.primary
        else -> colors.primaryContainer
    }
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        destructive -> colors.onErrorContainer
        style == KoverButtonStyle.Primary -> colors.onPrimary
        style == KoverButtonStyle.Secondary -> colors.onPrimaryContainer
        else -> colors.primary
    }
    val ring = when {
        destructive -> colors.onErrorContainer
        style == KoverButtonStyle.Primary -> colors.onPrimary
        else -> colors.primary
    }
    Surface(
        color = container,
        contentColor = content,
        shape = shape,
        border = if (focused && enabled) BorderStroke(Dimens.FocusBorder, ring) else null,
        modifier = modifier
            .height(
                when {
                    compact -> 36.dp
                    style == KoverButtonStyle.Text -> 36.dp
                    else -> 40.dp
                },
            )
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
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = if (compact) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelLarge,
                fontWeight = if (style == KoverButtonStyle.Primary) FontWeight.SemiBold
                else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** Compact row: leading icon, title, optional subtitle, optional trailing text. */
@Composable
fun ActionRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(Dimens.CardMinHeight),
        focusRequester = focusRequester,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dimens.CardHPadding, vertical = 6.dp),
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun FieldLabel(text: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Remote or local image with a neutral placeholder while loading/absent. */
@Composable
fun Thumb(
    model: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignedStart: Boolean = false,
) {
    val shape = RoundedCornerShape(6.dp)
    val context = LocalContext.current
    // Local files can be replaced in place (e.g. a regenerated miximage), so include
    // the modification time in the cache key to avoid showing a stale bitmap.
    val request: Any? = if (model is String && model.startsWith("/")) {
        val stamp = runCatching { java.io.File(model).lastModified() }.getOrDefault(0L)
        coil.request.ImageRequest.Builder(context)
            .data(model)
            .memoryCacheKey("$model@$stamp")
            .diskCacheKey("$model@$stamp")
            .build()
    } else {
        model
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        contentAlignment = if (alignedStart) Alignment.CenterStart else Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "—",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Artwork preview that understands non-image ES-DE slots: videos show the first frame
 * with a play glyph, manuals the first PDF page with a PDF badge, everything else the image.
 */
@Composable
fun MediaThumb(
    kind: String,
    model: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackModel: Any? = null,
) {
    val context = LocalContext.current
    var poster by remember(kind, model, fallbackModel) { mutableStateOf<String?>(null) }
    LaunchedEffect(kind, model, fallbackModel) {
        poster = when {
            // A screenshot looks better than a video frame and is always available for ES-DE sets.
            kind == "videos" && fallbackModel is String -> fallbackModel
            model is String &&
                (kind == "videos" || kind == "manuals") &&
                !model.startsWith("http") ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.ark.kover.core.media.MediaPreview.poster(context, model, kind)
                }
            else -> null
        }
    }
    when {
        kind == "videos" -> Box(modifier = modifier) {
            Thumb(model = poster, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "▶",
                    style = MaterialTheme.typography.titleLarge,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
        }

        kind == "manuals" && model != null -> Box(modifier = modifier) {
            Thumb(model = poster, modifier = Modifier.fillMaxSize())
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
            ) {
                Text(
                    text = "PDF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        else -> Thumb(model = model, modifier = modifier, contentScale = contentScale)
    }
}

/** Compact single-line field: no floating label, text is always vertically centred. */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        interactionSource = interaction,
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.45f))
            .border(
                width = if (focused) Dimens.FocusBorder else 1.dp,
                color = if (focused) colors.primary else colors.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp),
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
fun EmptyState(    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Dimens.Gap12),
        verticalArrangement = Arrangement.spacedBy(Dimens.Gap6),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}
