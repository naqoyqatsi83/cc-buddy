package dev.ccbuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccbuddy.app.data.ActivePin
import dev.ccbuddy.app.data.PeerSession
import dev.ccbuddy.app.data.PendingPairRequest
import dev.ccbuddy.app.util.LocalAddress
import kotlinx.coroutines.delay

@Composable
fun PairingScreen(
    activePin: ActivePin?,
    localAddresses: List<LocalAddress>,
    peers: List<PeerSession>,
    pendingRequest: PendingPairRequest?,
    onRegeneratePin: () -> Unit,
    onUnpair: (PeerSession) -> Unit,
    onOpenTerminal: (PeerSession) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
        AsciiLogo()
        Spacer(Modifier.height(24.dp))

        PinCard(activePin, onRegeneratePin)
        Spacer(Modifier.height(16.dp))

        Text("This device's addresses", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (localAddresses.isEmpty()) {
            Text("No network interfaces found — connect to Wi-Fi or Tailscale.")
        } else {
            localAddresses.forEach { addr ->
                Text(
                    "${addr.label}: ${addr.ip}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Paired sessions", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (peers.isEmpty()) {
            Text("None yet. Run /buddy-pair <ip> <pin> from a buddy-daemon session.")
        } else {
            Column {
                peers.forEach { peer -> PeerRow(peer, onUnpair, onOpenTerminal) }
            }
        }
    }

    if (pendingRequest != null) {
        PairRequestDialog(pendingRequest)
    }
}

@Composable
private fun PinCard(activePin: ActivePin?, onRegeneratePin: () -> Unit) {
    // Ticks once a second purely to force recomposition of the countdown
    // below -- isExpired()/the remaining-seconds math is recomputed
    // fresh each tick from activePin.expiresAtMillis, not accumulated.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activePin) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remainingSeconds = activePin?.let {
        ((it.expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Pairing PIN", style = MaterialTheme.typography.titleSmall)
                if (remainingSeconds != null) {
                    Text(
                        "expires in ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = activePin?.pin ?: "------",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (activePin == null) "Expired — generate a new one to pair."
                else "Run /buddy-pair <ip> <pin> from your Claude Code session.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRegeneratePin) {
                Text("Regenerate PIN")
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerSession, onUnpair: (PeerSession) -> Unit, onOpenTerminal: (PeerSession) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(peer.deviceName, fontWeight = FontWeight.Medium)
            Text(
                if (peer.connected) "online" else "offline",
                style = MaterialTheme.typography.bodySmall,
                color = if (peer.connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Row {
            if (peer.connected) {
                TextButton(onClick = { onOpenTerminal(peer) }) { Text("Open") }
            }
            OutlinedButton(onClick = { onUnpair(peer) }) {
                Text("Unpair")
            }
        }
    }
}

@Composable
private fun PairRequestDialog(request: PendingPairRequest) {
    AlertDialog(
        onDismissRequest = { request.result.complete(false) },
        title = { Text("Pairing request") },
        text = {
            Text("\"${request.deviceName}\" (${request.remoteAddress}) wants to pair with this session. Allow it to see and control this terminal?")
        },
        confirmButton = {
            Button(onClick = { request.result.complete(true) }) { Text("Accept") }
        },
        dismissButton = {
            OutlinedButton(onClick = { request.result.complete(false) }) { Text("Deny") }
        }
    )
}
