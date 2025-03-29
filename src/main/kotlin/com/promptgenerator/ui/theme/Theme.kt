package com.promptgenerator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D9CDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F3FF),
    onPrimaryContainer = Color(0xFF003244),

    secondary = Color(0xFF56CC9D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F5EC),
    onSecondaryContainer = Color(0xFF003827),

    tertiary = Color(0xFF9B51E0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0DBFF),
    onTertiaryContainer = Color(0xFF3E0069),

    error = Color(0xFFEB5757),
    errorContainer = Color(0xFFFFE5E5),
    onError = Color.White,
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF9FBFC),
    onBackground = Color(0xFF1A1E21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1E21),
    surfaceVariant = Color(0xFFE0E8ED),
    onSurfaceVariant = Color(0xFF43494D),

    outline = Color(0xFF8B9AAE)
)

// Dark theme colors
private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EC6FF),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004B6E),
    onPrimaryContainer = Color(0xFFD1EFFF),

    secondary = Color(0xFF7FE7C6),
    onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF005646),
    onSecondaryContainer = Color(0xFFB3FCE5),

    tertiary = Color(0xFFD5A6FF),
    onTertiary = Color(0xFF42007D),
    tertiaryContainer = Color(0xFF5C01AA),
    onTertiaryContainer = Color(0xFFF4E0FF),

    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF8C0000),
    onError = Color(0xFF5C0000),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF121417),
    onBackground = Color(0xFFE2E6EA),
    surface = Color(0xFF1B1F23),
    onSurface = Color(0xFFE2E6EA),
    surfaceVariant = Color(0xFF444C56),
    onSurfaceVariant = Color(0xFFC5CDD5),

    outline = Color(0xFF99A6B5)
)

/**
 * Theme for Prompt Generator application
 */
@Composable
fun PromptGeneratorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}