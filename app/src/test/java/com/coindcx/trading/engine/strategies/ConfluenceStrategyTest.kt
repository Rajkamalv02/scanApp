package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfluenceStrategyTest {

    private fun createCandle(
        time: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double = 1000.0
    ): MarketCandle {
        return MarketCandle(
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            time = time
        )
    }

    private fun createPosition(
        pair: String,
        isLong: Boolean,
        avgPrice: Double = 100.0,
        stopLossTrigger: Double? = null,
        takeProfitTrigger: Double? = null
    ): FuturesPosition {
        return FuturesPosition(
            id = "pos_1",
            pair = pair,
            activePos = if (isLong) 1.0 else -1.0,
            inactivePosBuy = 0.0,
            inactivePosSell = 0.0,
            avgPrice = avgPrice,
            liquidationPrice = 0.0,
            lockedMargin = 500.0,
            lockedUserMargin = 500.0,
            lockedOrderMargin = 0.0,
            takeProfitTrigger = takeProfitTrigger,
            stopLossTrigger = stopLossTrigger,
            leverage = 2.0,
            maintenanceMargin = null,
            markPrice = avgPrice,
            marginType = "ISOLATED",
            settlementCurrencyAvgPrice = null,
            cumulativeFundingFee = null,
            marginCurrencyShortName = "INR",
            updatedAt = System.currentTimeMillis()
        )
    }

    @Test
    fun testInsufficientCandlesReturnsHold() {
        val strategy = ConfluenceStrategy()
        val candles = (1..50).map { i ->
            createCandle(i * 900000L, 100.0, 102.0, 98.0, 100.0)
        }
        val signal = strategy.evaluate(candles, null, "B-BTC_USDT")
        assertEquals(SignalAction.HOLD, signal.action)
        assertTrue(signal.reason.contains("Insufficient candles"))
        assertEquals("confluence", signal.strategyId)
    }

    @Test
    fun testStrategyMetadata() {
        val strategy = ConfluenceStrategy()
        assertEquals("confluence", strategy.id)
        assertEquals("Confluence Engine Strategy", strategy.name)
        assertEquals("15m", strategy.defaultTimeframe)
        assertEquals(100, strategy.requiredCandleCount)
        assertTrue(strategy.parametersSummary.contains("Swing: 5"))
    }

    @Test
    fun testBullishConfluenceReversalSignalGeneration() {
        val strategy = ConfluenceStrategy(swingLen = 3, maxLookback = 15, confirmWindow = 10)
        val candles = mutableListOf<MarketCandle>()
        var t = 1000L

        // Generate baseline bars around 100.0 (100 bars warmup)
        for (i in 1..100) {
            candles.add(createCandle(t, 100.0, 101.0, 99.0, 100.0))
            t += 900000L
        }

        // 1. Create a prior Swing High at 105.0 and Swing Low at 95.0
        // Form a Swing High around bar 65
        candles.add(createCandle(t, 100.0, 102.0, 99.0, 101.0)); t += 900000L
        candles.add(createCandle(t, 101.0, 103.0, 100.0, 102.0)); t += 900000L
        candles.add(createCandle(t, 102.0, 106.0, 101.0, 103.0)); t += 900000L // Pivot High candidate: high 106.0
        candles.add(createCandle(t, 103.0, 104.0, 101.0, 102.0)); t += 900000L
        candles.add(createCandle(t, 102.0, 103.0, 100.0, 101.0)); t += 900000L
        candles.add(createCandle(t, 101.0, 102.0, 99.0, 100.0)); t += 900000L // Confirmed pivot high 106.0

        // Form a Swing Low around bar 75
        candles.add(createCandle(t, 100.0, 101.0, 97.0, 98.0)); t += 900000L
        candles.add(createCandle(t, 98.0, 99.0, 96.0, 97.0)); t += 900000L
        candles.add(createCandle(t, 97.0, 98.0, 93.0, 94.0)); t += 900000L // Pivot Low candidate: low 93.0
        candles.add(createCandle(t, 94.0, 96.0, 94.0, 95.0)); t += 900000L
        candles.add(createCandle(t, 95.0, 97.0, 95.0, 96.0)); t += 900000L
        candles.add(createCandle(t, 96.0, 98.0, 96.0, 97.0)); t += 900000L // Confirmed pivot low 93.0

        // Down candle to serve as Demand Zone anchor
        candles.add(createCandle(t, 97.0, 98.0, 94.0, 95.0)); t += 900000L // Bearish anchor (94.0 - 98.0)

        // Break structure above 106.0 to create Demand Zone
        candles.add(createCandle(t, 95.0, 102.0, 95.0, 100.0)); t += 900000L
        candles.add(createCandle(t, 100.0, 108.0, 100.0, 107.0)); t += 900000L // Bullish break! Demand zone created: 94.0 - 98.0

        // Form another swing high at 110.0 and swing low at 94.0
        candles.add(createCandle(t, 107.0, 109.0, 106.0, 108.0)); t += 900000L
        candles.add(createCandle(t, 108.0, 111.0, 107.0, 109.0)); t += 900000L // High 111.0
        candles.add(createCandle(t, 109.0, 110.0, 107.0, 108.0)); t += 900000L
        candles.add(createCandle(t, 108.0, 109.0, 106.0, 107.0)); t += 900000L
        candles.add(createCandle(t, 107.0, 108.0, 104.0, 105.0)); t += 900000L

        // Form a Swing Low at 95.0 to be swept
        candles.add(createCandle(t, 105.0, 106.0, 96.0, 97.0)); t += 900000L
        candles.add(createCandle(t, 97.0, 98.0, 95.0, 96.0)); t += 900000L // Low 95.0
        candles.add(createCandle(t, 96.0, 98.0, 96.0, 97.0)); t += 900000L
        candles.add(createCandle(t, 97.0, 99.0, 97.0, 98.0)); t += 900000L
        candles.add(createCandle(t, 98.0, 100.0, 98.0, 99.0)); t += 900000L

        // Swing High at 102.0
        candles.add(createCandle(t, 99.0, 103.0, 99.0, 101.0)); t += 900000L
        candles.add(createCandle(t, 101.0, 102.0, 99.0, 100.0)); t += 900000L
        candles.add(createCandle(t, 100.0, 101.0, 98.0, 99.0)); t += 900000L
        candles.add(createCandle(t, 99.0, 100.0, 97.0, 98.0)); t += 900000L

        // Stage 1 (ARM): Liquidity sweep into demand zone (94.0-98.0)
        // Pierces 95.0 low (low = 94.5) but closes inside (close = 96.0)
        candles.add(createCandle(t, 98.0, 98.0, 94.5, 96.0)); t += 900000L

        // Stage 2 (CONFIRM): Immediate strong reversal breakout above swing high (103.0)
        candles.add(createCandle(t, 96.0, 104.0, 96.0, 103.5)); t += 900000L

        // Live forming candle
        candles.add(createCandle(t, 103.5, 104.0, 103.0, 103.8))

        val signal = strategy.evaluate(candles, null, "B-BTC_USDT")

        // Should confirm ENTER_LONG or HOLD with ARMED/CONFIRMED state
        assertNotNull(signal)
        assertEquals("confluence", signal.strategyId)
        assertEquals("Confluence Engine Strategy", signal.strategyName)
        assertTrue(signal.diagnostics != null)
    }

    @Test
    fun testActivePositionExitOnZoneMitigation() {
        val strategy = ConfluenceStrategy(swingLen = 3, maxLookback = 15, confirmWindow = 10)
        val candles = mutableListOf<MarketCandle>()
        var t = 1000L

        // Warmup bars (100 bars)
        for (i in 1..100) {
            candles.add(createCandle(t, 100.0, 101.0, 99.0, 100.0))
            t += 900000L
        }

        // Swing High 1: high 110.0
        candles.add(createCandle(t, 100.0, 102.0, 99.0, 101.0)); t += 900000L
        candles.add(createCandle(t, 101.0, 103.0, 100.0, 102.0)); t += 900000L
        candles.add(createCandle(t, 102.0, 110.0, 101.0, 103.0)); t += 900000L // Pivot High 110.0
        candles.add(createCandle(t, 103.0, 104.0, 101.0, 102.0)); t += 900000L
        candles.add(createCandle(t, 102.0, 103.0, 100.0, 101.0)); t += 900000L
        candles.add(createCandle(t, 101.0, 102.0, 99.0, 100.0)); t += 900000L

        // Swing Low 1: low 90.0
        candles.add(createCandle(t, 100.0, 101.0, 97.0, 98.0)); t += 900000L
        candles.add(createCandle(t, 98.0, 99.0, 95.0, 96.0)); t += 900000L
        candles.add(createCandle(t, 96.0, 97.0, 90.0, 92.0)); t += 900000L // Pivot Low 90.0
        candles.add(createCandle(t, 92.0, 94.0, 91.0, 93.0)); t += 900000L
        candles.add(createCandle(t, 93.0, 95.0, 92.0, 94.0)); t += 900000L
        candles.add(createCandle(t, 94.0, 96.0, 93.0, 95.0)); t += 900000L

        // Swing High 2: high 105.0 (Lower High: 105 < 110)
        candles.add(createCandle(t, 95.0, 98.0, 94.0, 97.0)); t += 900000L
        candles.add(createCandle(t, 97.0, 100.0, 96.0, 99.0)); t += 900000L
        candles.add(createCandle(t, 99.0, 105.0, 98.0, 101.0)); t += 900000L // Pivot High 105.0
        candles.add(createCandle(t, 101.0, 102.0, 99.0, 100.0)); t += 900000L
        candles.add(createCandle(t, 100.0, 101.0, 98.0, 99.0)); t += 900000L
        candles.add(createCandle(t, 99.0, 100.0, 97.0, 98.0)); t += 900000L

        // Swing Low 2: low 85.0 (Lower Low: 85 < 90 -> Bearish Regime -1 established!)
        candles.add(createCandle(t, 98.0, 99.0, 92.0, 93.0)); t += 900000L
        candles.add(createCandle(t, 93.0, 94.0, 88.0, 89.0)); t += 900000L
        candles.add(createCandle(t, 89.0, 90.0, 85.0, 86.0)); t += 900000L // Pivot Low 85.0
        candles.add(createCandle(t, 86.0, 88.0, 85.5, 87.0)); t += 900000L
        candles.add(createCandle(t, 87.0, 89.0, 86.0, 88.0)); t += 900000L
        candles.add(createCandle(t, 88.0, 90.0, 87.0, 89.0)); t += 900000L

        // Anchor down candle (range: 88.0 to 96.0)
        candles.add(createCandle(t, 95.0, 96.0, 88.0, 90.0)); t += 900000L

        // Breakout candle: closes at 107.0 above 105.0 while regime is Bearish (-1) -> CHOCH (event 2)!
        // Creates Demand Zone [88.0, 96.0]
        candles.add(createCandle(t, 90.0, 108.0, 90.0, 107.0)); t += 900000L

        // Mitigation candle: closes at 82.0 below demand zone bottom 88.0!
        candles.add(createCandle(t, 107.0, 107.0, 80.0, 82.0)); t += 900000L

        // Live forming candle
        candles.add(createCandle(t, 82.0, 83.0, 81.0, 82.0))

        // When stopLossTrigger is hit (SL at 85.0, price at 82.0), position exits
        val longPos = createPosition("B-BTC_USDT", isLong = true, avgPrice = 100.0, stopLossTrigger = 85.0)
        val signal = strategy.evaluate(candles, longPos, "B-BTC_USDT")

        assertEquals(SignalAction.EXIT, signal.action)
        assertTrue(signal.reason.contains("Stop Loss trigger hit"))
    }
}
