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
    fun testBtcCorrelation_TwoAltLongsWithoutBtc_RuleDisabled_ApprovesTrade() {
        val openPositions = listOf(
            createPosition("B-SOL_USDT", true)
        )

        // BTC correlation rule for 2 altcoins disabled: B-ETH_USDT is now Approved even without BTC
        val ethResult = riskManager.checkPortfolioAndCorrelation("B-ETH_USDT", true, openPositions)
        assertTrue(ethResult is RiskCheckResult.Approved)

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

    @Test
    fun testPersistence_SavesAndRestoresRiskState() {
        val prefs = FakeSharedPreferences()
        val manager1 = RiskManager(settings = RiskSettings(maxDailyLossPercent = 4.0, maxDailyLossInr = 500.0, enableDailyLossLimit = true))

        manager1.recordTradeResult(-200.0, 10000.0)
        manager1.recordTradeResult(-200.0, 10000.0)
        manager1.recordTradeResult(-200.0, 10000.0) // Consecutive losses = 3, today loss = 600 -> trips daily circuit breaker

        assertTrue(manager1.isCircuitBreakerTripped())
        assertTrue(manager1.isCooldownActive())
        assertEquals(3, manager1.getConsecutiveLossCount())
        assertEquals(600.0, manager1.getTodayRealizedLossInr(), 0.01)

        manager1.saveToPreferences(prefs)

        // Restore into fresh manager
        val manager2 = RiskManager(settings = RiskSettings(maxDailyLossPercent = 4.0, maxDailyLossInr = 500.0, enableDailyLossLimit = true))
        manager2.loadFromPreferences(prefs)

        assertTrue(manager2.isCircuitBreakerTripped())
        assertTrue(manager2.isCooldownActive())
        assertEquals(3, manager2.getConsecutiveLossCount())
        assertEquals(600.0, manager2.getTodayRealizedLossInr(), 0.01)
    }

    @Test
    fun testPersistence_DayRolloverResetsDailyLossAndCircuitBreaker() {
        val prefs = FakeSharedPreferences()
        val manager1 = RiskManager(settings = RiskSettings(maxDailyLossPercent = 4.0, maxDailyLossInr = 500.0, enableDailyLossLimit = true))

        manager1.recordTradeResult(-600.0, 10000.0) // Trips daily limit
        assertTrue(manager1.isCircuitBreakerTripped())
        manager1.saveToPreferences(prefs)

        // Simulate that the saved epoch day was yesterday
        val currentEpochDay = System.currentTimeMillis() / 86400000L
        prefs.edit().putLong(RiskManager.KEY_LAST_EPOCH_DAY, currentEpochDay - 1).apply()

        // Load into manager2 on the "new" day
        val manager2 = RiskManager(settings = RiskSettings(maxDailyLossPercent = 4.0, maxDailyLossInr = 500.0, enableDailyLossLimit = true))
        manager2.loadFromPreferences(prefs)

        // Daily loss and circuit breaker must be automatically reset!
        assertFalse(manager2.isCircuitBreakerTripped())
        assertEquals(0.0, manager2.getTodayRealizedLossInr(), 0.01)
        // Consecutive loss count is preserved across days until cleared or cooled down
        assertEquals(1, manager2.getConsecutiveLossCount())
    }

    @Test
    fun testPerSymbolCooldown_BlocksSymbolAfterLoss() {
        val manager = RiskManager(settings = RiskSettings(symbolLossCooldownMinutes = 30L))

        // No cooldown initially
        assertFalse(manager.isSymbolInCooldown("B-ETH_USDT"))
        val checkInitial = manager.checkPortfolioAndCorrelation(
            candidatePair = "B-ETH_USDT",
            isBuy = true,
            activePositions = emptyList(),
            btcMacroTrendIsBullish = true
        )
        assertTrue(checkInitial is RiskCheckResult.Approved)

        // Record a loss on B-ETH_USDT
        manager.recordTradeResult(realizedPnlInr = -250.0, currentBalanceInr = 10000.0, pair = "B-ETH_USDT")

        // B-ETH_USDT must now be in cooldown!
        assertTrue(manager.isSymbolInCooldown("B-ETH_USDT"))
        assertTrue(manager.getSymbolCooldownRemainingMinutes("B-ETH_USDT") > 0L)

        // Candidate check on B-ETH_USDT must be Rejected
        val checkBlocked = manager.checkPortfolioAndCorrelation(
            candidatePair = "B-ETH_USDT",
            isBuy = true,
            activePositions = emptyList(),
            btcMacroTrendIsBullish = true
        )
        assertTrue(checkBlocked is RiskCheckResult.Rejected)
        assertTrue((checkBlocked as RiskCheckResult.Rejected).reason.contains("Per-symbol cooldown active"))
    }

    @Test
    fun testPerSymbolCooldown_AllowsOtherSymbols() {
        val manager = RiskManager(settings = RiskSettings(symbolLossCooldownMinutes = 30L, consecutiveLossLimit = 3))

        // Record a single loss on B-ETH_USDT (consecutive loss count = 1, below global limit of 3)
        manager.recordTradeResult(realizedPnlInr = -200.0, currentBalanceInr = 10000.0, pair = "B-ETH_USDT")

        // B-ETH_USDT is blocked
        assertTrue(manager.isSymbolInCooldown("B-ETH_USDT"))

        // BUT B-BTC_USDT and B-SOL_USDT must remain Approved!
        assertFalse(manager.isSymbolInCooldown("B-BTC_USDT"))
        assertFalse(manager.isSymbolInCooldown("B-SOL_USDT"))

        val checkBtc = manager.checkPortfolioAndCorrelation(
            candidatePair = "B-BTC_USDT",
            isBuy = true,
            activePositions = emptyList(),
            btcMacroTrendIsBullish = true
        )
        assertTrue(checkBtc is RiskCheckResult.Approved)

        val checkSol = manager.checkPortfolioAndCorrelation(
            candidatePair = "B-SOL_USDT",
            isBuy = true,
            activePositions = emptyList(),
            btcMacroTrendIsBullish = true
        )
        assertTrue(checkSol is RiskCheckResult.Approved)
    }

    @Test
    fun testPerSymbolCooldown_CanBeClearedManually() {
        val manager = RiskManager(settings = RiskSettings(symbolLossCooldownMinutes = 30L))

        manager.recordTradeResult(realizedPnlInr = -150.0, currentBalanceInr = 10000.0, pair = "B-SOL_USDT")
        assertTrue(manager.isSymbolInCooldown("B-SOL_USDT"))

        // Reset cooldowns
        manager.resetCooldown()
        assertFalse(manager.isSymbolInCooldown("B-SOL_USDT"))
        assertEquals(0, manager.getConsecutiveLossCount())
    }

    @Test
    fun testDailyLossLimit_DisabledByDefault_NeverTripsCircuitBreaker() {
        val manager = RiskManager() // enableDailyLossLimit = false by default
        assertFalse(manager.settings.enableDailyLossLimit)

        // Record a massive loss that would ordinarily exceed 4% and ₹2000 limit
        manager.recordTradeResult(-5000.0, 10000.0)

        // Loss limit reached check must be disabled
        assertFalse(manager.isCircuitBreakerTripped())

        // Candidate check must be approved rather than rejected for daily loss limit
        val check = manager.checkPortfolioAndCorrelation("B-BTC_USDT", true, emptyList())
        assertTrue(check is RiskCheckResult.Approved)
    }

    @Test
    fun testDailyLossLimit_WhenDisabled_OverridesPersistedTrippedState() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putBoolean(RiskManager.KEY_CIRCUIT_BREAKER, true).apply()

        // When enableDailyLossLimit is false, circuit breaker must be cleared even if previously saved in prefs
        val manager = RiskManager(settings = RiskSettings(enableDailyLossLimit = false))
        manager.loadFromPreferences(prefs)

        assertFalse(manager.isCircuitBreakerTripped())
    }

    private class FakeSharedPreferences : android.content.SharedPreferences {
        val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val prefs: FakeSharedPreferences) : android.content.SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any>()
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key != null && value != null) temp[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { if (key != null && values != null) temp[key] = values; return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { if (key != null) temp.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { temp.clear(); return this }
            override fun commit(): Boolean { prefs.data.putAll(temp); return true }
            override fun apply() { prefs.data.putAll(temp) }
        }
    }
}
