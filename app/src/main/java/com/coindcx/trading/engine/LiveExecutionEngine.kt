package com.coindcx.trading.engine

import com.coindcx.trading.data.api.CoinDCXApiService

class LiveExecutionEngine(
    private val orderManager: OrderManager,
    private val apiService: CoinDCXApiService
) : ExecutionEngine {

    override val isPaperTrading: Boolean = false

    override suspend fun getActivePosition(pair: String): com.coindcx.trading.data.api.models.FuturesPosition? {
        return try {
            val positionsPayload = mapOf(
                "page" to "1",
                "size" to "50",
                "margin_currency_short_name" to listOf("USDT"),
                "timestamp" to System.currentTimeMillis()
            )
            val positionsResp = apiService.getPositions(positionsPayload)
            positionsResp.body()?.find { it.pair == pair && it.isOpen }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun executeSignal(
        signal: Signal,
        pair: String,
        currentPrice: Double,
        quantity: Double,
        leverage: Int
    ): ExecutionResult {
        val isBuy = signal.action == SignalAction.ENTER_LONG
        val side = if (isBuy) "buy" else "sell"

        return when (val res = orderManager.placeLimitOrder(pair, side, currentPrice, quantity, leverage)) {
            is OrderResult.Success -> ExecutionResult.Success(res.orderId, "Live order placed: ${res.orderId}")
            is OrderResult.Ambiguous -> ExecutionResult.Failed("Order ambiguous (${res.clientOrderId}): ${res.message}")
            is OrderResult.Failed -> ExecutionResult.Failed("Live order failed: ${res.error}")
        }
    }

    override suspend fun exitPosition(
        pair: String,
        currentPrice: Double,
        reason: String
    ): ExecutionResult {
        return try {
            // Cancel all open orders for pair
            val ordersPayload = mapOf("page" to "1", "size" to "50", "timestamp" to System.currentTimeMillis())
            val openOrdersResp = apiService.getOpenOrders(ordersPayload)
            if (openOrdersResp.isSuccessful && openOrdersResp.body() != null) {
                for (o in openOrdersResp.body()!!.filter { it.pair == pair }) {
                    apiService.cancelOrder(mapOf("id" to o.id, "timestamp" to System.currentTimeMillis()))
                }
            }
            ExecutionResult.Success("exit_success", "Closed/Cancelled live orders on $pair")
        } catch (e: Exception) {
            ExecutionResult.Failed("Failed to exit live position on $pair: ${e.message}")
        }
    }
}
