package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.telemetry.RejectionCode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy 1: Pullback Continuation (PBC) (§1.1).
 *
 * Evidence Class: Trend Following / Momentum Pullback.
 * Enters in the direction of strong stacked-EMA trends on orderly shallow retests
 * of the 9/21 EMA zone, confirmed by 4H trend bias and clean reclaim bars.
 */
class PbcStrategy(
    val fastPeriod: Int = 9,
    val midPeriod: Int = 21,
    val slowPeriod: Int = 50,
    val atrPeriod: Int = 14,
    val adxMin: Double = 22.0,
    val adxMax: Double = 50.0,
    val minEr: Double = 0.40,
    val plannedRR: Double = 0.75,
    val expiryBars: Int = 16
) : Strategy {

    override val id: String = "pbc"
    override val name: String = "Pullback Continuation Strategy"
    override val description: String = "Trend-following strategy entering on shallow 9/21 EMA pullbacks with multi-timeframe 4H alignment and volume-confirmed reclaim."
    override val requiredCandleCount: Int = max(slowPeriod + 20, 70)
    override val primaryInterval: Interval = Interval.M15
    override val requiredIntervals: Set<Interval> = setOf(Interval.M15, Interval.H4)
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.TRENDING_MOMENTUM
    override val parametersSummary: String = "EMAs: $fastPeriod/$midPeriod/$slowPeriod, ADX: [$adxMin..$adxMax], R:R: 1:${"%.2f".format(plannedRR)}"

    override fun evaluate(ctx: SymbolContext, state: StrategyState?): StrategyResult {
        val series = ctx.primarySeries
        val rejections = mutableListOf<RejectionCode>()

        if (series.size < requiredCandleCount) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
        }

        val currClose = series.close(0)
        val atr = TechnicalIndicators.calculateAtr(series, atrPeriod, barIndex = 0)
        val emaFast = TechnicalIndicators.calculateEmaAt(series, fastPeriod, barIndex = 0)
        val emaMid = TechnicalIndicators.calculateEmaAt(series, midPeriod, barIndex = 0)
        val emaSlow = TechnicalIndicators.calculateEmaAt(series, slowPeriod, barIndex = 0)

        // 0. Active Position Exit Management
        val activePos = ctx.activePosition
        if (activePos != null && activePos.isOpen) {
            if (activePos.isLong && currClose < (emaMid - 0.3 * atr)) {
                return StrategyResult(
                    signal = Signal(
                        symbol = ctx.symbol, strategyId = id, direction = SignalDirection.LONG,
                        barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                        reason = "PBC Long EXIT: Trend body broken (Close %.2f < EMA%d - 0.3*ATR %.2f)".format(currClose, midPeriod, emaMid - 0.3 * atr),
                        explicitAction = SignalAction.EXIT
                    ), newState = state
                )
            } else if (activePos.isShort && currClose > (emaMid + 0.3 * atr)) {
                return StrategyResult(
                    signal = Signal(
                        symbol = ctx.symbol, strategyId = id, direction = SignalDirection.SHORT,
                        barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                        reason = "PBC Short EXIT: Trend body broken (Close %.2f > EMA%d + 0.3*ATR %.2f)".format(currClose, midPeriod, emaMid + 0.3 * atr),
                        explicitAction = SignalAction.EXIT
                    ), newState = state
                )
            }
        }

        // 1. Higher-Timeframe (4H) Trend Bias
        val htf4H = ctx.htfSeries[Interval.H4]
        var htfBullish = true
        var htfBearish = true
        if (htf4H != null && htf4H.size >= 50) {
            val htfEma50 = TechnicalIndicators.calculateEmaAt(htf4H, 50, barIndex = 0)
            val htfClose = htf4H.close(0)
            htfBullish = htfClose >= htfEma50
            htfBearish = htfClose <= htfEma50
        }

        // 2. Primary 15m Stacked EMAs

        val isBullishStack = emaFast > emaMid && emaMid > emaSlow
        val isBearishStack = emaFast < emaMid && emaMid < emaSlow

        if (!isBullishStack && !isBearishStack) {
            return StrategyResult(null, state, listOf(RejectionCode.S1_REGIME_STACKED_EMA))
        }

        // 3. ADX & Efficiency Ratio Bounds
        val adx = TechnicalIndicators.calculateAdx(series, 14, barIndex = 0)
        if (adx < adxMin || adx > adxMax) {
            rejections.add(RejectionCode.S1_REGIME_ADX_BOUNDS)
        }

        val er = TechnicalIndicators.calculateEfficiencyRatio(series, 20, barIndex = 0)
        if (er < minEr) {
            rejections.add(RejectionCode.S1_REGIME_EFFICIENCY)
        }

        // 4. Bar & Volatility Calculations
        val currOpen = series.open(0)
        val barRange = series.high(0) - series.low(0)
        val safeRange = if (barRange > 0.0) barRange else 0.0001
        val volSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = 0)
        val relVol = if (volSma > 0.0) series.volume(0) / volSma else 1.0

        // 5. Long Evaluation
        if (isBullishStack) {
            if (!htfBullish) rejections.add(RejectionCode.S1_REGIME_4H_ALIGNMENT)

            // Pullback condition: within last 1..5 bars, low touched or dipped below EMA fast
            var hadPullback = false
            var brokenSlow = false
            for (lookback in 1..min(5, series.size - 1)) {
                val pastEmaFast = TechnicalIndicators.calculateEmaAt(series, fastPeriod, barIndex = lookback)
                val pastEmaSlow = TechnicalIndicators.calculateEmaAt(series, slowPeriod, barIndex = lookback)
                if (series.low(lookback) <= pastEmaFast) {
                    hadPullback = true
                }
                if (series.low(lookback) < pastEmaSlow) {
                    brokenSlow = true
                }
            }

            if (!hadPullback) rejections.add(RejectionCode.S1_C2_NO_PULLBACK)
            if (brokenSlow) rejections.add(RejectionCode.S1_C4_PULLBACK_COLLAPSE)

            // Reclaim trigger at bar 0: close > emaFast and green candle
            val isReclaim = currClose > emaFast && currClose > currOpen
            if (!isReclaim) rejections.add(RejectionCode.S1_C5_RECLAIM_NOT_TRIGGERED)

            val closeLocation = (currClose - series.low(0)) / safeRange
            if (closeLocation < 0.60) rejections.add(RejectionCode.S1_C7_UPPER_HALF_CLOSE)

            if (rejections.isEmpty()) {
                // Stop loss: recent swing low - 0.5 ATR, clamped [1.4%..3.0%]
                var lowestLow = series.low(0)
                for (b in 1..min(3, series.size - 1)) {
                    if (series.low(b) < lowestLow) lowestLow = series.low(b)
                }
                val rawStop = lowestLow - 0.5 * atr
                val rawDist = currClose - rawStop
                val minSlDist = currClose * 0.014
                val maxSlDist = currClose * 0.030
                val clampedDist = rawDist.coerceIn(minSlDist, maxSlDist)
                val stopLoss = currClose - clampedDist
                val rawTpDist = clampedDist * plannedRR
                val clampedTpDist = rawTpDist.coerceIn(currClose * 0.015, currClose * 0.022)
                val takeProfit = currClose + clampedTpDist
                val riskPct = (clampedDist / currClose) * 100.0

                val strengths = mapOf(
                    "trendStrength" to ((adx - adxMin) / (adxMax - adxMin)).coerceIn(0.0, 1.0),
                    "reclaimStrength" to if (atr > 0.0) ((currClose - emaFast) / atr).coerceIn(0.0, 1.0) else 0.5,
                    "closeLocation" to closeLocation.coerceIn(0.0, 1.0),
                    "volumeParticipation" to (relVol / 2.0).coerceIn(0.0, 1.0)
                )

                val signal = Signal(
                    symbol = ctx.symbol,
                    strategyId = id,
                    direction = SignalDirection.LONG,
                    barOpenTimeUtc = series.openTime(0),
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = takeProfit, plannedRR = plannedRR),
                    riskDistance = clampedDist,
                    riskPct = riskPct,
                    regimeTag = RegimeTag.TREND_UP,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "PBC Long: 9/21 EMA pullback reclaim (Close %.2f > EMA9 %.2f) + ADX=%.1f".format(currClose, emaFast, adx),
                    confidenceScore = 80.0
                )

                return StrategyResult(signal = signal, newState = state)
            }
        }

        // 6. Short Evaluation
        if (isBearishStack) {
            if (!htfBearish) rejections.add(RejectionCode.S1_REGIME_4H_ALIGNMENT)

            // Pullback condition: within last 1..5 bars, high poked above or reached EMA fast
            var hadPullback = false
            var brokenSlow = false
            for (lookback in 1..min(5, series.size - 1)) {
                val pastEmaFast = TechnicalIndicators.calculateEmaAt(series, fastPeriod, barIndex = lookback)
                val pastEmaSlow = TechnicalIndicators.calculateEmaAt(series, slowPeriod, barIndex = lookback)
                if (series.high(lookback) >= pastEmaFast) {
                    hadPullback = true
                }
                if (series.high(lookback) > pastEmaSlow) {
                    brokenSlow = true
                }
            }

            if (!hadPullback) rejections.add(RejectionCode.S1_C2_NO_PULLBACK)
            if (brokenSlow) rejections.add(RejectionCode.S1_C4_PULLBACK_COLLAPSE)

            // Reclaim trigger at bar 0: close < emaFast and red candle
            val isReclaim = currClose < emaFast && currClose < currOpen
            if (!isReclaim) rejections.add(RejectionCode.S1_C5_RECLAIM_NOT_TRIGGERED)

            val closeLocation = (series.high(0) - currClose) / safeRange
            if (closeLocation < 0.60) rejections.add(RejectionCode.S1_C7_UPPER_HALF_CLOSE)

            if (rejections.isEmpty()) {
                var highestHigh = series.high(0)
                for (b in 1..min(3, series.size - 1)) {
                    if (series.high(b) > highestHigh) highestHigh = series.high(b)
                }
                val rawStop = highestHigh + 0.5 * atr
                val rawDist = rawStop - currClose
                val minSlDist = currClose * 0.014
                val maxSlDist = currClose * 0.030
                val clampedDist = rawDist.coerceIn(minSlDist, maxSlDist)
                val stopLoss = currClose + clampedDist
                val rawTpDist = clampedDist * plannedRR
                val clampedTpDist = rawTpDist.coerceIn(currClose * 0.015, currClose * 0.022)
                val takeProfit = currClose - clampedTpDist
                val riskPct = (clampedDist / currClose) * 100.0

                val strengths = mapOf(
                    "trendStrength" to ((adx - adxMin) / (adxMax - adxMin)).coerceIn(0.0, 1.0),
                    "reclaimStrength" to if (atr > 0.0) ((emaFast - currClose) / atr).coerceIn(0.0, 1.0) else 0.5,
                    "closeLocation" to closeLocation.coerceIn(0.0, 1.0),
                    "volumeParticipation" to (relVol / 2.0).coerceIn(0.0, 1.0)
                )

                val signal = Signal(
                    symbol = ctx.symbol,
                    strategyId = id,
                    direction = SignalDirection.SHORT,
                    barOpenTimeUtc = series.openTime(0),
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = takeProfit, plannedRR = plannedRR),
                    riskDistance = clampedDist,
                    riskPct = riskPct,
                    regimeTag = RegimeTag.TREND_DOWN,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "PBC Short: 9/21 EMA pullback reclaim (Close %.2f < EMA9 %.2f) + ADX=%.1f".format(currClose, emaFast, adx),
                    confidenceScore = 80.0
                )

                return StrategyResult(signal = signal, newState = state)
            }
        }

        val primaryRejection = if (rejections.isNotEmpty()) rejections else listOf(RejectionCode.S1_C2_NO_PULLBACK)
        return StrategyResult(signal = null, newState = state, rejections = primaryRejection)
    }
}
