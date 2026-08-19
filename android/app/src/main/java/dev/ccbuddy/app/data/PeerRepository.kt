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

    fun setLatency(id: String, latencyMs: Int) {
        _peers.update { current ->
            current.map { if (it.id == id) it.copy(latencyMs = latencyMs) else it }
        }
    }

    fun remove(id: String) {
        tokenStore.remove(id)
        _peers.update { current -> current.filterNot { it.id == id } }
    }

    // A user-chosen override for the PC-derived `user@host:dir` name --
    // the directory basename alone doesn't disambiguate two different
    // checkouts that happen to share a name. Persists via the same
    // upsert() the initial pairing uses, so it survives reconnects
    // (which never re-send/overwrite deviceName -- see BuddyWsServer's
    // reconnect handling) and only resets on a full unpair + re-pair.
    fun rename(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val current = _peers.value.find { it.id == id } ?: return
        val renamed = current.copy(deviceName = trimmed)
        tokenStore.upsert(renamed)
        _peers.update { peers -> peers.map { if (it.id == id) renamed else it } }
    }
}
