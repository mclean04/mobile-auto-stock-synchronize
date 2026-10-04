package com.example.finance_planning.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF17365D), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7F7), onPrimaryContainer = Color(0xFF17365D),
    secondary = Color(0xFF4C6077), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EAF2), onSecondaryContainer = Color(0xFF26384D),
    tertiary = Color(0xFF496359), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDFEAE4), onTertiaryContainer = Color(0xFF273D34),
    background = Color(0xFFF6F7FA), onBackground = Color(0xFF1B2430),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1B2430),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F7),
    surfaceContainer = Color(0xFFEAEFF5), surfaceContainerHigh = Color(0xFFE5EAF1),
    surfaceContainerHighest = Color(0xFFDFE5ED),
    surfaceVariant = Color(0xFFE5EAF1), onSurfaceVariant = Color(0xFF485566),
    outline = Color(0xFF727F8E), outlineVariant = Color(0xFFD3DAE4),
    error = Color(0xFFA62B32), onError = Color.White,
    errorContainer = Color(0xFFFFE2E1), onErrorContainer = Color(0xFF741B22)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC7F7), onPrimary = Color(0xFF102F53),
    primaryContainer = Color(0xFF263E5F), onPrimaryContainer = Color(0xFFDCE7F7),
    secondary = Color(0xFFB8C8DD), onSecondary = Color(0xFF26384D),
    secondaryContainer = Color(0xFF34465D), onSecondaryContainer = Color(0xFFE4EAF2),
    tertiary = Color(0xFFB6CEBF), onTertiary = Color(0xFF243C30),
    tertiaryContainer = Color(0xFF364D40), onTertiaryContainer = Color(0xFFDFEAE4),
    background = Color(0xFF11161D), onBackground = Color(0xFFE4E9F1),
    surface = Color(0xFF171D26), onSurface = Color(0xFFE4E9F1),
    surfaceContainerLowest = Color(0xFF0E131A), surfaceContainerLow = Color(0xFF1C232D),
    surfaceContainer = Color(0xFF222A35), surfaceContainerHigh = Color(0xFF29323E),
    surfaceContainerHighest = Color(0xFF323C49),
    surfaceVariant = Color(0xFF323C49), onSurfaceVariant = Color(0xFFB8C3D2),
    outline = Color(0xFF8794A6), outlineVariant = Color(0xFF3D4858),
    error = Color(0xFFFFB4B5), onError = Color(0xFF5E1720),
    errorContainer = Color(0xFF57272E), onErrorContainer = Color(0xFFFFDADB)
)

/** Fixed app palette. The legacy parameter is retained for existing preview callers. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Finance_planningTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(16.dp)), content = content)
}
