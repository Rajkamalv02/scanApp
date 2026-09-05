package com.coindcx.trading.engine

sealed class ExecutionResult {
    data class Success(val orderId: String, val message: String) : ExecutionResult()
    data class Failed(val error: String) : ExecutionResult()
}

/**
 * Execution Engine Abstraction
 * Allows seamless switching between simulated paper-trading and real-money live execution.
 */
interface ExecutionEngine {
    val isPaperTrading: Boolean

    suspend fun getActivePosition(pair: String): com.coindcx.trading.data.api.models.FuturesPosition?

    suspend fun executeSignal(
        signal: Signal,
        pair: String,
        currentPrice: Double,
        quantity: Double,
        leverage: Int
    ): ExecutionResult

    suspend fun exitPosition(
        pair: String,
        currentPrice: Double,
        reason: String
    ): ExecutionResult
}
