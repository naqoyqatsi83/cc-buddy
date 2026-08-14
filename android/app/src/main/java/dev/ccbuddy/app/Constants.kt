package dev.ccbuddy.app

/** Fixed port the daemon dials into (must match buddy-daemon/src/constants.ts PHONE_WS_PORT). */
const val WS_PORT = 8765

/** mDNS service type this app will advertise once discovery (build-order step 7) lands. */
const val MDNS_SERVICE_TYPE = "_buddycc._tcp"

const val PIN_LENGTH = 6
const val PIN_TTL_MILLIS = 2 * 60 * 1000L

const val NOTIFICATION_CHANNEL_ID = "buddy_listening"
const val FOREGROUND_NOTIFICATION_ID = 1
