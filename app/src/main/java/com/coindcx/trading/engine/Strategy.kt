package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle

enum class SignalAction {
    ENTER_LONG,
    ENTER_SHORT,
    EXIT,
    HOLD
}

data class Signal(
    val action: SignalAction,
    val suggestedQuantity: Double = 0.0,
    val suggestedLeverage: Int = 1,
    val stopLossPrice: Double? = null,
    val takeProfitPrice: Double? = null,
    val reason: String = ""
)

/**
 * Pure, deterministic strategy interface.
 * Has zero dependency on network, database, or UI.
 * Signal = f(candles, activePosition)
 */
interface Strategy {
    val id: String
    val name: String
    val description: String
    val parametersSummary: String
    val requiredCandleCount: Int
    val defaultTimeframe: String // e.g. "5m"

    fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?): Signal
}
