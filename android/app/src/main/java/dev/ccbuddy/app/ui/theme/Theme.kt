package dev.ccbuddy.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BuddyGreen = Color(0xFF00E5A0)

private val DarkColors = darkColorScheme(
    primary = BuddyGreen,
    secondary = BuddyGreen,
    background = Color(0xFF0D0D0D),
    surface = Color(0xFF161616)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00A876),
    secondary = Color(0xFF00A876)
)

@Composable
fun CCBuddyTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
