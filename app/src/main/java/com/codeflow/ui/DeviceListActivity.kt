package com.codeflow.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.content.SharedPreferences
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var connectionManager: ConnectionManager
    private lateinit var prefs: SharedPreferences
    private lateinit var deviceAdapter: DeviceAdapter
    
    private var isConnecting = false
    private var savedFriends = mutableListOf<Device>()
    
    // 当前模式
    private enum class Mode {
        BLUETOOTH, WIFI, FRIENDS, CHAT_ROOMS
    }
    
    private var currentMode = Mode.WIFI

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

        prefs = getSharedPreferences("bchat_prefs", Context.MODE_PRIVATE)
        loadSavedFriends()

        connectionManager = (application as CodeFlowApp).connectionManager
        
        setupDeviceInfo()
        setupUI()
        observeState()
        requestPermissions()
        
        // 默认显示 WiFi 模式
        setMode(Mode.WIFI)
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
                R.id.action_bluetooth -> {
                    setMode(Mode.BLUETOOTH)
                    true
                }
                R.id.action_wifi -> {
                    setMode(Mode.WIFI)
                    true
                }
                R.id.action_friends -> {
                    setMode(Mode.FRIENDS)
                    true
                }
                R.id.action_search -> {
                    setMode(Mode.CHAT_ROOMS)
                    true
                }
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
        
        // 好友操作按钮
        binding.btnCreateGroup.setOnClickListener {
            showCreateGroupDialog()
        }
        binding.btnJoinGroup.setOnClickListener {
            showJoinGroupDialog()
        }
    }

    private fun updateModeIcon(
        menuItemId: Int,
        isSelected: Boolean,
        selectedColor: Int,
        dimmedColor: Int
    ) {
        binding.toolbar.menu.findItem(menuItemId)?.icon?.mutate()?.colorFilter =
            PorterDuffColorFilter(if (isSelected) selectedColor else dimmedColor, PorterDuff.Mode.SRC_IN)
    }

    private fun loadSavedFriends() {
        val friendsJson = prefs.getString("saved_friends", null)
        if (friendsJson != null) {
            val type = object : TypeToken<List<Device>>() {}.type
            savedFriends = Gson().fromJson(friendsJson, type) ?: mutableListOf()
        }
    }
    
    private fun saveFriends() {
        val json = Gson().toJson(savedFriends)
        prefs.edit().putString("saved_friends", json).apply()
    }

    private fun setMode(mode: Mode) {
        currentMode = mode
        
        // 高亮当前模式图标
        val white = ContextCompat.getColor(this, android.R.color.white)
        val dimmed = (white and 0xFFFFFF) or (0x80 shl 24)
        updateModeIcon(R.id.action_bluetooth, mode == Mode.BLUETOOTH, white, dimmed)
        updateModeIcon(R.id.action_wifi, mode == Mode.WIFI, white, dimmed)
        updateModeIcon(R.id.action_friends, mode == Mode.FRIENDS, white, dimmed)
        updateModeIcon(R.id.action_search, mode == Mode.CHAT_ROOMS, white, dimmed)
        
        isConnecting = false
        
        // 更新 UI
        when (mode) {
            Mode.BLUETOOTH -> {
                binding.friendActionsLayout.visibility = View.GONE
                refreshDevices()
            }
            Mode.WIFI -> {
                binding.friendActionsLayout.visibility = View.GONE
                refreshDevices()
            }
            Mode.FRIENDS -> {
                binding.friendActionsLayout.visibility = View.VISIBLE
                showFriendsView()
            }
            Mode.CHAT_ROOMS -> {
                binding.friendActionsLayout.visibility = View.GONE
                showChatRoomsView()
            }
        }
    }

    private fun showFriendsView() {
        deviceAdapter.submitList(savedFriends)
        updateEmptyView(savedFriends.isEmpty(), "暂无好友\n连接过的设备会自动保存到好友列表")
    }
    
    private fun showChatRoomsView() {
        deviceAdapter.submitList(emptyList())
        updateEmptyView(true, "聊天室功能开发中")
    }

    private fun observeState() {
        lifecycleScope.launch {
            launch {
                connectionManager.getBluetoothDevices().collectLatest { devices ->
                    if (currentMode == Mode.BLUETOOTH) {
                        deviceAdapter.submitList(devices)
                        updateEmptyView(devices.isEmpty(), "未发现蓝牙设备")
                    }
                }
            }
            launch {
                connectionManager.getNetworkDevices().collectLatest { devices ->
                    if (currentMode == Mode.WIFI) {
                        deviceAdapter.submitList(devices)
                        updateEmptyView(devices.isEmpty(), "未发现局域网设备")
                    }
                }
            }
            launch {
                connectionManager.connectionState.collectLatest { state ->
                    when (state) {
                        ConnectionManager.ConnectionState.WAITING_FOR_REQUEST -> {
                            // 被动等待连接
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
        connectionManager.onFileCompleted = { _, _, _ -> /* handled in TransferActivity */ }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        if (currentMode == Mode.BLUETOOTH) {
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
        } else if (currentMode == Mode.WIFI) {
            connectionManager.stopBluetoothDiscovery()
            connectionManager.startNetworkDiscovery()
            connectionManager.startNetworkServer()
        }
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun initiateConnection(device: Device) {
        when (device.connectionType) {
            ConnectionType.BLUETOOTH -> connectionManager.connectViaBluetooth(device)
            ConnectionType.WIFI -> connectionManager.connectViaNetwork(device)
        }
    }
    
    private fun showCreateGroupDialog() {
        AlertDialog.Builder(this)
            .setTitle("群聊功能")
            .setMessage("群聊功能开发中，敬请期待！")
            .setPositiveButton("好的", null)
            .show()
    }
    
    private fun showJoinGroupDialog() {
        AlertDialog.Builder(this)
            .setTitle("群聊功能")
            .setMessage("群聊功能开发中，敬请期待！")
            .setPositiveButton("好的", null)
            .show()
    }
    
    // 连接成功后保存设备到好友
    private fun saveDeviceToFriendly(device: Device) {
        if (!savedFriends.any { it.id == device.id }) {
            savedFriends.add(device)
            saveFriends()
        }
    }

    private fun openTransferActivity() {
        val intent = Intent(this, TransferActivity::class.java)
        startActivity(intent)
    }

    private fun updateEmptyView(isEmpty: Boolean, hint: String? = null) {
        binding.tvEmptyHint.text = hint ?: getString(R.string.no_devices)
        binding.tvEmptyHint.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvDevices.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.progressBar.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.cleanup()
    }
}
