package com.coindcx.trading.engine.paper

import android.content.Context
import android.content.SharedPreferences
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.EquitySnapshotEntity
import com.coindcx.trading.data.db.entities.SystemLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PaperAccountSummary(
    val sessionId: String,
    val startingBalanceInr: Double,
    val availableBalanceInr: Double,
    val usedMarginInr: Double,
    val unrealizedPnlInr: Double,
    val totalEquityInr: Double,
    val totalReturnPct: Double,
    val openPositionsCount: Int,
    val closedTradesCount: Int
)

class PaperAccountManager(
    private val context: Context,
    private val db: AppDatabase
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("paper_trading_prefs", Context.MODE_PRIVATE)

    companion object {
        const val PREF_STARTING_BALANCE = "paper_starting_balance"
        const val PREF_SESSION_ID = "paper_session_id"
        const val DEFAULT_STARTING_BALANCE = 5000.0 // Default starting capital: ₹5,000
        const val DEFAULT_SESSION_ID = "session_1"

        val PRESET_BALANCES = listOf(1000.0, 2000.0, 3000.0, 5000.0, 10000.0)
    }

    fun getCurrentSessionId(): String {
        return prefs.getString(PREF_SESSION_ID, DEFAULT_SESSION_ID) ?: DEFAULT_SESSION_ID
    }

    fun getStartingBalanceInr(): Double {
        return prefs.getFloat(PREF_STARTING_BALANCE, DEFAULT_STARTING_BALANCE.toFloat()).toDouble()
    }

    suspend fun getAccountSummary(): PaperAccountSummary = withContext(Dispatchers.IO) {
        val sessionId = getCurrentSessionId()
        val startingBalance = getStartingBalanceInr()

        val allSessionTrades = db.tradeDao().getTradesForSession(sessionId)
        val openTrades = allSessionTrades.filter { it.status == "OPEN" }
        val closedTrades = allSessionTrades.filter { it.status == "CLOSED" }

        // Closed trades store realizedPnl as Net Realized PnL (Gross PnL - Fees - Funding)
        val netRealizedPnlClosed = closedTrades.sumOf { it.realizedPnl ?: 0.0 }

        // Open trades active margin, initial entry fees, and accrued funding
        val usedMargin = openTrades.sumOf { it.allocatedMarginInr }
        val openEntryFees = openTrades.sumOf { it.fees }
        val openFundingFees = openTrades.sumOf { it.fundingFees }
        val openGrossPnl = openTrades.sumOf { it.grossPnl ?: 0.0 }
        val openUnrealizedNetPnl = openGrossPnl - openEntryFees - openFundingFees

        // Formula: Available Cash = Starting Capital + Net Realized PnL - Locked Margin - Open Entry Fees - Open Funding
        val availableBalance = (startingBalance + netRealizedPnlClosed - usedMargin - openEntryFees - openFundingFees)
            .coerceAtLeast(0.0)

        // Total Equity = Available Cash + Locked Margin + Open Gross PnL
        // Mathematically equals: Starting Capital + Net Realized PnL + Open Net Unrealized PnL (Zero double counting!)
        val totalEquity = availableBalance + usedMargin + openGrossPnl

        val totalReturnPct = if (startingBalance > 0.0) {
            ((totalEquity - startingBalance) / startingBalance) * 100.0
        } else {
            0.0
        }

        PaperAccountSummary(
            sessionId = sessionId,
            startingBalanceInr = startingBalance,
            availableBalanceInr = availableBalance,
            usedMarginInr = usedMargin,
            unrealizedPnlInr = openUnrealizedNetPnl,
            totalEquityInr = totalEquity,
            totalReturnPct = totalReturnPct,
            openPositionsCount = openTrades.size,
            closedTradesCount = closedTrades.size
        )
    }

    suspend fun recordEquitySnapshot(): Unit = withContext(Dispatchers.IO) {
        val summary = getAccountSummary()
        db.equitySnapshotDao().insert(
            EquitySnapshotEntity(
                sessionId = summary.sessionId,
                timestamp = System.currentTimeMillis(),
                equityInr = summary.totalEquityInr,
                availableBalanceInr = summary.availableBalanceInr,
                unrealizedPnlInr = summary.unrealizedPnlInr
            )
        )
    }

    suspend fun resetAccount(newStartingBalanceInr: Double = DEFAULT_STARTING_BALANCE): String = withContext(Dispatchers.IO) {
        val currentSessionId = getCurrentSessionId()

        // 1. Close any currently open paper positions in the existing session
        val openTrades = db.tradeDao().getTradesForSession(currentSessionId).filter { it.status == "OPEN" }
        for (trade in openTrades) {
            val netRealized = (trade.unrealizedPnl ?: 0.0) - trade.fees - trade.fundingFees
            db.tradeDao().update(
                trade.copy(
                    status = "CLOSED",
                    exitPrice = trade.currentPrice ?: trade.entryPrice,
                    exitTime = System.currentTimeMillis(),
                    realizedPnl = netRealized,
                    exitReason = "Session Reset: Position Closed"
                )
            )
        }

        // 2. Create new session ID
        val newSessionId = "session_${System.currentTimeMillis()}"

        // 3. Persist new session preferences
        prefs.edit()
            .putString(PREF_SESSION_ID, newSessionId)
            .putFloat(PREF_STARTING_BALANCE, newStartingBalanceInr.toFloat())
            .apply()

        // 4. Record baseline equity snapshot
        db.equitySnapshotDao().insert(
            EquitySnapshotEntity(
                sessionId = newSessionId,
                timestamp = System.currentTimeMillis(),
                equityInr = newStartingBalanceInr,
                availableBalanceInr = newStartingBalanceInr,
                unrealizedPnlInr = 0.0
            )
        )

        db.systemLogDao().insert(
            SystemLogEntity(
                level = "INFO",
                tag = "PAPER_ACCOUNT",
                message = "Paper account reset. Session: $newSessionId. Starting Balance: ₹%.0f".format(newStartingBalanceInr)
            )
        )

        newSessionId
    }
}
