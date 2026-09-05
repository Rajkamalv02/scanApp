package com.coindcx.trading.engine.allocation

import com.coindcx.trading.engine.scanner.MarketOpportunity
import com.coindcx.trading.engine.scanner.OpportunityLifecycle
import kotlin.math.floor

class AllocationEngine {

    /**
     * Mathematical Trade Allocation Formula:
     * Max Allowed Trades = floor(Available Balance / Min Margin per Trade)
     * N = min(5, Max Allowed Trades)
     *
     * Allocates min margin to the top N ranked opportunities.
     * Leaves remaining ranked opportunities unfunded.
     */
    fun allocateCapital(
        availableBalanceInr: Double,
        minMarginPerTradeInr: Double,
        rankedOpportunities: List<MarketOpportunity>
    ): AllocationResult {
        if (minMarginPerTradeInr <= 0.0) {
            return AllocationResult(
                availableBalanceInr = availableBalanceInr,
                minMarginPerTradeInr = minMarginPerTradeInr,
                maxTradesAllowed = 0,
                allocatedTradesCount = 0,
                totalAllocatedInr = 0.0,
                remainingBalanceInr = availableBalanceInr,
                fundedOpportunities = emptyList(),
                unfundedOpportunities = rankedOpportunities,
                allRankedOpportunities = rankedOpportunities,
                isInsufficientBalance = true,
                statusMessage = "Invalid minimum margin per trade."
            )
        }

        val rawMaxTrades = floor(availableBalanceInr / minMarginPerTradeInr).toInt()
        val isInsufficient = rawMaxTrades <= 0

        if (isInsufficient) {
            val updatedRanked = rankedOpportunities.map {
                it.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Unfunded: Available ₹%.2f < ₹%.2f required".format(availableBalanceInr, minMarginPerTradeInr)
                )
            }
            return AllocationResult(
                availableBalanceInr = availableBalanceInr,
                minMarginPerTradeInr = minMarginPerTradeInr,
                maxTradesAllowed = 0,
                allocatedTradesCount = 0,
                totalAllocatedInr = 0.0,
                remainingBalanceInr = availableBalanceInr,
                fundedOpportunities = emptyList(),
                unfundedOpportunities = updatedRanked,
                allRankedOpportunities = updatedRanked,
                isInsufficientBalance = true,
                statusMessage = "Insufficient Balance: Available ₹%.2f is less than ₹%.2f required for 1 trade.".format(
                    availableBalanceInr, minMarginPerTradeInr
                )
            )
        }

        // Capped at top 5 as scanner ranks top 5 opportunities
        val nTrades = rawMaxTrades.coerceAtMost(5).coerceAtMost(rankedOpportunities.size)
        val totalAllocated = nTrades * minMarginPerTradeInr
        val remainingBalance = (availableBalanceInr - totalAllocated).coerceAtLeast(0.0)

        val funded = mutableListOf<MarketOpportunity>()
        val unfunded = mutableListOf<MarketOpportunity>()
        val allProcessed = mutableListOf<MarketOpportunity>()

        for ((index, opp) in rankedOpportunities.withIndex()) {
            if (index < nTrades) {
                val fundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.SELECTED_FOR_TRADE,
                    allocatedMarginInr = minMarginPerTradeInr,
                    statusMessage = "Selected for trade (Allocated: ₹%.0f)".format(minMarginPerTradeInr)
                )
                funded.add(fundedOpp)
                allProcessed.add(fundedOpp)
            } else {
                val needed = minMarginPerTradeInr - remainingBalance
                val unfundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Ranked #%d (Unfunded - Need ₹%.0f more balance)".format(opp.rank, needed.coerceAtLeast(minMarginPerTradeInr))
                )
                unfunded.add(unfundedOpp)
                allProcessed.add(unfundedOpp)
            }
        }

        return AllocationResult(
            availableBalanceInr = availableBalanceInr,
            minMarginPerTradeInr = minMarginPerTradeInr,
            maxTradesAllowed = rawMaxTrades,
            allocatedTradesCount = nTrades,
            totalAllocatedInr = totalAllocated,
            remainingBalanceInr = remainingBalance,
            fundedOpportunities = funded,
            unfundedOpportunities = unfunded,
            allRankedOpportunities = allProcessed,
            isInsufficientBalance = false,
            statusMessage = "Allocated ₹%.0f across top %d ranked opportunities.".format(totalAllocated, nTrades)
        )
    }
}
