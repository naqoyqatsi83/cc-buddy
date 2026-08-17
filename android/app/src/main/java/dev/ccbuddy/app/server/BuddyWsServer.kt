package dev.ccbuddy.app.server

import android.content.Context
import dev.ccbuddy.app.WS_PORT
import dev.ccbuddy.app.data.HookNotifier
import dev.ccbuddy.app.data.PairingAttemptLimiter
import dev.ccbuddy.app.data.PairingState
import dev.ccbuddy.app.data.PeerRepository
import dev.ccbuddy.app.data.PeerSession
import dev.ccbuddy.app.data.PendingPairRequest
import dev.ccbuddy.app.data.TerminalBridge
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

private fun JSONArray?.toStringList(): List<String> =
    this?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()

/**
 * The phone's WS server (Component 3 in the spec): the daemon dials into
 * this, never the other way around. Runs the pairing handshake (PIN +
 * explicit accept/deny tap, long-lived token on success) and then bridges
 * the same connection to [TerminalBridge] — `pty_data` frames from the
 * daemon feed the terminal mirror, and replies typed on the phone go back
 * out as `input` frames.
 */
class BuddyWsServer(
    private val context: Context,
    private val pairingState: PairingState,
    private val peerRepository: PeerRepository,
    private val terminalBridge: TerminalBridge,
    private val hookNotifier: HookNotifier,
    private val phoneDeviceName: () -> String
) {
    private var engine: ApplicationEngine? = null
    private val pairingAttemptLimiter = PairingAttemptLimiter()

    fun start() {
        if (engine != null) return
        val identity = TlsIdentity.loadOrCreate(context)
        val environment = applicationEngineEnvironment {
            sslConnector(
                keyStore = identity.keyStore,
                keyAlias = identity.alias,
                keyStorePassword = { identity.password },
                privateKeyPassword = { identity.password }
            ) {
                port = WS_PORT
                host = "0.0.0.0"
            }
            module { module() }
        }
        engine = embeddedServer(Netty, environment).start(wait = false)
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
                                pairedPeerId?.let { terminalBridge.emitOutput(it, msg.optString("data")) }
                            }
                            "resize" -> {
                                pairedPeerId?.let {
                                    terminalBridge.updateSize(it, msg.optInt("cols"), msg.optInt("rows"))
                                }
                            }
                            "transcript_init" -> {
                                pairedPeerId?.let {
                                    terminalBridge.setTranscript(it, msg.optJSONArray("lines").toStringList())
                                }
                            }
                            "transcript_append" -> {
                                pairedPeerId?.let {
                                    terminalBridge.appendTranscript(it, msg.optJSONArray("lines").toStringList())
                                }
                            }
                            "tail_update" -> {
                                pairedPeerId?.let {
                                    terminalBridge.setTail(it, msg.optJSONArray("lines").toStringList())
                                }
                            }
                            "menu_options" -> {
                                pairedPeerId?.let {
                                    terminalBridge.setMenuOptions(it, msg.optJSONArray("options").toStringList())
                                }
                            }
                            "ping" -> {
                                send(Frame.Text(JSONObject().put("type", "pong").put("ts", msg.optLong("ts")).toString()))
                            }
                            "pong" -> {
                                pairedPeerId?.let {
                                    val latencyMs = System.currentTimeMillis() - msg.optLong("ts")
                                    peerRepository.setLatency(it, latencyMs.toInt())
                                }
                            }
                            "notification" -> {
                                hookNotifier.onClaudeNotification(
                                    msg.optString("message", "Claude needs your attention"),
                                    msg.optString("question", "").ifBlank { null }
                                )
                            }
                            "stop" -> hookNotifier.onClaudeStop()
                            "pretooluse" -> {
                                hookNotifier.onPreToolUse(
                                    msg.optString("tool", "unknown"),
                                    msg.opt("input")?.toString()
                                )
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
        if (pairingAttemptLimiter.isLocked(remoteAddress)) {
            send(Frame.Text(JSONObject().put("type", "pair_denied").put("reason", "too many attempts, try again shortly").toString()))
            close(CloseReason(CloseReason.Codes.NORMAL, "rate limited"))
            return null
        }

        val pin = msg.optString("pin")
        val deviceName = msg.optString("device_name", "Unknown PC")
        val activePin = pairingState.activePin.value

        if (activePin == null || activePin.isExpired() || activePin.pin != pin) {
            pairingAttemptLimiter.recordFailure(remoteAddress)
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
        pairingAttemptLimiter.recordSuccess(remoteAddress)

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
        terminalBridge.attach(peerId) { frameType, text ->
            send(Frame.Text(JSONObject().put("type", frameType).put("data", text).toString()))
        }
        // Round-trip latency for the phone's own connection-details display
        // (see PeerRepository.setLatency) -- mirrors the daemon's own,
        // independent ping timer (buddy-daemon/src/bridge.ts) rather than
        // relaying a single measurement between the two. A child coroutine
        // of this session, so it's cancelled automatically when the
        // connection ends -- no manual cleanup needed, unlike the daemon's
        // setInterval.
        launch {
            while (isActive) {
                // send() throws once the connection is closing/closed, same
                // as any other point in its normal lifecycle (see
                // handlePairRequest's close() calls) -- catch and stop
                // rather than let it propagate and cancel the session.
                val sent = runCatching {
                    send(Frame.Text(JSONObject().put("type", "ping").put("ts", System.currentTimeMillis()).toString()))
                }
                if (sent.isFailure) break
                delay(10_000)
            }
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
