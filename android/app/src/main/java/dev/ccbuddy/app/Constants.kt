package dev.ccbuddy.app

/** Fixed port the daemon dials into (must match buddy-daemon/src/constants.ts PHONE_WS_PORT). */
const val WS_PORT = 8765

/** mDNS service type this app will advertise once discovery (build-order step 7) lands. */
const val MDNS_SERVICE_TYPE = "_buddycc._tcp"

const val PIN_LENGTH = 6
const val PIN_TTL_MILLIS = 2 * 60 * 1000L

const val NOTIFICATION_CHANNEL_ID = "buddy_listening"
const val FOREGROUND_NOTIFICATION_ID = 1

/** Separate high-importance channel for "Claude needs you" alerts — the
 * persistent listening notification above is deliberately low-importance
 * and silent, this one should actually buzz the phone. */
const val ALERT_NOTIFICATION_CHANNEL_ID = "buddy_alerts"
const val ALERT_NOTIFICATION_ID = 2

/** Intent action for the alert notification's "Stop reading" button (#22) —
 * silences the current TTS utterance without tearing down the engine. */
const val ACTION_STOP_READING = "dev.ccbuddy.app.ACTION_STOP_READING"
