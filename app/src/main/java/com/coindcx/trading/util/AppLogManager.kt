package com.coindcx.trading.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.SystemLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Production File Logging and Diagnostic Manager.
 *
 * Persists all trading operations, scan evaluations, indicator snapshots, risk decisions,
 * and order executions directly to the phone's public Download folder:
 *   Path: /storage/emulated/0/Download/trading_bot.log
 *
 * Architecture Highlights:
 * - Direct writing to public Download directory (Environment.DIRECTORY_DOWNLOADS)
 * - Non-blocking asynchronous Channel ingestion (capacity = 2,000 events)
 * - Single-threaded sequential disk flusher preventing concurrent file lock contention
 * - Strict 5MB file size cap with automatic 3-file rotation (.log -> .1.log -> .2.log)
 * - Redaction filter for API credentials and secret tokens
 * - Seamless Room DB insertion for live in-app UI display
 * - One-tap FileProvider sharing intent for exporting logs via WhatsApp/Email/Files
 */
object AppLogManager {

    private const val TAG = "AppLogManager"
    private const val LOG_FILE_NAME = "trading_bot.log"
    private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L // 5MB cap per file

    private data class LogEvent(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<LogEvent>(capacity = 2000)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var downloadLogFile: File? = null

    @Volatile
    private var dbInstance: AppDatabase? = null

    @Volatile
    private var isInitialized = false

    private val credentialRegex = Regex("(?i)(apikey|api_key|secret|token|authorization|bearer|signature)[\"':\\s=]+([A-Za-z0-9_\\-\\.~+/]{12,})")

    init {
        // Start dedicated background I/O flusher coroutine
        scope.launch {
            processLogQueue()
        }
    }

    fun init(context: Context, db: AppDatabase? = null) {
        if (isInitialized && db == null) return

        try {
            val appCtx = context.applicationContext

            // Primary Target: Phone's Public Download Folder
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            downloadLogFile = File(downloadDir, LOG_FILE_NAME)

            if (db != null) {
                dbInstance = db
            } else if (dbInstance == null) {
                dbInstance = AppDatabase.getInstance(appCtx)
            }

            isInitialized = true

            // Session startup header
            log("INFO", "SYSTEM", "================================================================================")
            log("INFO", "SYSTEM", "CoinDCX Futures Trading Bot Diagnostic Session Initialized")
            log("INFO", "SYSTEM", "Target Log File: ${downloadLogFile?.absolutePath}")
            log("INFO", "SYSTEM", "================================================================================")
        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing AppLogManager: ${e.message}", e)
        }
    }

    fun setDatabase(db: AppDatabase) {
        dbInstance = db
    }

    /**
     * Non-blocking log submission.
     * Enqueues into bounded channel in nanoseconds, never blocking the calling thread.
     */
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val sanitized = redactSensitiveData(message)

        // 1. Android Logcat (safely guarded for local JVM unit test environments)
        try {
            when (level.uppercase(Locale.US)) {
                "DEBUG" -> Log.d(tag, sanitized)
                "INFO", "TRADE", "STRATEGY" -> Log.i(tag, sanitized)
                "WARN", "RISK", "QUALITY" -> Log.w(tag, sanitized)
                "ERROR", "CRITICAL" -> Log.e(tag, sanitized, throwable)
                else -> Log.v(tag, sanitized)
            }
        } catch (_: Throwable) {
            // Ignored in headless JVM test runners where android.util.Log is unmocked
        }

        // 2. Enqueue for asynchronous disk writing
        logChannel.trySend(
            LogEvent(
                timestamp = System.currentTimeMillis(),
                level = level.uppercase(Locale.US),
                tag = tag,
                message = sanitized,
                throwable = throwable
            )
        )

        // 3. Room Database for in-app UI table
        val db = dbInstance
        if (db != null) {
            scope.launch {
                try {
                    db.systemLogDao().insert(
                        SystemLogEntity(
                            level = level.uppercase(Locale.US),
                            tag = tag,
                            message = if (sanitized.length > 500) sanitized.take(500) + "..." else sanitized,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (_: Exception) {
                    // Suppress during database teardown
                }
            }
        }
    }

    // Convenience logging functions
    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun w(tag: String, message: String) = log("WARN", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log("ERROR", tag, message, throwable)
    fun trade(tag: String, message: String) = log("TRADE", tag, message)
    fun strategy(message: String) = log("STRATEGY", "STRATEGY", message)
    fun quality(message: String) = log("QUALITY", "QUALITY", message)
    fun risk(message: String) = log("RISK", "RISK_MANAGER", message)
    fun scanner(message: String) = log("INFO", "SCANNER", message)

    /**
     * Dedicated background I/O consumer loop.
     * Batches log events to the single target file in the Download folder.
     */
    private suspend fun processLogQueue() {
        for (event in logChannel) {
            writeEventToDisk(event)
        }
    }

    private fun writeEventToDisk(event: LogEvent) {
        val targetFile = downloadLogFile ?: return

        try {
            checkAndRotateLogs(targetFile)

            val timeStr = synchronized(dateFormat) { dateFormat.format(Date(event.timestamp)) }
            val stackTraceStr = if (event.throwable != null) {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                event.throwable.printStackTrace(pw)
                "\nStacktrace:\n$sw"
            } else ""

            val line = "[$timeStr] [${event.level.padEnd(5)}] [${event.tag.padEnd(10)}] ${event.message}$stackTraceStr\n"

            FileWriter(targetFile, true).use { writer ->
                writer.append(line)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Disk write error to ${targetFile.absolutePath}: ${e.message}")
        }
    }

    private fun checkAndRotateLogs(currentFile: File) {
        try {
            if (currentFile.exists() && currentFile.length() > MAX_LOG_SIZE_BYTES) {
                val dir = currentFile.parentFile ?: return
                val file2 = File(dir, "$LOG_FILE_NAME.2")
                val file1 = File(dir, "$LOG_FILE_NAME.1")

                if (file2.exists()) file2.delete()
                if (file1.exists()) file1.renameTo(file2)
                currentFile.renameTo(file1)

                FileWriter(currentFile, false).use { writer ->
                    val now = synchronized(dateFormat) { dateFormat.format(Date()) }
                    writer.append("[$now] [INFO ] [SYSTEM    ] Log rotated: previous log moved to $LOG_FILE_NAME.1\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Log rotation failed: ${e.message}")
        }
    }

    private fun redactSensitiveData(raw: String): String {
        return credentialRegex.replace(raw) { matchResult ->
            val key = matchResult.groupValues[1]
            "$key=[REDACTED]"
        }
    }

    fun getLogFile(): File? = downloadLogFile

    fun getLogFilePath(): String {
        return downloadLogFile?.absolutePath ?: "/storage/emulated/0/Download/$LOG_FILE_NAME"
    }

    fun getLogFileSizeFormatted(): String {
        val bytes = downloadLogFile?.length() ?: 0L
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    fun readRecentLogs(maxLines: Int = 300): String {
        val file = downloadLogFile ?: return "Log file not initialized."
        if (!file.exists()) return "Log file is currently empty at: ${file.absolutePath}"

        return try {
            val lines = file.readLines()
            val recent = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
            recent.joinToString("\n")
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    fun clearLogs() {
        try {
            downloadLogFile?.let { file ->
                if (file.exists()) {
                    FileWriter(file, false).use { writer ->
                        val now = synchronized(dateFormat) { dateFormat.format(Date()) }
                        writer.append("[$now] [INFO ] [SYSTEM    ] Log file cleared by user.\n")
                    }
                }
            }
            val dir = downloadLogFile?.parentFile
            dir?.listFiles { _, name -> name.startsWith(LOG_FILE_NAME) && name != LOG_FILE_NAME }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing logs: ${e.message}")
        }
    }

    fun createShareIntent(context: Context): Intent? {
        val file = downloadLogFile ?: return null
        if (!file.exists() || file.length() == 0L) {
            i("SYSTEM", "User initiated log export")
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, file)

            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "CoinDCX Trading Bot Logs (${dateFormat.format(Date())})")
                putExtra(Intent.EXTRA_TEXT, "Attached trading_bot.log from CoinDCX Futures Bot.\nFile path on phone: ${file.absolutePath}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating share intent: ${e.message}", e)
            null
        }
    }
}
