package com.ark.kover.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Full app themes. [SYSTEM] follows the Material You dynamic palette (system accent),
 * every other preset replaces the whole palette: background, surfaces and accent.
 */
enum class AppThemePreset(val id: String, val dot: Color) {
    SYSTEM("system", Color(0xFF7E57C2)),
    FOREST("forest", Color(0xFF4CAF50)),
    RETRO("retro", Color(0xFFDDDBD6)),
    ARCADE("arcade", Color(0xFFFF2D95)),
    OCEAN("ocean", Color(0xFF00BCD4)),
    RUBY("ruby", Color(0xFFE53935)),
    NORD("nord", Color(0xFF88C0D0)),
    MONO("mono", Color(0xFF9E9E9E));

    companion object {
        fun fromId(id: String?): AppThemePreset =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

private fun onColor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF101010) else Color.White

private fun dark(
    primary: Color,
    background: Color,
    surface: Color,
    surfaceVariant: Color,
    onSurface: Color,
    secondary: Color = primary,
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onColor(primary),
    primaryContainer = primary.copy(alpha = 0.22f),
    onPrimaryContainer = primary,
    secondary = secondary,
    onSecondary = onColor(secondary),
    background = background,
    onBackground = onSurface,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurface.copy(alpha = 0.7f),
    outline = onSurface.copy(alpha = 0.25f),
    outlineVariant = onSurface.copy(alpha = 0.12f),
)

private fun light(
    primary: Color,
    background: Color,
    surface: Color,
    surfaceVariant: Color,
    onSurface: Color,
    secondary: Color = primary,
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = onColor(primary),
    primaryContainer = primary.copy(alpha = 0.16f),
    onPrimaryContainer = primary,
    secondary = secondary,
    onSecondary = onColor(secondary),
    background = background,
    onBackground = onSurface,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurface.copy(alpha = 0.7f),
    outline = onSurface.copy(alpha = 0.25f),
    outlineVariant = onSurface.copy(alpha = 0.12f),
)

/** Palette for a fixed preset. [SYSTEM] is handled by dynamic colors in KoverTheme. */
fun presetColorScheme(preset: AppThemePreset, darkTheme: Boolean): ColorScheme = when (preset) {
    AppThemePreset.SYSTEM -> if (darkTheme) dark(
        primary = Color(0xFF9FD5B6),
        background = Color(0xFF101412),
        surface = Color(0xFF101412),
        surfaceVariant = Color(0xFF404944),
        onSurface = Color(0xFFE1E4DF),
    ) else light(
        primary = Color(0xFF236B4A),
        background = Color(0xFFF6FBF5),
        surface = Color(0xFFF6FBF5),
        surfaceVariant = Color(0xFFDCE5DC),
        onSurface = Color(0xFF181D1A),
    )

    AppThemePreset.FOREST -> if (darkTheme) dark(
        primary = Color(0xFF8BD5A8),
        background = Color(0xFF08120C),
        surface = Color(0xFF0F1D14),
        surfaceVariant = Color(0xFF1A2C20),
        onSurface = Color(0xFFE0EBE3),
    ) else light(
        primary = Color(0xFF2E6B4F),
        background = Color(0xFFF3FAF4),
        surface = Color(0xFFFBFEFB),
        surfaceVariant = Color(0xFFDDEDE1),
        onSurface = Color(0xFF142018),
    )

    AppThemePreset.RETRO -> if (darkTheme) dark(
        primary = Color(0xFF7FA2F2),
        secondary = Color(0xFFE36A72),
        background = Color(0xFF2A2C31),
        surface = Color(0xFF34373D),
        surfaceVariant = Color(0xFF3E4248),
        onSurface = Color(0xFFEDEBE6),
    ) else light(
        primary = Color(0xFF2D55B0),
        secondary = Color(0xFFCC2F38),
        background = Color(0xFFDDDBD6),
        surface = Color(0xFFEDEBE6),
        surfaceVariant = Color(0xFFCBC8C6),
        onSurface = Color(0xFF3E4248),
    )

    AppThemePreset.ARCADE -> if (darkTheme) dark(
        primary = Color(0xFFFF2D95),
        secondary = Color(0xFF00E5FF),
        background = Color(0xFF0A0612),
        surface = Color(0xFF130B20),
        surfaceVariant = Color(0xFF1F1235),
        onSurface = Color(0xFFF1E7FF),
    ) else light(
        primary = Color(0xFFB0105F),
        secondary = Color(0xFF00768A),
        background = Color(0xFFFFF2F8),
        surface = Color(0xFFFFFAFC),
        surfaceVariant = Color(0xFFF6DCEC),
        onSurface = Color(0xFF1F0A16),
    )

    AppThemePreset.OCEAN -> if (darkTheme) dark(
        primary = Color(0xFF22D3EE),
        background = Color(0xFF041219),
        surface = Color(0xFF081C26),
        surfaceVariant = Color(0xFF0F2E3B),
        onSurface = Color(0xFFD9F4FB),
    ) else light(
        primary = Color(0xFF0E7490),
        background = Color(0xFFF0FAFD),
        surface = Color(0xFFFAFEFF),
        surfaceVariant = Color(0xFFD6EDF4),
        onSurface = Color(0xFF08222B),
    )

    AppThemePreset.RUBY -> if (darkTheme) dark(
        primary = Color(0xFFFF5252),
        background = Color(0xFF120707),
        surface = Color(0xFF1C0D0D),
        surfaceVariant = Color(0xFF2C1414),
        onSurface = Color(0xFFF5E4E4),
    ) else light(
        primary = Color(0xFFB3261E),
        background = Color(0xFFFDF4F3),
        surface = Color(0xFFFFFBFB),
        surfaceVariant = Color(0xFFF2DEDC),
        onSurface = Color(0xFF24100F),
    )

    AppThemePreset.NORD -> if (darkTheme) dark(
        primary = Color(0xFF88C0D0),
        secondary = Color(0xFFB48EAD),
        background = Color(0xFF2E3440),
        surface = Color(0xFF3B4252),
        surfaceVariant = Color(0xFF434C5E),
        onSurface = Color(0xFFECEFF4),
    ) else light(
        primary = Color(0xFF3B6E7E),
        background = Color(0xFFF2F5F7),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFDDE3E8),
        onSurface = Color(0xFF2E3440),
    )

    AppThemePreset.MONO -> if (darkTheme) dark(
        primary = Color(0xFFE0E0E0),
        background = Color(0xFF080808),
        surface = Color(0xFF121212),
        surfaceVariant = Color(0xFF222222),
        onSurface = Color(0xFFEDEDED),
    ) else light(
        primary = Color(0xFF333333),
        background = Color(0xFFFAFAFA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE8E8E8),
        onSurface = Color(0xFF161616),
    )
}
