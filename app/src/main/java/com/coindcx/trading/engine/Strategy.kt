package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle

enum class SignalAction {
    ENTER_LONG,
    ENTER_SHORT,
    EXIT,
    HOLD
}

data class StrategyDiagnostics(
    val stage: String,
    val failedFilter: String? = null,
    val indicators: Map<String, Double> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val mathDetails: Map<String, String> = emptyMap()
)

data class Signal(
    val action: SignalAction,
    val suggestedQuantity: Double = 0.0,
    val suggestedLeverage: Int = 1,
    val stopLossPrice: Double? = null,
    val takeProfitPrice: Double? = null,
    val reason: String = "",
    val confidenceScore: Double = 0.0, // 0.0 to 100.0 for ranking
    val diagnostics: StrategyDiagnostics? = null,
    val tradeId: String? = null,
    val entryPrice: Double = 0.0,
    val fastEma: Double = 0.0,
    val slowEma: Double = 0.0,
    val prevFastEma: Double = 0.0,
    val prevSlowEma: Double = 0.0,
    val atr: Double = 0.0,
    val atrMultiplier: Double = 0.0,
    val riskDistance: Double = 0.0,
    val riskRewardRatio: Double = 0.0,
    val strategyId: String = "",
    val strategyName: String = ""
)

enum class MarketRegimePreference {
    ANY,
    TRENDING_MOMENTUM,
    MEAN_REVERTING_RANGE
}

/**
 * Pure, deterministic strategy interface.
 * Has zero dependency on network, database, or UI.
 * Signal = f(candles, activePosition)
 */
interface Strategy {
    val id: String
    val name: String
    val description: String
    val parametersSummary: String
    val requiredCandleCount: Int
    val defaultTimeframe: String // e.g. "5m"
    val preferredRegime: MarketRegimePreference get() = MarketRegimePreference.ANY

    fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?, pair: String = ""): Signal
}
