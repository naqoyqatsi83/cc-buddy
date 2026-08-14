package dev.ccbuddy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.ccbuddy.app.ui.PairingScreen
import dev.ccbuddy.app.ui.theme.CCBuddyTheme
import dev.ccbuddy.app.util.NetworkUtils

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureNotificationPermission()
        BuddyForegroundService.start(this)

        val app = application as BuddyApp

        setContent {
            CCBuddyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val activePin by app.pairingState.activePin.collectAsState()
                    val pendingRequest by app.pairingState.pendingRequest.collectAsState()
                    val peers by app.peerRepository.peers.collectAsState()
                    var localAddresses by remember { mutableStateOf(NetworkUtils.localAddresses()) }

                    PairingScreen(
                        activePin = activePin,
                        localAddresses = localAddresses,
                        peers = peers,
                        pendingRequest = pendingRequest,
                        onRegeneratePin = {
                            regeneratePinDirectly()
                            localAddresses = NetworkUtils.localAddresses()
                        },
                        onUnpair = { peer -> app.peerRepository.remove(peer.id) }
                    )
                }
            }
        }
    }

    private fun regeneratePinDirectly() {
        // The service owns PIN generation; MainActivity just asks it to run again.
        val serviceIntent = android.content.Intent(this, BuddyForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
