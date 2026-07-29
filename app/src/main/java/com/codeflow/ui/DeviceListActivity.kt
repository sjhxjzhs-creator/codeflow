package com.codeflow.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.codeflow.CodeFlowApp
import com.codeflow.R
import com.codeflow.databinding.ActivityDeviceListBinding
import com.codeflow.model.ConnectionType
import com.codeflow.model.Device
import com.codeflow.transfer.ConnectionManager
import com.codeflow.ui.adapter.DeviceAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var connectionManager: ConnectionManager
    private lateinit var deviceAdapter: DeviceAdapter
    private var isConnecting = false

    private val isBluetoothMode: Boolean
        get() = binding.chipBluetooth.isChecked

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            refreshDevices()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) {
            refreshDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectionManager = (application as CodeFlowApp).connectionManager
        setupDeviceInfo()
        setupUI()
        observeState()
        requestPermissions()
    }

    private fun setupDeviceInfo() {
        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )
        val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { BluetoothAdapter.getDefaultAdapter()?.name } catch (e: SecurityException) { null }
        } else {
            @Suppress("DEPRECATION")
            try { BluetoothAdapter.getDefaultAdapter()?.name } catch (e: SecurityException) { null }
        } ?: (Build.MODEL ?: "Android")
        connectionManager.setDeviceInfo(deviceId, deviceName)
    }

    private fun setupUI() {
        binding.toolbar.inflateMenu(R.menu.toolbar_menu)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_become_server -> {
                    startServerMode()
                    true
                }
                else -> false
            }
        }

        deviceAdapter = DeviceAdapter { device ->
            if (!isConnecting) {
                isConnecting = true
                initiateConnection(device)
            }
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@DeviceListActivity)
            adapter = deviceAdapter
        }

        binding.chipGroup.setOnCheckedStateChangeListener { _, _ ->
            isConnecting = false
            refreshDevices()
        }

        binding.btnRefresh.setOnClickListener {
            isConnecting = false
            refreshDevices()
        }

        binding.btnConnect.setOnClickListener { manualConnect() }

        binding.etIpAddress.setOnEditorActionListener { _, _, _ ->
            manualConnect()
            true
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            launch {
                connectionManager.getBluetoothDevices().collectLatest { devices ->
                    if (isBluetoothMode) {
                        deviceAdapter.submitList(devices)
                        updateEmptyView(devices.isEmpty())
                    }
                }
            }
            launch {
                connectionManager.getNetworkDevices().collectLatest { devices ->
                    if (!isBluetoothMode) {
                        deviceAdapter.submitList(devices)
                        updateEmptyView(devices.isEmpty())
                    }
                }
            }
            launch {
                connectionManager.connectionState.collectLatest { state ->
                    when (state) {
                        ConnectionManager.ConnectionState.WAITING_FOR_REQUEST -> {
                            // 如果是被动等待连接，不停显示progress
                            // 主动连接时也会经过这个状态，但很快被connected覆盖
                        }
                        ConnectionManager.ConnectionState.CONNECTING -> {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        ConnectionManager.ConnectionState.CONNECTED -> {
                            binding.progressBar.visibility = View.GONE
                            isConnecting = false
                            openTransferActivity()
                        }
                        ConnectionManager.ConnectionState.DISCONNECTED -> {
                            binding.progressBar.visibility = View.GONE
                            isConnecting = false
                        }
                    }
                }
            }
        }

        // 收到远端连接请求时弹窗
        connectionManager.onConnectionRequest = { remoteName, _ ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.connection_request)
                    .setMessage(getString(R.string.connection_request_msg, remoteName))
                    .setCancelable(false)
                    .setPositiveButton(R.string.accept) { _, _ ->
                        connectionManager.acceptConnection()
                    }
                    .setNegativeButton(R.string.reject) { _, _ ->
                        connectionManager.rejectConnection()
                        isConnecting = false
                    }
                    .show()
            }
        }

        connectionManager.onMessageReceived = { /* handled in TransferActivity */ }
        connectionManager.onFileInfoReceived = { /* handled in TransferActivity */ }
        connectionManager.onFileDataReady = { _, _, _ -> /* handled in TransferActivity */ }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ))
        } else {
            @Suppress("DEPRECATION")
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            refreshDevices()
        }
    }

    private fun refreshDevices() {
        binding.tvLocalInfo.visibility = View.GONE

        if (isBluetoothMode) {
            val bt = connectionManager.getBluetoothDiscovery()
            if (!bt.isAvailable()) {
                Toast.makeText(this, "该设备不支持蓝牙", Toast.LENGTH_SHORT).show()
                return
            }
            if (!bt.isEnabled()) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(intent)
                return
            }
            val name = connectionManager.getDeviceName()
            binding.tvLocalInfo.text = "本机: $name (蓝牙)"
            binding.tvLocalInfo.visibility = View.VISIBLE
            connectionManager.stopNetworkDiscovery()
            connectionManager.startBluetoothDiscovery()
            // 自动启动蓝牙服务器
            connectionManager.startBluetoothServer()
        } else {
            connectionManager.stopBluetoothDiscovery()
            connectionManager.startNetworkDiscovery()
            // 自动启动网络服务器
            connectionManager.startNetworkServer()

            val ip = connectionManager.getNetworkDiscovery().getLocalIpAddress()
            val port = CodeFlowApp.TRANSFER_PORT
            if (ip != null) {
                binding.tvLocalInfo.text = "本机IP: $ip:$port"
            } else {
                binding.tvLocalInfo.text = "本机端口: $port (IP获取中...)"
            }
            binding.tvLocalInfo.visibility = View.VISIBLE
        }
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun startServerMode() {
        if (isBluetoothMode) {
            connectionManager.startBluetoothServer()
            Toast.makeText(this, R.string.waiting_connection, Toast.LENGTH_LONG).show()
        } else {
            connectionManager.startNetworkServer()
            Toast.makeText(this, R.string.waiting_connection, Toast.LENGTH_LONG).show()
        }
    }

    // 主动连接对方（不弹窗，请求由对方处理）
    private fun initiateConnection(device: Device) {
        binding.progressBar.visibility = View.VISIBLE
        when (device.connectionType) {
            ConnectionType.BLUETOOTH -> connectionManager.connectViaBluetooth(device)
            ConnectionType.WIFI -> connectionManager.connectViaNetwork(device)
        }
    }

    private fun manualConnect() {
        val input = binding.etIpAddress.text?.toString()?.trim() ?: return
        if (input.isEmpty() || isConnecting) return
        isConnecting = true

        val parts = input.split(":")
        val ip = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: CodeFlowApp.TRANSFER_PORT
            else CodeFlowApp.TRANSFER_PORT

        val device = Device(
            id = "manual_${ip}_${port}",
            name = ip,
            connectionType = ConnectionType.WIFI,
            ipAddress = ip,
            port = port
        )

        connectionManager.connectViaNetwork(device)
    }

    private fun openTransferActivity() {
        val intent = Intent(this, TransferActivity::class.java)
        startActivity(intent)
    }

    private fun updateEmptyView(isEmpty: Boolean) {
        binding.tvEmptyHint.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvDevices.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.progressBar.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不清理连接，保持连接状态给 TransferActivity
    }
}
