package com.codeflow.transfer

import com.codeflow.model.MessageType
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object TransferProtocol {

    const val HEADER_MAGIC = 0x43464C57 // "CFLW"
    const val PROTOCOL_VERSION: Short = 1

    @PublishedApi
    internal val gson = Gson()

    data class PacketHeader(
        @SerializedName("magic") val magic: Int = HEADER_MAGIC,
        @SerializedName("version") val version: Short = PROTOCOL_VERSION,
        @SerializedName("type") val type: String,
        @SerializedName("payloadSize") val payloadSize: Int
    )

    enum class PacketType(val value: String) {
        CONNECTION_REQUEST("conn_req"),
        CONNECTION_ACCEPT("conn_accept"),
        CONNECTION_REJECT("conn_reject"),
        TEXT_MESSAGE("text_msg"),
        FILE_INFO("file_info"),
        FILE_DATA("file_data"),
        FILE_COMPLETE("file_complete"),
        DISCONNECT("disconnect"),
        DEVICE_INFO("device_info"),
        // 群聊相关
        GROUP_ANNOUNCE("group_announce"),
        GROUP_JOIN("group_join"),
        GROUP_ACCEPT("group_accept"),
        GROUP_REJECT("group_reject"),
        GROUP_MSG("group_msg"),
        GROUP_FILE("group_file"),
        GROUP_LEAVE("group_leave"),
        GROUP_DISBAND("group_disband"),
        GROUP_MEMBERS("group_members");

        companion object {
            fun fromValue(value: String): PacketType? =
                entries.find { it.value == value }
        }
    }

    data class ConnectionRequest(
        @SerializedName("deviceId") val deviceId: String,
        @SerializedName("deviceName") val deviceName: String,
        @SerializedName("connectionType") val connectionType: String
    )

    data class DeviceInfo(
        @SerializedName("deviceId") val deviceId: String,
        @SerializedName("deviceName") val deviceName: String,
        @SerializedName("platform") val platform: String = "android"
    )

    data class TextMessage(
        @SerializedName("messageId") val messageId: String,
        @SerializedName("content") val content: String,
        @SerializedName("timestamp") val timestamp: Long
    )

    data class FileInfo(
        @SerializedName("messageId") val messageId: String,
        @SerializedName("fileName") val fileName: String,
        @SerializedName("fileSize") val fileSize: Long,
        @SerializedName("fileType") val fileType: String,
        @SerializedName("timestamp") val timestamp: Long
    )

    // ---- 群聊相关数据类 ----

    data class GroupAnnounce(
        @SerializedName("groupId") val groupId: String,
        @SerializedName("groupName") val groupName: String,
        @SerializedName("hostName") val hostName: String,
        @SerializedName("hostIp") val hostIp: String,
        @SerializedName("port") val port: Int,
        @SerializedName("hasPassword") val hasPassword: Boolean,
        @SerializedName("memberCount") val memberCount: Int,
        @SerializedName("timestamp") val timestamp: Long
    )

    data class GroupJoinRequest(
        @SerializedName("groupId") val groupId: String,
        @SerializedName("nickname") val nickname: String,
        @SerializedName("password") val password: String?,
        @SerializedName("memberId") val memberId: String,
        @SerializedName("timestamp") val timestamp: Long
    )

    data class GroupJoinResult(
        @SerializedName("groupId") val groupId: String,
        @SerializedName("groupName") val groupName: String,
        @SerializedName("accepted") val accepted: Boolean,
        @SerializedName("reason") val reason: String?,
        @SerializedName("hostName") val hostName: String? = null,
        @SerializedName("members") val members: List<GroupMemberDto> = emptyList()
    )

    data class GroupMemberDto(
        @SerializedName("memberId") val memberId: String,
        @SerializedName("nickname") val nickname: String,
        @SerializedName("isHost") val isHost: Boolean = false
    )

    data class GroupMessage(
        @SerializedName("groupId") val groupId: String,
        @SerializedName("content") val content: String,
        @SerializedName("type") val type: String,
        @SerializedName("filePath") val filePath: String? = null,
        @SerializedName("fileName") val fileName: String? = null,
        @SerializedName("fileSize") val fileSize: Long = 0,
        @SerializedName("senderId") val senderId: String,
        @SerializedName("senderName") val senderName: String,
        @SerializedName("timestamp") val timestamp: Long
    )

    data class GroupMembers(
        @SerializedName("groupId") val groupId: String,
        @SerializedName("members") val members: List<GroupMemberDto>
    )

    data class GroupLeave(
        @SerializedName("groupId") val groupId: String,
        @SerializedName("memberId") val memberId: String,
        @SerializedName("nickname") val nickname: String? = null,
        @SerializedName("reason") val reason: String? = null
    )

    data class GroupFileHeader(
        @SerializedName("groupId") val groupId: String,
        @SerializedName("fileName") val fileName: String,
        @SerializedName("fileSize") val fileSize: Long,
        @SerializedName("senderId") val senderId: String,
        @SerializedName("senderName") val senderName: String,
        @SerializedName("timestamp") val timestamp: Long
    )

    fun writePacket(output: OutputStream, type: PacketType, payload: Any) {
        val jsonPayload = gson.toJson(payload)
        val payloadBytes = jsonPayload.toByteArray(Charsets.UTF_8)

        val headerSize = 4 + 2 + 16 + 4 // magic + version + type + payloadSize
        val totalSize = headerSize + payloadBytes.size

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putInt(HEADER_MAGIC)
        buffer.putShort(PROTOCOL_VERSION)
        val typeBytes = ByteArray(16)
        val typeStr = type.value
        System.arraycopy(typeStr.toByteArray(Charsets.UTF_8), 0, typeBytes, 0, minOf(typeStr.length, 16))
        buffer.put(typeBytes)
        buffer.putInt(payloadBytes.size)
        buffer.put(payloadBytes)

        output.write(buffer.array())
        output.flush()
    }

    // 发送原始字节 payload（不进行 JSON 编码），用于文件等二进制数据
    fun writeRawPacket(output: OutputStream, type: PacketType, payloadBytes: ByteArray) {
        val headerSize = 4 + 2 + 16 + 4
        val totalSize = headerSize + payloadBytes.size

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putInt(HEADER_MAGIC)
        buffer.putShort(PROTOCOL_VERSION)
        val typeBytes = ByteArray(16)
        val typeStr = type.value
        System.arraycopy(typeStr.toByteArray(Charsets.UTF_8), 0, typeBytes, 0, minOf(typeStr.length, 16))
        buffer.put(typeBytes)
        buffer.putInt(payloadBytes.size)
        buffer.put(payloadBytes)

        output.write(buffer.array())
        output.flush()
    }

    fun readPacket(input: InputStream): Pair<PacketType, ByteArray>? {
        val headerBytes = ByteArray(26) // 4 + 2 + 16 + 4
        var read = 0
        while (read < headerBytes.size) {
            val n = input.read(headerBytes, read, headerBytes.size - read)
            if (n < 0) return null
            read += n
        }

        val buffer = ByteBuffer.wrap(headerBytes)
        val magic = buffer.int
        if (magic != HEADER_MAGIC) return null

        val version = buffer.short
        val typeBytes = ByteArray(16)
        buffer.get(typeBytes)
        val type = String(typeBytes).trimEnd('\u0000')
        val payloadSize = buffer.int

        if (payloadSize < 0 || payloadSize > 100 * 1024 * 1024) return null

        val payloadBytes = ByteArray(payloadSize)
        read = 0
        while (read < payloadSize) {
            val n = input.read(payloadBytes, read, payloadSize - read)
            if (n < 0) return null
            read += n
        }

        val packetType = PacketType.fromValue(type) ?: return null
        return Pair(packetType, payloadBytes)
    }

    inline fun <reified T> parsePayload(payload: ByteArray): T {
        return gson.fromJson(String(payload, Charsets.UTF_8), T::class.java)
    }

    fun toJson(obj: Any): String = gson.toJson(obj)

    inline fun <reified T> fromJson(json: String): T = gson.fromJson(json, T::class.java)
}
