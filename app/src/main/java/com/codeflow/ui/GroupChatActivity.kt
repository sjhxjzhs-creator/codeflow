package com.codeflow.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.codeflow.model.Message
import com.codeflow.R
import com.codeflow.databinding.ActivityGroupChatBinding
import com.codeflow.transfer.GroupManager
import com.codeflow.ui.adapter.MessageAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class GroupChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupChatBinding
    private lateinit var groupManager: GroupManager
    private lateinit var messageAdapter: MessageAdapter
    
    private val messages = mutableListOf<Message>()
    private var groupId: String = ""
    private var myDeviceId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: run {
            Toast.makeText(this, "群组信息无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        myDeviceId = UUID.randomUUID().toString()

        groupManager = GroupManager(this)
        setupAdapter()
        setupToolbar()
        setupSendButton()
        setupObservers()
        
        // 如果不是 Host，则尝试连接
        val isHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)
        if (!isHost) {
            val hostIp = intent.getStringExtra(EXTRA_HOST_IP)
            val hostPort = intent.getIntExtra(EXTRA_HOST_PORT, 0)
            if (hostIp != null && hostPort > 0) {
                groupManager.joinGroup(hostIp, hostPort, groupId)
            }
        }
    }

    private fun setupAdapter() {
        messageAdapter = MessageAdapter { message ->
            // 群聊暂不支持文件点击
        }
        binding.recyclerViewMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerViewMessages.adapter = messageAdapter
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = "Bchat 群聊"
    }

    private fun setupSendButton() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                groupManager.sendGroupMessage(text)
                binding.etMessage.text?.clear()
            }
        }
    }

    private fun setupObservers() {
        // 监听群消息
        groupManager.onGroupMessageReceived = { message ->
            messages.add(message)
            messageAdapter.notifyItemInserted(messages.size - 1)
            binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
        }
        
        // 监听系统消息
        groupManager.onGroupSystemMessage = { text ->
            val systemMessage = Message(
                type = com.codeflow.model.MessageType.SYSTEM,
                content = text,
                isFromMe = false
            )
            messages.add(systemMessage)
            messageAdapter.notifyItemInserted(messages.size - 1)
            binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
        }
        
        // 监听群组状态
        lifecycleScope.launch {
            groupManager.groupState.collectLatest { state ->
                when (state) {
                    GroupManager.GroupState.CONNECTED_HOST -> {
                        binding.toolbar.title = "Bchat 群聊 (群主)"
                    }
                    GroupManager.GroupState.CONNECTED_CLIENT -> {
                        binding.toolbar.title = "Bchat 群聊"
                    }
                    GroupManager.GroupState.DISCONNECTED_ERROR -> {
                        Toast.makeText(
                            this@GroupChatActivity,
                            "群聊已断开",
                        Toast.LENGTH_SHORT
                    ).show()
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun updateMemberCount(count: Int) {
        binding.toolbar.subtitle = "$count 人在线"
    }

    override fun onBackPressed() {
        // 离开群聊
        groupManager.leaveGroup()
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        groupManager.cleanup()
    }

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_IS_HOST = "extra_is_host"
        const val EXTRA_HOST_IP = "extra_host_ip"
        const val EXTRA_HOST_PORT = "extra_host_port"
    }
}
