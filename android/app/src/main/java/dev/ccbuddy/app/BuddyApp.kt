package dev.ccbuddy.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.ccbuddy.app.data.PairingState
import dev.ccbuddy.app.data.PeerRepository
import dev.ccbuddy.app.data.TokenStore

class BuddyApp : Application() {

    lateinit var tokenStore: TokenStore
        private set
    lateinit var peerRepository: PeerRepository
        private set
    lateinit var pairingState: PairingState
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        peerRepository = PeerRepository(tokenStore)
        pairingState = PairingState()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
