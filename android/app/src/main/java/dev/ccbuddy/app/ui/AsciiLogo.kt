package dev.ccbuddy.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ccbuddy.app.BuildConfig

private const val RULE = "========================"
private const val WORDMARK = "C C   B U D D Y"

/**
 * Wordmark rendered as real monospace text (not a bitmap) so it stays
 * crisp at any size and recolors with the theme, per the spec. Deliberately
 * plain rather than a figlet block-letter design: Android's system
 * monospace font doesn't cover the Unicode box-drawing/block glyphs a
 * figlet banner needs, so those fall back to a different font with
 * mismatched character widths and the whole banner misaligns — verified
 * broken on a real device. Sticking to plain ASCII (`=`, letters) avoids
 * the whole class of problem.
 */
@Composable
fun AsciiLogo(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = RULE,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = WORDMARK,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = RULE,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
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
