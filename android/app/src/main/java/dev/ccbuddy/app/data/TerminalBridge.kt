package dev.ccbuddy.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bridges the single active paired WS connection's PTY stream to the
 * Compose terminal screen. `pty_data` frames arriving on the connection
 * are pushed into [output]; replies typed/tapped in the UI go out through
 * whichever `sender` the currently-active connection registered via
 * [attach] (there's exactly one live bridge at a time — CC Buddy mirrors
 * one PC session per phone for now).
 */
class TerminalBridge {
    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 4096)
    val output: SharedFlow<String> = _output

    private val _activePeerId = MutableStateFlow<String?>(null)
    val activePeerId: StateFlow<String?> = _activePeerId

    @Volatile
    private var sender: (suspend (String) -> Unit)? = null

    fun attach(peerId: String, sender: suspend (String) -> Unit) {
        this.sender = sender
        _activePeerId.value = peerId
    }

    fun detach(peerId: String) {
        if (_activePeerId.value == peerId) {
            sender = null
            _activePeerId.value = null
        }
    }

    fun emitOutput(chunk: String) {
        _output.tryEmit(chunk)
    }

    suspend fun sendInput(text: String) {
        sender?.invoke(text)
    }
}
