package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.state.OrderBlockZone
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.state.StructureState
import com.coindcx.trading.engine.state.StructureTrend
import com.coindcx.trading.engine.telemetry.RejectionCode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy 9: Smart Money Structure Breakout (SBOB) (§1.9).
 *
 * Evidence Class: Market Structure & Order Block Zone Mitigation.
 * Tracks Dow Theory Break of Structure (BOS) and Change of Character (CHoCH),
 * constructs mechanical Order Block zones, and enters on confirmed mitigation retests.
 */
class SbobStrategy(
    val obMaxAgeBars: Int = 20,
    val minBreakVolumeMultiplier: Double = 1.2,
    val minAtrPct: Double = 0.45,
    val maxAtrPct: Double = 6.0,
    val plannedRR: Double = 2.0,
    val expiryBars: Int = 16
) : Strategy {

    override val id: String = "sbob"
    override val name: String = "Smart Money Structure Breakout Strategy"
    override val description: String = "Market structure breakout strategy entering on confirmed Dow Theory BOS breaks and mechanical Order Block retests."
    override val requiredCandleCount: Int = 80
    override val primaryInterval: Interval = Interval.M15
    override val requiredIntervals: Set<Interval> = setOf(Interval.M15)
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.TRENDING_MOMENTUM
    override val parametersSummary: String = "OB MaxAge: ${obMaxAgeBars}b, BreakVol: ${minBreakVolumeMultiplier}x, Planned RR: 1:${plannedRR.toInt()}"

    override fun evaluate(ctx: SymbolContext, state: StrategyState?): StrategyResult {
        val series = ctx.primarySeries
        val rejections = mutableListOf<RejectionCode>()

        if (series.size < requiredCandleCount) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
        }

        val currClose = series.close(0)

        // 0. Active Position Exit Management (Close beyond Order Block far edge)
        val activePos = ctx.activePosition
        val activeOb = (state as? StructureState)?.activeOrderBlocks?.firstOrNull()
        if (activePos != null && activePos.isOpen && activeOb != null) {
            if (activePos.isLong && currClose < activeOb.bottom) {
                return StrategyResult(
                    signal = Signal(
                        symbol = ctx.symbol, strategyId = id, direction = SignalDirection.LONG,
                        barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                        reason = "SBOB Long EXIT: Order block invalidated (Close %.2f < OB Bottom %.2f)".format(currClose, activeOb.bottom),
                        explicitAction = SignalAction.EXIT
                    ), newState = state
                )
            } else if (activePos.isShort && currClose > activeOb.top) {
                return StrategyResult(
                    signal = Signal(
                        symbol = ctx.symbol, strategyId = id, direction = SignalDirection.SHORT,
                        barOpenTimeUtc = series.openTime(0), entryRef = currClose, strategyName = name,
                        reason = "SBOB Short EXIT: Order block invalidated (Close %.2f > OB Top %.2f)".format(currClose, activeOb.top),
                        explicitAction = SignalAction.EXIT
                    ), newState = state
                )
            }
        }

        // 1. G3/G4 ATR Bounds
        val atr = TechnicalIndicators.calculateAtr(series, 14, barIndex = 0)
        val atrPct = if (currClose > 0.0) (atr / currClose) * 100.0 else 0.0

        if (atrPct < minAtrPct || atrPct > maxAtrPct) {
            return StrategyResult(null, state, listOf(RejectionCode.S9_REGIME_ATR_FLOOR))
        }

        // 2. S9 R7 Mutual Exclusivity Check with S1 (PBC)
        // If EMAs 9/21/50 are strictly stacked and price is in a shallow pullback (< EMA21),
        // S1 owns this regime. S9 yields.
        val ema9 = TechnicalIndicators.calculateEmaAt(series, 9, barIndex = 0)
        val ema21 = TechnicalIndicators.calculateEmaAt(series, 21, barIndex = 0)
        val ema50 = TechnicalIndicators.calculateEmaAt(series, 50, barIndex = 0)
        val isShallowTrend = (ema9 > ema21 && ema21 > ema50 && currClose > ema21) ||
                (ema9 < ema21 && ema21 < ema50 && currClose < ema21)

        // 3. Detect 5-Bar Swing Point Fractals (confirmed 2 bars late)
        val swingHighs = mutableListOf<Pair<Int, Double>>() // (barIndex, price)
        val swingLows = mutableListOf<Pair<Int, Double>>()
        val scanDepth = min(60, series.size - 3)

        for (i in 2 until scanDepth) {
            val h = series.high(i)
            val l = series.low(i)
            val isSwingHigh = h > series.high(i - 1) && h > series.high(i - 2) &&
                    h > series.high(i + 1) && h > series.high(i + 2)
            val isSwingLow = l < series.low(i - 1) && l < series.low(i - 2) &&
                    l < series.low(i + 1) && l < series.low(i + 2)

            if (isSwingHigh) swingHighs.add(Pair(i, h))
            if (isSwingLow) swingLows.add(Pair(i, l))
        }

        // 4. Resolve Break of Structure (BOS) & Order Block (OB)
        var recentBOS: Boolean = false
        var isBullishBOS = false
        var activeOB: OrderBlockZone? = null
        val volSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = 0)

        // Check if any bar in the last 15 bars broke the previous swing high/low
        for (b in 1..min(obMaxAgeBars, series.size - 2)) {
            val c = series.close(b)
            // Bullish BOS: closed above previous swing high
            val brokenHigh = swingHighs.firstOrNull { it.first > b && c > it.second }
            if (brokenHigh != null) {
                recentBOS = true
                isBullishBOS = true

                // Check breakout volume
                val bVolSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = b)
                if (bVolSma > 0.0 && series.volume(b) < minBreakVolumeMultiplier * bVolSma) {
                    rejections.add(RejectionCode.S9_REGIME_BREAK_VOLUME)
                }

                // Construct Bullish OB: the last down-close candle prior to or at the origin of this impulse
                var obBar = b + 1
                for (search in (b + 1)..min(b + 5, series.size - 1)) {
                    if (series.close(search) < series.open(search)) {
                        obBar = search
                        break
                    }
                }
                activeOB = OrderBlockZone(
                    isBullish = true,
                    top = series.high(obBar),
                    bottom = series.low(obBar),
                    formationBarOpenTime = series.openTime(obBar)
                )
                break
            }

            // Bearish BOS: closed below previous swing low
            val brokenLow = swingLows.firstOrNull { it.first > b && c < it.second }
            if (brokenLow != null) {
                recentBOS = true
                isBullishBOS = false

                val bVolSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = b)
                if (bVolSma > 0.0 && series.volume(b) < minBreakVolumeMultiplier * bVolSma) {
                    rejections.add(RejectionCode.S9_REGIME_BREAK_VOLUME)
                }

                // Construct Bearish OB: the last up-close candle prior to this drop
                var obBar = b + 1
                for (search in (b + 1)..min(b + 5, series.size - 1)) {
                    if (series.close(search) > series.open(search)) {
                        obBar = search
                        break
                    }
                }
                activeOB = OrderBlockZone(
                    isBullish = false,
                    top = series.high(obBar),
                    bottom = series.low(obBar),
                    formationBarOpenTime = series.openTime(obBar)
                )
                break
            }
        }

        if (!recentBOS || activeOB == null) {
            return StrategyResult(null, state, listOf(RejectionCode.S9_REGIME_NO_BOS_CHOCH))
        }

        // Mutual exclusivity assertion
        if (isShallowTrend) {
            rejections.add(RejectionCode.S9_REGIME_R7_STACKED_EMA_MUTUAL_EXCLUSION)
        }

        val ob = activeOB
        val barRange = series.high(0) - series.low(0)
        val safeRange = if (barRange > 0.0) barRange else 0.0001

        // 5. Evaluate Retest of the Order Block
        if (ob.isBullish) {
            // Price must penetrate into the OB zone
            if (series.low(0) > ob.top) {
                rejections.add(RejectionCode.S9_C2_NOT_IN_OB_ZONE)
            }
            // Close must hold above the OB bottom (invalidation if violated)
            if (currClose < ob.bottom) {
                rejections.add(RejectionCode.S9_C3_OB_ZONE_VIOLATED)
            }
            // Green candle confirmation
            if (currClose <= series.open(0)) {
                rejections.add(RejectionCode.S9_C4_CANDLE_DIRECTION)
            }

            // Lower wick rejection or decisive upper close
            val lowerWick = min(series.open(0), currClose) - series.low(0)
            val isWickRejection = (lowerWick / safeRange) >= 0.25 || ((currClose - series.low(0)) / safeRange) >= 0.50
            if (!isWickRejection) {
                rejections.add(RejectionCode.S9_C5_WICK_REJECTION)
            }

            if (rejections.isEmpty()) {
                val rawStop = ob.bottom - 0.2 * atr
                val rawDist = currClose - rawStop
                val clampedDist = rawDist.coerceIn(0.8 * atr, 2.5 * atr)
                val stopLoss = currClose - clampedDist
                val takeProfit = currClose + (clampedDist * plannedRR)

                val strengths = mapOf(
                    "obPenetrationFidelity" to ((ob.top - series.low(0)) / ob.height.coerceAtLeast(0.001)).coerceIn(0.0, 1.0),
                    "rejectionWick" to (lowerWick / safeRange).coerceIn(0.0, 1.0),
                    "closeLocation" to ((currClose - series.low(0)) / safeRange).coerceIn(0.0, 1.0),
                    "breakoutImpulse" to if (volSma > 0.0) (series.volume(0) / volSma).coerceIn(0.0, 1.0) else 0.5
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
                    regimeTag = RegimeTag.TREND_UP,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "SBOB Long: Bullish BOS confirmed + OB mitigation held (Low %.2f into [%.2f..%.2f])".format(series.low(0), ob.bottom, ob.top),
                    confidenceScore = 84.0
                )

                val newState = StructureState(
                    trend = StructureTrend.UPTREND,
                    swingHighs = swingHighs.map { it.second },
                    swingLows = swingLows.map { it.second },
                    activeOrderBlocks = listOf(ob.copy(isTested = true)),
                    lastBOSBarTime = series.openTime(0),
                    lastUpdatedBarOpenTime = series.openTime(0)
                )

                return StrategyResult(signal = signal, newState = newState)
            }
        } else {
            // Bearish Setup
            if (series.high(0) < ob.bottom) {
                rejections.add(RejectionCode.S9_C2_NOT_IN_OB_ZONE)
            }
            if (currClose > ob.top) {
                rejections.add(RejectionCode.S9_C3_OB_ZONE_VIOLATED)
            }
            if (currClose >= series.open(0)) {
                rejections.add(RejectionCode.S9_C4_CANDLE_DIRECTION)
            }

            val upperWick = series.high(0) - max(series.open(0), currClose)
            val isWickRejection = (upperWick / safeRange) >= 0.25 || ((series.high(0) - currClose) / safeRange) >= 0.50
            if (!isWickRejection) {
                rejections.add(RejectionCode.S9_C5_WICK_REJECTION)
            }

            if (rejections.isEmpty()) {
                val rawStop = ob.top + 0.2 * atr
                val rawDist = rawStop - currClose
                val clampedDist = rawDist.coerceIn(0.8 * atr, 2.5 * atr)
                val stopLoss = currClose + clampedDist
                val takeProfit = currClose - (clampedDist * plannedRR)

                val strengths = mapOf(
                    "obPenetrationFidelity" to ((series.high(0) - ob.bottom) / ob.height.coerceAtLeast(0.001)).coerceIn(0.0, 1.0),
                    "rejectionWick" to (upperWick / safeRange).coerceIn(0.0, 1.0),
                    "closeLocation" to ((series.high(0) - currClose) / safeRange).coerceIn(0.0, 1.0),
                    "breakoutImpulse" to if (volSma > 0.0) (series.volume(0) / volSma).coerceIn(0.0, 1.0) else 0.5
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
                    regimeTag = RegimeTag.TREND_DOWN,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "SBOB Short: Bearish BOS confirmed + OB mitigation held (High %.2f into [%.2f..%.2f])".format(series.high(0), ob.bottom, ob.top),
                    confidenceScore = 84.0
                )

                val newState = StructureState(
                    trend = StructureTrend.DOWNTREND,
                    swingHighs = swingHighs.map { it.second },
                    swingLows = swingLows.map { it.second },
                    activeOrderBlocks = listOf(ob.copy(isTested = true)),
                    lastBOSBarTime = series.openTime(0),
                    lastUpdatedBarOpenTime = series.openTime(0)
                )

                return StrategyResult(signal = signal, newState = newState)
            }
        }

        val updatedState = StructureState(
            trend = if (isBullishBOS) StructureTrend.UPTREND else StructureTrend.DOWNTREND,
            swingHighs = swingHighs.map { it.second },
            swingLows = swingLows.map { it.second },
            activeOrderBlocks = listOf(ob),
            lastUpdatedBarOpenTime = series.openTime(0)
        )
        return StrategyResult(null, updatedState, rejections)
    }
}
