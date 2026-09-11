package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.StrategyRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyDemandBidirectionalStrategyTest {

    private val bidirectionalStrategy = SupplyDemandBidirectionalStrategy()
    private val longStrategy = SupplyDemandEngulfingMacdLongStrategy()
    private val shortStrategy = SupplyDemandEngulfingMacdStrategy()

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
    fun testStrategyRegistry_DefaultIsBidirectional() {
        val active = StrategyRegistry.activeStrategy
        assertEquals("supply_demand_bidirectional", active.id)
        assertEquals(3, StrategyRegistry.availableStrategies.size)
        assertTrue(StrategyRegistry.availableStrategies.any { it.id == "supply_demand_bidirectional" })
        assertTrue(StrategyRegistry.availableStrategies.any { it.id == "supply_demand_engulfing_macd_long" })
        assertTrue(StrategyRegistry.availableStrategies.any { it.id == "supply_demand_engulfing_macd" })
    }

    @Test
    fun testBidirectional_InsufficientCandles_ReturnsHold() {
        val candles = (1..50).map { i ->
            candle(100.0, 102.0, 99.0, 101.0, time = i.toLong() * 60000)
        }
        val signal = bidirectionalStrategy.evaluate(candles, null)
        assertEquals(SignalAction.HOLD, signal.action)
        assertTrue(signal.reason.contains("Insufficient candle history"))
    }

    @Test
    fun testBidirectional_LowAdxChopFilter_SuppressesSignals() {
        val candles = (1..225).map { i ->
            candle(100.0, 100.1, 99.9, 100.0, volume = 50.0, time = i.toLong() * 60000)
        }
        val signal = bidirectionalStrategy.evaluate(candles, null)
        assertEquals(SignalAction.HOLD, signal.action)
        assertTrue(signal.reason.contains("Market in low-volatility consolidation") || signal.reason.contains("ADX"))
    }

    @Test
    fun testBidirectional_ActiveLongPosition_RoutesToLongHandler() {
        val candles = ArrayList<MarketCandle>()
        var price = 200.0
        var time = 1000L

        for (i in 1..220) {
            price += 0.2
            candles.add(candle(price - 0.1, price + 0.3, price - 0.2, price, volume = 100.0, time = time))
            time += 60000
        }

        val longPos = createPosition("BTCUSDT", isLong = true)
        val signal = bidirectionalStrategy.evaluate(candles, longPos)
        assertTrue(signal.action == SignalAction.HOLD || signal.action == SignalAction.EXIT)
    }

    @Test
    fun testBidirectional_ActiveShortPosition_RoutesToShortHandler() {
        val candles = ArrayList<MarketCandle>()
        var price = 200.0
        var time = 1000L

        for (i in 1..220) {
            price -= 0.2
            candles.add(candle(price + 0.1, price + 0.3, price - 0.2, price, volume = 100.0, time = time))
            time += 60000
        }

        val shortPos = createPosition("BTCUSDT", isLong = false)
        val signal = bidirectionalStrategy.evaluate(candles, shortPos)
        assertTrue(signal.action == SignalAction.HOLD || signal.action == SignalAction.EXIT)
    }

    @Test
    fun testLongStrategy_MetadataAndParameters() {
        assertEquals("supply_demand_engulfing_macd_long", longStrategy.id)
        assertEquals("Supply & Demand Long", longStrategy.name)
        assertTrue(longStrategy.requiredCandleCount >= 200)
        assertTrue(longStrategy.parametersSummary.contains("Demand Low"))
    }

    @Test
    fun testBidirectional_MetadataAndParameters() {
        assertEquals("supply_demand_bidirectional", bidirectionalStrategy.id)
        assertTrue(bidirectionalStrategy.name.contains("Two-Way"))
        assertTrue(bidirectionalStrategy.parametersSummary.contains("Bidirectional"))
    }
}
