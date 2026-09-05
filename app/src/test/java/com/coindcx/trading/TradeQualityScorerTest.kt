package com.coindcx.trading

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.scanner.QualityCategory
import com.coindcx.trading.engine.scanner.TradeQualityScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeQualityScorerTest {

    private val scorer = TradeQualityScorer

    private fun createCandle(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double
    ): MarketCandle {
        return MarketCandle(
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            time = System.currentTimeMillis()
        )
    }

    @Test
    fun testNetRiskReward_FeeDeduction() {
        // Entry: 100,000, TP: 105,000 (+5%), SL: 98,000 (-2%)
        // Fee adjustment = 0.20% (0.002)
        // Gross Reward = 5.0%, Gross Risk = 2.0%
        // Net Reward = 5% - 0.2% = 4.8%
        // Net Risk = 2% + 0.2% = 2.2%
        // Net R:R = 4.8 / 2.2 = ~2.18x (>= 2.0 -> 20 pts)
        val netRr = scorer.calculateNetRiskReward(
            entryPrice = 100000.0,
            takeProfit = 105000.0,
            stopLoss = 98000.0
        )
        assertEquals(2.18, netRr, 0.05)
    }

    @Test
    fun testNetRiskReward_BelowGate_Rejection() {
        // Entry: 100,000, TP: 102,000 (+2%), SL: 98,000 (-2%)
        // Net Reward = 2% - 0.2% = 1.8%
        // Net Risk = 2% + 0.2% = 2.2%
        // Net R:R = 1.8 / 2.2 = 0.82x (< 1.5x minimum gate)
        val netRr = scorer.calculateNetRiskReward(
            entryPrice = 100000.0,
            takeProfit = 102000.0,
            stopLoss = 98000.0
        )
        assertTrue(netRr < 1.5)

        val candles = List(60) { createCandle(100.0, 105.0, 95.0, 100.0, 1000.0) }
        val signal = Signal(
            action = SignalAction.ENTER_LONG,
            stopLossPrice = 98000.0,
            takeProfitPrice = 102000.0,
            confidenceScore = 70.0
        )

        val assessment = scorer.evaluateQuality(
            candles = candles,
            htfCandles = candles,
            signal = signal,
            currentPrice = 100000.0,
            pair = "B-BTC_USDT"
        )

        assertEquals(QualityCategory.REJECT, assessment.category)
        assertTrue(assessment.rejectionReason?.contains("Net R:R") == true)
    }

    @Test
    fun testScoreClamping_ZeroToHundred() {
        val candles = List(60) { createCandle(100.0, 105.0, 95.0, 100.0, 1000.0) }
        val signal = Signal(
            action = SignalAction.ENTER_LONG,
            stopLossPrice = 98000.0,
            takeProfitPrice = 106000.0,
            confidenceScore = 95.0
        )

        val assessment = scorer.evaluateQuality(
            candles = candles,
            htfCandles = candles,
            signal = signal,
            currentPrice = 100000.0,
            pair = "B-BTC_USDT"
        )

        assertTrue(assessment.totalScore in 0..100)
    }
}
