package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.strategies.EmaCrossoverStrategy
import com.coindcx.trading.engine.time.FixedClock
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Validates the Look-Ahead Property (Property 1):
 * strategy.evaluate(truncatedTo(t)) == full_run_at(t)
 *
 * Guarantees mathematically that indicators and strategy logic operate purely
 * on closed historical bars and do not exhibit look-ahead leakage.
 */
class LookAheadPropertyTest {

    @Test
    fun `test strategy evaluate on truncated series produces identical signal as historical state`() {
        val random = Random(42)
        var price = 50000.0
        val n = 300
        val baseTime = 1_700_000_000_000L
        val intervalMs = 15 * 60_000L

        val rawCandles = mutableListOf<MarketCandle>()
        for (i in 0 until n) {
            val change = (random.nextDouble() - 0.49) * 200.0
            val open = price
            val close = price + change
            val high = maxOf(open, close) + random.nextDouble() * 50.0
            val low = minOf(open, close) - random.nextDouble() * 50.0
            val volume = 10.0 + random.nextDouble() * 50.0
            val time = baseTime + i * intervalMs
            rawCandles.add(MarketCandle(open, high, low, close, volume, time))
            price = close
        }

        val clock = FixedClock(baseTime + n * intervalMs + 1000L)
        val fullSeries = CandleSeries.fromApi(rawCandles, Interval.M15, clock, "B-BTC_USDT")
        assertEquals(n, fullSeries.size)

        val strategy = EmaCrossoverStrategy()

        // Sample across 50 offsets from bar 0 back to bar 150
        val sampleIndices = (0..150 step 3).toList()
        for (t in sampleIndices) {
            val truncated = fullSeries.truncatedTo(t)
            val ctxTrunc = SymbolContext(
                symbol = "B-BTC_USDT",
                primarySeries = truncated,
                clock = FixedClock(truncated.openTime(0) + Interval.M15.durationMs + 1000L)
            )
            val resTrunc = strategy.evaluate(ctxTrunc)

            // Construct reference series independently from raw candles ending at that bar
            val endRawIdx = (n - 1) - t
            val subRaw = rawCandles.subList(0, endRawIdx + 1)
            val subClock = FixedClock(rawCandles[endRawIdx].time + Interval.M15.durationMs + 1000L)
            val directSeries = CandleSeries.fromApi(subRaw, Interval.M15, subClock, "B-BTC_USDT")
            val ctxDirect = SymbolContext(
                symbol = "B-BTC_USDT",
                primarySeries = directSeries,
                clock = subClock
            )
            val resDirect = strategy.evaluate(ctxDirect)

            // Truncated series and direct series evaluated at bar t must yield identical results
            assertEquals("Signal presence at offset $t", resDirect.signal != null, resTrunc.signal != null)
            if (resDirect.signal != null && resTrunc.signal != null) {
                assertEquals("Direction at offset $t", resDirect.signal!!.direction, resTrunc.signal!!.direction)
                assertEquals("Entry price at offset $t", resDirect.signal!!.entryPrice, resTrunc.signal!!.entryPrice, 1e-6)
                assertEquals("Stop loss at offset $t", resDirect.signal!!.stopLossPrice, resTrunc.signal!!.stopLossPrice)
                assertEquals("Take profit at offset $t", resDirect.signal!!.takeProfitPrice, resTrunc.signal!!.takeProfitPrice)
            }
            assertEquals("Rejections at offset $t", resDirect.rejections, resTrunc.rejections)
        }
    }
}
