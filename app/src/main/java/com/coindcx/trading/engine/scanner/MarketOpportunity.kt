package com.coindcx.trading.engine.scanner

import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction

enum class OpportunityLifecycle {
    SCANNED,
    RANKED,
    SELECTED_FOR_TRADE,
    TRADE_PLACED,
    ACTIVE_POSITION,
    CLOSED,
    UNFUNDED
}

data class MarketOpportunity(
    val pair: String,
    val signal: Signal,
    val currentPrice: Double,
    val confidenceScore: Double,
    val rank: Int = 0, // 1 to 5
    val lifecycleState: OpportunityLifecycle = OpportunityLifecycle.SCANNED,
    val allocatedMarginInr: Double = 0.0,
    val estimatedQuantity: Double = 0.0,
    val statusMessage: String = ""
) {
    val isBuy: Boolean get() = signal.action == SignalAction.ENTER_LONG
    val actionLabel: String get() = if (isBuy) "LONG" else "SHORT"
    val assetSymbol: String get() = pair.removePrefix("B-").removeSuffix("_USDT")
}
