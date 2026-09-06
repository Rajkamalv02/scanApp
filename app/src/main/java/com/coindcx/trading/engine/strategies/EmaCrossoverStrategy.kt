package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import kotlin.math.abs

class EmaCrossoverStrategy(
    private val fastPeriod: Int = 9,
    private val slowPeriod: Int = 21,
    private val baselinePeriod: Int = 50,
    private val atrMultiplier: Double = 1.5,
    private val defaultLeverage: Int = 2
) : Strategy {

    override val id: String = "ema_crossover"
    override val name: String = "EMA Trend Confluence"
    override val description: String = "Institutional trend filter ($baselinePeriod EMA) combined with Fast/Slow ($fastPeriod/$slowPeriod) crossovers and volume expansion scoring."
    override val parametersSummary: String = "Fast: $fastPeriod | Slow: $slowPeriod | Trend: $baselinePeriod | SL: ${atrMultiplier}x ATR"
    override val requiredCandleCount: Int = 55
    override val defaultTimeframe: String = "15m"

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(SignalAction.HOLD, reason = "Insufficient candle history (${candles.size}/$requiredCandleCount)", confidenceScore = 0.0)
        }

        val sortedCandles = if (candles.size >= 2 && candles[0].time > candles[1].time) candles.sortedBy { it.time } else candles
        val closePrices = sortedCandles.map { it.close }
        val fastEma = TechnicalIndicators.calculateEma(closePrices, fastPeriod)
        val slowEma = TechnicalIndicators.calculateEma(closePrices, slowPeriod)
        val baselineEma = TechnicalIndicators.calculateEma(closePrices, baselinePeriod)

        if (fastEma.size < 2 || slowEma.size < 2 || baselineEma.isEmpty()) {
            return Signal(SignalAction.HOLD, reason = "EMA calculation warm-up", confidenceScore = 0.0)
        }

        val prevFast = fastEma[fastEma.size - 2]
        val currFast = fastEma.last()

        val prevSlow = slowEma[slowEma.size - 2]
        val currSlow = slowEma.last()

        val currBaseline = baselineEma.last()
        val currentPrice = closePrices.last()
        val latestCandle = sortedCandles.last()
        val atr = TechnicalIndicators.calculateAtr(sortedCandles, 14)

        // 1. Relative Volume Confirmation (20-period baseline)
        val avgVolume = sortedCandles.takeLast(20).map { it.volume }.average()
        val isVolumeSurge = latestCandle.volume >= avgVolume * 1.2

        // 2. Candle Range and Body Strength
        val candleRange = latestCandle.high - latestCandle.low
        val candleBody = abs(latestCandle.close - latestCandle.open)
        val isStrongCandle = candleRange > 0 && (candleBody / candleRange) >= 0.5

        val isBullishCross = prevFast <= prevSlow && currFast > currSlow
        val isBearishCross = prevFast >= prevSlow && currFast < currSlow

        // 3. Exit rules for open positions
        if (activePosition != null && activePosition.isOpen) {
            if (activePosition.isLong && isBearishCross) {
                return Signal(SignalAction.EXIT, reason = "Bearish cross exit for Long position", confidenceScore = 88.0)
            }
            if (activePosition.isShort && isBullishCross) {
                return Signal(SignalAction.EXIT, reason = "Bullish cross exit for Short position", confidenceScore = 88.0)
            }
            return Signal(SignalAction.HOLD, reason = "Position active; trend intact", confidenceScore = 50.0)
        }

        // 4. Bullish Setup (Golden Cross + Trend Confluence)
        if (isBullishCross) {
            var score = 35.0 // Base crossover points

            // Macro Trend Filter: Price > 50 EMA (+30 pts)
            if (currentPrice > currBaseline) {
                score += 30.0
            } else {
                score -= 15.0 // Counter-trend penalty
            }

            // Volume surge (+20 pts)
            if (isVolumeSurge) score += 20.0

            // Strong green candle (+15 pts)
            if (latestCandle.close > latestCandle.open && isStrongCandle) score += 15.0

            val finalScore = score.coerceIn(40.0, 98.0)

            // Only enter if confluence score is at least 65%
            if (finalScore >= 65.0) {
                val stopLoss = currentPrice - (atr * atrMultiplier)
                val takeProfit = currentPrice + (atr * atrMultiplier * 2.0) // 1:2 R:R
                return Signal(
                    action = SignalAction.ENTER_LONG,
                    suggestedLeverage = defaultLeverage,
                    stopLossPrice = stopLoss,
                    takeProfitPrice = takeProfit,
                    reason = "Golden Cross (EMA $fastPeriod/$slowPeriod) + 50 EMA Trend Confluence",
                    confidenceScore = finalScore
                )
            }
        }

        // 5. Bearish Setup (Death Cross + Trend Confluence)
        if (isBearishCross) {
            var score = 35.0

            // Macro Trend Filter: Price < 50 EMA (+30 pts)
            if (currentPrice < currBaseline) {
                score += 30.0
            } else {
                score -= 15.0 // Counter-trend penalty
            }

            if (isVolumeSurge) score += 20.0
            if (latestCandle.close < latestCandle.open && isStrongCandle) score += 15.0

            val finalScore = score.coerceIn(40.0, 98.0)

            if (finalScore >= 65.0) {
                val stopLoss = currentPrice + (atr * atrMultiplier)
                val takeProfit = currentPrice - (atr * atrMultiplier * 2.0)
                return Signal(
                    action = SignalAction.ENTER_SHORT,
                    suggestedLeverage = defaultLeverage,
                    stopLossPrice = stopLoss,
                    takeProfitPrice = takeProfit,
                    reason = "Death Cross (EMA $fastPeriod/$slowPeriod) + 50 EMA Bearish Confluence",
                    confidenceScore = finalScore
                )
            }
        }

        // 6. Trend Pullback Re-entry (Dip-buying in a strong trend)
        val isBullishTrend = currentPrice > currBaseline && currFast > currSlow
        val isBearishTrend = currentPrice < currBaseline && currFast < currSlow

        if (isBullishTrend && currentPrice >= currFast * 0.992 && currentPrice <= currFast * 1.008) {
            if (latestCandle.close > latestCandle.open) {
                val score = if (isVolumeSurge) 75.0 else 68.0
                val stopLoss = currentPrice - (atr * atrMultiplier)
                val takeProfit = currentPrice + (atr * atrMultiplier * 2.0)
                return Signal(
                    action = SignalAction.ENTER_LONG,
                    suggestedLeverage = defaultLeverage,
                    stopLossPrice = stopLoss,
                    takeProfitPrice = takeProfit,
                    reason = "Bullish Pullback Bounce to EMA $fastPeriod (Macro Bullish)",
                    confidenceScore = score
                )
            }
        }

        if (isBearishTrend && currentPrice <= currFast * 1.008 && currentPrice >= currFast * 0.992) {
            if (latestCandle.close < latestCandle.open) {
                val score = if (isVolumeSurge) 75.0 else 68.0
                val stopLoss = currentPrice + (atr * atrMultiplier)
                val takeProfit = currentPrice - (atr * atrMultiplier * 2.0)
                return Signal(
                    action = SignalAction.ENTER_SHORT,
                    suggestedLeverage = defaultLeverage,
                    stopLossPrice = stopLoss,
                    takeProfitPrice = takeProfit,
                    reason = "Bearish Pullback Rejection from EMA $fastPeriod (Macro Bearish)",
                    confidenceScore = score
                )
            }
        }

        if (isBullishTrend) {
            val score = if (isVolumeSurge) 65.0 else 55.0
            return Signal(
                action = SignalAction.HOLD,
                reason = "Bullish Trend ($fastPeriod > $slowPeriod EMA, Price > $baselinePeriod EMA). Awaiting trigger",
                confidenceScore = score
            )
        }

        if (isBearishTrend) {
            val score = if (isVolumeSurge) 65.0 else 55.0
            return Signal(
                action = SignalAction.HOLD,
                reason = "Bearish Trend ($fastPeriod < $slowPeriod EMA, Price < $baselinePeriod EMA). Awaiting trigger",
                confidenceScore = score
            )
        }

        return Signal(SignalAction.HOLD, reason = "Consolidation / Chop (No clear trend)", confidenceScore = 25.0)
    }
}
