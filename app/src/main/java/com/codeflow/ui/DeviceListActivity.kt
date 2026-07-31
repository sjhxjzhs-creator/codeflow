package com.codeflow.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
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
import com.codeflow.model.Group
import com.codeflow.transfer.ConnectionManager
import com.codeflow.transfer.GroupManager
import com.codeflow.ui.adapter.DeviceAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var connectionManager: ConnectionManager
    private lateinit var groupManager: GroupManager
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var prefs: SharedPreferences
    
    private var isConnecting = false
    private var currentGroup: Group? = null
    private var savedFriends = mutableListOf<Device>()
    private var availableChatRooms = mutableListOf<ChatRoomInfo>()
    
    data class ChatRoomInfo(
        val groupId: String,
        val groupName: String,
        val hostName: String,
        val hostIp: String,
        val hostPort: Int,
        val memberCount: Int
    )

    private val isBluetoothMode: Boolean
        get() = binding.chipBluetooth.isChecked
    
    private val isWifiMode: Boolean
        get() = binding.chipWifi.isChecked
    
    private val isFriendsMode: Boolean
        get() = binding.chipFriends.isChecked
    
    private val isChatRoomsMode: Boolean
        get() = binding.chipChatRooms.isChecked

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
        groupManager = GroupManager(this)
        
        setupDeviceInfo()
        setupUI()
        setupChipListeners()
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
        
        // 默认显示 WiFi 设备
        binding.chipWifi.isChecked = true
    }

    private fun setupChipListeners() {
        binding.chipGroup.setOnCheckedStateChangeListener { _, _ ->
            isConnecting = false
            refreshDevices()
        }
        
        binding.chipFriends.setOnClickListener {
            if (!binding.chipFriends.isChecked) {
                binding.chipFriends.isChecked = true
            }
            isConnecting = false
            showFriendsView()
        }
        
        binding.chipChatRooms.setOnClickListener {
            if (!binding.chipChatRooms.isChecked) {
                binding.chipChatRooms.isChecked = true
            }
            isConnecting = false
            showChatRoomsView()
        }
    }

    private fun showFriendsView() {
        binding.chipBluetooth.isChecked = false
        binding.chipWifi.isChecked = false
        binding.chipChatRooms.isChecked = false
        
        deviceAdapter.submitList(savedFriends)
        updateEmptyView(savedFriends.isEmpty(), "暂无好友")
        
        // 显示操作按钮
        showFriendActionsDialog()
    }
    
    private fun showChatRoomsView() {
        binding.chipBluetooth.isChecked = false
        binding.chipWifi.isChecked = false
        binding.chipFriends.isChecked = false
        
        val chatRoomDevices = availableChatRooms.map { chatRoom ->
            Device(
                id = chatRoom.groupId,
                name = "${chatRoom.groupName} (${chatRoom.hostName})",
                connectionType = ConnectionType.WIFI,
                ipAddress = chatRoom.hostIp,
                port = chatRoom.hostPort,
                status = com.codeflow.model.DeviceStatus.ONLINE
            )
        }
        
        deviceAdapter.submitList(chatRoomDevices)
        updateEmptyView(chatRoomDevices.isEmpty(), "暂无聊天室")
    }

    private fun showFriendActionsDialog() {
        AlertDialog.Builder(this)
            .setTitle("好友操作")
            .setItems(arrayOf("发起群聊", "加入群聊")) { _, which ->
                when (which) {
                    0 -> showCreateGroupDialog()
                    1 -> showJoinGroupDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            launch {
                connectionManager.getBluetoothDevices().collectLatest { devices ->
                    if (isBluetoothMode) {
                        deviceAdapter.submitList(devices)
                        updateEmptyView(devices.isEmpty(), "未发现蓝牙设备")
                    }
                }
            }
            launch {
                connectionManager.getNetworkDevices().collectLatest { devices ->
                    if (isWifiMode) {
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
        } else if (isWifiMode) {
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
        // 创建群聊
        lifecycleScope.launch {
            currentGroup = groupManager.createGroup()
            currentGroup?.let { group ->
                val connectionInfo = groupManager.getGroupConnectionInfo() ?: return@let
                
                // 保存群聊信息到聊天室列表
                val ipMatch = try {
                    connectionInfo.substringAfter("\"ip\":").substringAfter("\"").substringBefore("\"")
                } catch (e: Exception) { "" }
                val portMatch = try {
                    connectionInfo.substringAfter("\"port\":").substringBefore(",").trim().toIntOrNull() ?: 53318
                } catch (e: Exception) { 53318 }
                
                availableChatRooms.add(ChatRoomInfo(
                    groupId = group.groupId,
                    groupName = group.groupName,
                    hostName = group.hostName,
                    hostIp = ipMatch,
                    hostPort = portMatch,
                    memberCount = group.memberCount
                ))
                saveChatRooms()
                
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("group_info", connectionInfo))
                
                AlertDialog.Builder(this@DeviceListActivity)
                    .setTitle("群聊已创建")
                    .setMessage("已复制群聊信息到剪贴板：\n\n$connectionInfo\n\n请将此信息分享给要邀请的好友。")
                    .setPositiveButton("分享") { _, _ ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, connectionInfo)
                        }
                        startActivity(Intent.createChooser(shareIntent, "分享群聊"))
                    }
                    .setNeutralButton("进入聊天", { _, _ ->
                        val intent = Intent(this@DeviceListActivity, GroupChatActivity::class.java).apply {
                            putExtra(GroupChatActivity.EXTRA_GROUP_ID, group.groupId)
                            putExtra(GroupChatActivity.EXTRA_IS_HOST, true)
                        }
                        startActivity(intent)
                    })
                    .setNegativeButton("取消", null)
                    .show()
            } ?: run {
                Toast.makeText(this@DeviceListActivity, "创建群聊失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showJoinGroupDialog() {
        val editTextView = android.widget.EditText(this).apply {
            hint = "IP:Port (例如：192.168.43.1:53318)"
            setTextSize(16f)
            setPadding(50, 40, 50, 10)
        }
        
        AlertDialog.Builder(this)
            .setTitle("加入群聊")
            .setMessage("请输入群主的 IP 地址和端口：")
            .setView(editTextView)
            .setPositiveButton("连接") { _, _ ->
                val input = editTextView.text?.toString()?.trim() ?: ""
                val parts = input.split(":")
                if (parts.size == 2) {
                    try {
                        val hostIp = parts[0].trim()
                        val hostPort = parts[1].trim().toInt()
                        val groupId = UUID.randomUUID().toString()
                        
                        val intent = Intent(this@DeviceListActivity, GroupChatActivity::class.java).apply {
                            putExtra(GroupChatActivity.EXTRA_GROUP_ID, groupId)
                            putExtra(GroupChatActivity.EXTRA_IS_HOST, false)
                            putExtra(GroupChatActivity.EXTRA_HOST_IP, hostIp)
                            putExtra(GroupChatActivity.EXTRA_HOST_PORT, hostPort)
                        }
                        startActivity(intent)
                    } catch (e: NumberFormatException) {
                        Toast.makeText(this, "端口号格式错误", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "请输入有效的 IP:Port", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    // 请求加入聊天室（群主会收到通知）
    private fun requestJoinChatRoom(chatRoom: ChatRoomInfo) {
        // TODO: 实现向群主发送加入请求的逻辑
        // 这里简化处理，直接连接
        val intent = Intent(this@DeviceListActivity, GroupChatActivity::class.java).apply {
            putExtra(GroupChatActivity.EXTRA_GROUP_ID, chatRoom.groupId)
            putExtra(GroupChatActivity.EXTRA_IS_HOST, false)
            putExtra(GroupChatActivity.EXTRA_HOST_IP, chatRoom.hostIp)
            putExtra(GroupChatActivity.EXTRA_HOST_PORT, chatRoom.hostPort)
        }
        startActivity(intent)
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
    
    private fun saveChatRooms() {
        val json = Gson().toJson(availableChatRooms)
        prefs.edit().putString("saved_chatrooms", json).apply()
    }
    
    private fun loadChatRooms() {
        val chatRoomsJson = prefs.getString("saved_chatrooms", null)
        if (chatRoomsJson != null) {
            val type = object : TypeToken<List<ChatRoomInfo>>() {}.type
            availableChatRooms = Gson().fromJson(chatRoomsJson, type) ?: mutableListOf()
        }
    }
    
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
        groupManager.cleanup()
    }
}
