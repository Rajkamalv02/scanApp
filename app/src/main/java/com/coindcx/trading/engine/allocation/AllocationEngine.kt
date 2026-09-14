package com.coindcx.trading.engine.allocation

import com.coindcx.trading.engine.RiskSettings
import com.coindcx.trading.engine.scanner.MarketOpportunity
import com.coindcx.trading.engine.scanner.OpportunityLifecycle

class AllocationEngine {

    /**
     * Institutional Waterfall Capital Allocator:
     * 1. Derives minimum margin needed to satisfy CoinDCX exchange notional floor at given leverage:
     *    minExchangeMargin = minExchangeNotional / effectiveLeverage
     * 2. Rejects early if wallet balance cannot afford even ONE minimum exchange order.
     * 3. Allocates full target budget (userBudgetInr) to ranked candidates up to maxConcurrentPositions.
     * 4. Capital Efficiency Closure: If remaining balance < userBudgetInr but >= minExchangeMargin,
     *    allocates the remainder to one more candidate so capital is never left idle.
     */
    fun allocateCapital(
        availableBalanceInr: Double,
        userBudgetInr: Double,
        leverage: Int = 2,
        rankedOpportunities: List<MarketOpportunity>,
        riskSettings: RiskSettings = RiskSettings(),
        minExchangeNotionalInr: Double = 620.0
    ): AllocationResult {
        val effectiveLev = leverage.coerceIn(1, riskSettings.maxLeverage)
        val minExchangeMarginPerTrade = minExchangeNotionalInr / effectiveLev

        // Step 1: Wallet cannot afford even ONE minimum exchange order
        if (availableBalanceInr < minExchangeMarginPerTrade || userBudgetInr <= 0.0 || rankedOpportunities.isEmpty()) {
            val updatedRanked = rankedOpportunities.map {
                it.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Unfunded: Available ₹%.2f < ₹%.2f min exchange requirement at %dx leverage."
                        .format(availableBalanceInr, minExchangeMarginPerTrade, effectiveLev)
                )
            }
            return AllocationResult(
                availableBalanceInr = availableBalanceInr,
                minMarginPerTradeInr = minExchangeMarginPerTrade,
                maxTradesAllowed = 0,
                allocatedTradesCount = 0,
                totalAllocatedInr = 0.0,
                remainingBalanceInr = availableBalanceInr,
                fundedOpportunities = emptyList(),
                unfundedOpportunities = updatedRanked,
                allRankedOpportunities = updatedRanked,
                isInsufficientBalance = true,
                statusMessage = "Insufficient Balance: Available ₹%.2f is less than ₹%.2f required for 1 trade."
                    .format(availableBalanceInr, minExchangeMarginPerTrade)
            )
        }

        // Step 2: Waterfall Allocation (Sourced directly from riskSettings.maxConcurrentPositions)
        val maxPortfolioPositions = riskSettings.maxConcurrentPositions
        val candidateLimit = rankedOpportunities.size.coerceAtMost(maxPortfolioPositions)
        val allocations = mutableListOf<Double>()
        var remainingBalance = availableBalanceInr

        // Step A: Allocate full-budget trades
        while (allocations.size < candidateLimit && remainingBalance >= userBudgetInr) {
            allocations.add(userBudgetInr)
            remainingBalance -= userBudgetInr
        }

        // Step B: Capital efficiency closure — allocate remainder if >= exchange floor
        if (allocations.size < candidateLimit && remainingBalance >= minExchangeMarginPerTrade) {
            allocations.add(remainingBalance)
            remainingBalance = 0.0
        }

        val nTrades = allocations.size
        val totalAllocated = allocations.sum()

        val funded = mutableListOf<MarketOpportunity>()
        val unfunded = mutableListOf<MarketOpportunity>()
        val allProcessed = mutableListOf<MarketOpportunity>()

        for ((index, opp) in rankedOpportunities.withIndex()) {
            if (index < nTrades) {
                val allocatedAmount = allocations[index]
                val fundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.SELECTED_FOR_TRADE,
                    allocatedMarginInr = allocatedAmount,
                    statusMessage = "Selected for trade (Allocated: ₹%.0f)".format(allocatedAmount)
                )
                funded.add(fundedOpp)
                allProcessed.add(fundedOpp)
            } else {
                val unfundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Ranked #%d (Unfunded - Portfolio/Balance Limit)".format(opp.rank)
                )
                unfunded.add(unfundedOpp)
                allProcessed.add(unfundedOpp)
            }
        }

        return AllocationResult(
            availableBalanceInr = availableBalanceInr,
            minMarginPerTradeInr = minExchangeMarginPerTrade,
            maxTradesAllowed = nTrades,
            allocatedTradesCount = nTrades,
            totalAllocatedInr = totalAllocated,
            remainingBalanceInr = remainingBalance,
            fundedOpportunities = funded,
            unfundedOpportunities = unfunded,
            allRankedOpportunities = allProcessed,
            isInsufficientBalance = false,
            statusMessage = "Allocated ₹%.0f across %d ranked opportunities.".format(totalAllocated, nTrades)
        )
    }
}
