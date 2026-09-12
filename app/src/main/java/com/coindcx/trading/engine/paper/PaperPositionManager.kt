package com.coindcx.trading.engine.paper

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.data.db.entities.TradeEntity
import com.coindcx.trading.util.AppLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

class PaperPositionManager(
    private val apiService: CoinDCXApiService,
    private val db: AppDatabase,
    private val accountManager: PaperAccountManager
) {
    var onTradeClosed: ((trade: TradeEntity, netRealizedPnl: Double) -> Unit)? = null

    companion object {
        const val SLIPPAGE_RATE = 0.0005 // 0.05% slippage
        const val TAKER_FEE_RATE = 0.0005 // 0.05% taker fee
        const val FUNDING_RATE_8H = 0.0001 // 0.01% per 8-hour period

        /**
         * Tiered Maintenance Margin schedule based on leverage:
         * <= 5x  : 1.0% (0.010)
         * 6x-10x : 1.5% (0.015)
         * 11x-20x: 2.5% (0.025)
         */
        fun getMaintenanceMargin(leverage: Int): Double {
            return when {
                leverage <= 5 -> 0.010
                leverage <= 10 -> 0.015
                else -> 0.025
            }
        }

        fun calculateEstimatedLiquidation(
            side: String,
            entryPrice: Double,
            leverage: Int
        ): Double {
            val mm = getMaintenanceMargin(leverage)
            val levInv = 1.0 / leverage.coerceAtLeast(1)
            return if (side.equals("LONG", ignoreCase = true)) {
                entryPrice * (1.0 - levInv + mm)
            } else {
                entryPrice * (1.0 + levInv - mm)
            }
        }

        /**
         * Calculates number of 8h UTC funding intervals crossed between startTime and now.
         * CoinDCX funding settles at 00:00, 08:00, 16:00 UTC.
         */
        fun countFundingIntervalsCrossed(startTimeMillis: Long, endTimeMillis: Long): Int {
            if (endTimeMillis <= startTimeMillis) return 0
            val intervalMs = 8 * 3600 * 1000L
            val startIntervalIndex = startTimeMillis / intervalMs
            val endIntervalIndex = endTimeMillis / intervalMs
            return (endIntervalIndex - startIntervalIndex).toInt().coerceAtLeast(0)
        }
    }

    /**
     * Updates all open paper positions using a SINGLE batched ticker call.
     * Prevents rate-limit exposure.
     */
    suspend fun updateOpenPositions(): Int = withContext(Dispatchers.IO) {
        val openTrades = db.tradeDao().getOpenTrades()
        if (openTrades.isEmpty()) return@withContext 0

        // 1. Fetch batched ticker (one lightweight HTTP GET)
        val tickerPrices = fetchBatchedTickerPrices()
        if (tickerPrices.isEmpty()) return@withContext 0

        val now = System.currentTimeMillis()
        var updatedCount = 0

        for (trade in openTrades) {
            val currentPrice = matchTickerPrice(trade.pair, tickerPrices) ?: continue
            updatedCount++

            val isLong = trade.side.equals("LONG", ignoreCase = true)
            val notionalInr = if (trade.notionalValueInr > 0) trade.notionalValueInr else trade.allocatedMarginInr * trade.leverage

            // 2. Exchange-aligned funding calculation
            val fundingIntervals = countFundingIntervalsCrossed(trade.entryTime, now)
            val accruedFunding = notionalInr * FUNDING_RATE_8H * fundingIntervals

            // 3. Gross PnL
            val grossPnl = if (isLong) {
                notionalInr * ((currentPrice - trade.entryPrice) / trade.entryPrice)
            } else {
                notionalInr * ((trade.entryPrice - currentPrice) / trade.entryPrice)
            }

            // Unrealized Net PnL = Gross PnL - Initial Entry Fees - Accrued Funding
            val unrealizedNetPnl = grossPnl - trade.fees - accruedFunding
            val roiPct = if (trade.allocatedMarginInr > 0) (unrealizedNetPnl / trade.allocatedMarginInr) * 100.0 else 0.0

            val estLiq = trade.estimatedLiquidationPrice ?: calculateEstimatedLiquidation(trade.side, trade.entryPrice, trade.leverage)

            // 4. Check Exit Conditions (Stop-Loss, Take-Profit, Estimated Liquidation)
            var exitReason: String? = null
            var exitCondition: String = "MONITORING"

            if (isLong) {
                if (trade.stopLoss != null && currentPrice <= trade.stopLoss) {
                    exitCondition = "STOP_LOSS_HIT"
                    exitReason = "Stop-Loss hit (Price: $currentPrice <= SL: ${trade.stopLoss})"
                } else if (trade.takeProfit != null && currentPrice >= trade.takeProfit) {
                    exitCondition = "TARGET_HIT"
                    exitReason = "Take-Profit hit (Price: $currentPrice >= TP: ${trade.takeProfit})"
                } else if (currentPrice <= estLiq) {
                    exitCondition = "EST_LIQUIDATION"
                    exitReason = "EST. LIQ reached (Price: $currentPrice <= EstLiq: $estLiq)"
                }
            } else {
                if (trade.stopLoss != null && currentPrice >= trade.stopLoss) {
                    exitCondition = "STOP_LOSS_HIT"
                    exitReason = "Stop-Loss hit (Price: $currentPrice >= SL: ${trade.stopLoss})"
                } else if (trade.takeProfit != null && currentPrice <= trade.takeProfit) {
                    exitCondition = "TARGET_HIT"
                    exitReason = "Take-Profit hit (Price: $currentPrice <= TP: ${trade.takeProfit})"
                } else if (currentPrice >= estLiq) {
                    exitCondition = "EST_LIQUIDATION"
                    exitReason = "EST. LIQ reached (Price: $currentPrice >= EstLiq: $estLiq)"
                }
            }

            if (exitReason != null) {
                executeExit(trade, currentPrice, exitReason, accruedFunding, now, exitCondition)
            } else {
                // Update live position state
                db.tradeDao().update(
                    trade.copy(
                        currentPrice = currentPrice,
                        grossPnl = grossPnl,
                        fundingFees = accruedFunding,
                        unrealizedPnl = unrealizedNetPnl,
                        roiPercent = roiPct,
                        estimatedLiquidationPrice = estLiq,
                        notionalValueInr = notionalInr
                    )
                )

                AppLogManager.tradeLifecycle(
                    event = "POSITION_MONITOR",
                    tradeId = trade.clientOrderId,
                    symbol = trade.pair,
                    mode = "PAPER",
                    attributes = mapOf(
                        "side" to trade.side,
                        "entry_price" to "%.4f".format(trade.entryPrice),
                        "current_price" to "%.4f".format(currentPrice),
                        "gross_pnl_inr" to "₹%.2f".format(grossPnl),
                        "net_pnl_inr" to "₹%.2f".format(unrealizedNetPnl),
                        "roi_pct" to "%.2f%%".format(roiPct),
                        "stop_loss" to trade.stopLoss?.let { "%.4f".format(it) },
                        "target" to trade.takeProfit?.let { "%.4f".format(it) },
                        "est_liq" to "%.4f".format(estLiq)
                    ),
                    narrative = "Monitoring %s %s: Price=%.4f (Entry: %.4f) | Net PnL: ₹%.2f (ROI: %.2f%%) | SL: %s | TP: %s"
                        .format(trade.side, trade.pair, currentPrice, trade.entryPrice, unrealizedNetPnl, roiPct, trade.stopLoss ?: "None", trade.takeProfit ?: "None")
                )
            }
        }

        // Record continuous equity curve for True Max Drawdown
        accountManager.recordEquitySnapshot()
        updatedCount
    }

    suspend fun closePositionManually(tradeId: Long, reason: String = "Manual Close"): Boolean = withContext(Dispatchers.IO) {
        val trade = db.tradeDao().getTradeById(tradeId) ?: return@withContext false
        if (trade.status != "OPEN") return@withContext false

        val tickerPrices = fetchBatchedTickerPrices()
        val currentPrice = matchTickerPrice(trade.pair, tickerPrices) ?: trade.currentPrice ?: trade.entryPrice
        val now = System.currentTimeMillis()
        val fundingIntervals = countFundingIntervalsCrossed(trade.entryTime, now)
        val notionalInr = if (trade.notionalValueInr > 0) trade.notionalValueInr else trade.allocatedMarginInr * trade.leverage
        val accruedFunding = notionalInr * FUNDING_RATE_8H * fundingIntervals

        val condition = if (reason.contains("EMA", ignoreCase = true)) "EMA_REVERSAL_CROSS" else "MANUAL_CLOSE"
        executeExit(trade, currentPrice, reason, accruedFunding, now, condition)
        accountManager.recordEquitySnapshot()
        true
    }

    private suspend fun executeExit(
        trade: TradeEntity,
        marketPrice: Double,
        reason: String,
        accruedFunding: Double,
        exitTimestamp: Long,
        exitCondition: String = "UNKNOWN"
    ) {
        val isLong = trade.side.equals("LONG", ignoreCase = true)
        val notionalInr = if (trade.notionalValueInr > 0) trade.notionalValueInr else trade.allocatedMarginInr * trade.leverage

        AppLogManager.tradeLifecycle(
            event = "EXIT_SIGNAL",
            tradeId = trade.clientOrderId,
            symbol = trade.pair,
            mode = "PAPER",
            attributes = mapOf(
                "exit_condition" to exitCondition,
                "side" to trade.side,
                "entry_price" to "%.4f".format(trade.entryPrice),
                "market_price" to "%.4f".format(marketPrice),
                "stop_loss" to trade.stopLoss?.let { "%.4f".format(it) },
                "target" to trade.takeProfit?.let { "%.4f".format(it) },
                "trigger_reason" to reason
            ),
            narrative = "EXIT_SIGNAL: %s triggered on %s %s @ %.4f: %s"
                .format(exitCondition, trade.side, trade.pair, marketPrice, reason)
        )

        AppLogManager.tradeLifecycle(
            event = "EXIT_ORDER_CONSTRUCTED",
            tradeId = trade.clientOrderId,
            symbol = trade.pair,
            mode = "PAPER",
            attributes = mapOf(
                "side" to (if (isLong) "SELL" else "BUY"),
                "order_type" to "MARKET",
                "reduce_only" to true,
                "quantity" to "%.4f".format(trade.quantity),
                "reference_price" to "%.4f".format(marketPrice)
            ),
            narrative = "EXIT_ORDER_CONSTRUCTED: Submitting %s MARKET order to close %s %s qty=%.4f @ %.4f"
                .format(if (isLong) "SELL" else "BUY", trade.side, trade.pair, trade.quantity, marketPrice)
        )

        // Apply slippage on exit
        val exitFillPrice = if (isLong) {
            marketPrice * (1.0 - SLIPPAGE_RATE)
        } else {
            marketPrice * (1.0 + SLIPPAGE_RATE)
        }
        val exitSlippage = kotlin.math.abs(exitFillPrice - marketPrice)
        val exitSlippagePct = if (marketPrice > 0) (exitSlippage / marketPrice) * 100.0 else 0.0

        val exitFeeInr = notionalInr * TAKER_FEE_RATE
        val totalFeesInr = trade.fees + exitFeeInr

        val grossPnl = if (isLong) {
            notionalInr * ((exitFillPrice - trade.entryPrice) / trade.entryPrice)
        } else {
            notionalInr * ((trade.entryPrice - exitFillPrice) / trade.entryPrice)
        }

        // Net Realized PnL = Gross PnL - Total Fees - Accrued Funding
        val netRealizedPnl = grossPnl - totalFeesInr - accruedFunding
        val durationMillis = exitTimestamp - trade.entryTime
        val durationSec = durationMillis / 1000L
        val durationFormatted = "${durationSec / 60}m ${durationSec % 60}s"
        val roiPct = if (trade.allocatedMarginInr > 0) (netRealizedPnl / trade.allocatedMarginInr) * 100.0 else 0.0

        val tradeResult = when {
            netRealizedPnl > 1.0 -> "WIN"
            netRealizedPnl < -1.0 -> "LOSS"
            else -> "BREAKEVEN"
        }

        val closedTrade = trade.copy(
            status = "CLOSED",
            exitPrice = exitFillPrice,
            currentPrice = exitFillPrice,
            fees = totalFeesInr,
            fundingFees = accruedFunding,
            grossPnl = grossPnl,
            realizedPnl = netRealizedPnl,
            unrealizedPnl = 0.0,
            roiPercent = roiPct,
            exitTime = exitTimestamp,
            durationMillis = durationMillis,
            exitReason = reason,
            tradeResult = tradeResult
        )

        db.tradeDao().update(closedTrade)

        AppLogManager.tradeLifecycle(
            event = "EXIT_ORDER_FILLED",
            tradeId = trade.clientOrderId,
            symbol = trade.pair,
            mode = "PAPER",
            attributes = mapOf(
                "status" to "FILLED",
                "exit_fill_price" to "%.4f".format(exitFillPrice),
                "exit_slippage_inr" to "%.4f".format(exitSlippage),
                "exit_slippage_pct" to "%.3f%%".format(exitSlippagePct),
                "exit_fee_inr" to "₹%.2f".format(exitFeeInr),
                "accrued_funding_inr" to "₹%.2f".format(accruedFunding),
                "total_fees_inr" to "₹%.2f".format(totalFeesInr)
            ),
            narrative = "EXIT_ORDER_FILLED: %s closed @ %.4f (Slippage: %.3f%%, Exit Fee: ₹%.2f, Total Fees: ₹%.2f)"
                .format(trade.pair, exitFillPrice, exitSlippagePct, exitFeeInr, totalFeesInr)
        )

        AppLogManager.tradeLifecycle(
            event = "TRADE_COMPLETED",
            tradeId = trade.clientOrderId,
            symbol = trade.pair,
            mode = "PAPER",
            attributes = mapOf(
                "side" to trade.side,
                "entry_price" to "%.4f".format(trade.entryPrice),
                "exit_price" to "%.4f".format(exitFillPrice),
                "gross_pnl_inr" to "₹%.2f".format(grossPnl),
                "total_fees_inr" to "₹%.2f".format(totalFeesInr),
                "funding_fees_inr" to "₹%.2f".format(accruedFunding),
                "net_pnl_inr" to "₹%.2f".format(netRealizedPnl),
                "roi_pct" to "%.2f%%".format(roiPct),
                "trade_result" to tradeResult,
                "duration_sec" to durationSec,
                "duration_formatted" to durationFormatted,
                "exit_condition" to exitCondition,
                "exit_reason" to reason
            ),
            narrative = "TRADE_COMPLETED: %s %s | Entry: %.4f -> Exit: %.4f | Gross: ₹%.2f | Fees: ₹%.2f | Net: ₹%.2f (ROI: %.2f%%) | Result: %s | Duration: %s | Condition: %s"
                .format(trade.side, trade.pair, trade.entryPrice, exitFillPrice, grossPnl, totalFeesInr, netRealizedPnl, roiPct, tradeResult, durationFormatted, exitCondition)
        )

        onTradeClosed?.invoke(closedTrade, netRealizedPnl)
    }

    private suspend fun fetchBatchedTickerPrices(): Map<String, Double> {
        return try {
            val response = apiService.getTicker()
            if (!response.isSuccessful || response.body() == null) return emptyMap()

            val priceMap = mutableMapOf<String, Double>()
            for (item in response.body()!!) {
                val market = item["market"]?.toString() ?: item["pair"]?.toString() ?: continue
                val lastPriceStr = item["last_price"]?.toString() ?: continue
                val price = lastPriceStr.toDoubleOrNull() ?: continue
                priceMap[market] = price
            }
            priceMap
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun matchTickerPrice(pair: String, priceMap: Map<String, Double>): Double? {
        if (priceMap.containsKey(pair)) return priceMap[pair]

        // Try normalized variations: "B-BTC_USDT" -> "BTCUSDT", "BTC_USDT"
        val clean1 = pair.replace("B-", "")
        if (priceMap.containsKey(clean1)) return priceMap[clean1]

        val clean2 = clean1.replace("_", "")
        if (priceMap.containsKey(clean2)) return priceMap[clean2]

        // Reverse lookup
        val match = priceMap.entries.firstOrNull {
            it.key.equals(pair, ignoreCase = true) ||
            it.key.equals(clean1, ignoreCase = true) ||
            it.key.equals(clean2, ignoreCase = true)
        }
        return match?.value
    }
}
