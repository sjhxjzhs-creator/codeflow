package com.codeflow.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

enum class MessageType {
    TEXT,
    IMAGE,
    FILE,
    SYSTEM
}

enum class MessageStatus {
    SENDING,
    SENT,
    RECEIVING,
    RECEIVED,
    FAILED
}

@Parcelize
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val type: MessageType,
    val content: String,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val filePath: String? = null,
    val isFromMe: Boolean,
    val status: MessageStatus = MessageStatus.SENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val progress: Int = 0,
    val senderName: String? = null
) : Parcelable
