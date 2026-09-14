package com.coindcx.trading

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.*
import com.coindcx.trading.data.db.dao.OrderDao
import com.coindcx.trading.data.db.entities.OrderEntity
import com.coindcx.trading.engine.LiveExecutionEngine
import com.coindcx.trading.engine.OrderManager
import com.coindcx.trading.engine.OrderResult
import com.coindcx.trading.engine.currency.CurrencyConverter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class LiveExecutionEngineTest {

    private class FakeOrderDao : OrderDao {
        val insertedOrders = mutableListOf<OrderEntity>()
        val updatedOrders = mutableListOf<OrderEntity>()

        override suspend fun insert(order: OrderEntity) {
            insertedOrders.add(order)
        }

        override suspend fun update(order: OrderEntity) {
            updatedOrders.add(order)
        }

        override suspend fun getByClientOrderId(clientOrderId: String): OrderEntity? = null
        override suspend fun getUnknownOrders(): List<OrderEntity> = emptyList()
        override suspend fun getActiveOrders(): List<OrderEntity> = emptyList()
        override fun getPendingOrdersFlow(): Flow<List<OrderEntity>> = emptyFlow()
        override fun getAllOrdersFlow(): Flow<List<OrderEntity>> = emptyFlow()
        override suspend fun getFinalizedOrdersCount(): Int = 0
        override suspend fun getActiveOrdersCount(): Int = 0
        override suspend fun deleteFinalizedOrderByClientOrderId(clientOrderId: String): Int = 0
        override suspend fun deleteFinalizedOrdersByClientOrderIds(clientOrderIds: List<String>): Int = 0
        override suspend fun deleteAllFinalizedOrders(): Int = 0
        override suspend fun deleteFinalizedOrdersOlderThan(beforeTimestamp: Long): Int = 0
    }

    private class TestApiService : CoinDCXApiService {
        val openPositionsList = mutableListOf<FuturesPosition>()
        val openOrdersList = mutableListOf<FuturesOrder>()
        val submittedOrders = mutableListOf<CreateOrderRequest>()
        val cancelledOrderIds = mutableListOf<String>()

        override suspend fun getPositions(body: Map<String, Any>): Response<List<FuturesPosition>> {
            return Response.success(openPositionsList)
        }

        override suspend fun getOpenOrders(body: Map<String, Any>): Response<List<FuturesOrder>> {
            return Response.success(openOrdersList)
        }

        override suspend fun createOrder(request: CreateOrderRequest): Response<List<FuturesOrder>> {
            submittedOrders.add(request)
            val order = FuturesOrder(
                id = "order_12345",
                clientOrderId = request.order.clientOrderId,
                pair = request.order.pair,
                side = request.order.side,
                orderType = request.order.orderType,
                price = request.order.price ?: 100.0,
                totalQuantity = request.order.totalQuantity,
                remainingQuantity = 0.0,
                status = "filled",
                leverage = request.order.leverage.toDouble(),
                fee = 0.0,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                stopLossPrice = request.order.stopLossPrice,
                takeProfitPrice = request.order.takeProfitPrice,
                settlementCurrencyConversionPrice = 90.0
            )
            return Response.success(listOf(order))
        }

        override suspend fun cancelOrder(body: Map<String, Any>): Response<Map<String, Any>> {
            (body["id"] as? String)?.let { cancelledOrderIds.add(it) }
            return Response.success(mapOf("status" to "cancelled"))
        }

        override suspend fun getActiveInstruments(): Response<List<String>> = Response.success(emptyList())
        override suspend fun getMarketsDetails(): Response<List<Map<String, Any>>> = Response.success(emptyList())
        override suspend fun getTicker(): Response<List<Map<String, Any>>> = Response.success(emptyList())
        override suspend fun getCandles(pair: String, interval: String): Response<List<MarketCandle>> = Response.success(emptyList())
        override suspend fun getFuturesWallets(timestamp: Long): Response<List<FuturesWallet>> = Response.success(emptyList())
    }

    private fun buildPosition(
        pair: String,
        activePos: Double,
        avgPrice: Double,
        leverage: Double = 5.0
    ): FuturesPosition {
        return FuturesPosition(
            id = "pos_$pair",
            pair = pair,
            activePos = activePos,
            inactivePosBuy = 0.0,
            inactivePosSell = 0.0,
            avgPrice = avgPrice,
            liquidationPrice = 50.0,
            lockedMargin = 100.0,
            lockedUserMargin = 100.0,
            lockedOrderMargin = 0.0,
            takeProfitTrigger = 120.0,
            stopLossTrigger = 95.0,
            leverage = leverage,
            maintenanceMargin = 5.0,
            markPrice = avgPrice,
            marginType = "ISOLATED",
            settlementCurrencyAvgPrice = 90.0,
            cumulativeFundingFee = 0.0,
            marginCurrencyShortName = "INR",
            updatedAt = System.currentTimeMillis()
        )
    }

    @Test
    fun testExitPosition_WhenLongPositionOpen_SubmitsReduceOnlySellMarketOrder() = runTest {
        val fakeApi = TestApiService()
        val fakeDao = FakeOrderDao()
        val currencyConverter = CurrencyConverter(fakeApi)
        val orderManager = OrderManager(fakeApi, fakeDao, currencyConverter)
        val engine = LiveExecutionEngine(orderManager, fakeApi, currencyConverter)

        var notifiedPair: String? = null
        var notifiedPnl: Double? = null
        engine.onTradeClosed = { pair, pnl ->
            notifiedPair = pair
            notifiedPnl = pnl
        }

        // Setup open long position: 2.0 contracts @ 100.0
        fakeApi.openPositionsList.add(buildPosition("B-BTC_USDT", activePos = 2.0, avgPrice = 100.0))

        // Exit at 110.0 (+10 move)
        val result = engine.exitPosition("B-BTC_USDT", currentPrice = 110.0, reason = "TP Hit")

        // 1. Assert market closing order submitted
        assertEquals(1, fakeApi.submittedOrders.size)
        val submitted = fakeApi.submittedOrders.first().order
        assertEquals("B-BTC_USDT", submitted.pair)
        assertEquals("sell", submitted.side) // Long position closes with sell
        assertEquals("market_order", submitted.orderType)
        assertEquals(2.0, submitted.totalQuantity, 0.001)
        assertEquals(true, submitted.reduceOnly)

        // 2. Assert realized PnL computed and notified
        // (110.0 - 100.0) * 2.0 = $20.0 USDT * 90.0 rate = ₹1800.0 INR
        assertEquals("B-BTC_USDT", notifiedPair)
        assertNotNull(notifiedPnl)
        assertEquals(1800.0, notifiedPnl!!, 0.01)
        assertTrue(result is com.coindcx.trading.engine.ExecutionResult.Success)
    }

    @Test
    fun testExitPosition_WhenShortPositionOpen_SubmitsReduceOnlyBuyMarketOrder() = runTest {
        val fakeApi = TestApiService()
        val fakeDao = FakeOrderDao()
        val currencyConverter = CurrencyConverter(fakeApi)
        val orderManager = OrderManager(fakeApi, fakeDao, currencyConverter)
        val engine = LiveExecutionEngine(orderManager, fakeApi, currencyConverter)

        var notifiedPair: String? = null
        var notifiedPnl: Double? = null
        engine.onTradeClosed = { pair, pnl ->
            notifiedPair = pair
            notifiedPnl = pnl
        }

        // Setup open short position: -1.5 contracts @ 200.0
        fakeApi.openPositionsList.add(buildPosition("B-ETH_USDT", activePos = -1.5, avgPrice = 200.0))

        // Exit at 190.0 (+10 profit move on short)
        val result = engine.exitPosition("B-ETH_USDT", currentPrice = 190.0, reason = "Strategy Exit")

        // 1. Assert market closing order submitted
        assertEquals(1, fakeApi.submittedOrders.size)
        val submitted = fakeApi.submittedOrders.first().order
        assertEquals("B-ETH_USDT", submitted.pair)
        assertEquals("buy", submitted.side) // Short position closes with buy
        assertEquals("market_order", submitted.orderType)
        assertEquals(1.5, submitted.totalQuantity, 0.001)
        assertEquals(true, submitted.reduceOnly)

        // 2. Assert realized PnL computed and notified
        // (200.0 - 190.0) * 1.5 = $15.0 USDT * 90.0 rate = ₹1350.0 INR
        assertEquals("B-ETH_USDT", notifiedPair)
        assertNotNull(notifiedPnl)
        assertEquals(1350.0, notifiedPnl!!, 0.01)
        assertTrue(result is com.coindcx.trading.engine.ExecutionResult.Success)
    }

    @Test
    fun testExitPosition_WhenNoOpenContracts_OnlyCancelsWorkingOrders() = runTest {
        val fakeApi = TestApiService()
        val fakeDao = FakeOrderDao()
        val currencyConverter = CurrencyConverter(fakeApi)
        val orderManager = OrderManager(fakeApi, fakeDao, currencyConverter)
        val engine = LiveExecutionEngine(orderManager, fakeApi, currencyConverter)

        var notifiedPair: String? = null
        var notifiedPnl: Double? = null
        engine.onTradeClosed = { pair, pnl ->
            notifiedPair = pair
            notifiedPnl = pnl
        }

        // Working trigger order exists on pair, but 0 active contracts
        fakeApi.openPositionsList.add(buildPosition("B-SOL_USDT", activePos = 0.0, avgPrice = 100.0))
        fakeApi.openOrdersList.add(
            FuturesOrder(
                id = "open_ord_999",
                clientOrderId = "client_999",
                pair = "B-SOL_USDT",
                side = "buy",
                orderType = "limit_order",
                price = 100.0,
                totalQuantity = 1.0,
                remainingQuantity = 1.0,
                status = "open",
                leverage = 5.0,
                fee = 0.0,
                createdAt = 0,
                updatedAt = 0,
                stopLossPrice = null,
                takeProfitPrice = null,
                settlementCurrencyConversionPrice = null
            )
        )

        val result = engine.exitPosition("B-SOL_USDT", currentPrice = 100.0, reason = "Cancel working order")

        // No market closing order submitted
        assertEquals(0, fakeApi.submittedOrders.size)
        // Working order cancelled
        assertEquals(1, fakeApi.cancelledOrderIds.size)
        assertEquals("open_ord_999", fakeApi.cancelledOrderIds.first())
        // No PnL notified since no position was filled
        assertNull(notifiedPair)
        assertNull(notifiedPnl)
        assertTrue(result is com.coindcx.trading.engine.ExecutionResult.Success)
    }

    @Test
    fun testReapStaleEntryOrders_CancelsStaleOrdersAndResiduals() = runTest {
        val fakeApi = TestApiService()
        val fakeDao = FakeOrderDao()
        val currencyConverter = CurrencyConverter(fakeApi)
        val orderManager = OrderManager(fakeApi, fakeDao, currencyConverter)

        val now = System.currentTimeMillis()

        // 1. Stale order: 150 seconds old, unfilled (status = "open")
        fakeApi.openOrdersList.add(
            FuturesOrder(
                id = "order_stale_1",
                clientOrderId = "bot_stale_1",
                pair = "B-BTC_USDT",
                side = "buy",
                orderType = "limit_order",
                price = 50000.0,
                totalQuantity = 0.5,
                remainingQuantity = 0.5,
                status = "open",
                leverage = 5.0,
                fee = 0.0,
                createdAt = now - 150_000L, // 150s old (> 120s TTL)
                updatedAt = now - 150_000L,
                stopLossPrice = null,
                takeProfitPrice = null,
                settlementCurrencyConversionPrice = null
            )
        )

        // 2. Fresh order: 30 seconds old, unfilled
        fakeApi.openOrdersList.add(
            FuturesOrder(
                id = "order_fresh_2",
                clientOrderId = "bot_fresh_2",
                pair = "B-ETH_USDT",
                side = "buy",
                orderType = "limit_order",
                price = 3000.0,
                totalQuantity = 1.0,
                remainingQuantity = 1.0,
                status = "open",
                leverage = 5.0,
                fee = 0.0,
                createdAt = now - 30_000L, // 30s old (< 120s TTL)
                updatedAt = now - 30_000L,
                stopLossPrice = null,
                takeProfitPrice = null,
                settlementCurrencyConversionPrice = null
            )
        )

        // 3. Partial fill on pair with active position: residual must be pruned
        fakeApi.openOrdersList.add(
            FuturesOrder(
                id = "order_partial_3",
                clientOrderId = "bot_partial_3",
                pair = "B-SOL_USDT",
                side = "buy",
                orderType = "limit_order",
                price = 150.0,
                totalQuantity = 2.0,
                remainingQuantity = 1.0,
                status = "partially_filled",
                leverage = 5.0,
                fee = 0.0,
                createdAt = now - 40_000L, // 40s old, but has active position!
                updatedAt = now - 40_000L,
                stopLossPrice = null,
                takeProfitPrice = null,
                settlementCurrencyConversionPrice = null
            )
        )

        // Active positions: B-SOL_USDT has an active open position
        val activePairs = setOf("B-SOL_USDT")

        val reapedCount = orderManager.reapStaleEntryOrders(ttlMs = 120_000L, activePositionPairs = activePairs)

        // Exactly 2 orders must be reaped: stale order_1 and residual partial order_3
        assertEquals(2, reapedCount)
        assertTrue(fakeApi.cancelledOrderIds.contains("order_stale_1"))
        assertTrue(fakeApi.cancelledOrderIds.contains("order_partial_3"))
        assertFalse(fakeApi.cancelledOrderIds.contains("order_fresh_2"))
    }
}
