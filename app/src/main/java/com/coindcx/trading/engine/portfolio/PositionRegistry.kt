package com.coindcx.trading.engine.portfolio

import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalDirection
import com.coindcx.trading.engine.scanner.SignalDedupRegistry
import com.coindcx.trading.engine.scanner.StrategyFamily
import com.coindcx.trading.engine.telemetry.RejectionCode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

sealed interface PortfolioGateResult {
    object Pass : PortfolioGateResult
    data class Rejected(val code: RejectionCode, val reason: String) : PortfolioGateResult
}

data class PortfolioPosition(
    val symbol: String,
    val strategyId: String,
    val family: StrategyFamily,
    val direction: SignalDirection,
    val entryTimeUtc: Long,
    val entryPrice: Double,
    val quantity: Double = 0.0
)

/**
 * Position & Portfolio Exposure Registry (§1.6).
 * Enforces single-position per symbol, global concurrency caps,
 * family concentration limits, and directional imbalance protection.
 */
class PositionRegistry(
    val limits: ExposureLimits = ExposureLimits()
) {
    private val activePositions = ConcurrentHashMap<String, PortfolioPosition>() // symbol -> Position

    fun recordPosition(pos: PortfolioPosition) {
        activePositions[pos.symbol] = pos
    }

    fun removePosition(symbol: String): PortfolioPosition? {
        return activePositions.remove(symbol)
    }

    fun clear() {
        activePositions.clear()
    }

    fun getAllPositions(): List<PortfolioPosition> = activePositions.values.toList()

    fun getPosition(symbol: String): PortfolioPosition? = activePositions[symbol]

    val activeCount: Int get() = activePositions.size

    fun evaluateSignal(signal: Signal, limitsOverride: ExposureLimits? = null): PortfolioGateResult {
        val effLimits = limitsOverride ?: limits

        // 1. Symbol Mutual Exclusion (Max 1 active position per symbol)
        if (activePositions.containsKey(signal.symbol)) {
            return PortfolioGateResult.Rejected(
                RejectionCode.CONFLICT_MUTUAL_EXCLUSION,
                "Symbol ${signal.symbol} already has an active position"
            )
        }

        // 2. Global Position Cap
        if (activePositions.size >= effLimits.maxGlobalPositions) {
            return PortfolioGateResult.Rejected(
                RejectionCode.EXPOSURE_CAP_REACHED,
                "Global position cap reached: ${activePositions.size}/${effLimits.maxGlobalPositions}"
            )
        }

        // 3. Strategy Family Exposure Cap
        val family = SignalDedupRegistry.getFamilyForStrategy(signal.strategyId)
        val familyCount = activePositions.values.count { it.family == family }
        val familyCap = effLimits.getCapForFamily(family)
        if (familyCount >= familyCap) {
            return PortfolioGateResult.Rejected(
                RejectionCode.EXPOSURE_CAP_REACHED,
                "Family $family exposure cap reached: $familyCount/$familyCap"
            )
        }

        // 4. Directional Imbalance Protection
        val currentLongs = activePositions.values.count { it.direction == SignalDirection.LONG }
        val currentShorts = activePositions.values.count { it.direction == SignalDirection.SHORT }
        val newLongs = if (signal.direction == SignalDirection.LONG) currentLongs + 1 else currentLongs
        val newShorts = if (signal.direction == SignalDirection.SHORT) currentShorts + 1 else currentShorts
        val imbalance = abs(newLongs - newShorts)

        if (imbalance > effLimits.maxDirectionalImbalance) {
            return PortfolioGateResult.Rejected(
                RejectionCode.EXPOSURE_CAP_REACHED,
                "Directional imbalance cap reached: Net $imbalance exceeds max ${effLimits.maxDirectionalImbalance}"
            )
        }

        return PortfolioGateResult.Pass
    }
}
