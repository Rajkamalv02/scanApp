package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.FuturesPosition

data class RiskSettings(
    val maxPositionSizeUsdt: Double = 50.0,
    val maxLeverage: Int = 5,
    val maxDailyLossUsdt: Double = 20.0,
    val maxConcurrentPositions: Int = 1,
    val maxConsecutiveLosses: Int = 3
)

sealed class RiskCheckResult {
    data class Approved(val adjustedQuantity: Double, val adjustedLeverage: Int) : RiskCheckResult()
    data class Rejected(val reason: String) : RiskCheckResult()
}

/**
 * Risk Manager
 * First line of defense — verifies all hard limits before allowing an order to reach the exchange.
 */
class RiskManager(
    var settings: RiskSettings = RiskSettings()
) {
    private var todayRealizedLossUsdt: Double = 0.0
    private var consecutiveLossCount: Int = 0
    private var circuitBreakerTripped: Boolean = false

    fun checkSignal(
        signal: Signal,
        currentPrice: Double,
        activePositions: List<FuturesPosition>
    ): RiskCheckResult {
        if (signal.action == SignalAction.HOLD) {
            return RiskCheckResult.Rejected("Signal is HOLD")
        }

        if (circuitBreakerTripped) {
            return RiskCheckResult.Rejected("Daily circuit breaker tripped. Trading disabled for today.")
        }

        if (consecutiveLossCount >= settings.maxConsecutiveLosses) {
            return RiskCheckResult.Rejected("Cooldown active: $consecutiveLossCount consecutive losses.")
        }

        if (signal.action == SignalAction.EXIT) {
            return RiskCheckResult.Approved(signal.suggestedQuantity, signal.suggestedLeverage)
        }

        // Open position limits
        val openPositions = activePositions.filter { it.isOpen }
        if (openPositions.size >= settings.maxConcurrentPositions) {
            return RiskCheckResult.Rejected("Max concurrent positions reached (${openPositions.size}/${settings.maxConcurrentPositions})")
        }

        // Leverage cap
        val leverage = signal.suggestedLeverage.coerceAtMost(settings.maxLeverage)

        // Position size calculation
        val notionalValue = signal.suggestedQuantity * currentPrice
        if (notionalValue > settings.maxPositionSizeUsdt) {
            val adjustedQuantity = settings.maxPositionSizeUsdt / currentPrice
            return RiskCheckResult.Approved(adjustedQuantity, leverage)
        }

        // Sanity checks on Stop Loss
        if (signal.action == SignalAction.ENTER_LONG && signal.stopLossPrice != null) {
            if (signal.stopLossPrice >= currentPrice) {
                return RiskCheckResult.Rejected("Invalid Long SL: SL (${signal.stopLossPrice}) must be < Entry ($currentPrice)")
            }
        }
        if (signal.action == SignalAction.ENTER_SHORT && signal.stopLossPrice != null) {
            if (signal.stopLossPrice <= currentPrice) {
                return RiskCheckResult.Rejected("Invalid Short SL: SL (${signal.stopLossPrice}) must be > Entry ($currentPrice)")
            }
        }

        return RiskCheckResult.Approved(signal.suggestedQuantity, leverage)
    }

    fun recordTradeResult(realizedPnl: Double) {
        if (realizedPnl < 0) {
            todayRealizedLossUsdt += kotlin.math.abs(realizedPnl)
            consecutiveLossCount++
            if (todayRealizedLossUsdt >= settings.maxDailyLossUsdt) {
                circuitBreakerTripped = true
            }
        } else {
            consecutiveLossCount = 0
        }
    }

    fun resetDaily() {
        todayRealizedLossUsdt = 0.0
        circuitBreakerTripped = false
    }
}
