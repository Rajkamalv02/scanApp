package com.coindcx.trading.engine.allocation

import com.coindcx.trading.engine.scanner.MarketOpportunity

data class AllocationResult(
    val availableBalanceInr: Double,
    val minMarginPerTradeInr: Double,
    val maxTradesAllowed: Int,
    val allocatedTradesCount: Int,
    val totalAllocatedInr: Double,
    val remainingBalanceInr: Double,
    val fundedOpportunities: List<MarketOpportunity>,
    val unfundedOpportunities: List<MarketOpportunity>,
    val allRankedOpportunities: List<MarketOpportunity>,
    val isInsufficientBalance: Boolean,
    val statusMessage: String
)
