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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.codeflow.CodeFlowApp
import com.codeflow.R
import com.codeflow.databinding.ActivityDeviceListBinding
import com.codeflow.databinding.DialogConnectionRequestBinding
import com.codeflow.databinding.DialogFormBinding
import com.codeflow.model.ConnectionType
import com.codeflow.model.Device
import com.codeflow.model.DeviceStatus
import com.codeflow.model.Group
import com.codeflow.model.GroupSession
import com.codeflow.transfer.ConnectionManager
import com.codeflow.transfer.GroupManager
import com.codeflow.ui.adapter.DeviceAdapter
import com.codeflow.ui.adapter.GroupAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val discoveredDeviceIds = mutableSetOf<String>()
    
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
        // 空白处点击：刷新 / 停止（仅蓝牙·局域网页生效）
        binding.rvDevices.setOnClickListener { handleBlankRefresh() }
        binding.tvEmptyHint.setOnClickListener { handleBlankRefresh() }
        
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
        refreshFriendsStatus()
        updateEmptyView(savedFriends.isEmpty(), "暂无好友\n连接过的设备会自动保存到好友列表")
    }

    private fun refreshFriendsStatus() {
        if (currentMode != Mode.FRIENDS) return
        val onlineIds = discoveredDeviceIds
        val updated = savedFriends.map { f ->
            val shouldBeOnline = onlineIds.contains(f.id)
            if (shouldBeOnline) {
                if (f.status == DeviceStatus.ONLINE) f else f.copy(status = DeviceStatus.ONLINE)
            } else {
                if (f.status == DeviceStatus.OFFLINE) f else f.copy(status = DeviceStatus.OFFLINE)
            }
        }.toMutableList()
        savedFriends = updated
        deviceAdapter.submitList(savedFriends)
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
                observeDiscoveryState()
            }
            launch {
                connectionManager.getBluetoothDevices().collectLatest { devices ->
                    discoveredDeviceIds.addAll(devices.map { it.id })
                    if (currentMode == Mode.BLUETOOTH) {
                        deviceAdapter.submitList(devices)
                        updateEmptyView(devices.isEmpty(), "未发现蓝牙设备")
                    } else if (currentMode == Mode.FRIENDS) {
                        refreshFriendsStatus()
                    }
                }
            }
            launch {
                connectionManager.getNetworkDevices().collectLatest { devices ->
                    discoveredDeviceIds.addAll(devices.map { it.id })
                    if (currentMode == Mode.WIFI) {
                        deviceAdapter.submitList(devices)
                        updateEmptyView(devices.isEmpty(), "未发现局域网设备")
                    } else if (currentMode == Mode.FRIENDS) {
                        refreshFriendsStatus()
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
                        ConnectionManager.ConnectionState.AWAITING_ACCEPT -> {
                            // 已连接到对方，等待对方接受
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
                val dialogView = layoutInflater.inflate(
                    R.layout.dialog_connection_request, null
                )
                val viewBinding = DialogConnectionRequestBinding.bind(dialogView)
                viewBinding.tvMessage.text =
                    getString(R.string.connection_request_msg, remoteName)

                MaterialAlertDialogBuilder(this)
                    .setView(dialogView)
                    .setCancelable(false)
                    .show()
                    .apply {
                        viewBinding.btnReject.setOnClickListener {
                            connectionManager.rejectConnection()
                            isConnecting = false
                            dismiss()
                        }
                        viewBinding.btnAccept.setOnClickListener {
                            connectionManager.acceptConnection()
                            dismiss()
                        }
                    }
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

    // 空白处点击：正在发现则停止，否则重新刷新（仅蓝牙/局域网页生效）
    private fun handleBlankRefresh() {
        if (currentMode != Mode.BLUETOOTH && currentMode != Mode.WIFI) return
        val discovering = when (currentMode) {
            Mode.BLUETOOTH -> connectionManager.getBluetoothDiscovery().isDiscovering.value
            else -> connectionManager.getNetworkDiscovery().isDiscovering.value
        }
        if (discovering) {
            when (currentMode) {
                Mode.BLUETOOTH -> connectionManager.stopBluetoothDiscovery()
                else -> connectionManager.stopNetworkDiscovery()
            }
            binding.progressBar.visibility = View.GONE
        } else {
            refreshDevices()
        }
    }

    private suspend fun observeDiscoveryState() {
        coroutineScope {
            launch {
                connectionManager.getBluetoothDiscovery().isDiscovering.collectLatest { _ ->
                    if (currentMode == Mode.BLUETOOTH) syncDiscoveryUi()
                }
            }
            launch {
                connectionManager.getNetworkDiscovery().isDiscovering.collectLatest { _ ->
                    if (currentMode == Mode.WIFI) syncDiscoveryUi()
                }
            }
        }
    }

    private fun syncDiscoveryUi() {
        val discovering = when (currentMode) {
            Mode.BLUETOOTH -> connectionManager.getBluetoothDiscovery().isDiscovering.value
            else -> connectionManager.getNetworkDiscovery().isDiscovering.value
        }
        binding.progressBar.visibility = if (discovering) View.VISIBLE else View.GONE
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
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                groupManager.joinGroup(
                    hostIp = group.hostIp,
                    port = group.hostPort,
                    groupId = group.id,
                    groupName = group.name,
                    password = password,
                    nickname = nickname
                )
            }
            if (result.isSuccess) {
                openGroupChat(groupManager.currentSession)
            } else {
                Toast.makeText(this@DeviceListActivity, "加入失败：${errorMessage(result.exceptionOrNull())}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showFormDialog(
        title: String,
        iconRes: Int,
        confirmText: String,
        fields: List<Pair<String, Int>>,
        onConfirm: (List<String>) -> Boolean
    ) {
        val formView = layoutInflater.inflate(R.layout.dialog_form, null)
        val formBinding = DialogFormBinding.bind(formView)
        formBinding.ivIcon.setImageResource(iconRes)
        formBinding.tvTitle.text = title
        formBinding.btnConfirm.text = confirmText
        formBinding.btnCancel.text = "取消"

        val inputs = mutableListOf<android.widget.EditText>()
        val pad = (12 * resources.displayMetrics.density).toInt()
        fields.forEachIndexed { index, (hint, inputType) ->
            val inputLayout = TextInputLayout(this).apply {
                this.hint = hint
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxStrokeColor(ContextCompat.getColor(this@DeviceListActivity, R.color.primary))
            }
            val editText = TextInputEditText(this).apply {
                this.inputType = inputType
                setSingleLine(true)
            }
            inputLayout.addView(editText)
            inputs.add(editText)
            val lp = android.widget.LinearLayout.LayoutParams(match, wrap)
            if (index > 0) lp.topMargin = pad
            formBinding.inputContainer.addView(inputLayout, lp)
        }

        MaterialAlertDialogBuilder(this)
            .setView(formView)
            .setCancelable(false)
            .show()
            .apply {
                formBinding.btnCancel.setOnClickListener { dismiss() }
                formBinding.btnConfirm.setOnClickListener {
                    val values = inputs.map { it.text.toString() }
                    if (onConfirm(values)) {
                        dismiss()
                    }
                }
            }
    }

    private fun showCreateGroupDialog() {
        showFormDialog(
            title = "发起群聊",
            iconRes = R.drawable.ic_friends,
            confirmText = "创建",
            fields = listOf(
                "你的昵称（如：小明）" to android.text.InputType.TYPE_CLASS_TEXT,
                "群聊名称" to android.text.InputType.TYPE_CLASS_TEXT,
                "入群密码（可选，留空则免密）" to
                    (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            )
        ) { values ->
            val nickname = values[0].trim()
            val groupName = values[1].trim()
            val password = values[2].trim().takeIf { it.isNotEmpty() }
            if (nickname.isEmpty() || groupName.isEmpty()) {
                Toast.makeText(this, "请填写昵称和群名", Toast.LENGTH_SHORT).show()
                return@showFormDialog false
            }
            val result = groupManager.createGroup(groupName, password, nickname)
            if (result.isSuccess) {
                Toast.makeText(this, "群聊已创建，可在群聊页看到", Toast.LENGTH_SHORT).show()
                openGroupChat(groupManager.currentSession)
                true
            } else {
                Toast.makeText(this, "创建失败：${errorMessage(result.exceptionOrNull())}", Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    private fun showJoinGroupDialog() {
        showFormDialog(
            title = "加入群聊",
            iconRes = R.drawable.ic_search,
            confirmText = "加入",
            fields = listOf(
                "你的昵称" to android.text.InputType.TYPE_CLASS_TEXT,
                "输入 IP:端口（如 192.168.1.5:53319）" to android.text.InputType.TYPE_CLASS_TEXT,
                "入群密码（如有）" to
                    (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            )
        ) { values ->
            val nickname = values[0].trim()
            val address = values[1].trim()
            val password = values[2].trim().takeIf { it.isNotEmpty() }
            if (nickname.isEmpty() || address.isEmpty()) {
                Toast.makeText(this, "请填写昵称和地址", Toast.LENGTH_SHORT).show()
                return@showFormDialog false
            }
            val parts = address.split(":")
            if (parts.size < 2 || parts[0].isBlank() || parts[1].toIntOrNull() == null) {
                Toast.makeText(this, "地址格式错误，应为 IP:端口", Toast.LENGTH_SHORT).show()
                return@showFormDialog false
            }
            val ip = parts[0].trim()
            val port = parts[1].trim().toInt()
            val group = Group(
                id = "manual", name = "", hostName = "",
                hostIp = ip, hostPort = port,
                hasPassword = !password.isNullOrEmpty()
            )
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    groupManager.joinGroup(
                        hostIp = ip, port = port, groupId = "manual",
                        groupName = "", password = password, nickname = nickname
                    )
                }
                if (result.isSuccess) {
                    openGroupChat(groupManager.currentSession)
                } else {
                    Toast.makeText(this@DeviceListActivity, "加入失败：${errorMessage(result.exceptionOrNull())}", Toast.LENGTH_LONG).show()
                }
            }
            true
        }
    }

    private fun promptNickname(onResult: (String) -> Unit) {
        showFormDialog(
            title = "设置昵称",
            iconRes = R.drawable.ic_friends,
            confirmText = "确定",
            fields = listOf("请输入一次性昵称" to android.text.InputType.TYPE_CLASS_TEXT)
        ) { values ->
            val nickname = values[0].trim()
            if (nickname.isEmpty()) {
                Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show()
                false
            } else {
                onResult(nickname)
                true
            }
        }
    }

    private fun promptPassword(onResult: (String) -> Unit) {
        showFormDialog(
            title = "输入密码",
            iconRes = R.drawable.ic_friends,
            confirmText = "确定",
            fields = listOf(
                "请输入入群密码" to
                    (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            )
        ) { values ->
            onResult(values[0])
            true
        }
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

    private fun errorMessage(t: Throwable?): String {
        t ?: return "未知错误"
        val msg = t.message
        return if (msg.isNullOrBlank() || msg == "null") {
            (t.cause?.message?.takeIf { !it.isNullOrBlank() && it != "null" })
                ?: t.javaClass.simpleName
        } else {
            msg
        }
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
