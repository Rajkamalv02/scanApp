package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy

/**
 * Two-Way Institutional Strategy: Supply & Demand Engulfing MACD
 *
 * Parallel trigger factory that encapsulates both Short (Supply Zone Rejections)
 * and Long (Demand Zone Bounces) institutional setups behind a unified Strategy interface.
 * Eliminates directional blind spots in bullish, bearish, and rangebound regimes.
 */
class SupplyDemandBidirectionalStrategy(
    fastMacd: Int = 12,
    slowMacd: Int = 26,
    signalMacd: Int = 9,
    adxPeriod: Int = 14,
    adxMin: Double = 18.0,
    emaTrendPeriod: Int = 200,
    atrPeriod: Int = 14,
    defaultLeverage: Int = 2,
    maxZoneAge: Int = 300
) : Strategy {

    private val shortStrategy = SupplyDemandEngulfingMacdStrategy(
        fastMacd = fastMacd,
        slowMacd = slowMacd,
        signalMacd = signalMacd,
        adxPeriod = adxPeriod,
        adxMin = adxMin,
        emaTrendPeriod = emaTrendPeriod,
        atrPeriod = atrPeriod,
        defaultLeverage = defaultLeverage,
        maxZoneAge = maxZoneAge
    )

    private val longStrategy = SupplyDemandEngulfingMacdLongStrategy(
        fastMacd = fastMacd,
        slowMacd = slowMacd,
        signalMacd = signalMacd,
        adxPeriod = adxPeriod,
        adxMin = adxMin,
        emaTrendPeriod = emaTrendPeriod,
        atrPeriod = atrPeriod,
        defaultLeverage = defaultLeverage,
        maxZoneAge = maxZoneAge
    )

    override val id: String = "supply_demand_bidirectional"
    override val name: String = "Supply & Demand Institutional (Two-Way)"
    override val description: String = "Two-way institutional strategy evaluating supply & demand zones, engulfing reversals, and contemporaneous MACD momentum for both Longs and Shorts with strict fee-adjusted clearance."
    override val parametersSummary: String = "Bidirectional | 15m | Fee-Adjusted Net R:R >= 1.80 | Trend & Exhaustion Gates"
    override val requiredCandleCount: Int = 215
    override val defaultTimeframe: String = "15m"

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Insufficient candle history (${candles.size}/$requiredCandleCount)",
                confidenceScore = 0.0
            )
        }

        // 1. Manage Active Positions via the Direction-Specific Handler
        if (activePosition != null && activePosition.isOpen) {
            return if (activePosition.isShort) {
                shortStrategy.evaluate(candles, activePosition)
            } else {
                longStrategy.evaluate(candles, activePosition)
            }
        }

        // 2. Parallel Trigger Evaluation (Evaluate both Long and Short setup factories)
        val shortSignal = shortStrategy.evaluate(candles, null)
        val longSignal = longStrategy.evaluate(candles, null)

        val isShortEntry = shortSignal.action == SignalAction.ENTER_SHORT
        val isLongEntry = longSignal.action == SignalAction.ENTER_LONG

        return when {
            isShortEntry && isLongEntry -> {
                // If both fire simultaneously (rare regime shift), select higher conviction
                if (longSignal.confidenceScore >= shortSignal.confidenceScore) longSignal else shortSignal
            }
            isShortEntry -> shortSignal
            isLongEntry -> longSignal
            else -> {
                // Both HOLD - return the one with higher diagnostic score
                if (longSignal.confidenceScore >= shortSignal.confidenceScore) longSignal else shortSignal
            }
        }
    }
}
