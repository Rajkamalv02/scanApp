package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.telemetry.RejectionCode
import kotlin.math.abs
import kotlin.math.max

/**
 * Strategy 10: Exponential Donchian Trend Momentum (EDTM) (§1.10).
 *
 * Evidence Class: Time-Series Momentum & Trend Following (published empirical support).
 * Captures clean structural expansions following high-efficiency trend states.
 * Uses open-ended trailing stops (Target.OpenEnded) to ride fat-tailed crypto trends.
 */
class EdtmStrategy(
    val donchianPeriod: Int = 20,
    val emaPeriod: Int = 100,
    val erPeriod: Int = 20,
    val erThreshold: Double = 0.55,
    val atrPeriod: Int = 14,
    val minAtrPct: Double = 0.8,
    val maxAtrPct: Double = 5.0,
    val trailAtrMultiplier: Double = 2.5,
    val maxEmaDistanceAtr: Double = 4.0,
    val expiryBars: Int = 24
) : Strategy {

    override val id: String = "edtm"
    override val name: String = "Exponential Donchian Trend Momentum"
    override val description: String = "Time-series momentum trend follower trading 20-bar Donchian channel breaks confirmed by 100 EMA alignment and Kaufman Efficiency Ratio."
    override val requiredCandleCount: Int = max(emaPeriod + 20, 120)
    override val primaryInterval: Interval = Interval.H1
    override val requiredIntervals: Set<Interval> = setOf(Interval.H1, Interval.H4)
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.TRENDING_MOMENTUM
    override val parametersSummary: String = "Donchian: $donchianPeriod, EMA: $emaPeriod, ER(20) >= $erThreshold, Trail ATR: ${trailAtrMultiplier}x"

    override fun evaluate(ctx: SymbolContext, state: StrategyState?): StrategyResult {
        val series = ctx.primarySeries
        val rejections = mutableListOf<RejectionCode>()

        if (series.size < requiredCandleCount) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
        }

        // 1. Macro / Higher-Timeframe (4H) Bias (if provided in ctx)
        val htf4H = ctx.htfSeries[Interval.H4]
        var htfBullish = true
        var htfBearish = true
        if (htf4H != null && htf4H.size >= 50) {
            val htfEma50 = TechnicalIndicators.calculateEmaAt(htf4H, 50, barIndex = 0)
            val htfClose = htf4H.close(0)
            htfBullish = htfClose >= htfEma50
            htfBearish = htfClose <= htfEma50
        }

        val atr = TechnicalIndicators.calculateAtr(series, atrPeriod, barIndex = 0)
        val currClose = series.close(0)
        val er = TechnicalIndicators.calculateEfficiencyRatio(series, erPeriod, barIndex = 0)
        val channel = TechnicalIndicators.donchian(series, donchianPeriod, endIndex = 1)

        // 0. Active Position Exit Management (Donchian 20 channel breach or ER < 0.20 collapse)
        val activePos = ctx.activePosition
        if (activePos != null && activePos.isOpen) {
            if (activePos.isLong) {
                if (currClose < channel.lower || er < 0.20) {
                    return StrategyResult(
                        signal = Signal(
                            symbol = ctx.symbol, strategyId = id, direction = SignalDirection.LONG,
                            barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                            reason = "EDTM Long EXIT: Donchian 20 breach (Close %.2f < %.2f) or ER collapse (%.2f < 0.20)".format(currClose, channel.lower, er),
                            explicitAction = SignalAction.EXIT
                        ), newState = state
                    )
                }
            } else if (activePos.isShort) {
                if (currClose > channel.upper || er < 0.20) {
                    return StrategyResult(
                        signal = Signal(
                            symbol = ctx.symbol, strategyId = id, direction = SignalDirection.SHORT,
                            barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                            reason = "EDTM Short EXIT: Donchian 20 breach (Close %.2f > %.2f) or ER collapse (%.2f < 0.20)".format(currClose, channel.upper, er),
                            explicitAction = SignalAction.EXIT
                        ), newState = state
                    )
                }
            }
        }

        // 2. Efficiency Ratio Filter (1H closed bars)
        if (er < erThreshold) {
            rejections.add(RejectionCode.S10_REGIME_EFFICIENCY_RATIO)
        }

        // 3. Volatility Bounds (1H ATR%)
        val atrPct = if (currClose > 0.0) (atr / currClose) * 100.0 else 0.0
        if (atrPct < minAtrPct || atrPct > maxAtrPct) {
            rejections.add(RejectionCode.S10_REGIME_ATR_BOUNDS)
        }

        // 4. Trend Filter: EMA 100
        val ema100 = TechnicalIndicators.calculateEmaAt(series, emaPeriod, barIndex = 0)

        val barRange = series.high(0) - series.low(0)
        val safeRange = if (barRange > 0.0) barRange else 0.0001

        // Evaluate Long Trigger
        val isBullishBreak = currClose > channel.upper
        val isBearishBreak = currClose < channel.lower

        if (isBullishBreak) {
            if (!htfBullish) rejections.add(RejectionCode.S10_REGIME_MACRO_BIAS)
            val isAboveEma = currClose > ema100
            if (!isAboveEma) rejections.add(RejectionCode.S10_C4_EMA100_ALIGNMENT)

            val longEmaDistAtr = if (atr > 0.0) (currClose - ema100) / atr else 99.0
            val longCloseLocation = (currClose - series.low(0)) / safeRange

            if (rejections.isNotEmpty()) {
                return StrategyResult(null, state, rejections)
            }
            if (longEmaDistAtr > maxEmaDistanceAtr) {
                return StrategyResult(null, state, listOf(RejectionCode.S10_C5_GAP_EXTENDED))
            }
            if (longCloseLocation < 0.70) {
                return StrategyResult(null, state, listOf(RejectionCode.S10_C6_CLOSE_LOCATION))
            }

            val minSlDist = currClose * 0.014
            val maxSlDist = currClose * 0.030
            val riskDistance = (trailAtrMultiplier * atr).coerceIn(minSlDist, maxSlDist)
            val stopLoss = currClose - riskDistance
            val takeProfit = currClose + (riskDistance * 0.75).coerceIn(currClose * 0.015, currClose * 0.022)
            val riskPct = (riskDistance / currClose) * 100.0

            val strengths = mapOf(
                "trendStrength" to ((er - erThreshold) / (1.0 - erThreshold)).coerceIn(0.0, 1.0),
                "breakoutStrength" to if (atr > 0.0) ((currClose - channel.upper) / atr).coerceIn(0.0, 1.0) else 0.5,
                "closeLocation" to longCloseLocation.coerceIn(0.0, 1.0),
                "extensionRoom" to (1.0 - (longEmaDistAtr / maxEmaDistanceAtr)).coerceIn(0.0, 1.0)
            )

            val signal = Signal(
                symbol = ctx.symbol,
                strategyId = id,
                direction = SignalDirection.LONG,
                barOpenTimeUtc = series.openTime(0),
                entryRef = currClose,
                stopLoss = stopLoss,
                target = Target.Fixed(tp1 = takeProfit, plannedRR = 0.75),
                riskDistance = riskDistance,
                riskPct = riskPct,
                regimeTag = RegimeTag.TREND_UP,
                strengths = strengths,
                expiryBars = expiryBars,
                primaryInterval = primaryInterval,
                strategyName = name,
                reason = "EDTM Long: Donchian upper breakout (%.2f > %.2f) + EMA100 alignment + ER=%.2f".format(currClose, channel.upper, er),
                confidenceScore = 80.0
            )

            return StrategyResult(signal = signal, newState = state)
        }

        // Evaluate Short Trigger
        if (isBearishBreak) {
            if (!htfBearish) rejections.add(RejectionCode.S10_REGIME_MACRO_BIAS)
            val isBelowEma = currClose < ema100
            if (!isBelowEma) rejections.add(RejectionCode.S10_C4_EMA100_ALIGNMENT)

            val shortEmaDistAtr = if (atr > 0.0) (ema100 - currClose) / atr else 99.0
            val shortCloseLocation = (series.high(0) - currClose) / safeRange

            if (rejections.isNotEmpty()) {
                return StrategyResult(null, state, rejections)
            }
            if (shortEmaDistAtr > maxEmaDistanceAtr) {
                return StrategyResult(null, state, listOf(RejectionCode.S10_C5_GAP_EXTENDED))
            }
            if (shortCloseLocation < 0.70) {
                return StrategyResult(null, state, listOf(RejectionCode.S10_C6_CLOSE_LOCATION))
            }

            val minSlDist = currClose * 0.014
            val maxSlDist = currClose * 0.030
            val riskDistance = (trailAtrMultiplier * atr).coerceIn(minSlDist, maxSlDist)
            val stopLoss = currClose + riskDistance
            val takeProfit = currClose - (riskDistance * 0.75).coerceIn(currClose * 0.015, currClose * 0.022)
            val riskPct = (riskDistance / currClose) * 100.0

            val strengths = mapOf(
                "trendStrength" to ((er - erThreshold) / (1.0 - erThreshold)).coerceIn(0.0, 1.0),
                "breakoutStrength" to if (atr > 0.0) ((channel.lower - currClose) / atr).coerceIn(0.0, 1.0) else 0.5,
                "closeLocation" to shortCloseLocation.coerceIn(0.0, 1.0),
                "extensionRoom" to (1.0 - (shortEmaDistAtr / maxEmaDistanceAtr)).coerceIn(0.0, 1.0)
            )

            val signal = Signal(
                symbol = ctx.symbol,
                strategyId = id,
                direction = SignalDirection.SHORT,
                barOpenTimeUtc = series.openTime(0),
                entryRef = currClose,
                stopLoss = stopLoss,
                target = Target.Fixed(tp1 = takeProfit, plannedRR = 0.75),
                riskDistance = riskDistance,
                riskPct = riskPct,
                regimeTag = RegimeTag.TREND_DOWN,
                strengths = strengths,
                expiryBars = expiryBars,
                primaryInterval = primaryInterval,
                strategyName = name,
                reason = "EDTM Short: Donchian lower breakdown (%.2f < %.2f) + EMA100 alignment + ER=%.2f".format(currClose, channel.lower, er),
                confidenceScore = 80.0
            )

            return StrategyResult(signal = signal, newState = state)
        }

        val primaryRejection = if (rejections.isNotEmpty()) {
            rejections
        } else {
            listOf(RejectionCode.S10_C2_C3_BREAK_NOT_TRIGGERED)
        }

        return StrategyResult(signal = null, newState = state, rejections = primaryRejection)
    }
}
