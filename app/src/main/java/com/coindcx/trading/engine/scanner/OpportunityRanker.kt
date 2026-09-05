package com.coindcx.trading.engine.scanner

class OpportunityRanker {

    /**
     * Ranks market opportunities by confidence score from highest to lowest.
     * Extracts the Top 5 opportunities and tags them with ranks #1 to #5.
     */
    fun rankOpportunities(opportunities: List<MarketOpportunity>): List<MarketOpportunity> {
        return opportunities
            .sortedByDescending { it.confidenceScore }
            .take(5)
            .mapIndexed { index, opp ->
                val rankNumber = index + 1
                opp.copy(
                    rank = rankNumber,
                    lifecycleState = OpportunityLifecycle.RANKED,
                    statusMessage = "Rank #$rankNumber: ${opp.actionLabel} setup (Score: %.0f/100)".format(opp.confidenceScore)
                )
            }
    }
}
