package dev.ccbuddy.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.ccbuddy.app.data.ActivePin
import dev.ccbuddy.app.server.BuddyWsServer
import dev.ccbuddy.app.util.PinGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service holding the WS server that the daemon dials into.
 * Must stay alive with a persistent notification per the spec — Android
 * kills backgrounded services without one.
 */
class BuddyForegroundService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private lateinit var wsServer: BuddyWsServer

    override fun onCreate() {
        super.onCreate()
        val app = application as BuddyApp
        wsServer = BuddyWsServer(
            pairingState = app.pairingState,
            peerRepository = app.peerRepository,
            terminalBridge = app.terminalBridge,
            phoneDeviceName = { Build.MODEL ?: "Android phone" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification())
        wsServer.start()
        regeneratePin()
        return START_STICKY
    }

    override fun onDestroy() {
        wsServer.stop()
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Generates a fresh PIN and schedules its expiry, per the spec's short-TTL requirement. */
    fun regeneratePin() {
        val app = application as BuddyApp
        val pin = ActivePin(PinGenerator.generate(PIN_LENGTH), System.currentTimeMillis() + PIN_TTL_MILLIS)
        app.pairingState.setPin(pin)
        scope.launch {
            delay(PIN_TTL_MILLIS)
            if (app.pairingState.activePin.value?.pin == pin.pin) {
                app.pairingState.setPin(null)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_listening))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        fun start(context: android.content.Context) {
            val intent = Intent(context, BuddyForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
