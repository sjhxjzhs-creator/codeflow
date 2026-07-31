package com.codeflow.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量级应用内日志。用于排查蓝牙 / 局域网连接问题。
 * 线程安全环形缓冲，最多保留 [MAX_SIZE] 条，进程级单例。
 */
object AppLog {

    const val MAX_SIZE = 200

    private val lock = Any()
    private val entries = ArrayDeque<String>()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(tag: String, message: String) {
        val line = "[${fmt.format(Date())}] [$tag] $message"
        synchronized(lock) {
            if (entries.size >= MAX_SIZE) {
                entries.removeFirst()
            }
            entries.addLast(line)
        }
    }

    /** 取全部日志，新的在末尾。 */
    fun snapshot(): List<String> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
