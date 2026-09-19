package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.SignalDirection
import com.coindcx.trading.engine.SymbolContext
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.state.CrossSectionalState
import com.coindcx.trading.engine.state.RelativeStrengthHolding
import com.coindcx.trading.engine.telemetry.RejectionCode
import com.coindcx.trading.engine.time.FixedClock
import org.junit.Assert.*
import org.junit.Test

class XrsStrategyTest {

    companion object {
        /**
         * Helper to build synthetic candle series with specified trend.
         */
        private fun buildSeries(
            symbol: String,
            count: Int,
            startPrice: Double,
            endPrice: Double,
            volatility: Double = 0.5
        ): CandleSeries {
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val baseTime = 1_700_000_000_000L
            val stepMs = Interval.H4.durationMs

            // bar count-1 is oldest (startPrice), bar 0 is newest (endPrice)
            val priceStep = if (count > 1) (endPrice - startPrice) / (count - 1) else 0.0

            for (step in 0 until count) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                val p = startPrice + (step * priceStep)
                open[idx] = p
                close[idx] = p + (if (step % 2 == 0) 0.1 else -0.1)
                high[idx] = maxOf(open[idx], close[idx]) + volatility
                low[idx] = minOf(open[idx], close[idx]) - volatility
                volume[idx] = 1000.0
            }

            return CandleSeries.fromArrays(symbol, Interval.H4, openTime, open, high, low, close, volume)
        }
    }

    @Test
    fun `test XrsStrategy rejects insufficient universe size`() {
        val count = 200
        val btcSeries = buildSeries("B-BTC_USDT", count, 40000.0, 44000.0)
        val btcCtx = SymbolContext("B-BTC_USDT", btcSeries, clock = FixedClock(1_800_000_000_000L))

        // Only 3 symbols provided when min is 5
        val universe = mapOf(
            "B-ETH_USDT" to SymbolContext("B-ETH_USDT", buildSeries("B-ETH_USDT", count, 2000.0, 2400.0)),
            "B-SOL_USDT" to SymbolContext("B-SOL_USDT", buildSeries("B-SOL_USDT", count, 100.0, 130.0)),
            "B-AVAX_USDT" to SymbolContext("B-AVAX_USDT", buildSeries("B-AVAX_USDT", count, 30.0, 35.0))
        )

        val strategy = XrsStrategy(minUniverseSize = 5, lookbackBars = 180)
        val result = strategy.evaluate(universe, btcCtx, null)

        assertTrue(result.signals.isEmpty())
        for (sym in universe.keys) {
            assertTrue(
                "Symbol $sym should be rejected for universe size",
                result.symbolRejections[sym]?.contains(RejectionCode.S5_REGIME_INSUFFICIENT_UNIVERSE_SIZE) == true
            )
        }
    }

    @Test
    fun `test XrsStrategy rejects insufficient history on BTC benchmark`() {
        val count = 100 // Required is 181
        val btcSeries = buildSeries("B-BTC_USDT", count, 40000.0, 44000.0)
        val btcCtx = SymbolContext("B-BTC_USDT", btcSeries)

        val universe = (1..6).associate { i ->
            val sym = "B-SYM${i}_USDT"
            sym to SymbolContext(sym, buildSeries(sym, 200, 10.0, 15.0))
        }

        val strategy = XrsStrategy(minUniverseSize = 5, lookbackBars = 180)
        val result = strategy.evaluate(universe, btcCtx, null)

        assertTrue(result.signals.isEmpty())
        for (sym in universe.keys) {
            assertTrue(result.symbolRejections[sym]?.contains(RejectionCode.S5_REGIME_BTC_INSUFFICIENT_HISTORY) == true)
        }
    }

    @Test
    fun `test XrsStrategy generates Long for Alpha leader and Short for Alpha laggard`() {
        val count = 200
        val lookback = 180
        // BTC gains 10% (40000 -> 44000)
        val btcSeries = buildSeries("B-BTC_USDT", count, 40000.0, 44000.0)
        val btcCtx = SymbolContext("B-BTC_USDT", btcSeries)

        // Universe of 10 symbols with varying returns
        // S1 (Leader): +50% gain -> huge alpha
        // S2..S9: moderate returns from +20% down to -5%
        // S10 (Laggard): -40% loss -> massive negative alpha
        val returns = listOf(
            0.50,  // S1 (Leader) -> Top Decile (Long)
            0.25,  // S2
            0.20,  // S3
            0.15,  // S4
            0.10,  // S5
            0.05,  // S6
            0.00,  // S7
            -0.05, // S8
            -0.15, // S9
            -0.40  // S10 (Laggard) -> Bottom Decile (Short)
        )

        val universe = returns.mapIndexed { idx, ret ->
            val sym = "B-SYM${idx + 1}_USDT"
            val startPrice = 100.0
            val endPrice = startPrice * (1.0 + ret)
            sym to SymbolContext(sym, buildSeries(sym, count, startPrice, endPrice, volatility = 1.0))
        }.toMap()

        val strategy = XrsStrategy(
            lookbackBars = lookback,
            minUniverseSize = 10,
            topPercentileThreshold = 90.0,
            bottomPercentileThreshold = 10.0,
            maxLongPositions = 2,
            maxShortPositions = 2
        )

        val result = strategy.evaluate(universe, btcCtx, null)

        assertEquals("Should produce 2 signals: 1 Long, 1 Short", 2, result.signals.size)

        val longSignal = result.signals.firstOrNull { it.direction == SignalDirection.LONG }
        assertNotNull("Must produce Long signal for alpha leader", longSignal)
        assertEquals("B-SYM1_USDT", longSignal!!.symbol)
        assertTrue("Long stop must be below entry", longSignal.stopLoss < longSignal.entryRef)
        val longTarget = longSignal.target as Target.Fixed
        assertTrue("Long target must be above entry", longTarget.tp1 > longSignal.entryRef)
        for ((k, v) in longSignal.strengths) {
            assertTrue("Strength '$k' ($v) must be normalized in 0..1", v in 0.0..1.0)
        }

        val shortSignal = result.signals.firstOrNull { it.direction == SignalDirection.SHORT }
        assertNotNull("Must produce Short signal for alpha laggard", shortSignal)
        assertEquals("B-SYM10_USDT", shortSignal!!.symbol)
        assertTrue("Short stop must be above entry", shortSignal.stopLoss > shortSignal.entryRef)
        val shortTarget = shortSignal.target as Target.Fixed
        assertTrue("Short target must be below entry", shortTarget.tp1 < shortSignal.entryRef)
        for ((k, v) in shortSignal.strengths) {
            assertTrue("Strength '$k' ($v) must be normalized in 0..1", v in 0.0..1.0)
        }

        // Verify updated state has recorded active holdings
        val state = result.newState as? CrossSectionalState
        assertNotNull(state)
        assertEquals(2, state!!.activeHoldings.size)
        assertTrue(state.activeHoldings.containsKey("B-SYM1_USDT"))
        assertTrue(state.activeHoldings.containsKey("B-SYM10_USDT"))
    }

    @Test
    fun `test XrsStrategy hysteresis retains active Long above 70th percentile and prevents duplicate entry`() {
        val count = 200
        val lookback = 180
        val btcSeries = buildSeries("B-BTC_USDT", count, 40000.0, 44000.0)
        val btcCtx = SymbolContext("B-BTC_USDT", btcSeries)

        // 10 symbols: S1 had previously entered Long
        // Now S1 is at rank 2 (percentile = 77.7% >= 70.0% retention threshold, but < 90.0% entry threshold)
        val returns = listOf(
            0.60,  // S0: new top leader (rank 0, 100%)
            0.40,  // S1: active holding (rank 1, 88.8% -> retains!)
            0.20,
            0.15,
            0.10,
            0.05,
            0.00,
            -0.05,
            -0.10,
            -0.30
        )

        val universe = returns.mapIndexed { idx, ret ->
            val sym = "B-SYM${idx}_USDT"
            val end = 100.0 * (1.0 + ret)
            sym to SymbolContext(sym, buildSeries(sym, count, 100.0, end))
        }.toMap()

        val existingHolding = RelativeStrengthHolding(
            symbol = "B-SYM1_USDT",
            direction = SignalDirection.LONG,
            entryBarOpenTime = 1_700_000_000_000L,
            alphaRankPct = 95.0
        )
        val prevState = CrossSectionalState(
            activeHoldings = mapOf("B-SYM1_USDT" to existingHolding),
            lastUpdatedBarOpenTime = 1_700_000_000_000L
        )

        val strategy = XrsStrategy(
            lookbackBars = lookback,
            minUniverseSize = 10,
            topPercentileThreshold = 90.0,
            longRetentionThreshold = 70.0,
            maxLongPositions = 3
        )

        val result = strategy.evaluate(universe, btcCtx, prevState)

        // S0 should generate NEW Long signal. S1 should NOT generate duplicate signal because it's retained.
        assertEquals(1, result.signals.filter { it.direction == SignalDirection.LONG }.size)
        assertEquals("B-SYM0_USDT", result.signals.first { it.direction == SignalDirection.LONG }.symbol)

        // S1 must still be retained in newState
        val newState = result.newState as? CrossSectionalState
        assertNotNull(newState)
        assertTrue("B-SYM1_USDT must be retained in active holdings", newState!!.activeHoldings.containsKey("B-SYM1_USDT"))
        assertTrue("B-SYM0_USDT must be added to active holdings", newState.activeHoldings.containsKey("B-SYM0_USDT"))

        // Rejections for S1 should record hysteresis retention
        assertTrue(result.symbolRejections["B-SYM1_USDT"]?.contains(RejectionCode.S5_HYSTERESIS_RETAINED) == true)
    }

    @Test
    fun `test XrsStrategy respects portfolio concurrency limits`() {
        val count = 200
        val lookback = 180
        val btcSeries = buildSeries("B-BTC_USDT", count, 40000.0, 44000.0)
        val btcCtx = SymbolContext("B-BTC_USDT", btcSeries)

        // Universe with 10 symbols where top 3 have > 90th percentile threshold if top threshold is 75%
        val returns = listOf(
            0.50, 0.45, 0.40, 0.20, 0.10, 0.05, 0.00, -0.05, -0.10, -0.20
        )
        val universe = returns.mapIndexed { idx, ret ->
            val sym = "B-SYM${idx}_USDT"
            sym to SymbolContext(sym, buildSeries(sym, count, 100.0, 100.0 * (1.0 + ret)))
        }.toMap()

        // Restrict to max 1 Long position
        val strategy = XrsStrategy(
            lookbackBars = lookback,
            minUniverseSize = 10,
            topPercentileThreshold = 75.0, // Top 3 symbols (percentiles 100%, 88.8%, 77.7%) qualify
            maxLongPositions = 1,
            maxShortPositions = 0
        )

        val result = strategy.evaluate(universe, btcCtx, null)

        assertEquals("Only 1 Long signal emitted due to concurrency cap", 1, result.signals.size)
        assertEquals("B-SYM0_USDT", result.signals[0].symbol)

        // S1 and S2 should have been rejected with S5_PORTFOLIO_CONCURRENCY_CAP
        assertTrue(result.symbolRejections["B-SYM1_USDT"]?.contains(RejectionCode.S5_PORTFOLIO_CONCURRENCY_CAP) == true)
        assertTrue(result.symbolRejections["B-SYM2_USDT"]?.contains(RejectionCode.S5_PORTFOLIO_CONCURRENCY_CAP) == true)
    }
}
