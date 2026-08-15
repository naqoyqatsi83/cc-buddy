package dev.ccbuddy.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.ccbuddy.app.data.PairingState
import dev.ccbuddy.app.data.PeerRepository
import dev.ccbuddy.app.data.SettingsStore
import dev.ccbuddy.app.data.TerminalBridge
import dev.ccbuddy.app.data.TokenStore

class BuddyApp : Application() {

    lateinit var tokenStore: TokenStore
        private set
    lateinit var peerRepository: PeerRepository
        private set
    lateinit var pairingState: PairingState
        private set
    lateinit var terminalBridge: TerminalBridge
        private set
    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        peerRepository = PeerRepository(tokenStore)
        pairingState = PairingState()
        terminalBridge = TerminalBridge()
        settingsStore = SettingsStore(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val listening = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val alerts = NotificationChannel(
            ALERT_NOTIFICATION_CHANNEL_ID,
            getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(listening, alerts))
    }
}
