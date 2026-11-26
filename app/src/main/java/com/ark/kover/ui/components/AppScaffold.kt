package com.ark.kover.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ark.kover.R
import com.ark.kover.ui.theme.Dimens
import java.util.Locale

/** Process-wide UI preferences that must survive navigation. */
object UiState {
    var sidebarCollapsed by mutableStateOf(false)
}

/** Lets the shell open the About screen without every screen passing a callback. */
val LocalAboutAction = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/** Pixel font used for the app wordmark. */
private val RetroFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.ark.kover.R.font.press_start_2p),
)

/** Material "translate" glyph, not part of material-icons-core. */
private val TranslateIcon: ImageVector = ImageVector.Builder(
    name = "Translate",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = androidx.compose.ui.graphics.vector.PathParser()
        .parsePathString(
            "M12.87,15.07l-2.54,-2.51l0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53L17,6L17,4h-7" +
                "L10,2L8,2v2L1,4v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 " +
                "6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76," +
                "-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33" +
                "L19.12,17h-3.24z",
        ).toNodes(),
    fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
).build()

enum class KoverTab(val icon: ImageVector) {
    Library(Icons.Filled.Home),
    Scrapers(Icons.Filled.Search),
    Translate(TranslateIcon),
    Launcher(Icons.Filled.PlayArrow),
    Backups(Icons.Filled.List),
    Settings(Icons.Filled.Settings),
}

@Composable
fun navLabel(tab: KoverTab): String = stringResource(
    when (tab) {
        KoverTab.Library -> R.string.nav_library
        KoverTab.Scrapers -> R.string.nav_scrapers
        KoverTab.Translate -> R.string.nav_translate
        KoverTab.Launcher -> R.string.nav_launcher
        KoverTab.Backups -> R.string.nav_backups
        KoverTab.Settings -> R.string.nav_settings
    },
)

/**
 * Two-pane shell: collapsible category rail on the left, breadcrumb + content on the right.
 * Everything inside is reachable with the D-pad; the focused item gets a primary ring.
 */
@Composable
fun AppScaffold(
    selectedTab: KoverTab,
    onTabSelected: (KoverTab) -> Unit,
    aboutSelected: Boolean = false,
    tabLabel: @Composable (KoverTab) -> String = { navLabel(it) },
    breadcrumbs: List<String> = emptyList(),
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SidebarRail(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            tabLabel = tabLabel,
            aboutSelected = aboutSelected,
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            HeaderBar(breadcrumbs = breadcrumbs, onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = Dimens.ScreenPaddingH, vertical = Dimens.ScreenPaddingV),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap8),
                content = content,
            )
        }
    }
}

@Composable
private fun SidebarRail(
    selectedTab: KoverTab,
    onTabSelected: (KoverTab) -> Unit,
    tabLabel: @Composable (KoverTab) -> String,
    aboutSelected: Boolean,
) {
    val collapsed = UiState.sidebarCollapsed
    val railWidth: Dp = if (collapsed) 56.dp else Dimens.SidebarWidth
    Column(
        modifier = Modifier
            .width(railWidth)
            .fillMaxHeight()
            .clip(
                RoundedCornerShape(
                    topEnd = Dimens.CardCorner,
                    bottomEnd = Dimens.CardCorner,
                ),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(Dimens.SidebarPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SidebarItemSpacing),
    ) {
        if (collapsed) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.ark.kover.R.drawable.ic_launcher_carpet),
                contentDescription = "Kover",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .align(Alignment.CenterHorizontally),
            )
        } else {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.ark.kover.R.drawable.ic_launcher_carpet),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)),
                )
                Text(
                    text = "KOVER",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = RetroFont,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.SidebarItemSpacing),
        ) {
            KoverTab.entries.forEach { tab ->
                SidebarItem(
                    label = tabLabel(tab),
                    icon = tab.icon,
                    selected = tab == selectedTab && !aboutSelected,
                    collapsed = collapsed,
                    onClick = { onTabSelected(tab) },
                )
            }

            val about = LocalAboutAction.current
            if (about != null) {
                SidebarItem(
                    label = androidx.compose.ui.res.stringResource(com.ark.kover.R.string.about_title),
                    icon = Icons.Filled.Info,
                    selected = aboutSelected,
                    collapsed = collapsed,
                    onClick = about,
                )
            }
        }

        SidebarToggle(collapsed = collapsed)
    }
}

@Composable
private fun SidebarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = MaterialTheme.colorScheme.primary
    val background by animateColorAsState(
        targetValue = when {
            selected && focused -> accent.copy(alpha = 0.32f)
            selected -> accent.copy(alpha = 0.18f)
            focused -> MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        label = "sidebarBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected || focused) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "sidebarFg",
    )
    Surface(
        color = background,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(
            width = if (focused) Dimens.FocusBorder else 0.dp,
            color = if (focused) accent else Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.SidebarItemHeight)
            .clip(RoundedCornerShape(9.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        if (collapsed) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) accent else contentColor,
                    modifier = Modifier.size(Dimens.SidebarIconSize),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap8),
            ) {
                Box(
                    modifier = Modifier
                        .width(Dimens.SidebarIndicatorWidth)
                        .height(Dimens.SidebarIndicatorHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (selected) accent else Color.Transparent),
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) accent else contentColor,
                    modifier = Modifier.size(Dimens.SidebarIconSize),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SidebarToggle(collapsed: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = if (focused) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(
            width = if (focused) Dimens.FocusBorder else 0.dp,
            color = if (focused) accent else Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { UiState.sidebarCollapsed = !collapsed },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
        ) {
            Icon(
                imageVector = if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(
                    if (collapsed) R.string.nav_expand else R.string.nav_collapse,
                ),
                tint = if (focused) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            if (!collapsed) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.nav_collapse),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HeaderBar(
    breadcrumbs: List<String>,
    onBack: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.ScreenPaddingH, end = Dimens.ScreenPaddingH, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
    ) {
        if (onBack != null) {
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Surface(
                color = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(
                    width = if (focused) Dimens.FocusBorder else 0.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
                modifier = Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(interactionSource = interaction, indication = null, onClick = onBack),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.common_back),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text(
            text = breadcrumbs.joinToString("  ›  ") { it.uppercase(Locale.getDefault()) },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Requests gamepad focus for the first meaningful control of a screen. */
@Composable
fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { requester.requestFocus() }
    }
    return requester
}
