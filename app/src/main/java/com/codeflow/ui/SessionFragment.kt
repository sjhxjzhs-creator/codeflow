package com.codeflow.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.codeflow.CodeFlowApp
import com.codeflow.R
import com.codeflow.databinding.DialogFormBinding
import com.codeflow.databinding.FragmentSessionBinding
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionFragment : Fragment() {

    private var _binding: FragmentSessionBinding? = null
    private val binding get() = _binding!!

    private lateinit var connectionManager: ConnectionManager
    private lateinit var groupManager: GroupManager
    private lateinit var prefs: SharedPreferences

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var deviceAdapter: DeviceAdapter
    private var savedFriends = mutableListOf<Device>()
    private val discoveredDeviceIds = mutableSetOf<String>()

    private enum class SubMode { FRIENDS, ROOMS }
    private var subMode = SubMode.ROOMS

    private val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CodeFlowApp
        connectionManager = app.connectionManager
        groupManager = app.groupManager
        prefs = requireContext().getSharedPreferences("bchat_prefs", Context.MODE_PRIVATE)

        loadSavedFriends()
        setupUI()
        observeState()
        showRooms()
    }

    private fun setupUI() {
        groupAdapter = GroupAdapter { group -> joinGroupByEntry(group) }
        deviceAdapter = DeviceAdapter { device ->
            when (device.connectionType) {
                ConnectionType.BLUETOOTH -> connectionManager.connectViaBluetooth(device)
                ConnectionType.WIFI -> connectionManager.connectViaNetwork(device)
            }
        }
        binding.rvList.layoutManager = LinearLayoutManager(requireContext())

        binding.btnCreateGroup.setOnClickListener { showCreateGroupDialog() }
        binding.btnJoinGroup.setOnClickListener { showJoinGroupDialog() }

        binding.toolbar.inflateMenu(R.menu.menu_session)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_toggle -> {
                    if (subMode == SubMode.ROOMS) showFriends() else showRooms()
                    true
                }
                else -> false
            }
        }
    }

    private fun loadSavedFriends() {
        val json = prefs.getString("saved_friends", null)
        if (json != null) {
            savedFriends = Gson().fromJson(json, object : TypeToken<List<Device>>() {}.type)
                ?: mutableListOf()
        }
    }

    private fun saveFriends() {
        prefs.edit().putString("saved_friends", Gson().toJson(savedFriends)).apply()
    }

    private fun showRooms() {
        subMode = SubMode.ROOMS
        binding.toolbar.title = "会话 · 群聊"
        binding.actionsLayout.visibility = View.VISIBLE
        binding.rvList.adapter = groupAdapter
        groupManager.startGroupDiscovery { refreshRooms() }
        refreshRooms()
    }

    private fun refreshRooms() {
        groupAdapter.submitList(groupManager.discoveredGroups)
        updateEmptyView(
            groupManager.discoveredGroups.isEmpty(),
            "暂无可用群聊\n好友发起的群聊会出现在这里"
        )
    }

    private fun showFriends() {
        subMode = SubMode.FRIENDS
        binding.toolbar.title = "会话 · 好友"
        binding.actionsLayout.visibility = View.GONE
        binding.rvList.adapter = deviceAdapter
        val onlineIds = discoveredDeviceIds
        val updated = savedFriends.map { f ->
            if (onlineIds.contains(f.id)) {
                if (f.status == DeviceStatus.ONLINE) f else f.copy(status = DeviceStatus.ONLINE)
            } else {
                if (f.status == DeviceStatus.OFFLINE) f else f.copy(status = DeviceStatus.OFFLINE)
            }
        }.toMutableList()
        savedFriends = updated
        deviceAdapter.submitList(savedFriends)
        updateEmptyView(savedFriends.isEmpty(), "暂无好友\n连接过的设备会自动保存到好友列表")
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            connectionManager.getBluetoothDevices().collectLatest { ds ->
                discoveredDeviceIds.addAll(ds.map { it.id })
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            connectionManager.getNetworkDevices().collectLatest { ds ->
                discoveredDeviceIds.addAll(ds.map { it.id })
            }
        }
    }

    private fun joinGroupByEntry(group: Group) {
        promptNickname { nickname ->
            if (group.hasPassword) {
                promptPassword { password -> doJoinGroup(group, nickname, password) }
            } else {
                doJoinGroup(group, nickname, null)
            }
        }
    }

    private fun doJoinGroup(group: Group, nickname: String, password: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                groupManager.joinGroup(
                    hostIp = group.hostIp, port = group.hostPort, groupId = group.id,
                    groupName = group.name, password = password, nickname = nickname
                )
            }
            if (result.isSuccess) {
                openGroupChat(groupManager.currentSession)
            } else {
                Toast.makeText(requireContext(), "加入失败：${errorMessage(result.exceptionOrNull())}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showFormDialog(
        title: String, iconRes: Int, confirmText: String,
        fields: List<Pair<String, Int>>, onConfirm: (List<String>) -> Boolean
    ) {
        val formView = layoutInflater.inflate(R.layout.dialog_form, null)
        val formBinding = DialogFormBinding.bind(formView)
        formBinding.ivIcon.setImageResource(iconRes)
        formBinding.tvTitle.text = title
        formBinding.btnConfirm.text = confirmText
        formBinding.btnCancel.text = "取消"

        val inputs = mutableListOf<EditText>()
        val pad = (12 * resources.displayMetrics.density).toInt()
        fields.forEachIndexed { index, (hint, inputType) ->
            val inputLayout = TextInputLayout(requireContext()).apply {
                this.hint = hint
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxStrokeColor(ContextCompat.getColor(requireContext(), R.color.primary))
            }
            val editText = TextInputEditText(requireContext()).apply {
                this.inputType = inputType
                setSingleLine(true)
            }
            inputLayout.addView(editText)
            inputs.add(editText)
            val lp = android.widget.LinearLayout.LayoutParams(match, wrap)
            if (index > 0) lp.topMargin = pad
            formBinding.inputContainer.addView(inputLayout, lp)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setView(formView)
            .setCancelable(false)
            .show()
            .apply {
                formBinding.btnCancel.setOnClickListener { dismiss() }
                formBinding.btnConfirm.setOnClickListener {
                    val values = inputs.map { it.text.toString() }
                    if (onConfirm(values)) dismiss()
                }
            }
    }

    private fun showCreateGroupDialog() {
        showFormDialog(
            title = "发起群聊", iconRes = R.drawable.ic_friends, confirmText = "创建",
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
                Toast.makeText(requireContext(), "请填写昵称和群名", Toast.LENGTH_SHORT).show()
                return@showFormDialog false
            }
            val result = groupManager.createGroup(groupName, password, nickname)
            if (result.isSuccess) {
                Toast.makeText(requireContext(), "群聊已创建", Toast.LENGTH_SHORT).show()
                openGroupChat(groupManager.currentSession)
                true
            } else {
                Toast.makeText(requireContext(), "创建失败：${errorMessage(result.exceptionOrNull())}", Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    private fun showJoinGroupDialog() {
        showFormDialog(
            title = "加入群聊", iconRes = R.drawable.ic_search, confirmText = "加入",
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
                Toast.makeText(requireContext(), "请填写昵称和地址", Toast.LENGTH_SHORT).show()
                return@showFormDialog false
            }
            val parts = address.split(":")
            if (parts.size < 2 || parts[0].isBlank() || parts[1].toIntOrNull() == null) {
                Toast.makeText(requireContext(), "地址格式错误，应为 IP:端口", Toast.LENGTH_SHORT).show()
                return@showFormDialog false
            }
            val ip = parts[0].trim()
            val port = parts[1].trim().toInt()
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    groupManager.joinGroup(
                        hostIp = ip, port = port, groupId = "manual",
                        groupName = "", password = password, nickname = nickname
                    )
                }
                if (result.isSuccess) {
                    openGroupChat(groupManager.currentSession)
                } else {
                    Toast.makeText(requireContext(), "加入失败：${errorMessage(result.exceptionOrNull())}", Toast.LENGTH_LONG).show()
                }
            }
            true
        }
    }

    private fun promptNickname(onResult: (String) -> Unit) {
        showFormDialog(
            title = "设置昵称", iconRes = R.drawable.ic_friends, confirmText = "确定",
            fields = listOf("请输入一次性昵称" to android.text.InputType.TYPE_CLASS_TEXT)
        ) { values ->
            val nickname = values[0].trim()
            if (nickname.isEmpty()) {
                Toast.makeText(requireContext(), "昵称不能为空", Toast.LENGTH_SHORT).show()
                false
            } else {
                onResult(nickname)
                true
            }
        }
    }

    private fun promptPassword(onResult: (String) -> Unit) {
        showFormDialog(
            title = "输入密码", iconRes = R.drawable.ic_friends, confirmText = "确定",
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
            Toast.makeText(requireContext(), "群会话创建失败", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(requireContext(), GroupChatActivity::class.java)
        intent.putExtra(GroupChatActivity.EXTRA_SESSION, Gson().toJson(session))
        startActivity(intent)
    }

    private fun updateEmptyView(isEmpty: Boolean, hint: String) {
        binding.tvEmptyHint.text = hint
        binding.tvEmptyHint.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvList.visibility = if (isEmpty) View.GONE else View.VISIBLE
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

    fun saveFriendFromTransfer(device: Device) {
        if (!savedFriends.any { it.id == device.id }) {
            savedFriends.add(device)
            saveFriends()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
