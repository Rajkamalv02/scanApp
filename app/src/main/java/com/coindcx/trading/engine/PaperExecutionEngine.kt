package com.coindcx.trading.engine

import android.content.Context
import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.OrderEntity
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.data.db.entities.TradeEntity
import com.coindcx.trading.engine.currency.CurrencyConverter
import java.util.UUID

class PaperExecutionEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val currencyConverter: CurrencyConverter
) : ExecutionEngine {

    override val isPaperTrading: Boolean = true
    private val slippageRate = 0.0005 // 0.05% estimated slippage
    private val feeRate = 0.0005      // 0.05% standard taker fee

    private val initialBalanceInr = 10_000.0

    override suspend fun getAvailableBalanceInr(): Double {
        val openTrades = db.tradeDao().getOpenTrades()
        val lockedMarginInr = openTrades.sumOf { it.allocatedMarginInr }

        // Fetch closed trades for realized P&L & fees
        val closedTrades = db.tradeDao().getTradesSince(0).filter { it.status == "CLOSED" }
        val cumulativeRealizedPnlInr = closedTrades.sumOf { it.realizedPnl ?: 0.0 }
        val totalFeesInr = db.tradeDao().getTradesSince(0).sumOf { it.fees }

        val calculated = initialBalanceInr + cumulativeRealizedPnlInr - totalFeesInr - lockedMarginInr
        return calculated.coerceAtLeast(0.0)
    }

    override suspend fun getActivePosition(pair: String): FuturesPosition? {
        val openTrade = db.tradeDao().getOpenTrades().find { it.pair == pair } ?: return null
        val isLong = openTrade.side == "LONG"
        return FuturesPosition(
            id = openTrade.clientOrderId,
            pair = openTrade.pair,
            activePos = if (isLong) openTrade.quantity else -openTrade.quantity,
            inactivePosBuy = 0.0,
            inactivePosSell = 0.0,
            avgPrice = openTrade.entryPrice,
            liquidationPrice = 0.0,
            lockedMargin = openTrade.allocatedMarginInr,
            lockedUserMargin = openTrade.allocatedMarginInr,
            lockedOrderMargin = 0.0,
            takeProfitTrigger = openTrade.takeProfit,
            stopLossTrigger = openTrade.stopLoss,
            leverage = openTrade.leverage.toDouble(),
            maintenanceMargin = 0.0,
            markPrice = openTrade.entryPrice,
            marginType = "ISOLATED",
            settlementCurrencyAvgPrice = openTrade.entryPrice,
            cumulativeFundingFee = 0.0,
            marginCurrencyShortName = "INR",
            updatedAt = openTrade.entryTime
        )
    }

    override suspend fun getAllOpenPositions(): List<FuturesPosition> {
        val openTrades = db.tradeDao().getOpenTrades()
        return openTrades.map { trade ->
            val isLong = trade.side == "LONG"
            FuturesPosition(
                id = trade.clientOrderId,
                pair = trade.pair,
                activePos = if (isLong) trade.quantity else -trade.quantity,
                inactivePosBuy = 0.0,
                inactivePosSell = 0.0,
                avgPrice = trade.entryPrice,
                liquidationPrice = 0.0,
                lockedMargin = trade.allocatedMarginInr,
                lockedUserMargin = trade.allocatedMarginInr,
                lockedOrderMargin = 0.0,
                takeProfitTrigger = trade.takeProfit,
                stopLossTrigger = trade.stopLoss,
                leverage = trade.leverage.toDouble(),
                maintenanceMargin = 0.0,
                markPrice = trade.entryPrice,
                marginType = "ISOLATED",
                settlementCurrencyAvgPrice = trade.entryPrice,
                cumulativeFundingFee = 0.0,
                marginCurrencyShortName = "INR",
                updatedAt = trade.entryTime
            )
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

    suspend fun checkPaperStopLossAndTakeProfit(pair: String, currentPrice: Double) {
        val openTrades = db.tradeDao().getOpenTrades().filter { it.pair == pair }
        for (trade in openTrades) {
            val isLong = trade.side == "LONG"
            val sl = trade.stopLoss
            val tp = trade.takeProfit
            if (isLong) {
                if (sl != null && currentPrice <= sl) {
                    exitPosition(pair, currentPrice, "Paper Stop-Loss triggered (Price: ₹$currentPrice <= SL: ₹$sl)")
                } else if (tp != null && currentPrice >= tp) {
                    exitPosition(pair, currentPrice, "Paper Take-Profit triggered (Price: ₹$currentPrice >= TP: ₹$tp)")
                }
            } else {
                if (sl != null && currentPrice >= sl) {
                    exitPosition(pair, currentPrice, "Paper Stop-Loss triggered (Price: ₹$currentPrice >= SL: ₹$sl)")
                } else if (tp != null && currentPrice <= tp) {
                    exitPosition(pair, currentPrice, "Paper Take-Profit triggered (Price: ₹$currentPrice <= TP: ₹$tp)")
                }
            }
        }
    }

    override suspend fun executeSignal(
        signal: Signal,
        pair: String,
        currentPrice: Double,
        marginInr: Double,
        leverage: Int
    ): ExecutionResult {
        val clientOrderId = "paper_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

        val isBuy = signal.action == SignalAction.ENTER_LONG
        val side = if (isBuy) "LONG" else "SHORT"

        // Apply slippage
        val fillPrice = if (isBuy) {
            currentPrice * (1.0 + slippageRate)
        } else {
            currentPrice * (1.0 - slippageRate)
        }

        // Convert INR margin to crypto quantity
        val rawQty = currencyConverter.convertInrMarginToContractQuantity(marginInr, leverage, fillPrice)
        val quantity = rawQty.coerceAtLeast(0.001)

        val notionalInr = marginInr * leverage
        val entryFeeInr = notionalInr * feeRate

        val trade = TradeEntity(
            pair = pair,
            side = side,
            entryPrice = fillPrice,
            quantity = quantity,
            leverage = leverage,
            stopLoss = signal.stopLossPrice,
            takeProfit = signal.takeProfitPrice,
            fees = entryFeeInr,
            realizedPnl = null,
            unrealizedPnl = 0.0,
            allocatedMarginInr = marginInr,
            clientOrderId = clientOrderId,
            status = "OPEN",
            entryTime = System.currentTimeMillis(),
            strategyName = StrategyRegistry.activeStrategy.name
        )

        db.tradeDao().insert(trade)

        db.orderDao().insert(
            OrderEntity(
                clientOrderId = clientOrderId,
                exchangeOrderId = "sim_${clientOrderId}",
                pair = pair,
                side = side,
                orderType = "limit_order",
                price = fillPrice,
                totalQuantity = quantity,
                filledQuantity = quantity,
                status = "FILLED"
            )
        )

        db.systemLogDao().insert(
            SystemLogEntity(
                level = "INFO",
                tag = "PAPER_TRADE",
                message = "Simulated $side $pair (Margin: ₹%.0f, Lev: ${leverage}x, Qty: $quantity) @ $fillPrice. ${signal.reason}".format(marginInr)
            )
        )

        return ExecutionResult.Success(
            orderId = clientOrderId,
            message = "Simulated $side fill on $pair with ₹%.0f margin".format(marginInr)
        )
    }

    override suspend fun exitPosition(
        pair: String,
        currentPrice: Double,
        reason: String
    ): ExecutionResult {
        val openTrades = db.tradeDao().getOpenTrades().filter { it.pair == pair }
        if (openTrades.isEmpty()) {
            return ExecutionResult.Failed("No open paper trades found for $pair")
        }

        for (trade in openTrades) {
            val isLong = trade.side == "LONG"
            val exitFillPrice = if (isLong) {
                currentPrice * (1.0 - slippageRate)
            } else {
                currentPrice * (1.0 + slippageRate)
            }

            val notionalInr = trade.allocatedMarginInr * trade.leverage
            val exitFeeInr = notionalInr * feeRate
            val totalFeesInr = trade.fees + exitFeeInr

            // Calculate percentage return on notional
            val pnlPct = if (isLong) {
                (exitFillPrice - trade.entryPrice) / trade.entryPrice
            } else {
                (trade.entryPrice - exitFillPrice) / trade.entryPrice
            }

            val realizedPnlInr = (notionalInr * pnlPct) - totalFeesInr

            db.tradeDao().update(
                trade.copy(
                    exitPrice = exitFillPrice,
                    fees = totalFeesInr,
                    realizedPnl = realizedPnlInr,
                    status = "CLOSED",
                    exitTime = System.currentTimeMillis(),
                    exitReason = reason
                )
            )

            db.systemLogDao().insert(
                SystemLogEntity(
                    level = if (realizedPnlInr >= 0) "INFO" else "WARN",
                    tag = "PAPER_EXIT",
                    message = "Simulated exit $pair @ $exitFillPrice. P&L: ₹%.2f. Reason: %s".format(realizedPnlInr, reason)
                )
            )
        }

        return ExecutionResult.Success("closed_all", "Closed ${openTrades.size} paper positions for $pair")
    }
}
