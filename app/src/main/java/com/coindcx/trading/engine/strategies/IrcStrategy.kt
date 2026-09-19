package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.state.ImpulseState
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.telemetry.RejectionCode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class IrcStopVariant {
    IRC_FIB618_STOP,
    IRC_SWING_STOP
}

/**
 * Strategy 8: Impulse-Retest Continuation (IRC) (§1.8).
 *
 * Evidence Class: Breakout / Momentum Continuation after Fibonacci Retest.
 * Identifies high-volume expansion impulse bars, waits for an orderly 38.2%-61.8%
 * Fibonacci retracement within a 6-bar TTL, and triggers entry on confirmed price resumption.
 */
class IrcStrategy(
    val stopVariant: IrcStopVariant = IrcStopVariant.IRC_FIB618_STOP,
    val impulseAtrMultiplier: Double = 1.8,
    val impulseVolMultiplier: Double = 1.5,
    val minAtrPct: Double = 0.45,
    val maxAtrPct: Double = 8.0,
    val maxRetestBars: Int = 6,
    val plannedRR: Double = 2.0,
    val expiryBars: Int = 16
) : Strategy {

    override val id: String = "irc"
    override val name: String = "Impulse-Retest Continuation Strategy"
    override val description: String = "Momentum continuation entering on confirmed 38.2%-61.8% Fibonacci retests of high-volume impulse bars."
    override val requiredCandleCount: Int = 50
    override val primaryInterval: Interval = Interval.M15
    override val requiredIntervals: Set<Interval> = setOf(Interval.M15)
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.TRENDING_MOMENTUM
    override val parametersSummary: String = "Stop: $stopVariant, ImpulseATR: ${impulseAtrMultiplier}x, Vol: ${impulseVolMultiplier}x, TTL: ${maxRetestBars}b"

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

        if (atrPct < minAtrPct || atrPct > maxAtrPct) {
            return StrategyResult(null, state, listOf(RejectionCode.S8_REGIME_ATR_BOUNDS))
        }

        val volSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = 0)

        // 2. Resolve or Discover Impulse State
        var activeImpulse: ImpulseState? = null
        if (state is ImpulseState) {
            // Check staleness: if last updated is older than maxRetestBars, drop
            val barDurationMs = primaryInterval.durationMs
            val elapsedMs = series.openTime(0) - state.impulseBarTime
            val barsElapsed = (elapsedMs / barDurationMs).toInt()
            if (barsElapsed in 1..maxRetestBars) {
                activeImpulse = state.copy(
                    barsSinceImpulse = barsElapsed,
                    lastUpdatedBarOpenTime = series.openTime(0)
                )
            }
        }

        // If no active impulse from state, scan recent bars (1..maxRetestBars) to discover a candidate impulse
        if (activeImpulse == null) {
            for (b in 1..min(maxRetestBars, series.size - 2)) {
                val bAtr = TechnicalIndicators.calculateAtr(series, 14, barIndex = b)
                val bVolSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = b)
                val tr = series.tr(b)
                val barRange = series.high(b) - series.low(b)
                val body = abs(series.close(b) - series.open(b))
                val isExpansion = tr >= impulseAtrMultiplier * bAtr && barRange > 0.0
                val isVolSpike = series.volume(b) >= impulseVolMultiplier * bVolSma
                val isDecisiveBody = (body / barRange) >= 0.55

                if (isExpansion && isVolSpike && isDecisiveBody) {
                    val isBullish = series.close(b) > series.open(b) && ((series.close(b) - series.low(b)) / barRange) >= 0.70
                    val isBearish = series.close(b) < series.open(b) && ((series.high(b) - series.close(b)) / barRange) >= 0.70

                    if (isBullish) {
                        activeImpulse = ImpulseState(
                            direction = SignalDirection.LONG,
                            impulseHigh = series.high(b),
                            impulseLow = series.low(b),
                            impulseVolume = series.volume(b),
                            impulseBarTime = series.openTime(b),
                            barsSinceImpulse = b,
                            lastUpdatedBarOpenTime = series.openTime(0)
                        )
                        break
                    } else if (isBearish) {
                        activeImpulse = ImpulseState(
                            direction = SignalDirection.SHORT,
                            impulseHigh = series.high(b),
                            impulseLow = series.low(b),
                            impulseVolume = series.volume(b),
                            impulseBarTime = series.openTime(b),
                            barsSinceImpulse = b,
                            lastUpdatedBarOpenTime = series.openTime(0)
                        )
                        break
                    }
                }
            }
        }

        if (activeImpulse == null) {
            return StrategyResult(null, null, listOf(RejectionCode.S8_NO_RECENT_IMPULSE))
        }

        val impulse = activeImpulse
        val b = impulse.barsSinceImpulse

        // 3. Evaluate Retrace & Resumption Conditions
        val rsi = TechnicalIndicators.calculateRsi(series, 14, barIndex = 0)

        if (impulse.direction == SignalDirection.LONG) {
            // Check retrace extremes between impulse bar b and bar 0
            var lowestLow = series.low(0)
            var lowestClose = series.close(0)
            for (i in 1 until b) {
                if (series.low(i) < lowestLow) lowestLow = series.low(i)
                if (series.close(i) < lowestClose) lowestClose = series.close(i)
            }

            // Must have reached 38.2% retrace
            if (lowestLow > impulse.fib382) {
                rejections.add(RejectionCode.S8_C2_RETRACE_SHALLOW)
            }

            // Must NOT have breached 61.8% retrace
            if (lowestClose < impulse.fib618 || lowestLow < (impulse.fib618 - 0.2 * atr)) {
                rejections.add(RejectionCode.S8_C3_RETRACE_TOO_DEEP)
            }

            // Resumption candle direction (green)
            if (series.close(0) <= series.open(0)) {
                rejections.add(RejectionCode.S8_C4_CANDLE_DIRECTION)
            }

            // Retest held above 38.2% Fib
            if (series.close(0) < impulse.fib382) {
                rejections.add(RejectionCode.S8_C5_C6_RETEST_NOT_HELD)
            }

            // Retest volume contraction
            if (series.volume(0) >= impulse.impulseVolume * 0.95) {
                rejections.add(RejectionCode.S8_C7_C8_RETEST_VOLUME_HIGH)
            }

            // Still within impulse range
            if (series.close(0) > impulse.impulseHigh * 1.01) {
                rejections.add(RejectionCode.S8_C9_OUTSIDE_IMPULSE_RANGE)
            }

            // RSI bounds
            if (rsi < 40.0 || rsi > 75.0) {
                rejections.add(RejectionCode.S8_C10_RSI_BOUNDS)
            }

            if (rejections.isEmpty()) {
                val rawStop = if (stopVariant == IrcStopVariant.IRC_FIB618_STOP) {
                    impulse.fib618 - 0.2 * atr
                } else {
                    impulse.impulseLow - 0.2 * atr
                }
                val rawDist = currClose - rawStop
                val clampedDist = rawDist.coerceIn(0.8 * atr, 3.0 * atr)
                val stopLoss = currClose - clampedDist
                val takeProfit = currClose + (clampedDist * plannedRR)
                val riskPct = (clampedDist / currClose) * 100.0

                val strengths = mapOf(
                    "impulseMagnitude" to (impulse.range / (2.0 * atr)).coerceIn(0.0, 1.0),
                    "volumeContraction" to (1.0 - (series.volume(0) / impulse.impulseVolume)).coerceIn(0.0, 1.0),
                    "retraceFidelity" to (1.0 - abs(lowestLow - impulse.fib382) / (impulse.range * 0.236)).coerceIn(0.0, 1.0),
                    "resumptionMomentum" to ((series.close(0) - series.open(0)) / (series.high(0) - series.low(0))).coerceIn(0.0, 1.0)
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
                    regimeTag = RegimeTag.EXPANSION,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "IRC Long: Fib retest held (Low %.2f >= Fib618 %.2f) + Green resumption bar".format(lowestLow, impulse.fib618),
                    confidenceScore = 82.0
                )

                // Impulse consumed; clear state to prevent duplicate emission
                return StrategyResult(signal = signal, newState = null)
            }
        } else {
            // Bearish IRC
            var highestHigh = series.high(0)
            var highestClose = series.close(0)
            for (i in 1 until b) {
                if (series.high(i) > highestHigh) highestHigh = series.high(i)
                if (series.close(i) > highestClose) highestClose = series.close(i)
            }

            if (highestHigh < impulse.fib382) {
                rejections.add(RejectionCode.S8_C2_RETRACE_SHALLOW)
            }

            if (highestClose > impulse.fib618 || highestHigh > (impulse.fib618 + 0.2 * atr)) {
                rejections.add(RejectionCode.S8_C3_RETRACE_TOO_DEEP)
            }

            if (series.close(0) >= series.open(0)) {
                rejections.add(RejectionCode.S8_C4_CANDLE_DIRECTION)
            }

            if (series.close(0) > impulse.fib382) {
                rejections.add(RejectionCode.S8_C5_C6_RETEST_NOT_HELD)
            }

            if (series.volume(0) >= impulse.impulseVolume * 0.95) {
                rejections.add(RejectionCode.S8_C7_C8_RETEST_VOLUME_HIGH)
            }

            if (series.close(0) < impulse.impulseLow * 0.99) {
                rejections.add(RejectionCode.S8_C9_OUTSIDE_IMPULSE_RANGE)
            }

            if (rsi < 25.0 || rsi > 60.0) {
                rejections.add(RejectionCode.S8_C10_RSI_BOUNDS)
            }

            if (rejections.isEmpty()) {
                val rawStop = if (stopVariant == IrcStopVariant.IRC_FIB618_STOP) {
                    impulse.fib618 + 0.2 * atr
                } else {
                    impulse.impulseHigh + 0.2 * atr
                }
                val rawDist = rawStop - currClose
                val clampedDist = rawDist.coerceIn(0.8 * atr, 3.0 * atr)
                val stopLoss = currClose + clampedDist
                val takeProfit = currClose - (clampedDist * plannedRR)
                val riskPct = (clampedDist / currClose) * 100.0

                val strengths = mapOf(
                    "impulseMagnitude" to (impulse.range / (2.0 * atr)).coerceIn(0.0, 1.0),
                    "volumeContraction" to (1.0 - (series.volume(0) / impulse.impulseVolume)).coerceIn(0.0, 1.0),
                    "retraceFidelity" to (1.0 - abs(highestHigh - impulse.fib382) / (impulse.range * 0.236)).coerceIn(0.0, 1.0),
                    "resumptionMomentum" to ((series.open(0) - series.close(0)) / (series.high(0) - series.low(0))).coerceIn(0.0, 1.0)
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
                    regimeTag = RegimeTag.EXPANSION,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "IRC Short: Fib retest held (High %.2f <= Fib618 %.2f) + Red resumption bar".format(highestHigh, impulse.fib618),
                    confidenceScore = 82.0
                )

                return StrategyResult(signal = signal, newState = null)
            }
        }

        // If not triggered, retain impulse in state until TTL expires
        return StrategyResult(signal = null, newState = impulse, rejections = rejections)
    }
}
