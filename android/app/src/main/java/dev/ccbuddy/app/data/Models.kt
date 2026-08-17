package dev.ccbuddy.app.data

import kotlinx.coroutines.CompletableDeferred

/** A PC-side Claude Code session paired (or pending pairing) with this phone. */
data class PeerSession(
    val id: String,
    val deviceName: String,
    val token: String,
    val pairedAt: Long,
    val connected: Boolean,
    // Round-trip time of the last ping/pong exchange, in ms -- live session
    // data like [connected], not a durable pairing fact, so it's never
    // persisted to TokenStore either.
    val latencyMs: Int? = null
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
