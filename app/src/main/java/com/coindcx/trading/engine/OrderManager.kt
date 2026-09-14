package com.coindcx.trading.engine

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.CreateOrderRequest
import com.coindcx.trading.data.api.models.OrderPayload
import com.coindcx.trading.data.db.dao.OrderDao
import com.coindcx.trading.data.db.entities.OrderEntity
import com.coindcx.trading.util.AppLogManager
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
    private val orderDao: OrderDao,
    private val currencyConverter: com.coindcx.trading.engine.currency.CurrencyConverter? = null
) {
    fun generateClientOrderId(): String {
        return "bot_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 6)}"
    }

    suspend fun placeLimitOrder(
        pair: String,
        side: String,
        price: Double,
        quantity: Double,
        leverage: Int,
        tradeId: String = "",
        stopLossPrice: Double? = null,
        takeProfitPrice: Double? = null
    ): OrderResult {
        val clientOrderId = tradeId.ifEmpty { generateClientOrderId() }

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

        val notionalUsdt = quantity * price
        val requestedAttrs = mutableMapOf(
            "side" to side.uppercase(),
            "order_type" to "LIMIT",
            "price" to "%.4f".format(price),
            "quantity" to "%.4f".format(quantity),
            "leverage" to "${leverage}x",
            "margin_currency" to "INR",
            "notional_usdt" to "%.2f".format(notionalUsdt)
        ).apply {
            stopLossPrice?.let { put("stop_loss", "%.4f".format(it)) }
            takeProfitPrice?.let { put("take_profit", "%.4f".format(it)) }
        }

        AppLogManager.tradeLifecycle(
            event = "ORDER_REQUESTED",
            tradeId = clientOrderId,
            symbol = pair,
            mode = "LIVE",
            attributes = requestedAttrs,
            narrative = "ORDER_REQUESTED: Submitting LIVE LIMIT order for %s %s qty=%.4f @ %.4f (Lev: %dx, Margin Currency: INR, Notional: $%.2f USDT, SL: %s, TP: %s)"
                .format(
                    pair,
                    side.uppercase(),
                    quantity,
                    price,
                    leverage,
                    notionalUsdt,
                    stopLossPrice?.let { "%.4f".format(it) } ?: "None",
                    takeProfitPrice?.let { "%.4f".format(it) } ?: "None"
                )
        )

        val request = CreateOrderRequest(
            timestamp = System.currentTimeMillis(),
            order = OrderPayload(
                side = side.lowercase(),
                pair = pair,
                orderType = "limit_order",
                price = price,
                totalQuantity = quantity,
                leverage = leverage,
                clientOrderId = clientOrderId,
                stopLossPrice = stopLossPrice,
                takeProfitPrice = takeProfitPrice
            )
        )

        return try {
            val response = apiService.createOrder(request)
            val order = response.body()?.firstOrNull()
            if (response.isSuccessful && order != null) {
                orderDao.update(
                    orderEntity.copy(
                        exchangeOrderId = order.id,
                        status = order.status
                    )
                )
                order.settlementCurrencyConversionPrice?.let { rate ->
                    currencyConverter?.updateSettlementRateFromExchange(rate)
                }
                val acceptedAttrs = mutableMapOf(
                    "exchange_order_id" to order.id,
                    "status" to order.status,
                    "side" to side.uppercase(),
                    "price" to "%.4f".format(price),
                    "quantity" to "%.4f".format(quantity)
                ).apply {
                    (order.stopLossPrice ?: stopLossPrice)?.let { put("stop_loss", "%.4f".format(it)) }
                    (order.takeProfitPrice ?: takeProfitPrice)?.let { put("take_profit", "%.4f".format(it)) }
                }
                AppLogManager.tradeLifecycle(
                    event = "ORDER_ACCEPTED",
                    tradeId = clientOrderId,
                    symbol = pair,
                    mode = "LIVE",
                    attributes = acceptedAttrs,
                    narrative = "ORDER_ACCEPTED: Live order accepted by CoinDCX: %s (Status: %s, SL: %s, TP: %s)".format(
                        order.id,
                        order.status,
                        (order.stopLossPrice ?: stopLossPrice)?.let { "%.4f".format(it) } ?: "None",
                        (order.takeProfitPrice ?: takeProfitPrice)?.let { "%.4f".format(it) } ?: "None"
                    )
                )
                OrderResult.Success(order.id, clientOrderId)
            } else {
                val rawErr = response.errorBody()?.string()
                val errorMsg = if (!rawErr.isNullOrBlank()) rawErr else "Order rejected (HTTP ${response.code()})"
                orderDao.update(orderEntity.copy(status = "REJECTED"))
                AppLogManager.tradeLifecycle(
                    event = "ORDER_REJECTED",
                    tradeId = clientOrderId,
                    symbol = pair,
                    mode = "LIVE",
                    attributes = mapOf(
                        "error" to errorMsg,
                        "side" to side.uppercase(),
                        "price" to "%.4f".format(price)
                    ),
                    narrative = "ORDER_REJECTED: CoinDCX rejected live order for %s: %s".format(pair, errorMsg)
                )
                OrderResult.Failed(errorMsg)
            }
        } catch (e: Exception) {
            // Network drop, timeout, or dropped response -> Enter UNKNOWN state
            // Never assume failure, never assume fill.
            orderDao.update(orderEntity.copy(status = "UNKNOWN"))
            AppLogManager.tradeLifecycle(
                event = "ORDER_AMBIGUOUS",
                tradeId = clientOrderId,
                symbol = pair,
                mode = "LIVE",
                attributes = mapOf(
                    "error" to (e.message ?: "Network error / Timeout"),
                    "side" to side.uppercase(),
                    "price" to "%.4f".format(price)
                ),
                narrative = "ORDER_AMBIGUOUS: Network error / timeout during live order on %s: %s".format(pair, e.message)
            )
            OrderResult.Ambiguous(clientOrderId, e.message ?: "Network error / Timeout")
        }
    }
}
