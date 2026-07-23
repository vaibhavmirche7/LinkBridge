package com.vaibhavmirche.linkbridge.signaling

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * LinkBridge signaling server.
 *
 * Purpose: help exactly two devices that scanned/showed the same QR find each other and
 * exchange WebRTC setup messages (SDP offer/answer, ICE candidates). It never sees file data -
 * once the two devices have exchanged enough signaling messages, WebRTC opens a direct (or
 * TURN-relayed) connection between them and the actual file transfer bypasses this server
 * entirely.
 *
 * Protocol (JSON text frames):
 *   client -> server  {"type":"join","sessionId":"..."}
 *   server -> client  {"type":"waiting"}                  (first device in the session)
 *   server -> both     {"type":"paired"}                   (once the second device joins)
 *   client -> server  {"type":"signal","payload":"..."}    (opaque - forwarded as-is)
 *   server -> peer     {"type":"signal","payload":"..."}
 *   server -> client  {"type":"peer-left"}                 (other device disconnected)
 *   server -> client  {"type":"error","message":"..."}
 */
@Serializable
data class SignalingMessage(
    val type: String,
    val sessionId: String? = null,
    val payload: String? = null,
    val message: String? = null
)

private val sessions = ConcurrentHashMap<String, MutableList<DefaultWebSocketServerSession>>()
private val sessionsLock = Any()

private suspend fun DefaultWebSocketServerSession.sendMessage(message: SignalingMessage) {
    send(Frame.Text(Json.encodeToString(SignalingMessage.serializer(), message)))
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        get("/") {
            call.respondText("LinkBridge signaling server is running.")
        }
        get("/health") {
            call.respondText("ok")
        }

        webSocket("/pair") {
            var joinedSessionId: String? = null
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    val message = runCatching { Json.decodeFromString<SignalingMessage>(text) }
                        .getOrNull() ?: continue

                    when (message.type) {
                        "join" -> {
                            val sessionId = message.sessionId
                            if (sessionId.isNullOrBlank()) {
                                sendMessage(SignalingMessage(type = "error", message = "Missing sessionId"))
                                continue
                            }
                            var rejected = false
                            synchronized(sessionsLock) {
                                val peers = sessions.getOrPut(sessionId) { mutableListOf() }
                                if (peers.size >= 2) {
                                    rejected = true
                                } else {
                                    peers.add(this)
                                    joinedSessionId = sessionId
                                }
                            }
                            if (rejected) {
                                sendMessage(SignalingMessage(type = "error", message = "Session already has two devices"))
                                continue
                            }
                            val peerCount = sessions[sessionId]?.size ?: 0
                            if (peerCount == 1) {
                                sendMessage(SignalingMessage(type = "waiting"))
                            } else {
                                sessions[sessionId]?.forEach { peerSession ->
                                    peerSession.sendMessage(SignalingMessage(type = "paired"))
                                }
                            }
                        }

                        "signal" -> {
                            val sessionId = joinedSessionId ?: continue
                            val peers = sessions[sessionId] ?: continue
                            peers.filter { it !== this }.forEach { peerSession ->
                                peerSession.sendMessage(SignalingMessage(type = "signal", payload = message.payload))
                            }
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                // normal disconnect
            } finally {
                val sessionId = joinedSessionId
                if (sessionId != null) {
                    synchronized(sessionsLock) {
                        sessions[sessionId]?.remove(this)
                        if (sessions[sessionId]?.isEmpty() == true) {
                            sessions.remove(sessionId)
                        }
                    }
                    sessions[sessionId]?.forEach { peerSession ->
                        runCatching {
                            peerSession.sendMessage(SignalingMessage(type = "peer-left"))
                        }
                    }
                }
            }
        }
    }
}
