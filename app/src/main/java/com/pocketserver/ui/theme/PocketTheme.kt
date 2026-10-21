package com.pocketserver.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkPocketColors.primaryButtonFill,
    onPrimary = DarkPocketColors.primaryButtonText,
    secondary = DarkPocketColors.accent,
    onSecondary = DarkPocketColors.background,
    background = DarkPocketColors.background,
    onBackground = DarkPocketColors.textPrimary,
    surface = DarkPocketColors.surface,
    onSurface = DarkPocketColors.textPrimary,
    surfaceVariant = DarkPocketColors.raised,
    onSurfaceVariant = DarkPocketColors.textSecondary,
    outline = DarkPocketColors.border,
    outlineVariant = DarkPocketColors.borderSubtle,
    error = DarkPocketColors.error,
    onError = DarkPocketColors.background
)

private val LightColorScheme = lightColorScheme(
    primary = LightPocketColors.primaryButtonFill,
    onPrimary = LightPocketColors.primaryButtonText,
    secondary = LightPocketColors.accent,
    onSecondary = LightPocketColors.background,
    background = LightPocketColors.background,
    onBackground = LightPocketColors.textPrimary,
    surface = LightPocketColors.surface,
    onSurface = LightPocketColors.textPrimary,
    surfaceVariant = LightPocketColors.raised,
    onSurfaceVariant = LightPocketColors.textSecondary,
    outline = LightPocketColors.border,
    outlineVariant = LightPocketColors.borderSubtle,
    error = LightPocketColors.error,
    onError = LightPocketColors.background
)

@Composable
fun PocketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val pocketColors = if (darkTheme) DarkPocketColors else LightPocketColors

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val pocketTypography = PocketTypography()
    val pocketShapes = PocketShapes()

    CompositionLocalProvider(
        LocalPocketColors provides pocketColors,
        LocalPocketTypography provides pocketTypography,
        LocalPocketShapes provides pocketShapes
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTypography,
            shapes = MaterialShapes,
            content = content
        )
    }
}

object PocketTheme {
    val colors: PocketColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPocketColors.current

    val typography: PocketTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalPocketTypography.current

    val shapes: PocketShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalPocketShapes.current
}
