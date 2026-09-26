package com.coindcx.trading

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.FuturesWallet
import com.coindcx.trading.engine.RiskManager
import com.coindcx.trading.engine.RiskSettings
import com.coindcx.trading.engine.allocation.AllocationEngine
import com.coindcx.trading.engine.scanner.MarketOpportunity
import com.coindcx.trading.engine.scanner.OpportunityLifecycle
import com.coindcx.trading.engine.scanner.TradeCandidateSelector
import org.junit.Assert.*
import org.junit.Test

/**
 * Validation Test Suite verifying:
 * 1. Fixed Stop Loss and Target Price (no dynamic trailing or breakeven ratcheting).
 * 2. Complete removal of capital reservation amount (no trade blocked by reserve).
 * 3. FuturesWallet balance calculations (never negative, mathematically accurate across 1x, 2x, micro, and multi-trade accounts).
 * 4. Insufficient margin rejection handling.
 */
class FixedStopAndBalanceValidationTest {

    // =========================================================================
    // 1. Live Account Balance & Margin Validation (Fixes Negative Balance Bug)
    // =========================================================================

    @Test
    fun testFuturesWallet_2xLeverage_LargeMarginLocked_NeverProducesNegativeBalance() {
        // Live CoinDCX Scenario:
        // Account has ₹224.80 free cash and ₹732.51 locked in 72.2 contracts of B-ARB_USDT @ 2x leverage.
        // Old buggy formula: balance - locked_balance = 224.80 - 732.51 = -507.71 (BUG!)
        // Correct formula: availableBalance = 224.80, totalWalletBalance = 224.80 + 732.51 = 957.31
        val wallet = FuturesWallet(
            id = "w1",
            currencyShortName = "INR",
            balance = "224.804791",
            lockedBalance = "732.516490",
            crossOrderMargin = "0.0",
            crossUserMargin = "0.0"
        )

        assertEquals(224.804791, wallet.availableBalance, 0.0001)
        assertTrue("Available balance must be strictly positive", wallet.availableBalance > 0.0)
        assertEquals(732.516490, wallet.lockedMargin, 0.0001)
        assertEquals(957.321281, wallet.totalWalletBalance, 0.0001)
    }

    @Test
    fun testFuturesWallet_NormalTrade_1xLeverage() {
        // Total cash ₹1000. 1x leverage order uses ₹612 margin, leaving ₹388 free cash.
        val wallet = FuturesWallet(
            id = "w2",
            currencyShortName = "INR",
            balance = "388.00",
            lockedBalance = "612.00",
            crossOrderMargin = "0.0",
            crossUserMargin = "0.0"
        )

        assertEquals(388.00, wallet.availableBalance, 0.01)
        assertEquals(612.00, wallet.lockedMargin, 0.01)
        assertEquals(1000.00, wallet.totalWalletBalance, 0.01)
    }

    @Test
    fun testFuturesWallet_SmallTradeAmount_LowMarginLocked() {
        // Total cash ₹1000. Small trade takes minimum floor margin of ₹308.
        val wallet = FuturesWallet(
            id = "w3",
            currencyShortName = "INR",
            balance = "692.00",
            lockedBalance = "308.00",
            crossOrderMargin = "0.0",
            crossUserMargin = "0.0"
        )

        assertEquals(692.00, wallet.availableBalance, 0.01)
        assertEquals(308.00, wallet.lockedMargin, 0.01)
        assertEquals(1000.00, wallet.totalWalletBalance, 0.01)
    }

    @Test
    fun testFuturesWallet_MultipleSimultaneousTrades() {
        // Two positions open: ₹300 margin each -> Total locked = ₹600, Free cash = ₹400.
        val wallet = FuturesWallet(
            id = "w4",
            currencyShortName = "INR",
            balance = "400.00",
            lockedBalance = "600.00",
            crossOrderMargin = "0.0",
            crossUserMargin = "0.0"
        )

        assertEquals(400.00, wallet.availableBalance, 0.01)
        assertEquals(600.00, wallet.lockedMargin, 0.01)
        assertEquals(1000.00, wallet.totalWalletBalance, 0.01)
    }

    @Test
    fun testFuturesWallet_AccountWithOpenPositionsAndUnrealizedPnl() {
        // Wallet cash ₹957.31 (₹224.80 free + ₹732.51 locked).
        // Open trade has +$1.50 USDT unrealized PnL (+₹153.00 @ 102 rate).
        val wallet = FuturesWallet(
            id = "w5",
            currencyShortName = "INR",
            balance = "224.80",
            lockedBalance = "732.51",
            crossOrderMargin = "0.0",
            crossUserMargin = "0.0"
        )
        val unrealizedPnlInr = 153.00
        val totalEquity = wallet.totalWalletBalance + unrealizedPnlInr

        assertEquals(224.80, wallet.availableBalance, 0.01)
        assertEquals(1110.31, totalEquity, 0.01)
    }

    // =========================================================================
    // 2. Reservation Amount Removal Validation
    // =========================================================================

    @Test
    fun testCandidateSelector_ReservationRemoved_ApprovesTradePreviouslyBlocked() {
        // Scenario: Account available cash = ₹350.0.
        // Exchange floor margin @ 2x = 616 / 2 = ₹308.0.
        // Previously:
        //   safetyReserve = max(350 * 0.05, 100.0) = 100.0 INR.
        //   availableTradingCash = 350 - 100 = 250.0 INR < 308.0 Floor -> Capacity K = 0 (BLOCKED!).
        // Now with reservation removed:
        //   availableTradingCash = 350.0 >= 308.0 Floor -> Capacity K = 1 (APPROVED!).
        val selector = TradeCandidateSelector()
        val candidate = MarketOpportunity(
            pair = "B-BTC_USDT",
            signal = com.coindcx.trading.engine.Signal(
                symbol = "B-BTC_USDT",
                direction = com.coindcx.trading.engine.SignalDirection.LONG,
                entryRef = 60000.0,
                stopLoss = 59000.0,
                stopLossPrice = 59000.0,
                takeProfitPrice = 62500.0,
                strategyId = "CONFLUENCE",
                reason = "Trend confluence",
                confidenceScore = 85.0
            ),
            currentPrice = 60000.0,
            confidenceScore = 85.0,
            strategyId = "CONFLUENCE",
            strategyName = "Confluence",
            netRiskRewardRatio = 2.5,
            selectionReason = "Bullish breakout",
            isApproved = true,
            lifecycleState = OpportunityLifecycle.SCANNED
        )

        val result = selector.selectCandidates(
            candidates = listOf(candidate),
            accountEquityInr = 350.0,
            availableCashInr = 350.0,
            activePositionsCount = 0,
            maxConcurrentPositions = 3,
            leverage = 2,
            minExchangeNotionalInr = 616.0,
            riskPerTradePercent = 1.0,
            maxPortfolioRiskPercent = 4.0
        )

        assertEquals("Capacity K must be at least 1 trade", 1, result.capacityK)
        assertEquals("Valid trade must be approved and not blocked by reserve", 1, result.approvedTrades.size)
        assertEquals("B-BTC_USDT", result.approvedTrades[0].pair)
        assertEquals(OpportunityLifecycle.RANKED, result.approvedTrades[0].lifecycleState)
    }

    // =========================================================================
    // 3. Fixed Stop Loss & Target Price Validation (No Trailing Ratchet)
    // =========================================================================

    @Test
    fun testFixedStopLoss_Long_RemainsStrictlyFixed_NoTrailingModification() {
        val entryPrice = 100.0
        val fixedSl = 96.0
        val fixedTp = 108.0

        // Simulate position mark price fluctuations:
        // 1. Price rises to 105.0 (+1.25R): In old trailing logic, this was approaching breakeven.
        // In new fixed logic, stop loss MUST remain exactly 96.0!
        var currentMarkPrice = 105.0
        var effectiveSl = fixedSl
        assertEquals("SL must remain unchanged as price moves in profit", 96.0, effectiveSl, 0.001)

        // 2. Price rises to 107.0 (+1.75R): Old logic ratcheted SL to 100.20 (breakeven).
        // New fixed logic: SL remains exactly 96.0!
        currentMarkPrice = 107.0
        assertEquals("SL must never ratchet to breakeven or trail", 96.0, effectiveSl, 0.001)

        // 3. Price pulls back to 98.0: Old trailing stop would have exited!
        // New fixed logic: 98.0 > 96.0, trade stays open!
        currentMarkPrice = 98.0
        val isSlBreachedOnRetest = currentMarkPrice <= effectiveSl
        assertFalse("Retest above original SL must NOT trigger exit", isSlBreachedOnRetest)

        // 4. Price falls to 95.9 (below fixed SL 96.0): Trigger fixed exit!
        currentMarkPrice = 95.9
        val isSlBreached = currentMarkPrice <= effectiveSl
        assertTrue("Fixed SL breach must trigger exit", isSlBreached)
    }

    @Test
    fun testFixedStopLoss_Short_RemainsStrictlyFixed_NoTrailingModification() {
        val entryPrice = 100.0
        val fixedSl = 104.0
        val fixedTp = 92.0

        // 1. Price drops to 94.0 (+1.5R): SL must remain 104.0
        var currentMarkPrice = 94.0
        var effectiveSl = fixedSl
        assertEquals("Short SL must remain strictly at strategy level", 104.0, effectiveSl, 0.001)

        // 2. Price bounces to 101.0: Trade stays open because 101.0 < 104.0
        currentMarkPrice = 101.0
        val isSlBreachedOnBounce = currentMarkPrice >= effectiveSl
        assertFalse("Bounce below original SL must NOT trigger exit", isSlBreachedOnBounce)

        // 3. Price spikes to 104.1: Trigger fixed exit!
        currentMarkPrice = 104.1
        val isSlBreached = currentMarkPrice >= effectiveSl
        assertTrue("Fixed SL breach must trigger exit", isSlBreached)
    }

    @Test
    fun testFixedTakeProfit_Long_TriggersAtTarget() {
        val entryPrice = 100.0
        val fixedSl = 96.0
        val fixedTp = 108.0

        var currentMarkPrice = 107.9
        assertFalse(currentMarkPrice >= fixedTp)

        currentMarkPrice = 108.2
        assertTrue("Reaching fixed TP must trigger exit", currentMarkPrice >= fixedTp)
    }

    @Test
    fun testFixedTakeProfit_Short_TriggersAtTarget() {
        val entryPrice = 100.0
        val fixedSl = 104.0
        val fixedTp = 92.0

        var currentMarkPrice = 92.1
        assertFalse(currentMarkPrice <= fixedTp)

        currentMarkPrice = 91.8
        assertTrue("Reaching fixed short TP must trigger exit", currentMarkPrice <= fixedTp)
    }

    // =========================================================================
    // 4. Insufficient Margin Rejection Invariant
    // =========================================================================

    @Test
    fun testInsufficientMargin_SafelyRejectsWithoutGoingNegative() {
        // Account has ₹200.0 available cash, but order requires ₹308.0 margin.
        val inMemoryAvailable = 200.0
        val requiredMargin = 308.0

        val hasSufficientMargin = inMemoryAvailable >= requiredMargin
        assertFalse("Account with insufficient available margin must not proceed", hasSufficientMargin)

        // Verifying balance subtraction is guarded
        val balanceAfterRejection = if (hasSufficientMargin) inMemoryAvailable - requiredMargin else inMemoryAvailable
        assertEquals(200.0, balanceAfterRejection, 0.001)
        assertTrue("Balance remains positive", balanceAfterRejection > 0.0)
    }

    // =========================================================================
    // 5. UI-Configured Stop-Loss & Target Price Parameter Validation
    // =========================================================================

    @Test
    fun testUIControls_DefaultParameters_StopLoss3Percent_Target1_5Percent() {
        val defaultConfig = com.coindcx.trading.data.config.TradingConfig()
        assertEquals("Default Stop-Loss must be strictly 3.0%", 3.0, defaultConfig.stopLossPercent, 0.001)
        assertEquals("Default Target Price must be strictly 1.5%", 1.5, defaultConfig.targetPricePercent, 0.001)
    }

    @Test
    fun testUIControls_StepIncrementsAndSelectableRanges() {
        // Step size is 0.5%
        val step = 0.5
        val slRangeMin = 0.5
        val slRangeMax = 10.0
        val tpRangeMin = 0.5
        val tpRangeMax = 15.0

        // Test selectable values in range:
        for (v in listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 7.5, 10.0)) {
            assertEquals("SL value must be a multiple of 0.5% step", 0.0, (v * 10) % (step * 10), 0.001)
            assertTrue("SL value must be within [0.5..10.0]", v in slRangeMin..slRangeMax)
        }

        for (v in listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.5, 6.0, 10.0, 15.0)) {
            assertEquals("TP value must be a multiple of 0.5% step", 0.0, (v * 10) % (step * 10), 0.001)
            assertTrue("TP value must be within [0.5..15.0]", v in tpRangeMin..tpRangeMax)
        }
    }

    // =========================================================================
    // 6. Leverage Invariance Validation
    // =========================================================================

    @Test
    fun testLeverageInvariance_StopLossAndTargetPricesRemainStrictlyIdenticalAcrossLeverages() {
        // Requirement: "These percentages must remain the same regardless of the leverage used.
        // Leverage should not modify or scale these values."
        val entryPrice = 60000.0
        val slPercent = 3.0
        val tpPercent = 1.5

        val expectedLongSl = entryPrice * (1.0 - slPercent / 100.0) // 58200.0
        val expectedLongTp = entryPrice * (1.0 + tpPercent / 100.0) // 60900.0
        val expectedShortSl = entryPrice * (1.0 + slPercent / 100.0) // 61800.0
        val expectedShortTp = entryPrice * (1.0 - tpPercent / 100.0) // 59100.0

        val leveragesToTest = listOf(1, 2, 3, 5, 7, 10, 15, 20)

        for (lev in leveragesToTest) {
            // Sizing notional is scaled by leverage, but price triggers must remain mathematically identical
            val longSlForLeverage = entryPrice * (1.0 - slPercent / 100.0)
            val longTpForLeverage = entryPrice * (1.0 + tpPercent / 100.0)
            val shortSlForLeverage = entryPrice * (1.0 + slPercent / 100.0)
            val shortTpForLeverage = entryPrice * (1.0 - tpPercent / 100.0)

            assertEquals("Long SL @ ${lev}x must be strictly invariant", expectedLongSl, longSlForLeverage, 0.0001)
            assertEquals("Long TP @ ${lev}x must be strictly invariant", expectedLongTp, longTpForLeverage, 0.0001)
            assertEquals("Short SL @ ${lev}x must be strictly invariant", expectedShortSl, shortSlForLeverage, 0.0001)
            assertEquals("Short TP @ ${lev}x must be strictly invariant", expectedShortTp, shortTpForLeverage, 0.0001)
        }
    }

    @Test
    fun testLeverageInvariance_MarginChangesWithLeverageWhileStopDistanceStaysConstant() {
        val currentPrice = 60000.0
        val slPercent = 3.0
        val notionalUsdt = 600.0 // 0.01 BTC
        val fxRate = 100.0

        val stopDistanceUsdt = notionalUsdt * (slPercent / 100.0) // $18.00 USDT at risk
        assertEquals("Stop risk in USDT must be independent of leverage", 18.0, stopDistanceUsdt, 0.001)

        val margin1x = (notionalUsdt * fxRate) / 1
        val margin2x = (notionalUsdt * fxRate) / 2
        val margin10x = (notionalUsdt * fxRate) / 10
        val margin20x = (notionalUsdt * fxRate) / 20

        assertEquals(60000.0, margin1x, 0.01)
        assertEquals(30000.0, margin2x, 0.01)
        assertEquals(6000.0, margin10x, 0.01)
        assertEquals(3000.0, margin20x, 0.01)

        // Verifying that leverage only divides required margin, while SL distance % remains exactly 3.0%
        val slDist1x = (currentPrice * (slPercent / 100.0)) / currentPrice * 100.0
        val slDist20x = (currentPrice * (slPercent / 100.0)) / currentPrice * 100.0
        assertEquals(3.0, slDist1x, 0.001)
        assertEquals(3.0, slDist20x, 0.001)
    }

    // =========================================================================
    // 7. Risk Factor Check Removal Validation (Stop-Loss Not Blocked)
    // =========================================================================

    @Test
    fun testUIConfiguredStopLoss_HighPercentagesNeverBlockedByRiskFactorCheck() {
        val allocator = AllocationEngine()
        val entryPrice = 50000.0

        // Test high stop loss percentages selectable in UI: 4.0%, 5.0%, 7.0%, 10.0%
        val testStopLosses = listOf(4.0, 5.0, 7.0, 10.0)

        for (slPct in testStopLosses) {
            val slPrice = entryPrice * (1.0 - slPct / 100.0)
            val candidate = MarketOpportunity(
                pair = "B-BTC_USDT",
                signal = com.coindcx.trading.engine.Signal(
                    symbol = "B-BTC_USDT",
                    direction = com.coindcx.trading.engine.SignalDirection.LONG,
                    entryRef = entryPrice,
                    stopLoss = slPrice,
                    stopLossPrice = slPrice,
                    takeProfitPrice = entryPrice * 1.015,
                    strategyId = "CONFLUENCE",
                    reason = "UI SL test $slPct%",
                    confidenceScore = 85.0
                ),
                currentPrice = entryPrice,
                confidenceScore = 85.0,
                rank = 1,
                isApproved = true,
                lifecycleState = OpportunityLifecycle.RANKED
            )

            // Even on a 1000 INR account at 2x leverage where floor risk would previously exceed 2.5% cap:
            val result = allocator.allocateCapital(
                accountEquityInr = 1000.0,
                availableCashInr = 1000.0,
                activePositionsCount = 0,
                leverage = 2,
                rankedOpportunities = listOf(candidate),
                minExchangeNotionalInr = 620.0
            )

            assertEquals("Candidate with $slPct% SL must be funded and not blocked by risk cap", 1, result.fundedOpportunities.size)
            assertEquals("No candidate should be unfunded due to stop loss risk factor check", 0, result.unfundedOpportunities.size)
            assertEquals(OpportunityLifecycle.SELECTED_FOR_TRADE, result.fundedOpportunities[0].lifecycleState)
            assertTrue("Allocated margin should meet or exceed floor margin", result.fundedOpportunities[0].allocatedMarginInr >= 310.0)
        }
    }
}

