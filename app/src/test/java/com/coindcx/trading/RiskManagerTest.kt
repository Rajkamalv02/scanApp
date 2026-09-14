package com.coindcx.trading

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.engine.RiskCheckResult
import com.coindcx.trading.engine.RiskManager
import com.coindcx.trading.engine.RiskSettings
import com.coindcx.trading.engine.SizingResult
import org.junit.Assert.*
import org.junit.Test

class RiskManagerTest {

    private val riskManager = RiskManager(
        RiskSettings(
            riskPerTradePercent = 1.0,
            maxLeverage = 20,
            maxDailyLossPercent = 4.0,
            maxConcurrentPositions = 3,
            maxDirectionalPositions = 2,
            consecutiveLossLimit = 3,
            consecutiveLossCooldownMinutes = 90L,
            liquidationBufferMultiplier = 1.25
        )
    )

    private fun createPosition(pair: String, isLong: Boolean): FuturesPosition {
        return FuturesPosition(
            id = "pos_$pair",
            pair = pair,
            activePos = if (isLong) 1.0 else -1.0,
            inactivePosBuy = 0.0,
            inactivePosSell = 0.0,
            avgPrice = 100.0,
            liquidationPrice = 0.0,
            lockedMargin = 500.0,
            lockedUserMargin = 500.0,
            lockedOrderMargin = 0.0,
            takeProfitTrigger = null,
            stopLossTrigger = null,
            leverage = 2.0,
            maintenanceMargin = null,
            markPrice = 100.0,
            marginType = "ISOLATED",
            settlementCurrencyAvgPrice = null,
            cumulativeFundingFee = null,
            marginCurrencyShortName = "INR",
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Case 1: Floor > Cap at 1x leverage triggers auto-bump to 2x (liquidation safe).
     * Balance: 10,000, 1% Risk = 100, SL: 5%, Budget: 500, MinNotional: 615.
     * At 1x: Floor = 615 > 500. Auto-bump to 2x: Floor = 307.50 <= 500.
     * Ideal Margin = 2000 / 2 = 1,000. Clamped to budget cap: 500.0 INR.
     */
    @Test
    fun testCase1_FloorExceedsCap_AutoBumpsLeverage_ClampsToBudget() {
        val result = riskManager.calculateRiskSizedMargin(
            balanceInr = 10000.0,
            entryPrice = 100.0,
            stopLossPrice = 95.0,
            requestedLeverage = 1,
            userBudgetInr = 500.0,
            minOrderNotionalInr = 615.0
        )
        assertTrue(result is SizingResult.Sized)
        val sized = result as SizingResult.Sized
        assertEquals(2, sized.effectiveLeverage)
        assertEquals(500.0, sized.allocatedMarginInr, 0.01)
        assertEquals(1000.0, sized.notionalInr, 0.01)
        assertFalse(sized.isAdjustedForExchangeFloor)
    }

    /**
     * Case 2: Exact Risk Parity Hit.
     * Balance: 10,000, 1% Risk = 100, SL: 10%, Budget: 500, MinNotional: 615, Leverage: 2x.
     * Ideal Notional = 100 / 0.10 = 1,000. Ideal Margin = 1000 / 2 = 500.
     * Clamped to [307.50, 500.0] -> exactly 500.0 INR.
     */
    @Test
    fun testCase2_ExactRiskParityHit() {
        val result = riskManager.calculateRiskSizedMargin(
            balanceInr = 10000.0,
            entryPrice = 100.0,
            stopLossPrice = 90.0,
            requestedLeverage = 2,
            userBudgetInr = 500.0,
            minOrderNotionalInr = 615.0
        )
        assertTrue(result is SizingResult.Sized)
        val sized = result as SizingResult.Sized
        assertEquals(2, sized.effectiveLeverage)
        assertEquals(500.0, sized.allocatedMarginInr, 0.01)
        assertEquals(1000.0, sized.notionalInr, 0.01)
        assertFalse(sized.isAdjustedForExchangeFloor)
    }

    /**
     * Case 3: Tight SL (2%) -> Ideal margin 2,500 exceeds budget cap of 500.
     * Clamps to budget ceiling 500.0 INR.
     */
    @Test
    fun testCase3_TightStopLoss_ClampsToBudgetCappingRisk() {
        val result = riskManager.calculateRiskSizedMargin(
            balanceInr = 10000.0,
            entryPrice = 100.0,
            stopLossPrice = 98.0,
            requestedLeverage = 2,
            userBudgetInr = 500.0,
            minOrderNotionalInr = 615.0
        )
        assertTrue(result is SizingResult.Sized)
        val sized = result as SizingResult.Sized
        assertEquals(2, sized.effectiveLeverage)
        assertEquals(500.0, sized.allocatedMarginInr, 0.01)
        assertEquals(1000.0, sized.notionalInr, 0.01)
    }

    /**
     * Case 4: Wide Stop Loss (25%) fails liquidation safety when leverage bump is attempted.
     * User budget = 100, MinNotional = 615 -> requires 7x leverage.
     * At 7x: Liq dist = 12.78% < 25% * 1.25 (31.25%) -> strictly Rejected!
     */
    @Test
    fun testCase4_WideStopLoss_RejectedByLiquidationSafetyGuard() {
        val result = riskManager.calculateRiskSizedMargin(
            balanceInr = 10000.0,
            entryPrice = 100.0,
            stopLossPrice = 75.0,
            requestedLeverage = 1,
            userBudgetInr = 100.0,
            minOrderNotionalInr = 615.0
        )
        assertTrue(result is SizingResult.Rejected)
        val rejected = result as SizingResult.Rejected
        assertTrue(rejected.reason.contains("too wide for required leverage"))
    }

    /**
     * Case 5: Extremely small budget where min order notional exceeds ceiling even at maxLeverage.
     * MinNotional = 615, MaxLev = 20 -> Floor = 30.75 > Budget (20.0) -> strictly Rejected!
     */
    @Test
    fun testCase5_BudgetTooSmallForMaxLeverageFloor_Rejected() {
        val result = riskManager.calculateRiskSizedMargin(
            balanceInr = 10000.0,
            entryPrice = 100.0,
            stopLossPrice = 95.0,
            requestedLeverage = 1,
            userBudgetInr = 20.0,
            minOrderNotionalInr = 615.0
        )
        assertTrue(result is SizingResult.Rejected)
        val rejected = result as SizingResult.Rejected
        assertTrue(rejected.reason.contains("exceeding"))
    }

    /**
     * Case 6: Higher Leverage (10x) scales down margin required to 200 INR while keeping risk at 100 INR.
     */
    @Test
    fun testCase6_HigherLeverage_ScalesDownMargin_MaintainsExactRisk() {
        val result = riskManager.calculateRiskSizedMargin(
            balanceInr = 10000.0,
            entryPrice = 100.0,
            stopLossPrice = 95.0,
            requestedLeverage = 10,
            userBudgetInr = 500.0,
            minOrderNotionalInr = 615.0
        )
        assertTrue(result is SizingResult.Sized)
        val sized = result as SizingResult.Sized
        assertEquals(10, sized.effectiveLeverage)
        assertEquals(200.0, sized.allocatedMarginInr, 0.01)
        assertEquals(2000.0, sized.notionalInr, 0.01)
        // Risk = 2000 * 5% = 100 INR (exact 1.0% account risk!)
    }

    @Test
    fun testPortfolioLimit_MaxConcurrentPositions() {
        val openPositions = listOf(
            createPosition("B-BTC_USDT", true),
            createPosition("B-ETH_USDT", true),
            createPosition("B-SOL_USDT", false)
        )
        val result = riskManager.checkPortfolioAndCorrelation("B-ADA_USDT", false, openPositions)
        assertTrue(result is RiskCheckResult.Rejected)
        assertTrue((result as RiskCheckResult.Rejected).reason.contains("Max concurrent positions"))
    }

    @Test
    fun testBtcCorrelation_BlockTwoAltLongsWithoutBtc() {
        val openPositions = listOf(
            createPosition("B-SOL_USDT", true)
        )

        val ethResult = riskManager.checkPortfolioAndCorrelation("B-ETH_USDT", true, openPositions)
        assertTrue(ethResult is RiskCheckResult.Rejected)
        assertTrue((ethResult as RiskCheckResult.Rejected).reason.contains("BTC correlation rule"))

        val btcResult = riskManager.checkPortfolioAndCorrelation("B-BTC_USDT", true, openPositions)
        assertTrue(btcResult is RiskCheckResult.Approved)
    }

    @Test
    fun testBtcCorrelation_AllowAltLongWhenBtcLongAlreadyHeld() {
        val openPositions = listOf(
            createPosition("B-BTC_USDT", true)
        )

        val altResult = riskManager.checkPortfolioAndCorrelation("B-ETH_USDT", true, openPositions)
        assertTrue(altResult is RiskCheckResult.Approved)
    }

    @Test
    fun testConsecutiveLosses_CooldownAndResetOnWin() {
        riskManager.resetDaily()
        riskManager.resetCooldown()

        assertFalse(riskManager.isCooldownActive())

        riskManager.recordTradeResult(-200.0, 10000.0)
        assertEquals(1, riskManager.getConsecutiveLossCount())
        assertFalse(riskManager.isCooldownActive())

        riskManager.recordTradeResult(-150.0, 10000.0)
        assertEquals(2, riskManager.getConsecutiveLossCount())
        assertFalse(riskManager.isCooldownActive())

        riskManager.recordTradeResult(-100.0, 10000.0)
        assertEquals(3, riskManager.getConsecutiveLossCount())
        assertTrue(riskManager.isCooldownActive())
        assertTrue(riskManager.getCooldownRemainingMinutes() > 0)

        riskManager.recordTradeResult(350.0, 10000.0)
        assertEquals(0, riskManager.getConsecutiveLossCount())
    }
}
