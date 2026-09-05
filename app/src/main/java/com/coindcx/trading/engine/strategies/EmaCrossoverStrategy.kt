package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.indicators.TechnicalIndicators

class EmaCrossoverStrategy(
    private val fastPeriod: Int = 9,
    private val slowPeriod: Int = 21,
    private val atrMultiplier: Double = 1.5,
    private val defaultLeverage: Int = 2
) : Strategy {

    override val id: String = "ema_crossover"
    override val name: String = "EMA Trend Crossover"
    override val description: String = "Trades golden/death crosses between Fast EMA ($fastPeriod) & Slow EMA ($slowPeriod) with ATR dynamic stop-loss."
    override val parametersSummary: String = "Fast: $fastPeriod | Slow: $slowPeriod | Leverage: ${defaultLeverage}x | SL: ${atrMultiplier}x ATR"
    override val requiredCandleCount: Int = 50
    override val defaultTimeframe: String = "5m"

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(SignalAction.HOLD, reason = "Insufficient candle history (${candles.size}/$requiredCandleCount)")
        }

        val closePrices = candles.map { it.close }
        val fastEma = TechnicalIndicators.calculateEma(closePrices, fastPeriod)
        val slowEma = TechnicalIndicators.calculateEma(closePrices, slowPeriod)

        if (fastEma.size < 2 || slowEma.size < 2) {
            return Signal(SignalAction.HOLD, reason = "EMA calculation warm-up")
        }

        // Align latest values
        val prevFast = fastEma[fastEma.size - 2]
        val currFast = fastEma.last()

        val prevSlow = slowEma[slowEma.size - 2]
        val currSlow = slowEma.last()

        val currentPrice = closePrices.last()
        val atr = TechnicalIndicators.calculateAtr(candles, 14)

        val isBullishCross = prevFast <= prevSlow && currFast > currSlow
        val isBearishCross = prevFast >= prevSlow && currFast < currSlow

        // If we already have an open position, check for exit condition
        if (activePosition != null && activePosition.isOpen) {
            if (activePosition.isLong && isBearishCross) {
                return Signal(SignalAction.EXIT, reason = "Bearish EMA cross exit for Long")
            }
            if (activePosition.isShort && isBullishCross) {
                return Signal(SignalAction.EXIT, reason = "Bullish EMA cross exit for Short")
            }
            return Signal(SignalAction.HOLD, reason = "Position active; trend intact")
        }

        // Generate entry signals
        if (isBullishCross) {
            val stopLoss = currentPrice - (atr * atrMultiplier)
            val takeProfit = currentPrice + (atr * atrMultiplier * 2.0) // 1:2 Risk/Reward
            return Signal(
                action = SignalAction.ENTER_LONG,
                suggestedLeverage = defaultLeverage,
                stopLossPrice = stopLoss,
                takeProfitPrice = takeProfit,
                reason = "Golden Cross: Fast EMA ($currFast) crossed above Slow EMA ($currSlow)"
            )
        }

        if (isBearishCross) {
            val stopLoss = currentPrice + (atr * atrMultiplier)
            val takeProfit = currentPrice - (atr * atrMultiplier * 2.0) // 1:2 Risk/Reward
            return Signal(
                action = SignalAction.ENTER_SHORT,
                suggestedLeverage = defaultLeverage,
                stopLossPrice = stopLoss,
                takeProfitPrice = takeProfit,
                reason = "Death Cross: Fast EMA ($currFast) crossed below Slow EMA ($currSlow)"
            )
        }

        return Signal(SignalAction.HOLD, reason = "No crossover detected (Fast: $currFast, Slow: $currSlow)")
    }
}
