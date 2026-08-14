package dev.ccbuddy.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TerminalSize(val cols: Int, val rows: Int)

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
        // The PC terminal's actual size. Must be applied to xterm.js
        // exactly (not reflowed to the phone's width) — the source uses
        // absolute cursor positioning sized for its own terminal, so a
        // differently-sized mirror renders garbled.
        val size: MutableStateFlow<TerminalSize?> = MutableStateFlow(null),
        // Growing, append-only history captured daemon-side from a headless
        // shadow terminal (see buddy-daemon/src/shadowTerminal.ts) — lines
        // that have permanently scrolled off Claude Code's alt-screen and
        // can never change again. Rendered as its own independently-
        // scrollable pane, decoupled from the live mirror below it.
        val transcript: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
        // (frameType, data) -> Unit. frameType is "input" (complete reply,
        // daemon appends Enter) or "raw_input" (literal keystroke, e.g.
        // Tab — must NOT get an Enter appended).
        var sender: (suspend (String, String) -> Unit)? = null
    )

    private val bridges = mutableMapOf<String, PeerBridge>()

    private val _activePeerIds = MutableStateFlow<Set<String>>(emptySet())
    /** Peer ids that currently have a live, mirrorable connection. */
    val activePeerIds: StateFlow<Set<String>> = _activePeerIds

    @Synchronized
    fun attach(peerId: String, sender: suspend (String, String) -> Unit) {
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

    @Synchronized
    fun updateSize(peerId: String, cols: Int, rows: Int) {
        bridges[peerId]?.size?.value = TerminalSize(cols, rows)
    }

    /** Replaces the whole transcript — used once for the daemon's initial full-history send. */
    @Synchronized
    fun setTranscript(peerId: String, lines: List<String>) {
        bridges[peerId]?.transcript?.value = lines
    }

    /** Appends newly-captured lines — used for ongoing transcript growth. */
    @Synchronized
    fun appendTranscript(peerId: String, lines: List<String>) {
        bridges[peerId]?.transcript?.update { it + lines }
    }

    /** Null if this peer has no live connection right now (e.g. already disconnected). */
    fun outputFlow(peerId: String): SharedFlow<String>? = bridges[peerId]?.output

    /** Null if this peer has no live connection right now. Current value may
     * still be null even for a live connection if no resize has arrived yet. */
    fun sizeFlow(peerId: String): StateFlow<TerminalSize?>? = bridges[peerId]?.size

    /** Null if this peer has no live connection right now. */
    fun transcriptFlow(peerId: String): StateFlow<List<String>>? = bridges[peerId]?.transcript

    /** A complete reply — the daemon appends Enter (e.g. quick-reply buttons, the text field's Send). */
    suspend fun sendInput(peerId: String, text: String) {
        bridges[peerId]?.sender?.invoke("input", text)
    }

    /** A literal keystroke with no Enter appended — e.g. Tab for autocomplete. */
    suspend fun sendRaw(peerId: String, text: String) {
        bridges[peerId]?.sender?.invoke("raw_input", text)
    }
}
