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
 * Strategy 6: Funding & Positioning Extreme Fade (FPX) (§1.6 & §3a Degraded Variant).
 *
 * Evidence Class: Market Crowding & Overextended Perp Mean Reversion.
 * Fades extreme directional crowding when price is stretched >= 2.5 ATR from 1H EMA20,
 * supported by multi-bar directional exhaustion (>= 6 of 8 bars trending), RSI deceleration,
 * volume-confirmed reversal candle, and takes out the prior bar's extreme.
 */
class FpxStrategy(
    val emaFastPeriod: Int = 20,
    val emaSlowPeriod: Int = 50,
    val minAtrPct: Double = 0.80,
    val maxAtrPct: Double = 5.0,
    val minDistanceAtr: Double = 2.5,
    val minConsecutiveDirectionalBars: Int = 6,
    val maxHtfAdx: Double = 35.0,
    val minNetRR: Double = 1.3,
    val expiryBars: Int = 16
) : Strategy {

    override val id: String = "fpx"
    override val name: String = "Funding & Positioning Extreme Fade Strategy"
    override val description: String = "Counter-trend strategy fading extreme positioning and price crowding overextensions back to the 20/50 EMA mean."
    override val requiredCandleCount: Int = max(emaSlowPeriod + 20, 70)
    override val primaryInterval: Interval = Interval.H1
    override val requiredIntervals: Set<Interval> = setOf(Interval.H1, Interval.H4)
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.MEAN_REVERTING_RANGE
    override val parametersSummary: String = "EMA: $emaFastPeriod/$emaSlowPeriod, ExtDist >= ${minDistanceAtr}x ATR, HTF ADX <= $maxHtfAdx, Min R:R: 1:$minNetRR"

    override fun evaluate(ctx: SymbolContext, state: StrategyState?): StrategyResult {
        val series = ctx.primarySeries
        val rejections = mutableListOf<RejectionCode>()

        if (series.size < requiredCandleCount) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
        }

        val currClose = series.close(0)
        val currOpen = series.open(0)
        val currHigh = series.high(0)
        val currLow = series.low(0)
        val barRange = currHigh - currLow
        val safeRange = if (barRange > 0.0) barRange else 0.0001

        val atr = TechnicalIndicators.calculateAtr(series, 14, barIndex = 0)
        val ema20 = TechnicalIndicators.calculateEmaAt(series, emaFastPeriod, barIndex = 0)
        val ema50 = TechnicalIndicators.calculateEmaAt(series, emaSlowPeriod, barIndex = 0)

        // 0. Active Position Exit Management
        val activePos = ctx.activePosition
        if (activePos != null && activePos.isOpen) {
            if (activePos.isLong) {
                if (currClose >= ema20) {
                    val exitSignal = Signal(
                        symbol = ctx.symbol,
                        strategyId = id,
                        direction = SignalDirection.LONG,
                        barOpenTimeUtc = series.openTime(0),
                        entryRef = currClose,
                        strategyName = name,
                        reason = "FPX Long EXIT: Reached mean reversion target (Close %.2f >= EMA20 %.2f)".format(currClose, ema20),
                        explicitAction = SignalAction.EXIT
                    )
                    return StrategyResult(signal = exitSignal, newState = state)
                }
            } else if (activePos.isShort) {
                if (currClose <= ema20) {
                    val exitSignal = Signal(
                        symbol = ctx.symbol,
                        strategyId = id,
                        direction = SignalDirection.SHORT,
                        barOpenTimeUtc = series.openTime(0),
                        entryRef = currClose,
                        strategyName = name,
                        reason = "FPX Short EXIT: Reached mean reversion target (Close %.2f <= EMA20 %.2f)".format(currClose, ema20),
                        explicitAction = SignalAction.EXIT
                    )
                    return StrategyResult(signal = exitSignal, newState = state)
                }
            }
        }

        // 1. R1: Higher-Timeframe 4H ADX Guard (Never fade a dominant HTF trend)
        val htf4H = ctx.htfSeries[Interval.H4]
        if (htf4H != null && htf4H.size >= 30) {
            val htfAdx = TechnicalIndicators.calculateAdx(htf4H, 14, barIndex = 0)
            if (htfAdx > maxHtfAdx) {
                return StrategyResult(null, state, listOf(RejectionCode.S6_REGIME_ADX_CAP))
            }
        }

        // 2. R3: ATR% Bounds
        val atrPct = if (currClose > 0.0) (atr / currClose) * 100.0 else 0.0
        if (atrPct < minAtrPct || atrPct > maxAtrPct) {
            return StrategyResult(null, state, listOf(RejectionCode.S6_REGIME_ATR_BOUNDS))
        }

        // 3. Price Crowding Extension Distance from EMA20
        val distanceAtr = if (atr > 0.0) (currClose - ema20) / atr else 0.0

        // 4. Consecutive Directional Closes in last 8 bars
        var upCloses = 0
        var downCloses = 0
        for (i in 1..min(8, series.size - 1)) {
            if (series.close(i) > series.open(i)) upCloses++
            if (series.close(i) < series.open(i)) downCloses++
        }

        val rsi0 = TechnicalIndicators.calculateRsi(series, 14, barIndex = 0)
        val rsi3 = if (series.size >= 20) TechnicalIndicators.calculateRsi(series, 14, barIndex = 3) else rsi0
        val rsiSlope = rsi0 - rsi3
        val volSma = TechnicalIndicators.calculateVolumeSma(series, 20, barIndex = 0)

        // 5. Long Setup: Fade Downside Capitulation Dump
        val isDownsideExtreme = distanceAtr <= -minDistanceAtr && downCloses >= minConsecutiveDirectionalBars
        if (isDownsideExtreme) {
            if (rsi0 > 28.0) rejections.add(RejectionCode.S6_C4_RSI_THRESHOLD)
            if (rsiSlope <= 0.0) rejections.add(RejectionCode.S6_C5_MOMENTUM_DECELERATION)

            // Reversal candle: bullish close in upper 60% of range
            val isBullishCandle = currClose > currOpen
            val lowerWick = (currClose - currLow) / safeRange
            if (!isBullishCandle || lowerWick < 0.60) rejections.add(RejectionCode.S6_C6_REVERSAL_CANDLE)

            // Trigger: takes out prior bar's high
            if (currClose <= series.high(1)) rejections.add(RejectionCode.S6_C7_TRIGGER_EXTREME)

            // Volume floor
            if (volSma > 0.0 && series.volume(0) < 1.2 * volSma) rejections.add(RejectionCode.S6_C8_VOLUME_FLOOR)

            if (rejections.isEmpty()) {
                var lowestLow = currLow
                for (b in 1..min(3, series.size - 1)) {
                    if (series.low(b) < lowestLow) lowestLow = series.low(b)
                }
                val rawStop = lowestLow - 0.5 * atr
                val rawDist = currClose - rawStop
                val clampedDist = rawDist.coerceIn(0.8 * atr, 2.0 * atr)
                val stopLoss = currClose - clampedDist

                val targetPrice = ema20
                val netDistToMean = targetPrice - currClose
                val netRR = if (clampedDist > 0.0) netDistToMean / clampedDist else 0.0

                if (netRR < minNetRR) {
                    return StrategyResult(null, state, listOf(RejectionCode.S6_RR_GATE))
                }

                val strengths = mapOf(
                    "positioningExtreme" to ((abs(distanceAtr) - minDistanceAtr) / 2.5).coerceIn(0.0, 1.0),
                    "momentumReversal" to (abs(rsiSlope) / 8.0).coerceIn(0.0, 1.0),
                    "reversalCandle" to lowerWick.coerceIn(0.0, 1.0),
                    "distanceToMean" to (((netRR - minNetRR) / 2.0).coerceIn(0.0, 1.0))
                )

                val signal = Signal(
                    symbol = ctx.symbol,
                    strategyId = id,
                    direction = SignalDirection.LONG,
                    barOpenTimeUtc = series.openTime(0),
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = targetPrice, tp2 = ema50, plannedRR = netRR),
                    riskDistance = clampedDist,
                    riskPct = (clampedDist / currClose) * 100.0,
                    regimeTag = RegimeTag.RANGE,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "FPX Long: Crowded short dump faded (Dist %.1f ATR, RSI %.1f hook +%.1f). Mean target=%.2f (R:R 1:%.1f)".format(
                        abs(distanceAtr), rsi0, rsiSlope, targetPrice, netRR
                    ),
                    confidenceScore = 80.0
                )

                return StrategyResult(signal = signal, newState = state)
            }
        }

        // 6. Short Setup: Fade Upside Blowoff Pump
        val isUpsideExtreme = distanceAtr >= minDistanceAtr && upCloses >= minConsecutiveDirectionalBars
        if (isUpsideExtreme) {
            if (rsi0 < 72.0) rejections.add(RejectionCode.S6_C4_RSI_THRESHOLD)
            if (rsiSlope >= 0.0) rejections.add(RejectionCode.S6_C5_MOMENTUM_DECELERATION)

            // Reversal candle: bearish close in lower 60% of range
            val isBearishCandle = currClose < currOpen
            val upperWick = (currHigh - currClose) / safeRange
            if (!isBearishCandle || upperWick < 0.60) rejections.add(RejectionCode.S6_C6_REVERSAL_CANDLE)

            // Trigger: takes out prior bar's low
            if (currClose >= series.low(1)) rejections.add(RejectionCode.S6_C7_TRIGGER_EXTREME)

            // Volume floor
            if (volSma > 0.0 && series.volume(0) < 1.2 * volSma) rejections.add(RejectionCode.S6_C8_VOLUME_FLOOR)

            if (rejections.isEmpty()) {
                var highestHigh = currHigh
                for (b in 1..min(3, series.size - 1)) {
                    if (series.high(b) > highestHigh) highestHigh = series.high(b)
                }
                val rawStop = highestHigh + 0.5 * atr
                val rawDist = rawStop - currClose
                val clampedDist = rawDist.coerceIn(0.8 * atr, 2.0 * atr)
                val stopLoss = currClose + clampedDist

                val targetPrice = ema20
                val netDistToMean = currClose - targetPrice
                val netRR = if (clampedDist > 0.0) netDistToMean / clampedDist else 0.0

                if (netRR < minNetRR) {
                    return StrategyResult(null, state, listOf(RejectionCode.S6_RR_GATE))
                }

                val strengths = mapOf(
                    "positioningExtreme" to ((distanceAtr - minDistanceAtr) / 2.5).coerceIn(0.0, 1.0),
                    "momentumReversal" to (abs(rsiSlope) / 8.0).coerceIn(0.0, 1.0),
                    "reversalCandle" to upperWick.coerceIn(0.0, 1.0),
                    "distanceToMean" to (((netRR - minNetRR) / 2.0).coerceIn(0.0, 1.0))
                )

                val signal = Signal(
                    symbol = ctx.symbol,
                    strategyId = id,
                    direction = SignalDirection.SHORT,
                    barOpenTimeUtc = series.openTime(0),
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = targetPrice, tp2 = ema50, plannedRR = netRR),
                    riskDistance = clampedDist,
                    riskPct = (clampedDist / currClose) * 100.0,
                    regimeTag = RegimeTag.RANGE,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "FPX Short: Crowded long pump faded (Dist +%.1f ATR, RSI %.1f hook %.1f). Mean target=%.2f (R:R 1:%.1f)".format(
                        distanceAtr, rsi0, rsiSlope, targetPrice, netRR
                    ),
                    confidenceScore = 80.0
                )

                return StrategyResult(signal = signal, newState = state)
            }
        }

        if (rejections.isEmpty()) {
            rejections.add(RejectionCode.S6_C2_CROWDING_PROXY_THRESHOLD)
        }

        return StrategyResult(null, state, rejections)
    }
}
