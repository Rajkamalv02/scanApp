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
    private val atrMultiplier: Double = 1.5,
    private val defaultLeverage: Int = 2
) : Strategy {

    override val id: String = "ema_crossover"
    override val name: String = "EMA Trend Crossover"
    override val description: String = "Scans golden/death crosses between Fast EMA ($fastPeriod) & Slow EMA ($slowPeriod) with volume & ATR scoring."
    override val parametersSummary: String = "Fast: $fastPeriod | Slow: $slowPeriod | Leverage: ${defaultLeverage}x | SL: ${atrMultiplier}x ATR"
    override val requiredCandleCount: Int = 50
    override val defaultTimeframe: String = "15m"

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(SignalAction.HOLD, reason = "Insufficient candle history (${candles.size}/$requiredCandleCount)", confidenceScore = 0.0)
        }

        val closePrices = candles.map { it.close }
        val fastEma = TechnicalIndicators.calculateEma(closePrices, fastPeriod)
        val slowEma = TechnicalIndicators.calculateEma(closePrices, slowPeriod)

        if (fastEma.size < 2 || slowEma.size < 2) {
            return Signal(SignalAction.HOLD, reason = "EMA calculation warm-up", confidenceScore = 0.0)
        }

        // Align latest values
        val prevFast = fastEma[fastEma.size - 2]
        val currFast = fastEma.last()

        val prevSlow = slowEma[slowEma.size - 2]
        val currSlow = slowEma.last()

        val currentPrice = closePrices.last()
        val latestCandle = candles.last()
        val atr = TechnicalIndicators.calculateAtr(candles, 14)

        // Volume momentum
        val avgVolume = candles.takeLast(10).map { it.volume }.average()
        val volumeBoost = if (latestCandle.volume > avgVolume * 1.2) 10.0 else 0.0

        val isBullishCross = prevFast <= prevSlow && currFast > currSlow
        val isBearishCross = prevFast >= prevSlow && currFast < currSlow

        // If we already have an open position, check for exit condition
        if (activePosition != null && activePosition.isOpen) {
            if (activePosition.isLong && isBearishCross) {
                return Signal(SignalAction.EXIT, reason = "Bearish EMA cross exit for Long", confidenceScore = 85.0)
            }
            if (activePosition.isShort && isBullishCross) {
                return Signal(SignalAction.EXIT, reason = "Bullish EMA cross exit for Short", confidenceScore = 85.0)
            }
            return Signal(SignalAction.HOLD, reason = "Position active; trend intact", confidenceScore = 50.0)
        }

        // 1. Fresh Golden Cross
        if (isBullishCross) {
            val candleColorBoost = if (latestCandle.close > latestCandle.open) 8.0 else 0.0
            val score = (75.0 + volumeBoost + candleColorBoost).coerceIn(60.0, 98.0)

            val stopLoss = currentPrice - (atr * atrMultiplier)
            val takeProfit = currentPrice + (atr * atrMultiplier * 2.0)
            return Signal(
                action = SignalAction.ENTER_LONG,
                suggestedLeverage = defaultLeverage,
                stopLossPrice = stopLoss,
                takeProfitPrice = takeProfit,
                reason = "Golden Cross: Fast EMA ($fastPeriod) crossed above Slow EMA ($slowPeriod)",
                confidenceScore = score
            )
        }

        // 2. Fresh Death Cross
        if (isBearishCross) {
            val candleColorBoost = if (latestCandle.close < latestCandle.open) 8.0 else 0.0
            val score = (75.0 + volumeBoost + candleColorBoost).coerceIn(60.0, 98.0)

            val stopLoss = currentPrice + (atr * atrMultiplier)
            val takeProfit = currentPrice - (atr * atrMultiplier * 2.0)
            return Signal(
                action = SignalAction.ENTER_SHORT,
                suggestedLeverage = defaultLeverage,
                stopLossPrice = stopLoss,
                takeProfitPrice = takeProfit,
                reason = "Death Cross: Fast EMA ($fastPeriod) crossed below Slow EMA ($slowPeriod)",
                confidenceScore = score
            )
        }

        // 3. Strong Trend Continuation / Dip-buying into EMA
        val spreadPct = abs(currFast - currSlow) / currSlow
        if (currFast > currSlow && spreadPct > 0.005) {
            // Strong bullish trend: price near fast EMA
            if (currentPrice >= currFast * 0.995 && currentPrice <= currFast * 1.01) {
                val score = (60.0 + volumeBoost).coerceIn(50.0, 78.0)
                val stopLoss = currentPrice - (atr * atrMultiplier)
                val takeProfit = currentPrice + (atr * atrMultiplier * 2.0)
                return Signal(
                    action = SignalAction.ENTER_LONG,
                    suggestedLeverage = defaultLeverage,
                    stopLossPrice = stopLoss,
                    takeProfitPrice = takeProfit,
                    reason = "Bullish Trend Continuation: Pullback bounce to EMA $fastPeriod",
                    confidenceScore = score
                )
            }
        } else if (currFast < currSlow && spreadPct > 0.005) {
            // Strong bearish trend
            if (currentPrice <= currFast * 1.005 && currentPrice >= currFast * 0.99) {
                val score = (60.0 + volumeBoost).coerceIn(50.0, 78.0)
                val stopLoss = currentPrice + (atr * atrMultiplier)
                val takeProfit = currentPrice - (atr * atrMultiplier * 2.0)
                return Signal(
                    action = SignalAction.ENTER_SHORT,
                    suggestedLeverage = defaultLeverage,
                    stopLossPrice = stopLoss,
                    takeProfitPrice = takeProfit,
                    reason = "Bearish Trend Continuation: Pullback reject from EMA $fastPeriod",
                    confidenceScore = score
                )
            }
        }

        return Signal(SignalAction.HOLD, reason = "No strong setup detected", confidenceScore = 15.0)
    }
}
