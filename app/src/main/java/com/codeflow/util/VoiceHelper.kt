package com.codeflow.util

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File

/**
 * 语音录制封装。输出 m4a，存到应用缓存目录。
 */
object VoiceRecorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val startTime = java.util.concurrent.atomic.AtomicLong(0)

    @android.annotation.SuppressLint("MissingPermission")
    fun start(context: Context): File {
        stop()
        val dir = File(context.cacheDir, "voice")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64000)
        r.setAudioSamplingRate(44100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        outputFile = file
        startTime.set(System.currentTimeMillis())
        return file
    }

    /** 停止录制并返回 (文件, 时长秒)。未在录制时返回 null。 */
    fun stop(): Pair<File, Int>? {
        val r = recorder ?: return null
        val file = outputFile ?: return null
        return try {
            r.stop()
            val duration = ((System.currentTimeMillis() - startTime.get()) / 1000).toInt()
            if (file.length() > 0 && duration > 0) file to duration else null
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
            recorder = null
            outputFile = null
        }
    }

    fun isRecording(): Boolean = recorder != null
}

/**
 * 语音播放封装。整个进程共享一个 MediaPlayer，一次只播一条。
 */
object VoicePlayer {

    private var player: MediaPlayer? = null
    private var currentPath: String? = null
    private var onComplete: (() -> Unit)? = null

    fun play(path: String, onCompletion: () -> Unit) {
        stop()
        if (currentPath == path && player != null) {
            player?.start()
            onComplete = onCompletion
            return
        }
        try {
            val p = MediaPlayer()
            p.setDataSource(path)
            p.setOnCompletionListener { onCompletion() }
            p.setOnErrorListener { _, _, _ ->
                onCompletion()
                true
            }
            p.prepare()
            p.start()
            player = p
            currentPath = path
            onComplete = onCompletion
        } catch (e: Exception) {
            onCompletion()
        }
    }

    fun isPlaying(path: String?): Boolean {
        val p = player ?: return false
        return currentPath == path && p.isPlaying
    }

    fun stop() {
        val p = player
        player = null
        currentPath = null
        onComplete?.invoke()
        onComplete = null
        try { p?.stop() } catch (_: Exception) {}
        try { p?.release() } catch (_: Exception) {}
    }
}
