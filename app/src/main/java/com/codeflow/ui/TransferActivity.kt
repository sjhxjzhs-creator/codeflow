package com.codeflow.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.codeflow.R
import com.codeflow.databinding.ActivityTransferBinding
import com.codeflow.model.Message
import com.codeflow.model.MessageStatus
import com.codeflow.model.MessageType
import com.codeflow.transfer.ConnectionManager
import com.codeflow.transfer.TransferProtocol
import com.codeflow.transfer.TransferService
import com.codeflow.ui.adapter.MessageAdapter
import com.codeflow.util.VoicePlayer
import com.codeflow.util.VoiceRecorder
import com.codeflow.util.VoiceUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

class TransferActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransferBinding
    private lateinit var connectionManager: ConnectionManager
    private lateinit var messageAdapter: MessageAdapter
    private var isRecording = false

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handleFileSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectionManager = (application as com.codeflow.CodeFlowApp).connectionManager
        setupUI()
        observeMessages()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            showDisconnectDialog()
        }

        messageAdapter = MessageAdapter(
            onFileClick = { message ->
                if (message.status == MessageStatus.RECEIVED && message.filePath != null) {
                    openFile(message.filePath)
                }
            },
            onVoiceClick = { message ->
                if (message.filePath != null) {
                    toggleVoicePlayback(message)
                }
            }
        )

        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@TransferActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }

        binding.rvMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                binding.rvMessages.post {
                    binding.rvMessages.scrollToPosition(messageAdapter.itemCount - 1)
                }
            }
        }

        binding.btnAttach.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        binding.btnMic.setOnClickListener {
            toggleRecording()
        }

        binding.btnSend.setOnClickListener {
            sendTextMessage()
        }

        binding.btnDisconnect.setOnClickListener {
            showDisconnectDialog()
        }

        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendTextMessage()
            true
        }
    }

    private fun observeMessages() {
        connectionManager.onMessageReceived = { message ->
            runOnUiThread {
                messageAdapter.addMessage(message)
                scrollToBottom()
            }
        }

        connectionManager.onPeerDisconnected = {
            runOnUiThread {
                messageAdapter.addMessage(
                    Message(
                        type = MessageType.SYSTEM,
                        content = "对方已退出",
                        isFromMe = false
                    )
                )
                scrollToBottom()
            }
        }

        connectionManager.onFileInfoReceived = { fileInfo ->
            runOnUiThread {
                val isVoice = VoiceUtils.isVoiceFile(fileInfo.fileName)
                val previewMsg = Message(
                    id = fileInfo.messageId,
                    type = if (isVoice) MessageType.VOICE else MessageType.FILE,
                    content = fileInfo.fileName,
                    fileName = fileInfo.fileName,
                    fileSize = fileInfo.fileSize,
                    isFromMe = false,
                    status = MessageStatus.RECEIVING,
                    duration = 0
                )
                messageAdapter.addMessage(previewMsg)
            }
        }

        connectionManager.onFileSendProgress = { messageId, progress ->
            runOnUiThread {
                messageAdapter.updateMessage(messageId, MessageStatus.SENDING, progress)
            }
        }

        connectionManager.onFileReceiveProgress = { messageId, progress ->
            runOnUiThread {
                messageAdapter.updateMessage(messageId, MessageStatus.RECEIVING, progress)
            }
        }

        connectionManager.onFileCompleted = { messageId, filePath, _ ->
            runOnUiThread {
                if (messageAdapter.isVoiceMessage(messageId)) {
                    messageAdapter.setVoiceDuration(
                        messageId, filePath, VoiceUtils.getDurationSeconds(filePath)
                    )
                } else {
                    messageAdapter.updateMessage(messageId, MessageStatus.RECEIVED, 100)
                    messageAdapter.updateMessageFile(messageId, filePath)
                }
                Toast.makeText(this, "文件接收完成", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendTextMessage() {
        val text = binding.etMessage.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        connectionManager.sendTextMessage(text)
        binding.etMessage.text?.clear()
    }

    private fun handleFileSelected(uri: Uri) {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val fileName = if (nameIndex >= 0) cursor.getString(nameIndex) else "unknown"
                    val fileSize = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L

                    val messageId = java.util.UUID.randomUUID().toString()
                    val fileInfo = TransferProtocol.FileInfo(
                        messageId = messageId,
                        fileName = fileName,
                        fileSize = fileSize,
                        fileType = getFileType(fileName),
                        timestamp = System.currentTimeMillis()
                    )

                    val inputStream = contentResolver.openInputStream(uri)
                    inputStream?.let { stream ->
                        val message = Message(
                            id = messageId,
                            type = if (isImageFile(fileName)) MessageType.IMAGE else MessageType.FILE,
                            content = fileName,
                            fileName = fileName,
                            fileSize = fileSize,
                            isFromMe = true,
                            status = MessageStatus.SENDING
                        )
                        messageAdapter.addMessage(message)
                        scrollToBottom()

                        connectionManager.sendLargeFile(fileInfo, stream, fileSize)

                        connectionManager.onFileSendProgress = { msgId, progress ->
                            runOnUiThread {
                                if (msgId == messageId) {
                                    messageAdapter.updateMessage(msgId, MessageStatus.SENDING, progress)
                                    if (progress >= 100) {
                                        messageAdapter.updateMessage(msgId, MessageStatus.SENT, 100)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "文件选择失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(filePath: String) {
        try {
            val file = File(filePath)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleVoicePlayback(message: Message) {
        val path = message.filePath ?: return
        if (VoicePlayer.isPlaying(path)) {
            VoicePlayer.stop()
            messageAdapter.setPlayingMessage(null)
            return
        }
        VoicePlayer.play(path) {
            runOnUiThread { messageAdapter.setPlayingMessage(null) }
        }
        messageAdapter.setPlayingMessage(message.id)
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        val file = VoiceRecorder.start(this)
        isRecording = true
        binding.btnMic.iconTint = ContextCompat.getColorStateList(
            this, R.color.error
        )
        Toast.makeText(this, "正在录音，点击完成", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        val result = VoiceRecorder.stop()
        isRecording = false
        binding.btnMic.iconTint = ContextCompat.getColorStateList(
            this, R.color.primary
        )
        if (result == null) {
            Toast.makeText(this, "录音时间太短", Toast.LENGTH_SHORT).show()
            return
        }
        val (file, duration) = result
        sendVoice(file, duration)
    }

    private fun sendVoice(file: java.io.File, duration: Int) {
        try {
            val messageId = java.util.UUID.randomUUID().toString()
            val fileInfo = TransferProtocol.FileInfo(
                messageId = messageId,
                fileName = file.name,
                fileSize = file.length(),
                fileType = "m4a",
                timestamp = System.currentTimeMillis()
            )
            val message = Message(
                id = messageId,
                type = MessageType.VOICE,
                content = file.name,
                fileName = file.name,
                fileSize = file.length(),
                filePath = file.absolutePath,
                isFromMe = true,
                status = MessageStatus.SENDING,
                duration = duration
            )
            messageAdapter.addMessage(message)
            scrollToBottom()

            val inputStream = java.io.FileInputStream(file)
            connectionManager.sendLargeFile(fileInfo, inputStream, file.length())

            connectionManager.onFileSendProgress = { msgId, progress ->
                runOnUiThread {
                    if (msgId == messageId) {
                        messageAdapter.updateMessage(msgId, MessageStatus.SENDING, progress)
                        if (progress >= 100) {
                            messageAdapter.updateMessage(msgId, MessageStatus.SENT, 100)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "语音发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDisconnectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.tvTitle).text = getString(R.string.disconnect)
        view.findViewById<TextView>(R.id.tvMessage).text = "确定要断开连接吗？"
        view.findViewById<MaterialButton>(R.id.btnConfirm).text = getString(R.string.disconnect)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .show()
        view.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnConfirm).setOnClickListener {
            connectionManager.disconnect()
            finish()
        }
    }

    private fun scrollToBottom() {
        binding.rvMessages.post {
            val lastIndex = messageAdapter.itemCount - 1
            if (lastIndex >= 0) {
                binding.rvMessages.smoothScrollToPosition(lastIndex)
            }
        }
    }

    private fun isImageFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic")
    }

    private fun getFileType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "*/*"
        }
    }

    override fun onBackPressed() {
        showDisconnectDialog()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            VoiceRecorder.stop()
        }
        VoicePlayer.stop()
    }
}
