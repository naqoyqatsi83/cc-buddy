package dev.ccbuddy.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
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

// The 10sp size below was only ever fit-tested on wider/denser screens
// (e.g. the Galaxy A54 this app was first built against) -- at a fixed
// 10sp the 68-monospace-column banner ran off the right edge of a
// narrower Galaxy A34 with no visible hint it was scrollable, reading as
// broken rather than "swipe for more". Same auto-fit-then-clamp shape as
// the terminal mirror's own font sizing (xterm/index.html) -- shrink only
// as far as needed to fit this screen's actual width, down to a floor,
// rather than a single fixed size tuned for one device.
private const val MAX_LOGO_FONT_SIZE = 10
private const val MIN_LOGO_FONT_SIZE = 5

@Composable
fun AsciiLogo(modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    // All lines are the same character count by construction (a monospace
    // block-letter grid), so the widest line's measured width represents
    // every line -- no need to measure all six.
    val widestLine = remember { LOGO_LINES.maxByOrNull { it.length } ?: "" }

    Column(modifier = modifier) {
        BoxWithConstraints {
            var fontSizeSp = MAX_LOGO_FONT_SIZE
            while (fontSizeSp > MIN_LOGO_FONT_SIZE) {
                val width = textMeasurer.measure(
                    text = widestLine,
                    style = TextStyle(fontFamily = LogoFont, fontSize = fontSizeSp.sp)
                ).size.width
                if (width <= constraints.maxWidth) break
                fontSizeSp--
            }
            // Horizontal scroll stays as a fallback for whatever's still
            // too wide at the floor size (e.g. an even narrower screen, or
            // a large system font-scale setting) -- every line stays intact
            // and correctly aligned rather than wrapping and folding block
            // glyphs onto each other.
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                LOGO_LINES.forEach { line ->
                    Text(
                        text = line,
                        fontFamily = LogoFont,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp + 2).sp,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
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
