package com.codeflow.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import com.codeflow.CodeFlowApp
import com.codeflow.databinding.ActivityGroupChatBinding
import com.codeflow.model.GroupMember
import com.codeflow.model.GroupSession
import com.codeflow.model.Message
import com.codeflow.model.MessageStatus
import com.codeflow.model.MessageType
import com.codeflow.transfer.GroupManager
import com.codeflow.ui.adapter.MessageAdapter
import com.google.gson.Gson
import java.io.File
import java.util.Locale

class GroupChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupChatBinding
    private lateinit var groupSession: GroupSession
    private lateinit var messageAdapter: MessageAdapter
    private val groupManager: GroupManager by lazy { (application as CodeFlowApp).groupManager }
    private val gson = Gson()
    private var memberList: List<GroupMember> = emptyList()

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { handlePickedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sessionJson = intent.getStringExtra(EXTRA_SESSION)
        if (sessionJson == null) {
            finish()
            return
        }
        groupSession = gson.fromJson(sessionJson, GroupSession::class.java)

        binding.toolbar.title = groupSession.groupName
        binding.toolbar.setNavigationOnClickListener { confirmLeave() }

        messageAdapter = MessageAdapter { msg -> openReceivedFile(msg) }
        messageAdapter.groupMode = true

        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@GroupChatActivity)
            adapter = messageAdapter
        }

        binding.btnSend.setOnClickListener { sendText() }
        binding.btnAttach.setOnClickListener { pickFile() }

        // 成员按钮：点击工具栏？加一个 menu。这里先通过长按标题或添加 action。
        setupCallbacks()
        addSystemMessage("已加入群聊")
        refreshTitle()
    }

    private fun setupCallbacks() {
        groupManager.onMessageReceived = { msg ->
            val isMe = msg.senderId == groupSession.myMemberId
            messageAdapter.addMessage(
                Message(
                    id = "${msg.timestamp}_${msg.senderId}",
                    type = MessageType.TEXT,
                    content = msg.content,
                    isFromMe = isMe,
                    status = if (isMe) MessageStatus.SENT else MessageStatus.RECEIVED,
                    timestamp = msg.timestamp,
                    senderName = msg.senderName
                )
            )
            scrollToBottom()
        }
        groupManager.onFileReceived = { header, file ->
            val isMe = header.senderId == groupSession.myMemberId
            messageAdapter.addMessage(
                Message(
                    id = "${header.timestamp}_${header.senderId}",
                    type = if (header.fileName.lowercase(Locale.ROOT).endsWith("jpg")
                        || header.fileName.lowercase(Locale.ROOT).endsWith("png")
                        || header.fileName.lowercase(Locale.ROOT).endsWith("gif")
                        || header.fileName.lowercase(Locale.ROOT).endsWith("webp")
                    ) MessageType.IMAGE else MessageType.FILE,
                    content = "",
                    fileName = header.fileName,
                    fileSize = header.fileSize,
                    filePath = file.absolutePath,
                    isFromMe = isMe,
                    status = MessageStatus.RECEIVED,
                    timestamp = header.timestamp,
                    senderName = header.senderName
                )
            )
            scrollToBottom()
            Toast.makeText(this, "收到文件：${header.fileName}", Toast.LENGTH_SHORT).show()
        }
        groupManager.onMemberChanged = { members ->
            memberList = members
            refreshTitle()
        }
        groupManager.onGroupDisbanded = { _ ->
            runOnUiThread {
                Toast.makeText(this, "群聊已解散", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        groupManager.currentSession?.let { session ->
            groupSession = session
        }
        memberList = groupManager.getCurrentMemberList()
    }

    private fun refreshTitle() {
        val count = if (groupManager.currentSession != null) {
            groupManager.getCurrentMemberList().size
        } else {
            memberList.size
        }
        binding.toolbar.subtitle = "$count 名成员"
    }

    private fun sendText() {
        val text = binding.etMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }
        messageAdapter.addMessage(
            Message(
                type = MessageType.TEXT,
                content = text,
                isFromMe = true,
                status = MessageStatus.SENT,
                timestamp = System.currentTimeMillis(),
                senderName = groupSession.myNickname
            )
        )
        binding.etMessage.text?.clear()
        scrollToBottom()
        groupManager.memberSendText(text)
    }

    private fun pickFile() {
        filePicker.launch("*/*")
    }

    private fun handlePickedFile(uri: Uri) {
        try {
            val fp = getFileFromUri(uri)
            if (fp == null) {
                Toast.makeText(this, "文件过大或无法读取", Toast.LENGTH_LONG).show()
                return
            }
            if (fp.length() > GroupManager.MAX_GROUP_FILE_SIZE) {
                Toast.makeText(this, "群聊文件不能超过5MB", Toast.LENGTH_LONG).show()
                return
            }
            val result = groupManager.sendFile(fp)
            if (result.isSuccess) {
                messageAdapter.addMessage(
                    Message(
                        type = MessageType.FILE,
                        content = "",
                        fileName = fp.name,
                        fileSize = fp.length(),
                        filePath = fp.absolutePath,
                        isFromMe = true,
                        status = MessageStatus.SENT,
                        timestamp = System.currentTimeMillis(),
                        senderName = groupSession.myNickname
                    )
                )
                scrollToBottom()
            } else {
                Toast.makeText(this, result.exceptionOrNull()?.message ?: "发送失败", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无法读取文件", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val resolver = contentResolver
            val temp = File(cacheDir, "group_send_cache")
            if (!temp.exists()) temp.mkdirs()
            val outFile = File(temp, System.currentTimeMillis().toString())
            val input = resolver.openInputStream(uri) ?: return null
            input.use { ins ->
                val output = outFile.outputStream()
                output.use { outs ->
                    ins.copyTo(outs)
                }
            }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    private fun openReceivedFile(message: Message) {
        val path = message.filePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val viewIntent = Intent(Intent.ACTION_VIEW)
        viewIntent.setDataAndType(file.toUri(), getMimeType(file.name))
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(viewIntent, "打开文件"))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "image/*"
            "mp4", "avi", "mkv", "mov", "wmv" -> "video/*"
            "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "audio/*"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "txt" -> "text/plain"
            else -> "*/*"
        }
    }

    private fun addSystemMessage(content: String) {
        messageAdapter.addMessage(
            Message(
                type = MessageType.SYSTEM,
                content = content,
                isFromMe = false,
                status = MessageStatus.RECEIVED,
                timestamp = System.currentTimeMillis()
            )
        )
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.rvMessages.post {
            binding.rvMessages.scrollToPosition(messageAdapter.itemCount - 1)
        }
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setTitle("离开群聊")
            .setMessage(if (groupSession.isHost) "你确定要解散此群聊吗？" else "你确定要退出此群聊吗？")
            .setPositiveButton("确定") { _, _ ->
                groupManager.leaveGroup()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onBackPressed() {
        confirmLeave()
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        outState.putString("session", gson.toJson(groupSession))
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_SESSION = "extra_group_session"
    }
}
