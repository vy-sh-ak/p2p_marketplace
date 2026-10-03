package com.example.android_app.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcClient(
    context: Context,
    private val listener: Listener,
) : PeerConnection.Observer {

    interface Listener {
        fun onDataChannelOpen()
        fun onDataChannelClosed()
        fun onMessageReceived(text: String)
        fun onFailure(reason: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    @Volatile
    private var gatheringSignal = CompletableDeferred<Unit>()

    fun createPeerConnection(): PeerConnection {
        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory(appContext).createPeerConnection(config, this)
            ?: throw IllegalStateException("Failed to create PeerConnection")
        peerConnection = pc
        return pc
    }

    fun attachDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> mainHandler.post { listener.onDataChannelOpen() }
                    DataChannel.State.CLOSED -> mainHandler.post { listener.onDataChannelClosed() }
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                mainHandler.post { listener.onMessageReceived(text) }
            }
        })
    }

    suspend fun createLocalOffer(): SessionDescription {
        val desc = createDescriptionSuspend(isOffer = true)
        return setLocalAndGather(desc)
    }

    suspend fun createLocalAnswer(): SessionDescription {
        val desc = createDescriptionSuspend(isOffer = false)
        return setLocalAndGather(desc)
    }

    suspend fun setRemoteDescription(desc: SessionDescription) {
        suspendCancellableCoroutine { cont ->
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {}
                override fun onSetSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("setRemoteDescription failed: $error")
                    )
                }
            }, desc)
        }
    }

    fun send(text: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
        return try {
            dc.send(buffer)
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        try {
            dataChannel?.close()
        } catch (_: Exception) {
        }
        try {
            peerConnection?.close()
        } catch (_: Exception) {
        }
        dataChannel = null
        peerConnection = null
    }

    private suspend fun createDescriptionSuspend(isOffer: Boolean): SessionDescription {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not created")
        return suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    if (cont.isActive) cont.resume(desc)
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException(
                            if (isOffer) "createOffer failed: $error" else "createAnswer failed: $error"
                        )
                    )
                }

                override fun onSetFailure(error: String?) {}
            }
            if (isOffer) {
                pc.createOffer(observer, MediaConstraints())
            } else {
                pc.createAnswer(observer, MediaConstraints())
            }
        }
    }

    private suspend fun setLocalAndGather(desc: SessionDescription): SessionDescription {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not created")
        gatheringSignal = CompletableDeferred()
        suspendCancellableCoroutine { cont ->
            pc.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {}
                override fun onSetSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("setLocalDescription failed: $error")
                    )
                }
            }, desc)
        }
        // Non-trickle ICE: wait for gathering to finish so the local description
        // contains all candidates. Fall back to what we have on timeout.
        withTimeoutOrNull(5_000) { gatheringSignal.await() }
        return pc.localDescription ?: desc
    }

    // --- PeerConnection.Observer (callbacks arrive on WebRTC threads) ---

    override fun onSignalingChange(state: PeerConnection.SignalingState) {}

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.FAILED ->
                mainHandler.post { listener.onFailure("Peer connection failed (NAT traversal may be blocked)") }
            // DISCONNECTED can be transient; wait for FAILED/CLOSED or data channel close.
            PeerConnection.IceConnectionState.CLOSED ->
                mainHandler.post { listener.onDataChannelClosed() }
            else -> {}
        }
    }

    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {}

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        if (newState == PeerConnection.PeerConnectionState.FAILED) {
            mainHandler.post { listener.onFailure("Peer connection failed (NAT traversal may be blocked)") }
        }
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {}

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
        if (state == PeerConnection.IceGatheringState.COMPLETE) {
            gatheringSignal.complete(Unit)
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        // Non-trickle ICE: candidates are embedded in the exchanged SDP.
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}

    override fun onAddStream(stream: MediaStream) {}

    override fun onRemoveStream(stream: MediaStream) {}

    override fun onDataChannel(channel: DataChannel) {
        attachDataChannel(channel)
    }

    override fun onRenegotiationNeeded() {}

    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}

    override fun onTrack(transceiver: RtpTransceiver) {}

    override fun onRemoveTrack(receiver: RtpReceiver) {}

    companion object {
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        )

        @Volatile
        private var factory: PeerConnectionFactory? = null

        private fun factory(appContext: Context): PeerConnectionFactory =
            factory ?: synchronized(this) {
                factory ?: run {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(appContext)
                            .createInitializationOptions()
                    )
                    PeerConnectionFactory.builder()
                        .createPeerConnectionFactory()
                        .also { factory = it }
                }
            }
    }
}
