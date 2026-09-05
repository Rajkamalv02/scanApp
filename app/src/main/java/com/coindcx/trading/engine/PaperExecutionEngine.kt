package com.coindcx.trading.engine

import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.OrderEntity
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.data.db.entities.TradeEntity
import java.util.UUID

class PaperExecutionEngine(
    private val db: AppDatabase
) : ExecutionEngine {

    override val isPaperTrading: Boolean = true
    private val slippageRate = 0.0005 // 0.05% estimated slippage
    private val feeRate = 0.0005      // 0.05% standard taker fee

    override suspend fun getActivePosition(pair: String): com.coindcx.trading.data.api.models.FuturesPosition? {
        val openTrade = db.tradeDao().getOpenTrades().find { it.pair == pair } ?: return null
        val isLong = openTrade.side == "LONG"
        return com.coindcx.trading.data.api.models.FuturesPosition(
            id = openTrade.clientOrderId,
            pair = openTrade.pair,
            activePos = if (isLong) openTrade.quantity else -openTrade.quantity,
            inactivePosBuy = 0.0,
            inactivePosSell = 0.0,
            avgPrice = openTrade.entryPrice,
            liquidationPrice = 0.0,
            lockedMargin = (openTrade.entryPrice * openTrade.quantity) / openTrade.leverage,
            lockedUserMargin = (openTrade.entryPrice * openTrade.quantity) / openTrade.leverage,
            lockedOrderMargin = 0.0,
            takeProfitTrigger = openTrade.takeProfit,
            stopLossTrigger = openTrade.stopLoss,
            leverage = openTrade.leverage.toDouble(),
            maintenanceMargin = 0.0,
            markPrice = openTrade.entryPrice,
            marginType = "ISOLATED",
            settlementCurrencyAvgPrice = openTrade.entryPrice,
            cumulativeFundingFee = 0.0,
            marginCurrencyShortName = "USDT",
            updatedAt = openTrade.entryTime
        )
    }

    suspend fun checkPaperStopLossAndTakeProfit(pair: String, currentPrice: Double) {
        val openTrades = db.tradeDao().getOpenTrades().filter { it.pair == pair }
        for (trade in openTrades) {
            val isLong = trade.side == "LONG"
            val sl = trade.stopLoss
            val tp = trade.takeProfit
            if (isLong) {
                if (sl != null && currentPrice <= sl) {
                    exitPosition(pair, currentPrice, "Paper Stop-Loss triggered (Price: $currentPrice <= SL: $sl)")
                } else if (tp != null && currentPrice >= tp) {
                    exitPosition(pair, currentPrice, "Paper Take-Profit triggered (Price: $currentPrice >= TP: $tp)")
                }
            } else {
                if (sl != null && currentPrice >= sl) {
                    exitPosition(pair, currentPrice, "Paper Stop-Loss triggered (Price: $currentPrice >= SL: $sl)")
                } else if (tp != null && currentPrice <= tp) {
                    exitPosition(pair, currentPrice, "Paper Take-Profit triggered (Price: $currentPrice <= TP: $tp)")
                }
            }
        }
    }

    override suspend fun executeSignal(
        signal: Signal,
        pair: String,
        currentPrice: Double,
        quantity: Double,
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

        val notional = fillPrice * quantity
        val entryFee = notional * feeRate

        val trade = TradeEntity(
            pair = pair,
            side = side,
            entryPrice = fillPrice,
            quantity = quantity,
            leverage = leverage,
            stopLoss = signal.stopLossPrice,
            takeProfit = signal.takeProfitPrice,
            fees = entryFee,
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
                message = "Simulated $side entry on $pair at ₹$fillPrice (Qty: $quantity, Lev: ${leverage}x). Reason: ${signal.reason}"
            )
        )

        return ExecutionResult.Success(
            orderId = clientOrderId,
            message = "Simulated fill at ₹$fillPrice ($side)"
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

            val exitNotional = exitFillPrice * trade.quantity
            val exitFee = exitNotional * feeRate
            val totalFees = trade.fees + exitFee

            val pnl = PnlEngine.calculateRealizedPnl(
                side = trade.side,
                entryPrice = trade.entryPrice,
                exitPrice = exitFillPrice,
                quantity = trade.quantity,
                totalFees = totalFees
            )

            db.tradeDao().update(
                trade.copy(
                    exitPrice = exitFillPrice,
                    fees = totalFees,
                    realizedPnl = pnl,
                    status = "CLOSED",
                    exitTime = System.currentTimeMillis(),
                    exitReason = reason
                )
            )

            db.systemLogDao().insert(
                SystemLogEntity(
                    level = if (pnl >= 0) "INFO" else "WARN",
                    tag = "PAPER_EXIT",
                    message = "Simulated exit $pair at ₹$exitFillPrice. P&L: ₹$pnl. Reason: $reason"
                )
            )
        }

        return ExecutionResult.Success("closed_all", "Closed ${openTrades.size} paper positions for $pair")
    }
}
