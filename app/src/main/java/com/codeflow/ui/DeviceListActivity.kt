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
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
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
            connectionManager.stopNetworkDiscovery()
            connectionManager.startBluetoothDiscovery()
            connectionManager.startBluetoothServer()
        } else {
            connectionManager.stopBluetoothDiscovery()
            connectionManager.startNetworkDiscovery()
            connectionManager.startNetworkServer()
        }
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun initiateConnection(device: Device) {
        binding.progressBar.visibility = View.VISIBLE
        when (device.connectionType) {
            com.codeflow.model.ConnectionType.BLUETOOTH -> connectionManager.connectViaBluetooth(device)
            com.codeflow.model.ConnectionType.WIFI -> connectionManager.connectViaNetwork(device)
        }
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
