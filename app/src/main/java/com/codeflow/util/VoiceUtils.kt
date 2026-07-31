package com.codeflow.util

import android.media.MediaMetadataRetriever
import kotlin.math.roundToInt

object VoiceUtils {

    private val VOICE_EXTS = setOf("m4a", "ogg", "aac", "amr", "3gp", "opus")

    fun isVoiceFile(fileName: String?): Boolean {
        if (fileName == null) return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in VOICE_EXTS
    }

    fun voiceFileName(): String = "voice_${System.currentTimeMillis()}.m4a"

    /** 读取音频文件时长（秒）。失败返回 0。 */
    fun getDurationSeconds(filePath: String): Int {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.release()
            (ms / 1000.0).roundToInt().coerceAtLeast(1)
        } catch (e: Exception) {
            0
        }
    }
}
