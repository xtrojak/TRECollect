package com.trec.customlogsheets.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-app logging system that stores logs for display in the UI
 */
object AppLogger {
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private const val MAX_LOGS = 500 // Keep last 500 log entries
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
            val throwableStr = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
            return "[$time] $level/$tag: $message$throwableStr"
        }
    }
    
    /**
     * Logs a message at DEBUG level
     */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        Log.d(tag, message, throwable)
        addLog(LogEntry(System.currentTimeMillis(), "D", tag, message, throwable))
    }
    
    /**
     * Logs a message at INFO level
     */
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        Log.i(tag, message, throwable)
        addLog(LogEntry(System.currentTimeMillis(), "I", tag, message, throwable))
    }
    
    /**
     * Logs a message at WARN level
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        addLog(LogEntry(System.currentTimeMillis(), "W", tag, message, throwable))
    }
    
    /**
     * Logs a message at ERROR level
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        addLog(LogEntry(System.currentTimeMillis(), "E", tag, message, throwable))
    }
    
    private fun addLog(entry: LogEntry) {
        logQueue.offer(entry)
        // Keep only the last MAX_LOGS entries
        while (logQueue.size > MAX_LOGS) {
            logQueue.poll()
        }
    }
    
    /**
     * Gets all logs as a formatted string
     */
    fun getAllLogs(): String {
        return logQueue.joinToString("\n") { it.format() }
    }
    
    /**
     * Gets logs filtered by tag
     */
    fun getLogsByTag(tag: String): String {
        return logQueue
            .filter { it.tag.contains(tag, ignoreCase = true) }
            .joinToString("\n") { it.format() }
    }
    
    /**
     * Gets recent logs (last N entries)
     */
    fun getRecentLogs(count: Int = 100): String {
        return logQueue
            .toList()
            .takeLast(count)
            .joinToString("\n") { it.format() }
    }
    
    /**
     * Clears all logs
     */
    fun clearLogs() {
        logQueue.clear()
    }
    
    /**
     * Gets log count
     */
    fun getLogCount(): Int {
        return logQueue.size
    }
}

