package com.coindcx.trading.engine.scanner

import com.coindcx.trading.util.AppLogManager

class OpportunityRanker {

    /**
     * Ranks market opportunities globally across all strategies.
     * Evaluates entry status, quality approval, quality score, net R:R, and confidence score.
     * Guarantees distinct symbols (no duplicate instrument slots) and extracts the Top 5.
     */
    fun rankOpportunities(opportunities: List<MarketOpportunity>): List<MarketOpportunity> {
        val ranked = opportunities
            .sortedWith(
                compareByDescending<MarketOpportunity> { it.isEntry }
                    .thenByDescending { it.isApproved }
                    .thenByDescending { it.qualityScore }
                    .thenByDescending { it.netRiskRewardRatio }
                    .thenByDescending { it.confidenceScore }
            )
            .distinctBy { it.pair }
            .take(5)
            .mapIndexed { index, opp ->
                val rankNumber = index + 1
                val stratLabel = opp.strategyId.ifBlank { opp.signal.strategyId }.uppercase()
                opp.copy(
                    rank = rankNumber,
                    lifecycleState = OpportunityLifecycle.RANKED,
                    statusMessage = "Rank #$rankNumber [$stratLabel]: ${opp.actionLabel} [${opp.qualityCategory}] (Score: ${opp.qualityScore}/100)"
                )
            }

        // Structured log of the final ranked Top 5
        if (ranked.isNotEmpty()) {
            val summary = ranked.joinToString("\n") { opp ->
                val strat = (opp.strategyId.ifBlank { opp.signal.strategyId }).uppercase()
                "  #%d [%-12s] %-14s | %-5s | Score: %2d/100 (%s) | Conf: %4.1f%% | Net R:R: %.2f".format(
                    opp.rank, strat, opp.pair, opp.actionLabel, opp.qualityScore, opp.qualityCategory, opp.confidenceScore, opp.netRiskRewardRatio
                )
            }
            AppLogManager.scanner(
                "================ RANKED TOP 5 (ALL STRATEGIES) ================\n$summary\n================================================================"
            )
        }

        return ranked
    }
}
