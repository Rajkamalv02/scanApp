package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.state.LevelBufferState
import com.coindcx.trading.engine.state.LevelRecord
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.telemetry.RejectionCode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy 3: Liquidity Sweep Reversal (LSR) (§1.3).
 *
 * Evidence Class: Order Flow Liquidity Absorption & False Breakout Reversal.
 * Identifies institutional stop runs beyond established 5-bar swing fractals,
 * requiring decisive intra-bar wick penetration, immediate reclaim back inside the level,
 * strong candle rejection signature, and volume expansion indicating absorption.
 */
class LsrStrategy(
    val lookbackDepth: Int = 60,
    val minLevelAgeBars: Int = 5,
    val maxLevelAgeBars: Int = 50,
    val minPenetrationAtr: Double = 0.05,
    val maxPenetrationAtr: Double = 1.8,
    val minWickRatio: Double = 0.35,
    val minVolumeMultiplier: Double = 1.15,
    val minAtrPct: Double = 0.25,
    val maxAtrPct: Double = 6.0,
    val plannedRR: Double = 0.75,
    val expiryBars: Int = 12
) : Strategy {

    override val id: String = "lsr"
    override val name: String = "Liquidity Sweep Reversal Strategy"
    override val description: String = "Mean-reversion strategy fading liquidity sweeps and false breakouts of key swing levels."
    override val requiredCandleCount: Int = 60
    override val primaryInterval: Interval = Interval.M15
    override val requiredIntervals: Set<Interval> = setOf(Interval.M15)
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.MEAN_REVERTING_RANGE
    override val parametersSummary: String = "Depth: ${lookbackDepth}b, Age: [${minLevelAgeBars}..${maxLevelAgeBars}], WickRatio: ${(minWickRatio * 100).toInt()}%, VolMult: ${minVolumeMultiplier}x, RR: 1:${"%.2f".format(plannedRR)}"

    override fun evaluate(ctx: SymbolContext, state: StrategyState?): StrategyResult {
        val series = ctx.primarySeries
        val rejections = mutableListOf<RejectionCode>()

        if (series.size < requiredCandleCount) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
        }

        val currClose = series.close(0)

        // 0. Active Position Exit Management
        val activePos = ctx.activePosition
        if (activePos != null && activePos.isOpen) {
            val entryPrice = activePos.avgPrice
            val tpTrigger = activePos.takeProfitTrigger
            val slTrigger = activePos.stopLossTrigger
            if (activePos.isLong) {
                if (tpTrigger != null && currClose >= tpTrigger) {
                    return StrategyResult(
                        signal = Signal(
                            symbol = ctx.symbol, strategyId = id, direction = SignalDirection.LONG,
                            barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                            reason = "LSR Long EXIT: Target reached @ %.4f".format(currClose), explicitAction = SignalAction.EXIT
                        ), newState = state
                    )
                }
                if (slTrigger != null && currClose <= slTrigger) {
                    return StrategyResult(
                        signal = Signal(
                            symbol = ctx.symbol, strategyId = id, direction = SignalDirection.LONG,
                            barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                            reason = "LSR Long EXIT: Stop loss hit @ %.4f".format(currClose), explicitAction = SignalAction.EXIT
                        ), newState = state
                    )
                }
            } else if (activePos.isShort) {
                if (tpTrigger != null && currClose <= tpTrigger) {
                    return StrategyResult(
                        signal = Signal(
                            symbol = ctx.symbol, strategyId = id, direction = SignalDirection.SHORT,
                            barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                            reason = "LSR Short EXIT: Target reached @ %.4f".format(currClose), explicitAction = SignalAction.EXIT
                        ), newState = state
                    )
                }
                if (slTrigger != null && currClose >= slTrigger) {
                    return StrategyResult(
                        signal = Signal(
                            symbol = ctx.symbol, strategyId = id, direction = SignalDirection.SHORT,
                            barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                            reason = "LSR Short EXIT: Stop loss hit @ %.4f".format(currClose), explicitAction = SignalAction.EXIT
                        ), newState = state
                    )
                }
            }
        }

        // R5: Mutual exclusion - NOT within 3 bars of a VCEB (S2) signal
        val isVcebCooldown = com.coindcx.trading.engine.scanner.SignalDedupRegistry.default
            .hasSignalWithinBars(ctx.symbol, "vceb", primaryInterval, 3, series.openTime(0))
        if (isVcebCooldown) {
            return StrategyResult(null, state, listOf(RejectionCode.S3_CONFLICT_S2_COOLDOWN))
        }

        // 1. ATR Bounds
        val atr = TechnicalIndicators.calculateAtr(series, 14, barIndex = 0)
        val atrPct = if (currClose > 0.0) (atr / currClose) * 100.0 else 0.0

        if (atrPct < minAtrPct || atrPct > maxAtrPct) {
            return StrategyResult(null, state, listOf(RejectionCode.S3_STOP_TOO_TIGHT))
        }

        // 2. Discover 5-bar swing fractals within valid age window
        val swingHighs = mutableListOf<LevelRecord>()
        val swingLows = mutableListOf<LevelRecord>()
        val scanLimit = min(lookbackDepth, series.size - 3)

        for (i in minLevelAgeBars..scanLimit) {
            val h = series.high(i)
            val l = series.low(i)
            val isSwingHigh = h > series.high(i - 1) && h > series.high(i - 2) &&
                    h > series.high(i + 1) && h > series.high(i + 2)
            val isSwingLow = l < series.low(i - 1) && l < series.low(i - 2) &&
                    l < series.low(i + 1) && l < series.low(i + 2)

            if (isSwingHigh) {
                swingHighs.add(LevelRecord(price = h, formationBarOpenTime = series.openTime(i)))
            }
            if (isSwingLow) {
                swingLows.add(LevelRecord(price = l, formationBarOpenTime = series.openTime(i)))
            }
        }

        val volSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = 0)
        val rsi = TechnicalIndicators.calculateRsi(series, 14, barIndex = 0)
        val currHigh = series.high(0)
        val currLow = series.low(0)
        val currOpen = series.open(0)
        val barRange = currHigh - currLow
        val safeRange = if (barRange > 0.0) barRange else 0.0001

        // 3. Evaluate Bullish Sweep (Long) of established swing low
        // Bar wicks below level.price, but closes back above level.price
        val sweptLow = swingLows.firstOrNull { level ->
            currLow < level.price && currClose > level.price
        }

        if (sweptLow != null) {
            val penetration = sweptLow.price - currLow
            val isPenetrationValid = penetration >= (minPenetrationAtr * atr) && penetration <= (maxPenetrationAtr * atr)

            val lowerWick = min(currOpen, currClose) - currLow
            val wickRatio = lowerWick / safeRange
            val isWickValid = wickRatio >= minWickRatio

            val isCloseValid = currClose > currOpen || ((currClose - currLow) / safeRange) >= 0.50
            val isVolumeValid = volSma <= 0.0 || series.volume(0) >= minVolumeMultiplier * volSma
            val isRsiExhausted = rsi <= 55.0 // momentum exhausted/low

            if (!isPenetrationValid) {
                rejections.add(if (penetration < minPenetrationAtr * atr) RejectionCode.S3_C3_NO_PENETRATION else RejectionCode.S3_STOP_TOO_WIDE)
            }
            if (!isWickValid) rejections.add(RejectionCode.S3_C5_WICK_RATIO)
            if (!isCloseValid) rejections.add(RejectionCode.S3_C8_CANDLE_DIRECTION)
            if (!isVolumeValid) rejections.add(RejectionCode.S3_C7_VOLUME_FLOOR)

            if (rejections.isEmpty()) {
                val rawStop = currLow - 0.2 * atr
                val rawDist = currClose - rawStop
                val minSlDist = currClose * 0.014
                val maxSlDist = currClose * 0.030
                val clampedDist = rawDist.coerceIn(minSlDist, maxSlDist)
                val stopLoss = currClose - clampedDist
                val rawTpDist = clampedDist * plannedRR
                val clampedTpDist = rawTpDist.coerceIn(currClose * 0.015, currClose * 0.022)
                val takeProfit = currClose + clampedTpDist

                val strengths = mapOf(
                    "wickRatio" to wickRatio.coerceIn(0.0, 1.0),
                    "penetrationDepth" to (penetration / (maxPenetrationAtr * atr).coerceAtLeast(0.001)).coerceIn(0.0, 1.0),
                    "volumeSurge" to if (volSma > 0.0) (series.volume(0) / (2.0 * volSma)).coerceIn(0.0, 1.0) else 0.5,
                    "rsiExhaustion" to ((55.0 - rsi) / 40.0).coerceIn(0.0, 1.0)
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
                    regimeTag = RegimeTag.RANGE,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "LSR Long: Swept swing low %.2f by %.2f ATR and reclaimed with %.1f%% lower wick".format(
                        sweptLow.price, penetration / atr, wickRatio * 100.0
                    ),
                    confidenceScore = 82.0
                )

                val updatedState = LevelBufferState(
                    swingHighs = swingHighs,
                    swingLows = swingLows,
                    lastUpdatedBarOpenTime = series.openTime(0)
                )

                return StrategyResult(signal, updatedState, emptyList())
            }
        }

        // 4. Evaluate Bearish Sweep (Short) of established swing high
        // Bar wicks above level.price, but closes back below level.price
        val sweptHigh = swingHighs.firstOrNull { level ->
            currHigh > level.price && currClose < level.price
        }

        if (sweptHigh != null) {
            val penetration = currHigh - sweptHigh.price
            val isPenetrationValid = penetration >= (minPenetrationAtr * atr) && penetration <= (maxPenetrationAtr * atr)

            val upperWick = currHigh - max(currOpen, currClose)
            val wickRatio = upperWick / safeRange
            val isWickValid = wickRatio >= minWickRatio

            val isCloseValid = currClose < currOpen || ((currHigh - currClose) / safeRange) >= 0.50
            val isVolumeValid = volSma <= 0.0 || series.volume(0) >= minVolumeMultiplier * volSma
            val isRsiExhausted = rsi >= 45.0 // momentum exhausted/high

            if (!isPenetrationValid) {
                rejections.add(if (penetration < minPenetrationAtr * atr) RejectionCode.S3_C3_NO_PENETRATION else RejectionCode.S3_STOP_TOO_WIDE)
            }
            if (!isWickValid) rejections.add(RejectionCode.S3_C5_WICK_RATIO)
            if (!isCloseValid) rejections.add(RejectionCode.S3_C8_CANDLE_DIRECTION)
            if (!isVolumeValid) rejections.add(RejectionCode.S3_C7_VOLUME_FLOOR)

            if (rejections.isEmpty()) {
                val rawStop = currHigh + 0.2 * atr
                val rawDist = rawStop - currClose
                val minSlDist = currClose * 0.014
                val maxSlDist = currClose * 0.030
                val clampedDist = rawDist.coerceIn(minSlDist, maxSlDist)
                val stopLoss = currClose + clampedDist
                val rawTpDist = clampedDist * plannedRR
                val clampedTpDist = rawTpDist.coerceIn(currClose * 0.015, currClose * 0.022)
                val takeProfit = currClose - clampedTpDist

                val strengths = mapOf(
                    "wickRatio" to wickRatio.coerceIn(0.0, 1.0),
                    "penetrationDepth" to (penetration / (maxPenetrationAtr * atr).coerceAtLeast(0.001)).coerceIn(0.0, 1.0),
                    "volumeSurge" to if (volSma > 0.0) (series.volume(0) / (2.0 * volSma)).coerceIn(0.0, 1.0) else 0.5,
                    "rsiExhaustion" to ((rsi - 45.0) / 40.0).coerceIn(0.0, 1.0)
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
                    regimeTag = RegimeTag.RANGE,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "LSR Short: Swept swing high %.2f by %.2f ATR and rejected with %.1f%% upper wick".format(
                        sweptHigh.price, penetration / atr, wickRatio * 100.0
                    ),
                    confidenceScore = 82.0
                )

                val updatedState = LevelBufferState(
                    swingHighs = swingHighs,
                    swingLows = swingLows,
                    lastUpdatedBarOpenTime = series.openTime(0)
                )

                return StrategyResult(signal, updatedState, emptyList())
            }
        }

        if (rejections.isEmpty()) {
            rejections.add(RejectionCode.S3_C3_NO_PENETRATION)
        }

        val updatedState = LevelBufferState(
            swingHighs = swingHighs,
            swingLows = swingLows,
            lastUpdatedBarOpenTime = series.openTime(0)
        )

        return StrategyResult(null, updatedState, rejections)
    }
}
