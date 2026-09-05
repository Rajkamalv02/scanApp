package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.FuturesPosition

data class ExchangeStateSnapshot(
    val availableBalanceInr: Double,
    val openPositions: List<FuturesPosition>,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class ExecutionResult {
    data class Success(val orderId: String, val message: String) : ExecutionResult()
    data class Failed(val error: String) : ExecutionResult()
}

/**
 * Execution Engine Abstraction
 * Supports INR-denominated trading across simulated paper-trading and real-money live execution.
 */
interface ExecutionEngine {
    val isPaperTrading: Boolean

    suspend fun getAvailableBalanceInr(): Double

    suspend fun getActivePosition(pair: String): FuturesPosition?

    suspend fun getAllOpenPositions(): List<FuturesPosition>

    suspend fun refreshExchangeState(): Result<ExchangeStateSnapshot>

    suspend fun executeSignal(
        signal: Signal,
        pair: String,
        currentPrice: Double,
        marginInr: Double,
        leverage: Int
    ): ExecutionResult

    suspend fun exitPosition(
        pair: String,
        currentPrice: Double,
        reason: String
    ): ExecutionResult
}
