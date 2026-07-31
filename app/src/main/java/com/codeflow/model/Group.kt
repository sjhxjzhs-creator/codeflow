package com.codeflow.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class Group(
    val groupId: String = UUID.randomUUID().toString(),
    val groupName: String = "Bchat 群聊",
    val hostDeviceId: String,
    val hostName: String,
    val members: List<Device> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val maxMembers: Int = 5
) : Parcelable {
    
    val memberCount: Int
        get() = members.size + 1 // +1 for host
    
    val fullGroupName: String
        get() = "$groupName ($memberCount)"
    
    val isFull: Boolean
        get() = memberCount >= maxMembers
    
    fun isHost(deviceId: String): Boolean {
        return hostDeviceId == deviceId
    }
    
    fun isMember(deviceId: String): Boolean {
        return hostDeviceId == deviceId || members.any { it.id == deviceId }
    }
}
