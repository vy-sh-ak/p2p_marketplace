package com.example.android_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.android_app.ui.theme.Android_appTheme
import kotlinx.coroutines.launch

import com.example.rust_core.init

enum class AppScreen {
    LOBBY,
    CHAT
}

data class ChatMessage(val text: String, val isMe: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        init(BuildConfig.SUPABASE_DB_PASSWORD, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
        enableEdgeToEdge()
        setContent {
            Android_appTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainNavigationWrapper(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainNavigationWrapper(modifier: Modifier = Modifier) {
    val viewModel: ChatViewModel = viewModel()
    var currentScreen by remember { mutableStateOf(AppScreen.LOBBY) }
    var activeRoomId by remember { mutableStateOf("") }

    Box(modifier = modifier.fillMaxSize()) {
        when (currentScreen) {
            AppScreen.LOBBY -> LobbyScreen(
                viewModel = viewModel,
                onRoomConnected = { roomId ->
                    activeRoomId = roomId
                    currentScreen = AppScreen.CHAT
                }
            )

            AppScreen.CHAT -> ChatScreen(
                viewModel = viewModel,
                roomId = activeRoomId,
                onLeaveRoom = {
                    activeRoomId = ""
                    currentScreen = AppScreen.LOBBY
                }
            )
        }
    }

}

@Composable
fun LobbyScreen(viewModel: ChatViewModel, onRoomConnected: (String) -> Unit) {
    var inputRoomId by remember { mutableStateOf("") }
    var lobbyError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "P2P WebRTC Chat",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        // CARD 1: Host a Room
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Host a New Chat", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        lobbyError = null
                        scope.launch {
                            try {
                                val code = viewModel.hostRoom()
                                onRoomConnected(code)
                            } catch (e: Exception) {
                                lobbyError = e.message ?: "Failed to create room"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Creating..." else "Create Room ID")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // CARD 2: Join a Room
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Join Existing Chat", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = inputRoomId,
                    onValueChange = { inputRoomId = it },
                    label = { Text("Enter 4-Digit Room ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        lobbyError = null
                        scope.launch {
                            try {
                                viewModel.joinRoom(inputRoomId)
                                onRoomConnected(inputRoomId.trim())
                            } catch (e: Exception) {
                                lobbyError = e.message ?: "Failed to join room"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = inputRoomId.length >= 4 && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Joining..." else "Join Room")
                }

                lobbyError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            }
        }

    }
}

@Composable
fun ChatScreen(viewModel: ChatViewModel, roomId: String, onLeaveRoom: () -> Unit) {
    var messageText by remember { mutableStateOf("") }
    val messages = viewModel.messages
    val connectionState = viewModel.connectionState
    val statusMessage = viewModel.statusMessage
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Active Top Bar Display
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = "Connected Room", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(text = "#$roomId", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Button(
                onClick = {
                    viewModel.disconnect()
                    onLeaveRoom()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Disconnect")
            }
        }

        if (connectionState != ConnectionState.CONNECTED) {
            Text(
                text = when (connectionState) {
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.FAILED -> statusMessage ?: "Connection failed"
                    ConnectionState.DISCONNECTED -> statusMessage ?: "Disconnected"
                    ConnectionState.CONNECTED -> ""
                },
                fontSize = 13.sp,
                color = if (connectionState == ConnectionState.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Chat Bubble Scroll Area
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            reverseLayout = false
        ) {
            items(messages) { message ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (message.isMe) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Surface(
                        color = if (message.isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 12.dp,
                            bottomStart = if (message.isMe) 12.dp else 0.dp,
                            bottomEnd = if (message.isMe) 0.dp else 12.dp
                        ),
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(
                            text = message.text,
                            color = if (message.isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        // Message Bottom Keyboard Entry Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = { Text("Type a message...") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (messageText.isNotBlank()) {
                        if (viewModel.sendMessage(messageText)) {
                            messageText = ""
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Send")
            }
        }
    }
}
