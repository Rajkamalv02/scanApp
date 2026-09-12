package com.coindcx.trading.engine

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.engine.currency.CurrencyConverter
import com.coindcx.trading.util.AppLogManager

class LiveExecutionEngine(
    private val orderManager: OrderManager,
    private val apiService: CoinDCXApiService,
    private val currencyConverter: CurrencyConverter
) : ExecutionEngine {

    override val isPaperTrading: Boolean = false

    override suspend fun getAvailableBalanceInr(): Double {
        return try {
            val resp = apiService.getFuturesWallets()
            if (resp.isSuccessful && !resp.body().isNullOrEmpty()) {
                val inrWallet = resp.body()!!.find { it.currencyShortName.equals("INR", ignoreCase = true) }
                if (inrWallet != null) {
                    return inrWallet.availableBalance
                }
                // Fallback: convert USDT wallet balance to INR
                val usdtWallet = resp.body()!!.find { it.currencyShortName.equals("USDT", ignoreCase = true) }
                if (usdtWallet != null) {
                    return currencyConverter.convertUsdtToInr(usdtWallet.availableBalance)
                }
            }
            0.0
        } catch (_: Exception) {
            0.0
        }
    }

    override suspend fun getActivePosition(pair: String): FuturesPosition? {
        return try {
            val positionsPayload = mapOf(
                "page" to "1",
                "size" to "50",
                "margin_currency_short_name" to listOf("USDT"),
                "timestamp" to System.currentTimeMillis()
            )
            val positionsResp = apiService.getPositions(positionsPayload)
            positionsResp.body()?.find { it.pair == pair && (it.isOpen || it.inactivePosBuy > 0 || it.inactivePosSell > 0) }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getAllOpenPositions(): List<FuturesPosition> {
        return try {
            val positionsPayload = mapOf(
                "page" to "1",
                "size" to "50",
                "margin_currency_short_name" to listOf("USDT"),
                "timestamp" to System.currentTimeMillis()
            )
            val positionsResp = apiService.getPositions(positionsPayload)
            positionsResp.body()?.filter { it.isOpen || it.inactivePosBuy > 0 || it.inactivePosSell > 0 } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun refreshExchangeState(): Result<ExchangeStateSnapshot> {
        return try {
            val balanceInr = getAvailableBalanceInr()
            val positions = getAllOpenPositions()
            Result.success(
                ExchangeStateSnapshot(
                    availableBalanceInr = balanceInr,
                    openPositions = positions,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun executeSignal(
        signal: Signal,
        pair: String,
        currentPrice: Double,
        marginInr: Double,
        leverage: Int,
        tradeId: String
    ): ExecutionResult {
        val isBuy = signal.action == SignalAction.ENTER_LONG
        val side = if (isBuy) "buy" else "sell"

        val rawQty = currencyConverter.convertInrMarginToContractQuantity(marginInr, leverage, currentPrice)
        val quantity = rawQty.coerceAtLeast(0.001)

        return when (val res = orderManager.placeLimitOrder(pair, side, currentPrice, quantity, leverage, tradeId)) {
            is OrderResult.Success -> {
                AppLogManager.trade("LIVE_EXEC", "Placed live order: ${res.orderId} on $pair $side qty=$quantity @ $currentPrice (Margin: ₹%.0f)".format(marginInr))
                ExecutionResult.Success(res.orderId, "Live order placed: ${res.orderId} (Margin: ₹%.0f)".format(marginInr))
            }
            is OrderResult.Ambiguous -> {
                AppLogManager.w("LIVE_EXEC", "Order ambiguous (${res.clientOrderId}): ${res.message}")
                ExecutionResult.Failed("Order ambiguous (${res.clientOrderId}): ${res.message}")
            }
            is OrderResult.Failed -> {
                AppLogManager.e("LIVE_EXEC", "Live order failed on $pair: ${res.error}")
                ExecutionResult.Failed("Live order failed: ${res.error}")
            }
        }
    }

    override suspend fun exitPosition(
        pair: String,
        currentPrice: Double,
        reason: String,
        tradeId: String?
    ): ExecutionResult {
        return try {
            val ordersPayload = mapOf("page" to "1", "size" to "50", "timestamp" to System.currentTimeMillis())
            val openOrdersResp = apiService.getOpenOrders(ordersPayload)
            if (openOrdersResp.isSuccessful && openOrdersResp.body() != null) {
                for (o in openOrdersResp.body()!!.filter { it.pair == pair }) {
                    apiService.cancelOrder(mapOf("id" to o.id, "timestamp" to System.currentTimeMillis()))
                }
            }
            AppLogManager.tradeLifecycle(
                event = "EXIT_ORDER_FILLED",
                tradeId = tradeId ?: "live_exit_$pair",
                symbol = pair,
                mode = "LIVE",
                attributes = mapOf(
                    "reason" to reason,
                    "price" to currentPrice
                ),
                narrative = "Closed/Cancelled live orders on %s: %s @ %.4f".format(pair, reason, currentPrice)
            )
            ExecutionResult.Success("exit_success", "Closed/Cancelled live orders on $pair")
        } catch (e: Exception) {
            ExecutionResult.Failed("Failed to exit live position on $pair: ${e.message}")
        }
    }
}
