package dev.ccbuddy.app.data

import kotlinx.coroutines.CompletableDeferred

/** A PC-side Claude Code session paired (or pending pairing) with this phone. */
data class PeerSession(
    val id: String,
    val deviceName: String,
    val token: String,
    val pairedAt: Long,
    val connected: Boolean
)

/**
 * An in-flight pairing request awaiting an accept/deny tap from the user.
 * The WS coroutine handling the handshake suspends on [result] until the
 * UI completes it.
 */
data class PendingPairRequest(
    val requestId: String,
    val deviceName: String,
    val remoteAddress: String,
    val result: CompletableDeferred<Boolean>
)

data class ActivePin(
    val pin: String,
    val expiresAtMillis: Long
) {
    fun isExpired(now: Long = System.currentTimeMillis()) = now >= expiresAtMillis
}
