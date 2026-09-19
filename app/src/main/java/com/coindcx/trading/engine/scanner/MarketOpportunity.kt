package com.coindcx.trading.engine.scanner

import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction

enum class OpportunityLifecycle {
    SCANNED,
    RANKED,
    SELECTED_FOR_TRADE,
    TRADE_PLACED,
    ACTIVE_POSITION,
    CLOSED,
    UNFUNDED
}

enum class QualityCategory {
    PRIME,
    ACCEPTABLE,
    WATCH,
    REJECT
}

enum class HtfAlignment {
    ALIGNED_BULLISH,
    ALIGNED_BEARISH,
    NEUTRAL,
    CONFLICTING
}

data class StrategyContribution(
    val strategyId: String,
    val strategyName: String,
    val action: SignalAction,
    val qualityScore: Int,
    val confidenceScore: Double,
    val netRiskRewardRatio: Double = 0.0,
    val reason: String = ""
) {
    val direction: String get() = when (action) {
        SignalAction.ENTER_LONG -> "LONG"
        SignalAction.ENTER_SHORT -> "SHORT"
        SignalAction.EXIT -> "EXIT"
        SignalAction.HOLD -> "HOLD"
    }
    val score: Int get() = qualityScore
    val confidence: Double get() = confidenceScore
}

data class MarketOpportunity(
    val pair: String,
    val signal: Signal,
    val currentPrice: Double,
    val confidenceScore: Double,
    val rank: Int = 0, // 1 to 5
    val lifecycleState: OpportunityLifecycle = OpportunityLifecycle.SCANNED,
    val allocatedMarginInr: Double = 0.0,
    val estimatedQuantity: Double = 0.0,
    val statusMessage: String = "",
    val qualityScore: Int = 0,
    val qualityCategory: QualityCategory = QualityCategory.REJECT,
    val netRiskRewardRatio: Double = 0.0,
    val adxValue: Double = 0.0,
    val rejectionReason: String? = null,
    val isApproved: Boolean = false,
    val htfAlignment: HtfAlignment = HtfAlignment.NEUTRAL,
    val strategyId: String = "",
    val strategyName: String = "",
    val marketActivityScore: Double = 0.0,
    val contributingStrategies: List<StrategyContribution> = emptyList(),
    val selectionReason: String = ""
) {
    val isBuy: Boolean get() = signal.action == SignalAction.ENTER_LONG
    val isSell: Boolean get() = signal.action == SignalAction.ENTER_SHORT
    val isEntry: Boolean get() = isBuy || isSell
    val actionLabel: String get() = when (signal.action) {
        SignalAction.ENTER_LONG -> "LONG"
        SignalAction.ENTER_SHORT -> "SHORT"
        SignalAction.EXIT -> "EXIT"
        SignalAction.HOLD -> "WATCH"
    }
    val assetSymbol: String get() = pair.removePrefix("B-").removeSuffix("_USDT")
}
