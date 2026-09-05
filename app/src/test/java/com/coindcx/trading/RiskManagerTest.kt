package com.coindcx.trading

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.engine.RiskCheckResult
import com.coindcx.trading.engine.RiskManager
import com.coindcx.trading.engine.RiskSettings
import org.junit.Assert.*
import org.junit.Test

class RiskManagerTest {

    private val riskManager = RiskManager(
        RiskSettings(
            riskPerTradePercent = 1.0,
            maxLeverage = 5,
            maxDailyLossPercent = 4.0,
            maxConcurrentPositions = 3,
            maxDirectionalPositions = 2,
            consecutiveLossLimit = 3,
            consecutiveLossCooldownMinutes = 90L
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

    @Test
    fun testRiskParitySizing_OnePercentRisk() {
        // Balance = ₹10,000, 1% Risk = ₹100
        // Entry = 100.0, SL = 95.0 (5% distance)
        // Notional = 100 / 0.05 = ₹2,000
        // Leverage = 2x -> Margin = 2,000 / 2 = ₹1,000
        val sizedMargin = riskManager.calculateRiskSizedMargin(
            balanceInr = 10000.0,
            entryPrice = 100.0,
            stopLossPrice = 95.0,
            leverage = 2,
            minMarginInr = 500.0
        )
        assertEquals(1000.0, sizedMargin, 0.01)
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
        // Already holding 1 Altcoin Long (SOL) and 0 BTC Long
        val openPositions = listOf(
            createPosition("B-SOL_USDT", true)
        )

        // Attempting 2nd Altcoin Long (ETH) -> Must be rejected!
        val ethResult = riskManager.checkPortfolioAndCorrelation("B-ETH_USDT", true, openPositions)
        assertTrue(ethResult is RiskCheckResult.Rejected)
        assertTrue((ethResult as RiskCheckResult.Rejected).reason.contains("BTC correlation rule"))

        // Attempting BTC Long instead -> Allowed!
        val btcResult = riskManager.checkPortfolioAndCorrelation("B-BTC_USDT", true, openPositions)
        assertTrue(btcResult is RiskCheckResult.Approved)
    }

    @Test
    fun testBtcCorrelation_AllowAltLongWhenBtcLongAlreadyHeld() {
        // Holding BTC Long
        val openPositions = listOf(
            createPosition("B-BTC_USDT", true)
        )

        // Adding 1 Altcoin Long -> Allowed (BTC + Altcoin is valid 2-Long basket)
        val altResult = riskManager.checkPortfolioAndCorrelation("B-ETH_USDT", true, openPositions)
        assertTrue(altResult is RiskCheckResult.Approved)
    }

    @Test
    fun testConsecutiveLosses_CooldownAndResetOnWin() {
        riskManager.resetDaily()
        riskManager.resetCooldown()

        assertFalse(riskManager.isCooldownActive())

        // 1st loss
        riskManager.recordTradeResult(-200.0, 10000.0)
        assertEquals(1, riskManager.getConsecutiveLossCount())
        assertFalse(riskManager.isCooldownActive())

        // 2nd loss
        riskManager.recordTradeResult(-150.0, 10000.0)
        assertEquals(2, riskManager.getConsecutiveLossCount())
        assertFalse(riskManager.isCooldownActive())

        // 3rd consecutive loss -> Cooldown trips!
        riskManager.recordTradeResult(-100.0, 10000.0)
        assertEquals(3, riskManager.getConsecutiveLossCount())
        assertTrue(riskManager.isCooldownActive())
        assertTrue(riskManager.getCooldownRemainingMinutes() > 0)

        // Winning trade strictly resets consecutive losses to 0
        riskManager.recordTradeResult(350.0, 10000.0)
        assertEquals(0, riskManager.getConsecutiveLossCount())
    }
}
