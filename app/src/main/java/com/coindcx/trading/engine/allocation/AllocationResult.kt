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
    val statusMessage: String,
    // Dynamic Risk-Parity & Solvency Metadata
    val accountEquityInr: Double = 0.0,
    val safetyReserveInr: Double = 0.0,
    val exchangeFloorMarginInr: Double = 0.0,
    val economicFloorMarginInr: Double = 0.0,
    val targetRiskPerTradeInr: Double = 0.0,
    val recommendedMarginPerTradeInr: Double = 0.0
)
