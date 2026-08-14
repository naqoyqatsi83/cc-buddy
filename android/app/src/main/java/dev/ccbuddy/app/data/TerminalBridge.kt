package dev.ccbuddy.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges every currently-connected paired WS session's PTY stream to the
 * Compose terminal screen — keyed by peer id, so multiple PC sessions can
 * be mirrored at once (Phase 2: one phone pairs with multiple PC sessions
 * over time, per the spec) without their output interleaving into a
 * single stream. `pty_data` frames for a given peer are pushed into that
 * peer's own [output] flow; replies typed/tapped in the UI for a given
 * peer go out through the `sender` that peer's connection registered.
 */
class TerminalBridge {
    private class PeerBridge(
        // Replay a bounded backlog so (re)opening this peer's terminal —
        // after being on the session list, or after switching to another
        // peer and back — shows recent output instead of a blank screen;
        // each subscriber (a fresh WebView/xterm.js instance) replays it
        // in order and correctly reconstructs the visible terminal state.
        val output: MutableSharedFlow<String> = MutableSharedFlow(replay = 300, extraBufferCapacity = 4096),
        var sender: (suspend (String) -> Unit)? = null
    )

    private val bridges = mutableMapOf<String, PeerBridge>()

    private val _activePeerIds = MutableStateFlow<Set<String>>(emptySet())
    /** Peer ids that currently have a live, mirrorable connection. */
    val activePeerIds: StateFlow<Set<String>> = _activePeerIds

    @Synchronized
    fun attach(peerId: String, sender: suspend (String) -> Unit) {
        bridges.getOrPut(peerId) { PeerBridge() }.sender = sender
        _activePeerIds.update { it + peerId }
    }

    @Synchronized
    fun detach(peerId: String) {
        bridges.remove(peerId)
        _activePeerIds.update { it - peerId }
    }

    @Synchronized
    fun emitOutput(peerId: String, chunk: String) {
        bridges[peerId]?.output?.tryEmit(chunk)
    }

    /** Null if this peer has no live connection right now (e.g. already disconnected). */
    fun outputFlow(peerId: String): SharedFlow<String>? = bridges[peerId]?.output

    suspend fun sendInput(peerId: String, text: String) {
        bridges[peerId]?.sender?.invoke(text)
    }
}
