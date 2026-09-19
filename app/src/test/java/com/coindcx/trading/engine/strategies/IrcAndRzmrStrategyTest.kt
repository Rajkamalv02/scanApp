package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.backtest.BacktestDataLoader
import com.coindcx.trading.engine.backtest.ReplayEngine
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.telemetry.RejectionCode
import com.coindcx.trading.engine.time.FixedClock
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class IrcAndRzmrStrategyTest {

    companion object {
        private val dataFile = File("d:/Projects/scanApp/tools/backtesting/data/BTCUSDT_15m_35000.json")

        @BeforeClass
        @JvmStatic
        fun checkDataset() {
            assertTrue("Dataset file must exist: ${dataFile.absolutePath}", dataFile.exists())
        }

        /**
         * Builds an IRC Bullish setup:
         * Bars 49..4: quiet baseline price ~100.0
         * Bar 3: huge impulse expansion candle (low 100 -> high 106, close 105.5, volume 5000)
         * Bar 2: retrace bar pulling down to 102.5 (touches 38.2% Fib which is 106 - 0.382*6 = 103.7)
         * Bar 1: consolidation bar holding at 103.0
         * Bar 0: strong green resumption bar closing at 104.5 (above Fib 38.2%)
         */
        private fun buildIrcBullishSetup(): CandleSeries {
            val count = 50
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val baseTime = 1_700_000_000_000L
            val stepMs = Interval.M15.durationMs

            // Bars 49 down to 4: quiet consolidation around 100.0
            for (step in 0 until (count - 4)) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                open[idx] = 100.0
                high[idx] = 100.5
                low[idx] = 99.5
                close[idx] = 100.1
                volume[idx] = 1000.0
            }

            // Bar 3: Massive Bullish Impulse Expansion
            val b3 = 3
            val step3 = count - 1 - b3
            openTime[b3] = baseTime + (step3 * stepMs)
            open[b3] = 100.0
            high[b3] = 106.0
            low[b3] = 99.8
            close[b3] = 105.5 // Expansion TR = 6.2, large body, high volume
            volume[b3] = 6000.0

            // Bar 2: Retrace down into 38.2%-50% zone
            val b2 = 2
            val step2 = count - 1 - b2
            openTime[b2] = baseTime + (step2 * stepMs)
            open[b2] = 105.5
            high[b2] = 105.6
            low[b2] = 102.5 // Retraces to 102.5 (Fib382 is ~103.6, Fib618 is ~102.1)
            close[b2] = 103.0
            volume[b2] = 1200.0

            // Bar 1: Quiet inside bar holding Fib zone
            val b1 = 1
            val step1 = count - 1 - b1
            openTime[b1] = baseTime + (step1 * stepMs)
            open[b1] = 103.0
            high[b1] = 103.8
            low[b1] = 102.8
            close[b1] = 103.5
            volume[b1] = 1100.0

            // Bar 0: Green Resumption Candle
            val b0 = 0
            val step0 = count - 1 - b0
            openTime[b0] = baseTime + (step0 * stepMs)
            open[b0] = 103.5
            high[b0] = 105.0
            low[b0] = 103.4
            close[b0] = 104.8 // Green, closes above Fib382
            volume[b0] = 2000.0

            return CandleSeries.fromArrays("B-BTC_USDT", Interval.M15, openTime, open, high, low, close, volume)
        }

        /**
         * Builds an RZMR Oversold mean-reverting setup:
         * 77 bars oscillating in a coherent sinusoidal channel (mean = 100.0, stdev ~ 2.0, ATR ~ 1.5).
         * Bars 2 and 1 dip down to Z = -2.2 (within the 3-sigma bound).
         * Bar 0: green reversal candle closing at 97.2, hooking Z-score back up.
         */
        private fun buildRzmrOversoldSetup(): CandleSeries {
            val count = 80
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val baseTime = 1_700_000_000_000L
            val stepMs = Interval.M15.durationMs

            // 77 bars sinusoidal wave: amplitude 2.8 (stdev ~ 2.0, ATR ~ 1.4)
            var prevClose = 100.0
            for (step in 0 until (count - 3)) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                val curr = 100.0 + 2.8 * kotlin.math.sin(step * 2.0 * Math.PI / 16.0)
                open[idx] = prevClose
                close[idx] = curr
                high[idx] = maxOf(open[idx], close[idx]) + 0.3
                low[idx] = minOf(open[idx], close[idx]) - 0.3
                volume[idx] = 1000.0
                prevClose = curr
            }

            // Bar 2: Controlled dip to 95.8 (Z ~ -2.1, <= -1.8, < 3.0 sigma)
            val b2 = 2
            openTime[b2] = baseTime + ((count - 1 - b2) * stepMs)
            open[b2] = prevClose
            close[b2] = 95.8
            high[b2] = maxOf(open[b2], close[b2]) + 0.2
            low[b2] = 95.5
            volume[b2] = 1200.0

            // Bar 1: Tests extremity at 95.6 (Z ~ -2.2)
            val b1 = 1
            openTime[b1] = baseTime + ((count - 1 - b1) * stepMs)
            open[b1] = 95.8
            close[b1] = 95.6
            high[b1] = 96.0
            low[b1] = 95.4
            volume[b1] = 1100.0

            // Bar 0: Green reversal bar hooking back up to 96.5 (Z ~ -1.75)
            val b0 = 0
            openTime[b0] = baseTime + ((count - 1 - b0) * stepMs)
            open[b0] = 95.6
            close[b0] = 96.5
            high[b0] = 96.8
            low[b0] = 95.5
            volume[b0] = 1400.0

            return CandleSeries.fromArrays("B-BTC_USDT", Interval.M15, openTime, open, high, low, close, volume)
        }
    }

    // =========================================================================
    // Strategy 8 (IRC) Tests
    // =========================================================================

    @Test
    fun `test IrcStrategy rejects insufficient history`() {
        val series = buildIrcBullishSetup().subSeries(0, 30)
        val strategy = IrcStrategy()
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )
        val res = strategy.evaluate(ctx, null)
        assertNull(res.signal)
        assertTrue(res.rejections.contains(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
    }

    @Test
    fun `test IrcStrategy triggers Bullish continuation on valid Fib retest`() {
        val series = buildIrcBullishSetup()
        val strategy = IrcStrategy(stopVariant = IrcStopVariant.IRC_FIB618_STOP)
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )

        val res = strategy.evaluate(ctx, null)
        assertNotNull("IRC should trigger Long signal. Rejections: ${res.rejections}", res.signal)
        val sig = res.signal!!

        assertEquals(SignalDirection.LONG, sig.direction)
        assertEquals(RegimeTag.EXPANSION, sig.regimeTag)
        assertTrue("Stop must be below entry", sig.stopLoss < sig.entryRef)
        assertTrue("Target must be above entry", (sig.target as Target.Fixed).tp1 > sig.entryRef)
        for ((k, v) in sig.strengths) {
            assertTrue("Strength '$k' ($v) must be in 0.0..1.0", v in 0.0..1.0)
        }
    }

    @Test
    fun `test IrcStrategy evaluates stop variant comparison`() {
        val series = buildIrcBullishSetup()
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )

        val fibStrategy = IrcStrategy(stopVariant = IrcStopVariant.IRC_FIB618_STOP)
        val swingStrategy = IrcStrategy(stopVariant = IrcStopVariant.IRC_SWING_STOP)

        val sigFib = fibStrategy.evaluate(ctx, null).signal!!
        val sigSwing = swingStrategy.evaluate(ctx, null).signal!!

        // Swing stop is at impulse low (~99.8) while Fib618 stop is at Fib618 (~102.1)
        // Therefore, swing stop must be lower (wider risk distance) than Fib618 stop
        assertTrue("Swing stop should be lower than Fib618 stop", sigSwing.stopLoss < sigFib.stopLoss)
        assertTrue("Swing stop risk distance should be larger", sigSwing.riskDistance > sigFib.riskDistance)
    }

    // =========================================================================
    // Strategy 7 (RZMR) Tests
    // =========================================================================

    @Test
    fun `test RzmrStrategy rejects insufficient history`() {
        val series = buildRzmrOversoldSetup().subSeries(0, 50)
        val strategy = RzmrStrategy()
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )
        val res = strategy.evaluate(ctx, null)
        assertNull(res.signal)
        assertTrue(res.rejections.contains(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
    }

    @Test
    fun `test RzmrStrategy triggers Oversold Long Mean Reversion`() {
        val series = buildRzmrOversoldSetup()
        val strategy = RzmrStrategy(minChop = 10.0, minRangeMaturityBars = 5, minAtrPct = 0.40, minNetRR = 1.0)
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )
        val state = com.coindcx.trading.engine.state.RangeMaturityState(
            consecutiveBarsInRange = 20,
            lastUpdatedBarOpenTime = series.openTime(1)
        )

        val res = strategy.evaluate(ctx, state)
        assertNotNull("RZMR should trigger Long mean reversion. Rejections: ${res.rejections}", res.signal)
        val sig = res.signal!!

        assertEquals(SignalDirection.LONG, sig.direction)
        assertEquals(RegimeTag.RANGE, sig.regimeTag)
        assertTrue("Stop must be below entry", sig.stopLoss < sig.entryRef)
        val target = sig.target as Target.Fixed
        assertTrue("Target must be towards the mean (above entry)", target.tp1 > sig.entryRef)
        assertTrue("Planned RR must be >= 1.0", target.plannedRR >= 1.0)
    }

    // =========================================================================
    // Replay Backtests on Real BTC Data (35,000 bars)
    // =========================================================================

    @Test
    fun `test Replay Backtest of Strategy 8 IRC on 15m BTC Data`() {
        val series = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val strategy = IrcStrategy(
            stopVariant = IrcStopVariant.IRC_FIB618_STOP,
            impulseAtrMultiplier = 2.0,
            impulseVolMultiplier = 1.8,
            minAtrPct = 0.45,
            maxAtrPct = 8.0,
            plannedRR = 2.0
        )

        val engine = ReplayEngine(takerFeePct = 0.0005, slippagePct = 0.0002)
        val summary = engine.replay(series = series, strategy = strategy)

        println("=================================================================")
        println("  S8 IRC REPLAY BACKTEST (15m BTC Futures, 35,000 candles)")
        println("=================================================================")
        println("Total Bars Analyzed : ${summary.totalBars}")
        println("Total Trades        : ${summary.totalTrades}")
        println("Wins / Losses       : ${summary.winningTrades} / ${summary.losingTrades}")
        println("Win Rate            : ${"%.2f".format(summary.winRatePct)}%")
        println("Profit Factor       : ${"%.2f".format(summary.profitFactor)}")
        println("Total Return        : ${"%.2f".format(summary.totalReturnPct)}%")
        println("Max Drawdown        : ${"%.2f".format(summary.maxDrawdownPct)}%")
        println("Sharpe Ratio        : ${"%.2f".format(summary.sharpeRatio)}")
        println("Avg Bars Held       : ${"%.1f".format(summary.avgBarsHeld)}")
        println("=================================================================")

        assertTrue("Should analyze all 35k bars", summary.totalBars > 30000)
    }

    @Test
    fun `test Replay Backtest of Strategy 7 RZMR on 15m BTC Data`() {
        val series = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val strategy = RzmrStrategy(
            zPeriod = 50,
            zThreshold = 2.0,
            minAtrPct = 0.55,
            maxAdx = 22.0,
            minChop = 55.0,
            plannedRR = 1.5
        )

        val engine = ReplayEngine(takerFeePct = 0.0005, slippagePct = 0.0002)
        val summary = engine.replay(series = series, strategy = strategy)

        println("=================================================================")
        println("  S7 RZMR REPLAY BACKTEST (15m BTC Futures, 35,000 candles)")
        println("=================================================================")
        println("Total Bars Analyzed : ${summary.totalBars}")
        println("Total Trades        : ${summary.totalTrades}")
        println("Wins / Losses       : ${summary.winningTrades} / ${summary.losingTrades}")
        println("Win Rate            : ${"%.2f".format(summary.winRatePct)}%")
        println("Profit Factor       : ${"%.2f".format(summary.profitFactor)}")
        println("Total Return        : ${"%.2f".format(summary.totalReturnPct)}%")
        println("Max Drawdown        : ${"%.2f".format(summary.maxDrawdownPct)}%")
        println("Sharpe Ratio        : ${"%.2f".format(summary.sharpeRatio)}")
        println("Avg Bars Held       : ${"%.1f".format(summary.avgBarsHeld)}")
        println("=================================================================")

        assertTrue("Should analyze all 35k bars", summary.totalBars > 30000)
    }
}
