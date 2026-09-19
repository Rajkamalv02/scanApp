package com.coindcx.trading.engine.portfolio

import com.coindcx.trading.engine.scanner.StrategyFamily

/**
 * Institutional portfolio exposure constraints (§1.6).
 * Restricts global concurrency, family concentration, and directional imbalance.
 */
data class ExposureLimits(
    val maxGlobalPositions: Int = 5,
    val maxTrendPositions: Int = 3,
    val maxBreakoutPositions: Int = 2,
    val maxMeanRevPositions: Int = 2,
    val maxStructurePositions: Int = 2,
    val maxRotationPositions: Int = 3,
    val maxDirectionalImbalance: Int = 3 // Net Long - Net Short
) {
    fun getCapForFamily(family: StrategyFamily): Int = when (family) {
        StrategyFamily.TREND -> maxTrendPositions
        StrategyFamily.BREAKOUT -> maxBreakoutPositions
        StrategyFamily.MEANREV -> maxMeanRevPositions
        StrategyFamily.STRUCTURE -> maxStructurePositions
        StrategyFamily.ROTATION -> maxRotationPositions
    }
}
