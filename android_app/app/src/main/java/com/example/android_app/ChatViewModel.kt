package com.example.android_app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.android_app.data.ChatRepository
import com.example.android_app.webrtc.WebRtcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import org.webrtc.SessionDescription
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeoutException

enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    FAILED,
    DISCONNECTED,
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ChatRepository()

    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    val messages = mutableStateListOf<ChatMessage>()

    private var isHost = false
    private var roomId: String = ""
    private var roomRowId = -1L
    private var webRtc: WebRtcClient? = null
    private var negotiationJob: Job? = null

    private val roomCodeRandom = SecureRandom()

    private fun newRoomCode(): String = (roomCodeRandom.nextInt(9000) + 1000).toString()

    suspend fun hostRoom(): String = withContext(Dispatchers.IO) {
        repo.connect()
        var code: String
        var attempts = 0
        do {
            code = newRoomCode()
            attempts++
            check(attempts <= 25) { "Could not find a free room code" }
        } while (repo.getRoom(code) != null)
        val room = repo.createRoom(code)
        roomRowId = room.id
        roomId = code
        isHost = true
        prepareForNewRoom()
        startHostNegotiation(code)
        code
    }

    suspend fun joinRoom(id: String): Unit = withContext(Dispatchers.IO) {
        val code = id.trim()
        repo.connect()
        val room = repo.getRoom(code)
            ?: throw IllegalStateException("Room #$code not found")
        if (!isFresh(room)) throw IllegalStateException("Room #$code has expired")
        roomRowId = room.id
        roomId = code
        isHost = false
        prepareForNewRoom()
        startJoinerNegotiation(code)
    }

    fun disconnect() {
        negotiationJob?.cancel()
        negotiationJob = null
        webRtc?.close()
        webRtc = null
        deleteRoomRow()
        connectionState = ConnectionState.DISCONNECTED
    }

    fun sendMessage(text: String): Boolean {
        val client = webRtc
        val sent = client?.send(text.trim()) ?: false
        if (sent) {
            messages.add(ChatMessage(text = text.trim(), isMe = true))
        } else {
            statusMessage = "Not connected - message not sent"
        }
        return sent
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private fun prepareForNewRoom() {
        messages.clear()
        statusMessage = null
        connectionState = ConnectionState.CONNECTING
    }

    private fun startHostNegotiation(code: String) {
        negotiationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = createClient()
                val pc = client.createPeerConnection()
                val channel = pc.createDataChannel("chat", DataChannel.Init())
                    ?: throw IllegalStateException("Failed to create data channel")
                client.attachDataChannel(channel)
                val offer = client.createLocalOffer()
                repo.setOffer(code, offer.description)
                val answerSdp = pollUntil { repo.getRoom(code)?.answer }
                client.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                fail(e.message ?: "Hosting failed")
            }
        }
    }

    private fun startJoinerNegotiation(code: String) {
        negotiationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = createClient()
                val pc = client.createPeerConnection()
                val offerSdp = pollUntil { repo.getRoom(code)?.offer }
                client.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.OFFER, offerSdp)
                )
                val answer = client.createLocalAnswer()
                repo.setAnswer(code, answer.description)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                fail(e.message ?: "Joining failed")
            }
        }
    }

    private fun createClient(): WebRtcClient =
        WebRtcClient(getApplication(), rtcListener).also { webRtc = it }

    private val rtcListener = object : WebRtcClient.Listener {
        override fun onDataChannelOpen() {
            connectionState = ConnectionState.CONNECTED
            statusMessage = null
        }

        override fun onDataChannelClosed() {
            if (connectionState == ConnectionState.CONNECTED) {
                webRtc?.close()
                webRtc = null
                deleteRoomRow()
                connectionState = ConnectionState.DISCONNECTED
                statusMessage = "Peer disconnected"
            }
        }

        override fun onMessageReceived(text: String) {
            messages.add(ChatMessage(text = text, isMe = false))
        }

        override fun onFailure(reason: String) {
            connectionState = ConnectionState.FAILED
            statusMessage = reason
        }
    }

    private fun fail(reason: String) {
        connectionState = ConnectionState.FAILED
        statusMessage = reason
        webRtc?.close()
        webRtc = null
    }

    private fun deleteRoomRow() {
        if (isHost && roomRowId > 0) {
            val rowId = roomRowId
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    repo.deleteRoom(rowId)
                } catch (_: Exception) {
                }
            }
        }
        roomRowId = -1L
    }

    private suspend fun <T> pollUntil(
        intervalMs: Long = 1_000,
        timeoutMs: Long = 45_000,
        check: suspend () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            check()?.let { return it }
            delay(intervalMs)
        }
        throw TimeoutException("Timed out waiting for peer signaling")
    }

    private fun isFresh(room: com.example.rust_core.ChatRoom, maxAgeMinutes: Long = 10): Boolean {
        // created_at is a timestamptz rendered as text, e.g. "2026-09-14 10:23:45.678+00".
        val normalized = room.createdAt.replace(' ', 'T')
            .let { if (Regex("[+-]\\d{2}$").containsMatchIn(it)) "$it:00" else it }
        val instant = try {
            OffsetDateTime.parse(normalized).toInstant()
        } catch (_: Exception) {
            return true
        }
        return Duration.between(instant, Instant.now()).toMinutes() < maxAgeMinutes
    }
}
