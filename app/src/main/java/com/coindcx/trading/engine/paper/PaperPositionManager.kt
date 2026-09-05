package com.coindcx.trading.engine.paper

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.data.db.entities.TradeEntity
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

            if (isLong) {
                if (trade.stopLoss != null && currentPrice <= trade.stopLoss) {
                    exitReason = "Stop-Loss hit (Price: $currentPrice <= SL: ${trade.stopLoss})"
                } else if (trade.takeProfit != null && currentPrice >= trade.takeProfit) {
                    exitReason = "Take-Profit hit (Price: $currentPrice >= TP: ${trade.takeProfit})"
                } else if (currentPrice <= estLiq) {
                    exitReason = "EST. LIQ reached (Price: $currentPrice <= EstLiq: $estLiq)"
                }
            } else {
                if (trade.stopLoss != null && currentPrice >= trade.stopLoss) {
                    exitReason = "Stop-Loss hit (Price: $currentPrice >= SL: ${trade.stopLoss})"
                } else if (trade.takeProfit != null && currentPrice <= trade.takeProfit) {
                    exitReason = "Take-Profit hit (Price: $currentPrice <= TP: ${trade.takeProfit})"
                } else if (currentPrice >= estLiq) {
                    exitReason = "EST. LIQ reached (Price: $currentPrice >= EstLiq: $estLiq)"
                }
            }

            if (exitReason != null) {
                executeExit(trade, currentPrice, exitReason, accruedFunding, now)
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

        executeExit(trade, currentPrice, reason, accruedFunding, now)
        accountManager.recordEquitySnapshot()
        true
    }

    private suspend fun executeExit(
        trade: TradeEntity,
        marketPrice: Double,
        reason: String,
        accruedFunding: Double,
        exitTimestamp: Long
    ) {
        val isLong = trade.side.equals("LONG", ignoreCase = true)
        val notionalInr = if (trade.notionalValueInr > 0) trade.notionalValueInr else trade.allocatedMarginInr * trade.leverage

        // Apply slippage on exit
        val exitFillPrice = if (isLong) {
            marketPrice * (1.0 - SLIPPAGE_RATE)
        } else {
            marketPrice * (1.0 + SLIPPAGE_RATE)
        }

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

        db.systemLogDao().insert(
            SystemLogEntity(
                level = if (netRealizedPnl >= 0) "INFO" else "WARN",
                tag = "PAPER_EXIT",
                message = "Closed ${trade.side} ${trade.pair} @ $exitFillPrice. Net P&L: ₹%.2f (ROI: %.1f%%). Reason: %s"
                    .format(netRealizedPnl, roiPct, reason)
            )
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
