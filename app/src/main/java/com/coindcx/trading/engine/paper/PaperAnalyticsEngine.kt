package com.coindcx.trading.engine.paper

import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.TradeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class PaperAnalyticsReport(
    val sessionId: String,
    val totalTrades: Int,
    val winCount: Int,
    val lossCount: Int,
    val breakevenCount: Int,
    val winRatePct: Double,
    val profitFactor: Double,
    val maxDrawdownPct: Double,
    val avgWinInr: Double,
    val avgLossInr: Double,
    val avgDurationMinutes: Long,
    val maxConsecutiveWins: Int,
    val maxConsecutiveLosses: Int,
    val netProfitInr: Double
)

class PaperAnalyticsEngine(
    private val db: AppDatabase,
    private val accountManager: PaperAccountManager
) {
    suspend fun computeAnalytics(sessionId: String = accountManager.getCurrentSessionId()): PaperAnalyticsReport = withContext(Dispatchers.IO) {
        val closedTrades = db.tradeDao().getClosedTradesForSession(sessionId).sortedBy { it.exitTime ?: 0L }

        val winTrades = closedTrades.filter { (it.realizedPnl ?: 0.0) > 1.0 }
        val lossTrades = closedTrades.filter { (it.realizedPnl ?: 0.0) < -1.0 }
        val breakevenTrades = closedTrades.filter { abs(it.realizedPnl ?: 0.0) <= 1.0 }

        val totalTrades = closedTrades.size
        val winCount = winTrades.size
        val lossCount = lossTrades.size
        val breakevenCount = breakevenTrades.size

        val winRatePct = if (totalTrades > 0) (winCount.toDouble() / totalTrades) * 100.0 else 0.0

        val totalWinsInr = winTrades.sumOf { it.realizedPnl ?: 0.0 }
        val totalLossesInr = abs(lossTrades.sumOf { it.realizedPnl ?: 0.0 })
        val netProfitInr = closedTrades.sumOf { it.realizedPnl ?: 0.0 }

        val profitFactor = when {
            totalLossesInr > 0.0 -> totalWinsInr / totalLossesInr
            totalWinsInr > 0.0 -> 99.9 // All wins, no losses
            else -> 0.0
        }

        val avgWinInr = if (winCount > 0) totalWinsInr / winCount else 0.0
        val avgLossInr = if (lossCount > 0) totalLossesInr / lossCount else 0.0

        // True Max Drawdown computed continuous peak-to-trough from Equity Snapshots
        val snapshots = db.equitySnapshotDao().getSnapshotsForSession(sessionId)
        var peak = accountManager.getStartingBalanceInr()
        var maxDrawdownPct = 0.0

        for (snapshot in snapshots) {
            if (snapshot.equityInr > peak) {
                peak = snapshot.equityInr
            } else if (peak > 0.0) {
                val currentDd = ((peak - snapshot.equityInr) / peak) * 100.0
                if (currentDd > maxDrawdownPct) {
                    maxDrawdownPct = currentDd
                }
            }
        }

        // Average duration in minutes
        val durations = closedTrades.mapNotNull { it.durationMillis }
        val avgDurationMinutes = if (durations.isNotEmpty()) {
            (durations.average() / (60 * 1000)).toLong()
        } else {
            0L
        }

        // Consecutive wins / losses
        var curWins = 0
        var maxWins = 0
        var curLosses = 0
        var maxLosses = 0

        for (trade in closedTrades) {
            val pnl = trade.realizedPnl ?: 0.0
            if (pnl > 1.0) {
                curWins++
                curLosses = 0
                if (curWins > maxWins) maxWins = curWins
            } else if (pnl < -1.0) {
                curLosses++
                curWins = 0
                if (curLosses > maxLosses) maxLosses = curLosses
            } else {
                curWins = 0
                curLosses = 0
            }
        }

        PaperAnalyticsReport(
            sessionId = sessionId,
            totalTrades = totalTrades,
            winCount = winCount,
            lossCount = lossCount,
            breakevenCount = breakevenCount,
            winRatePct = winRatePct,
            profitFactor = profitFactor,
            maxDrawdownPct = maxDrawdownPct,
            avgWinInr = avgWinInr,
            avgLossInr = avgLossInr,
            avgDurationMinutes = avgDurationMinutes,
            maxConsecutiveWins = maxWins,
            maxConsecutiveLosses = maxLosses,
            netProfitInr = netProfitInr
        )
    }
}
