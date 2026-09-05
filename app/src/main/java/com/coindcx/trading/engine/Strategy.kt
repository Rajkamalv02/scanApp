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
 * Pluggable Strategy Interface
 * Pure decision logic — completely decoupled from API execution and database.
 */
interface Strategy {
    val name: String
    fun evaluate(candles: List<MarketCandle>, currentPosition: FuturesPosition?): Signal
}
