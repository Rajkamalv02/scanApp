package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.time.FixedClock
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class TechnicalIndicatorsExtendedTest {

    private val clock = FixedClock(10_000_000_000L)

    private fun buildSeries(closes: DoubleArray, highs: DoubleArray? = null, lows: DoubleArray? = null, volumes: DoubleArray? = null): CandleSeries {
        val n = closes.size
        val h = highs ?: closes.map { it + 1.0 }.toDoubleArray()
        val l = lows ?: closes.map { it - 1.0 }.toDoubleArray()
        val v = volumes ?: DoubleArray(n) { 100.0 }
        val times = LongArray(n) { (it + 1) * 60_000L }

        val candles = (0 until n).map { i ->
            MarketCandle(open = closes[i], high = h[i], low = l[i], close = closes[i], volume = v[i], time = times[i])
        }
        return CandleSeries.fromApi(candles, Interval.M1, clock, "B-TEST_USDT")
    }

    @Test
    fun `test Kaufman Efficiency Ratio ER`() {
        // 1. Monotonic uptrend: 1, 2, 3, ... 21. Net change = 20, Total path = 20 -> ER = 1.0
        val trendCloses = (1..25).map { it.toDouble() }.toDoubleArray()
        val trendSeries = buildSeries(trendCloses)
        val erTrend = TechnicalIndicators.calculateEfficiencyRatio(trendSeries, period = 20, barIndex = 0)
        assertEquals("Perfect trend must have ER = 1.0", 1.0, erTrend, 1e-6)

        // 2. Oscillatory series: 10, 11, 10, 11, 10, 11...
        val chopCloses = DoubleArray(30) { if (it % 2 == 0) 10.0 else 11.0 }
        val chopSeries = buildSeries(chopCloses)
        val erChop = TechnicalIndicators.calculateEfficiencyRatio(chopSeries, period = 20, barIndex = 0)
        // Net change: abs(chopSeries.close(0) - chopSeries.close(20)) = 0.0 -> ER = 0.0
        assertEquals("Oscillating series ending at same level has ER = 0.0", 0.0, erChop, 1e-6)
    }

    @Test
    fun `test Bollinger Bands population stdev and BBWidth`() {
        // Values: 10, 20, 30, 40 (mean = 25, variance = ((15^2 + 5^2 + 5^2 + 15^2)/4) = 500/4 = 125, stdev = sqrt(125) = 11.180339887)
        val closes = doubleArrayOf(10.0, 20.0, 30.0, 40.0)
        val series = buildSeries(closes)
        val bb = TechnicalIndicators.calculateBollingerBands(series, period = 4, k = 2.0, barIndex = 0)

        assertEquals(25.0, bb.basis, 1e-6)
        val expectedStdev = sqrt(125.0)
        assertEquals(25.0 + 2.0 * expectedStdev, bb.upper, 1e-6)
        assertEquals(25.0 - 2.0 * expectedStdev, bb.lower, 1e-6)
        val expectedWidth = (bb.upper - bb.lower) / 25.0
        assertEquals(expectedWidth, bb.bbWidth, 1e-6)
    }

    @Test
    fun `test percentileRank`() {
        val window = doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0)
        // Values <= 30: 10, 20, 30 (3 of 5 = 60.0%)
        val pct = TechnicalIndicators.percentileRank(window, 30.0)
        assertEquals(60.0, pct, 1e-6)

        // Value <= 5: 0 of 5 = 0.0%
        assertEquals(0.0, TechnicalIndicators.percentileRank(window, 5.0), 1e-6)

        // Value <= 55: 5 of 5 = 100.0%
        assertEquals(100.0, TechnicalIndicators.percentileRank(window, 55.0), 1e-6)
    }

    @Test
    fun `test Donchian channel with explicit endIndex`() {
        // Construct series of 10 bars:
        // ascending: 1..10, so bar[0] = 10, bar[1] = 9, bar[2] = 8, bar[3] = 7, bar[4] = 6, bar[5] = 5...
        val closes = (1..10).map { it.toDouble() }.toDoubleArray()
        val highs = (1..10).map { it.toDouble() + 0.5 }.toDoubleArray()
        val lows = (1..10).map { it.toDouble() - 0.5 }.toDoubleArray()
        val series = buildSeries(closes, highs, lows)

        // Donchian 3 ending at bar[1] looks at bars: bar[1], bar[2], bar[3]
        // highs: 9.5, 8.5, 7.5 -> upper = 9.5
        // lows:  8.5, 7.5, 6.5 -> lower = 6.5
        val don1 = TechnicalIndicators.donchian(series, period = 3, endIndex = 1)
        assertEquals(9.5, don1.upper, 1e-6)
        assertEquals(6.5, don1.lower, 1e-6)

        // Donchian 3 ending at bar[2] looks at bars: bar[2], bar[3], bar[4]
        // highs: 8.5, 7.5, 6.5 -> upper = 8.5
        // lows:  7.5, 6.5, 5.5 -> lower = 5.5
        val don2 = TechnicalIndicators.donchian(series, period = 3, endIndex = 2)
        assertEquals(8.5, don2.upper, 1e-6)
        assertEquals(5.5, don2.lower, 1e-6)
    }

    @Test
    fun `test 5-bar swing point fractals confirm 2 bars late`() {
        // Ascending input: [10, 12, 20, 12, 10, 8, 5]
        // In CandleSeries:
        // bar[0] = 5
        // bar[1] = 8
        // bar[2] = 10
        // bar[3] = 12
        // bar[4] = 20  <-- Swing High! (high > 12, 10, 12, 8)
        // bar[5] = 12
        // bar[6] = 10
        val closes = doubleArrayOf(10.0, 12.0, 20.0, 12.0, 10.0, 8.0, 5.0)
        val highs = doubleArrayOf(10.0, 12.0, 20.0, 12.0, 10.0, 8.0, 5.0)
        val lows = doubleArrayOf(10.0, 12.0, 20.0, 12.0, 10.0, 8.0, 5.0)
        val series = buildSeries(closes, highs, lows)

        val result = TechnicalIndicators.calculateSwingPoints(series, window = 5, maxCount = 5)
        assertEquals("Should identify 1 swing high", 1, result.swingHighs.size)
        val sh = result.swingHighs.first()
        assertEquals("Swing high is at price 20.0", 20.0, sh.price, 1e-6)
        assertEquals("Swing high is at bar index 4", 4, sh.barIndex)
    }

    @Test
    fun `test Z-score with population stdev`() {
        // Values: 10, 20, 30, 40 (mean = 25, stdev = sqrt(125) = 11.180339887)
        // Series has bar[0] = 40.0
        val closes = doubleArrayOf(10.0, 20.0, 30.0, 40.0)
        val series = buildSeries(closes)
        val z = TechnicalIndicators.calculateZScore(series, period = 4, barIndex = 0)
        val expected = (40.0 - 25.0) / sqrt(125.0)
        assertEquals(expected, z, 1e-6)
    }

    @Test
    fun `test anchored VWAP`() {
        // Candle 1: time=1000, H=10, L=8, C=9 -> typical=9.0, vol=10 -> sum=90, vol=10
        // Candle 2: time=2000, H=12, L=10, C=11 -> typical=11.0, vol=20 -> sum=90+220=310, vol=30 -> vwap = 310/30 = 10.333333
        val candles = listOf(
            MarketCandle(open = 9.0, high = 10.0, low = 8.0, close = 9.0, volume = 10.0, time = 1000L),
            MarketCandle(open = 11.0, high = 12.0, low = 10.0, close = 11.0, volume = 20.0, time = 2000L)
        )
        val series = CandleSeries.fromApi(candles, Interval.M1, clock, "B-TEST_USDT")

        val vwapAll = TechnicalIndicators.anchoredVwap(series, anchorOpenTimeUtc = 1000L)
        assertEquals(310.0 / 30.0, vwapAll, 1e-6)

        val vwapSecondOnly = TechnicalIndicators.anchoredVwap(series, anchorOpenTimeUtc = 2000L)
        assertEquals(11.0, vwapSecondOnly, 1e-6)
    }
}
