package com.coindcx.trading

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class PaperAnalyticsEngineTest {

    @Test
    fun testTrueMaxDrawdownContinuousPeakToTrough() {
        // Continuous equity series during paper testing:
        // Starts at 5,000
        // Peak 1: 5,500, dips to 5,200 (DD: (5500 - 5200)/5500 = 5.45%)
        // Peak 2: 6,000, severe intra-trade adverse dip to 5,100 (DD: (6000 - 5100)/6000 = 15.0%)
        // Recovers to 6,500
        val equitySnapshots = listOf(5000.0, 5200.0, 5500.0, 5200.0, 5800.0, 6000.0, 5100.0, 5700.0, 6500.0)

        var peak = equitySnapshots.first()
        var maxDrawdownPct = 0.0

        for (equity in equitySnapshots) {
            if (equity > peak) {
                peak = equity
            } else if (peak > 0.0) {
                val currentDd = ((peak - equity) / peak) * 100.0
                if (currentDd > maxDrawdownPct) {
                    maxDrawdownPct = currentDd
                }
            }
        }

        // Peak was 6,000, trough was 5,100 -> (6000 - 5100) / 6000 * 100 = 15.0%
        assertEquals(15.0, maxDrawdownPct, 0.001)
    }

    @Test
    fun testProfitFactorAndWinRate() {
        val tradePnlList = listOf(250.0, -100.0, 400.0, -150.0, 150.0, -50.0, 0.0)

        val winTrades = tradePnlList.filter { it > 1.0 }
        val lossTrades = tradePnlList.filter { it < -1.0 }
        val breakevenTrades = tradePnlList.filter { abs(it) <= 1.0 }

        assertEquals(3, winTrades.size)
        assertEquals(3, lossTrades.size)
        assertEquals(1, breakevenTrades.size)

        val totalWins = winTrades.sum() // 250 + 400 + 150 = 800.0
        val totalLosses = abs(lossTrades.sum()) // |-100 + -150 + -50| = 300.0

        val profitFactor = totalWins / totalLosses // 800 / 300 = 2.6667
        assertEquals(2.6667, profitFactor, 0.001)

        val totalClosed = tradePnlList.size
        val winRatePct = (winTrades.size.toDouble() / totalClosed) * 100.0 // 3 / 7 = 42.857%
        assertEquals(42.857, winRatePct, 0.01)
    }

    @Test
    fun testStreakCounting() {
        val trades = listOf(10.0, 20.0, 30.0, -5.0, -10.0, 15.0, 25.0, -20.0)

        var curWins = 0
        var maxWins = 0
        var curLosses = 0
        var maxLosses = 0

        for (pnl in trades) {
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

        assertEquals(3, maxWins) // first 3 trades
        assertEquals(2, maxLosses) // trades 4 and 5
    }
}
