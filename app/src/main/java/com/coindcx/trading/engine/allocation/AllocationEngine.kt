package com.coindcx.trading.engine.allocation

import com.coindcx.trading.engine.MaintenanceMarginSchedule
import com.coindcx.trading.engine.RiskSettings
import com.coindcx.trading.engine.scanner.MarketOpportunity
import com.coindcx.trading.engine.scanner.OpportunityLifecycle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class AllocationEngine {

    /**
     * Institutional Dynamic Capital & Risk-Parity Allocator:
     *
     * 1. Solvency & Reserve: Protects dedicated safety reserve (5% of equity, min ₹100)
     *    and derives unencumbered available trading capital and available concurrent slots.
     * 2. Risk-Parity Notional: Computes target dollar risk (1.0% equity) divided by
     *    strategy-specific stop-loss distance, bounded by single-trade exposure cap (30%).
     * 3. Two-Tier Floors: Enforces physical exchange floor (6.0 USDT, ~₹620 / leverage)
     *    and portfolio economic viability floor (2% of equity).
     * 4. Constrained Sequential Slicing: Prevents Rank #1 from monopolizing available cash
     *    (capped at 45% of start capital) while allowing downstream candidates to absorb remainder.
     * 5. Gated Decision Tree: Evaluates solvency, 1.5% max risk cap, and liquidation safety buffer
     *    before bumping to floor. Never forces a trade into excess risk.
     */
    fun allocateCapital(
        accountEquityInr: Double,
        availableCashInr: Double,
        activePositionsCount: Int = 0,
        leverage: Int = 2,
        rankedOpportunities: List<MarketOpportunity>,
        riskSettings: RiskSettings = RiskSettings(),
        minExchangeNotionalInr: Double = 620.0,
        riskPerTradePercent: Double = 1.0,
        safetyReservePercent: Double = 5.0,
        maxSingleExposurePercent: Double = 30.0,
        minTradeWeightPercent: Double = 2.0,
        maxFloorRiskPercent: Double = riskSettings.maxFloorRiskPercent
    ): AllocationResult {
        val effectiveLev = leverage.coerceIn(1, riskSettings.maxLeverage)
        val equity = accountEquityInr.coerceAtLeast(availableCashInr).coerceAtLeast(0.0)

        // Stage 1: Solvency & Slot Allocation
        val safetyReserveInr = (equity * (safetyReservePercent / 100.0)).coerceAtLeast(100.0)
        val availableCashForTrading = (availableCashInr - safetyReserveInr).coerceAtLeast(0.0)
        val availableSlots = (riskSettings.maxConcurrentPositions - activePositionsCount).coerceAtLeast(0)

        // Stage 2: Boundaries & Floors
        val minExchangeMarginFloor = ceil(minExchangeNotionalInr / effectiveLev)
        val economicNotionalFloor = equity * (minTradeWeightPercent / 100.0)
        val economicMarginFloor = economicNotionalFloor / effectiveLev
        val governingFloorMargin = minExchangeMarginFloor.coerceAtLeast(economicMarginFloor)

        val targetRiskPerTradeInr = equity * (riskPerTradePercent / 100.0)
        val benchmarkSlPct = 0.02 // 2% benchmark for UI display
        val recommendedMargin = ((targetRiskPerTradeInr / benchmarkSlPct) / effectiveLev)
            .coerceAtLeast(minExchangeMarginFloor)

        // Capacity check
        val maxSlotsCapital = if (minExchangeMarginFloor > 0) floor(availableCashForTrading / minExchangeMarginFloor).toInt() else 0
        val dynamicMaxTrades = min(availableSlots, maxSlotsCapital)

        // Early Exit: No slots, no capital for 1 exchange order, or no candidates
        if (dynamicMaxTrades <= 0 || availableCashForTrading < minExchangeMarginFloor || rankedOpportunities.isEmpty()) {
            val failureReason = when {
                availableSlots <= 0 -> "Portfolio Limit Reached: All %d concurrent positions active."
                    .format(riskSettings.maxConcurrentPositions)
                availableCashForTrading < minExchangeMarginFloor -> "Insufficient Balance: Available trading cash (₹%.2f) < ₹%.2f min exchange requirement at %dx leverage (Reserve protected: ₹%.2f)."
                    .format(availableCashForTrading, minExchangeMarginFloor, effectiveLev, safetyReserveInr)
                else -> "No ranked candidate opportunities available."
            }

            val updatedRanked = rankedOpportunities.map {
                it.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = failureReason
                )
            }

            return AllocationResult(
                availableBalanceInr = availableCashInr,
                minMarginPerTradeInr = minExchangeMarginFloor,
                maxTradesAllowed = 0,
                allocatedTradesCount = 0,
                totalAllocatedInr = 0.0,
                remainingBalanceInr = availableCashInr,
                fundedOpportunities = emptyList(),
                unfundedOpportunities = updatedRanked,
                allRankedOpportunities = updatedRanked,
                isInsufficientBalance = availableCashForTrading < minExchangeMarginFloor,
                statusMessage = failureReason,
                accountEquityInr = equity,
                safetyReserveInr = safetyReserveInr,
                exchangeFloorMarginInr = minExchangeMarginFloor,
                economicFloorMarginInr = economicMarginFloor,
                targetRiskPerTradeInr = targetRiskPerTradeInr,
                recommendedMarginPerTradeInr = recommendedMargin
            )
        }

        // Stage 3 & 4: Constrained Sequential Slot Slicing & Gated Decision Tree
        var remainingCash = availableCashForTrading
        var remainingSlots = dynamicMaxTrades
        val maxSingleTradeCap = max(minExchangeMarginFloor, availableCashForTrading * 0.45) // No single trade can take > 45% of start cash (floor-guaranteed)

        val funded = mutableListOf<MarketOpportunity>()
        val unfunded = mutableListOf<MarketOpportunity>()
        val allProcessed = mutableListOf<MarketOpportunity>()

        val maxTolerableRiskInr = equity * (maxFloorRiskPercent / 100.0) // Tunable max risk cap on floor bump (default 2.5% for micro-accounts)

        for (opp in rankedOpportunities) {
            // Signal Action Check
            if (!opp.isBuy && !opp.isSell) {
                val watchingOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Watching: ${opp.signal.reason} [Score: ${opp.qualityScore}]"
                )
                unfunded.add(watchingOpp)
                allProcessed.add(watchingOpp)
                continue
            }


            // Slot & Solvency Availability Check
            if (remainingSlots <= 0 || remainingCash < minExchangeMarginFloor) {
                val unfundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Unfunded: Portfolio/Capital capacity exhausted (Remaining: ₹%.2f)"
                        .format(remainingCash)
                )
                unfunded.add(unfundedOpp)
                allProcessed.add(unfundedOpp)
                continue
            }

            // Calculate Strategy-Specific Stop Loss Distance
            val hasExplicitSl = (opp.signal.stopLossPrice ?: 0.0) > 0.0
            val slPrice = if (hasExplicitSl) opp.signal.stopLossPrice!! else (if (opp.isBuy) opp.currentPrice * 0.98 else opp.currentPrice * 1.02)
            val slDistance = abs(opp.currentPrice - slPrice)
            val slDistPercent = if (opp.currentPrice > 0.0) (slDistance / opp.currentPrice).coerceAtLeast(0.0001) else 0.02

            // Risk-Parity Target Notional
            val idealNotional = targetRiskPerTradeInr / slDistPercent
            val maxSingleExposureNotional = equity * (maxSingleExposurePercent / 100.0) * effectiveLev
            val targetNotional = idealNotional.coerceAtMost(maxSingleExposureNotional)
            val idealMargin = targetNotional / effectiveLev

            // Sequential Slot Ceiling for this candidate
            val slotCeilingMargin = max(
                minExchangeMarginFloor,
                min(
                    maxSingleTradeCap,
                    min((remainingCash / remainingSlots) * 1.25, remainingCash)
                )
            )

            // Gated Decision Tree
            var isApprovedForFunding = false
            var allocatedMargin = 0.0
            var rejectionReason = ""

            if (idealMargin < governingFloorMargin) {
                // Position is small: evaluate bump gates
                val floorRiskInr = governingFloorMargin * effectiveLev * slDistPercent
                val liqDist = MaintenanceMarginSchedule.getEstimatedLiquidationDistancePct(effectiveLev)
                val isLiqSafe = liqDist >= (slDistPercent * riskSettings.liquidationBufferMultiplier)

                when {
                    governingFloorMargin > slotCeilingMargin -> {
                        rejectionReason = "Floor margin (₹%.0f) exceeds slot budget ceiling (₹%.0f)"
                            .format(governingFloorMargin, slotCeilingMargin)
                    }
                    floorRiskInr > maxTolerableRiskInr -> {
                        rejectionReason = "Floor order forces ₹%.2f risk (%.2f%% of equity), exceeding %.1f%% cap"
                            .format(floorRiskInr, (floorRiskInr / equity) * 100.0, maxFloorRiskPercent)
                    }
                    !isLiqSafe -> {
                        rejectionReason = "Liquidation distance (%.2f%%) at %dx leverage is within safety buffer (SL: %.2f%%)"
                            .format(liqDist * 100.0, effectiveLev, slDistPercent * 100.0)
                    }
                    else -> {
                        // Passed all 3 bump gates
                        isApprovedForFunding = true
                        allocatedMargin = governingFloorMargin
                    }
                }
            } else {
                // Position is above floor: clamp to slot ceiling
                val clampedMargin = min(idealMargin, slotCeilingMargin)
                if (clampedMargin < minExchangeMarginFloor) {
                    rejectionReason = "Slot budget (₹%.0f) below exchange minimum floor (₹%.0f)"
                        .format(clampedMargin, minExchangeMarginFloor)
                } else {
                    isApprovedForFunding = true
                    allocatedMargin = clampedMargin
                }
            }

            if (isApprovedForFunding && allocatedMargin >= minExchangeMarginFloor) {
                val fundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.SELECTED_FOR_TRADE,
                    allocatedMarginInr = allocatedMargin,
                    statusMessage = "Funded: ₹%.0f allocated (Risk: ₹%.2f, SL: %.2f%%)"
                        .format(allocatedMargin, allocatedMargin * effectiveLev * slDistPercent, slDistPercent * 100.0)
                )
                funded.add(fundedOpp)
                allProcessed.add(fundedOpp)
                remainingCash = (remainingCash - allocatedMargin).coerceAtLeast(0.0)
                remainingSlots--
            } else {
                val unfundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Unfunded: $rejectionReason"
                )
                unfunded.add(unfundedOpp)
                allProcessed.add(unfundedOpp)
            }
        }

        val totalAllocated = funded.sumOf { it.allocatedMarginInr }
        val nTrades = funded.size

        return AllocationResult(
            availableBalanceInr = availableCashInr,
            minMarginPerTradeInr = minExchangeMarginFloor,
            maxTradesAllowed = dynamicMaxTrades,
            allocatedTradesCount = nTrades,
            totalAllocatedInr = totalAllocated,
            remainingBalanceInr = remainingCash + safetyReserveInr,
            fundedOpportunities = funded,
            unfundedOpportunities = unfunded,
            allRankedOpportunities = allProcessed,
            isInsufficientBalance = false,
            statusMessage = "Allocated ₹%.0f across %d trades (Reserve protected: ₹%.0f)."
                .format(totalAllocated, nTrades, safetyReserveInr),
            accountEquityInr = equity,
            safetyReserveInr = safetyReserveInr,
            exchangeFloorMarginInr = minExchangeMarginFloor,
            economicFloorMarginInr = economicMarginFloor,
            targetRiskPerTradeInr = targetRiskPerTradeInr,
            recommendedMarginPerTradeInr = recommendedMargin
        )
    }

    /**
     * Backward-compatible legacy signature for existing test suites.
     */
    @Deprecated("Use full risk-parity allocateCapital signature")
    fun allocateCapital(
        availableBalanceInr: Double,
        userBudgetInr: Double,
        leverage: Int = 2,
        rankedOpportunities: List<MarketOpportunity>,
        riskSettings: RiskSettings = RiskSettings(),
        minExchangeNotionalInr: Double = 620.0
    ): AllocationResult {
        val effectiveLeverage = leverage.coerceIn(1, riskSettings.maxLeverage)
        val minExchangeMarginPerTrade = ceil(minExchangeNotionalInr / effectiveLeverage)

        if (availableBalanceInr < minExchangeMarginPerTrade) {
            val unfundedAll = rankedOpportunities.map { opp ->
                opp.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Ranked #${opp.rank} (Unfunded - Balance ₹%.2f below ₹%.0f minimum margin)"
                        .format(availableBalanceInr, minExchangeMarginPerTrade)
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
                unfundedOpportunities = unfundedAll,
                allRankedOpportunities = unfundedAll,
                isInsufficientBalance = true,
                statusMessage = "Insufficient balance (₹%.2f). Minimum margin required is ₹%.0f."
                    .format(availableBalanceInr, minExchangeMarginPerTrade)
            )
        }

        val dynamicMaxTrades = min(
            (availableBalanceInr / minExchangeMarginPerTrade).toInt(),
            min(riskSettings.maxConcurrentPositions, rankedOpportunities.size)
        )

        var currentRemainingBalance = availableBalanceInr
        val funded = mutableListOf<MarketOpportunity>()
        val unfunded = mutableListOf<MarketOpportunity>()
        val allProcessed = mutableListOf<MarketOpportunity>()
        var allocatedCount = 0

        for (opp in rankedOpportunities) {
            if (allocatedCount >= dynamicMaxTrades || currentRemainingBalance < minExchangeMarginPerTrade) {
                val unfundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Ranked #${opp.rank} (Unfunded - Portfolio/Balance Limit)"
                )
                unfunded.add(unfundedOpp)
                allProcessed.add(unfundedOpp)
                continue
            }

            val allocatedAmount = min(userBudgetInr, currentRemainingBalance)
            if (allocatedAmount >= minExchangeMarginPerTrade) {
                val fundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.SELECTED_FOR_TRADE,
                    allocatedMarginInr = allocatedAmount,
                    statusMessage = "Selected for trade (Allocated: ₹%.0f)".format(allocatedAmount)
                )
                funded.add(fundedOpp)
                allProcessed.add(fundedOpp)
                currentRemainingBalance -= allocatedAmount
                allocatedCount++
            } else {
                val unfundedOpp = opp.copy(
                    lifecycleState = OpportunityLifecycle.UNFUNDED,
                    allocatedMarginInr = 0.0,
                    statusMessage = "Ranked #${opp.rank} (Unfunded - Portfolio/Balance Limit)"
                )
                unfunded.add(unfundedOpp)
                allProcessed.add(unfundedOpp)
            }
        }

        val totalAllocated = funded.sumOf { it.allocatedMarginInr }
        val nTrades = funded.size

        return AllocationResult(
            availableBalanceInr = availableBalanceInr,
            minMarginPerTradeInr = minExchangeMarginPerTrade,
            maxTradesAllowed = nTrades,
            allocatedTradesCount = nTrades,
            totalAllocatedInr = totalAllocated,
            remainingBalanceInr = currentRemainingBalance,
            fundedOpportunities = funded,
            unfundedOpportunities = unfunded,
            allRankedOpportunities = allProcessed,
            isInsufficientBalance = false,
            statusMessage = "Allocated ₹%.0f across %d ranked opportunities.".format(totalAllocated, nTrades)
        )
    }
}
