package com.coindcx.trading.engine.database

import android.content.Context
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.util.AppLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

data class DatabaseStats(
    val closedTradesCount: Int,
    val openTradesCount: Int,
    val finalizedOrdersCount: Int,
    val activeOrdersCount: Int,
    val systemLogsCount: Int,
    val equitySnapshotsCount: Int,
    val databaseSizeBytes: Long,
    val databaseSizeFormatted: String
)

data class PurgeSummary(
    val deletedTradesCount: Int,
    val deletedOrdersCount: Int,
    val deletedLogsCount: Int,
    val deletedSnapshotsCount: Int
) {
    val totalDeleted: Int get() = deletedTradesCount + deletedOrdersCount + deletedLogsCount + deletedSnapshotsCount
}

/**
 * Institutional Database Manager
 *
 * Governs database inspection, categorization, and safe record deletion.
 * Guarantees that active trading state (open positions, pending orders, and active sessions)
 * is protected by strict SQL constraints and defense-in-depth safety filters.
 */
class DatabaseManager(
    private val context: Context,
    private val db: AppDatabase
) {

    suspend fun getDatabaseStats(): DatabaseStats = withContext(Dispatchers.IO) {
        val closedTrades = db.tradeDao().getClosedTradesCount()
        val openTrades = db.tradeDao().getOpenTradesCount()
        val finalizedOrders = db.orderDao().getFinalizedOrdersCount()
        val activeOrders = db.orderDao().getActiveOrdersCount()
        val systemLogs = db.systemLogDao().getLogsCount()
        val snapshots = db.equitySnapshotDao().getSnapshotsCount()

        val sizeBytes = try {
            val dbFile = context.getDatabasePath("trading_bot_db")
            if (dbFile != null && dbFile.exists()) dbFile.length() else 0L
        } catch (_: Exception) {
            0L
        }

        DatabaseStats(
            closedTradesCount = closedTrades,
            openTradesCount = openTrades,
            finalizedOrdersCount = finalizedOrders,
            activeOrdersCount = activeOrders,
            systemLogsCount = systemLogs,
            equitySnapshotsCount = snapshots,
            databaseSizeBytes = sizeBytes,
            databaseSizeFormatted = formatBytes(sizeBytes)
        )
    }

    suspend fun deleteClosedTradeById(tradeId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = db.tradeDao().deleteClosedTradeById(tradeId)
            if (rows > 0) {
                AppLogManager.i("DB_MGR", "Deleted closed trade record #$tradeId from database.")
                true
            } else {
                AppLogManager.w("DB_MGR", "Cannot delete trade #$tradeId (Record does not exist or trade is currently OPEN).")
                false
            }
        } catch (e: Exception) {
            AppLogManager.e("DB_MGR", "Failed deleting trade #$tradeId: ${e.message}", e)
            false
        }
    }

    suspend fun deleteClosedTradesByIds(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        try {
            val deleted = db.tradeDao().deleteClosedTradesByIds(ids)
            AppLogManager.i("DB_MGR", "Bulk-deleted $deleted closed trade records.")
            deleted
        } catch (e: Exception) {
            AppLogManager.e("DB_MGR", "Failed bulk-deleting closed trades: ${e.message}", e)
            0
        }
    }

    suspend fun clearClosedTrades(
        olderThanDays: Int? = null,
        resultFilter: String? = null
    ): Int = withContext(Dispatchers.IO) {
        try {
            val deleted = when {
                olderThanDays != null && olderThanDays > 0 -> {
                    val cutoff = System.currentTimeMillis() - (olderThanDays * 86_400_000L)
                    db.tradeDao().deleteClosedTradesOlderThan(cutoff)
                }
                !resultFilter.isNullOrBlank() -> {
                    db.tradeDao().deleteClosedTradesByResult(resultFilter)
                }
                else -> {
                    db.tradeDao().deleteAllClosedTrades()
                }
            }
            AppLogManager.i("DB_MGR", "Cleared $deleted closed trade records (Filter: ${olderThanDays ?: "ALL"}d, Result: ${resultFilter ?: "ALL"}). Active positions preserved.")
            deleted
        } catch (e: Exception) {
            AppLogManager.e("DB_MGR", "Failed clearing closed trades: ${e.message}", e)
            0
        }
    }

    suspend fun clearFinalizedOrders(olderThanDays: Int? = null): Int = withContext(Dispatchers.IO) {
        try {
            val deleted = if (olderThanDays != null && olderThanDays > 0) {
                val cutoff = System.currentTimeMillis() - (olderThanDays * 86_400_000L)
                db.orderDao().deleteFinalizedOrdersOlderThan(cutoff)
            } else {
                db.orderDao().deleteAllFinalizedOrders()
            }
            AppLogManager.i("DB_MGR", "Cleared $deleted finalized order records. Working/Pending orders preserved.")
            deleted
        } catch (e: Exception) {
            AppLogManager.e("DB_MGR", "Failed clearing finalized orders: ${e.message}", e)
            0
        }
    }

    suspend fun clearSystemLogs(olderThanDays: Int? = null): Int = withContext(Dispatchers.IO) {
        try {
            val deleted = if (olderThanDays != null && olderThanDays > 0) {
                val cutoff = System.currentTimeMillis() - (olderThanDays * 86_400_000L)
                val beforeCount = db.systemLogDao().getLogsCount()
                db.systemLogDao().pruneOldLogs(cutoff)
                val afterCount = db.systemLogDao().getLogsCount()
                (beforeCount - afterCount).coerceAtLeast(0)
            } else {
                db.systemLogDao().deleteAllLogs()
            }
            AppLogManager.i("DB_MGR", "Cleared $deleted system log records from database.")
            deleted
        } catch (e: Exception) {
            AppLogManager.e("DB_MGR", "Failed clearing system logs: ${e.message}", e)
            0
        }
    }

    suspend fun clearHistoricalEquitySnapshots(currentSessionId: String): Int = withContext(Dispatchers.IO) {
        try {
            val deleted = db.equitySnapshotDao().clearHistoricalSessions(currentSessionId)
            AppLogManager.i("DB_MGR", "Cleared $deleted historical equity snapshots (Current session $currentSessionId preserved).")
            deleted
        } catch (e: Exception) {
            AppLogManager.e("DB_MGR", "Failed clearing historical equity snapshots: ${e.message}", e)
            0
        }
    }

    suspend fun purgeAllHistoricalData(currentSessionId: String): PurgeSummary = withContext(Dispatchers.IO) {
        val deletedTrades = clearClosedTrades()
        val deletedOrders = clearFinalizedOrders()
        val deletedLogs = clearSystemLogs()
        val deletedSnapshots = clearHistoricalEquitySnapshots(currentSessionId)

        val summary = PurgeSummary(
            deletedTradesCount = deletedTrades,
            deletedOrdersCount = deletedOrders,
            deletedLogsCount = deletedLogs,
            deletedSnapshotsCount = deletedSnapshots
        )
        AppLogManager.i("DB_MGR", "Master Historical Purge Completed: ${summary.totalDeleted} records removed. All open positions and pending orders active and unharmed.")
        summary
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val df = DecimalFormat("#,##0.0")
        return when {
            bytes >= 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024.0))} MB"
            bytes >= 1024 -> "${df.format(bytes / 1024.0)} KB"
            else -> "$bytes B"
        }
    }
}
