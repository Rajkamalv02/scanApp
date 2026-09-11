package com.coindcx.trading

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.scanner.HtfAlignment
import com.coindcx.trading.engine.scanner.QualityCategory
import com.coindcx.trading.engine.scanner.TradeQualityScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun testHtfAligned_ApprovesWithStandardScore() {
        // HTF candles with close at 100.0 (EMA ~ 100)
        // Current price 105.0 -> ALIGNED (+25 pts) for BUY
        val htfCandles = List(60) { createCandle(100.0, 101.0, 99.0, 100.0, 1000.0) }
        val candles = List(60) { createCandle(105.0, 106.0, 104.0, 105.0, 1000.0) }
        val signal = Signal(
            action = SignalAction.ENTER_LONG,
            stopLossPrice = 100.0,
            takeProfitPrice = 115.0, // Net R:R >= 1.5
            confidenceScore = 75.0   // 20 pts
        )

        val assessment = scorer.evaluateQuality(
            candles = candles,
            htfCandles = htfCandles,
            signal = signal,
            currentPrice = 105.0,
            pair = "B-BTC_USDT"
        )

        assertEquals(HtfAlignment.ALIGNED, assessment.htfAlignment)
        assertEquals(25, assessment.htfScore)
        assertTrue(assessment.passedSecondaryGate)
        // Non-HTF points: Confluence (20) + Volume (5) + R:R (6) + Extension (10) + Regime (0) = 41 pts
        // Total = 25 + 41 = 66 (Reject).
        // Now if confidence is 80 and R:R >= 2.0:
        val signalHigh = Signal(
            action = SignalAction.ENTER_LONG,
            stopLossPrice = 100.0,
            takeProfitPrice = 120.0, // Net R:R >= 2.0 (10 pts)
            confidenceScore = 80.0   // 20 pts
        )
        val approvedAssessment = scorer.evaluateQuality(
            candles = candles,
            htfCandles = htfCandles,
            signal = signalHigh,
            currentPrice = 105.0,
            pair = "B-BTC_USDT"
        )
        // Non-HTF: 20 + 5 + 10 + 10 = 45 pts. Total = 25 + 45 = 70.
        assertEquals(70, approvedAssessment.totalScore)
        assertTrue(approvedAssessment.isApproved)
    }

    @Test
    fun testHtfNeutral_RequiresHigherNonHtfScore() {
        // No HTF candles provided -> NEUTRAL (+10 pts)
        val candles = List(60) { createCandle(105.0, 106.0, 104.0, 105.0, 1000.0) }
        val signal = Signal(
            action = SignalAction.ENTER_LONG,
            stopLossPrice = 100.0,
            takeProfitPrice = 120.0, // Net R:R >= 2.0 (10 pts)
            confidenceScore = 80.0   // 20 pts
        )

        val assessment = scorer.evaluateQuality(
            candles = candles,
            htfCandles = emptyList(), // Insufficient HTF -> NEUTRAL (+10 pts)
            signal = signal,
            currentPrice = 105.0,
            pair = "B-BTC_USDT"
        )

        assertEquals(HtfAlignment.NEUTRAL, assessment.htfAlignment)
        assertEquals(10, assessment.htfScore)
        assertTrue(assessment.passedSecondaryGate)
        // Non-HTF is 45 pts. Total = 10 + 45 = 55 (< 70 -> Rejected)
        assertFalse(assessment.isApproved)
    }

    @Test
    fun testHtfMisaligned_RejectedWhenSecondaryGateFails() {
        // HTF candles with price at 120.0 (EMA ~ 120)
        // Current price 100.0 -> MISALIGNED (0 pts) for BUY
        val htfCandles = List(60) { createCandle(120.0, 121.0, 119.0, 120.0, 1000.0) }
        val candles = List(60) { createCandle(100.0, 101.0, 99.0, 100.0, 1000.0) }
        val signal = Signal(
            action = SignalAction.ENTER_LONG,
            stopLossPrice = 96.0,
            takeProfitPrice = 110.0, // R:R >= 2.0
            confidenceScore = 80.0
        )

        val assessment = scorer.evaluateQuality(
            candles = candles,
            htfCandles = htfCandles,
            signal = signal,
            currentPrice = 100.0,
            pair = "B-BTC_USDT"
        )

        assertEquals(HtfAlignment.MISALIGNED, assessment.htfAlignment)
        assertEquals(0, assessment.htfScore)
        // Non-HTF score is < 70 and RelVol is not >= 1.3x -> secondary gate fails!
        assertFalse(assessment.passedSecondaryGate)
        assertFalse(assessment.isApproved)
        assertTrue(assessment.rejectionReason?.contains("Counter-trend setup rejected") == true)
    }

    @Test
    fun testHtfMisaligned_ApprovedWhenEliteConfluenceMet() {
        // Strong trending candles to generate ADX >= 25 (20 pts), followed by brief consolidation to keep extension <= 1.2 ATR (10 pts)
        val trendCandles = mutableListOf<MarketCandle>()
        var price = 50.0
        for (i in 0 until 50) {
            price += 1.5
            trendCandles.add(MarketCandle(price - 1.0, price + 3.0, price - 1.0, price, 1000.0, System.currentTimeMillis() + i * 60000L))
        }
        for (i in 50 until 60) {
            val vol = if (i == 59) 2000.0 else 1000.0 // RelVol = 2.0x >= 1.3x (15 pts)
            trendCandles.add(MarketCandle(price - 1.0, price + 3.0, price - 1.0, price, vol, System.currentTimeMillis() + i * 60000L))
        }

        // HTF candles well above current price to force MISALIGNED
        val currentPrice = trendCandles.last().close
        val htfCandles = List(60) { createCandle(currentPrice + 50.0, currentPrice + 52.0, currentPrice + 48.0, currentPrice + 50.0, 1000.0) }

        val signal = Signal(
            action = SignalAction.ENTER_LONG,
            stopLossPrice = currentPrice - 2.0,
            takeProfitPrice = currentPrice + 6.0, // Net R:R >= 2.0 (10 pts)
            confidenceScore = 90.0               // Confluence >= 75 (20 pts)
        )

        val assessment = scorer.evaluateQuality(
            candles = trendCandles,
            htfCandles = htfCandles,
            signal = signal,
            currentPrice = currentPrice,
            pair = "B-BTC_USDT"
        )

        assertEquals(HtfAlignment.MISALIGNED, assessment.htfAlignment)
        assertEquals(0, assessment.htfScore)
        assertTrue("Relative volume must be >= 1.3x", assessment.relativeVolume >= 1.3)
        assertTrue("Net R:R must be >= 2.0", assessment.netRiskRewardRatio >= 2.0)
        assertTrue("Non-HTF score should be elite (>=70)", assessment.nonHtfScore >= 70)
        assertTrue("Secondary gate must pass", assessment.passedSecondaryGate)
        assertTrue("Overall trade must be approved", assessment.isApproved)
        assertEquals(QualityCategory.ACCEPTABLE, assessment.category)
    }
}
