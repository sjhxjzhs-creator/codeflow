package com.codeflow.transfer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.codeflow.CodeFlowApp
import com.codeflow.model.Group
import com.codeflow.model.GroupMember
import com.codeflow.model.GroupSession
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GroupManager(
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    companion object {
        const val GROUP_PORT = 53319
        const val GROUP_MULTICAST_ADDRESS = "224.0.0.168"
        const val GROUP_DISCOVERY_PORT = 53320
        const val MAX_GROUP_FILE_SIZE = 5 * 1024 * 1024 // 5MB
    }

    // 当前群会话（房主或成员）
    var currentSession: GroupSession? = null
        private set

    // 房主主机侧的群信息
    var hostGroupInfo: Group? = null
        private set

    // 房主：已连接成员
    private val hostMemberClients = ConcurrentHashMap<String, HostClient>()

    // 成员：连接房主的 socket
    private var memberSocket: Socket? = null
    private var memberInput: BufferedInputStream? = null
    private var memberOutput: BufferedOutputStream? = null

    // 广播/监听
    private var announceSocket: DatagramSocket? = null
    private var announceJob: Job? = null
    private var serverJob: Job? = null
    private var memberReadJob: Job? = null
    private var isHostRunning = false

    private val _discoveredGroups = mutableListOf<Group>()
    val discoveredGroups: List<Group>
        get() = synchronized(_discoveredGroups) { _discoveredGroups.toList() }

    // 回调
    var onMemberJoined: ((List<GroupMember>) -> Unit)? = null
    var onMessageReceived: ((TransferProtocol.GroupMessage) -> Unit)? = null
    var onFileReceived: ((TransferProtocol.GroupFileHeader, File) -> Unit)? = null
    var onMemberChanged: ((List<GroupMember>) -> Unit)? = null
    var onGroupDisbanded: ((String?) -> Unit)? = null
    var onJoinRejected: ((String) -> Unit)? = null
    var onDiscoveredGroupsChanged: (() -> Unit)? = null

    // ==================== 房主建群 ====================

    fun createGroup(groupName: String, password: String?, nickname: String): Result<GroupSession> {
        if (isHostRunning) {
            return Result.failure(Exception("已有一个群在运行"))
        }
        return try {
            val memberId = UUID.randomUUID().toString()
            val groupId = "g_${System.currentTimeMillis()}"
            val hostSession = GroupSession(
                groupId = groupId,
                groupName = groupName,
                hostName = nickname,
                hostIp = getLocalIpAddress() ?: return Result.failure(Exception("无法获取本机IP，请检查WiFi")),
                hostPort = GROUP_PORT,
                isHost = true,
                myMemberId = memberId,
                myNickname = nickname
            )

            hostGroupInfo = Group(
                id = groupId,
                name = groupName,
                hostName = nickname,
                hostIp = hostSession.hostIp,
                hostPort = GROUP_PORT,
                hasPassword = !password.isNullOrEmpty(),
                memberCount = 1
            )

            val serverSocket = ServerSocket(GROUP_PORT, 16)
            isHostRunning = true
            currentSession = hostSession

            hostMemberClients[memberId] = HostClient(
                socket = null,
                input = null,
                output = null,
                memberId = memberId,
                nickname = nickname,
                isHost = true,
                password = password
            )

            serverJob = scope.launch {
                while (isActive && isHostRunning) {
                    try {
                        val client = serverSocket.accept()
                        // 每个连接独立协程，避免阻塞 accept 循环
                        scope.launch { handleHostAccept(client) }
                    } catch (e: IOException) {
                        if (isHostRunning) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            startGroupAnnounce()
            mainHandler.post {
                onMemberChanged?.invoke(getHostMemberList())
            }
            Result.success(hostSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun handleHostAccept(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val (type, payload) = TransferProtocol.readPacket(input)
                ?: run { socket.close(); return }
            if (type != TransferProtocol.PacketType.GROUP_JOIN) {
                socket.close()
                return
            }

            val joinReq = TransferProtocol.fromJson<TransferProtocol.GroupJoinRequest>(
                String(payload, Charsets.UTF_8)
            )

            val session = currentSession ?: run { socket.close(); return }
            val hostClient = hostMemberClients.values.firstOrNull { it.isHost }

            // 校验密码
            val hostPassword = hostClient?.password
            val passwordOk = hostPassword.isNullOrEmpty() || hostPassword == joinReq.password
            val alreadyIn = hostMemberClients.containsKey(joinReq.memberId)

            if (!passwordOk) {
                sendTo(output, TransferProtocol.PacketType.GROUP_REJECT,
                    TransferProtocol.GroupJoinResult(
                        groupId = session.groupId,
                        groupName = session.groupName,
                        accepted = false,
                        reason = "密码错误"
                    )
                )
                socket.close()
                return
            }

            if (alreadyIn) {
                sendTo(output, TransferProtocol.PacketType.GROUP_REJECT,
                    TransferProtocol.GroupJoinResult(
                        groupId = session.groupId,
                        groupName = session.groupName,
                        accepted = false,
                        reason = "重复连接"
                    )
                )
                socket.close()
                return
            }

            val client = HostClient(
                socket = socket,
                input = input,
                output = output,
                memberId = joinReq.memberId,
                nickname = joinReq.nickname,
                isHost = false,
                password = null
            )
            hostMemberClients[joinReq.memberId] = client
            hostGroupInfo?.memberCount = hostMemberClients.size

            // 发送 ACCEPT + 成员列表
            sendTo(output, TransferProtocol.PacketType.GROUP_ACCEPT,
                TransferProtocol.GroupJoinResult(
                    groupId = session.groupId,
                    groupName = session.groupName,
                    accepted = true,
                    reason = null,
                    hostName = session.hostName,
                    members = toMemberDto(getHostMemberList())
                )
            )

            // 通知其他成员：有新成员
            broadcastMembers(session, excludeMemberId = null)
            mainHandler.post {
                onMemberChanged?.invoke(getHostMemberList())
                onDiscoveredGroupUpdate()
            }

            // 启动该成员的读取循环
            readHostClient(client, session)
        } catch (e: IOException) {
            e.printStackTrace()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun readHostClient(client: HostClient, session: GroupSession) {
        try {
            val input = client.input ?: return
            while (isHostRunning) {
                val result = TransferProtocol.readPacket(input) ?: break
                val (type, payload) = result
                when (type) {
                    TransferProtocol.PacketType.GROUP_MSG -> {
                        val msg = TransferProtocol.fromJson<TransferProtocol.GroupMessage>(
                            String(payload, Charsets.UTF_8)
                        )
                        forwardMessage(msg, excludeMemberId = client.memberId)
                        mainHandler.post { onMessageReceived?.invoke(msg) }
                    }
                    TransferProtocol.PacketType.GROUP_FILE -> {
                        forwardFile(client, session, payload)
                    }
                    TransferProtocol.PacketType.GROUP_LEAVE -> {
                        hostMemberClients.remove(client.memberId)
                        removeHostClient(client)
                        hostGroupInfo?.memberCount = hostMemberClients.size
                        broadcastMembers(session, excludeMemberId = null)
                        mainHandler.post { onMemberChanged?.invoke(getHostMemberList()) }
                        return
                    }
                    else -> {}
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            hostMemberClients.remove(client.memberId)
            removeHostClient(client)
            hostGroupInfo?.memberCount = hostMemberClients.size
            broadcastMembers(session, excludeMemberId = null)
            mainHandler.post { onMemberChanged?.invoke(getHostMemberList()) }
        }
    }

    private fun forwardFile(client: HostClient, session: GroupSession, payload: ByteArray) {
        try {
            // payload = fileHeaderJson + 4字节长度 + 文件字节
            val headerLen = extractInt(payload, 0)
            val headerJson = String(payload, 8, headerLen, Charsets.UTF_8)
            val header = TransferProtocol.fromJson<TransferProtocol.GroupFileHeader>(headerJson)
            val fileBytes = payload.copyOfRange(8 + headerLen, payload.size)

            hostMemberClients.values.forEach { c ->
                if (c.memberId != client.memberId && c.socket != null && c.output != null) {
                    try {
                        sendRawTo(c.output!!, TransferProtocol.PacketType.GROUP_FILE, payload)
                    } catch (e: IOException) {
                        // 忽略单个失败
                    }
                }
            }
            saveReceivedFile(header, fileBytes)
            mainHandler.post { onFileReceived?.invoke(header, lastReceivedFile ?: File("")) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var lastReceivedFile: File? = null

    private fun saveReceivedFile(header: TransferProtocol.GroupFileHeader, bytes: ByteArray) {
        try {
            val dir = File(CodeFlowApp.getAppContext().filesDir, "group_transfers")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, sanitizeName(header.fileName))
            file.writeBytes(bytes)
            lastReceivedFile = file
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun forwardMessage(msg: TransferProtocol.GroupMessage, excludeMemberId: String?) {
        hostMemberClients.values.forEach { c ->
            if (c.memberId != excludeMemberId && c.socket != null && c.output != null) {
                try {
                    sendTo(c.output!!, TransferProtocol.PacketType.GROUP_MSG,
                        msg.copy(senderId = msg.senderId, senderName = msg.senderName))
                } catch (e: IOException) {
                    // 忽略
                }
            }
        }
        mainHandler.post { onMessageReceived?.invoke(msg) }
    }

    private fun broadcastMembers(session: GroupSession, excludeMemberId: String?) {
        val memberDto = toMemberDto(getHostMemberList())
        val payload = TransferProtocol.GroupMembers(session.groupId, memberDto)
        hostMemberClients.values.forEach { c ->
            if (c.memberId != excludeMemberId && c.socket != null && c.output != null) {
                try {
                    sendTo(c.output!!, TransferProtocol.PacketType.GROUP_MEMBERS, payload)
                } catch (e: IOException) {
                    // 忽略
                }
            }
        }
    }

    private fun getHostMemberList(): List<GroupMember> {
        return hostMemberClients.values.map {
            GroupMember(it.memberId, it.nickname, it.isHost)
        }
    }

    private fun toMemberDto(list: List<GroupMember>): List<TransferProtocol.GroupMemberDto> {
        return list.map { TransferProtocol.GroupMemberDto(it.id, it.nickname, it.isHost) }
    }

    // ==================== 成员加入群 ====================

    fun joinGroup(
        hostIp: String,
        port: Int,
        groupId: String,
        groupName: String,
        password: String?,
        nickname: String
    ): Result<GroupSession> {
        return try {
            val memberId = UUID.randomUUID().toString()
            val socket = Socket()
            socket.connect(InetSocketAddress(hostIp, port), 8000)
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            sendTo(output, TransferProtocol.PacketType.GROUP_JOIN,
                TransferProtocol.GroupJoinRequest(
                    groupId = groupId,
                    nickname = nickname,
                    password = password,
                    memberId = memberId,
                    timestamp = System.currentTimeMillis()
                )
            )

            val (type, payload) = TransferProtocol.readPacket(input)
                ?: throw IOException("连接超时")

            when (type) {
                TransferProtocol.PacketType.GROUP_ACCEPT -> {
                    val result = TransferProtocol.fromJson<TransferProtocol.GroupJoinResult>(
                        String(payload, Charsets.UTF_8)
                    )
                    val session = GroupSession(
                        groupId = result.groupId,
                        groupName = result.groupName,
                        hostName = result.hostName ?: "房主",
                        hostIp = hostIp,
                        hostPort = port,
                        isHost = false,
                        myMemberId = memberId,
                        myNickname = nickname
                    )
                    memberSocket = socket
                    memberInput = input
                    memberOutput = output
                    currentSession = session
                    startMemberReadLoop(session, socket, input)
                    mainHandler.post {
                        onMemberChanged?.invoke(result.members.map {
                            GroupMember(it.memberId, it.nickname, it.isHost)
                        })
                    }
                    Result.success(session)
                }
                TransferProtocol.PacketType.GROUP_REJECT -> {
                    val result = TransferProtocol.fromJson<TransferProtocol.GroupJoinResult>(
                        String(payload, Charsets.UTF_8)
                    )
                    socket.close()
                    mainHandler.post { onJoinRejected?.invoke(result.reason ?: "加入被拒绝") }
                    Result.failure(Exception(result.reason ?: "加入被拒绝"))
                }
                else -> {
                    socket.close()
                    Result.failure(Exception("协议错误"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun startMemberReadLoop(session: GroupSession, socket: Socket, input: InputStream) {
        memberReadJob?.cancel()
        memberReadJob = scope.launch {
            try {
                while (isActive && !socket.isClosed) {
                    val result = TransferProtocol.readPacket(input) ?: break
                    val (type, payload) = result
                    when (type) {
                        TransferProtocol.PacketType.GROUP_MSG -> {
                            val msg = TransferProtocol.fromJson<TransferProtocol.GroupMessage>(
                                String(payload, Charsets.UTF_8)
                            )
                            mainHandler.post { onMessageReceived?.invoke(msg) }
                        }
                        TransferProtocol.PacketType.GROUP_FILE -> {
                            handleGroupFilePayload(payload)
                        }
                        TransferProtocol.PacketType.GROUP_MEMBERS -> {
                            val members = TransferProtocol.fromJson<TransferProtocol.GroupMembers>(
                                String(payload, Charsets.UTF_8)
                            )
                            mainHandler.post {
                                onMemberChanged?.invoke(members.members.map {
                                    GroupMember(it.memberId, it.nickname, it.isHost)
                                })
                            }
                        }
                        TransferProtocol.PacketType.GROUP_DISBAND -> {
                            mainHandler.post { onGroupDisbanded?.invoke(null) }
                            leaveGroupInternal()
                            return@launch
                        }
                        else -> {}
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                mainHandler.post { onGroupDisbanded?.invoke("连接已断开") }
            }
        }
    }

    private fun handleGroupFilePayload(payload: ByteArray) {
        try {
            val headerLen = extractInt(payload, 0)
            val headerJson = String(payload, 8, headerLen, Charsets.UTF_8)
            val header = TransferProtocol.fromJson<TransferProtocol.GroupFileHeader>(headerJson)
            val fileBytes = payload.copyOfRange(8 + headerLen, payload.size)
            saveReceivedFile(header, fileBytes)
            mainHandler.post { onFileReceived?.invoke(header, lastReceivedFile ?: File("")) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 消息发送 ====================

    // 房主发消息
    fun hostSendText(content: String) {
        val session = currentSession ?: return
        val msg = TransferProtocol.GroupMessage(
            groupId = session.groupId,
            content = content,
            type = "TEXT",
            senderId = session.myMemberId,
            senderName = session.myNickname,
            timestamp = System.currentTimeMillis()
        )
        forwardMessage(msg, excludeMemberId = null)
    }

    // 成员发消息
    fun memberSendText(content: String) {
        val session = currentSession ?: return
        val msg = TransferProtocol.GroupMessage(
            groupId = session.groupId,
            content = content,
            type = "TEXT",
            senderId = session.myMemberId,
            senderName = session.myNickname,
            timestamp = System.currentTimeMillis()
        )
        if (session.isHost) {
            hostSendText(content)
        } else {
            sendTo(memberOutput, TransferProtocol.PacketType.GROUP_MSG, msg)
            mainHandler.post { onMessageReceived?.invoke(msg) }
        }
    }

    // 发送文件（大小 ≤ 5MB）
    fun sendFile(file: File): Result<Unit> {
        val session = currentSession ?: return Result.failure(Exception("未加入群聊"))
        if (file.length() > MAX_GROUP_FILE_SIZE) {
            return Result.failure(Exception("群聊文件大小不能超过5MB"))
        }
        return try {
            val header = TransferProtocol.GroupFileHeader(
                groupId = session.groupId,
                fileName = file.name,
                fileSize = file.length(),
                senderId = session.myMemberId,
                senderName = session.myNickname,
                timestamp = System.currentTimeMillis()
            )
            val headerJson = gson.toJson(header)
            val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
            val fileBytes = file.readBytes()
            val payload = ByteArray(8 + headerBytes.size + fileBytes.size)
            writeInt(payload, 0, headerBytes.size)
            headerBytes.copyInto(payload, 8)
            fileBytes.copyInto(payload, 8 + headerBytes.size)

            if (session.isHost) {
                // 房主直接广播给所有成员
                hostMemberClients.values.forEach { c ->
                    if (!c.isHost && c.socket != null && c.output != null) {
                        try {
                            sendRawTo(c.output!!, TransferProtocol.PacketType.GROUP_FILE, payload)
                        } catch (e: IOException) {}
                    }
                }
            } else {
                sendRawTo(memberOutput, TransferProtocol.PacketType.GROUP_FILE, payload)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 群生命周期 ====================

    fun leaveGroup() {
        val session = currentSession ?: return
        if (session.isHost) {
            disbandGroupInternal()
        } else {
            try {
                sendTo(memberOutput, TransferProtocol.PacketType.GROUP_LEAVE,
                    TransferProtocol.GroupLeave(session.groupId, session.myMemberId, session.myNickname))
            } catch (e: Exception) {}
            leaveGroupInternal()
        }
    }

    private fun disbandGroupInternal() {
        val session = currentSession
        val payload = if (session != null) {
            TransferProtocol.GroupLeave(session.groupId, "", null, "disbanded")
        } else {
            null
        }
        hostMemberClients.values.forEach { c ->
            if (c.socket != null && c.output != null && !c.isHost) {
                try {
                    sendTo(c.output!!, TransferProtocol.PacketType.GROUP_DISBAND, payload ?: "")
                } catch (e: IOException) {}
                try { c.socket?.close() } catch (_: Exception) {}
            }
        }
        stopGroupAnnounce()
        isHostRunning = false
        serverJob?.cancel()
        serverJob = null
        hostMemberClients.clear()
        hostGroupInfo = null
        currentSession = null
    }

    private fun leaveGroupInternal() {
        try { memberSocket?.close() } catch (_: Exception) {}
        memberSocket = null
        memberInput = null
        memberOutput = null
        memberReadJob?.cancel()
        memberReadJob = null
        currentSession = null
    }

    fun cleanup() {
        scope.cancel()
        stopGroupAnnounce()
        isHostRunning = false
        try { hostMemberClients.values.forEach { it.socket?.close() } } catch (_: Exception) {}
        hostMemberClients.clear()
        try { memberSocket?.close() } catch (_: Exception) {}
        memberSocket = null
        currentSession = null
        hostGroupInfo = null
    }

    fun isInGroup(): Boolean = currentSession != null

    // ==================== UDP 群广播发现 ====================

    private fun stopGroupAnnounce() {
        announceJob?.cancel()
        announceJob = null
        try { announceSocket?.close() } catch (_: Exception) {}
        announceSocket = null
    }

    private fun startGroupAnnounce() {
        val session = currentSession ?: return
        announceJob = scope.launch {
            val announceSocketLocal = DatagramSocket()
            try {
                val info = hostGroupInfo ?: return@launch
                val announce = TransferProtocol.GroupAnnounce(
                    groupId = info.id,
                    groupName = info.name,
                    hostName = info.hostName,
                    hostIp = info.hostIp,
                    port = info.hostPort,
                    hasPassword = info.hasPassword,
                    memberCount = hostMemberClients.size,
                    timestamp = System.currentTimeMillis()
                )
                val json = gson.toJson(announce)
                val bytes = json.toByteArray(Charsets.UTF_8)

                val lock = getWifiManager().createMulticastLock("codeflow_group_announce")
                lock.acquire()
                announceSocket = announceSocketLocal

                val address = InetAddress.getByName(GROUP_MULTICAST_ADDRESS)
                while (isActive && isHostRunning) {
                    val packet = DatagramPacket(
                        bytes, bytes.size,
                        address, GROUP_DISCOVERY_PORT
                    )
                    try {
                        announceSocketLocal.send(packet)
                    } catch (e: Exception) {}
                    // 也发广播
                    try {
                        val broadcastPacket = DatagramPacket(
                            bytes, bytes.size,
                            InetAddress.getByName("255.255.255.255"), GROUP_DISCOVERY_PORT
                        )
                        announceSocketLocal.send(broadcastPacket)
                    } catch (e: Exception) {}
                    delay(2000)
                }
                try { lock.release() } catch (_: Exception) {}
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                announceSocketLocal.close()
                announceSocket = null
            }
        }
    }

    fun startGroupDiscovery(onNewGroup: (Group) -> Unit) {
        stopGroupDiscovery()
        announceJob = scope.launch {
            val buffer = ByteArray(2048)
            val socket = MulticastSocket(GROUP_DISCOVERY_PORT)
            socket.broadcast = true
            socket.soTimeout = 1000
            try {
                val group = InetAddress.getByName(GROUP_MULTICAST_ADDRESS)
                try { socket.joinGroup(group) } catch (_: Exception) {}

                val known = mutableSetOf<String>()

                while (isActive) {
                    // 广播接收
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(receivePacket)
                        parseGroupAnnounce(receivePacket, known, onNewGroup)
                    } catch (e: Exception) {
                        // timeout or io
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket.close()
            }
        }
    }

    private fun parseGroupAnnounce(
        packet: DatagramPacket,
        known: MutableSet<String>,
        onNewGroup: (Group) -> Unit
    ) {
        try {
            val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val info = TransferProtocol.fromJson<TransferProtocol.GroupAnnounce>(json)
            if (info.groupName.isEmpty()) return
            if (currentSession?.groupId == info.groupId) return

            val group = Group(
                id = info.groupId,
                name = info.groupName,
                hostName = info.hostName,
                hostIp = info.hostIp,
                hostPort = info.port,
                hasPassword = info.hasPassword,
                memberCount = info.memberCount,
                timestamp = info.timestamp
            )
            val key = "${info.groupId}"
            if (known.add(key)) {
                synchronized(_discoveredGroups) {
                    _discoveredGroups.removeAll { it.id == group.id }
                    _discoveredGroups.add(group)
                }
            } else {
                synchronized(_discoveredGroups) {
                    val existing = _discoveredGroups.firstOrNull { it.id == group.id }
                    existing?.memberCount = info.memberCount
                }
            }
            mainHandler.post {
                onNewGroup(group)
                onDiscoveredGroupsChanged?.invoke()
            }
        } catch (e: Exception) {
            // 忽略非群聊广播包
        }
    }

    fun stopGroupDiscovery() {
        announceJob?.cancel()
        announceJob = null
        announceSocket?.close()
        announceSocket = null
        synchronized(_discoveredGroups) { _discoveredGroups.clear() }
        mainHandler.post {
            onDiscoveredGroupsChanged?.invoke()
        }
    }

    fun getCurrentMemberList(): List<GroupMember> {
        val session = currentSession ?: return emptyList()
        if (session.isHost) {
            return hostMemberClients.values.map {
                GroupMember(it.memberId, it.nickname, it.isHost)
            }
        }
        return cachedMemberList
    }

    private var cachedMemberList: List<GroupMember> = emptyList()

    fun updateCachedMemberList(list: List<GroupMember>) {
        cachedMemberList = list
    }

    private fun onDiscoveredGroupUpdate() {
        // 更新 hostGroupInfo 成员数并触发刷新（可选）
    }

    // ==================== 工具方法 ====================

    private fun getWifiManager(): WifiManager {
        return context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val wifiManager = getWifiManager()
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
            val nis = java.net.NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) return host
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun sendTo(output: OutputStream?, type: TransferProtocol.PacketType, payload: Any) {
        if (output == null) return
        TransferProtocol.writePacket(output, type, payload)
    }

    private fun sendRawTo(output: OutputStream?, type: TransferProtocol.PacketType, payloadBytes: ByteArray) {
        if (output == null) return
        TransferProtocol.writeRawPacket(output, type, payloadBytes)
    }

    private fun extractInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun sanitizeName(name: String): String {
        return name.replace("/", "_").replace("\\", "_")
    }

    private fun removeHostClient(client: HostClient) {
        try { client.socket?.close() } catch (_: Exception) {}
        try { client.input?.close() } catch (_: Exception) {}
        try { client.output?.close() } catch (_: Exception) {}
        hostMemberClients.remove(client.memberId)
    }

    private data class HostClient(
        val socket: Socket?,
        val input: BufferedInputStream?,
        val output: BufferedOutputStream?,
        val memberId: String,
        val nickname: String,
        val isHost: Boolean,
        val password: String?
    )
}
