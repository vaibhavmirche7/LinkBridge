package com.vaibhavmirche.linkbridge.webrtc

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Mirrors the server's SignalingMessage - kept in sync manually, the two projects don't share code. */
@Serializable
data class SignalingMessage(
    val type: String,
    val sessionId: String? = null,
    val payload: String? = null,
    val message: String? = null
)

sealed class SignalingEvent {
    object Waiting : SignalingEvent()
    object Paired : SignalingEvent()
    data class Signal(val payload: String) : SignalingEvent()
    object PeerLeft : SignalingEvent()
    data class Error(val message: String) : SignalingEvent()
    data class ConnectionFailed(val reason: String) : SignalingEvent()
}

/**
 * WebSocket client for the LinkBridge signaling server. One instance per pairing attempt.
 *
 * [serverUrl] must be a plain ws:// or wss:// URL to the server's root (e.g. "wss://host.example.com");
 * this client connects to the "/pair" path on it.
 */
class SignalingClient(private val serverUrl: String) {

    private val logger = Timber.tag("SignalingClient")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(CIO) { install(WebSockets) }
    @Volatile private var activeSession: DefaultClientWebSocketSession? = null

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SignalingEvent> = _events

    private val parsedUrl = io.ktor.http.Url(serverUrl)

    /** Connects and joins [sessionId]. Safe to call once per client instance. */
    fun connectAndJoin(sessionId: String) {
        scope.launch {
            try {
                val useTls = parsedUrl.protocol.name == "wss" || parsedUrl.protocol.name == "https"
                client.webSocket(
                    method = HttpMethod.Get,
                    host = parsedUrl.host,
                    port = parsedUrl.port,
                    path = "/pair",
                    request = {
                        url.protocol = if (useTls) io.ktor.http.URLProtocol.WSS else io.ktor.http.URLProtocol.WS
                    }
                ) {
                    activeSession = this
                    send(
                        Frame.Text(
                            Json.encodeToString(
                                SignalingMessage.serializer(),
                                SignalingMessage(type = "join", sessionId = sessionId)
                            )
                        )
                    )

                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = runCatching {
                            Json.decodeFromString(SignalingMessage.serializer(), frame.readText())
                        }.getOrNull() ?: continue

                        when (message.type) {
                            "waiting" -> _events.emit(SignalingEvent.Waiting)
                            "paired" -> _events.emit(SignalingEvent.Paired)
                            "signal" -> message.payload?.let { _events.emit(SignalingEvent.Signal(it)) }
                            "peer-left" -> _events.emit(SignalingEvent.PeerLeft)
                            "error" -> _events.emit(SignalingEvent.Error(message.message ?: "Unknown error"))
                        }
                    }
                }
            } catch (e: Exception) {
                logger.w(e, "Signaling connection failed")
                _events.emit(SignalingEvent.ConnectionFailed(e.localizedMessage ?: "Connection failed"))
            }
        }
    }

    /** Sends an opaque signaling payload (SDP or ICE candidate, JSON-encoded by the caller) to the peer. */
    fun sendSignal(payload: String) {
        scope.launch {
            runCatching {
                activeSession?.send(
                    Frame.Text(
                        Json.encodeToString(
                            SignalingMessage.serializer(),
                            SignalingMessage(type = "signal", payload = payload)
                        )
                    )
                )
            }.onFailure { logger.w(it, "Failed to send signal") }
        }
    }

    fun close() {
        scope.launch {
            runCatching { activeSession?.close() }
            client.close()
        }
    }
}
