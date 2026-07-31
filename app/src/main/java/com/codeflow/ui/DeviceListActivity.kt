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
import com.codeflow.model.Group
import com.codeflow.model.GroupSession
import com.codeflow.transfer.ConnectionManager
import com.codeflow.transfer.GroupManager
import com.codeflow.ui.adapter.DeviceAdapter
import com.codeflow.ui.adapter.GroupAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var connectionManager: ConnectionManager
    private lateinit var groupManager: GroupManager
    private lateinit var prefs: SharedPreferences
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var groupAdapter: GroupAdapter
    
    private var isConnecting = false
    private var pendingDeviceForFriend: Device? = null
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
        groupManager = (application as CodeFlowApp).groupManager
        
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
        groupAdapter = GroupAdapter { group ->
            joinGroupByEntry(group)
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
                binding.rvDevices.adapter = deviceAdapter
                refreshDevices()
            }
            Mode.WIFI -> {
                binding.friendActionsLayout.visibility = View.GONE
                binding.rvDevices.adapter = deviceAdapter
                refreshDevices()
            }
            Mode.FRIENDS -> {
                binding.friendActionsLayout.visibility = View.VISIBLE
                binding.rvDevices.adapter = deviceAdapter
                showFriendsView()
            }
            Mode.CHAT_ROOMS -> {
                binding.friendActionsLayout.visibility = View.GONE
                showChatRoomsView()
            }
        }

        if (mode != Mode.CHAT_ROOMS) {
            groupManager.stopGroupDiscovery()
        }
    }

    private fun showFriendsView() {
        deviceAdapter.submitList(savedFriends)
        updateEmptyView(savedFriends.isEmpty(), "暂无好友\n连接过的设备会自动保存到好友列表")
    }
    
    private fun showChatRoomsView() {
        binding.rvDevices.adapter = groupAdapter
        groupAdapter.submitList(groupManager.discoveredGroups)
        updateEmptyView(groupManager.discoveredGroups.isEmpty(), "暂无可用群聊\n好友发起的群聊会出现在这里")

        // 启动群发现
        groupManager.startGroupDiscovery { _ ->
            refreshDiscoveredGroups()
        }
        // 首次也刷新一次
        refreshDiscoveredGroups()
    }

    private fun refreshDiscoveredGroups() {
        groupAdapter.submitList(groupManager.discoveredGroups)
        updateEmptyView(groupManager.discoveredGroups.isEmpty(), "暂无可用群聊\n好友发起的群聊会出现在这里")
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
                            pendingDeviceForFriend?.let { saveDeviceToFriendly(it) }
                            pendingDeviceForFriend = null
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
        pendingDeviceForFriend = device
        when (device.connectionType) {
            ConnectionType.BLUETOOTH -> connectionManager.connectViaBluetooth(device)
            ConnectionType.WIFI -> connectionManager.connectViaNetwork(device)
        }
    }
    
    // ==================== 群聊相关 ====================

    // 点击群列表条目进群
    private fun joinGroupByEntry(group: Group) {
        // 输入一次性昵称
        promptNickname { nickname ->
            if (group.hasPassword) {
                promptPassword { password ->
                    doJoinGroup(group, nickname, password)
                }
            } else {
                doJoinGroup(group, nickname, password = null)
            }
        }
    }

    private fun doJoinGroup(group: Group, nickname: String, password: String?) {
        val result = groupManager.joinGroup(
            hostIp = group.hostIp,
            port = group.hostPort,
            groupId = group.id,
            groupName = group.name,
            password = password,
            nickname = nickname
        )
        if (result.isSuccess) {
            openGroupChat(groupManager.currentSession)
        } else {
            Toast.makeText(this, "加入失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCreateGroupDialog() {
        val context = this
        val nicknameInput = android.widget.EditText(context).apply {
            hint = "你的昵称（如：小明）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val nameInput = android.widget.EditText(context).apply {
            hint = "群聊名称"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val passwordInput = android.widget.EditText(context).apply {
            hint = "入群密码（可选，留空则免密）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(nicknameInput)
            addView(nameInput, android.widget.LinearLayout.LayoutParams(match, wrap).apply {
                topMargin = pad
            })
            addView(passwordInput, android.widget.LinearLayout.LayoutParams(match, wrap).apply {
                topMargin = pad
            })
        }

        AlertDialog.Builder(this)
            .setTitle("发起群聊")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val nickname = nicknameInput.text.toString().trim()
                val groupName = nameInput.text.toString().trim()
                if (nickname.isEmpty() || groupName.isEmpty()) {
                    Toast.makeText(this, "请填写昵称和群名", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val password = passwordInput.text.toString().trim().takeIf { it.isNotEmpty() }
                val result = groupManager.createGroup(groupName, password, nickname)
                if (result.isSuccess) {
                    Toast.makeText(this, "群聊已创建，可在群聊页看到", Toast.LENGTH_SHORT).show()
                    openGroupChat(groupManager.currentSession)
                } else {
                    Toast.makeText(this, "创建失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showJoinGroupDialog() {
        val context = this
        val nicknameInput = android.widget.EditText(context).apply {
            hint = "你的昵称"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val addressInput = android.widget.EditText(context).apply {
            hint = "输入 IP:端口（如 192.168.1.5:53319）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val passwordInput = android.widget.EditText(context).apply {
            hint = "入群密码（如有）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(nicknameInput)
            addView(addressInput, android.widget.LinearLayout.LayoutParams(match, wrap).apply {
                topMargin = pad
            })
            addView(passwordInput, android.widget.LinearLayout.LayoutParams(match, wrap).apply {
                topMargin = pad
            })
        }

        AlertDialog.Builder(this)
            .setTitle("加入群聊")
            .setView(layout)
            .setPositiveButton("加入") { _, _ ->
                val nickname = nicknameInput.text.toString().trim()
                val address = addressInput.text.toString().trim()
                if (nickname.isEmpty() || address.isEmpty()) {
                    Toast.makeText(this, "请填写昵称和地址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val parts = address.split(":")
                if (parts.size < 2 || parts[0].isBlank() || parts[1].toIntOrNull() == null) {
                    Toast.makeText(this, "地址格式错误，应为 IP:端口", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ip = parts[0].trim()
                val port = parts[1].trim().toInt()
                val password = passwordInput.text.toString().trim().takeIf { it.isNotEmpty() }

                // 直接通过 IP:Port 加入，groupId 未知，用占位
                val group = Group(
                    id = "manual", name = "", hostName = "",
                    hostIp = ip, hostPort = port,
                    hasPassword = !password.isNullOrEmpty()
                )
                val result = groupManager.joinGroup(
                    hostIp = ip, port = port, groupId = "manual",
                    groupName = "", password = password, nickname = nickname
                )
                if (result.isSuccess) {
                    openGroupChat(groupManager.currentSession)
                } else {
                    Toast.makeText(this, "加入失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptNickname(onResult: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            hint = "请输入一次性昵称"
        }
        AlertDialog.Builder(this)
            .setTitle("设置昵称")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val nickname = input.text.toString().trim()
                if (nickname.isEmpty()) {
                    Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    onResult(nickname)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptPassword(onResult: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            hint = "请输入入群密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("输入密码")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                onResult(input.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openGroupChat(session: GroupSession?) {
        if (session == null) {
            Toast.makeText(this, "群会话创建失败", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, GroupChatActivity::class.java)
        intent.putExtra(GroupChatActivity.EXTRA_SESSION, Gson().toJson(session))
        startActivity(intent)
    }

    private val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

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
