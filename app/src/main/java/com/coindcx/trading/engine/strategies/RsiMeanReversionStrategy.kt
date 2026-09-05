package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.indicators.TechnicalIndicators

class RsiMeanReversionStrategy(
    private val rsiPeriod: Int = 14,
    private val oversoldThreshold: Double = 30.0,
    private val overboughtThreshold: Double = 70.0,
    private val defaultLeverage: Int = 2
) : Strategy {

    override val id: String = "rsi_mean_reversion"
    override val name: String = "RSI Mean Reversion"
    override val description: String = "Scans extreme RSI extensions (oversold <$oversoldThreshold, overbought >$overboughtThreshold) for high-probability mean reversion setups."
    override val parametersSummary: String = "Period: $rsiPeriod | Oversold: $oversoldThreshold | Overbought: $overboughtThreshold | Lev: ${defaultLeverage}x"
    override val requiredCandleCount: Int = 50
    override val defaultTimeframe: String = "15m"

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(SignalAction.HOLD, reason = "Insufficient candle history (${candles.size}/$requiredCandleCount)", confidenceScore = 0.0)
        }

        val closePrices = candles.map { it.close }
        val rsi = TechnicalIndicators.calculateRsi(closePrices, rsiPeriod)
        val currentPrice = closePrices.last()
        val atr = TechnicalIndicators.calculateAtr(candles, 14)

        // Exit management for open positions
        if (activePosition != null && activePosition.isOpen) {
            if (activePosition.isLong && rsi >= overboughtThreshold) {
                return Signal(SignalAction.EXIT, reason = "RSI reached overbought (%.1f >= %.1f)".format(rsi, overboughtThreshold), confidenceScore = 85.0)
            }
            if (activePosition.isShort && rsi <= oversoldThreshold) {
                return Signal(SignalAction.EXIT, reason = "RSI reached oversold (%.1f <= %.1f)".format(rsi, oversoldThreshold), confidenceScore = 85.0)
            }
            return Signal(SignalAction.HOLD, reason = "Position active; RSI is %.1f".format(rsi), confidenceScore = 40.0)
        }

        // Entry signals
        if (rsi <= oversoldThreshold) {
            val depth = (oversoldThreshold - rsi).coerceAtLeast(0.0)
            val score = (72.0 + depth * 2.2).coerceIn(60.0, 99.0)

            val stopLoss = currentPrice - (atr * 1.5)
            val takeProfit = currentPrice + (atr * 2.5)
            return Signal(
                action = SignalAction.ENTER_LONG,
                suggestedLeverage = defaultLeverage,
                stopLossPrice = stopLoss,
                takeProfitPrice = takeProfit,
                reason = "RSI Extreme Oversold (%.1f <= %.1f)".format(rsi, oversoldThreshold),
                confidenceScore = score
            )
        }

        if (rsi >= overboughtThreshold) {
            val depth = (rsi - overboughtThreshold).coerceAtLeast(0.0)
            val score = (72.0 + depth * 2.2).coerceIn(60.0, 99.0)

            val stopLoss = currentPrice + (atr * 1.5)
            val takeProfit = currentPrice - (atr * 2.5)
            return Signal(
                action = SignalAction.ENTER_SHORT,
                suggestedLeverage = defaultLeverage,
                stopLossPrice = stopLoss,
                takeProfitPrice = takeProfit,
                reason = "RSI Extreme Overbought (%.1f >= %.1f)".format(rsi, overboughtThreshold),
                confidenceScore = score
            )
        }

        return Signal(SignalAction.HOLD, reason = "RSI neutral (%.1f)".format(rsi), confidenceScore = 10.0)
    }
}
