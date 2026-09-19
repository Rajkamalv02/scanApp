package com.coindcx.trading.engine.scanner

import com.coindcx.trading.util.AppLogManager

class OpportunityRanker {

    /**
     * Ranks market opportunities globally across all strategies.
     * Evaluates entry status, quality approval, quality score, net R:R, and confidence score.
     * Guarantees distinct symbols (no duplicate instrument slots) and extracts the Top 5.
     */
    fun rankOpportunities(opportunities: List<MarketOpportunity>): List<MarketOpportunity> {
        val candidates = opportunities
            .sortedWith(
                compareByDescending<MarketOpportunity> { it.isEntry }
                    .thenByDescending { it.isApproved }
                    .thenByDescending { it.qualityScore }
                    .thenByDescending { it.marketActivityScore } // Higher MAS market prioritized on tie
                    .thenByDescending { it.netRiskRewardRatio }
                    .thenByDescending { it.confidenceScore }
            )
            .distinctBy { it.pair }

        // Diversified Top 5 Selection: Max 2 slots per StrategyFamily to guarantee regime diversity
        val selected = mutableListOf<MarketOpportunity>()
        val familyCounts = mutableMapOf<StrategyFamily, Int>()
        val maxPerFamily = 2

        // First pass: select top opportunities respecting the family cap
        for (opp in candidates) {
            if (selected.size >= 5) break
            val stratId = opp.strategyId.ifBlank { opp.signal.strategyId }
            val family = SignalDedupRegistry.getFamilyForStrategy(stratId)
            val currentCount = familyCounts.getOrDefault(family, 0)
            if (currentCount < maxPerFamily) {
                selected.add(opp)
                familyCounts[family] = currentCount + 1
            }
        }

        // Second pass: if slots remain (< 5), fill with remaining candidates regardless of family
        if (selected.size < 5) {
            for (opp in candidates) {
                if (selected.size >= 5) break
                if (opp !in selected) {
                    selected.add(opp)
                }
            }
        }

        val ranked = selected.mapIndexed { index, opp ->
            val rankNumber = index + 1
            val stratLabel = opp.strategyId.ifBlank { opp.signal.strategyId }.uppercase()
            val derivedReason = if (opp.selectionReason.isNotBlank()) {
                opp.selectionReason
            } else if (opp.isEntry) {
                "Rank #$rankNumber: Actionable ${opp.actionLabel} entry qualified by ${opp.strategyName.ifBlank { stratLabel }} (TQS: ${opp.qualityScore}/100, Net R:R: ${"%.2f".format(opp.netRiskRewardRatio)}, MAS: ${"%.1f".format(opp.marketActivityScore)})"
            } else {
                "Rank #$rankNumber: Market watch snapshot (MAS: ${"%.1f".format(opp.marketActivityScore)}, Quality: ${opp.qualityScore}/100)"
            }

            opp.copy(
                rank = rankNumber,
                lifecycleState = OpportunityLifecycle.RANKED,
                selectionReason = derivedReason,
                statusMessage = "Rank #$rankNumber [$stratLabel]: ${opp.actionLabel} [${opp.qualityCategory}] (TQS: ${opp.qualityScore}/100, MAS: ${"%.0f".format(opp.marketActivityScore)})"
            )
        }

        // Structured log of the final ranked Top 5 with comprehensive strategy attribution (§6)
        if (ranked.isNotEmpty()) {
            val summary = ranked.joinToString("\n") { opp ->
                val strat = (opp.strategyId.ifBlank { opp.signal.strategyId }).uppercase()
                val header = "  #%d [%-12s] %-14s | %-5s | Score: %2d/100 (%s) | MAS: %4.1f | Conf: %4.1f%% | Net R:R: %.2f".format(
                    opp.rank, strat, opp.pair, opp.actionLabel, opp.qualityScore, opp.qualityCategory, opp.marketActivityScore, opp.confidenceScore, opp.netRiskRewardRatio
                )
                val contributions = if (opp.contributingStrategies.isNotEmpty()) {
                    opp.contributingStrategies.joinToString("\n") { c ->
                        "     -> Strategy: ${c.strategyName} (${c.strategyId.uppercase()}) | Direction: ${c.action} | Score: ${c.qualityScore}/100 | Conf: ${"%.1f".format(c.confidenceScore)}% | Reason: ${c.reason}"
                    }
                } else {
                    "     -> Strategy: ${opp.strategyName.ifBlank { strat }} | Direction: ${opp.actionLabel} | Reason: ${opp.signal.reason}"
                }
                val reasonLine = "     Selection: ${opp.selectionReason}"
                "$header\n$contributions\n$reasonLine"
            }
            AppLogManager.scanner(
                "================ RANKED TOP 5 (STRATEGY ATTRIBUTION) ================\n$summary\n======================================================================"
            )
        }

        return ranked
    }
}
