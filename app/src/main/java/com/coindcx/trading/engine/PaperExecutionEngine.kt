package com.coindcx.trading.engine

import android.content.Context
import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.OrderEntity
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.data.db.entities.TradeEntity
import com.coindcx.trading.engine.currency.CurrencyConverter
import com.coindcx.trading.engine.paper.PaperAccountManager
import com.coindcx.trading.engine.paper.PaperAnalyticsEngine
import com.coindcx.trading.engine.paper.PaperPositionManager
import com.coindcx.trading.engine.paper.PaperPositionManager.Companion.calculateEstimatedLiquidation
import java.util.UUID

class PaperExecutionEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val currencyConverter: CurrencyConverter,
    private val apiService: CoinDCXApiService
) : ExecutionEngine {

    override val isPaperTrading: Boolean = true

    val accountManager = PaperAccountManager(context, db)
    val positionManager = PaperPositionManager(apiService, db, accountManager)
    val analyticsEngine = PaperAnalyticsEngine(db, accountManager)

    var onTradeClosed: ((Double) -> Unit)? = null

    init {
        positionManager.onTradeClosed = { _, netPnl ->
            onTradeClosed?.invoke(netPnl)
        }
    }

    override suspend fun getAvailableBalanceInr(): Double {
        return accountManager.getAccountSummary().availableBalanceInr
    }

    override suspend fun getActivePosition(pair: String): FuturesPosition? {
        val currentSessionId = accountManager.getCurrentSessionId()
        val openTrade = db.tradeDao().getTradesForSession(currentSessionId)
            .find { it.status == "OPEN" && it.pair == pair } ?: return null
        val isLong = openTrade.side.equals("LONG", ignoreCase = true)
        val currentPrice = openTrade.currentPrice ?: openTrade.entryPrice

        return FuturesPosition(
            id = openTrade.clientOrderId,
            pair = openTrade.pair,
            activePos = if (isLong) openTrade.quantity else -openTrade.quantity,
            inactivePosBuy = 0.0,
            inactivePosSell = 0.0,
            avgPrice = openTrade.entryPrice,
            liquidationPrice = openTrade.estimatedLiquidationPrice ?: 0.0,
            lockedMargin = openTrade.allocatedMarginInr,
            lockedUserMargin = openTrade.allocatedMarginInr,
            lockedOrderMargin = 0.0,
            takeProfitTrigger = openTrade.takeProfit,
            stopLossTrigger = openTrade.stopLoss,
            leverage = openTrade.leverage.toDouble(),
            maintenanceMargin = PaperPositionManager.getMaintenanceMargin(openTrade.leverage) * openTrade.notionalValueInr,
            markPrice = currentPrice,
            marginType = "ISOLATED",
            settlementCurrencyAvgPrice = openTrade.entryPrice,
            cumulativeFundingFee = openTrade.fundingFees,
            marginCurrencyShortName = "INR",
            updatedAt = openTrade.entryTime
        )
    }

    override suspend fun getAllOpenPositions(): List<FuturesPosition> {
        val currentSessionId = accountManager.getCurrentSessionId()
        val openTrades = db.tradeDao().getTradesForSession(currentSessionId).filter { it.status == "OPEN" }
        return openTrades.map { trade ->
            val isLong = trade.side.equals("LONG", ignoreCase = true)
            val currentPrice = trade.currentPrice ?: trade.entryPrice
            FuturesPosition(
                id = trade.clientOrderId,
                pair = trade.pair,
                activePos = if (isLong) trade.quantity else -trade.quantity,
                inactivePosBuy = 0.0,
                inactivePosSell = 0.0,
                avgPrice = trade.entryPrice,
                liquidationPrice = trade.estimatedLiquidationPrice ?: 0.0,
                lockedMargin = trade.allocatedMarginInr,
                lockedUserMargin = trade.allocatedMarginInr,
                lockedOrderMargin = 0.0,
                takeProfitTrigger = trade.takeProfit,
                stopLossTrigger = trade.stopLoss,
                leverage = trade.leverage.toDouble(),
                maintenanceMargin = PaperPositionManager.getMaintenanceMargin(trade.leverage) * trade.notionalValueInr,
                markPrice = currentPrice,
                marginType = "ISOLATED",
                settlementCurrencyAvgPrice = trade.entryPrice,
                cumulativeFundingFee = trade.fundingFees,
                marginCurrencyShortName = "INR",
                updatedAt = trade.entryTime
            )
        }
    }

    override suspend fun refreshExchangeState(): Result<ExchangeStateSnapshot> {
        return try {
            // Run batched position monitor to refresh prices, pnl, funding
            positionManager.updateOpenPositions()
            val summary = accountManager.getAccountSummary()
            val positions = getAllOpenPositions()
            Result.success(
                ExchangeStateSnapshot(
                    availableBalanceInr = summary.availableBalanceInr,
                    openPositions = positions,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkPaperStopLossAndTakeProfit(
        @Suppress("UNUSED_PARAMETER") pair: String? = null,
        @Suppress("UNUSED_PARAMETER") currentPrice: Double? = null
    ) {
        // Handled centrally via positionManager.updateOpenPositions()
        positionManager.updateOpenPositions()
    }

    override suspend fun executeSignal(
        signal: Signal,
        pair: String,
        currentPrice: Double,
        marginInr: Double,
        leverage: Int
    ): ExecutionResult {
        val currentSession = accountManager.getCurrentSessionId()
        val clientOrderId = "paper_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

        val isBuy = signal.action == SignalAction.ENTER_LONG
        val side = if (isBuy) "LONG" else "SHORT"

        // 1. Explicit Order Placement (State: SUBMITTED)
        val initialOrder = OrderEntity(
            clientOrderId = clientOrderId,
            exchangeOrderId = "sim_$clientOrderId",
            pair = pair,
            side = side,
            orderType = "market_order",
            price = currentPrice,
            totalQuantity = 0.0,
            filledQuantity = 0.0,
            status = "SUBMITTED"
        )
        db.orderDao().insert(initialOrder)

        // 2. Simulate Fill (State: FILLED with slippage + fees)
        val fillPrice = if (isBuy) {
            currentPrice * (1.0 + PaperPositionManager.SLIPPAGE_RATE)
        } else {
            currentPrice * (1.0 - PaperPositionManager.SLIPPAGE_RATE)
        }

        val quantity = currencyConverter.convertInrMarginToContractQuantity(marginInr, leverage, fillPrice)

        val notionalInr = marginInr * leverage
        val entryFeeInr = notionalInr * PaperPositionManager.TAKER_FEE_RATE
        val estLiq = calculateEstimatedLiquidation(side, fillPrice, leverage)

        db.orderDao().update(
            initialOrder.copy(
                price = fillPrice,
                totalQuantity = quantity,
                filledQuantity = quantity,
                status = "FILLED",
                updatedAt = System.currentTimeMillis()
            )
        )

        // 3. Create Open Position in Holding (State: OPEN)
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
            strategyName = StrategyRegistry.activeStrategy.name,
            signalPrice = currentPrice,
            orderPrice = currentPrice,
            currentPrice = fillPrice,
            notionalValueInr = notionalInr,
            estimatedLiquidationPrice = estLiq,
            grossPnl = 0.0,
            fundingFees = 0.0,
            slippageRate = PaperPositionManager.SLIPPAGE_RATE,
            roiPercent = 0.0,
            timeframe = "15m",
            sessionId = currentSession
        )

        db.tradeDao().insert(trade)

        // Record initial equity snapshot
        accountManager.recordEquitySnapshot()

        db.systemLogDao().insert(
            SystemLogEntity(
                level = "INFO",
                tag = "PAPER_TRADE",
                message = "Simulated $side $pair (Margin: ₹%.0f, Lev: ${leverage}x, Qty: $quantity) @ $fillPrice. EstLiq: $estLiq. ${signal.reason}"
                    .format(marginInr)
            )
        )

        return ExecutionResult.Success(
            orderId = clientOrderId,
            message = "Simulated $side fill on $pair with ₹%.0f margin @ $fillPrice".format(marginInr)
        )
    }

    override suspend fun exitPosition(
        pair: String,
        currentPrice: Double,
        reason: String
    ): ExecutionResult {
        val currentSession = accountManager.getCurrentSessionId()
        val openTrades = db.tradeDao().getTradesForSession(currentSession)
            .filter { it.status == "OPEN" && it.pair == pair }

        if (openTrades.isEmpty()) {
            return ExecutionResult.Failed("No open paper trades found for $pair")
        }

        for (trade in openTrades) {
            positionManager.closePositionManually(trade.id, reason)
        }

        return ExecutionResult.Success("closed_all", "Closed ${openTrades.size} paper positions for $pair")
    }
}
