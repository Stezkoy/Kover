package com.ark.kover.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Layout tokens shared by every screen (808 x 455 dp gamepad handheld). */
object Dimens {
    val SidebarWidth = 168.dp
    val SidebarItemHeight = 36.dp
    val SidebarItemSpacing = 3.dp
    val SidebarPadding = 6.dp
    val SidebarIconSize = 18.dp
    val SidebarIndicatorWidth = 3.dp
    val SidebarIndicatorHeight = 18.dp

    val ScreenPaddingH = 12.dp
    val ScreenPaddingV = 8.dp
    val Gap2 = 2.dp
    val Gap4 = 4.dp
    val Gap6 = 6.dp
    val Gap8 = 8.dp
    val Gap10 = 10.dp
    val Gap12 = 12.dp

    val CardCorner = 10.dp
    val CardMinHeight = 48.dp
    val CardHPadding = 12.dp
    val CardVPadding = 8.dp

    val FocusBorder = 2.dp
    val ButtonHeight = 36.dp
    val ButtonHeightLarge = 44.dp
    val RowHeight = 34.dp

    val CoverAspect = 0.72f
    val ThumbAspect = 16f / 10f
}

private val KoverTypography = Typography(
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal),
)

/**
 * App theme.
 *
 * @param darkTheme resolved by the caller from the preference (system/light/dark).
 * @param presetId one of [AppThemePreset]; "system" keeps Material You dynamic colors.
 */
@Composable
fun KoverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    presetId: String = "system",
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preset = AppThemePreset.fromId(presetId)
    val colorScheme = if (preset == AppThemePreset.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        presetColorScheme(preset, darkTheme)
    }

    // The XML theme is a Material.Light theme, so the system bars are grey by default.
    // Paint them with the active app background and flip the icon contrast with the theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KoverTypography,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
