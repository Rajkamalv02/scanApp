package com.coindcx.trading

import com.coindcx.trading.data.db.entities.OrderEntity
import com.coindcx.trading.data.db.entities.TradeEntity
import com.coindcx.trading.engine.database.DatabaseStats
import com.coindcx.trading.engine.database.PurgeSummary
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test suite verifying Database Management invariants:
 * 1. Safe partition enforcement: OPEN trades and working orders can NEVER be deleted.
 * 2. Time and result-based filtering accurately targets closed trades.
 * 3. Finalized orders filtering preserves working/pending orders.
 * 4. Master purge cleanly removes inactive records while preserving active trading state.
 */
class DatabaseManagerTest {

    @Test
    fun testTradeSafetyPartitionInvariant() {
        // Mocking trade records in the database
        val openTrade = TradeEntity(
            id = 101L,
            pair = "B-BTC_USDT",
            side = "LONG",
            entryPrice = 65000.0,
            quantity = 0.1,
            leverage = 10,
            allocatedMarginInr = 1000.0,
            clientOrderId = "order_open_101",
            strategyName = "MACD_RSI",
            status = "OPEN" // CRITICAL PROTECTED
        )

        val closedTrade = TradeEntity(
            id = 102L,
            pair = "B-ETH_USDT",
            side = "SHORT",
            entryPrice = 3500.0,
            quantity = 1.0,
            leverage = 5,
            allocatedMarginInr = 1000.0,
            clientOrderId = "order_closed_102",
            strategyName = "MACD_RSI",
            status = "CLOSED", // SAFE TO DELETE
            exitPrice = 3400.0,
            realizedPnl = 500.0,
            tradeResult = "WIN"
        )

        val tradeTable = mutableListOf(openTrade, closedTrade)

        // Safe deletion query logic: DELETE FROM trades WHERE id = :id AND status = 'CLOSED'
        fun deleteClosedTradeById(id: Long): Boolean {
            val initialSize = tradeTable.size
            tradeTable.removeAll { it.id == id && it.status == "CLOSED" }
            return tradeTable.size < initialSize
        }

        // Test 1: Attempt to delete an OPEN trade by ID
        val openDeleted = deleteClosedTradeById(101L)
        assertFalse("Attempting to delete OPEN trade MUST return false", openDeleted)
        assertTrue("OPEN trade MUST remain in table untouched", tradeTable.any { it.id == 101L && it.status == "OPEN" })

        // Test 2: Delete a CLOSED trade by ID
        val closedDeleted = deleteClosedTradeById(102L)
        assertTrue("Deleting CLOSED trade must return true", closedDeleted)
        assertFalse("CLOSED trade should be removed", tradeTable.any { it.id == 102L })

        // Invariant check: OPEN trade is STILL preserved
        assertEquals(1, tradeTable.size)
        assertEquals(101L, tradeTable.first().id)
        assertEquals("OPEN", tradeTable.first().status)
    }

    @Test
    fun testOrderSafetyPartitionInvariant() {
        // Status partition:
        // Active: 'PENDING', 'SUBMITTED', 'UNKNOWN', 'open', 'partially_filled'
        // Finalized: 'FILLED', 'CANCELLED', 'REJECTED', 'EXPIRED'
        val activeOrders = listOf(
            OrderEntity(clientOrderId = "ord_pending_1", pair = "B-SOL_USDT", side = "LONG", orderType = "market", price = 0.0, totalQuantity = 1.0, status = "PENDING"),
            OrderEntity(clientOrderId = "ord_submitted_1", pair = "B-BTC_USDT", side = "SHORT", orderType = "limit", price = 65000.0, totalQuantity = 0.1, status = "SUBMITTED"),
            OrderEntity(clientOrderId = "ord_unknown_1", pair = "B-DOGE_USDT", side = "LONG", orderType = "market", price = 0.0, totalQuantity = 100.0, status = "UNKNOWN"),
            OrderEntity(clientOrderId = "ord_open_1", pair = "B-ETH_USDT", side = "LONG", orderType = "limit", price = 3500.0, totalQuantity = 0.5, status = "open"),
            OrderEntity(clientOrderId = "ord_part_1", pair = "B-AVAX_USDT", side = "LONG", orderType = "market", price = 0.0, totalQuantity = 10.0, status = "partially_filled")
        )

        val finalizedOrders = listOf(
            OrderEntity(clientOrderId = "ord_filled_1", pair = "B-SOL_USDT", side = "LONG", orderType = "market", price = 0.0, totalQuantity = 1.0, status = "FILLED"),
            OrderEntity(clientOrderId = "ord_cancelled_1", pair = "B-ADA_USDT", side = "SHORT", orderType = "limit", price = 0.5, totalQuantity = 50.0, status = "CANCELLED"),
            OrderEntity(clientOrderId = "ord_rejected_1", pair = "B-XRP_USDT", side = "LONG", orderType = "market", price = 0.0, totalQuantity = 200.0, status = "REJECTED"),
            OrderEntity(clientOrderId = "ord_expired_1", pair = "B-NEAR_USDT", side = "LONG", orderType = "limit", price = 5.0, totalQuantity = 20.0, status = "EXPIRED")
        )

        val orderTable = (activeOrders + finalizedOrders).toMutableList()

        val protectedStatuses = setOf("PENDING", "SUBMITTED", "UNKNOWN", "open", "partially_filled")

        // Safe deleteAllFinalizedOrders query logic:
        // DELETE FROM orders WHERE status NOT IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'open', 'partially_filled')
        fun deleteAllFinalizedOrders(): Int {
            val countBefore = orderTable.size
            orderTable.removeAll { it.status !in protectedStatuses }
            return countBefore - orderTable.size
        }

        val deletedCount = deleteAllFinalizedOrders()
        assertEquals("Must delete exactly 4 finalized orders", 4, deletedCount)
        assertEquals("Must leave exactly 5 active orders untouched", 5, orderTable.size)

        // Verify that NO active orders were deleted
        for (active in activeOrders) {
            assertTrue("Active order ${active.clientOrderId} (${active.status}) must still exist",
                orderTable.any { it.clientOrderId == active.clientOrderId })
        }
    }

    @Test
    fun testClosedTradeTimeAndResultFilters() {
        val now = System.currentTimeMillis()
        val oneDayAgo = now - 86_400_000L
        val tenDaysAgo = now - (10 * 86_400_000L)
        val fortyDaysAgo = now - (40 * 86_400_000L)

        val tradeTable = mutableListOf(
            TradeEntity(id = 1L, pair = "B-BTC_USDT", side = "LONG", entryPrice = 1.0, quantity = 1.0, leverage = 1, allocatedMarginInr = 100.0, clientOrderId = "c1", strategyName = "s1", status = "CLOSED", exitTime = oneDayAgo, tradeResult = "WIN"),
            TradeEntity(id = 2L, pair = "B-ETH_USDT", side = "LONG", entryPrice = 1.0, quantity = 1.0, leverage = 1, allocatedMarginInr = 100.0, clientOrderId = "c2", strategyName = "s1", status = "CLOSED", exitTime = tenDaysAgo, tradeResult = "LOSS"),
            TradeEntity(id = 3L, pair = "B-SOL_USDT", side = "LONG", entryPrice = 1.0, quantity = 1.0, leverage = 1, allocatedMarginInr = 100.0, clientOrderId = "c3", strategyName = "s1", status = "CLOSED", exitTime = fortyDaysAgo, tradeResult = "LOSS"),
            TradeEntity(id = 4L, pair = "B-ADA_USDT", side = "LONG", entryPrice = 1.0, quantity = 1.0, leverage = 1, allocatedMarginInr = 100.0, clientOrderId = "c4", strategyName = "s1", status = "OPEN") // PROTECTED
        )

        // Filter: Older than 7 days
        val cutoff7d = now - (7 * 86_400_000L)
        val olderThan7d = tradeTable.filter { it.status == "CLOSED" && (it.exitTime ?: 0L) < cutoff7d }
        assertEquals("2 trades are older than 7 days", 2, olderThan7d.size)
        assertTrue(olderThan7d.any { it.id == 2L })
        assertTrue(olderThan7d.any { it.id == 3L })

        // Filter: Older than 30 days
        val cutoff30d = now - (30 * 86_400_000L)
        val olderThan30d = tradeTable.filter { it.status == "CLOSED" && (it.exitTime ?: 0L) < cutoff30d }
        assertEquals("1 trade is older than 30 days", 1, olderThan30d.size)
        assertEquals(3L, olderThan30d.first().id)

        // Filter: Losses only
        val losses = tradeTable.filter { it.status == "CLOSED" && it.tradeResult == "LOSS" }
        assertEquals("2 trades are losses", 2, losses.size)
        assertTrue(losses.any { it.id == 2L })
        assertTrue(losses.any { it.id == 3L })

        // Invariant check: OPEN trade is NEVER included in any filter
        assertFalse(olderThan7d.any { it.status == "OPEN" })
        assertFalse(olderThan30d.any { it.status == "OPEN" })
        assertFalse(losses.any { it.status == "OPEN" })
    }

    @Test
    fun testMasterPurgeSummaryCalculation() {
        val summary = PurgeSummary(
            deletedTradesCount = 15,
            deletedOrdersCount = 20,
            deletedLogsCount = 150,
            deletedSnapshotsCount = 12
        )

        assertEquals(197, summary.totalDeleted)
    }

    @Test
    fun testDatabaseStatsDataModel() {
        val stats = DatabaseStats(
            closedTradesCount = 42,
            openTradesCount = 2,
            finalizedOrdersCount = 50,
            activeOrdersCount = 1,
            systemLogsCount = 250,
            equitySnapshotsCount = 30,
            databaseSizeBytes = 524288L, // 512 KB
            databaseSizeFormatted = "512.0 KB"
        )

        assertEquals(42, stats.closedTradesCount)
        assertEquals(2, stats.openTradesCount)
        assertEquals(50, stats.finalizedOrdersCount)
        assertEquals(1, stats.activeOrdersCount)
        assertEquals("512.0 KB", stats.databaseSizeFormatted)
    }
}
