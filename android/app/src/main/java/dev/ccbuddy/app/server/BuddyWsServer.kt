package dev.ccbuddy.app.server

import dev.ccbuddy.app.WS_PORT
import dev.ccbuddy.app.data.PairingState
import dev.ccbuddy.app.data.PeerRepository
import dev.ccbuddy.app.data.PeerSession
import dev.ccbuddy.app.data.PendingPairRequest
import dev.ccbuddy.app.data.TerminalBridge
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * The phone's WS server (Component 3 in the spec): the daemon dials into
 * this, never the other way around. Runs the pairing handshake (PIN +
 * explicit accept/deny tap, long-lived token on success) and then bridges
 * the same connection to [TerminalBridge] — `pty_data` frames from the
 * daemon feed the terminal mirror, and replies typed on the phone go back
 * out as `input` frames.
 */
class BuddyWsServer(
    private val pairingState: PairingState,
    private val peerRepository: PeerRepository,
    private val terminalBridge: TerminalBridge,
    private val phoneDeviceName: () -> String
) {
    private var engine: ApplicationEngine? = null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = WS_PORT, host = "0.0.0.0") {
            module()
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    private fun Application.module() {
        install(WebSockets)
        routing {
            webSocket("/") {
                val remoteAddress = this.call.request.origin.remoteHost
                var pairedPeerId: String? = null
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val msg = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue

                        when (msg.optString("type")) {
                            "pair_request" -> {
                                pairedPeerId = handlePairRequest(msg, remoteAddress)
                                pairedPeerId?.let { attachBridge(it) }
                            }
                            "reconnect" -> {
                                pairedPeerId = handleReconnect(msg)
                                pairedPeerId?.let { attachBridge(it) }
                            }
                            "pty_data" -> {
                                terminalBridge.emitOutput(msg.optString("data"))
                            }
                            else -> Unit
                        }
                    }
                } finally {
                    pairedPeerId?.let {
                        peerRepository.setConnected(it, false)
                        terminalBridge.detach(it)
                    }
                }
            }
        }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handlePairRequest(
        msg: JSONObject,
        remoteAddress: String
    ): String? {
        val pin = msg.optString("pin")
        val deviceName = msg.optString("device_name", "Unknown PC")
        val activePin = pairingState.activePin.value

        if (activePin == null || activePin.isExpired() || activePin.pin != pin) {
            send(Frame.Text(JSONObject().put("type", "pair_denied").put("reason", "invalid or expired PIN").toString()))
            close(CloseReason(CloseReason.Codes.NORMAL, "denied"))
            return null
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pairingState.setPendingRequest(PendingPairRequest(requestId, deviceName, remoteAddress, deferred))

        val accepted = deferred.await()
        pairingState.setPendingRequest(null)

        if (!accepted) {
            send(Frame.Text(JSONObject().put("type", "pair_denied").put("reason", "declined on phone").toString()))
            close(CloseReason(CloseReason.Codes.NORMAL, "denied"))
            return null
        }

        // PIN is single-use: clear it once it's been consumed by a successful pairing.
        pairingState.setPin(null)

        val peerId = UUID.randomUUID().toString()
        val token = generateToken()
        peerRepository.addPaired(
            PeerSession(id = peerId, deviceName = deviceName, token = token, pairedAt = System.currentTimeMillis(), connected = true)
        )

        send(
            Frame.Text(
                JSONObject()
                    .put("type", "pair_ok")
                    .put("token", token)
                    .put("device_name", phoneDeviceName())
                    .toString()
            )
        )
        return peerId
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleReconnect(msg: JSONObject): String? {
        val token = msg.optString("token")
        val peer = peerRepository.peers.value.find { it.token == token }
        if (peer == null) {
            send(Frame.Text(JSONObject().put("type", "reconnect_denied").toString()))
            close(CloseReason(CloseReason.Codes.NORMAL, "unknown token"))
            return null
        }
        peerRepository.setConnected(peer.id, true)
        send(Frame.Text(JSONObject().put("type", "reconnect_ok").toString()))
        return peer.id
    }

    private fun io.ktor.server.websocket.DefaultWebSocketServerSession.attachBridge(peerId: String) {
        terminalBridge.attach(peerId) { text ->
            send(Frame.Text(JSONObject().put("type", "input").put("data", text).toString()))
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
