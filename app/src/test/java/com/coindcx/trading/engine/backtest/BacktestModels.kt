package com.coindcx.trading.engine.backtest

import com.coindcx.trading.engine.SignalDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ExitReason {
    STOP_LOSS,
    TAKE_PROFIT,
    TRAILING_STOP,
    EXPIRY_BARS,
    OPPOSITE_SIGNAL,
    END_OF_DATA
}

data class BacktestTrade(
    val symbol: String,
    val strategyId: String,
    val direction: SignalDirection,
    val entryBarTime: Long,
    val entryPrice: Double,
    val exitBarTime: Long,
    val exitPrice: Double,
    val exitReason: ExitReason,
    val initialRisk: Double,
    val rawPnlPct: Double,
    val netPnlPct: Double,
    val rMultiple: Double,
    val barsHeld: Int
)

data class BacktestSummary(
    val strategyId: String,
    val totalBars: Int,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRatePct: Double,
    val profitFactor: Double,
    val totalReturnPct: Double,
    val maxDrawdownPct: Double,
    val sharpeRatio: Double,
    val calmarRatio: Double,
    val systemQualityNumber: Double,
    val avgBarsHeld: Double,
    val avgTradePnlPct: Double,
    val trades: List<BacktestTrade>
) {
    companion object {
        fun calculate(
            strategyId: String,
            totalBars: Int,
            trades: List<BacktestTrade>,
            barsPerYear: Double = 365.25 * 24 * 4 // 15m default: 35,064 bars/yr
        ): BacktestSummary {
            if (trades.isEmpty()) {
                return BacktestSummary(
                    strategyId = strategyId,
                    totalBars = totalBars,
                    totalTrades = 0,
                    winningTrades = 0,
                    losingTrades = 0,
                    winRatePct = 0.0,
                    profitFactor = 0.0,
                    totalReturnPct = 0.0,
                    maxDrawdownPct = 0.0,
                    sharpeRatio = 0.0,
                    calmarRatio = 0.0,
                    systemQualityNumber = 0.0,
                    avgBarsHeld = 0.0,
                    avgTradePnlPct = 0.0,
                    trades = emptyList()
                )
            }

            val wins = trades.filter { it.netPnlPct > 0 }
            val losses = trades.filter { it.netPnlPct <= 0 }
            val winRate = (wins.size.toDouble() / trades.size) * 100.0

            val grossProfit = wins.sumOf { it.netPnlPct }
            val grossLoss = losses.sumOf { -it.netPnlPct }
            val profitFactor = if (grossLoss > 0.0) grossProfit / grossLoss else if (grossProfit > 0) 99.99 else 0.0

            // Compounded equity curve & Drawdown
            var equity = 1.0
            var peak = 1.0
            var maxDd = 0.0

            val pnlReturns = DoubleArray(trades.size)
            val rMultiples = DoubleArray(trades.size)

            for (i in trades.indices) {
                val t = trades[i]
                val ret = t.netPnlPct / 100.0
                equity *= (1.0 + ret)
                if (equity > peak) peak = equity
                val dd = (peak - equity) / peak
                if (dd > maxDd) maxDd = dd
                pnlReturns[i] = ret
                rMultiples[i] = t.rMultiple
            }

            val totalReturnPct = (equity - 1.0) * 100.0
            val maxDrawdownPct = maxDd * 100.0

            // Sharpe & SQN
            val meanR = rMultiples.average()
            var varR = 0.0
            for (r in rMultiples) {
                val d = r - meanR
                varR += d * d
            }
            val stdevR = if (rMultiples.size > 1) sqrt(varR / (rMultiples.size - 1)) else 0.0
            val sqn = if (stdevR > 0.0) sqrt(trades.size.toDouble()) * (meanR / stdevR) else 0.0

            // Trade Sharpe annualized
            val meanRet = pnlReturns.average()
            var varRet = 0.0
            for (ret in pnlReturns) {
                val d = ret - meanRet
                varRet += d * d
            }
            val stdevRet = if (pnlReturns.size > 1) sqrt(varRet / (pnlReturns.size - 1)) else 0.0
            val tradesPerYear = if (totalBars > 0) trades.size.toDouble() / (totalBars / barsPerYear) else trades.size.toDouble()
            val sharpe = if (stdevRet > 0.0) (meanRet / stdevRet) * sqrt(tradesPerYear) else 0.0

            val years = if (totalBars > 0) totalBars / barsPerYear else 1.0
            val cagrPct = if (years > 0 && equity > 0) ((Math.pow(equity, 1.0 / years) - 1.0) * 100.0) else totalReturnPct
            val calmar = if (maxDrawdownPct > 0.0) cagrPct / maxDrawdownPct else 0.0

            val avgBars = trades.map { it.barsHeld }.average()
            val avgPnl = trades.map { it.netPnlPct }.average()

            return BacktestSummary(
                strategyId = strategyId,
                totalBars = totalBars,
                totalTrades = trades.size,
                winningTrades = wins.size,
                losingTrades = losses.size,
                winRatePct = winRate,
                profitFactor = profitFactor,
                totalReturnPct = totalReturnPct,
                maxDrawdownPct = maxDrawdownPct,
                sharpeRatio = sharpe,
                calmarRatio = calmar,
                systemQualityNumber = sqn,
                avgBarsHeld = avgBars,
                avgTradePnlPct = avgPnl,
                trades = trades
            )
        }
    }
}
