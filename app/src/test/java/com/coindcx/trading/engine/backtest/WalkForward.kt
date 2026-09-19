package com.coindcx.trading.engine.backtest

import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.data.CandleSeries

data class WalkForwardSplit(
    val trainSeries: CandleSeries,
    val validationSeries: CandleSeries,
    val testSeries: CandleSeries
)

data class WalkForwardResult(
    val strategyId: String,
    val trainSummary: BacktestSummary,
    val validationSummary: BacktestSummary,
    val testSummary: BacktestSummary
)

object WalkForward {

    /**
     * Splits series chronologically into Train (60%), Validation (20%), and Test (20%).
     * Note: in CandleSeries, index 0 is newest bar, index (N-1) is oldest bar.
     * Chronological order:
     * - Train (oldest 60%): chronological bars [0 .. trainEnd] -> offset in CandleSeries: [N - trainEnd .. N]
     * - Validation (middle 20%): chronological bars [trainEnd .. valEnd]
     * - Test (newest 20%): chronological bars [valEnd .. N] -> offset in CandleSeries: [0 .. testCount]
     */
    fun split60_20_20(series: CandleSeries): WalkForwardSplit {
        val total = series.size
        require(total >= 100) { "Series size ($total) must be at least 100 for walk-forward splitting." }

        val trainCount = (total * 0.60).toInt()
        val valCount = (total * 0.20).toInt()
        val testCount = total - trainCount - valCount

        // In CandleSeries (0 = newest, total - 1 = oldest):
        // Oldest block (Train): offset is valCount + testCount, length is trainCount
        val trainOffset = valCount + testCount
        val trainSeries = series.subSeries(trainOffset, trainCount)

        // Middle block (Validation): offset is testCount, length is valCount
        val valOffset = testCount
        val valSeries = series.subSeries(valOffset, valCount)

        // Newest block (Test): offset is 0, length is testCount
        val testSeries = series.subSeries(0, testCount)

        return WalkForwardSplit(
            trainSeries = trainSeries,
            validationSeries = valSeries,
            testSeries = testSeries
        )
    }

    fun evaluate(
        series: CandleSeries,
        strategy: Strategy,
        engine: ReplayEngine = ReplayEngine()
    ): WalkForwardResult {
        val split = split60_20_20(series)

        val trainRes = engine.replay(split.trainSeries, strategy)
        val valRes = engine.replay(split.validationSeries, strategy)
        val testRes = engine.replay(split.testSeries, strategy)

        return WalkForwardResult(
            strategyId = strategy.id,
            trainSummary = trainRes,
            validationSummary = valRes,
            testSummary = testRes
        )
    }
}
