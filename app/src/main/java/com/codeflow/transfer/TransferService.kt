package com.codeflow.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codeflow.CodeFlowApp
import com.codeflow.R
import com.codeflow.model.Message
import com.codeflow.model.MessageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TransferService : Service() {

    inner class TransferBinder : Binder() {
        fun getService(): TransferService = this@TransferService
    }

    private val binder = TransferBinder()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val messageList = CopyOnWriteArrayList<Message>()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    var connectedDeviceName: String = ""
        private set

    val transferDir: File by lazy {
        File(filesDir, "transfers").also { it.mkdirs() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        return START_STICKY
    }

    fun startConnection(deviceName: String) {
        connectedDeviceName = deviceName
        _isConnected.value = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    fun endConnection() {
        connectedDeviceName = ""
        _isConnected.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun addMessage(message: Message) {
        messageList.add(message)
        _messages.value = messageList.toList()
    }

    fun updateMessage(messageId: String, status: MessageStatus, progress: Int = 0) {
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            val updated = messageList[index].copy(status = status, progress = progress)
            messageList[index] = updated
            _messages.value = messageList.toList()
        }
    }

    fun saveReceivedFile(fileName: String, inputStream: InputStream, totalSize: Long): File {
        val file = File(transferDir, fileName)
        FileOutputStream(file).use { fos ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead: Long = 0
            while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                fos.write(buffer, 0, bytesRead)
                totalRead += bytesRead
            }
        }
        return file
    }

    fun saveReceivedText(content: String): Message {
        val message = Message(
            type = com.codeflow.model.MessageType.TEXT,
            content = content,
            isFromMe = false,
            status = MessageStatus.RECEIVED
        )
        addMessage(message)
        return message
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件传输",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bchat 文件传输服务"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.codeflow.ui.TransferActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bchat")
            .setContentText("已连接到 $connectedDeviceName")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "codeflow_transfer"
        const val NOTIFICATION_ID = 1001
    }
}
