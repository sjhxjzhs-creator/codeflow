package com.codeflow.transfer

import android.content.Context
import com.codeflow.model.Device
import com.codeflow.model.Group
import com.codeflow.model.Message
import com.codeflow.model.MessageType
import com.codeflow.network.NetworkDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GroupManager(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val networkDiscovery = NetworkDiscovery(context)
    
    // 群组状态
    private var currentGroup: Group? = null
    private var isHost = false
    
    // Host 端：维护所有客户端连接
    private val clientConnections = ConcurrentHashMap<Socket, ClientInfo>()
    private var serverSocket: ServerSocket? = null
    
    // Client 端：连接到 Host 的 socket
    private var hostSocket: Socket? = null
    private var hostOutputStream: DataOutputStream? = null
    
    // 状态流转
    private val _groupState = MutableStateFlow(GroupState.DISCONNECTED)
    val groupState: StateFlow<GroupState> = _groupState
    
    // 回调
    var onGroupMessageReceived: ((Message) -> Unit)? = null
    var onGroupMemberUpdate: ((List<Device>) -> Unit)? = null
    var onGroupSystemMessage: ((String) -> Unit)? = null
    var onGroupError: ((String) -> Unit)? = null
    
    enum class GroupState {
        DISCONNECTED,
        CREATING,
        JOINING,
        CONNECTED_HOST,
        CONNECTED_CLIENT,
        DISCONNECTED_ERROR
    }
    
    data class ClientInfo(
        val device: Device,
        val outputStream: DataOutputStream,
        val readJob: Job
    )
    
    /**
     * 创建群聊（作为 Host）
     */
    fun createGroup(groupName: String = "Bchat 群聊"): Group? {
        try {
            _groupState.value = GroupState.CREATING
            
            // 获取本机热点 IP
            val hostIp = networkDiscovery.getLocalIpAddress()
                ?: run {
                    _groupState.value = GroupState.DISCONNECTED_ERROR
                    onGroupError?.invoke("无法获取热点 IP，请先开启热点")
                    return null
                }
            
            val port = 53318
            val deviceId = UUID.randomUUID().toString()
            val deviceName = networkDiscovery.getDeviceName()
            
            // 创建群组
            currentGroup = Group(
                groupId = UUID.randomUUID().toString(),
                groupName = groupName,
                hostDeviceId = deviceId,
                hostName = deviceName
            )
            
            isHost = true
            
            // 启动服务器监听
            startGroupServer(port)
            
            _groupState.value = GroupState.CONNECTED_HOST
            
            // 发送系统消息
            onGroupSystemMessage?.invoke("群聊已创建，等待成员加入")
            
            return currentGroup
        } catch (e: Exception) {
            e.printStackTrace()
            _groupState.value = GroupState.DISCONNECTED_ERROR
            onGroupError?.invoke("创建群聊失败：${e.message}")
            return null
        }
    }
    
    /**
     * 加入群聊（作为 Client）
     */
    fun joinGroup(hostIp: String, port: Int, groupId: String): Boolean {
        return try {
            _groupState.value = GroupState.JOINING
            
            val deviceId = UUID.randomUUID().toString()
            val deviceName = networkDiscovery.getDeviceName()
            
            // 连接到 Host
            hostSocket = Socket(hostIp, port)
            hostOutputStream = DataOutputStream(hostSocket!!.outputStream)
            
            // 发送加入请求
            val joinRequest = TransferProtocol.GroupJoin(
                groupId = groupId,
                deviceId = deviceId,
                deviceName = deviceName,
                timestamp = System.currentTimeMillis()
            )
            TransferProtocol.writePacket(hostOutputStream!!, TransferProtocol.PacketType.GROUP_JOIN, joinRequest)
            
            // 启动接收线程
            startClientReader(hostSocket!!)
            
            _groupState.value = GroupState.CONNECTED_CLIENT
            
            true
        } catch (e: IOException) {
            e.printStackTrace()
            _groupState.value = GroupState.DISCONNECTED_ERROR
            onGroupError?.invoke("无法连接到群主，请检查 IP 和端口")
            false
        }
    }
    
    /**
     * Host 启动服务器
     */
    private fun startGroupServer(port: Int) {
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                onGroupSystemMessage?.invoke("群主已启动，监听端口：$port")
                
                while (serverSocket != null && _groupState.value == GroupState.CONNECTED_HOST) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        handleClientConnection(clientSocket)
                    } catch (e: IOException) {
                        if (serverSocket != null) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onGroupError?.invoke("服务器启动失败：${e.message}")
            }
        }
    }
    
    /**
     * Host 处理客户端连接
     */
    private fun handleClientConnection(clientSocket: Socket) {
        scope.launch {
            try {
                val inputStream = clientSocket.inputStream
                
                // 读取第一个包（应该是 GROUP_JOIN）
                val result = TransferProtocol.readPacket(inputStream)
                if (result == null) {
                    clientSocket.close()
                    return@launch
                }
                
                val (type, payload) = result
                if (type != TransferProtocol.PacketType.GROUP_JOIN) {
                    clientSocket.close()
                    return@launch
                }
                
                val joinRequest: TransferProtocol.GroupJoin = TransferProtocol.parsePayload(payload)
                
                // 检查群是否已满
                if (currentGroup?.isFull == true) {
                    val groupFull = TransferProtocol.GroupFull(
                        groupId = currentGroup!!.groupId,
                        maxMembers = currentGroup!!.maxMembers
                    )
                    TransferProtocol.writePacket(
                        DataOutputStream(clientSocket.outputStream),
                        TransferProtocol.PacketType.GROUP_FULL,
                        groupFull
                    )
                    clientSocket.close()
                    withContext(Dispatchers.Main) {
                        onGroupSystemMessage?.invoke("拒绝 ${joinRequest.deviceName} 加入：群已满")
                    }
                    return@launch
                }
                
                // 添加客户端
                val device = Device(
                    id = joinRequest.deviceId,
                    name = joinRequest.deviceName,
                    connectionType = com.codeflow.model.ConnectionType.WIFI,
                    ipAddress = clientSocket.inetAddress.hostAddress
                )
                
                val outputStream = DataOutputStream(clientSocket.outputStream)
                val readJob = launch { readClientMessages(clientSocket, device) }
                
                clientConnections[clientSocket] = ClientInfo(device, outputStream, readJob)
                
                // 更新成员列表
                updateGroupMembers()
                
                // 广播新人加入
                broadcastGroupJoin(device)
                
                // 发送当前成员列表给新加入者
                sendMemberListToClient(outputStream)
                
                withContext(Dispatchers.Main) {
                    onGroupSystemMessage?.invoke("${joinRequest.deviceName} 加入群聊")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                clientSocket.close()
            }
        }
    }
    
    /**
     * Host 读取客户端消息
     */
    private suspend fun readClientMessages(clientSocket: Socket, device: Device) {
        try {
            val inputStream = clientSocket.inputStream
            while (clientSocket.isConnected && _groupState.value == GroupState.CONNECTED_HOST) {
                val result = TransferProtocol.readPacket(inputStream)
                if (result == null) {
                    break
                }
                
                val (type, payload) = result
                
                when (type) {
                    TransferProtocol.PacketType.TEXT_MESSAGE -> {
                        val textMsg: TransferProtocol.TextMessage = TransferProtocol.parsePayload(payload)
                        // 广播给其他客户端
                        broadcastMessage(clientSocket, type, payload)
                        
                        // 更新为群消息格式
                        val message = Message(
                            id = textMsg.messageId,
                            type = MessageType.GROUP_TEXT,
                            content = textMsg.content,
                            isFromMe = false,
                            senderId = textMsg.senderId,
                            senderName = textMsg.senderName,
                            groupId = textMsg.groupId,
                            isGroupMsg = true,
                            timestamp = textMsg.timestamp
                        )
                        
                        withContext(Dispatchers.Main) {
                            onGroupMessageReceived?.invoke(message)
                        }
                    }
                    TransferProtocol.PacketType.GROUP_LEAVE -> {
                        val leaveMsg: TransferProtocol.GroupLeave = TransferProtocol.parsePayload(payload)
                        removeClient(clientSocket)
                        broadcastGroupLeave(device)
                        withContext(Dispatchers.Main) {
                            onGroupSystemMessage?.invoke("${device.name} 离开群聊")
                        }
                        break
                    }
                    else -> {
                        // 其他类型也广播
                        broadcastMessage(clientSocket, type, payload)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            removeClient(clientSocket)
        }
    }
    
    /**
     * Client 读取服务器（Host）消息
     */
    private fun startClientReader(clientSocket: Socket) {
        scope.launch {
            try {
                val inputStream = clientSocket.inputStream
                while (clientSocket.isConnected && _groupState.value == GroupState.CONNECTED_CLIENT) {
                    val result = TransferProtocol.readPacket(inputStream)
                    if (result == null) {
                        break
                    }
                    
                    val (type, payload) = result
                    handleClientPacket(type, payload)
                }
                
                // 连接断开
                withContext(Dispatchers.Main) {
                    onGroupSystemMessage?.invoke("与群主断开连接，群聊已解散")
                    _groupState.value = GroupState.DISCONNECTED_ERROR
                }
            } catch (e: IOException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onGroupSystemMessage?.invoke("与群主断开连接，群聊已解散")
                    _groupState.value = GroupState.DISCONNECTED_ERROR
                }
            } finally {
                cleanupClient()
            }
        }
    }
    
    /**
     * Client 处理收到的包
     */
    private suspend fun handleClientPacket(
        type: TransferProtocol.PacketType,
        payload: ByteArray
    ) {
        when (type) {
            TransferProtocol.PacketType.TEXT_MESSAGE -> {
                val textMsg: TransferProtocol.TextMessage = TransferProtocol.parsePayload(payload)
                val message = Message(
                    id = textMsg.messageId,
                    type = MessageType.GROUP_TEXT,
                    content = textMsg.content,
                    isFromMe = false,
                    senderId = textMsg.senderId,
                    senderName = textMsg.senderName,
                    groupId = textMsg.groupId,
                    isGroupMsg = true,
                    timestamp = textMsg.timestamp
                )
                withContext(Dispatchers.Main) {
                    onGroupMessageReceived?.invoke(message)
                }
            }
            TransferProtocol.PacketType.GROUP_MEMBER_LIST -> {
                val memberList: TransferProtocol.GroupMemberList = TransferProtocol.parsePayload(payload)
                val devices = memberList.members.map { dto ->
                    Device(
                        id = dto.id,
                        name = dto.name,
                        connectionType = com.codeflow.model.ConnectionType.WIFI,
                        ipAddress = dto.ipAddress
                    )
                }
                withContext(Dispatchers.Main) {
                    onGroupMemberUpdate?.invoke(devices)
                }
            }
            TransferProtocol.PacketType.GROUP_JOIN -> {
                val joinMsg: TransferProtocol.GroupJoin = TransferProtocol.parsePayload(payload)
                withContext(Dispatchers.Main) {
                    onGroupSystemMessage?.invoke("${joinMsg.deviceName} 加入群聊")
                }
            }
            TransferProtocol.PacketType.GROUP_LEAVE -> {
                val leaveMsg: TransferProtocol.GroupLeave = TransferProtocol.parsePayload(payload)
                withContext(Dispatchers.Main) {
                    onGroupSystemMessage?.invoke("成员离开群聊")
                }
            }
            else -> {}
        }
    }
    
    /**
     * 发送群消息
     */
    fun sendGroupMessage(text: String) {
        val group = currentGroup ?: return
        val deviceId = UUID.randomUUID().toString()
        val deviceName = networkDiscovery.getDeviceName()
        
        val textMsg = TransferProtocol.TextMessage(
            messageId = UUID.randomUUID().toString(),
            content = text,
            timestamp = System.currentTimeMillis(),
            senderId = deviceId,
            senderName = deviceName,
            groupId = group.groupId,
            isGroupMsg = true
        )
        
        if (isHost) {
            // Host 直接广播
            broadcastMessage(null, TransferProtocol.PacketType.TEXT_MESSAGE, textMsg)
            
            val message = Message(
                id = textMsg.messageId,
                type = MessageType.GROUP_TEXT,
                content = text,
                isFromMe = true,
                senderId = deviceId,
                senderName = deviceName,
                groupId = group.groupId,
                isGroupMsg = true,
                timestamp = textMsg.timestamp
            )
            onGroupMessageReceived?.invoke(message)
        } else {
            // Client 发送给 Host
            hostOutputStream?.let {
                TransferProtocol.writePacket(it, TransferProtocol.PacketType.TEXT_MESSAGE, textMsg)
            }
        }
    }
    
    /**
     * 广播消息给所有客户端（Host 专用）
     */
    private fun broadcastMessage(
        excludeSocket: Socket?,
        type: TransferProtocol.PacketType,
        payload: Any
    ) {
        clientConnections.forEach { (socket, clientInfo) ->
            if (socket != excludeSocket && socket.isConnected) {
                try {
                    TransferProtocol.writePacket(clientInfo.outputStream, type, payload)
                    clientInfo.outputStream.flush()
                } catch (e: IOException) {
                    e.printStackTrace()
                    removeClient(socket)
                }
            }
        }
    }
    
    /**
     * 广播成员加入（Host 专用）
     */
    private fun broadcastGroupJoin(device: Device) {
        val joinMsg = TransferProtocol.GroupJoin(
            groupId = currentGroup!!.groupId,
            deviceId = device.id,
            deviceName = device.name,
            timestamp = System.currentTimeMillis()
        )
        broadcastMessage(null, TransferProtocol.PacketType.GROUP_JOIN, joinMsg)
    }
    
    /**
     * 广播成员离开（Host 专用）
     */
    private fun broadcastGroupLeave(device: Device) {
        val leaveMsg = TransferProtocol.GroupLeave(
            groupId = currentGroup!!.groupId,
            deviceId = device.id,
            timestamp = System.currentTimeMillis()
        )
        broadcastMessage(null, TransferProtocol.PacketType.GROUP_LEAVE, leaveMsg)
    }
    
    /**
     * 发送成员列表给客户端（Host 专用）
     */
    private fun sendMemberListToClient(outputStream: DataOutputStream) {
        val memberList = TransferProtocol.GroupMemberList(
            groupId = currentGroup!!.groupId,
            hostDeviceId = currentGroup!!.hostDeviceId,
            hostName = currentGroup!!.hostName,
            members = clientConnections.values.map { clientInfo ->
                TransferProtocol.DeviceDto(
                    id = clientInfo.device.id,
                    name = clientInfo.device.name,
                    ipAddress = clientInfo.device.ipAddress
                )
            }
        )
        TransferProtocol.writePacket(outputStream, TransferProtocol.PacketType.GROUP_MEMBER_LIST, memberList)
    }
    
    /**
     * 更新群组成员列表
     */
    private fun updateGroupMembers() {
        currentGroup = currentGroup?.copy(
            members = clientConnections.values.map { it.device }
        )
    }
    
    /**
     * 移除客户端（Host 专用）
     */
    private fun removeClient(clientSocket: Socket) {
        val clientInfo = clientConnections.remove(clientSocket)
        clientInfo?.readJob?.cancel()
        try { clientSocket.close() } catch (_: IOException) {}
        updateGroupMembers()
    }
    
    /**
     * 清理客户端资源
     */
    private fun cleanupClient() {
        hostOutputStream?.close()
        hostSocket?.close()
        hostOutputStream = null
        hostSocket = null
    }
    
    /**
     * 离开群聊
     */
    fun leaveGroup() {
        if (isHost) {
            // Host 离开 = 解散群
            broadcastMessage(null, TransferProtocol.PacketType.GROUP_LEAVE, 
                TransferProtocol.GroupLeave(currentGroup!!.groupId, "", System.currentTimeMillis()))
            clientConnections.values.forEach { it.readJob.cancel() }
            clientConnections.clear()
            serverSocket?.close()
            serverSocket = null
        } else {
            // Client 离开
            hostOutputStream?.let {
                TransferProtocol.writePacket(it, TransferProtocol.PacketType.GROUP_LEAVE,
                    TransferProtocol.GroupLeave(currentGroup!!.groupId, "", System.currentTimeMillis()))
            }
            cleanupClient()
        }
        
        currentGroup = null
        isHost = false
        _groupState.value = GroupState.DISCONNECTED
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        leaveGroup()
        scope.cancel()
    }
    
    /**
     * 获取群聊连接信息（用于生成二维码）
     */
    fun getGroupConnectionInfo(): String? {
        if (!isHost || currentGroup == null) return null
        
        val ip = networkDiscovery.getLocalIpAddress() ?: return null
        val port = 53318
        
        return """
            {
                "ip": "$ip",
                "port": $port,
                "groupId": "${currentGroup!!.groupId}",
                "groupName": "${currentGroup!!.groupName}",
                "host": "${currentGroup!!.hostName}"
            }
        """.trimIndent()
    }
}
