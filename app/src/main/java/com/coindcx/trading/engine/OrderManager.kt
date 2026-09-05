package com.coindcx.trading.engine

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.CreateOrderRequest
import com.coindcx.trading.data.api.models.OrderPayload
import com.coindcx.trading.data.db.dao.OrderDao
import com.coindcx.trading.data.db.entities.OrderEntity
import java.util.UUID

sealed class OrderResult {
    data class Success(val orderId: String, val clientOrderId: String) : OrderResult()
    data class Ambiguous(val clientOrderId: String, val message: String) : OrderResult()
    data class Failed(val error: String) : OrderResult()
}

/**
 * Order Manager
 * Governs order submission, client_order_id generation, and ambiguous state resolution.
 */
class OrderManager(
    private val apiService: CoinDCXApiService,
    private val orderDao: OrderDao
) {
    fun generateClientOrderId(): String {
        return "bot_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 6)}"
    }

    suspend fun placeLimitOrder(
        pair: String,
        side: String,
        price: Double,
        quantity: Double,
        leverage: Int
    ): OrderResult {
        val clientOrderId = generateClientOrderId()

        // 1. Record order locally as PENDING before transmission
        val orderEntity = OrderEntity(
            clientOrderId = clientOrderId,
            pair = pair,
            side = side,
            orderType = "limit_order",
            price = price,
            totalQuantity = quantity,
            status = "PENDING"
        )
        orderDao.insert(orderEntity)

        val request = CreateOrderRequest(
            timestamp = System.currentTimeMillis(),
            order = OrderPayload(
                side = side.lowercase(),
                pair = pair,
                orderType = "limit_order",
                price = price,
                totalQuantity = quantity,
                leverage = leverage,
                clientOrderId = clientOrderId
            )
        )

        return try {
            val response = apiService.createOrder(request)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                orderDao.update(
                    orderEntity.copy(
                        exchangeOrderId = body.id,
                        status = body.status
                    )
                )
                OrderResult.Success(body.id, clientOrderId)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Order rejected"
                orderDao.update(orderEntity.copy(status = "REJECTED"))
                OrderResult.Failed(errorMsg)
            }
        } catch (e: Exception) {
            // Network drop, timeout, or dropped response -> Enter UNKNOWN state
            // Never assume failure, never assume fill.
            orderDao.update(orderEntity.copy(status = "UNKNOWN"))
            OrderResult.Ambiguous(clientOrderId, e.message ?: "Network error / Timeout")
        }
    }
}
