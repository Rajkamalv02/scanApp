package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.SignalDirection
import com.coindcx.trading.engine.SymbolContext
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.backtest.BacktestDataLoader
import com.coindcx.trading.engine.backtest.ReplayEngine
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.state.SessionState
import com.coindcx.trading.engine.telemetry.RejectionCode
import com.coindcx.trading.engine.time.FixedClock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LsrAndSormStrategyTest {

    companion object {
        /**
         * Builds synthetic 15m candle series for LSR tests.
         */
        private fun buildLsrBullishSetup(): CandleSeries {
            val count = 70
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val baseTime = 1_700_000_000_000L
            val stepMs = Interval.M15.durationMs

            // Baseline oscillation around 100.0
            for (step in 0 until count) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                open[idx] = 100.0
                close[idx] = 100.2
                high[idx] = 101.0
                low[idx] = 99.2
                volume[idx] = 1000.0
            }

            // Swing low at bar 15: low=98.0 (strictly lower than neighbors 17, 16, 14, 13 having low=99.2)
            val b15 = 15
            low[b15] = 98.0
            close[b15] = 99.0
            open[b15] = 99.5

            // Bar 0: Liquidity sweep of bar 15 low (98.0)
            // Wicks down to 97.4 (< 98.0), reclaims and closes at 99.2 (> 98.0)
            val b0 = 0
            open[b0] = 98.4
            close[b0] = 99.2 // Green candle closing in upper half
            high[b0] = 99.4
            low[b0] = 97.4  // Deep sweep of 98.0 level
            volume[b0] = 2500.0 // Volume expansion

            return CandleSeries.fromArrays("B-BTC_USDT", Interval.M15, openTime, open, high, low, close, volume)
        }

        private fun buildLsrBearishSetup(): CandleSeries {
            val count = 70
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val baseTime = 1_700_000_000_000L
            val stepMs = Interval.M15.durationMs

            for (step in 0 until count) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                open[idx] = 100.0
                close[idx] = 99.8
                high[idx] = 100.8
                low[idx] = 99.0
                volume[idx] = 1000.0
            }

            // Swing high at bar 15: high=102.0 (strictly higher than neighbors having high=100.8)
            val b15 = 15
            high[b15] = 102.0
            close[b15] = 101.0
            open[b15] = 100.5

            // Bar 0: Liquidity sweep of 102.0 swing high
            // Wicks up to 102.7 (> 102.0), closes back down at 100.8 (< 102.0) with upper wick rejection
            val b0 = 0
            open[b0] = 101.6
            close[b0] = 100.8
            high[b0] = 102.7
            low[b0] = 100.6
            volume[b0] = 2500.0

            return CandleSeries.fromArrays("B-BTC_USDT", Interval.M15, openTime, open, high, low, close, volume)
        }

        /**
         * Builds synthetic 15m candles aligned to UTC day boundaries for SORM tests.
         */
        private fun buildSormAsiaSetup(breakoutLong: Boolean = true): CandleSeries {
            val count = 50
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val stepMs = Interval.M15.durationMs

            // Align bar 0 to 01:15 UTC (bar 5 of the UTC day)
            // A day start UTC: 1_700_000_000_000L rounded to UTC day start
            val dayStartUtc = 1_700_006_400_000L // Divisible by 86_400_000
            val bar0Time = dayStartUtc + 5 * stepMs // 01:15 UTC

            for (step in 0 until count) {
                val idx = count - 1 - step
                openTime[idx] = bar0Time - (idx * stepMs)
                open[idx] = 100.0
                close[idx] = 100.2
                high[idx] = 100.8
                low[idx] = 99.2
                volume[idx] = 1000.0
            }

            // Opening Range is bars 00:00 to 01:00 UTC
            // In our series, bar 0 is 01:15 UTC.
            // Bar 1 is 01:00 UTC.
            // Bar 2 is 00:45 UTC.
            // Bar 3 is 00:30 UTC.
            // Bar 4 is 00:15 UTC.
            // Bar 5 is 00:00 UTC.
            // Opening Range: bars 5, 4, 3, 2 (00:00 to 01:00 UTC)
            for (b in listOf(5, 4, 3, 2)) {
                high[b] = 101.5 // OR High = 101.5
                low[b] = 98.5   // OR Low = 98.5
                open[b] = 100.0
                close[b] = 100.5
                volume[b] = 1000.0
            }

            // Bar 0: at 01:15 UTC, breakout past OR
            val b0 = 0
            if (breakoutLong) {
                open[b0] = 101.2
                close[b0] = 102.6 // Decisive breakout above OR High (101.5)
                high[b0] = 102.8
                low[b0] = 101.0
                volume[b0] = 3000.0 // 3x volume
            } else {
                open[b0] = 98.8
                close[b0] = 97.4 // Decisive breakdown below OR Low (98.5)
                high[b0] = 99.0
                low[b0] = 97.2
                volume[b0] = 3000.0
            }

            return CandleSeries.fromArrays("B-BTC_USDT", Interval.M15, openTime, open, high, low, close, volume)
        }
    }

    // =========================================================================
    // Strategy 3 (LSR) Tests
    // =========================================================================

    @Test
    fun `test LsrStrategy rejects insufficient history`() {
        val series = buildLsrBullishSetup().subSeries(0, 40)
        val strategy = LsrStrategy()
        val ctx = SymbolContext("B-BTC_USDT", series, clock = FixedClock(1_800_000_000_000L))
        val res = strategy.evaluate(ctx, null)

        assertNull(res.signal)
        assertTrue(res.rejections.contains(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
    }

    @Test
    fun `test LsrStrategy triggers Bullish Sweep Long on confirmed reclaim`() {
        val series = buildLsrBullishSetup()
        val strategy = LsrStrategy()
        val ctx = SymbolContext("B-BTC_USDT", series, clock = FixedClock(1_800_000_000_000L))
        val res = strategy.evaluate(ctx, null)

        assertNotNull("LSR should trigger Long on swept swing low", res.signal)
        val sig = res.signal!!
        assertEquals(SignalDirection.LONG, sig.direction)
        assertEquals("lsr", sig.strategyId)
        assertTrue("Stop must be below entry", sig.stopLoss < sig.entryRef)
        val target = sig.target as Target.Fixed
        assertTrue("Target must be above entry", target.tp1 > sig.entryRef)
        for ((k, v) in sig.strengths) {
            assertTrue("Strength '$k' ($v) must be in 0..1", v in 0.0..1.0)
        }
    }

    @Test
    fun `test LsrStrategy triggers Bearish Sweep Short on confirmed reclaim`() {
        val series = buildLsrBearishSetup()
        val strategy = LsrStrategy()
        val ctx = SymbolContext("B-BTC_USDT", series, clock = FixedClock(1_800_000_000_000L))
        val res = strategy.evaluate(ctx, null)

        assertNotNull("LSR should trigger Short on swept swing high", res.signal)
        val sig = res.signal!!
        assertEquals(SignalDirection.SHORT, sig.direction)
        assertEquals("lsr", sig.strategyId)
        assertTrue("Stop must be above entry", sig.stopLoss > sig.entryRef)
        val target = sig.target as Target.Fixed
        assertTrue("Target must be below entry", target.tp1 < sig.entryRef)
        for ((k, v) in sig.strengths) {
            assertTrue("Strength '$k' ($v) must be in 0..1", v in 0.0..1.0)
        }
    }

    // =========================================================================
    // Strategy 4 (SORM) Tests
    // =========================================================================

    @Test
    fun `test SormStrategy rejects when outside session window`() {
        val count = 50
        val dayStartUtc = 1_700_006_400_000L
        val bar0Time = dayStartUtc + (8 * 3600 * 1000L) // 08:00 UTC (between Asia and US sessions)
        val stepMs = Interval.M15.durationMs

        val openTime = LongArray(count) { idx -> bar0Time - (idx * stepMs) }
        val open = DoubleArray(count) { 100.0 }
        val high = DoubleArray(count) { 101.0 }
        val low = DoubleArray(count) { 99.0 }
        val close = DoubleArray(count) { 100.2 }
        val volume = DoubleArray(count) { 1000.0 }

        val series = CandleSeries.fromArrays("B-BTC_USDT", Interval.M15, openTime, open, high, low, close, volume)
        val strategy = SormStrategy()
        val ctx = SymbolContext("B-BTC_USDT", series)
        val res = strategy.evaluate(ctx, null)

        assertNull(res.signal)
        assertTrue(res.rejections.contains(RejectionCode.S4_REGIME_OUTSIDE_SESSION))
    }

    @Test
    fun `test SormStrategy triggers Long on Asia Opening Range Breakout`() {
        val series = buildSormAsiaSetup(breakoutLong = true)
        val strategy = SormStrategy()
        val ctx = SymbolContext("B-BTC_USDT", series)
        val res = strategy.evaluate(ctx, null)

        assertNotNull("SORM should trigger Long on Asia OR breakout", res.signal)
        val sig = res.signal!!
        assertEquals(SignalDirection.LONG, sig.direction)
        assertEquals("sorm", sig.strategyId)
        assertTrue("Stop loss must be below entry (at Mid-OR)", sig.stopLoss < sig.entryRef)
        val target = sig.target as Target.Fixed
        assertTrue("Target must be above entry", target.tp1 > sig.entryRef)
        for ((k, v) in sig.strengths) {
            assertTrue("Strength '$k' ($v) must be in 0..1", v in 0.0..1.0)
        }

        // Verify state is marked hasSignalled = true
        val state = res.newState as? SessionState
        assertNotNull(state)
        assertTrue("Session state must record hasSignalled = true", state!!.hasSignalled)
    }

    @Test
    fun `test SormStrategy enforces 1 trade per session limit`() {
        val series = buildSormAsiaSetup(breakoutLong = true)
        val strategy = SormStrategy()
        val ctx = SymbolContext("B-BTC_USDT", series)

        // Pre-existing state has already signalled in this session
        val dayStartUtc = 1_700_006_400_000L
        val prevState = SessionState(
            sessionId = "ASIA",
            sessionStartOpenTime = dayStartUtc,
            orHigh = 101.5,
            orLow = 98.5,
            orVolumeSum = 4000.0,
            hasSignalled = true,
            lastUpdatedBarOpenTime = dayStartUtc + 4 * Interval.M15.durationMs
        )

        val res = strategy.evaluate(ctx, prevState)
        assertNull("SORM must not signal twice in the same session", res.signal)
        assertTrue(res.rejections.contains(RejectionCode.S4_ALREADY_SIGNALLED))
    }

    @Test
    fun `test SormStrategy triggers Short on Asia Opening Range Breakdown`() {
        val series = buildSormAsiaSetup(breakoutLong = false)
        val strategy = SormStrategy()
        val ctx = SymbolContext("B-BTC_USDT", series)
        val res = strategy.evaluate(ctx, null)

        assertNotNull("SORM should trigger Short on Asia OR breakdown", res.signal)
        val sig = res.signal!!
        assertEquals(SignalDirection.SHORT, sig.direction)
        assertEquals("sorm", sig.strategyId)
        assertTrue("Stop loss must be above entry (at Mid-OR)", sig.stopLoss > sig.entryRef)
        val target = sig.target as Target.Fixed
        assertTrue("Target must be below entry", target.tp1 < sig.entryRef)
    }

    // =========================================================================
    // Replay on 35,000 Real BTC Candles
    // =========================================================================

    @Test
    fun `test Replay Backtest of Strategy 3 LSR on 15m BTC Data`() {
        val dataFile = File("tools/data/historical/BTC_USDT_15m.csv")
        if (!dataFile.exists()) return

        val series = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val engine = ReplayEngine()
        val lsr = LsrStrategy()
        val summary = engine.replay(series = series, strategy = lsr)

        println("\n=== LSR (Strategy 3) 35,000 BTC Candles Replay ===")
        println("Total Trades:    ${summary.totalTrades}")
        println("Win Rate:        ${"%.2f".format(summary.winRatePct)}%")
        println("Profit Factor:   ${"%.2f".format(summary.profitFactor)}")
        println("Total Return:    ${"%.2f".format(summary.totalReturnPct)}%")
        println("Max Drawdown:    ${"%.2f".format(summary.maxDrawdownPct)}%")
        println("Sharpe Ratio:    ${"%.2f".format(summary.sharpeRatio)}")

        assertTrue("Should replay through all 35k bars", summary.totalBars > 30000)
    }
}
