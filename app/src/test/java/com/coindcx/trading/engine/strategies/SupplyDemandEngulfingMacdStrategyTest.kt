package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyDemandEngulfingMacdStrategyTest {

    private val strategy = SupplyDemandEngulfingMacdStrategy()

    private fun candle(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double = 100.0,
        time: Long = 0L
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

    private fun createPosition(pair: String, isLong: Boolean): FuturesPosition {
        return FuturesPosition(
            id = "pos_$pair",
            pair = pair,
            activePos = if (isLong) 1.0 else -1.0,
            inactivePosBuy = 0.0,
            inactivePosSell = 0.0,
            avgPrice = 100.0,
            liquidationPrice = 0.0,
            lockedMargin = 500.0,
            lockedUserMargin = 500.0,
            lockedOrderMargin = 0.0,
            takeProfitTrigger = null,
            stopLossTrigger = null,
            leverage = 2.0,
            maintenanceMargin = null,
            markPrice = 100.0,
            marginType = "ISOLATED",
            settlementCurrencyAvgPrice = null,
            cumulativeFundingFee = null,
            marginCurrencyShortName = "INR",
            updatedAt = System.currentTimeMillis()
        )
    }

    @Test
    fun testInsufficientCandles_ReturnsHold() {
        val candles = (1..50).map { i ->
            candle(100.0, 102.0, 99.0, 101.0, time = i.toLong() * 60000)
        }
        val signal = strategy.evaluate(candles, null)
        assertEquals(SignalAction.HOLD, signal.action)
        assertTrue(signal.reason.contains("Insufficient candle history"))
    }

    @Test
    fun testLowAdxChopFilter_SuppressesSignals() {
        // Build 225 completely flat candles (zero directional movement -> ADX will be near 0)
        val candles = (1..225).map { i ->
            candle(100.0, 100.1, 99.9, 100.0, volume = 50.0, time = i.toLong() * 60000)
        }
        val signal = strategy.evaluate(candles, null)
        assertEquals(SignalAction.HOLD, signal.action)
        assertTrue(signal.reason.contains("Market in low-volatility consolidation") || signal.reason.contains("ADX"))
    }

    @Test
    fun testActiveShortExit_OnReversalOrHold() {
        val candles = ArrayList<MarketCandle>()
        var price = 200.0
        var time = 1000L

        for (i in 1..220) {
            price -= 0.2
            candles.add(candle(price + 0.1, price + 0.3, price - 0.2, price, volume = 100.0, time = time))
            time += 60000
        }

        val position = createPosition("BTCUSDT", isLong = false)
        val signal = strategy.evaluate(candles, position)
        assertTrue(signal.action == SignalAction.HOLD || signal.action == SignalAction.EXIT)
    }

    @Test
    fun testStrategyRegistrationAndMetadata() {
        assertEquals("supply_demand_engulfing_macd", strategy.id)
        assertEquals("Supply & Demand Short", strategy.name)
        assertTrue(strategy.requiredCandleCount > 200)
        assertTrue(strategy.parametersSummary.contains("MACD"))
        assertTrue(strategy.parametersSummary.contains("ADX"))
    }

    @Test
    fun testVettedFilters_InsufficientRewardToRiskVeto() {
        // Construct market history: 215 candles drifting downward
        val candles = ArrayList<MarketCandle>()
        var price = 50000.0
        var time = 100000L

        for (i in 1..215) {
            price -= 5.0
            candles.add(candle(price + 5, price + 10, price - 10, price, volume = 200.0, time = time))
            time += 60000L
        }

        // Even if market conditions trigger, if R:R < 1.50, action must be HOLD and never an invalid entry
        val signal = strategy.evaluate(candles, null)
        assertTrue(signal.action == SignalAction.HOLD)
    }

    @Test
    fun testVettedFilters_ExtremeExtensionDoesNotShort() {
        // Price extended > 2.5x ATR far below any supply zone
        val candles = ArrayList<MarketCandle>()
        var price = 60000.0
        var time = 100000L

        for (i in 1..220) {
            price -= 20.0
            candles.add(candle(price + 10, price + 15, price - 15, price, volume = 200.0, time = time))
            time += 60000L
        }

        val signal = strategy.evaluate(candles, null)
        assertTrue(signal.action == SignalAction.HOLD)
    }
}
