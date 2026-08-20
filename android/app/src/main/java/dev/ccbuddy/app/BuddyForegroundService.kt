package dev.ccbuddy.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ccbuddy.app.data.ActivePin
import dev.ccbuddy.app.data.HookNotifier
import dev.ccbuddy.app.server.BuddyWsServer
import dev.ccbuddy.app.server.NsdHelper
import dev.ccbuddy.app.server.TtsSpeaker
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
class BuddyForegroundService : Service(), HookNotifier {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private lateinit var wsServer: BuddyWsServer
    private lateinit var nsdHelper: NsdHelper
    private lateinit var ttsSpeaker: TtsSpeaker

    // Can't intercept the power button itself -- Android reserves it for
    // lock/screen-off, no public API exists for a regular app (#22). The
    // screen actually turning off is the closest reliable proxy for "the
    // user just pressed power to shut this up," and needs no special
    // permission to listen for (unlike an AccessibilityService, which is
    // what true button interception while backgrounded/locked would take).
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ttsSpeaker.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as BuddyApp
        wsServer = BuddyWsServer(
            context = applicationContext,
            pairingState = app.pairingState,
            peerRepository = app.peerRepository,
            terminalBridge = app.terminalBridge,
            hookNotifier = this,
            phoneDeviceName = { Build.MODEL ?: "Android phone" }
        )
        nsdHelper = NsdHelper(this)
        ttsSpeaker = TtsSpeaker(applicationContext)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Routed here (rather than a separate BroadcastReceiver) since a
        // PendingIntent.getService() targeting this same class is simpler
        // than standing up a new component just for one action button
        // (#22). Must short-circuit before the normal startup below --
        // re-running wsServer.start()/nsdHelper.register() on an already-
        // running service would rebind the same port/service name.
        if (intent?.action == ACTION_STOP_READING) {
            ttsSpeaker.stop()
            return START_STICKY
        }
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification())
        wsServer.start()
        nsdHelper.register()
        regeneratePin()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        nsdHelper.unregister()
        wsServer.stop()
        ttsSpeaker.shutdown()
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

    /** Notification hook fired — Claude is idle/waiting on a permission
     * prompt. This is the "buzz the phone" moment, unlike the silent
     * low-importance foreground notification. */
    override fun onClaudeNotification(message: String, question: String?) {
        val openApp = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val readAloud = (application as BuddyApp).settingsStore.readNotificationsAloud.value
        val notificationBuilder = NotificationCompat.Builder(this, ALERT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
        // Only offer this when there's actually something to stop (#22) --
        // otherwise it's a dead button on every plain visual notification.
        if (readAloud) {
            val stopReading = PendingIntent.getService(
                this, 2,
                Intent(this, BuddyForegroundService::class.java).setAction(ACTION_STOP_READING),
                PendingIntent.FLAG_IMMUTABLE
            )
            notificationBuilder.addAction(
                android.R.drawable.ic_lock_silent_mode,
                getString(R.string.stop_reading),
                stopReading
            )
        }
        NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, notificationBuilder.build())

        // Additive, not a replacement for the visual notification above --
        // opt-in via Settings, off by default.
        if (readAloud) {
            ttsSpeaker.speak(question ?: message)
        }
    }

    /** Stop hook fired — the turn finished, so whatever was waiting for
     * attention no longer is. */
    override fun onClaudeStop() {
        NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
    }

    /** PreToolUse hook fired — logged for now; a richer "review this Edit"
     * card is a future-build-order enhancement, not required for the
     * notification pipeline itself. */
    override fun onPreToolUse(tool: String, input: String?) {
        android.util.Log.i("BuddyForegroundService", "PreToolUse: $tool $input")
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
