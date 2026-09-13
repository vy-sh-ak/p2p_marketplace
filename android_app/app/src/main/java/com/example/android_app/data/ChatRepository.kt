package com.example.android_app.data

import com.example.rust_core.ChatRoom
import com.example.rust_core.connectDatabase
import com.example.rust_core.createRoom as ffiCreateRoom
import com.example.rust_core.deleteRoom as ffiDeleteRoom
import com.example.rust_core.getRoom as ffiGetRoom
import com.example.rust_core.setRoomAnswer as ffiSetRoomAnswer
import com.example.rust_core.setRoomOffer as ffiSetRoomOffer

class ChatRepository {

    suspend fun connect() = connectDatabase()

    suspend fun createRoom(roomId: String): ChatRoom = ffiCreateRoom(roomId)

    suspend fun getRoom(roomId: String): ChatRoom? = ffiGetRoom(roomId)

    suspend fun setOffer(roomId: String, offerSdp: String): ChatRoom = ffiSetRoomOffer(roomId, offerSdp)

    suspend fun setAnswer(roomId: String, answerSdp: String): ChatRoom = ffiSetRoomAnswer(roomId, answerSdp)

    suspend fun deleteRoom(rowId: Long): Boolean = ffiDeleteRoom(rowId)
}
