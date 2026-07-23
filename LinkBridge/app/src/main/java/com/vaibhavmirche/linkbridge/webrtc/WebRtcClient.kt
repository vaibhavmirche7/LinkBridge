package com.vaibhavmirche.linkbridge.webrtc

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import timber.log.Timber
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Wire format for the opaque "signal" payloads relayed by the signaling server. */
@Serializable
data class WireSignal(
    val kind: String, // "sdp-offer" | "sdp-answer" | "ice-candidate"
    val sdp: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null
)

sealed class DataChannelEvent {
    object Open : DataChannelEvent()
    object Closed : DataChannelEvent()
    data class TextReceived(val text: String) : DataChannelEvent()
    data class BinaryReceived(val data: ByteArray) : DataChannelEvent()
}

sealed class PeerConnectionEvent {
    data class IceCandidateGenerated(val candidate: IceCandidate) : PeerConnectionEvent()
    data class StateChanged(val state: PeerConnection.PeerConnectionState) : PeerConnectionEvent()
}

/**
 * Wraps a single WebRTC peer connection used for one QR-paired cross-network transfer.
 * Free STUN (Google's public servers) for NAT discovery, free public TURN (Open Relay
 * Project) as the fallback relay when a direct connection isn't possible.
 */
class WebRtcClient(context: Context) {

    private val logger = Timber.tag("WebRtcClient")
    private val appContext = context.applicationContext
    private val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private val _dataChannelEvents = MutableSharedFlow<DataChannelEvent>(extraBufferCapacity = 64)
    val dataChannelEvents: SharedFlow<DataChannelEvent> = _dataChannelEvents

    private val _peerConnectionEvents = MutableSharedFlow<PeerConnectionEvent>(extraBufferCapacity = 32)
    val peerConnectionEvents: SharedFlow<PeerConnectionEvent> = _peerConnectionEvents

    private fun iceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        // Open Relay Project's free public TURN service - no account/hosting needed.
        // See https://www.metered.ca/tools/openrelay/
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
    )

    /** [isOfferer] should be true for the device that showed the QR, false for the scanner. */
    fun createConnection(isOfferer: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                _peerConnectionEvents.tryEmit(PeerConnectionEvent.IceCandidateGenerated(candidate))
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                _peerConnectionEvents.tryEmit(PeerConnectionEvent.StateChanged(newState))
            }
            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
                attachDataChannelObserver(channel)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)

        if (isOfferer) {
            val channel = peerConnection?.createDataChannel("linkbridge-transfer", DataChannel.Init())
            dataChannel = channel
            channel?.let { attachDataChannelObserver(it) }
        }
    }

    private fun attachDataChannelObserver(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> _dataChannelEvents.tryEmit(DataChannelEvent.Open)
                    DataChannel.State.CLOSED -> _dataChannelEvents.tryEmit(DataChannelEvent.Closed)
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                if (buffer.binary) {
                    _dataChannelEvents.tryEmit(DataChannelEvent.BinaryReceived(bytes))
                } else {
                    _dataChannelEvents.tryEmit(DataChannelEvent.TextReceived(String(bytes, Charsets.UTF_8)))
                }
            }
        })
    }

    /** Small JSON control messages (verification, file-offer/accept, etc). */
    fun sendText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    /** Raw file bytes - one data channel message per chunk. */
    fun sendBinary(bytes: ByteArray) {
        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    /** Bytes currently queued but not yet sent - callers should pace sending against this. */
    fun bufferedAmount(): Long = dataChannel?.bufferedAmount() ?: 0L

    suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                cont.resume(sdp)
            }
            override fun onCreateFailure(error: String) {
                cont.resumeWithException(IllegalStateException("createOffer failed: $error"))
            }
        }, MediaConstraints())
    }

    suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { cont ->
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                cont.resume(sdp)
            }
            override fun onCreateFailure(error: String) {
                cont.resumeWithException(IllegalStateException("createAnswer failed: $error"))
            }
        }, MediaConstraints())
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        dataChannel = null
        peerConnection = null
    }

    companion object {
        /** Encodes a local SDP as the JSON string that goes over the signaling channel. */
        fun encodeSdp(kind: String, sdp: SessionDescription): String =
            Json.encodeToString(WireSignal.serializer(), WireSignal(kind = kind, sdp = sdp.description))

        fun encodeIceCandidate(candidate: IceCandidate): String =
            Json.encodeToString(
                WireSignal.serializer(),
                WireSignal(
                    kind = "ice-candidate",
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp
                )
            )

        fun decode(payload: String): WireSignal? =
            runCatching { Json.decodeFromString(WireSignal.serializer(), payload) }.getOrNull()
    }
}

/** SdpObserver has 4 methods; most callers only care about 1-2, so this gives no-op defaults. */
private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
