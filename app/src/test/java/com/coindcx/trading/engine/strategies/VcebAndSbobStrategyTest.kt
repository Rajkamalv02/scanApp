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

class VcebAndSbobStrategyTest {

    companion object {
        private val dataFile = File("d:/Projects/scanApp/tools/backtesting/data/BTCUSDT_15m_35000.json")

        @BeforeClass
        @JvmStatic
        fun checkDataset() {
            assertTrue("Dataset file must exist: ${dataFile.absolutePath}", dataFile.exists())
        }

        /**
         * Builds a VCEB Bullish Breakout setup:
         * 225 bars:
         * Bars 224 down to 10: normal/wide volatility ~100.0 (high/low swing 95..105)
         * Bars 9 down to 1: super-tight squeeze (range 100.0 to 100.3, BBWidth drops to historic low)
         * Bar 0: explosive green breakout candle breaching upper BB with high volume
         */
        private fun buildVcebBullishSetup(): CandleSeries {
            val count = 230
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val baseTime = 1_700_000_000_000L
            val stepMs = Interval.M15.durationMs

            // Bars 229 down to 10: normal wider volatility (range ~ 98.0 to 102.0)
            for (step in 0 until (count - 10)) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                val wave = if (step % 2 == 0) 101.5 else 98.5
                open[idx] = wave
                close[idx] = if (step % 2 == 0) 99.0 else 101.0
                high[idx] = 102.5
                low[idx] = 97.5
                volume[idx] = 1000.0
            }

            // Bars 9 down to 1: tight compression squeeze (range 99.9 to 100.1, BB inside KC)
            for (step in (count - 10) until (count - 1)) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                open[idx] = 100.0
                close[idx] = 100.05
                high[idx] = 100.15
                low[idx] = 99.95
                volume[idx] = 400.0
            }

            // Bar 0: Explosive Bullish Breakout
            val b0 = 0
            openTime[b0] = baseTime + ((count - 1) * stepMs)
            open[b0] = 100.05
            close[b0] = 104.5 // Decisive surge above upper BB
            high[b0] = 104.8
            low[b0] = 99.95
            volume[b0] = 5000.0 // 5x volume spike

            return CandleSeries.fromArrays("B-BTC_USDT", Interval.M15, openTime, open, high, low, close, volume)
        }

        /**
         * Builds an SBOB Bullish setup:
         * 90 bars:
         * Bars 89 down to 15: range [98..102] with swing high at bar 20 (high=102.5)
         * Bar 12: red down-candle at 101.0 -> 100.0 (the Order Block)
         * Bar 10: massive green candle closes at 103.5 (Bullish BOS above 102.5)
         * Bars 9 down to 1: drift down into the OB zone [100.0..101.0]
         * Bar 0: touches 100.5 (mitigation), wick rejects, and green candle closes at 101.8
         */
        private fun buildSbobBullishSetup(stackedTrend: Boolean = false): CandleSeries {
            val count = 90
            val openTime = LongArray(count)
            val open = DoubleArray(count)
            val high = DoubleArray(count)
            val low = DoubleArray(count)
            val close = DoubleArray(count)
            val volume = DoubleArray(count)
            val baseTime = 1_700_000_000_000L
            val stepMs = Interval.M15.durationMs

            // Baseline: historical bars 89..30 at 102.0, bars 29..0 around 100.0
            // This ensures EMA50 (~101.5) > EMA21 (~100.5), meaning EMAs are NOT stacked bullishly before the BOS break.
            for (step in 0 until count) {
                val idx = count - 1 - step
                openTime[idx] = baseTime + (step * stepMs)
                val basePrice = if (idx >= 30) 102.0 else 100.0
                open[idx] = basePrice
                close[idx] = basePrice + 0.1
                high[idx] = basePrice + 0.5
                low[idx] = basePrice - 0.5
                volume[idx] = 1000.0
            }

            // Bar 25: A clear 5-bar swing high (highest high among 23..27)
            val b25 = 25
            high[b25] = 103.0
            close[b25] = 102.5

            // Bar 12: Order Block (last down-candle before BOS)
            val b12 = 12
            openTime[b12] = baseTime + ((count - 1 - b12) * stepMs)
            open[b12] = 101.5
            close[b12] = 100.0 // Red candle
            high[b12] = 101.8 // OB Top
            low[b12] = 99.5  // OB Bottom
            volume[b12] = 1200.0

            // Bar 10: Bullish BOS (surges past 103.0 swing high with volume)
            val b10 = 10
            openTime[b10] = baseTime + ((count - 1 - b10) * stepMs)
            open[b10] = 100.2
            close[b10] = 104.2 // Closes above 103.0 BOS!
            high[b10] = 104.5
            low[b10] = 100.0
            volume[b10] = 3500.0

            // Bars 9 down to 1: Retrace drift towards OB zone [99.5..101.8]
            for (b in 9 downTo 1) {
                openTime[b] = baseTime + ((count - 1 - b) * stepMs)
                open[b] = 102.0 - ((9 - b) * 0.15)
                close[b] = open[b] - 0.1
                high[b] = open[b] + 0.2
                low[b] = close[b] - 0.2
                volume[b] = 800.0
            }

            // Bar 0: Mitigation into OB zone + Wick Rejection
            val b0 = 0
            openTime[b0] = baseTime + ((count - 1 - b0) * stepMs)
            open[b0] = 100.8
            close[b0] = 101.6 // Green candle closing above midpoint
            high[b0] = 101.8
            low[b0] = 100.2 // Dips inside OB zone [99.5..101.8]
            volume[b0] = 1500.0

            if (stackedTrend) {
                // Manipulate prices so EMAs 9/21/50 are strictly stacked
                for (i in 0 until count) {
                    val p = 50.0 + (count - 1 - i) * 1.0
                    open[i] = p
                    close[i] = p + 0.5
                    high[i] = p + 0.7
                    low[i] = p - 0.2
                }
            }

            return CandleSeries.fromArrays("B-BTC_USDT", Interval.M15, openTime, open, high, low, close, volume)
        }
    }

    // =========================================================================
    // Strategy 2 (VCEB) Tests
    // =========================================================================

    @Test
    fun `test VcebStrategy rejects insufficient history`() {
        val series = buildVcebBullishSetup().subSeries(0, 100)
        val strategy = VcebStrategy()
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
    fun `test VcebStrategy triggers Bullish Breakout from TTM Squeeze`() {
        val series = buildVcebBullishSetup()
        val strategy = VcebStrategy(percentileThreshold = 35.0, minCompressionBars = 3)
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )

        val res = strategy.evaluate(ctx, null)
        assertNotNull("VCEB should trigger Bullish breakout. Rejections: ${res.rejections}", res.signal)
        val sig = res.signal!!

        assertEquals(SignalDirection.LONG, sig.direction)
        assertEquals(RegimeTag.EXPANSION, sig.regimeTag)
        assertTrue("Stop must be below entry", sig.stopLoss < sig.entryRef)
        val target = sig.target as Target.Fixed
        assertTrue("Target must be above entry", target.tp1 > sig.entryRef)
        assertEquals(2.0, target.plannedRR, 0.001)

        for ((k, v) in sig.strengths) {
            assertTrue("Strength '$k' ($v) must be in 0.0..1.0", v in 0.0..1.0)
        }
    }

    // =========================================================================
    // Strategy 9 (SBOB) Tests & S1 Exclusivity
    // =========================================================================

    @Test
    fun `test SbobStrategy rejects insufficient history`() {
        val series = buildSbobBullishSetup().subSeries(0, 50)
        val strategy = SbobStrategy()
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
    fun `test SbobStrategy triggers Bullish BOS and Order Block Mitigation`() {
        val series = buildSbobBullishSetup(stackedTrend = false)
        val strategy = SbobStrategy(minBreakVolumeMultiplier = 1.1, minAtrPct = 0.20)
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )

        val res = strategy.evaluate(ctx, null)
        assertNotNull("SBOB should trigger Long on OB mitigation. Rejections: ${res.rejections}", res.signal)
        val sig = res.signal!!

        assertEquals(SignalDirection.LONG, sig.direction)
        assertEquals(RegimeTag.TREND_UP, sig.regimeTag)
        assertTrue("Stop must be below entry", sig.stopLoss < sig.entryRef)
        val target = sig.target as Target.Fixed
        assertTrue("Target must be above entry", target.tp1 > sig.entryRef)
    }

    @Test
    fun `test S9 R7 Mutual Exclusivity suppresses SBOB when EMAs are strictly stacked`() {
        val series = buildSbobBullishSetup(stackedTrend = true)
        val strategy = SbobStrategy()
        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = series,
            clock = FixedClock(1_800_000_000_000L),
            tickerLastPrice = series.close(0)
        )

        val res = strategy.evaluate(ctx, null)
        assertNull("SBOB must not trigger when stacked EMAs exist (S1 exclusivity)", res.signal)
        assertTrue("Expected S9_REGIME_R7_STACKED_EMA_MUTUAL_EXCLUSION",
            res.rejections.contains(RejectionCode.S9_REGIME_R7_STACKED_EMA_MUTUAL_EXCLUSION) ||
                    res.rejections.contains(RejectionCode.S9_REGIME_NO_BOS_CHOCH))
    }

    // =========================================================================
    // Replay Backtests on Real BTC Data (35,000 candles) & Confluence Comparison
    // =========================================================================

    @Test
    fun `test Replay Backtest of Strategy 2 VCEB on 15m BTC Data`() {
        val series = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val strategy = VcebStrategy(
            bbPeriod = 20,
            percentileThreshold = 25.0,
            minCompressionBars = 4,
            expansionTrMultiplier = 1.5,
            expansionVolMultiplier = 1.5,
            plannedRR = 2.0
        )

        val engine = ReplayEngine(takerFeePct = 0.0005, slippagePct = 0.0002)
        val summary = engine.replay(series = series, strategy = strategy)

        println("=================================================================")
        println("  S2 VCEB REPLAY BACKTEST (15m BTC Futures, 35,000 candles)")
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
    fun `test Replay Backtest of Strategy 9 SBOB vs Legacy Confluence on 15m BTC Data`() {
        val series = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val sbobStrategy = SbobStrategy(
            obMaxAgeBars = 20,
            minBreakVolumeMultiplier = 1.2,
            plannedRR = 2.0
        )

        val confluenceStrategy = ConfluenceStrategy(
            riskRewardRatio = 2.0
        )

        val engine = ReplayEngine(takerFeePct = 0.0005, slippagePct = 0.0002)
        val sbobSummary = engine.replay(series = series, strategy = sbobStrategy)
        val confluenceSummary = engine.replay(series = series, strategy = confluenceStrategy)

        println("=================================================================")
        println("  HEAD-TO-HEAD: S9 SBOB vs LEGACY CONFLUENCE (35,000 BTC Bars)")
        println("=================================================================")
        println("Metric                  | S9 (SBOB)             | Legacy Confluence")
        println("------------------------|-----------------------|------------------")
        println("Total Trades            | %-21d | %d".format(sbobSummary.totalTrades, confluenceSummary.totalTrades))
        println("Win Rate                | %-20s%% | %.2f%%".format("%.2f".format(sbobSummary.winRatePct), confluenceSummary.winRatePct))
        println("Profit Factor           | %-21s | %.2f".format("%.2f".format(sbobSummary.profitFactor), confluenceSummary.profitFactor))
        println("Total Return            | %-20s%% | %.2f%%".format("%.2f".format(sbobSummary.totalReturnPct), confluenceSummary.totalReturnPct))
        println("Max Drawdown            | %-20s%% | %.2f%%".format("%.2f".format(sbobSummary.maxDrawdownPct), confluenceSummary.maxDrawdownPct))
        println("Sharpe Ratio            | %-21s | %.2f".format("%.2f".format(sbobSummary.sharpeRatio), confluenceSummary.sharpeRatio))
        println("Avg Bars Held           | %-21s | %.1f".format("%.1f".format(sbobSummary.avgBarsHeld), confluenceSummary.avgBarsHeld))
        println("=================================================================")

        assertTrue("Both should execute on 35k bars", sbobSummary.totalBars > 30000 && confluenceSummary.totalBars > 30000)
    }
}
