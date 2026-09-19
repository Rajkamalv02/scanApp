package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.time.FixedClock
import org.junit.Assert.*
import org.junit.Test

class CandleSeriesTest {

    @Test
    fun `test forming candle is structurally dropped by CandleSeries fromApi`() {
        val clock = FixedClock(1_000_000L) // Current time = 1,000,000 ms

        // Candle 1: 900,000 to 960,000 (Completed on 1m / 60,000 ms interval)
        // Candle 2: 960,000 to 1,020,000 (Currently forming! 960k + 60k = 1,020k > 1,000k)
        val rawCandles = listOf(
            MarketCandle(open = 100.0, high = 105.0, low = 99.0, close = 104.0, volume = 50.0, time = 900_000L),
            MarketCandle(open = 104.0, high = 110.0, low = 103.0, close = 108.0, volume = 20.0, time = 960_000L)
        )

        val series = CandleSeries.fromApi(rawCandles, Interval.M1, clock, "B-BTC_USDT")

        // Forming candle must be discarded!
        assertEquals("Should contain only 1 completed candle", 1, series.size)
        assertEquals("Bar 0 openTime must be 900,000", 900_000L, series.openTime(0))
        assertEquals("Bar 0 close must be 104.0", 104.0, series.close(0), 1e-6)
    }

    @Test
    fun `test bar indexing convention index 0 is newest closed candle`() {
        val clock = FixedClock(2_000_000L)

        val rawCandles = listOf(
            MarketCandle(open = 10.0, high = 12.0, low = 9.0, close = 11.0, volume = 100.0, time = 100_000L),
            MarketCandle(open = 11.0, high = 13.0, low = 10.5, close = 12.5, volume = 120.0, time = 200_000L),
            MarketCandle(open = 12.5, high = 14.0, low = 12.0, close = 13.0, volume = 150.0, time = 300_000L)
        )

        val series = CandleSeries.fromApi(rawCandles, Interval.M1, clock, "B-ETH_USDT")

        assertEquals(3, series.size)
        // index 0 = bar[0] = candle at 300,000
        assertEquals(300_000L, series.openTime(0))
        assertEquals(13.0, series.close(0), 1e-6)

        // index 1 = bar[1] = candle at 200,000
        assertEquals(200_000L, series.openTime(1))
        assertEquals(12.5, series.close(1), 1e-6)

        // index 2 = bar[2] = candle at 100,000
        assertEquals(100_000L, series.openTime(2))
        assertEquals(11.0, series.close(2), 1e-6)
    }

    @Test
    fun `test truncatedTo property`() {
        val clock = FixedClock(2_000_000L)

        val rawCandles = (1..10).map { i ->
            MarketCandle(
                open = i.toDouble(),
                high = i + 0.5,
                low = i - 0.5,
                close = i + 0.2,
                volume = 100.0,
                time = i * 60_000L
            )
        }

        val full = CandleSeries.fromApi(rawCandles, Interval.M1, clock, "B-SOL_USDT")
        assertEquals(10, full.size)

        // Truncate to index 3 (bar[3] becomes new bar[0])
        val truncated = full.truncatedTo(3)
        assertEquals(7, truncated.size)
        assertEquals(full.openTime(3), truncated.openTime(0))
        assertEquals(full.close(3), truncated.close(0), 1e-6)
        assertEquals(full.close(4), truncated.close(1), 1e-6)
    }
}
