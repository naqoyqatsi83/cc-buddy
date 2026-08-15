package dev.ccbuddy.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Claude's own clay/coral accent, so the app reads as belonging to Claude
// Code rather than a generic green "terminal app" palette.
private val ClaudeClay = Color(0xFFD97757)
private val ClaudeClayDark = Color(0xFFC96442)

private val DarkColors = darkColorScheme(
    primary = ClaudeClay,
    secondary = ClaudeClay,
    background = Color(0xFF262624),
    surface = Color(0xFF30302E)
)

private val LightColors = lightColorScheme(
    primary = ClaudeClayDark,
    secondary = ClaudeClayDark,
    background = Color(0xFFF5F4ED),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun CCBuddyTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
