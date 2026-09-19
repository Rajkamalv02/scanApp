package com.coindcx.trading.engine

import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.telemetry.RejectionCode

/**
 * Result of evaluating a cross-sectional universe strategy.
 */
data class UniverseStrategyResult(
    val signals: List<Signal>,
    val newState: StrategyState? = null,
    val symbolRejections: Map<String, List<RejectionCode>> = emptyMap()
)

/**
 * Interface for strategies that evaluate cross-sectional market universes simultaneously
 * (e.g. Strategy 5: Cross-Sectional Relative Strength vs BTC).
 */
interface UniverseStrategy {
    val id: String
    val name: String
    val description: String
    val parametersSummary: String
    val requiredCandleCount: Int
    val primaryInterval: Interval get() = Interval.H4
    val requiredIntervals: Set<Interval> get() = setOf(primaryInterval)
    val preferredRegime: MarketRegimePreference get() = MarketRegimePreference.ANY

    /**
     * Pure, deterministic cross-sectional evaluation method.
     *
     * @param universe Map of symbol -> SymbolContext for all tradeable candidates
     * @param btcContext Benchmark SymbolContext for Bitcoin (BTC/USDT)
     * @param state Optional persisted strategy state (e.g. CrossSectionalState)
     * @return UniverseStrategyResult containing active entry signals, updated state, and rejection codes
     */
    fun evaluate(
        universe: Map<String, SymbolContext>,
        btcContext: SymbolContext,
        state: StrategyState? = null
    ): UniverseStrategyResult
}
