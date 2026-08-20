package dev.ccbuddy.app

import android.content.Context
import android.content.Intent

// Shared by MainActivity's foreground volume-key intercept and the terminal
// screen's speaker-icon tap (#22) -- both just want to silence whatever's
// currently being read, routed through the same ACTION_STOP_READING the
// notification button uses (see BuddyForegroundService.onStartCommand).
// BuddyForegroundService's own screen-off receiver calls ttsSpeaker.stop()
// directly rather than through here, since it already owns the TtsSpeaker
// instance.
fun stopReadingAloud(context: Context) {
    context.startService(Intent(context, BuddyForegroundService::class.java).setAction(ACTION_STOP_READING))
}
