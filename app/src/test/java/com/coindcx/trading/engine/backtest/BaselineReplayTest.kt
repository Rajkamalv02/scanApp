package com.coindcx.trading.engine.backtest

import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.strategies.ConfluenceStrategy
import com.coindcx.trading.engine.strategies.EmaCrossoverStrategy
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class BaselineReplayTest {

    companion object {
        private val dataFile = File("d:/Projects/scanApp/tools/backtesting/data/BTCUSDT_15m_35000.json")

        @BeforeClass
        @JvmStatic
        fun checkDataset() {
            assertTrue("Dataset file must exist: ${dataFile.absolutePath}", dataFile.exists())
        }
    }

    @Test
    fun `test BacktestDataLoader loads 35000 candles with strict integrity`() {
        val series = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        assertEquals(35000, series.size)
        assertEquals(Interval.M15, series.interval)

        // Time monotonicity: bar[0] is newest, bar[N-1] is oldest
        for (i in 0 until (series.size - 1)) {
            assertTrue(
                "Timestamps must strictly decrease backwards: openTime($i)=${series.openTime(i)} > openTime(${i+1})=${series.openTime(i+1)}",
                series.openTime(i) > series.openTime(i + 1)
            )
        }
    }

    @Test
    fun `test WalkForward 60-20-20 split integrity`() {
        val series = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val split = WalkForward.split60_20_20(series)

        assertEquals("Train count should be 60% of 35,000 = 21,000", 21000, split.trainSeries.size)
        assertEquals("Validation count should be 20% of 35,000 = 7,000", 7000, split.validationSeries.size)
        assertEquals("Test count should be remainder = 7,000", 7000, split.testSeries.size)

        // Train is oldest, Test is newest
        assertTrue(split.testSeries.openTime(0) > split.validationSeries.openTime(0))
        assertTrue(split.validationSeries.openTime(0) > split.trainSeries.openTime(0))
    }

    @Test
    fun `test Baseline EMA Crossover Replay Backtest`() {
        val series = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val strategy = EmaCrossoverStrategy(
            initialFastPeriod = 9,
            initialSlowPeriod = 21,
            initialAtrMultiplier = 1.5,
            riskRewardRatio = 2.0
        )

        val engine = ReplayEngine(takerFeePct = 0.0005, slippagePct = 0.0002)
        val summary = engine.replay(series, strategy)

        println("=================================================================")
        println("  BASELINE BACKTEST: ${summary.strategyId.uppercase()}")
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
        println("Avg Trade PnL       : ${"%+.2f".format(summary.avgTradePnlPct)}%")
        println("=================================================================")

        assertTrue("EMA strategy should generate trades over 35,000 bars", summary.totalTrades > 50)
        assertTrue("Win rate must be valid percentage", summary.winRatePct in 0.0..100.0)
        assertTrue("Max drawdown must be non-negative", summary.maxDrawdownPct >= 0.0)
        assertTrue("Average bars held must be >= 1", summary.avgBarsHeld >= 1.0)
    }

    @Test
    fun `test Baseline Confluence Strategy Replay Backtest`() {
        val series = BacktestDataLoader.loadFromFile(dataFile, Interval.M15, "B-BTC_USDT")
        val strategy = ConfluenceStrategy()

        val engine = ReplayEngine(takerFeePct = 0.0005, slippagePct = 0.0002)
        val summary = engine.replay(series, strategy)

        println("=================================================================")
        println("  BASELINE BACKTEST: ${summary.strategyId.uppercase()}")
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
        assertTrue("Win rate must be valid percentage", summary.winRatePct in 0.0..100.0)
    }
}
