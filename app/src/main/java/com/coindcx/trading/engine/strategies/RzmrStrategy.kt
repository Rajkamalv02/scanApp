package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.state.RangeMaturityState
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.telemetry.RejectionCode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy 7: Range-Bound Z-Score Mean Reversion (RZMR) (§1.7).
 *
 * Evidence Class: Statistical Mean Reversion in Verified Consolidations.
 * Exploits bounded oscillatory dynamics by fading 2-sigma price extremities
 * back toward the 50-period SMA, verified by ADX, Choppiness Index,
 * 20-bar regime maturity, and standard deviation / ATR coherence checks.
 */
class RzmrStrategy(
    val zPeriod: Int = 50,
    val zThreshold: Double = 1.8,
    val minAtrPct: Double = 0.55, // G3 override for 15m mean reversion
    val maxAtrPct: Double = 6.0,
    val maxAdx: Double = 25.0,
    val minChop: Double = 52.0,
    val minRangeMaturityBars: Int = 15,
    val plannedRR: Double = 1.5,
    val minNetRR: Double = 1.2,
    val expiryBars: Int = 12
) : Strategy {

    override val id: String = "rzmr"
    override val name: String = "Range-Bound Z-Score Mean Reversion Strategy"
    override val description: String = "Statistical mean reversion fading 2-sigma extremities back towards the 50-period mean in verified non-trending consolidations."
    override val requiredCandleCount: Int = 70
    override val primaryInterval: Interval = Interval.M15
    override val requiredIntervals: Set<Interval> = setOf(Interval.M15)
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.MEAN_REVERTING_RANGE
    override val parametersSummary: String = "Z(50) >= $zThreshold, ADX <= $maxAdx, CHOP >= $minChop, MinMaturity: ${minRangeMaturityBars}b"

    override fun evaluate(ctx: SymbolContext, state: StrategyState?): StrategyResult {
        val series = ctx.primarySeries
        val rejections = mutableListOf<RejectionCode>()

        if (series.size < requiredCandleCount) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
        }

        // 1. G3/G4 ATR Bounds
        val atr = TechnicalIndicators.calculateAtr(series, 14, barIndex = 0)
        val currClose = series.close(0)
        val atrPct = if (currClose > 0.0) (atr / currClose) * 100.0 else 0.0

        if (atrPct < minAtrPct) {
            rejections.add(RejectionCode.S7_REGIME_ATR_FLOOR)
        }
        if (atrPct > maxAtrPct) {
            rejections.add(RejectionCode.GATE_G4_ATR_CEILING)
        }

        // 2. Range Regime Validation (ADX & Choppiness)
        val adx = TechnicalIndicators.calculateAdx(series, 14, barIndex = 0)
        if (adx > maxAdx) {
            rejections.add(RejectionCode.S7_REGIME_ADX_CAP)
        }

        val chop = TechnicalIndicators.calculateChoppiness(series, 14, barIndex = 0)
        if (chop < minChop) {
            rejections.add(RejectionCode.S7_REGIME_CHOP_FLOOR)
        }

        // 3. Higher-Timeframe 4H Range Validation (if provided)
        val htf4H = ctx.htfSeries[Interval.H4]
        if (htf4H != null && htf4H.size >= 30) {
            val htfAdx = TechnicalIndicators.calculateAdx(htf4H, 14, barIndex = 0)
            if (htfAdx > 30.0) {
                rejections.add(RejectionCode.S7_REGIME_4H_CONTAINMENT)
            }
        }

        // 4. Mean Drift Stability (SMA50 slope <= 1.0% over 10 bars)
        val sma0 = TechnicalIndicators.calculateSmaAt(series, zPeriod, barIndex = 0)
        val sma10 = TechnicalIndicators.calculateSmaAt(series, zPeriod, barIndex = 10)
        if (sma10 > 0.0 && (abs(sma0 - sma10) / sma10) > 0.010) {
            rejections.add(RejectionCode.S7_REGIME_MEAN_DRIFT)
        }

        // 5. Range Maturity State Tracking
        val isInRange = adx <= maxAdx && chop >= minChop
        val maturityCount = if (state is RangeMaturityState) {
            if (isInRange) state.consecutiveBarsInRange + 1 else 0
        } else {
            if (isInRange) minRangeMaturityBars else 0
        }
        val newState = RangeMaturityState(maturityCount, series.openTime(0))

        if (maturityCount < minRangeMaturityBars) {
            rejections.add(RejectionCode.S7_REGIME_MATURITY_BARS)
        }

        // 6. C10 Coherence Ratio: 0.8 <= (Stdev50 / ATR14) <= 2.5
        val stdev50 = TechnicalIndicators.calculateStdev(series, zPeriod, barIndex = 0)
        val coherenceRatio = if (atr > 0.0) stdev50 / atr else 1.0
        if (coherenceRatio < 0.8 || coherenceRatio > 2.5) {
            rejections.add(RejectionCode.S7_C10_COHERENCE_RATIO)
        }

        // 7. Outlier / Breakout Protection
        val z0 = TechnicalIndicators.calculateZScore(series, zPeriod, barIndex = 0)
        val z1 = TechnicalIndicators.calculateZScore(series, zPeriod, barIndex = 1)
        val z2 = TechnicalIndicators.calculateZScore(series, zPeriod, barIndex = 2)

        if (abs(z0) > 3.0 || abs(z1) > 3.0 || abs(z2) > 3.0) {
            rejections.add(RejectionCode.S7_C9_RECENT_3SIGMA_CLOSE)
        }

        val volSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = 0)
        if (volSma > 0.0 && series.volume(0) > 2.5 * volSma) {
            rejections.add(RejectionCode.S7_C8_VOLUME_CAP)
        }

        // 8. Z-Score Trigger & Directional Extremity
        val isOversold = z1 <= -zThreshold || z0 <= -zThreshold
        val isOverbought = z1 >= zThreshold || z0 >= zThreshold

        if (!isOversold && !isOverbought) {
            rejections.add(RejectionCode.S7_C2_ZSCORE_THRESHOLD)
        }

        val barRange = series.high(0) - series.low(0)
        val safeRange = if (barRange > 0.0) barRange else 0.0001

        // Long Setup (Oversold -> Hook Back to Mean)
        if (isOversold) {
            if (z0 <= z1) {
                rejections.add(RejectionCode.S7_C3_ZSCORE_NOT_TURNING)
            }
            if (series.close(0) <= series.open(0)) {
                rejections.add(RejectionCode.S7_C4_CANDLE_DIRECTION)
            }
            val closeLocation = (series.close(0) - series.low(0)) / safeRange
            if (closeLocation < 0.50) {
                rejections.add(RejectionCode.S7_C5_CLOSE_LOCATION)
            }
            if (series.close(0) > (sma0 - 0.6 * stdev50)) {
                rejections.add(RejectionCode.S7_C7_OUTER_THIRD_POSITION)
            }

            if (rejections.isEmpty()) {
                val lowestRecent = min(series.low(0), min(series.low(1), series.low(2)))
                val rawStop = lowestRecent - 0.5 * atr
                val rawDist = currClose - rawStop
                val clampedDist = rawDist.coerceIn(1.0 * atr, 2.5 * atr)
                val stopLoss = currClose - clampedDist

                // Target: 50-period SMA (the mean)
                val targetPrice = sma0
                val netDistToMean = targetPrice - currClose
                val netRR = if (clampedDist > 0.0) netDistToMean / clampedDist else 0.0

                if (netRR < 1.3) {
                    return StrategyResult(null, newState, listOf(RejectionCode.S7_RR_GATE))
                }

                val strengths = mapOf(
                    "zScoreDepth" to ((abs(z1) - zThreshold) / 1.5).coerceIn(0.0, 1.0),
                    "hookMagnitude" to ((z0 - z1) / 0.5).coerceIn(0.0, 1.0),
                    "coherenceScore" to (1.0 - abs(coherenceRatio - 1.5) / 1.0).coerceIn(0.0, 1.0),
                    "rangeMaturity" to (maturityCount.toDouble() / 30.0).coerceIn(0.0, 1.0)
                )

                val signal = Signal(
                    symbol = ctx.symbol,
                    strategyId = id,
                    direction = SignalDirection.LONG,
                    barOpenTimeUtc = series.openTime(0),
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = targetPrice, plannedRR = netRR),
                    riskDistance = clampedDist,
                    riskPct = (clampedDist / currClose) * 100.0,
                    regimeTag = RegimeTag.RANGE,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "RZMR Long: Oversold Z=%.2f hooked up to Z=%.2f. Mean target=%.2f (R:R 1:%.1f)".format(z1, z0, targetPrice, netRR),
                    confidenceScore = 78.0
                )

                return StrategyResult(signal = signal, newState = newState)
            }
        }

        // Short Setup (Overbought -> Hook Back to Mean)
        if (isOverbought) {
            if (z0 >= z1) {
                rejections.add(RejectionCode.S7_C3_ZSCORE_NOT_TURNING)
            }
            if (series.close(0) >= series.open(0)) {
                rejections.add(RejectionCode.S7_C4_CANDLE_DIRECTION)
            }
            val closeLocation = (series.high(0) - series.close(0)) / safeRange
            if (closeLocation < 0.50) {
                rejections.add(RejectionCode.S7_C5_CLOSE_LOCATION)
            }
            if (series.close(0) < (sma0 + 0.6 * stdev50)) {
                rejections.add(RejectionCode.S7_C7_OUTER_THIRD_POSITION)
            }

            if (rejections.isEmpty()) {
                val highestRecent = max(series.high(0), max(series.high(1), series.high(2)))
                val rawStop = highestRecent + 0.5 * atr
                val rawDist = rawStop - currClose
                val clampedDist = rawDist.coerceIn(1.0 * atr, 2.5 * atr)
                val stopLoss = currClose + clampedDist

                val targetPrice = sma0
                val netDistToMean = currClose - targetPrice
                val netRR = if (clampedDist > 0.0) netDistToMean / clampedDist else 0.0

                if (netRR < minNetRR) {
                    return StrategyResult(null, newState, listOf(RejectionCode.S7_RR_GATE))
                }

                val strengths = mapOf(
                    "zScoreDepth" to ((z1 - zThreshold) / 1.5).coerceIn(0.0, 1.0),
                    "hookMagnitude" to ((z1 - z0) / 0.5).coerceIn(0.0, 1.0),
                    "coherenceScore" to (1.0 - abs(coherenceRatio - 1.5) / 1.0).coerceIn(0.0, 1.0),
                    "rangeMaturity" to (maturityCount.toDouble() / 30.0).coerceIn(0.0, 1.0)
                )

                val signal = Signal(
                    symbol = ctx.symbol,
                    strategyId = id,
                    direction = SignalDirection.SHORT,
                    barOpenTimeUtc = series.openTime(0),
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = targetPrice, plannedRR = netRR),
                    riskDistance = clampedDist,
                    riskPct = (clampedDist / currClose) * 100.0,
                    regimeTag = RegimeTag.RANGE,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "RZMR Short: Overbought Z=%.2f hooked down to Z=%.2f. Mean target=%.2f (R:R 1:%.1f)".format(z1, z0, targetPrice, netRR),
                    confidenceScore = 78.0
                )

                return StrategyResult(signal = signal, newState = newState)
            }
        }

        return StrategyResult(null, newState, rejections)
    }
}
