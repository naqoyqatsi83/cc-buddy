package dev.ccbuddy.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.ccbuddy.app.BuildConfig

private const val WORDMARK = """
 ██████╗ ██████╗          ██████╗ ██╗   ██╗██████╗ ██████╗ ██╗   ██╗
██╔════╝██╔════╝          ██╔══██╗██║   ██║██╔══██╗██╔══██╗╚██╗ ██╔╝
██║     ██║               ██████╔╝██║   ██║██║  ██║██║  ██║ ╚████╔╝
██║     ██║               ██╔══██╗██║   ██║██║  ██║██║  ██║  ╚██╔╝
╚██████╗╚██████╗          ██████╔╝╚██████╔╝██████╔╝██████╔╝   ██║
 ╚═════╝ ╚═════╝          ╚═════╝  ╚═════╝ ╚═════╝ ╚═════╝    ╚═╝
"""

/**
 * ASCII wordmark rendered as real monospace text (not a bitmap) so it
 * stays crisp at any size and recolors with the theme, per the spec.
 */
@Composable
fun AsciiLogo(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = WORDMARK.trim('\n'),
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})" +
                if (BuildConfig.DEBUG) " · debug" else "",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}
