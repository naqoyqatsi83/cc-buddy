package dev.ccbuddy.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.ccbuddy.app.BuildConfig
import dev.ccbuddy.app.R

// A previous attempt at this same block-letter banner used
// FontFamily.Monospace and broke on a real device: Android's system
// monospace font doesn't cover the Unicode box-drawing/block glyphs
// (U+2550-256C, U+2580-259F) a figlet banner needs, so those fell back to a
// different font with mismatched character widths and the whole banner
// misaligned. Bundling JetBrains Mono Bold (confirmed via fontTools to
// cover every glyph this banner uses) fixes that at the source instead of
// avoiding block-letter logos altogether.
private val LogoFont = FontFamily(Font(R.font.jetbrains_mono_bold))

private val LOGO_LINES = listOf(
    " ██████╗ ██████╗          ██████╗ ██╗   ██╗██████╗ ██████╗ ██╗   ██╗",
    "██╔════╝██╔════╝          ██╔══██╗██║   ██║██╔══██╗██╔══██╗╚██╗ ██╔╝",
    "██║     ██║               ██████╔╝██║   ██║██║  ██║██║  ██║ ╚████╔╝ ",
    "██║     ██║               ██╔══██╗██║   ██║██║  ██║██║  ██║  ╚██╔╝  ",
    "╚██████╗╚██████╗          ██████╔╝╚██████╔╝██████╔╝██████╔╝   ██║   ",
    " ╚═════╝ ╚═════╝          ╚═════╝  ╚═════╝ ╚═════╝ ╚═════╝    ╚═╝   "
)

@Composable
fun AsciiLogo(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        // The banner is wider than most phone screens even at a small font
        // size (70 monospace columns) -- wrapping it, tried first, made
        // lines fold onto each other and look melted since each glyph is
        // itself a multi-cell block shape. Horizontal scroll keeps every
        // line intact and correctly aligned instead.
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            LOGO_LINES.forEach { line ->
                Text(
                    text = line,
                    fontFamily = LogoFont,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})" +
                if (BuildConfig.DEBUG) " · debug" else "",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}
