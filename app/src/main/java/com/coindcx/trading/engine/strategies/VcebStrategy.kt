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
 * Strategy 2: Volatility Compression Expansion Breakout (VCEB) (§1.2).
 *
 * Evidence Class: Volatility Breakout from Extended Range Squeeze.
 * Identifies 200-bar rolling Bollinger Band Width compression (<= 20th percentile)
 * and TTM Squeeze, then enters on strong volume-confirmed expansion breakouts.
 */
class VcebStrategy(
    val bbPeriod: Int = 20,
    val bbStdDevMultiplier: Double = 2.0,
    val percentileThreshold: Double = 25.0,
    val minCompressionBars: Int = 4,
    val expansionTrMultiplier: Double = 1.4,
    val expansionVolMultiplier: Double = 1.4,
    val minAtrPct: Double = 0.45,
    val maxAtrPct: Double = 6.0,
    val plannedRR: Double = 2.0,
    val expiryBars: Int = 16
) : Strategy {

    override val id: String = "vceb"
    override val name: String = "Volatility Compression Expansion Breakout"
    override val description: String = "Volatility breakout strategy entering when Bollinger Bands expand out of a multi-bar TTM squeeze and multi-month BBWidth low."
    override val requiredCandleCount: Int = 220
    override val primaryInterval: Interval = Interval.M15
    override val requiredIntervals: Set<Interval> = setOf(Interval.M15)
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.TRENDING_MOMENTUM
    override val parametersSummary: String = "BB: $bbPeriod/${bbStdDevMultiplier}x, BBW% <= ${percentileThreshold.toInt()}th, ExpTR: ${expansionTrMultiplier}x, Vol: ${expansionVolMultiplier}x"

    override fun evaluate(ctx: SymbolContext, state: StrategyState?): StrategyResult {
        val series = ctx.primarySeries
        val rejections = mutableListOf<RejectionCode>()

        if (series.size < requiredCandleCount) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
        }

        // 0. Active Position Exit Management
        val currClose = series.close(0)
        val activePos = ctx.activePosition
        if (activePos != null && activePos.isOpen) {
            val bb0 = TechnicalIndicators.calculateBollingerBands(series, bbPeriod, bbStdDevMultiplier, barIndex = 0)
            if (activePos.isLong && currClose < bb0.basis) {
                return StrategyResult(
                    signal = Signal(
                        symbol = ctx.symbol, strategyId = id, direction = SignalDirection.LONG,
                        barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                        reason = "VCEB Long EXIT: Price fell back inside pre-breakout range (Close %.2f < Basis %.2f)".format(currClose, bb0.basis),
                        explicitAction = SignalAction.EXIT
                    ), newState = state
                )
            } else if (activePos.isShort && currClose > bb0.basis) {
                return StrategyResult(
                    signal = Signal(
                        symbol = ctx.symbol, strategyId = id, direction = SignalDirection.SHORT,
                        barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                        reason = "VCEB Short EXIT: Price fell back inside pre-breakout range (Close %.2f > Basis %.2f)".format(currClose, bb0.basis),
                        explicitAction = SignalAction.EXIT
                    ), newState = state
                )
            }
        }

        // 1. G3/G4 ATR Bounds
        val atr = TechnicalIndicators.calculateAtr(series, 14, barIndex = 0)
        val atrPct = if (currClose > 0.0) (atr / currClose) * 100.0 else 0.0

        if (atrPct < minAtrPct || atrPct > maxAtrPct) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G3_ATR_FLOOR))
        }

        // 2. 200-Bar Rolling BBWidth Percentile Window
        val windowSize = min(200, series.size - bbPeriod)
        val bbWidthWindow = DoubleArray(windowSize)
        for (i in 0 until windowSize) {
            val bb = TechnicalIndicators.calculateBollingerBands(series, bbPeriod, bbStdDevMultiplier, barIndex = i)
            bbWidthWindow[i] = bb.bbWidth
        }

        // Check compression percentile of bar[1] (pre-breakout bar)
        val preBreakoutBBW = bbWidthWindow[1]
        val bbwPercentile = TechnicalIndicators.percentileRank(bbWidthWindow, preBreakoutBBW)
        if (bbwPercentile > percentileThreshold) {
            rejections.add(RejectionCode.S2_REGIME_BBWIDTH_PERCENTILE)
        }

        // 3. TTM Squeeze Detection on Bar 1 & Compression Age
        var squeezeCount = 0
        for (b in 1..min(15, series.size - bbPeriod - 1)) {
            val bb = TechnicalIndicators.calculateBollingerBands(series, bbPeriod, bbStdDevMultiplier, barIndex = b)
            val kc = TechnicalIndicators.calculateKeltnerChannel(series, emaPeriod = 20, atrMultiplier = 1.5, atrPeriod = 20, barIndex = b)
            val isSqueeze = bb.upper <= kc.upper && bb.lower >= kc.lower
            if (isSqueeze) {
                squeezeCount++
            } else if (b > 1) {
                break
            }
        }

        if (squeezeCount < minCompressionBars) {
            rejections.add(RejectionCode.S2_REGIME_COMPRESSION_AGE)
        }

        // 4. Bar 0 Expansion Trigger & Breakout Confirmation
        val bb0 = TechnicalIndicators.calculateBollingerBands(series, bbPeriod, bbStdDevMultiplier, barIndex = 0)
        val tr0 = series.tr(0)
        if (tr0 < expansionTrMultiplier * atr) {
            rejections.add(RejectionCode.S2_C3_EXPANSION_TR)
        }

        val volSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = 0)
        if (volSma > 0.0 && series.volume(0) < expansionVolMultiplier * volSma) {
            rejections.add(RejectionCode.S2_C4_EXPANSION_VOLUME)
        }

        val barRange = series.high(0) - series.low(0)
        val safeRange = if (barRange > 0.0) barRange else 0.0001
        val body = abs(series.close(0) - series.open(0))
        val bodyRatio = body / safeRange
        if (bodyRatio < 0.50) {
            rejections.add(RejectionCode.S2_C6_BODY_RATIO)
        }

        val isBullishBreakout = currClose > bb0.upper && currClose > series.open(0)
        val isBearishBreakout = currClose < bb0.lower && currClose < series.open(0)

        if (!isBullishBreakout && !isBearishBreakout) {
            rejections.add(RejectionCode.S2_C2_BREAKOUT_NOT_CONFIRMED)
        }

        // Context EMA200
        val ema200 = if (series.size >= 200) TechnicalIndicators.calculateEmaAt(series, 200, barIndex = 0) else 0.0
        if (isBullishBreakout && ema200 > 0.0 && currClose < ema200) {
            rejections.add(RejectionCode.S2_C7_CONTEXT_EMA200)
        }
        if (isBearishBreakout && ema200 > 0.0 && currClose > ema200) {
            rejections.add(RejectionCode.S2_C7_CONTEXT_EMA200)
        }

        // Overextension guard: close <= 2.5 ATR from 20-period basis
        val distFromBasis = abs(currClose - bb0.basis)
        if (distFromBasis > 2.5 * atr) {
            rejections.add(RejectionCode.S2_C8_OVEREXTENDED)
        }

        // 5. Emit Signal if All Passed
        if (isBullishBreakout) {
            val closeLocation = (currClose - series.low(0)) / safeRange
            if (closeLocation < 0.65) {
                rejections.add(RejectionCode.S2_C5_CLOSE_LOCATION)
            }

            if (rejections.isEmpty()) {
                val rawStop = min(bb0.basis, series.low(0) - 0.2 * atr)
                val rawDist = currClose - rawStop
                val clampedDist = rawDist.coerceIn(1.0 * atr, 2.5 * atr)
                val stopLoss = currClose - clampedDist
                val takeProfit = currClose + (clampedDist * plannedRR)

                val strengths = mapOf(
                    "compressionDepth" to (1.0 - (bbwPercentile / percentileThreshold)).coerceIn(0.0, 1.0),
                    "expansionVolume" to if (volSma > 0.0) ((series.volume(0) / volSma) / 3.0).coerceIn(0.0, 1.0) else 0.5,
                    "bodyDominance" to bodyRatio.coerceIn(0.0, 1.0),
                    "closeLocation" to closeLocation.coerceIn(0.0, 1.0)
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
                    riskPct = (clampedDist / currClose) * 100.0,
                    regimeTag = RegimeTag.EXPANSION,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "VCEB Long: BB upper breakout (%.2f > %.2f) from %d-bar squeeze (BBW %d%%)".format(currClose, bb0.upper, squeezeCount, bbwPercentile.toInt()),
                    confidenceScore = 85.0
                )

                return StrategyResult(signal = signal, newState = null)
            }
        }

        if (isBearishBreakout) {
            val closeLocation = (series.high(0) - currClose) / safeRange
            if (closeLocation < 0.65) {
                rejections.add(RejectionCode.S2_C5_CLOSE_LOCATION)
            }

            if (rejections.isEmpty()) {
                val rawStop = max(bb0.basis, series.high(0) + 0.2 * atr)
                val rawDist = rawStop - currClose
                val clampedDist = rawDist.coerceIn(1.0 * atr, 2.5 * atr)
                val stopLoss = currClose + clampedDist
                val takeProfit = currClose - (clampedDist * plannedRR)

                val strengths = mapOf(
                    "compressionDepth" to (1.0 - (bbwPercentile / percentileThreshold)).coerceIn(0.0, 1.0),
                    "expansionVolume" to if (volSma > 0.0) ((series.volume(0) / volSma) / 3.0).coerceIn(0.0, 1.0) else 0.5,
                    "bodyDominance" to bodyRatio.coerceIn(0.0, 1.0),
                    "closeLocation" to closeLocation.coerceIn(0.0, 1.0)
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
                    riskPct = (clampedDist / currClose) * 100.0,
                    regimeTag = RegimeTag.EXPANSION,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "VCEB Short: BB lower breakdown (%.2f < %.2f) from %d-bar squeeze (BBW %d%%)".format(currClose, bb0.lower, squeezeCount, bbwPercentile.toInt()),
                    confidenceScore = 85.0
                )

                return StrategyResult(signal = signal, newState = null)
            }
        }

        return StrategyResult(signal = null, newState = null, rejections = rejections)
    }
}
