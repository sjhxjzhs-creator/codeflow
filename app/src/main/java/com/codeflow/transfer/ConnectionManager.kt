package com.codeflow.transfer

import android.content.Context
import com.codeflow.CodeFlowApp
import com.codeflow.bluetooth.BluetoothDiscovery
import com.codeflow.model.*
import com.codeflow.network.NetworkDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.net.Socket
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
    var onFileDataReady: ((InputStream, String, Long) -> Unit)? = null
    var onConnectionRequest: ((String, String) -> Unit)? = null

    enum class ConnectionState {
        DISCONNECTED,
        WAITING_FOR_REQUEST,
        CONNECTING,
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
        _connectionState.value = ConnectionState.WAITING_FOR_REQUEST
        bluetoothDiscovery.startServer { socket ->
            setupBluetoothStreams(socket.inputStream, socket.outputStream)
            startReading(ConnectionType.BLUETOOTH)
        }
    }

    fun startNetworkServer() {
        val nd = networkDiscovery
        nd.startServer { socket ->
            setupNetworkStreams(socket.getInputStream(), socket.getOutputStream())
            startReading(ConnectionType.WIFI)
        }
    }

    // 主动连接对方设备（发起方，不弹窗）
    fun connectViaBluetooth(device: Device) {
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothDiscovery.connectToDevice(device) { socket ->
            setupBluetoothStreams(socket.inputStream, socket.outputStream)
            activeConnection = ConnectionType.BLUETOOTH
            startReading(ConnectionType.BLUETOOTH)
            _connectionState.value = ConnectionState.CONNECTED
            sendConnectionRequest(device)
        }
    }

    fun connectViaNetwork(device: Device) {
        _connectionState.value = ConnectionState.CONNECTING
        networkDiscovery.connectToDevice(device) { socket ->
            setupNetworkStreams(socket.getInputStream(), socket.getOutputStream())
            activeConnection = ConnectionType.WIFI
            startReading(ConnectionType.WIFI)
            _connectionState.value = ConnectionState.CONNECTED
            sendConnectionRequest(device)
        }
    }

    fun acceptConnection() {
        val prevState = activeConnection
        _connectionState.value = ConnectionState.CONNECTED
        activeConnection = prevState
        sendPacket(TransferProtocol.PacketType.CONNECTION_ACCEPT,
            mapOf("status" to "accepted", "deviceName" to deviceName))
    }

    fun rejectConnection() {
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
        sendPacket(TransferProtocol.PacketType.FILE_INFO, fileInfo)

        scope.launch {
            try {
                outputStream?.let { os ->
                    val dataOutput = DataOutputStream(os)
                    dataOutput.writeLong(fileSize)
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (fileInputStream.read(buffer).also { bytesRead = it } > 0) {
                        dataOutput.write(buffer, 0, bytesRead)
                    }
                    dataOutput.flush()
                }
                sendPacket(TransferProtocol.PacketType.FILE_COMPLETE,
                    mapOf("messageId" to fileInfo.messageId))
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                try { fileInputStream.close() } catch (_: IOException) {}
            }
        }
    }

    fun disconnect() {
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
                        delay(100)
                        continue
                    }
                    val (type, payload) = result
                    handlePacket(type, payload)
                }
            } catch (e: IOException) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }
    }

    private fun handlePacket(type: TransferProtocol.PacketType, payload: ByteArray) {
        scope.launch(Dispatchers.Main) {
            try {
                when (type) {
                    TransferProtocol.PacketType.CONNECTION_REQUEST -> {
                        val request: TransferProtocol.ConnectionRequest =
                            TransferProtocol.parsePayload(payload)
                        _connectionState.value = ConnectionState.WAITING_FOR_REQUEST
                        // 通知UI，由UI弹出接受/拒绝对话框
                        onConnectionRequest?.invoke(request.deviceName, request.connectionType)
                    }
                    TransferProtocol.PacketType.CONNECTION_ACCEPT -> {
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    TransferProtocol.PacketType.CONNECTION_REJECT -> {
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
                    TransferProtocol.PacketType.FILE_INFO -> {
                        val fileInfo: TransferProtocol.FileInfo =
                            TransferProtocol.parsePayload(payload)
                        onFileInfoReceived?.invoke(fileInfo)
                        scope.launch(Dispatchers.IO) {
                            try {
                                val dataInput = DataInputStream(inputStream)
                                val dataSize = dataInput.readLong()
                                onFileDataReady?.invoke(inputStream!!, fileInfo.fileName, dataSize)
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }
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

    private fun sendConnectionRequest(device: Device) {
        val request = TransferProtocol.ConnectionRequest(
            deviceId = deviceId,
            deviceName = deviceName,
            connectionType = device.connectionType.name.lowercase()
        )
        sendPacket(TransferProtocol.PacketType.CONNECTION_REQUEST, request)
    }
}
