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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ccbuddy.app.ui.PairingScreen
import dev.ccbuddy.app.ui.SettingsScreen
import dev.ccbuddy.app.ui.TerminalScreen
import dev.ccbuddy.app.ui.theme.CCBuddyTheme
import dev.ccbuddy.app.util.NetworkUtils
import dev.ccbuddy.app.util.appDetailsSettingsIntent
import dev.ccbuddy.app.util.ignoreBatteryOptimizationsIntent
import dev.ccbuddy.app.util.isIgnoringBatteryOptimizations

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private val requestBatteryExemption =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* state refreshes on resume */ }

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
                    val activeBridgePeerIds by app.terminalBridge.activePeerIds.collectAsState()
                    var localAddresses by remember { mutableStateOf(NetworkUtils.localAddresses()) }
                    // Which peer's terminal is on screen, if any — multiple can be
                    // bridged at once (Phase 2: one phone, several PC sessions), so
                    // this is a specific peer id, not just a boolean.
                    var viewingPeerId by remember { mutableStateOf<String?>(null) }
                    var showSettings by remember { mutableStateOf(false) }
                    val fontSizeOverride by app.settingsStore.fontSizeOverride.collectAsState()
                    val compactMode by app.settingsStore.compactMode.collectAsState()
                    val showConnectionDetails by app.settingsStore.showConnectionDetails.collectAsState()
                    val readNotificationsAloud by app.settingsStore.readNotificationsAloud.collectAsState()
                    // Only the very first bridge of the app's lifetime auto-opens the
                    // terminal (first-pairing convenience). Without this, returning to
                    // the list to pair a second session and having it connect would
                    // yank the view back into whichever session happens to be first in
                    // the set — including one the user already saw and deliberately
                    // backed out of — instead of just updating the list quietly.
                    var hasAutoOpened by remember { mutableStateOf(false) }

                    var batteryOptimizationExempt by remember {
                        mutableStateOf(isIgnoringBatteryOptimizations(this@MainActivity))
                    }
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        // The system settings screen this launches doesn't
                        // report a result Compose can react to directly --
                        // re-read the OS-level flag whenever we come back
                        // to the foreground instead.
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                batteryOptimizationExempt = isIgnoringBatteryOptimizations(this@MainActivity)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    LaunchedEffect(activeBridgePeerIds) {
                        if (!hasAutoOpened && activeBridgePeerIds.isNotEmpty()) {
                            viewingPeerId = activeBridgePeerIds.first()
                            hasAutoOpened = true
                        }
                        // Fall back to the list if the one being viewed dropped.
                        if (viewingPeerId != null && viewingPeerId !in activeBridgePeerIds) {
                            viewingPeerId = null
                        }
                    }

                    val viewingPeer = peers.find { it.id == viewingPeerId }
                    if (showSettings) {
                        androidx.activity.compose.BackHandler { showSettings = false }
                        SettingsScreen(
                            fontSizeOverride = fontSizeOverride,
                            compactMode = compactMode,
                            showConnectionDetails = showConnectionDetails,
                            readNotificationsAloud = readNotificationsAloud,
                            batteryOptimizationExempt = batteryOptimizationExempt,
                            onRequestBatteryExemption = {
                                requestBatteryExemption.launch(ignoreBatteryOptimizationsIntent(this@MainActivity))
                            },
                            onOpenBatterySettings = {
                                startActivity(appDetailsSettingsIntent(this@MainActivity))
                            },
                            onFontSizeOverrideChange = { app.settingsStore.setFontSizeOverride(it) },
                            onCompactModeChange = { app.settingsStore.setCompactMode(it) },
                            onShowConnectionDetailsChange = { app.settingsStore.setShowConnectionDetails(it) },
                            onReadNotificationsAloudChange = { app.settingsStore.setReadNotificationsAloud(it) },
                            onBack = { showSettings = false }
                        )
                    } else if (viewingPeerId != null && viewingPeer != null) {
                        // The system back button used to exit the app
                        // straight from the terminal screen (there's no
                        // back stack — this is the only Activity). Route
                        // it to the same "return to session list" action
                        // as the on-screen back button instead.
                        androidx.activity.compose.BackHandler { viewingPeerId = null }
                        TerminalScreen(
                            peerId = viewingPeerId!!,
                            deviceName = viewingPeer.deviceName,
                            terminalBridge = app.terminalBridge,
                            fontSizeOverride = fontSizeOverride,
                            compactMode = compactMode,
                            onBack = { viewingPeerId = null }
                        )
                    } else {
                        PairingScreen(
                            activePin = activePin,
                            localAddresses = localAddresses,
                            peers = peers,
                            pendingRequest = pendingRequest,
                            showConnectionDetails = showConnectionDetails,
                            batteryOptimizationExempt = batteryOptimizationExempt,
                            onRequestBatteryExemption = {
                                requestBatteryExemption.launch(ignoreBatteryOptimizationsIntent(this@MainActivity))
                            },
                            onRegeneratePin = {
                                regeneratePinDirectly()
                                localAddresses = NetworkUtils.localAddresses()
                            },
                            onUnpair = { peer -> app.peerRepository.remove(peer.id) },
                            onOpenTerminal = { peer -> viewingPeerId = peer.id },
                            onOpenSettings = { showSettings = true }
                        )
                    }
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
