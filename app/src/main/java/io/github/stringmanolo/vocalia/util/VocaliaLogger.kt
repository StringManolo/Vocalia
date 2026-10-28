package io.github.stringmanolo.vocalia.util

import java.text.SimpleDateFormat
import java.util.*

object VocaliaLogger {
    private const val MAX_LINES = 500
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, message: String) {
        addLog("D", tag, message)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        addLog("E", tag, "$message ${tr?.stackTraceToString() ?: ""}")
    }

    fun i(tag: String, message: String) {
        addLog("I", tag, message)
    }

    fun w(tag: String, message: String) {
      addLog("W", tag, message)
    }

    fun v(tag: String, message: String) {
      addLog("V", tag, message)
    }

    private fun addLog(level: String, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp $level/$tag: $message"
        synchronized(logs) {
            logs.add(line)
            if (logs.size > MAX_LINES) {
                logs.removeAt(0)
            }
        }
    }

    fun getAllLogs(): String {
        synchronized(logs) {
            return logs.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(logs) { logs.clear() }
    }
}
