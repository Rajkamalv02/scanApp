package com.coindcx.trading

import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.allocation.AllocationEngine
import com.coindcx.trading.engine.scanner.MarketOpportunity
import com.coindcx.trading.engine.scanner.OpportunityLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllocationEngineTest {

    private val allocator = AllocationEngine()

    private fun createDummyOpportunity(pair: String, rank: Int, score: Double): MarketOpportunity {
        return MarketOpportunity(
            pair = pair,
            signal = Signal(SignalAction.ENTER_LONG, confidenceScore = score, reason = "Test"),
            currentPrice = 50000.0,
            confidenceScore = score,
            rank = rank,
            lifecycleState = OpportunityLifecycle.RANKED
        )
    }

    @Test
    fun testExample1_Balance1500_Min500_YieldsTop3Trades() {
        val top5 = listOf(
            createDummyOpportunity("B-BTC_USDT", 1, 95.0),
            createDummyOpportunity("B-ETH_USDT", 2, 90.0),
            createDummyOpportunity("B-SOL_USDT", 3, 85.0),
            createDummyOpportunity("B-XRP_USDT", 4, 80.0),
            createDummyOpportunity("B-ADA_USDT", 5, 75.0)
        )

        val result = allocator.allocateCapital(
            availableBalanceInr = 1500.0,
            minMarginPerTradeInr = 500.0,
            rankedOpportunities = top5
        )

        assertFalse(result.isInsufficientBalance)
        assertEquals(3, result.maxTradesAllowed)
        assertEquals(3, result.allocatedTradesCount)
        assertEquals(1500.0, result.totalAllocatedInr, 0.001)
        assertEquals(0.0, result.remainingBalanceInr, 0.001)
        assertEquals(3, result.fundedOpportunities.size)
        assertEquals(2, result.unfundedOpportunities.size)

        assertEquals("B-BTC_USDT", result.fundedOpportunities[0].pair)
        assertEquals("B-ETH_USDT", result.fundedOpportunities[1].pair)
        assertEquals("B-SOL_USDT", result.fundedOpportunities[2].pair)
        assertEquals(OpportunityLifecycle.SELECTED_FOR_TRADE, result.fundedOpportunities[0].lifecycleState)

        assertEquals("B-XRP_USDT", result.unfundedOpportunities[0].pair)
        assertEquals(OpportunityLifecycle.UNFUNDED, result.unfundedOpportunities[0].lifecycleState)
    }

    @Test
    fun testExample2_Balance4000_Min1000_YieldsTop4Trades() {
        val top5 = listOf(
            createDummyOpportunity("B-BTC_USDT", 1, 95.0),
            createDummyOpportunity("B-ETH_USDT", 2, 90.0),
            createDummyOpportunity("B-SOL_USDT", 3, 85.0),
            createDummyOpportunity("B-XRP_USDT", 4, 80.0),
            createDummyOpportunity("B-ADA_USDT", 5, 75.0)
        )

        val result = allocator.allocateCapital(
            availableBalanceInr = 4000.0,
            minMarginPerTradeInr = 1000.0,
            rankedOpportunities = top5
        )

        assertFalse(result.isInsufficientBalance)
        assertEquals(4, result.maxTradesAllowed)
        assertEquals(4, result.allocatedTradesCount)
        assertEquals(4000.0, result.totalAllocatedInr, 0.001)
        assertEquals(0.0, result.remainingBalanceInr, 0.001)
        assertEquals(4, result.fundedOpportunities.size)
        assertEquals(1, result.unfundedOpportunities.size)
    }

    @Test
    fun testCappedAt5_Balance10000_Min1000_YieldsMax5Trades() {
        val top5 = listOf(
            createDummyOpportunity("B-BTC_USDT", 1, 95.0),
            createDummyOpportunity("B-ETH_USDT", 2, 90.0),
            createDummyOpportunity("B-SOL_USDT", 3, 85.0),
            createDummyOpportunity("B-XRP_USDT", 4, 80.0),
            createDummyOpportunity("B-ADA_USDT", 5, 75.0)
        )

        val result = allocator.allocateCapital(
            availableBalanceInr = 10000.0,
            minMarginPerTradeInr = 1000.0,
            rankedOpportunities = top5
        )

        assertFalse(result.isInsufficientBalance)
        assertEquals(10, result.maxTradesAllowed)
        assertEquals(5, result.allocatedTradesCount) // Capped at 5
        assertEquals(5000.0, result.totalAllocatedInr, 0.001)
        assertEquals(5000.0, result.remainingBalanceInr, 0.001)
        assertEquals(5, result.fundedOpportunities.size)
        assertEquals(0, result.unfundedOpportunities.size)
    }

    @Test
    fun testInsufficientBalance_Balance400_Min500_Yields0Trades() {
        val top5 = listOf(
            createDummyOpportunity("B-BTC_USDT", 1, 95.0)
        )

        val result = allocator.allocateCapital(
            availableBalanceInr = 400.0,
            minMarginPerTradeInr = 500.0,
            rankedOpportunities = top5
        )

        assertTrue(result.isInsufficientBalance)
        assertEquals(0, result.maxTradesAllowed)
        assertEquals(0, result.allocatedTradesCount)
        assertEquals(0.0, result.totalAllocatedInr, 0.001)
        assertEquals(400.0, result.remainingBalanceInr, 0.001)
        assertEquals(0, result.fundedOpportunities.size)
        assertEquals(1, result.unfundedOpportunities.size)
        assertEquals(OpportunityLifecycle.UNFUNDED, result.unfundedOpportunities[0].lifecycleState)
    }
}
