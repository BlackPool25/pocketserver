package com.pocketserver.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class PocketColors(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val border: Color,
    val borderSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primaryButtonFill: Color,
    val primaryButtonText: Color,
    val accent: Color,
    val running: Color,
    val sleeping: Color,
    val starting: Color,
    val warning: Color,
    val error: Color,
    val stopped: Color,
    val isDark: Boolean
)

val DarkPocketColors = PocketColors(
    background = Color(0xFF09090B),
    surface = Color(0xFF111113),
    raised = Color(0xFF18181B),
    border = Color(0xFF27272A),
    borderSubtle = Color(0xFF3F3F46),
    textPrimary = Color(0xFFFAFAFA),
    textSecondary = Color(0xFFA1A1AA),
    textTertiary = Color(0xFF71717A),
    primaryButtonFill = Color(0xFFFAFAFA),
    primaryButtonText = Color(0xFF09090B),
    accent = Color(0xFF38BDF8),
    running = Color(0xFF34D399),
    sleeping = Color(0xFF60A5FA),
    starting = Color(0xFFFBBF24),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    stopped = Color(0xFF71717A),
    isDark = true
)

val LightPocketColors = PocketColors(
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFF4F4F5),
    raised = Color(0xFFE4E4E7),
    border = Color(0xFFD4D4D8),
    borderSubtle = Color(0xFFA1A1AA),
    textPrimary = Color(0xFF09090B),
    textSecondary = Color(0xFF71717A),
    textTertiary = Color(0xFFA1A1AA),
    primaryButtonFill = Color(0xFF09090B),
    primaryButtonText = Color(0xFFFAFAFA),
    accent = Color(0xFF0284C7),
    running = Color(0xFF34D399),
    sleeping = Color(0xFF60A5FA),
    starting = Color(0xFFFBBF24),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    stopped = Color(0xFF71717A),
    isDark = false
)

val LocalPocketColors = staticCompositionLocalOf { DarkPocketColors }
