package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.state.SessionState
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.telemetry.RejectionCode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy 4: Session Opening Range Momentum (SORM) (§1.4).
 *
 * Evidence Class: Cross-Session Liquidity Shifts & Opening Range Expansion.
 * Establishes a 1-hour Opening Range (OR) at Asia Open (00:00 UTC) and US Open (13:30 UTC),
 * and enters on explosive expansion breaks during the 3-hour post-OR window.
 * Enforces strict 1-trade-per-session limit via SessionState.
 */
class SormStrategy(
    val orBarsCount: Int = 4, // 4 15m bars = 1 hour Opening Range
    val minOrHeightAtr: Double = 0.70,
    val maxOrHeightAtr: Double = 3.5,
    val minBreakoutAtr: Double = 0.05,
    val minBreakVolumeMultiplier: Double = 1.20,
    val minAtrPct: Double = 0.25,
    val maxAtrPct: Double = 6.0,
    val stopLossPercent: Double = 3.0,
    val targetPricePercent: Double = 1.5,
    val plannedRR: Double = targetPricePercent / stopLossPercent,
    val expiryBars: Int = 16
) : Strategy {

    override val id: String = "sorm"
    override val name: String = "Session Opening Range Momentum Strategy"
    override val description: String = "Session expansion breakout strategy trading verified breaks of the 1-hour Asia and US opening ranges."
    override val requiredCandleCount: Int = 40
    override val primaryInterval: Interval = Interval.M15
    override val requiredIntervals: Set<Interval> = setOf(Interval.M15)
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.TRENDING_MOMENTUM
    override val parametersSummary: String = "OR: 1h (${orBarsCount}x15m), VolBreak: ${minBreakVolumeMultiplier}x, Height: [${minOrHeightAtr}..${maxOrHeightAtr}] ATR, RR: 1:${"%.2f".format(plannedRR)}"

    private data class SessionWindow(
        val sessionId: String,
        val sessionStartMs: Long,
        val orStartMs: Long,
        val orEndMs: Long,
        val evalEndMs: Long
    )

    private fun resolveSession(barTime: Long): SessionWindow? {
        val msInDay = barTime % 86_400_000L
        val dayStart = barTime - msInDay

        // Asia Session: OR 00:00 to 01:00 UTC, Evaluation 01:00 to 04:00 UTC
        val asiaOrStart = dayStart
        val asiaOrEnd = dayStart + 3_600_000L
        val asiaEvalEnd = dayStart + 14_400_000L

        if (barTime in asiaOrEnd until asiaEvalEnd) {
            return SessionWindow("ASIA", dayStart, asiaOrStart, asiaOrEnd, asiaEvalEnd)
        }

        // US Session: OR 13:30 to 14:30 UTC, Evaluation 14:30 to 17:30 UTC
        // 13h 30m = (13 * 3600 + 30 * 60) * 1000 = 48,600,000 ms
        val usOrStart = dayStart + 48_600_000L
        val usOrEnd = dayStart + 52_200_000L
        val usEvalEnd = dayStart + 63_000_000L

        if (barTime in usOrEnd until usEvalEnd) {
            return SessionWindow("US", usOrStart, usOrStart, usOrEnd, usEvalEnd)
        }

        return null
    }

    override fun evaluate(ctx: SymbolContext, state: StrategyState?): StrategyResult {
        val series = ctx.primarySeries
        val rejections = mutableListOf<RejectionCode>()

        if (series.size < requiredCandleCount) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
        }

        val currBarTime = series.openTime(0)
        val currClose = series.close(0)
        val session = resolveSession(currBarTime)

        // 0. Active Position Exit Management (Hard Session Exit outside window or failure exit)
        val activePos = ctx.activePosition
        if (activePos != null && activePos.isOpen) {
            if (session == null) {
                return StrategyResult(
                    signal = Signal(
                        symbol = ctx.symbol, strategyId = id,
                        direction = if (activePos.isLong) SignalDirection.LONG else SignalDirection.SHORT,
                        barOpenTimeUtc = currBarTime, entryRef = currClose, strategyName = name,
                        reason = "SORM Hard Session EXIT: Current bar outside trade session window",
                        explicitAction = SignalAction.EXIT
                    ), newState = state
                )
            }
            val orState = state as? SessionState
            if (orState != null) {
                if (activePos.isLong && currClose < orState.orLow) {
                    return StrategyResult(
                        signal = Signal(
                            symbol = ctx.symbol, strategyId = id, direction = SignalDirection.LONG,
                            barOpenTimeUtc = currBarTime, entryRef = currClose, strategyName = name,
                            reason = "SORM Long EXIT: Price fell back inside Opening Range (Close %.2f < OR Low %.2f)".format(currClose, orState.orLow),
                            explicitAction = SignalAction.EXIT
                        ), newState = state
                    )
                } else if (activePos.isShort && currClose > orState.orHigh) {
                    return StrategyResult(
                        signal = Signal(
                            symbol = ctx.symbol, strategyId = id, direction = SignalDirection.SHORT,
                            barOpenTimeUtc = currBarTime, entryRef = currClose, strategyName = name,
                            reason = "SORM Short EXIT: Price rose back inside Opening Range (Close %.2f > OR High %.2f)".format(currClose, orState.orHigh),
                            explicitAction = SignalAction.EXIT
                        ), newState = state
                    )
                }
            }
        }

        if (session == null) {
            return StrategyResult(null, state, listOf(RejectionCode.S4_REGIME_OUTSIDE_SESSION))
        }

        // Check if session has already signalled
        val prevState = state as? SessionState
        if (prevState != null && prevState.sessionId == session.sessionId &&
            prevState.sessionStartOpenTime == session.sessionStartMs && prevState.hasSignalled
        ) {
            return StrategyResult(null, state, listOf(RejectionCode.S4_ALREADY_SIGNALLED))
        }

        // 1. Locate Opening Range Bars
        var orHigh = Double.MIN_VALUE
        var orLow = Double.MAX_VALUE
        var orVolSum = 0.0
        var orFoundCount = 0

        for (i in 1 until min(30, series.size)) {
            val t = series.openTime(i)
            if (t in session.orStartMs until session.orEndMs) {
                orHigh = max(orHigh, series.high(i))
                orLow = min(orLow, series.low(i))
                orVolSum += series.volume(i)
                orFoundCount++
            }
        }

        if (orFoundCount < orBarsCount) {
            return StrategyResult(null, state, listOf(RejectionCode.S4_REGIME_OUTSIDE_SESSION))
        }

        val orHeight = orHigh - orLow
        val atr = TechnicalIndicators.calculateAtr(series, 14, barIndex = 0)
        val atrPct = if (currClose > 0.0) (atr / currClose) * 100.0 else 0.0

        if (atrPct < minAtrPct || atrPct > maxAtrPct) {
            return StrategyResult(null, state, listOf(RejectionCode.S4_STOP_TOO_WIDE))
        }

        // Filter OR height relative to ATR
        if (orHeight < (minOrHeightAtr * atr) || orHeight > (maxOrHeightAtr * atr)) {
            return StrategyResult(null, state, listOf(RejectionCode.S4_REGIME_OR_HEIGHT))
        }

        val volSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = 0)
        val currHigh = series.high(0)
        val currLow = series.low(0)
        val barRange = currHigh - currLow
        val safeRange = if (barRange > 0.0) barRange else 0.0001
        val midOr = (orHigh + orLow) / 2.0

        // 2. Evaluate Bullish Breakout (Long)
        val isBullishBreak = currClose > orHigh && (currClose - orHigh) >= (minBreakoutAtr * atr)
        val isBullishCloseLocation = ((currClose - currLow) / safeRange) >= 0.60
        val isBreakVolume = volSma <= 0.0 || series.volume(0) >= minBreakVolumeMultiplier * volSma

        if (isBullishBreak) {
            if (!isBullishCloseLocation) rejections.add(RejectionCode.S4_C7_CLOSE_LOCATION)
            if (!isBreakVolume) rejections.add(RejectionCode.S4_C4_VOLUME_FLOOR)

            if (rejections.isEmpty()) {
                val clampedDist = currClose * (stopLossPercent / 100.0)
                val stopLoss = currClose - clampedDist
                val clampedTpDist = currClose * (targetPricePercent / 100.0)
                val takeProfit = currClose + clampedTpDist

                val strengths = mapOf(
                    "breakoutDecisiveness" to ((currClose - orHigh) / atr).coerceIn(0.0, 1.0),
                    "closeLocation" to ((currClose - currLow) / safeRange).coerceIn(0.0, 1.0),
                    "volumeSurge" to if (volSma > 0.0) (series.volume(0) / (2.0 * volSma)).coerceIn(0.0, 1.0) else 0.5,
                    "orExpansionRatio" to (orHeight / (2.0 * atr)).coerceIn(0.0, 1.0)
                )

                val signal = Signal(
                    symbol = ctx.symbol,
                    strategyId = id,
                    direction = SignalDirection.LONG,
                    barOpenTimeUtc = currBarTime,
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = takeProfit, plannedRR = targetPricePercent / stopLossPercent),
                    riskDistance = clampedDist,
                    riskPct = stopLossPercent,
                    regimeTag = RegimeTag.TREND_UP,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "SORM Long: %s session breakout above OR High %.2f (OR: [%.2f..%.2f])".format(
                        session.sessionId, orHigh, orLow, orHigh
                    ),
                    confidenceScore = 83.0
                )

                val newState = SessionState(
                    sessionId = session.sessionId,
                    sessionStartOpenTime = session.sessionStartMs,
                    orHigh = orHigh,
                    orLow = orLow,
                    orVolumeSum = orVolSum,
                    hasSignalled = true,
                    lastUpdatedBarOpenTime = currBarTime
                )

                return StrategyResult(signal, newState, emptyList())
            }
        }

        // 3. Evaluate Bearish Breakdown (Short)
        val isBearishBreak = currClose < orLow && (orLow - currClose) >= (minBreakoutAtr * atr)
        val isBearishCloseLocation = ((currHigh - currClose) / safeRange) >= 0.60

        if (isBearishBreak) {
            if (!isBearishCloseLocation) rejections.add(RejectionCode.S4_C7_CLOSE_LOCATION)
            if (!isBreakVolume) rejections.add(RejectionCode.S4_C4_VOLUME_FLOOR)

            if (rejections.isEmpty()) {
                val clampedDist = currClose * (stopLossPercent / 100.0)
                val stopLoss = currClose + clampedDist
                val clampedTpDist = currClose * (targetPricePercent / 100.0)
                val takeProfit = currClose - clampedTpDist

                val strengths = mapOf(
                    "breakoutDecisiveness" to ((orLow - currClose) / atr).coerceIn(0.0, 1.0),
                    "closeLocation" to ((currHigh - currClose) / safeRange).coerceIn(0.0, 1.0),
                    "volumeSurge" to if (volSma > 0.0) (series.volume(0) / (2.0 * volSma)).coerceIn(0.0, 1.0) else 0.5,
                    "orExpansionRatio" to (orHeight / (2.0 * atr)).coerceIn(0.0, 1.0)
                )

                val signal = Signal(
                    symbol = ctx.symbol,
                    strategyId = id,
                    direction = SignalDirection.SHORT,
                    barOpenTimeUtc = currBarTime,
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = takeProfit, plannedRR = targetPricePercent / stopLossPercent),
                    riskDistance = clampedDist,
                    riskPct = stopLossPercent,
                    regimeTag = RegimeTag.TREND_DOWN,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "SORM Short: %s session breakdown below OR Low %.2f (OR: [%.2f..%.2f])".format(
                        session.sessionId, orLow, orLow, orHigh
                    ),
                    confidenceScore = 83.0
                )

                val newState = SessionState(
                    sessionId = session.sessionId,
                    sessionStartOpenTime = session.sessionStartMs,
                    orHigh = orHigh,
                    orLow = orLow,
                    orVolumeSum = orVolSum,
                    hasSignalled = true,
                    lastUpdatedBarOpenTime = currBarTime
                )

                return StrategyResult(signal, newState, emptyList())
            }
        }

        if (rejections.isEmpty()) {
            rejections.add(RejectionCode.S4_C2_C3_BREAK_NOT_TRIGGERED)
        }

        val updatedState = SessionState(
            sessionId = session.sessionId,
            sessionStartOpenTime = session.sessionStartMs,
            orHigh = orHigh,
            orLow = orLow,
            orVolumeSum = orVolSum,
            hasSignalled = false,
            lastUpdatedBarOpenTime = currBarTime
        )

        return StrategyResult(null, updatedState, rejections)
    }
}
