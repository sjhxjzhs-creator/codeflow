package com.codeflow.network

import android.content.Context
import android.net.wifi.WifiManager
import com.codeflow.CodeFlowApp
import com.codeflow.model.ConnectionType
import com.codeflow.model.Device
import com.codeflow.transfer.TransferProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NetworkDiscovery(private val context: Context) {

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val discoveredDevices = ConcurrentHashMap<String, Device>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private var discoveryJob: Job? = null
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var connectedSocket: Socket? = null
    private var pendingRemoteSocket: Socket? = null
    private var isServerRunning = false

    private var deviceId: String = UUID.randomUUID().toString()
    private var deviceName: String = "Unknown"

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    fun init(deviceId: String, deviceName: String) {
        this.deviceId = deviceId
        this.deviceName = deviceName
    }

    fun getDeviceName() = deviceName
    fun getDeviceId() = deviceId

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        _isDiscovering.value = true

        discoveryJob = scope.launch {
            val announceJson = TransferProtocol.toJson(getDeviceInfo())
            val announceBytes = announceJson.toByteArray(Charsets.UTF_8)
            val buffer = ByteArray(1024)
            var multicastSocket: MulticastSocket? = null

            try {
                val lock = wifiManager.createMulticastLock("codeflow_disc")
                lock.acquire()

                // 单一 socket: 绑定发现端口, 加入组播组, 开启广播收发
                // 避免多 socket 绑定同一端口导致冲突
                multicastSocket = MulticastSocket(CodeFlowApp.DISCOVERY_PORT)
                multicastSocket.broadcast = true
                multicastSocket.soTimeout = 1000
                try {
                    multicastSocket.joinGroup(
                        InetAddress.getByName(CodeFlowApp.MULTICAST_ADDRESS)
                    )
                } catch (_: Exception) {}

                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val groupAddr = InetAddress.getByName(CodeFlowApp.MULTICAST_ADDRESS)
                val receivePacket = DatagramPacket(buffer, buffer.size)

                while (isActive && _isDiscovering.value) {
                    // 发送广播
                    try {
                        val broadcastPacket = DatagramPacket(
                            announceBytes, announceBytes.size,
                            broadcastAddr, CodeFlowApp.DISCOVERY_PORT
                        )
                        multicastSocket.send(broadcastPacket)
                    } catch (_: Exception) {}

                    // 同时通过组播发送
                    try {
                        val mcPacket = DatagramPacket(
                            announceBytes, announceBytes.size,
                            groupAddr, CodeFlowApp.DISCOVERY_PORT
                        )
                        multicastSocket.send(mcPacket)
                    } catch (_: Exception) {}

                    // 接收
                    try {
                        multicastSocket.receive(receivePacket)
                        processReceivedPacket(receivePacket)
                    } catch (_: SocketTimeoutException) {
                    } catch (_: IOException) {
                    }

                    delay(2000)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                try { multicastSocket?.close() } catch (_: Exception) {}
                try {
                    val lock = wifiManager.createMulticastLock("codeflow_disc")
                    lock.acquire()
                    lock.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun processReceivedPacket(packet: DatagramPacket) {
        try {
            val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val info = TransferProtocol.fromJson<TransferProtocol.DeviceInfo>(data)
            val remoteAddress = packet.address.hostAddress ?: return

            val device = Device(
                id = info.deviceId,
                name = info.deviceName,
                connectionType = ConnectionType.WIFI,
                ipAddress = remoteAddress,
                port = CodeFlowApp.TRANSFER_PORT
            )

            if (device.id != deviceId && !isLocalAddress(remoteAddress)) {
                discoveredDevices[device.id] = device
                _devices.value = discoveredDevices.values.toList()
            }
        } catch (_: Exception) {}
    }

    private fun isLocalAddress(address: String): Boolean {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    if (addrs.nextElement().hostAddress == address) return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun stopDiscovery() {
        _isDiscovering.value = false
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun startServer(onAccepted: (Socket) -> Unit) {
        if (isServerRunning) return
        isServerRunning = true
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(CodeFlowApp.TRANSFER_PORT, 5)
                while (isActive && isServerRunning) {
                    try {
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            connectedSocket = socket
                            scope.launch(Dispatchers.Main) {
                                onAccepted(socket)
                            }
                            break
                        }
                    } catch (e: IOException) {
                        if (!isServerRunning) break
                    }
                }
            } catch (e: IOException) {
                if (isServerRunning) e.printStackTrace()
            }
        }
    }

    fun acceptPendingConnection() {
        // 连接已经建立，只需标记
    }

    fun connectToDevice(device: Device, onConnected: (Socket) -> Unit) {
        scope.launch {
            try {
                val address = device.ipAddress ?: return@launch
                val port = device.port ?: CodeFlowApp.TRANSFER_PORT
                val socket = Socket()
                socket.connect(InetSocketAddress(address, port), 10000)
                connectedSocket = socket
                withContext(Dispatchers.Main) {
                    onConnected(socket)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun getConnectedInputStream() = connectedSocket?.getInputStream()
    fun getConnectedOutputStream() = connectedSocket?.getOutputStream()

    fun disconnect() {
        isServerRunning = false
        try {
            connectedSocket?.close()
        } catch (_: IOException) {}
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        connectedSocket = null
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    fun cleanup() {
        disconnect()
        stopDiscovery()
        discoveredDevices.clear()
        _devices.value = emptyList()
    }

    private fun getDeviceInfo(): TransferProtocol.DeviceInfo {
        return TransferProtocol.DeviceInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            platform = "android"
        )
    }

    fun getLocalIpAddress(): String? {
        try {
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) return host
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
}
