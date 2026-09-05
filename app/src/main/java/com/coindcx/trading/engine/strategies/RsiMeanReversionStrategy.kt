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
    override val description: String = "Buys when RSI drops below $oversoldThreshold (oversold) and sells when RSI rises above $overboughtThreshold (overbought)."
    override val parametersSummary: String = "Period: $rsiPeriod | Oversold: $oversoldThreshold | Overbought: $overboughtThreshold | Lev: ${defaultLeverage}x"
    override val requiredCandleCount: Int = 50
    override val defaultTimeframe: String = "5m"

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(SignalAction.HOLD, reason = "Insufficient candle history (${candles.size}/$requiredCandleCount)")
        }

        val closePrices = candles.map { it.close }
        val rsi = TechnicalIndicators.calculateRsi(closePrices, rsiPeriod)
        val currentPrice = closePrices.last()
        val atr = TechnicalIndicators.calculateAtr(candles, 14)

        // Exit management for open positions
        if (activePosition != null && activePosition.isOpen) {
            if (activePosition.isLong && rsi >= overboughtThreshold) {
                return Signal(SignalAction.EXIT, reason = "RSI reached overbought ($rsi >= $overboughtThreshold)")
            }
            if (activePosition.isShort && rsi <= oversoldThreshold) {
                return Signal(SignalAction.EXIT, reason = "RSI reached oversold ($rsi <= $oversoldThreshold)")
            }
            return Signal(SignalAction.HOLD, reason = "Position active; RSI is $rsi")
        }

        // Entry signals
        if (rsi <= oversoldThreshold) {
            val stopLoss = currentPrice - (atr * 1.5)
            val takeProfit = currentPrice + (atr * 2.5)
            return Signal(
                action = SignalAction.ENTER_LONG,
                suggestedLeverage = defaultLeverage,
                stopLossPrice = stopLoss,
                takeProfitPrice = takeProfit,
                reason = "RSI Oversold ($rsi <= $oversoldThreshold)"
            )
        }

        if (rsi >= overboughtThreshold) {
            val stopLoss = currentPrice + (atr * 1.5)
            val takeProfit = currentPrice - (atr * 2.5)
            return Signal(
                action = SignalAction.ENTER_SHORT,
                suggestedLeverage = defaultLeverage,
                stopLossPrice = stopLoss,
                takeProfitPrice = takeProfit,
                reason = "RSI Overbought ($rsi >= $overboughtThreshold)"
            )
        }

        return Signal(SignalAction.HOLD, reason = "RSI neutral ($rsi)")
    }
}
