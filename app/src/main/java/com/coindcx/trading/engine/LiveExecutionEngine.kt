package com.coindcx.trading.engine

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.engine.currency.CurrencyConverter
import com.coindcx.trading.util.AppLogManager

class LiveExecutionEngine(
    private val orderManager: OrderManager,
    private val apiService: CoinDCXApiService,
    private val currencyConverter: CurrencyConverter,
    private val universeManager: com.coindcx.trading.engine.scanner.FuturesUniverseManager? = null
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
            } else if (!resp.isSuccessful) {
                AppLogManager.w("LIVE_EXEC", "Failed fetching futures wallets: HTTP ${resp.code()} ${resp.errorBody()?.string()}")
            }
            0.0
        } catch (e: Exception) {
            AppLogManager.w("LIVE_EXEC", "Exception fetching futures wallets: ${e.message}")
            0.0
        }
    }

    override suspend fun getActivePosition(pair: String): FuturesPosition? {
        return try {
            val positionsPayload = mapOf(
                "page" to "1",
                "size" to "50",
                "margin_currency_short_name" to listOf("INR", "USDT"),
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
                "margin_currency_short_name" to listOf("INR", "USDT"),
                "timestamp" to System.currentTimeMillis()
            )
            val positionsResp = apiService.getPositions(positionsPayload)
            positionsResp.body()?.filter { it.isOpen || it.inactivePosBuy > 0 || it.inactivePosSell > 0 } ?: emptyList()
        } catch (e: Exception) {
            AppLogManager.w("LIVE_EXEC", "Exception fetching open positions: ${e.message}")
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

        val spec = universeManager?.getInstrumentSpec(pair)
        val step = spec?.step ?: 0.001
        val minNotionalUsdt = 6.0.coerceAtLeast(spec?.minNotionalUsdt ?: 5.0)

        val rawQty = currencyConverter.convertInrMarginToContractQuantity(marginInr, leverage, currentPrice)

        // Quantize to step size and guarantee CoinDCX minimum order notional
        var steps = if (step > 0) kotlin.math.round(rawQty / step) else rawQty
        var quantity = if (step > 0) steps * step else rawQty
        if (quantity * currentPrice < minNotionalUsdt && step * currentPrice > 0) {
            steps = kotlin.math.ceil(minNotionalUsdt / (step * currentPrice))
            quantity = steps * step
        }
        val precision = (spec?.targetCurrencyPrecision ?: 3).coerceIn(0, 8)
        val roundedQty = java.math.BigDecimal.valueOf(quantity)
            .setScale(precision, java.math.RoundingMode.HALF_UP)
            .toDouble()
        val finalQty = roundedQty.coerceAtLeast(spec?.minQuantity ?: step)

        val notionalUsdt = finalQty * currentPrice
        AppLogManager.trade("LIVE_EXEC",
            "Quantized order qty for %s: raw=%.6f -> final=%.6f (step=%s, precision=%d, notional=$%.2f USDT, floor=%.2f USDT)"
                .format(pair, rawQty, finalQty, step.toString(), precision, notionalUsdt, minNotionalUsdt)
        )

        val pricePrecision = (spec?.baseCurrencyPrecision ?: if (currentPrice < 1.0) 4 else 2).coerceIn(0, 8)
        val formattedSl = signal.stopLossPrice?.let {
            if (it > 0.0) {
                java.math.BigDecimal.valueOf(it)
                    .setScale(pricePrecision, java.math.RoundingMode.HALF_UP)
                    .toDouble()
            } else null
        }
        val formattedTp = signal.takeProfitPrice?.let {
            if (it > 0.0) {
                java.math.BigDecimal.valueOf(it)
                    .setScale(pricePrecision, java.math.RoundingMode.HALF_UP)
                    .toDouble()
            } else null
        }

        AppLogManager.trade("LIVE_EXEC",
            "Prepared bracket order for %s: entry=%.4f, SL=%s, TP=%s (price_precision=%d)"
                .format(pair, currentPrice, formattedSl?.toString() ?: "None", formattedTp?.toString() ?: "None", pricePrecision)
        )

        return when (val res = orderManager.placeLimitOrder(
            pair = pair,
            side = side,
            price = currentPrice,
            quantity = finalQty,
            leverage = leverage,
            tradeId = tradeId,
            stopLossPrice = formattedSl,
            takeProfitPrice = formattedTp
        )) {
            is OrderResult.Success -> {
                AppLogManager.trade("LIVE_EXEC", "Placed live bracket order: ${res.orderId} on $pair $side qty=$finalQty @ $currentPrice (SL: $formattedSl, TP: $formattedTp, Margin: ₹%.0f)".format(marginInr))
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
