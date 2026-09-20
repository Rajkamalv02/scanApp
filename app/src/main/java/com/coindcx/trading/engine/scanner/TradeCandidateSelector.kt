package com.coindcx.trading.engine.scanner

import com.coindcx.trading.util.AppLogManager
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Result of Trade Candidate Selection.
 */
data class CandidateSelectionResult(
    val capacityK: Int,
    val capacitySlots: Int,
    val capacityMargin: Int,
    val capacityRisk: Int,
    val approvedTrades: List<MarketOpportunity>,
    val deferredTrades: List<MarketOpportunity>,
    val allEvaluatedCount: Int,
    val statusMessage: String
)

/**
 * Dynamic Trade Candidate Selector (§F, §E).
 * Replaces fixed Top-5 ranking with dynamic capacity K sizing.
 * No artificial padding: comfortably outputs 0, 1, 2, ... K trades.
 */
class TradeCandidateSelector {

    companion object {
        /**
         * ConsensusBonus bounded [0.0, 100.0] scaling with agreeing strategies n (§E.4).
         */
        fun calculateConsensusBonus(consensusCount: Int): Double {
            return if (consensusCount <= 1) 0.0 else min(100.0, (consensusCount - 1) * 35.0)
        }

        /**
         * Multi-factor Execution Priority Score (§E.4).
         */
        fun calculatePriorityScore(
            confidence: Double,
            netRiskReward: Double,
            consensusCount: Int,
            spreadBps: Double = 2.0
        ): Double {
            val cTerm = confidence.coerceIn(0.0, 100.0) * 0.40
            val rrTerm = (netRiskReward * 20.0).coerceIn(0.0, 100.0) * 0.30
            val consensusTerm = calculateConsensusBonus(consensusCount) * 0.20
            val spreadTerm = (100.0 - spreadBps.coerceIn(0.0, 100.0)) * 0.10
            return cTerm + rrTerm + consensusTerm + spreadTerm
        }
    }

    /**
     * Derives dual trade capacity K and selects top K actionable candidates.
     */
    fun selectCandidates(
        candidates: List<MarketOpportunity>,
        accountEquityInr: Double,
        availableCashInr: Double,
        activePositionsCount: Int,
        maxConcurrentPositions: Int,
        leverage: Int,
        minExchangeNotionalInr: Double,
        riskPerTradePercent: Double = 1.0,
        maxPortfolioRiskPercent: Double = 4.0,
        safetyReservePercent: Double = 0.0
    ): CandidateSelectionResult {
        // 1. Calculate Capacities (§E)
        val kSlots = max(0, maxConcurrentPositions - activePositionsCount)

        val availableTradingCash = max(0.0, availableCashInr)
        val effectiveLev = leverage.coerceAtLeast(1)
        val minMarginRequired = max(1.0, minExchangeNotionalInr / effectiveLev)
        val kMargin = floor(availableTradingCash / minMarginRequired).toInt()

        val maxPortfolioRisk = accountEquityInr * (maxPortfolioRiskPercent / 100.0)
        val targetRiskPerTrade = max(1.0, accountEquityInr * (riskPerTradePercent / 100.0))
        val currentPortfolioRisk = activePositionsCount * targetRiskPerTrade
        val remainingRiskBudget = max(0.0, maxPortfolioRisk - currentPortfolioRisk)
        val kRisk = floor(remainingRiskBudget / targetRiskPerTrade).toInt()

        val capacityK = min(kSlots, min(kMargin, kRisk))

        // 2. Filter strictly actionable entries
        val actionableCandidates = candidates.filter { it.isEntry && it.isApproved }

        if (actionableCandidates.isEmpty()) {
            val msg = "Market Scan Complete: 0 valid actionable setups identified across universe. Capital safely preserved."
            AppLogManager.scanner("================ TRADE CANDIDATE SELECTION ================\n$msg\nCapacity K=$capacityK (Slots: $kSlots, Margin: $kMargin, Risk: $kRisk)\n===========================================================")
            return CandidateSelectionResult(
                capacityK = capacityK,
                capacitySlots = kSlots,
                capacityMargin = kMargin,
                capacityRisk = kRisk,
                approvedTrades = emptyList(),
                deferredTrades = emptyList(),
                allEvaluatedCount = candidates.size,
                statusMessage = msg
            )
        }

        // 3. Score and Prioritize Candidates (§E.4)
        val scoredCandidates = actionableCandidates.map { opp ->
            val priorityScore = calculatePriorityScore(
                confidence = opp.confidenceScore,
                netRiskReward = opp.netRiskRewardRatio,
                consensusCount = opp.contributingStrategies.size.coerceAtLeast(1)
            )
            opp to priorityScore
        }.sortedByDescending { it.second }

        val approved = mutableListOf<MarketOpportunity>()
        val deferred = mutableListOf<MarketOpportunity>()

        scoredCandidates.forEachIndexed { index, (opp, score) ->
            val rankNumber = index + 1
            if (approved.size < capacityK) {
                approved.add(
                    opp.copy(
                        rank = rankNumber,
                        lifecycleState = OpportunityLifecycle.RANKED,
                        statusMessage = "Approved for execution (Priority Score: ${"%.1f".format(score)}, Conf: ${"%.1f".format(opp.confidenceScore)}%, Net R:R: ${"%.2f".format(opp.netRiskRewardRatio)})"
                    )
                )
            } else {
                deferred.add(
                    opp.copy(
                        rank = rankNumber,
                        lifecycleState = OpportunityLifecycle.UNFUNDED,
                        statusMessage = "Deferred: Exceeds dynamic capacity K=$capacityK (Ranked #$rankNumber, Priority Score: ${"%.1f".format(score)})"
                    )
                )
            }
        }

        val logSummary = buildString {
            appendLine("================ TRADE CANDIDATE SELECTION (DYNAMIC CAPACITY K=$capacityK) ================")
            appendLine("Account Constraints: Slots: $kSlots | Margin Cap: $kMargin trades (Available: ₹${"%.2f".format(availableTradingCash)}) | Risk Cap: $kRisk trades")
            appendLine("Actionable Setups: ${actionableCandidates.size} | Approved: ${approved.size} | Deferred: ${deferred.size}")
            if (approved.isNotEmpty()) {
                appendLine("--- APPROVED TRADES ---")
                approved.forEach { app ->
                    val strat = app.strategyName.ifBlank { app.strategyId.uppercase() }
                    appendLine("  #${app.rank} [${app.pair}] ${app.actionLabel} | $strat | Conf: ${"%.1f".format(app.confidenceScore)}% | Net R:R: ${"%.2f".format(app.netRiskRewardRatio)} | Agreeing: ${app.contributingStrategies.size}")
                    appendLine("     Reason: ${app.selectionReason}")
                }
            }
            if (deferred.isNotEmpty()) {
                appendLine("--- DEFERRED TRADES (CAPACITY EXCEEDED) ---")
                deferred.forEach { def ->
                    appendLine("  #${def.rank} [${def.pair}] ${def.actionLabel} | Conf: ${"%.1f".format(def.confidenceScore)}% -> ${def.statusMessage}")
                }
            }
            appendLine("===========================================================================================")
        }
        AppLogManager.scanner(logSummary)

        return CandidateSelectionResult(
            capacityK = capacityK,
            capacitySlots = kSlots,
            capacityMargin = kMargin,
            capacityRisk = kRisk,
            approvedTrades = approved,
            deferredTrades = deferred,
            allEvaluatedCount = candidates.size,
            statusMessage = "Selected ${approved.size} approved trades under capacity K=$capacityK (${deferred.size} deferred)."
        )
    }
}
