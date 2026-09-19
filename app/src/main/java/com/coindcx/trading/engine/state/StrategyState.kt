package com.coindcx.trading.engine.state

import com.coindcx.trading.engine.SignalDirection

/**
 * Base immutable state marker for stateful strategy persistence.
 */
interface StrategyState {
    val lastUpdatedBarOpenTime: Long
}

enum class StructureTrend {
    UPTREND,
    DOWNTREND,
    NEUTRAL
}

data class OrderBlockZone(
    val isBullish: Boolean,
    val top: Double,
    val bottom: Double,
    val formationBarOpenTime: Long,
    var isTested: Boolean = false,
    var isInvalidated: Boolean = false
) {
    val height: Double get() = top - bottom
}

data class LevelRecord(
    val price: Double,
    val formationBarOpenTime: Long,
    val touchCount: Int = 1,
    var isInvalidated: Boolean = false
)

// Specific state objects
data class ImpulseState(
    val direction: SignalDirection,
    val impulseHigh: Double,
    val impulseLow: Double,
    val impulseVolume: Double,
    val impulseBarTime: Long,
    val barsSinceImpulse: Int = 0,
    override val lastUpdatedBarOpenTime: Long
) : StrategyState {
    val range: Double get() = impulseHigh - impulseLow
    val fib382: Double get() = if (direction == SignalDirection.LONG) impulseHigh - 0.382 * range else impulseLow + 0.382 * range
    val fib618: Double get() = if (direction == SignalDirection.LONG) impulseHigh - 0.618 * range else impulseLow + 0.618 * range
}

data class StructureState(
    val trend: StructureTrend = StructureTrend.NEUTRAL,
    val swingHighs: List<Double> = emptyList(),
    val swingLows: List<Double> = emptyList(),
    val activeOrderBlocks: List<OrderBlockZone> = emptyList(),
    val lastBOSBarTime: Long = 0L,
    val lastCHoCHBarTime: Long = 0L,
    override val lastUpdatedBarOpenTime: Long
) : StrategyState

data class LevelBufferState(
    val swingHighs: List<LevelRecord> = emptyList(),
    val swingLows: List<LevelRecord> = emptyList(),
    override val lastUpdatedBarOpenTime: Long
) : StrategyState

data class SessionState(
    val sessionId: String, // "A" or "B"
    val sessionStartOpenTime: Long,
    val orHigh: Double,
    val orLow: Double,
    val orVolumeSum: Double,
    val hasSignalled: Boolean = false,
    override val lastUpdatedBarOpenTime: Long
) : StrategyState {
    val orHeight: Double get() = orHigh - orLow
}

data class RangeMaturityState(
    val consecutiveBarsInRange: Int,
    override val lastUpdatedBarOpenTime: Long
) : StrategyState

data class RelativeStrengthHolding(
    val symbol: String,
    val direction: SignalDirection,
    val entryBarOpenTime: Long,
    val alphaRankPct: Double
)

data class CrossSectionalState(
    val activeHoldings: Map<String, RelativeStrengthHolding> = emptyMap(),
    override val lastUpdatedBarOpenTime: Long = 0L
) : StrategyState
