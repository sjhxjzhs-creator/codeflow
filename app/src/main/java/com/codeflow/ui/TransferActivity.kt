package com.codeflow.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

class TransferActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransferBinding
    private lateinit var connectionManager: ConnectionManager
    private lateinit var messageAdapter: MessageAdapter

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { handleFileSelected(it) } }

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

        messageAdapter = MessageAdapter { message ->
            if (message.status == MessageStatus.RECEIVED && message.filePath != null) {
                openFile(message.filePath)
            }
        }

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

        connectionManager.onFileInfoReceived = { fileInfo ->
            runOnUiThread {
                val previewMsg = Message(
                    id = fileInfo.messageId,
                    type = MessageType.FILE,
                    content = fileInfo.fileName,
                    fileName = fileInfo.fileName,
                    fileSize = fileInfo.fileSize,
                    isFromMe = false,
                    status = MessageStatus.RECEIVING
                )
                messageAdapter.addMessage(previewMsg)
            }
        }

        connectionManager.onFileDataReady = { inputStream, fileName, size ->
            lifecycleScope.launch {
                val dir = File(filesDir, "transfers").also { it.mkdirs() }
                val file = File(dir, fileName)
                try {
                    file.outputStream().use { fos ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                runOnUiThread {
                    Toast.makeText(this@TransferActivity,
                        "$fileName ${getString(R.string.file_received)}", Toast.LENGTH_SHORT).show()
                }
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

                    val fileInfo = TransferProtocol.FileInfo(
                        messageId = java.util.UUID.randomUUID().toString(),
                        fileName = fileName,
                        fileSize = fileSize,
                        fileType = getFileType(fileName),
                        timestamp = System.currentTimeMillis()
                    )

                    val inputStream = contentResolver.openInputStream(uri)
                    inputStream?.let { stream ->
                        connectionManager.sendLargeFile(fileInfo, stream, fileSize)

                        val message = Message(
                            id = fileInfo.messageId,
                            type = if (isImageFile(fileName)) MessageType.IMAGE else MessageType.FILE,
                            content = fileName,
                            fileName = fileName,
                            fileSize = fileSize,
                            isFromMe = true,
                            status = MessageStatus.SENDING
                        )
                        messageAdapter.addMessage(message)
                        scrollToBottom()
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

    private fun showDisconnectDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.disconnect))
            .setMessage("确定要断开连接吗？")
            .setPositiveButton(getString(R.string.disconnect)) { _, _ ->
                connectionManager.disconnect()
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
}
