package dev.ccbuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ccbuddy.app.data.SettingsStore

@Composable
fun SettingsScreen(
    fontSizeOverride: Int?,
    compactMode: Boolean,
    showConnectionDetails: Boolean,
    batteryOptimizationExempt: Boolean,
    onRequestBatteryExemption: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onFontSizeOverrideChange: (Int?) -> Unit,
    onCompactModeChange: (Boolean) -> Unit,
    onShowConnectionDetailsChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Done") }
        }
        Spacer(Modifier.height(24.dp))

        Text("Terminal font size", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            if (fontSizeOverride == null) "Auto — fits the real PC terminal's row count to the screen"
            else "Fixed at ${fontSizeOverride}sp — overrides the auto-fit calculation",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = (fontSizeOverride ?: 0).toFloat(),
                onValueChange = {
                    onFontSizeOverrideChange(if (it < SettingsStore.MIN_FONT_SIZE) null else it.toInt())
                },
                valueRange = 0f..SettingsStore.MAX_FONT_SIZE.toFloat(),
                steps = SettingsStore.MAX_FONT_SIZE - 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(fontSizeOverride?.toString() ?: "Auto", modifier = Modifier.padding(start = 4.dp))
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Compact mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Shrinks the bottom button row so more terminal is visible",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = compactMode, onCheckedChange = onCompactModeChange)
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show connection details", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Shows round-trip latency next to each paired session",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = showConnectionDetails, onCheckedChange = onShowConnectionDetailsChange)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Battery optimization", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (batteryOptimizationExempt) "Exempt — pairing stays stable in the background"
                    else "Not exempt — Android may kill the connection to save power",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            // There's no direct "un-exempt" action to request (only Android
            // itself can hand out the exemption) -- once granted, the best
            // this can do is open this app's own system Settings page,
            // where the user can find battery options and flip it back
            // themselves.
            TextButton(onClick = if (batteryOptimizationExempt) onOpenBatterySettings else onRequestBatteryExemption) {
                Text(if (batteryOptimizationExempt) "App settings" else "Disable")
            }
        }
    }
}
