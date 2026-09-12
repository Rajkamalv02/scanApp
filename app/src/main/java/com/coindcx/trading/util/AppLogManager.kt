package com.coindcx.trading.util

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
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
 * Multi-Tiered Reliability Architecture:
 * 1. Internal Storage (Always Guaranteed): Writes to context.filesDir/trading_bot.log with zero
 *    permissions required on any Android version.
 * 2. Public Storage Direct Write: Writes to /storage/emulated/0/Download/trading_bot.log when
 *    legacy/all-files storage access is available.
 * 3. MediaStore Scoped Storage Sync: Automatically syncs logs to public MediaStore.Downloads
 *    on Android 10+ (API 29+) so the file physically appears in the Downloads directory
 *    and across USB/PC even when direct file writes are restricted by Android OS.
 * 4. Automatic MediaScanner notifications for immediate USB MTP visibility on Windows/Mac.
 * 5. One-tap FileProvider sharing intent for exporting logs via WhatsApp/Email/Files.
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
    private val logChannel = Channel<LogEvent>(capacity = 5000)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var downloadLogFile: File? = null

    @Volatile
    private var internalLogFile: File? = null

    @Volatile
    private var dbInstance: AppDatabase? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var lastMediaStoreSyncTime = 0L

    @Volatile
    private var lastMediaScanTime = 0L

    @Volatile
    private var cachedMediaStoreUri: Uri? = null

    private val credentialRegex = Regex("(?i)(apikey|api_key|secret|token|authorization|bearer|signature)[\"':\\s=]+([A-Za-z0-9_\\-\\.~+/]{12,})")

    init {
        // Dedicated background I/O flusher coroutine
        scope.launch {
            processLogQueue()
        }
    }

    fun init(context: Context, db: AppDatabase? = null) {
        try {
            val appCtx = context.applicationContext
            appContext = appCtx

            // 1. Guaranteed internal log file (never fails, 0 permission required)
            internalLogFile = File(appCtx.filesDir, LOG_FILE_NAME)

            // 2. Public Download folder target
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                downloadLogFile = File(downloadDir, LOG_FILE_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "Public download directory access limited: ${e.message}")
            }

            if (db != null) {
                dbInstance = db
            } else if (dbInstance == null) {
                try {
                    dbInstance = AppDatabase.getInstance(appCtx)
                } catch (_: Exception) {}
            }

            if (!isInitialized) {
                isInitialized = true
                log("INFO", "SYSTEM", "================================================================================")
                log("INFO", "SYSTEM", "CoinDCX Futures Trading Bot Diagnostic Session Initialized")
                log("INFO", "SYSTEM", "Internal Log File: ${internalLogFile?.absolutePath}")
                log("INFO", "SYSTEM", "Public Download Log: ${downloadLogFile?.absolutePath}")
                log("INFO", "SYSTEM", "================================================================================")
            }
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
            // Headless JVM test runner
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
     * Institutional Structured Trade Lifecycle Logger.
     * Writes standardized machine-searchable key-value pairs and optional human-readable narrative step.
     */
    fun tradeLifecycle(
        event: String,
        tradeId: String,
        symbol: String,
        mode: String,
        attributes: Map<String, Any?>,
        narrative: String? = null
    ) {
        val builder = java.lang.StringBuilder()
        builder.append("event=").append(event)
            .append(" | trade_id=").append(tradeId)
            .append(" | symbol=").append(symbol)
            .append(" | mode=").append(mode)

        for ((k, v) in attributes) {
            if (v != null) {
                builder.append(" | ").append(k).append("=").append(v)
            }
        }
        log("TRADE", "LIFECYCLE", builder.toString())

        if (!narrative.isNullOrBlank()) {
            log("TRADE", "LIFECYCLE", "trade_id=$tradeId | >>> $narrative")
        }
    }

    object TradeIdGenerator {
        private val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

        fun generate(pair: String): String {
            val clean = pair.replace("B-", "").replace("_", "").replace("-", "")
            val time = synchronized(format) { format.format(Date()) }
            val rand = java.util.UUID.randomUUID().toString().take(4).uppercase(Locale.US)
            return "TID-$clean-$time-$rand"
        }
    }

    /**
     * Dedicated background I/O consumer loop.
     * Batches log events to maximize I/O throughput.
     */
    private suspend fun processLogQueue() {
        for (event in logChannel) {
            val batch = mutableListOf(event)
            while (batch.size < 100) {
                val next = logChannel.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            writeBatchToDisk(batch)
        }
    }

    private fun writeBatchToDisk(events: List<LogEvent>) {
        val internalFile = internalLogFile
        val pubFile = downloadLogFile
        if (internalFile == null && pubFile == null) return

        val textToAppend = buildString {
            for (event in events) {
                val timeStr = synchronized(dateFormat) { dateFormat.format(Date(event.timestamp)) }
                val stackTraceStr = if (event.throwable != null) {
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    event.throwable.printStackTrace(pw)
                    "\nStacktrace:\n$sw"
                } else ""
                append("[$timeStr] [${event.level.padEnd(5)}] [${event.tag.padEnd(10)}] ${event.message}$stackTraceStr\n")
            }
        }

        // 1. Primary Reliable Internal Storage (Never Fails)
        if (internalFile != null) {
            try {
                checkAndRotateLogs(internalFile)
                FileWriter(internalFile, true).use { writer ->
                    writer.write(textToAppend)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Internal disk write error: ${e.message}")
            }
        }

        // 2. Direct Write to Public Download File (Works if permission granted / legacy storage)
        var directWriteSuccess = false
        if (pubFile != null) {
            try {
                checkAndRotateLogs(pubFile)
                FileWriter(pubFile, true).use { writer ->
                    writer.write(textToAppend)
                }
                directWriteSuccess = true
                notifyMediaScanner(pubFile)
            } catch (_: Exception) {
                directWriteSuccess = false
            }
        }

        // 3. If direct write to public storage was denied by Scoped Storage, sync via MediaStore
        if (!directWriteSuccess && internalFile != null && internalFile.exists()) {
            val hasHighPriority = events.any { it.level in listOf("TRADE", "ERROR", "CRITICAL") || it.tag == "LIFECYCLE" }
            syncToMediaStoreIfNeeded(internalFile, force = hasHighPriority)
        }
    }

    private fun checkAndRotateLogs(currentFile: File) {
        try {
            if (currentFile.exists() && currentFile.length() > MAX_LOG_SIZE_BYTES) {
                val dir = currentFile.parentFile ?: return
                val baseName = currentFile.name
                val file2 = File(dir, "$baseName.2")
                val file1 = File(dir, "$baseName.1")

                if (file2.exists()) file2.delete()
                if (file1.exists()) file1.renameTo(file2)
                currentFile.renameTo(file1)

                FileWriter(currentFile, false).use { writer ->
                    val now = synchronized(dateFormat) { dateFormat.format(Date()) }
                    writer.append("[$now] [INFO ] [SYSTEM    ] Log rotated: previous log moved to $baseName.1\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Log rotation failed: ${e.message}")
        }
    }

    private fun syncToMediaStoreIfNeeded(sourceFile: File, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || (now - lastMediaStoreSyncTime > 3000L)) {
            lastMediaStoreSyncTime = now
            syncToMediaStore(sourceFile)
        }
    }

    /**
     * Publishes internal log file to the public Download directory using MediaStore.
     * This creates /storage/emulated/0/Download/trading_bot.log on Android 10+ without any permissions!
     */
    private fun syncToMediaStore(sourceFile: File) {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!sourceFile.exists() || sourceFile.length() == 0L) return

        try {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            var uri = cachedMediaStoreUri
            if (uri == null) {
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(LOG_FILE_NAME)

                resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        uri = ContentUris.withAppendedId(collection, id)
                    }
                }

                if (uri == null) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, LOG_FILE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    uri = resolver.insert(collection, contentValues)
                }
                cachedMediaStoreUri = uri
            }

            uri?.let { targetUri ->
                resolver.openOutputStream(targetUri, "wt")?.use { outStream ->
                    sourceFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore sync fallback: ${e.message}")
            cachedMediaStoreUri = null // Reset so next attempt re-queries or re-inserts
        }
    }

    private fun notifyMediaScanner(file: File) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastMediaScanTime > 5000L) {
            lastMediaScanTime = now
            try {
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
            } catch (_: Exception) {}
        }
    }

    private fun redactSensitiveData(raw: String): String {
        return credentialRegex.replace(raw) { matchResult ->
            val key = matchResult.groupValues[1]
            "$key=[REDACTED]"
        }
    }

    /**
     * Resolves the primary active log file that currently contains data.
     */
    fun getLogFile(): File? {
        val pub = downloadLogFile
        if (pub != null && pub.exists() && pub.length() > 0L) {
            return pub
        }
        val internal = internalLogFile
        if (internal != null && internal.exists() && internal.length() > 0L) {
            return internal
        }
        return pub ?: internal
    }

    fun getLogFilePath(): String {
        return downloadLogFile?.absolutePath ?: "/storage/emulated/0/Download/$LOG_FILE_NAME"
    }

    fun getLogFileSizeFormatted(): String {
        val file = getLogFile()
        val bytes = file?.length() ?: 0L
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    fun isDirectStorageAccessGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun flush() {
        val internal = internalLogFile
        if (internal != null && internal.exists()) {
            syncToMediaStore(internal)
            downloadLogFile?.let { notifyMediaScanner(it) }
        }
    }

    fun readRecentLogs(maxLines: Int = 300): String {
        val file = getLogFile() ?: return "Log file not initialized."
        if (!file.exists() || file.length() == 0L) {
            return "Log file is currently empty at: ${file.absolutePath}"
        }

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
            internalLogFile?.let { file ->
                if (file.exists()) {
                    FileWriter(file, false).use { writer ->
                        val now = synchronized(dateFormat) { dateFormat.format(Date()) }
                        writer.append("[$now] [INFO ] [SYSTEM    ] Log file cleared by user.\n")
                    }
                }
            }

            downloadLogFile?.let { file ->
                if (file.exists()) {
                    try {
                        FileWriter(file, false).use { writer ->
                            val now = synchronized(dateFormat) { dateFormat.format(Date()) }
                            writer.append("[$now] [INFO ] [SYSTEM    ] Log file cleared by user.\n")
                        }
                    } catch (_: Exception) {}
                }
            }

            internalLogFile?.let { syncToMediaStore(it) }

            // Delete rotated files
            internalLogFile?.parentFile?.listFiles { _, name -> name.startsWith(LOG_FILE_NAME) && name != LOG_FILE_NAME }
                ?.forEach { it.delete() }

            downloadLogFile?.parentFile?.listFiles { _, name -> name.startsWith(LOG_FILE_NAME) && name != LOG_FILE_NAME }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing logs: ${e.message}")
        }
    }

    fun createShareIntent(context: Context): Intent? {
        flush()

        val fileToShare = getLogFile() ?: return null
        if (!fileToShare.exists() || fileToShare.length() == 0L) {
            i("SYSTEM", "User initiated log export")
            flush()
        }

        val activeFile = getLogFile() ?: fileToShare
        if (!activeFile.exists()) return null

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, activeFile)

            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "CoinDCX Trading Bot Logs (${dateFormat.format(Date())})")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Attached trading_bot.log from CoinDCX Futures Bot.\nSource File: ${activeFile.absolutePath}"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating share intent: ${e.message}", e)
            null
        }
    }
}
