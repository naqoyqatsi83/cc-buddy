package dev.ccbuddy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
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
import dev.ccbuddy.app.ui.PairRequestDialog
import dev.ccbuddy.app.ui.PairingScreen
import dev.ccbuddy.app.ui.SettingsScreen
import dev.ccbuddy.app.ui.TerminalScreen
import dev.ccbuddy.app.ui.theme.CCBuddyTheme
import dev.ccbuddy.app.util.NetworkUtils
import dev.ccbuddy.app.util.appDetailsSettingsIntent
import dev.ccbuddy.app.util.hasKnownOemBatteryManagement
import dev.ccbuddy.app.util.ignoreBatteryOptimizationsIntent
import dev.ccbuddy.app.util.isIgnoringBatteryOptimizations
import dev.ccbuddy.app.util.oemBatterySettingsIntent
import dev.ccbuddy.app.util.UpdateChecker
import dev.ccbuddy.app.util.UpdateInfo

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private val requestBatteryExemption =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* state refreshes on resume */ }

    // Any volume-key press silences an in-progress TTS reading (#22) --
    // scoped to while this Activity has input focus, since a background/
    // screen-off intercept would need an AccessibilityService, a much
    // bigger permission ask for a "shut it up" convenience. Doesn't
    // consume the event -- super still runs below, so volume changes
    // normally too; stopReadingAloud() is a harmless no-op when nothing
    // is currently speaking.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            stopReadingAloud(this)
        }
        return super.dispatchKeyEvent(event)
    }

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
                    val showQuickReplyButtons by app.settingsStore.showQuickReplyButtons.collectAsState()
                    val showReplyTextField by app.settingsStore.showReplyTextField.collectAsState()
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

                    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                    val dismissedUpdateVersion by app.settingsStore.dismissedUpdateVersion.collectAsState()
                    // Once per app open, not on every recomposition -- a
                    // "nice to know" background check, never worth
                    // repeating on its own or blocking anything on.
                    LaunchedEffect(Unit) {
                        updateInfo = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
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
                    // Rendered here, outside the screen switch below, so a
                    // pairing request that arrives while on Settings or the
                    // Terminal screen still shows the Accept/Deny dialog
                    // instead of silently timing out unseen (bug report:
                    // "permission question for pairing doesn't pop up on
                    // settings — you have to get to main screen").
                    pendingRequest?.let { PairRequestDialog(it) }
                    if (showSettings) {
                        androidx.activity.compose.BackHandler { showSettings = false }
                        SettingsScreen(
                            fontSizeOverride = fontSizeOverride,
                            compactMode = compactMode,
                            showConnectionDetails = showConnectionDetails,
                            readNotificationsAloud = readNotificationsAloud,
                            showQuickReplyButtons = showQuickReplyButtons,
                            showReplyTextField = showReplyTextField,
                            batteryOptimizationExempt = batteryOptimizationExempt,
                            onRequestBatteryExemption = {
                                requestBatteryExemption.launch(ignoreBatteryOptimizationsIntent(this@MainActivity))
                            },
                            onOpenBatterySettings = {
                                startActivity(appDetailsSettingsIntent(this@MainActivity))
                            },
                            oemBatteryManagementLabel = if (hasKnownOemBatteryManagement()) {
                                Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
                            } else {
                                null
                            },
                            onOpenOemBatterySettings = { openOemBatterySettings() },
                            onFontSizeOverrideChange = { app.settingsStore.setFontSizeOverride(it) },
                            onCompactModeChange = { app.settingsStore.setCompactMode(it) },
                            onShowConnectionDetailsChange = { app.settingsStore.setShowConnectionDetails(it) },
                            onReadNotificationsAloudChange = { app.settingsStore.setReadNotificationsAloud(it) },
                            onShowQuickReplyButtonsChange = { app.settingsStore.setShowQuickReplyButtons(it) },
                            onShowReplyTextFieldChange = { app.settingsStore.setShowReplyTextField(it) },
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
                            readNotificationsAloud = readNotificationsAloud,
                            showQuickReplyButtons = showQuickReplyButtons,
                            showReplyTextField = showReplyTextField,
                            onToggleReadNotificationsAloud = {
                                val newValue = !readNotificationsAloud
                                app.settingsStore.setReadNotificationsAloud(newValue)
                                stopReadingAloud(this@MainActivity)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (newValue) "Read aloud: On" else "Read aloud: Off",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onBack = { viewingPeerId = null }
                        )
                    } else {
                        PairingScreen(
                            activePin = activePin,
                            localAddresses = localAddresses,
                            peers = peers,
                            showConnectionDetails = showConnectionDetails,
                            batteryOptimizationExempt = batteryOptimizationExempt,
                            onRequestBatteryExemption = {
                                requestBatteryExemption.launch(ignoreBatteryOptimizationsIntent(this@MainActivity))
                            },
                            updateInfo = updateInfo?.takeIf { it.latestVersion != dismissedUpdateVersion },
                            onOpenReleasePage = {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo!!.releaseUrl)))
                            },
                            onDismissUpdate = {
                                app.settingsStore.setDismissedUpdateVersion(updateInfo?.latestVersion)
                            },
                            onRegeneratePin = {
                                regeneratePinDirectly()
                                localAddresses = NetworkUtils.localAddresses()
                            },
                            onUnpair = { peer -> app.peerRepository.remove(peer.id) },
                            onRename = { peer, newName -> app.peerRepository.rename(peer.id, newName) },
                            onOpenTerminal = { peer -> viewingPeerId = peer.id },
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    /**
     * OEM battery/autostart screens are launched by component name, not a
     * documented action -- they can still fail on OS versions where the
     * component moved again despite [oemBatterySettingsIntent]'s resolve
     * check. Fall back to the app's own details page (always resolvable)
     * rather than crashing on ActivityNotFoundException.
     */
    private fun openOemBatterySettings() {
        val intent = oemBatterySettingsIntent(this) ?: appDetailsSettingsIntent(this)
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(appDetailsSettingsIntent(this))
        } catch (e: SecurityException) {
            startActivity(appDetailsSettingsIntent(this))
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
