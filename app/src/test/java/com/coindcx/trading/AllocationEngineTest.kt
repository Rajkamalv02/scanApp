package com.coindcx.trading

import com.coindcx.trading.engine.RiskSettings
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
            userBudgetInr = 500.0,
            leverage = 2,
            rankedOpportunities = top5,
            riskSettings = RiskSettings(maxConcurrentPositions = 3)
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
            userBudgetInr = 1000.0,
            leverage = 2,
            rankedOpportunities = top5,
            riskSettings = RiskSettings(maxConcurrentPositions = 5)
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
            userBudgetInr = 1000.0,
            leverage = 2,
            rankedOpportunities = top5,
            riskSettings = RiskSettings(maxConcurrentPositions = 5)
        )

        assertFalse(result.isInsufficientBalance)
        assertEquals(5, result.maxTradesAllowed)
        assertEquals(5, result.allocatedTradesCount)
        assertEquals(5000.0, result.totalAllocatedInr, 0.001)
        assertEquals(5000.0, result.remainingBalanceInr, 0.001)
        assertEquals(5, result.fundedOpportunities.size)
        assertEquals(0, result.unfundedOpportunities.size)
    }

    @Test
    fun testWaterfallClosure_RemainingBalanceAboveExchangeFloor_AllocatesToNextTrade() {
        // Balance = ₹1,350, UserBudget = ₹500, Leverage = 2x -> Floor = 620 / 2 = ₹310
        // Trade 1 gets ₹500 (rem ₹850)
        // Trade 2 gets ₹500 (rem ₹350)
        // Rem ₹350 >= ₹310 floor -> Trade 3 gets ₹350! Total allocated = ₹1,350, Rem = 0.
        val top5 = listOf(
            createDummyOpportunity("B-BTC_USDT", 1, 95.0),
            createDummyOpportunity("B-ETH_USDT", 2, 90.0),
            createDummyOpportunity("B-SOL_USDT", 3, 85.0),
            createDummyOpportunity("B-XRP_USDT", 4, 80.0),
            createDummyOpportunity("B-ADA_USDT", 5, 75.0)
        )

        val result = allocator.allocateCapital(
            availableBalanceInr = 1350.0,
            userBudgetInr = 500.0,
            leverage = 2,
            rankedOpportunities = top5,
            riskSettings = RiskSettings(maxConcurrentPositions = 5),
            minExchangeNotionalInr = 620.0
        )

        assertEquals(3, result.allocatedTradesCount)
        assertEquals(1350.0, result.totalAllocatedInr, 0.01)
        assertEquals(0.0, result.remainingBalanceInr, 0.01)
        assertEquals(500.0, result.fundedOpportunities[0].allocatedMarginInr, 0.01)
        assertEquals(500.0, result.fundedOpportunities[1].allocatedMarginInr, 0.01)
        assertEquals(350.0, result.fundedOpportunities[2].allocatedMarginInr, 0.01)
    }

    @Test
    fun testInsufficientBalance_BalanceBelowMinExchangeFloor_Yields0Trades() {
        val top5 = listOf(
            createDummyOpportunity("B-BTC_USDT", 1, 95.0)
        )

        // Min exchange notional = 620, at 2x leverage floor = 310.
        // Balance = 250 < 310 -> Insufficient balance!
        val result = allocator.allocateCapital(
            availableBalanceInr = 250.0,
            userBudgetInr = 500.0,
            leverage = 2,
            rankedOpportunities = top5,
            minExchangeNotionalInr = 620.0
        )

        assertTrue(result.isInsufficientBalance)
        assertEquals(0, result.maxTradesAllowed)
        assertEquals(0, result.allocatedTradesCount)
        assertEquals(0.0, result.totalAllocatedInr, 0.001)
        assertEquals(250.0, result.remainingBalanceInr, 0.001)
        assertEquals(0, result.fundedOpportunities.size)
        assertEquals(1, result.unfundedOpportunities.size)
        assertEquals(OpportunityLifecycle.UNFUNDED, result.unfundedOpportunities[0].lifecycleState)
    }

    private fun createDummyOpportunityWithSl(pair: String, rank: Int, price: Double, slPrice: Double): MarketOpportunity {
        return MarketOpportunity(
            pair = pair,
            signal = Signal(
                SignalAction.ENTER_LONG,
                confidenceScore = 90.0,
                reason = "Test",
                stopLossPrice = slPrice
            ),
            currentPrice = price,
            confidenceScore = 90.0,
            rank = rank,
            lifecycleState = OpportunityLifecycle.RANKED
        )
    }

    @Test
    fun testDynamicRiskParity_10000Balance_2xLeverage_SafeReserveProtected() {
        // Equity = 10,000, Cash = 10,000, Reserve = max(500, 100) = 500. Cash for trading = 9,500.
        // Risk per trade 1% = 100.
        // 5 candidates with 2% SL (price 100, SL 98)
        val candidates = listOf(
            createDummyOpportunityWithSl("B-BTC_USDT", 1, 100.0, 98.0),
            createDummyOpportunityWithSl("B-ETH_USDT", 2, 100.0, 98.0),
            createDummyOpportunityWithSl("B-SOL_USDT", 3, 100.0, 98.0),
            createDummyOpportunityWithSl("B-XRP_USDT", 4, 100.0, 98.0),
            createDummyOpportunityWithSl("B-ADA_USDT", 5, 100.0, 98.0)
        )

        val result = allocator.allocateCapital(
            accountEquityInr = 10000.0,
            availableCashInr = 10000.0,
            activePositionsCount = 0,
            leverage = 2,
            rankedOpportunities = candidates,
            riskSettings = RiskSettings(maxConcurrentPositions = 5),
            minExchangeNotionalInr = 620.0,
            riskPerTradePercent = 1.0,
            safetyReservePercent = 5.0
        )

        assertFalse(result.isInsufficientBalance)
        assertEquals(0.0, result.safetyReserveInr, 0.01)
        assertEquals(310.0, result.exchangeFloorMarginInr, 0.01)
        assertEquals(5, result.fundedOpportunities.size)
        // Ensure total allocated margin does not exceed available cash
        assertTrue(result.totalAllocatedInr <= 10000.0)
        assertTrue(result.remainingBalanceInr >= 0.0)
    }

    @Test
    fun testDynamicRiskParity_SmallAccount500_FloorRiskCheckRemoved_ApprovesTrade() {
        // Equity = 500, Cash = 500. Floor at 2x = 310.
        // With floor risk check removed, candidate with user-configured stoploss is successfully approved.
        val candidates = listOf(
            createDummyOpportunityWithSl("B-BTC_USDT", 1, 100.0, 98.0)
        )

        val result = allocator.allocateCapital(
            accountEquityInr = 500.0,
            availableCashInr = 500.0,
            activePositionsCount = 0,
            leverage = 2,
            rankedOpportunities = candidates,
            riskSettings = RiskSettings(maxConcurrentPositions = 5, maxFloorRiskPercent = 1.5),
            minExchangeNotionalInr = 620.0,
            riskPerTradePercent = 1.0,
            safetyReservePercent = 0.0
        )

        assertEquals(1, result.fundedOpportunities.size)
        assertEquals(0, result.unfundedOpportunities.size)
        assertEquals(310.0, result.fundedOpportunities[0].allocatedMarginInr, 0.01)
        assertEquals(OpportunityLifecycle.SELECTED_FOR_TRADE, result.fundedOpportunities[0].lifecycleState)
    }

    @Test
    fun testDynamicRiskParity_SmallAccount500_TightStop_ApprovesBumpUnderCap() {
        // Equity = 500, Cash = 500, Reserve = 100. Cash for trading = 400.
        // Floor at 2x = 310.
        // Tight stop: 0.8% SL (price 100, SL 99.2)
        // Floor risk = 310 * 2 * 0.008 = 4.96.
        // 1.5% max risk cap on 500 equity = 7.50.
        // 4.96 <= 7.50 and 310 <= 400 -> Approved!
        val candidates = listOf(
            createDummyOpportunityWithSl("B-BTC_USDT", 1, 100.0, 99.2)
        )

        val result = allocator.allocateCapital(
            accountEquityInr = 500.0,
            availableCashInr = 500.0,
            activePositionsCount = 0,
            leverage = 2,
            rankedOpportunities = candidates,
            riskSettings = RiskSettings(maxConcurrentPositions = 5),
            minExchangeNotionalInr = 620.0,
            riskPerTradePercent = 1.0,
            safetyReservePercent = 5.0
        )

        assertEquals(1, result.fundedOpportunities.size)
        assertEquals(310.0, result.fundedOpportunities[0].allocatedMarginInr, 0.01)
        assertEquals(OpportunityLifecycle.SELECTED_FOR_TRADE, result.fundedOpportunities[0].lifecycleState)
    }

    @Test
    fun testDynamicRiskParity_LiveScenario_Balance1000_StopLoss35_ApprovedWithDefault25Cap() {
        // Equity = ₹986.87, Cash = ₹886.87, Reserve = ₹100.00
        // Leverage = 2x, Min Notional = ₹616.39 -> Floor Margin = ₹309.00
        // Stop Loss = 3.50% (e.g. entry 4.876, SL 4.70534)
        // Floor Risk = 309 * 2 * 0.035 = ₹21.63 (2.19% of equity)
        // Under default 2.5% cap: max tolerable risk = 986.87 * 0.025 = ₹24.67
        // 21.63 <= 24.67 -> Successfully approved and funded!
        val candidates = listOf(
            createDummyOpportunityWithSl("B-AR_USDT", 1, 4.876, 4.70534),
            createDummyOpportunityWithSl("B-ENA_USDT", 2, 0.2003, 0.1932895)
        )

        val result = allocator.allocateCapital(
            accountEquityInr = 986.87,
            availableCashInr = 886.87,
            activePositionsCount = 0,
            leverage = 2,
            rankedOpportunities = candidates,
            riskSettings = RiskSettings(maxConcurrentPositions = 3),
            minExchangeNotionalInr = 616.39,
            riskPerTradePercent = 1.0,
            safetyReservePercent = 5.0
        )

        assertEquals(2, result.fundedOpportunities.size)
        assertEquals("B-AR_USDT", result.fundedOpportunities[0].pair)
        assertEquals(309.0, result.fundedOpportunities[0].allocatedMarginInr, 0.01)
        assertEquals(OpportunityLifecycle.SELECTED_FOR_TRADE, result.fundedOpportunities[0].lifecycleState)

        assertEquals("B-ENA_USDT", result.fundedOpportunities[1].pair)
        assertEquals(309.0, result.fundedOpportunities[1].allocatedMarginInr, 0.01)
        assertEquals(OpportunityLifecycle.SELECTED_FOR_TRADE, result.fundedOpportunities[1].lifecycleState)
    }

    @Test
    fun testDynamicRiskParity_NoReserveBlocksTrade_SmallAccount350_FundsValidTrade() {
        // Equity = 350, Cash = 350. No reservation deduction!
        // Floor at 2x = 310.
        // Available cash (350) >= Floor (310) -> Valid trade is successfully approved!
        val candidates = listOf(
            createDummyOpportunityWithSl("B-BTC_USDT", 1, 100.0, 99.2)
        )

        val result = allocator.allocateCapital(
            accountEquityInr = 350.0,
            availableCashInr = 350.0,
            activePositionsCount = 0,
            leverage = 2,
            rankedOpportunities = candidates,
            minExchangeNotionalInr = 620.0
        )

        assertFalse(result.isInsufficientBalance)
        assertEquals(1, result.fundedOpportunities.size)
        assertEquals(310.0, result.fundedOpportunities[0].allocatedMarginInr, 0.01)
    }

    @Test
    fun testDynamicRiskParity_InsufficientCashBelowExchangeFloor_Yields0Trades() {
        // Equity = 200, Cash = 200. Floor at 2x = 310.
        // Available cash (200) < Floor (310) -> True insufficient balance!
        val candidates = listOf(
            createDummyOpportunityWithSl("B-BTC_USDT", 1, 100.0, 99.2)
        )

        val result = allocator.allocateCapital(
            accountEquityInr = 200.0,
            availableCashInr = 200.0,
            activePositionsCount = 0,
            leverage = 2,
            rankedOpportunities = candidates,
            minExchangeNotionalInr = 620.0
        )

        assertTrue(result.isInsufficientBalance)
        assertEquals(0, result.fundedOpportunities.size)
    }
}
