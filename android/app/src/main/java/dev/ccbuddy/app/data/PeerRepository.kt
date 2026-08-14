package dev.ccbuddy.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory view of paired sessions (backed by [TokenStore] for
 * persistence), shared between the foreground service (which updates
 * connection state as WS sockets open/close) and the Compose UI.
 */
class PeerRepository(private val tokenStore: TokenStore) {

    private val _peers = MutableStateFlow(tokenStore.all())
    val peers: StateFlow<List<PeerSession>> = _peers

    fun addPaired(peer: PeerSession) {
        tokenStore.upsert(peer)
        _peers.update { current -> current.filterNot { it.id == peer.id } + peer.copy(connected = true) }
    }

    fun setConnected(id: String, connected: Boolean) {
        _peers.update { current ->
            current.map { if (it.id == id) it.copy(connected = connected) else it }
        }
    }

    fun remove(id: String) {
        tokenStore.remove(id)
        _peers.update { current -> current.filterNot { it.id == id } }
    }
}
