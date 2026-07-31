package com.codeflow.transfer

import com.codeflow.util.AppLog

import android.content.Context
import com.codeflow.CodeFlowApp
import com.codeflow.bluetooth.BluetoothDiscovery
import com.codeflow.model.*
import com.codeflow.network.NetworkDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.util.UUID

class ConnectionManager(context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val bluetoothDiscovery = BluetoothDiscovery(context)
    private val networkDiscovery = NetworkDiscovery(context)

    private var activeConnection: ConnectionType? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readJob: Job? = null

    private var deviceId: String = UUID.randomUUID().toString()
    private var deviceName: String = "Unknown"

    var onMessageReceived: ((Message) -> Unit)? = null
    var onFileInfoReceived: ((TransferProtocol.FileInfo) -> Unit)? = null
    var onFileCompleted: ((String, String, Long) -> Unit)? = null
    var onPeerDisconnected: (() -> Unit)? = null
    var onFileSendProgress: ((String, Int) -> Unit)? = null
    var onFileReceiveProgress: ((String, Int) -> Unit)? = null
    var onConnectionRequest: ((String, String) -> Unit)? = null

    enum class ConnectionState {
        DISCONNECTED,
        WAITING_FOR_REQUEST,
        CONNECTING,
        AWAITING_ACCEPT,
        CONNECTED
    }

    fun setDeviceInfo(id: String, name: String) {
        this.deviceId = id
        this.deviceName = name
        networkDiscovery.init(id, name)
    }

    fun getDeviceName(): String = deviceName

    fun getBluetoothDevices(): StateFlow<List<Device>> = bluetoothDiscovery.devices
    fun getNetworkDevices(): StateFlow<List<Device>> = networkDiscovery.devices

    fun getBluetoothDiscovery() = bluetoothDiscovery
    fun getNetworkDiscovery() = networkDiscovery

    fun startBluetoothDiscovery() = bluetoothDiscovery.startDiscovery()
    fun stopBluetoothDiscovery() = bluetoothDiscovery.stopDiscovery()

    fun startNetworkDiscovery() = networkDiscovery.startDiscovery()
    fun stopNetworkDiscovery() = networkDiscovery.stopDiscovery()

    fun startBluetoothServer() {
        AppLog.log("CONN", "蓝牙服务启动（等待连接请求）")
        _connectionState.value = ConnectionState.WAITING_FOR_REQUEST
        bluetoothDiscovery.startServer { socket ->
            setupBluetoothStreams(socket.inputStream, socket.outputStream)
            startReading(ConnectionType.BLUETOOTH)
        }
    }

    fun startNetworkServer() {
        AppLog.log("CONN", "局域网服务启动: 端口 ${CodeFlowApp.TRANSFER_PORT}")
        val nd = networkDiscovery
        nd.startServer { socket ->
            setupNetworkStreams(socket.getInputStream(), socket.getOutputStream())
            startReading(ConnectionType.WIFI)
        }
    }

    fun connectViaBluetooth(device: Device) {
        AppLog.log("CONN", "尝试蓝牙连接 ${device.bluetoothAddress}")
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothDiscovery.connectToDevice(device) { socket ->
            setupBluetoothStreams(socket.inputStream, socket.outputStream)
            activeConnection = ConnectionType.BLUETOOTH
            startReading(ConnectionType.BLUETOOTH)
            _connectionState.value = ConnectionState.AWAITING_ACCEPT
            sendConnectionRequest(device)
        }
    }

    fun connectViaNetwork(device: Device) {
        AppLog.log("CONN", "尝试局域网连接 ${device.ipAddress}:${device.port ?: CodeFlowApp.TRANSFER_PORT}")
        _connectionState.value = ConnectionState.CONNECTING
        networkDiscovery.connectToDevice(device) { socket ->
            setupNetworkStreams(socket.getInputStream(), socket.getOutputStream())
            activeConnection = ConnectionType.WIFI
            startReading(ConnectionType.WIFI)
            _connectionState.value = ConnectionState.AWAITING_ACCEPT
            sendConnectionRequest(device)
        }
    }

    fun acceptConnection() {
        AppLog.log("CONN", "接受连接请求")
        val prevState = activeConnection
        _connectionState.value = ConnectionState.CONNECTED
        activeConnection = prevState
        sendPacket(TransferProtocol.PacketType.CONNECTION_ACCEPT,
            mapOf("status" to "accepted", "deviceName" to deviceName))
    }

    fun rejectConnection() {
        AppLog.log("CONN", "拒绝连接请求")
        sendPacket(TransferProtocol.PacketType.CONNECTION_REJECT,
            mapOf("status" to "rejected"))
        disconnect()
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val message = Message(
            type = MessageType.TEXT,
            content = text,
            isFromMe = true,
            status = MessageStatus.SENT
        )
        onMessageReceived?.invoke(message)

        val textMsg = TransferProtocol.TextMessage(
            messageId = message.id,
            content = text,
            timestamp = message.timestamp
        )
        sendPacket(TransferProtocol.PacketType.TEXT_MESSAGE, textMsg)
    }

    fun sendLargeFile(fileInfo: TransferProtocol.FileInfo, fileInputStream: InputStream, fileSize: Long) {
        scope.launch {
            try {
                val os = outputStream ?: return@launch
                sendPacketSync(TransferProtocol.PacketType.FILE_INFO, fileInfo)

                val sizeBytes = ByteArray(8)
                var sz = fileSize
                for (i in 7 downTo 0) {
                    sizeBytes[i] = (sz and 0xFF).toByte()
                    sz = sz shr 8
                }
                os.write(sizeBytes)

                val buffer = ByteArray(65536)
                var totalSent = 0L
                var bytesRead: Int
                while (fileInputStream.read(buffer).also { bytesRead = it } > 0) {
                    os.write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                    val progress = if (fileSize > 0) ((totalSent * 100) / fileSize).toInt() else 0
                    withContext(Dispatchers.Main) {
                        onFileSendProgress?.invoke(fileInfo.messageId, progress)
                    }
                }
                os.flush()

                sendPacketSync(TransferProtocol.PacketType.FILE_COMPLETE,
                    mapOf("messageId" to fileInfo.messageId, "fileSize" to totalSent))
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                try { fileInputStream.close() } catch (_: IOException) {}
            }
        }
    }

    fun disconnect() {
        AppLog.log("CONN", "断开连接")
        readJob?.cancel()
        readJob = null
        try { outputStream?.close() } catch (_: IOException) {}
        try { inputStream?.close() } catch (_: IOException) {}
        outputStream = null
        inputStream = null
        bluetoothDiscovery.disconnect()
        networkDiscovery.disconnect()
        activeConnection = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun isServerRunning(): Boolean {
        return _connectionState.value == ConnectionState.WAITING_FOR_REQUEST
    }

    fun cleanup() {
        disconnect()
        bluetoothDiscovery.cleanup()
        networkDiscovery.cleanup()
        scope.cancel()
    }

    private fun setupBluetoothStreams(input: InputStream, output: OutputStream) {
        this.inputStream = BufferedInputStream(input)
        this.outputStream = BufferedOutputStream(output)
    }

    private fun setupNetworkStreams(input: InputStream, output: OutputStream) {
        this.inputStream = BufferedInputStream(input)
        this.outputStream = BufferedOutputStream(output)
    }

    private fun startReading(connectionType: ConnectionType) {
        readJob?.cancel()
        readJob = scope.launch {
            val input = inputStream ?: return@launch
            try {
                while (isActive) {
                    val result = TransferProtocol.readPacket(input)
                    if (result == null) {
                        if (!isActive) return@launch
                        throw IOException("Peer closed connection")
                    }
                    val (type, payload) = result

                    if (type == TransferProtocol.PacketType.FILE_INFO) {
                        handleFileReceive(payload)
                    } else {
                        handlePacket(type, payload)
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        onPeerDisconnected?.invoke()
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }
    }

    private suspend fun readFileDataLength(input: InputStream): Long {
        var length = 0L
        for (i in 0..7) {
            val b = input.read()
            if (b < 0) throw IOException("Unexpected EOF while reading file data length")
            length = (length shl 8) or (b.toLong() and 0xFF)
        }
        return length
    }

    private suspend fun handleFileReceive(payload: ByteArray) {
        try {
            val fileInfo: TransferProtocol.FileInfo =
                TransferProtocol.parsePayload(payload)

            withContext(Dispatchers.Main) {
                onFileInfoReceived?.invoke(fileInfo)
            }

            val input = inputStream ?: return
            val dataSize = readFileDataLength(input)

            val dir = File(CodeFlowApp.getAppContext().filesDir, "transfers")
            dir.mkdirs()

            var targetFile = File(dir, fileInfo.fileName)
            if (targetFile.exists()) {
                var counter = 1
                val name = fileInfo.fileName.substringBeforeLast('.')
                val ext = fileInfo.fileName.substringAfterLast('.', "")
                while (true) {
                    targetFile = if (ext.isNotEmpty())
                        File(dir, "${name}($counter).$ext")
                    else
                        File(dir, "${name}($counter)")
                    if (!targetFile.exists()) break
                    counter++
                }
            }

            targetFile.outputStream().use { fos ->
                val buffer = ByteArray(65536)
                var totalRead = 0L
                while (totalRead < dataSize) {
                    val remaining = (dataSize - totalRead).toInt().coerceAtMost(65536)
                    val bytesRead = input.read(buffer, 0, remaining)
                    if (bytesRead < 0) throw IOException("Unexpected EOF while reading file data")
                    fos.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val progress = if (dataSize > 0) ((totalRead * 100) / dataSize).toInt() else 0
                    withContext(Dispatchers.Main) {
                        onFileReceiveProgress?.invoke(fileInfo.messageId, progress)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onFileCompleted?.invoke(fileInfo.messageId, targetFile.absolutePath, dataSize)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handlePacket(type: TransferProtocol.PacketType, payload: ByteArray) {
        scope.launch(Dispatchers.Main) {
            try {
                when (type) {
                    TransferProtocol.PacketType.CONNECTION_REQUEST -> {
                        val request: TransferProtocol.ConnectionRequest =
                            TransferProtocol.parsePayload(payload)
                        AppLog.log(
                            "CONN",
                            "收到连接请求 from=${request.deviceName}(${request.connectionType})"
                        )
                        _connectionState.value = ConnectionState.WAITING_FOR_REQUEST
                        if (onConnectionRequest == null) {
                            AppLog.log("CONN", "警告: onConnectionRequest 为空，无法弹窗")
                        }
                        onConnectionRequest?.invoke(request.deviceName, request.connectionType)
                    }
                    TransferProtocol.PacketType.CONNECTION_ACCEPT -> {
                        AppLog.log("CONN", "收到连接接受，已连接")
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    TransferProtocol.PacketType.CONNECTION_REJECT -> {
                        AppLog.log("CONN", "收到连接拒绝")
                        disconnect()
                    }
                    TransferProtocol.PacketType.TEXT_MESSAGE -> {
                        val textMsg: TransferProtocol.TextMessage =
                            TransferProtocol.parsePayload(payload)
                        val message = Message(
                            id = textMsg.messageId,
                            type = MessageType.TEXT,
                            content = textMsg.content,
                            isFromMe = false,
                            status = MessageStatus.RECEIVED,
                            timestamp = textMsg.timestamp
                        )
                        onMessageReceived?.invoke(message)
                    }
                    TransferProtocol.PacketType.FILE_COMPLETE -> {
                    }
                    TransferProtocol.PacketType.DISCONNECT -> {
                        disconnect()
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendPacket(type: TransferProtocol.PacketType, payload: Any) {
        scope.launch(Dispatchers.IO) {
            try {
                outputStream?.let {
                    TransferProtocol.writePacket(it, type, payload)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun sendPacketSync(type: TransferProtocol.PacketType, payload: Any) {
        try {
            outputStream?.let {
                TransferProtocol.writePacket(it, type, payload)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun sendConnectionRequest(device: Device) {
        AppLog.log(
            "CONN",
            "已发送连接请求，等待对方接受 from=${device.name}"
        )
        val request = TransferProtocol.ConnectionRequest(
            deviceId = deviceId,
            deviceName = deviceName,
            connectionType = device.connectionType.name.lowercase()
        )
        sendPacket(TransferProtocol.PacketType.CONNECTION_REQUEST, request)
    }
}
