package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.SignalAction
import org.junit.Assert.*
import org.junit.Test

class EmaCrossoverStrategyTest {

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

    private fun createPosition(pair: String, isLong: Boolean, avgPrice: Double = 100.0): FuturesPosition {
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
            takeProfitTrigger = null,
            stopLossTrigger = null,
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
        val strategy = EmaCrossoverStrategy(initialFastPeriod = 9, initialSlowPeriod = 21)
        val candles = (1..30).map { i ->
            createCandle(i * 900000L, 100.0, 102.0, 98.0, 100.0)
        }

        val signal = strategy.evaluate(candles, null)
        assertEquals(SignalAction.HOLD, signal.action)
        assertTrue(signal.reason.contains("Insufficient candles"))
    }

    @Test
    fun testBullishCrossoverGeneratesEnterLong() {
        val strategy = EmaCrossoverStrategy(initialFastPeriod = 3, initialSlowPeriod = 5, initialAtrMultiplier = 1.5)
        // Required count is max(5*3, 50) = 50
        val baseTime = 1000000L
        val candles = mutableListOf<MarketCandle>()

        // 1. First 50 bars in a steady downtrend so Fast EMA is below Slow EMA
        var price = 200.0
        for (i in 1..50) {
            price -= 1.0
            candles.add(createCandle(baseTime + i * 900000L, price + 0.5, price + 1.0, price - 1.0, price))
        }

        // 2. Bar 51 flat
        candles.add(createCandle(baseTime + 51 * 900000L, price, price + 1.0, price - 1.0, price))

        // 3. Bar 52 flat
        candles.add(createCandle(baseTime + 52 * 900000L, price, price + 1.0, price - 1.0, price))

        // 4. Bar 53 sharp upward surge (Completed Bar t-1) causing Fast EMA to cross ABOVE Slow EMA
        val surgePrice = price + 40.0
        candles.add(createCandle(baseTime + 53 * 900000L, price, surgePrice + 2.0, price - 0.5, surgePrice))

        // 5. Bar 54 Live Forming Bar (t)
        candles.add(createCandle(baseTime + 54 * 900000L, surgePrice, surgePrice + 1.0, surgePrice - 1.0, surgePrice))

        val signal = strategy.evaluate(candles, null, "B-BTC_USDT")
        assertEquals(SignalAction.ENTER_LONG, signal.action)
        assertNotNull(signal.stopLossPrice)
        assertNotNull(signal.takeProfitPrice)
        assertTrue("SL must be below entry", signal.stopLossPrice!! < surgePrice)
        assertTrue("TP must be above entry", signal.takeProfitPrice!! > surgePrice)
        assertTrue("Confidence score should be 80.0", signal.confidenceScore >= 75.0)

        // Mathematical Transparency Verification
        assertNotNull("tradeId must be generated", signal.tradeId)
        assertTrue("tradeId must contain symbol", signal.tradeId!!.contains("BTCUSDT"))
        assertEquals(surgePrice, signal.entryPrice, 0.001)
        assertTrue("Current Fast EMA must be > Slow EMA on bullish cross", signal.fastEma > signal.slowEma)
        assertTrue("Prev Fast EMA must be <= Slow EMA", signal.prevFastEma <= signal.prevSlowEma)
        assertTrue("ATR must be positive", signal.atr > 0.0)
        assertEquals(1.5, signal.atrMultiplier, 0.001)
        assertTrue("Risk distance must be positive", signal.riskDistance > 0.0)
        assertEquals(2.0, signal.riskRewardRatio, 0.001)
    }

    @Test
    fun testBearishCrossoverGeneratesEnterShort() {
        val strategy = EmaCrossoverStrategy(initialFastPeriod = 3, initialSlowPeriod = 5, initialAtrMultiplier = 1.5)
        val baseTime = 1000000L
        val candles = mutableListOf<MarketCandle>()

        // 1. First 50 bars in a steady uptrend so Fast EMA is above Slow EMA
        var price = 100.0
        for (i in 1..50) {
            price += 1.0
            candles.add(createCandle(baseTime + i * 900000L, price - 0.5, price + 1.0, price - 1.0, price))
        }

        // 2. Bar 51 flat
        candles.add(createCandle(baseTime + 51 * 900000L, price, price + 1.0, price - 1.0, price))

        // 3. Bar 52 flat
        candles.add(createCandle(baseTime + 52 * 900000L, price, price + 1.0, price - 1.0, price))

        // 4. Bar 53 sharp downward drop (Completed Bar t-1) causing Fast EMA to cross BELOW Slow EMA
        val dropPrice = price - 40.0
        candles.add(createCandle(baseTime + 53 * 900000L, price, price + 0.5, dropPrice - 2.0, dropPrice))

        // 5. Bar 54 Live Forming Bar (t)
        candles.add(createCandle(baseTime + 54 * 900000L, dropPrice, dropPrice + 1.0, dropPrice - 1.0, dropPrice))

        val signal = strategy.evaluate(candles, null)
        assertEquals(SignalAction.ENTER_SHORT, signal.action)
        assertNotNull(signal.stopLossPrice)
        assertNotNull(signal.takeProfitPrice)
        assertTrue("SL must be above entry", signal.stopLossPrice!! > dropPrice)
        assertTrue("TP must be below entry", signal.takeProfitPrice!! < dropPrice)
    }

    @Test
    fun testActiveLongPositionExitsOnBearishCrossover() {
        val strategy = EmaCrossoverStrategy(initialFastPeriod = 3, initialSlowPeriod = 5)
        val baseTime = 1000000L
        val candles = mutableListOf<MarketCandle>()

        var price = 100.0
        for (i in 1..50) {
            price += 1.0
            candles.add(createCandle(baseTime + i * 900000L, price - 0.5, price + 1.0, price - 1.0, price))
        }

        // Bearish cross at bar 51
        val dropPrice = price - 40.0
        candles.add(createCandle(baseTime + 51 * 900000L, price, price + 0.5, dropPrice - 2.0, dropPrice))
        candles.add(createCandle(baseTime + 52 * 900000L, dropPrice, dropPrice + 1.0, dropPrice - 1.0, dropPrice))

        val longPos = createPosition("B-BTC_USDT", isLong = true, avgPrice = 140.0)
        val signal = strategy.evaluate(candles, longPos)

        assertEquals(SignalAction.EXIT, signal.action)
        assertTrue(signal.reason.contains("Bearish EMA Crossover"))
    }

    @Test
    fun testActiveShortPositionExitsOnBullishCrossover() {
        val strategy = EmaCrossoverStrategy(initialFastPeriod = 3, initialSlowPeriod = 5)
        val baseTime = 1000000L
        val candles = mutableListOf<MarketCandle>()

        var price = 200.0
        for (i in 1..50) {
            price -= 1.0
            candles.add(createCandle(baseTime + i * 900000L, price + 0.5, price + 1.0, price - 1.0, price))
        }

        // Bullish cross at bar 51
        val surgePrice = price + 40.0
        candles.add(createCandle(baseTime + 51 * 900000L, price, surgePrice + 2.0, price - 0.5, surgePrice))
        candles.add(createCandle(baseTime + 52 * 900000L, surgePrice, surgePrice + 1.0, surgePrice - 1.0, surgePrice))

        val shortPos = createPosition("B-BTC_USDT", isLong = false, avgPrice = 160.0)
        val signal = strategy.evaluate(candles, shortPos)

        assertEquals(SignalAction.EXIT, signal.action)
        assertTrue(signal.reason.contains("Bullish EMA Crossover"))
    }

    @Test
    fun testDynamicReconfiguration() {
        val strategy = EmaCrossoverStrategy(initialFastPeriod = 9, initialSlowPeriod = 21, initialAtrMultiplier = 1.5)
        assertEquals(9, strategy.fastPeriod)
        assertEquals(21, strategy.slowPeriod)
        assertEquals(1.5, strategy.atrMultiplier, 0.001)

        strategy.configure(fast = 12, slow = 26, atrMult = 2.0)
        assertEquals(12, strategy.fastPeriod)
        assertEquals(26, strategy.slowPeriod)
        assertEquals(2.0, strategy.atrMultiplier, 0.001)
    }

    @Test
    fun testFlatOrTrendContinuationReturnsHold() {
        val strategy = EmaCrossoverStrategy(initialFastPeriod = 3, initialSlowPeriod = 5)
        val baseTime = 1000000L
        val candles = mutableListOf<MarketCandle>()

        // 55 flat bars -> Fast EMA == Slow EMA, no cross
        for (i in 1..55) {
            candles.add(createCandle(baseTime + i * 900000L, 100.0, 101.0, 99.0, 100.0))
        }

        val signal = strategy.evaluate(candles, null)
        assertEquals(SignalAction.HOLD, signal.action)
        assertTrue(signal.reason.contains("Awaiting fresh crossover"))
    }
}
