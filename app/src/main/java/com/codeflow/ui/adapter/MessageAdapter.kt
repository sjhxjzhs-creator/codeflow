package com.codeflow.ui.adapter

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.codeflow.R
import com.codeflow.databinding.ItemMessageBinding
import com.codeflow.model.Message
import com.codeflow.model.MessageStatus
import com.codeflow.model.MessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val onFileClick: (Message) -> Unit
) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    private val messages = mutableListOf<Message>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitList(newMessages: List<Message>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateMessage(messageId: String, status: MessageStatus, progress: Int) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            messages[index] = messages[index].copy(status = status, progress = progress)
            notifyItemChanged(index)
        }
    }

    fun updateMessageFile(messageId: String, filePath: String) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            val msg = messages[index]
            messages[index] = msg.copy(status = MessageStatus.RECEIVED, filePath = filePath)
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class ViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            when {
                // 群聊系统消息
                message.type == MessageType.GROUP_JOIN || message.type == MessageType.GROUP_LEAVE -> bindGroupSystemMessage(message)
                // 群聊消息
                message.isGroupMsg -> bindGroupMessage(message)
                // 私聊消息
                message.type == MessageType.TEXT -> bindTextMessage(message)
                message.type == MessageType.IMAGE || message.type == MessageType.FILE -> bindFileMessage(message)
                message.type == MessageType.SYSTEM -> bindSystemMessage(message)
            }
        }

        private fun bindTextMessage(message: Message) {
            binding.layoutText.visibility = View.VISIBLE
            binding.layoutFile.visibility = View.GONE
            binding.tvSenderName.visibility = View.GONE
            binding.tvContent.text = message.content
            binding.tvTime.text = dateFormat.format(Date(message.timestamp))

            val textParams = binding.layoutText.layoutParams as FrameLayout.LayoutParams

            if (message.isFromMe) {
                textParams.gravity = Gravity.END
                binding.layoutText.setBackgroundResource(0)
                binding.layoutText.background = roundedRect(
                    binding.root.context.getColor(R.color.bubble_sent), 18f
                )
                binding.tvStatus.text = when (message.status) {
                    MessageStatus.SENDING -> "..."
                    MessageStatus.SENT -> "\u2713"
                    MessageStatus.FAILED -> "\u2717"
                    else -> "\u2713\u2713"
                }
                binding.layoutTextFooter.visibility = View.VISIBLE
            } else {
                textParams.gravity = Gravity.START
                binding.layoutText.setBackgroundResource(0)
                binding.layoutText.background = roundedRect(
                    binding.root.context.getColor(R.color.bubble_received), 18f
                )
                binding.layoutTextFooter.visibility = View.VISIBLE
                binding.tvStatus.visibility = View.GONE
            }
            binding.layoutText.layoutParams = textParams
        }

        private fun bindGroupMessage(message: Message) {
            binding.layoutText.visibility = View.VISIBLE
            binding.layoutFile.visibility = View.GONE
            binding.tvSenderName.visibility = View.VISIBLE
            binding.tvSenderName.text = message.senderName ?: "未知成员"
            binding.tvContent.text = message.content
            binding.tvTime.text = dateFormat.format(Date(message.timestamp))

            val textParams = binding.layoutText.layoutParams as FrameLayout.LayoutParams
            textParams.gravity = Gravity.START
            binding.layoutText.setBackgroundResource(0)
            binding.layoutText.background = roundedRect(
                binding.root.context.getColor(R.color.bubble_received), 18f
            )
            binding.layoutTextFooter.visibility = View.GONE
            binding.tvStatus.visibility = View.GONE

            binding.layoutText.layoutParams = textParams
        }

        private fun bindGroupSystemMessage(message: Message) {
            binding.layoutText.visibility = View.VISIBLE
            binding.layoutFile.visibility = View.GONE
            binding.tvSenderName.visibility = View.GONE
            binding.tvContent.text = message.content
            binding.layoutTextFooter.visibility = View.GONE

            val textParams = binding.layoutText.layoutParams as FrameLayout.LayoutParams
            textParams.gravity = Gravity.CENTER
            binding.layoutText.layoutParams = textParams

            binding.layoutText.setBackgroundResource(0)
            binding.layoutText.background = roundedRect(
                binding.root.context.resources.getColor(android.R.color.darker_gray, null),
                12f
            )
        }

        private fun bindFileMessage(message: Message) {
            binding.layoutText.visibility = View.GONE
            binding.layoutFile.visibility = View.VISIBLE
            binding.tvFileName.text = message.fileName ?: "Unknown"
            binding.tvFileSize.text = formatFileSize(message.fileSize)

            val iconRes = when {
                message.fileName?.let { isImageFile(it) } == true -> R.drawable.ic_file_image
                message.fileName?.let { isVideoFile(it) } == true -> R.drawable.ic_file_video
                message.fileName?.let { isAudioFile(it) } == true -> R.drawable.ic_file_audio
                message.fileName?.let { isApkFile(it) } == true -> R.drawable.ic_file_apk
                message.fileName?.let { isDocFile(it) } == true -> R.drawable.ic_file_document
                else -> R.drawable.ic_file_generic
            }
            binding.ivFileIcon.setImageResource(iconRes)

            if (message.status == MessageStatus.SENDING || message.status == MessageStatus.RECEIVING) {
                binding.progressFile.visibility = View.VISIBLE
                binding.progressFile.progress = message.progress
            } else {
                binding.progressFile.visibility = View.GONE
            }

            binding.tvFileTime.text = dateFormat.format(Date(message.timestamp))

            val fileParams = binding.layoutFile.layoutParams as FrameLayout.LayoutParams
            if (message.isFromMe) {
                fileParams.gravity = Gravity.END
                binding.layoutFile.setBackgroundResource(0)
                binding.layoutFile.background = roundedRect(
                    binding.root.context.getColor(R.color.bubble_sent), 18f
                )
            } else {
                fileParams.gravity = Gravity.START
                binding.layoutFile.setBackgroundResource(0)
                binding.layoutFile.background = roundedRect(
                    binding.root.context.getColor(R.color.bubble_received), 18f
                )
            }
            binding.layoutFile.layoutParams = fileParams

            binding.layoutFile.setOnClickListener {
                if (message.status == MessageStatus.RECEIVED || message.status == MessageStatus.SENT) {
                    onFileClick(message)
                }
            }
        }

        private fun bindSystemMessage(message: Message) {
            binding.layoutText.visibility = View.VISIBLE
            binding.layoutFile.visibility = View.GONE
            binding.tvContent.text = message.content
            binding.layoutTextFooter.visibility = View.GONE

            val textParams = binding.layoutText.layoutParams as FrameLayout.LayoutParams
            textParams.gravity = Gravity.CENTER
            binding.layoutText.layoutParams = textParams

            binding.layoutText.setBackgroundResource(0)
            binding.layoutText.background = roundedRect(
                binding.root.context.resources.getColor(android.R.color.darker_gray, null),
                12f
            )
        }

        private fun roundedRect(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
            return android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = radius * binding.root.context.resources.displayMetrics.density
            }
        }

        private fun isImageFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic")
        }

        private fun isVideoFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in listOf("mp4", "avi", "mkv", "mov", "flv", "wmv")
        }

        private fun isAudioFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in listOf("mp3", "wav", "flac", "aac", "ogg", "wma", "m4a")
        }

        private fun isApkFile(fileName: String): Boolean {
            return fileName.substringAfterLast('.', "").lowercase() == "apk"
        }

        private fun isDocFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv")
        }
    }

    companion object {
        fun formatFileSize(size: Long): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
                else -> "${size / (1024 * 1024 * 1024)} GB"
            }
        }
    }
}
