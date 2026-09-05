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
    override val name: String = "RSI Reversal Confluence"
    override val description: String = "Scans extreme RSI extensions (<$oversoldThreshold / >$overboughtThreshold) requiring candlestick reversal confirmation and volume absorption."
    override val parametersSummary: String = "Period: $rsiPeriod | Oversold: $oversoldThreshold | Overbought: $overboughtThreshold | Confirmed Reversal"
    override val requiredCandleCount: Int = 50
    override val defaultTimeframe: String = "15m"

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(SignalAction.HOLD, reason = "Insufficient candle history (${candles.size}/$requiredCandleCount)", confidenceScore = 0.0)
        }

        val closePrices = candles.map { it.close }
        val rsi = TechnicalIndicators.calculateRsi(closePrices, rsiPeriod)
        val currentPrice = closePrices.last()
        val latestCandle = candles.last()
        val prevCandle = candles[candles.size - 2]
        val atr = TechnicalIndicators.calculateAtr(candles, 14)

        // Volume momentum
        val avgVolume = candles.takeLast(20).map { it.volume }.average()
        val isVolumeSurge = latestCandle.volume >= avgVolume * 1.15

        // Exit management for open positions
        if (activePosition != null && activePosition.isOpen) {
            if (activePosition.isLong && rsi >= overboughtThreshold) {
                return Signal(SignalAction.EXIT, reason = "RSI overbought target reached (%.1f >= %.1f)".format(rsi, overboughtThreshold), confidenceScore = 88.0)
            }
            if (activePosition.isShort && rsi <= oversoldThreshold) {
                return Signal(SignalAction.EXIT, reason = "RSI oversold target reached (%.1f <= %.1f)".format(rsi, oversoldThreshold), confidenceScore = 88.0)
            }
            return Signal(SignalAction.HOLD, reason = "Position active; RSI is %.1f".format(rsi), confidenceScore = 40.0)
        }

        // 1. Oversold Reversal (Bullish Long)
        if (rsi <= oversoldThreshold) {
            // Reversal Confirmation: Current candle closes green (buyers step in)
            val isBullishReversal = latestCandle.close > latestCandle.open && latestCandle.close >= prevCandle.close

            if (isBullishReversal) {
                val depth = (oversoldThreshold - rsi).coerceAtLeast(0.0)
                var score = 70.0 + (depth * 2.5) // Deeper oversold = higher confidence
                if (isVolumeSurge) score += 15.0

                val finalScore = score.coerceIn(65.0, 98.0)
                val stopLoss = currentPrice - (atr * 1.5)
                val takeProfit = currentPrice + (atr * 2.5) // 1:1.6+ R:R
                return Signal(
                    action = SignalAction.ENTER_LONG,
                    suggestedLeverage = defaultLeverage,
                    stopLossPrice = stopLoss,
                    takeProfitPrice = takeProfit,
                    reason = "RSI Oversold (%.1f) + Bullish Reversal Candle Confirmation".format(rsi),
                    confidenceScore = finalScore
                )
            } else {
                return Signal(
                    action = SignalAction.HOLD,
                    reason = "RSI Oversold (%.1f) but waiting for green reversal candle confirmation".format(rsi),
                    confidenceScore = 45.0
                )
            }
        }

        // 2. Overbought Reversal (Bearish Short)
        if (rsi >= overboughtThreshold) {
            // Reversal Confirmation: Current candle closes red (sellers reject high)
            val isBearishReversal = latestCandle.close < latestCandle.open && latestCandle.close <= prevCandle.close

            if (isBearishReversal) {
                val depth = (rsi - overboughtThreshold).coerceAtLeast(0.0)
                var score = 70.0 + (depth * 2.5)
                if (isVolumeSurge) score += 15.0

                val finalScore = score.coerceIn(65.0, 98.0)
                val stopLoss = currentPrice + (atr * 1.5)
                val takeProfit = currentPrice - (atr * 2.5)
                return Signal(
                    action = SignalAction.ENTER_SHORT,
                    suggestedLeverage = defaultLeverage,
                    stopLossPrice = stopLoss,
                    takeProfitPrice = takeProfit,
                    reason = "RSI Overbought (%.1f) + Bearish Rejection Candle Confirmation".format(rsi),
                    confidenceScore = finalScore
                )
            } else {
                return Signal(
                    action = SignalAction.HOLD,
                    reason = "RSI Overbought (%.1f) but waiting for red rejection candle confirmation".format(rsi),
                    confidenceScore = 45.0
                )
            }
        }

        return Signal(SignalAction.HOLD, reason = "RSI neutral (%.1f)".format(rsi), confidenceScore = 10.0)
    }
}
