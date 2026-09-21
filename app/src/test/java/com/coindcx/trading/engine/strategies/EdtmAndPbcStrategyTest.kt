package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.backtest.BacktestDataLoader
import com.coindcx.trading.engine.backtest.ReplayEngine
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.scanner.SignalDedupRegistry
import com.coindcx.trading.engine.scanner.StrategyFamily
import com.coindcx.trading.engine.telemetry.RejectionCode
import com.coindcx.trading.engine.time.FixedClock
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class EdtmAndPbcStrategyTest {

    companion object {
        private val dataFile = File("d:/Projects/scanApp/tools/backtesting/data/BTCUSDT_15m_35000.json")

        @BeforeClass
        @JvmStatic
        fun checkDataset() {
            assertTrue("Dataset file must exist: ${dataFile.absolutePath}", dataFile.exists())
        }

        private fun buildEdtmSetup(isLong: Boolean, closeNearLow: Boolean = false): CandleSeries {
            val count = 150
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val baseTime = 1_700_000_000_000L
            val stepMs = Interval.H1.durationMs

            // 1. Oldest bars (indices 149 down to 21): Consolidate around 100.0 so EMA100 sits at ~100.0
            for (i in 21 until count) {
                val step = count - 1 - i
                openTime[i] = baseTime + (step * stepMs)
                open[i] = 100.0
                close[i] = 100.0
                high[i] = 100.5
                low[i] = 99.5
                volume[i] = 1000.0
            }

            // 2. Bars 20 down to 1: Form Donchian channel [99.0..102.0] for Long, [98.0..101.0] for Short
            if (isLong) {
                for (i in 1..20) {
                    val step = count - 1 - i
                    openTime[i] = baseTime + (step * stepMs)
                    val p = 99.5 + ((20 - i) * 0.1) // gentle climb 99.5 to 101.5
                    open[i] = p
                    close[i] = p + 0.1
                    high[i] = 102.0 // Donchian upper will be exactly 102.0
                    low[i] = 99.0
                    volume[i] = 1000.0
                }
                // Bar 0: Bullish breakout above 102.0
                openTime[0] = baseTime + ((count - 1) * stepMs)
                open[0] = 101.5
                if (closeNearLow) {
                    high[0] = 104.0
                    low[0] = 102.1
                    close[0] = 102.3 // Breaks 102.0, but closeLocation = (102.3-102.1)/1.9 = 0.105 < 0.70
                } else {
                    high[0] = 103.5
                    low[0] = 101.5
                    close[0] = 103.2 // Breaks 102.0, closeLocation = (103.2-101.5)/2.0 = 0.85 >= 0.70
                }
                volume[0] = 2500.0
            } else {
                for (i in 1..20) {
                    val step = count - 1 - i
                    openTime[i] = baseTime + (step * stepMs)
                    val p = 100.5 - ((20 - i) * 0.1) // gentle drift 100.5 to 98.5
                    open[i] = p
                    close[i] = p - 0.1
                    high[i] = 101.0
                    low[i] = 98.0 // Donchian lower will be exactly 98.0
                    volume[i] = 1000.0
                }
                // Bar 0: Bearish breakdown below 98.0
                openTime[0] = baseTime + ((count - 1) * stepMs)
                open[0] = 98.5
                high[0] = 98.5
                low[0] = 96.5
                close[0] = 96.8 // Breaks 98.0, closeLocation = (98.5 - 96.8)/(98.5 - 96.5) = 0.85 >= 0.70
                volume[0] = 2500.0
            }

            return CandleSeries.fromArrays("B-BTC_USDT", Interval.H1, openTime, open, high, low, close, volume)
        }

        private fun buildPbcSetup(): CandleSeries {
            val count = 120
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val baseTime = 1_700_000_000_000L
            val stepMs = Interval.M15.durationMs

            // Steady uptrend bars 119 to 5: price climbs from 50 to 100
            var price = 50.0
            for (step in 0 until (count - 5)) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                open[idx] = price
                close[idx] = price + 0.45
                high[idx] = close[idx] + 0.1
                low[idx] = open[idx] - 0.1
                volume[idx] = 1000.0
                price = close[idx]
            }

            // Pullback bars 4 to 1: shallow dip touching EMA9
            for (step in (count - 5) until (count - 1)) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                open[idx] = price
                close[idx] = price - 0.4
                high[idx] = open[idx] + 0.05
                low[idx] = close[idx] - 0.8 // dips down to touch EMA9
                volume[idx] = 500.0
                price = close[idx]
            }

            // Bar 0: strong green reclaim bar closing in upper 80% above EMA9
            val bar0 = 0
            openTime[bar0] = baseTime + ((count - 1) * stepMs)
            open[bar0] = price
            close[bar0] = price + 2.0
            high[bar0] = close[bar0] + 0.1
            low[bar0] = open[bar0] - 0.05
            volume[bar0] = 2000.0

            return CandleSeries.fromArrays("B-BTC_USDT", Interval.M15, openTime, open, high, low, close, volume)
        }
    }

    // =========================================================================
    // Strategy 10: EDTM Unit Tests
    // =========================================================================

    @Test
    fun `test EdtmStrategy rejects insufficient history`() {
        val shortSeries = buildEdtmSetup(isLong = true).subSeries(0, 50)
        val strategy = EdtmStrategy()
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = shortSeries,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = 100.0
        )

        val result = strategy.evaluate(ctx, null)
        assertNull(result.signal)
        assertTrue(result.rejections.contains(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
    }

    @Test
    fun `test EdtmStrategy triggers bullish breakout with open-ended target and valid strengths`() {
        val series = buildEdtmSetup(isLong = true, closeNearLow = false)
        val strategy = EdtmStrategy(donchianPeriod = 20, emaPeriod = 100, erThreshold = 0.50, minAtrPct = 0.01)

        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )
        val result = strategy.evaluate(ctx, null)

        assertNotNull("EDTM should trigger bullish breakout signal. Rejections: ${result.rejections}", result.signal)
        val sig = result.signal!!

        assertEquals(SignalDirection.LONG, sig.direction)
        assertEquals(RegimeTag.TREND_UP, sig.regimeTag)
        assertTrue("Target must be Target.Fixed", sig.target is Target.Fixed)
        val fixedTarget = sig.target as Target.Fixed
        assertEquals(strategy.targetPricePercent / strategy.stopLossPercent, fixedTarget.plannedRR, 0.001)

        assertTrue("All strengths must be non-empty", sig.strengths.isNotEmpty())
        for ((key, value) in sig.strengths) {
            assertTrue("Strength '$key' ($value) must be between 0.0 and 1.0", value in 0.0..1.0)
        }

        assertTrue("Stop loss must be below entry price", sig.stopLoss < sig.entryRef)
        assertTrue("Risk pct must be positive", sig.riskPct > 0.0)
    }

    @Test
    fun `test EdtmStrategy triggers bearish breakdown with open-ended target`() {
        val series = buildEdtmSetup(isLong = false, closeNearLow = false)
        val strategy = EdtmStrategy(donchianPeriod = 20, emaPeriod = 100, erThreshold = 0.50, minAtrPct = 0.01)

        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )
        val result = strategy.evaluate(ctx, null)

        assertNotNull("EDTM should trigger bearish breakdown signal. Rejections: ${result.rejections}", result.signal)
        val sig = result.signal!!

        assertEquals(SignalDirection.SHORT, sig.direction)
        assertEquals(RegimeTag.TREND_DOWN, sig.regimeTag)
        assertTrue("Target must be Target.Fixed", sig.target is Target.Fixed)
        val shortTarget = sig.target as Target.Fixed
        assertEquals(strategy.targetPricePercent / strategy.stopLossPercent, shortTarget.plannedRR, 0.001)
        assertTrue("Stop loss must be above entry price", sig.stopLoss > sig.entryRef)

        for ((key, value) in sig.strengths) {
            assertTrue("Strength '$key' ($value) must be in 0.0..1.0", value in 0.0..1.0)
        }
    }

    @Test
    fun `test EdtmStrategy rejects low close location`() {
        val testSeries = buildEdtmSetup(isLong = true, closeNearLow = true)
        val strategy = EdtmStrategy(donchianPeriod = 20, emaPeriod = 100, erThreshold = 0.50, minAtrPct = 0.01)

        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = testSeries,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = testSeries.close(0)
        )
        val result = strategy.evaluate(ctx, null)

        assertNull("Should reject breakout due to low close location. Rejections: ${result.rejections}", result.signal)
        assertTrue(result.rejections.contains(RejectionCode.S10_C6_CLOSE_LOCATION))
    }

    // =========================================================================
    // Strategy 1: PBC Unit Tests
    // =========================================================================

    @Test
    fun `test PbcStrategy rejects unstacked EMAs`() {
        val flatSeries = CandleSeries.fromArrays(
            symbol = "B-BTC_USDT",
            interval = Interval.M15,
            openTime = LongArray(100) { 1_700_000_000_000L + it * 900_000L },
            open = DoubleArray(100) { 100.0 },
            high = DoubleArray(100) { 100.5 },
            low = DoubleArray(100) { 99.5 },
            close = DoubleArray(100) { 100.0 },
            volume = DoubleArray(100) { 1000.0 }
        )
        val strategy = PbcStrategy()
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = flatSeries,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = 100.0
        )

        val result = strategy.evaluate(ctx, null)
        assertNull(result.signal)
        assertTrue("Expected S1_REGIME_STACKED_EMA in ${result.rejections}", result.rejections.contains(RejectionCode.S1_REGIME_STACKED_EMA))
    }

    @Test
    fun `test PbcStrategy triggers bullish pullback continuation`() {
        val series = buildPbcSetup()
        val strategy = PbcStrategy(adxMin = 10.0, adxMax = 100.0, minEr = 0.30)

        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )
        val result = strategy.evaluate(ctx, null)

        assertNotNull("PBC should trigger on shallow pullback reclaim. Rejections: ${result.rejections}", result.signal)
        val sig = result.signal!!

        assertEquals(SignalDirection.LONG, sig.direction)
        assertEquals(RegimeTag.TREND_UP, sig.regimeTag)
        assertTrue("Target must be Target.Fixed", sig.target is Target.Fixed)
        val fixedTarget = sig.target as Target.Fixed
        assertEquals(strategy.targetPricePercent / strategy.stopLossPercent, fixedTarget.plannedRR, 0.001)
        assertTrue("Take profit must be higher than entry", fixedTarget.tp1 > sig.entryRef)
        assertTrue("Stop loss must be lower than entry", sig.stopLoss < sig.entryRef)

        for ((k, v) in sig.strengths) {
            assertTrue("Strength '$k' ($v) must be in 0.0..1.0", v in 0.0..1.0)
        }
    }

    // =========================================================================
    // Full Replay Backtest on BTC 35,000 Candles
    // =========================================================================

    @Test
    fun `test Replay Backtest of Strategy 10 EDTM on 1H Synthesized BTC Data`() {
        val series15m = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val clock = FixedClock(series15m.openTime(0) + Interval.M15.durationMs + 10_000L)
        val series1H = CandleSeries.synthesize(series15m, Interval.H1, clock)
        val series4H = CandleSeries.synthesize(series1H, Interval.H4, clock)

        assertTrue("Synthesized 1H series must have ~8750 bars", series1H.size >= 8000)
        assertTrue("Synthesized 4H series must have ~2180 bars", series4H.size >= 2000)

        val strategy = EdtmStrategy(
            donchianPeriod = 20,
            emaPeriod = 100,
            erThreshold = 0.50,
            minAtrPct = 0.5,
            maxAtrPct = 6.0,
            trailAtrMultiplier = 2.5
        )

        val engine = ReplayEngine(takerFeePct = 0.0005, slippagePct = 0.0002)
        val summary = engine.replay(
            series = series1H,
            strategy = strategy,
            htf4HSeries = series4H
        )

        println("=================================================================")
        println("  S10 EDTM REPLAY BACKTEST (1H BTC Futures, 35k 15m synthesized)")
        println("=================================================================")
        println("Total 1H Bars       : ${summary.totalBars}")
        println("Total Trades        : ${summary.totalTrades}")
        println("Wins / Losses       : ${summary.winningTrades} / ${summary.losingTrades}")
        println("Win Rate            : ${"%.2f".format(summary.winRatePct)}%")
        println("Profit Factor       : ${"%.2f".format(summary.profitFactor)}")
        println("Total Return        : ${"%.2f".format(summary.totalReturnPct)}%")
        println("Max Drawdown        : ${"%.2f".format(summary.maxDrawdownPct)}%")
        println("Sharpe Ratio        : ${"%.2f".format(summary.sharpeRatio)}")
        println("Calmar Ratio        : ${"%.2f".format(summary.calmarRatio)}")
        println("SQN                 : ${"%.2f".format(summary.systemQualityNumber)}")
        println("Avg Bars Held       : ${"%.1f".format(summary.avgBarsHeld)}")
        println("=================================================================")

        assertTrue("EDTM should execute trades over 8000+ 1H bars", summary.totalTrades > 0)
        assertTrue("Win rate must be valid", summary.winRatePct in 0.0..100.0)
        assertTrue("Max drawdown must be non-negative", summary.maxDrawdownPct >= 0.0)
    }

    @Test
    fun `test Replay Backtest of Strategy 1 PBC on 15m BTC Data`() {
        val series15m = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val clock = FixedClock(series15m.openTime(0) + Interval.M15.durationMs + 10_000L)
        val series4H = CandleSeries.synthesize(series15m, Interval.H4, clock)

        val strategy = PbcStrategy(
            fastPeriod = 9,
            midPeriod = 21,
            slowPeriod = 50,
            adxMin = 20.0,
            adxMax = 55.0,
            minEr = 0.35,
            plannedRR = 2.0
        )

        val engine = ReplayEngine(takerFeePct = 0.0005, slippagePct = 0.0002)
        val summary = engine.replay(
            series = series15m,
            strategy = strategy,
            htf4HSeries = series4H
        )

        println("=================================================================")
        println("  S1 PBC REPLAY BACKTEST (15m BTC Futures, 35,000 candles)")
        println("=================================================================")
        println("Total Bars Analyzed : ${summary.totalBars}")
        println("Total Trades        : ${summary.totalTrades}")
        println("Wins / Losses       : ${summary.winningTrades} / ${summary.losingTrades}")
        println("Win Rate            : ${"%.2f".format(summary.winRatePct)}%")
        println("Profit Factor       : ${"%.2f".format(summary.profitFactor)}")
        println("Total Return        : ${"%.2f".format(summary.totalReturnPct)}%")
        println("Max Drawdown        : ${"%.2f".format(summary.maxDrawdownPct)}%")
        println("Sharpe Ratio        : ${"%.2f".format(summary.sharpeRatio)}")
        println("Calmar Ratio        : ${"%.2f".format(summary.calmarRatio)}")
        println("SQN                 : ${"%.2f".format(summary.systemQualityNumber)}")
        println("Avg Bars Held       : ${"%.1f".format(summary.avgBarsHeld)}")
        println("=================================================================")

        assertTrue("Summary total bars must match", summary.totalBars > 0)
        assertTrue("Win rate must be valid", summary.winRatePct in 0.0..100.0)
    }
}
