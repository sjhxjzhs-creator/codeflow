package com.codeflow.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.codeflow.CodeFlowApp
import com.codeflow.model.ConnectionType
import com.codeflow.model.Device
import com.codeflow.util.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothDiscovery(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val discoveredDevices = ConcurrentHashMap<String, Device>()

    private var serverSocket: BluetoothServerSocket? = null
    private var connectedSocket: BluetoothSocket? = null
    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device?.let { addDiscoveredDevice(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isDiscovering.value = false
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addDiscoveredDevice(bluetoothDevice: BluetoothDevice) {
        val name = bluetoothDevice.name ?: bluetoothDevice.address
        val address = bluetoothDevice.address
        val device = Device(
            id = "bt_$address",
            name = name,
            connectionType = ConnectionType.BLUETOOTH,
            bluetoothAddress = address
        )
        discoveredDevices[device.id] = device
        _devices.value = discoveredDevices.values.toList()
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (bluetoothAdapter == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) return
        }

        discoveredDevices.clear()
        _devices.value = emptyList()
        _isDiscovering.value = true

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)

        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        bluetoothAdapter.startDiscovery()
    }

    fun stopDiscovery() {
        _isDiscovering.value = false
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // receiver not registered
        }
        @SuppressLint("MissingPermission")
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter.cancelDiscovery()
        }
    }

    fun addSelfDevice() {
        bluetoothAdapter?.let {
            @SuppressLint("MissingPermission")
            val selfDevice = Device(
                id = "self_bt_${it.address}",
                name = "${it.name} (本机)",
                connectionType = ConnectionType.BLUETOOTH,
                bluetoothAddress = it.address,
                ipAddress = null
            )
            discoveredDevices[selfDevice.id] = selfDevice
            _devices.value = discoveredDevices.values.toList()
        }
    }

    fun startServer(onSocketAccepted: (BluetoothSocket) -> Unit) {
        scope.launch {
            try {
                @SuppressLint("MissingPermission")
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
                    CodeFlowApp.SERVICE_NAME,
                    UUID.fromString(CodeFlowApp.SERVICE_UUID)
                )
                AppLog.log("BT", "蓝牙服务已监听")

                while (isActive) {
                    val socket = serverSocket?.accept() ?: continue
                    AppLog.log("BT", "接受到蓝牙连接")
                    // 交给上层建立流并读取，继续等待后续连接
                    scope.launch {
                        onSocketAccepted(socket)
                    }
                    connectedSocket = socket
                }
            } catch (e: IOException) {
                AppLog.log("BT", "蓝牙服务异常: ${e.javaClass.simpleName} ${e.message}")
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: Device, onConnected: (BluetoothSocket) -> Unit) {
        scope.launch {
            try {
                val btDevice = bluetoothAdapter?.getRemoteDevice(device.bluetoothAddress)
                val socket = btDevice?.createRfcommSocketToServiceRecord(
                    UUID.fromString(CodeFlowApp.SERVICE_UUID)
                )
                bluetoothAdapter?.cancelDiscovery()
                socket?.connect()
                AppLog.log("BT", "蓝牙连接成功 ${device.bluetoothAddress}")
                socket?.let {
                    connectedSocket = it
                    withContext(Dispatchers.Main) {
                        onConnected(it)
                    }
                }
            } catch (e: IOException) {
                AppLog.log("BT", "蓝牙连接失败 ${device.bluetoothAddress}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun getConnectedInputStream() = connectedSocket?.inputStream
    fun getConnectedOutputStream() = connectedSocket?.outputStream

    fun disconnect() {
        scope.launch {
            try {
                connectedSocket?.close()
                serverSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            connectedSocket = null
            serverSocket = null
        }
    }

    fun cleanup() {
        disconnect()
        stopDiscovery()
        discoveredDevices.clear()
        _devices.value = emptyList()
    }

    fun isAvailable(): Boolean = bluetoothAdapter != null

    @SuppressLint("MissingPermission")
    fun isEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun getDeviceName(): String = bluetoothAdapter?.name ?: "Unknown"
}
