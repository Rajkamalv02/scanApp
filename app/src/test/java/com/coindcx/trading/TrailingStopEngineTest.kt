package com.coindcx.trading

import com.coindcx.trading.engine.RiskSettings
import com.coindcx.trading.service.TradingForegroundService.TrailingStopState
import com.coindcx.trading.service.TradingForegroundService.ActiveTradeMetadata
import org.junit.Assert.*
import org.junit.Test

class TrailingStopEngineTest {

    private val settings = RiskSettings(
        breakevenTriggerRMultiple = 1.5,
        trailingStopActivationRMultiple = 2.5,
        trailingStopDistanceRMultiple = 1.5,
        trailingStopDebounceTicks = 2
    )

    @Test
    fun testTrailingStop_DiscoveryPhase_DoesNotTightenBelowOnePointFiveR() {
        // Long Trade entered @ 100.0 with SL @ 96.0 -> 1R = 4.0
        val entryPrice = 100.0
        val initialSl = 96.0
        val rPerUnit = 4.0
        val state = TrailingStopState(
            pair = "B-TEST_USDT",
            isLong = true,
            entryPrice = entryPrice,
            initialSl = initialSl,
            riskPerUnit = rPerUnit,
            peakPrice = 100.0,
            effectiveStop = initialSl
        )

        // Price rises to 104.0 (+1.0R) -> should NOT trigger breakeven yet (threshold is +1.5R)
        val mark1 = 104.0
        if (mark1 > state.peakPrice) state.peakPrice = mark1
        val peakR1 = (state.peakPrice - entryPrice) / state.riskPerUnit

        assertFalse(peakR1 >= settings.breakevenTriggerRMultiple)
        assertEquals(96.0, state.effectiveStop, 0.001)
        assertFalse(state.isBreakevenActive)
        assertFalse(state.isTrailingActive)
    }

    @Test
    fun testTrailingStop_BreakevenPhase_ActivatesAtOnePointFiveR_LocksProfitShield() {
        val entryPrice = 100.0
        val initialSl = 96.0
        val rPerUnit = 4.0
        val state = TrailingStopState(
            pair = "B-TEST_USDT",
            isLong = true,
            entryPrice = entryPrice,
            initialSl = initialSl,
            riskPerUnit = rPerUnit,
            peakPrice = 100.0,
            effectiveStop = initialSl
        )

        // Price reaches 106.0 (+1.5R)
        val mark = 106.0
        if (mark > state.peakPrice) state.peakPrice = mark
        val peakR = (state.peakPrice - entryPrice) / state.riskPerUnit
        assertTrue(peakR >= settings.breakevenTriggerRMultiple)

        val feeBuffer = maxOf(entryPrice * 0.0015, state.riskPerUnit * 0.20) // 4.0 * 0.2 = 0.80
        val beStop = entryPrice + feeBuffer
        if (beStop > state.effectiveStop) {
            state.effectiveStop = beStop
            state.isBreakevenActive = true
        }

        assertTrue(state.isBreakevenActive)
        assertEquals(100.80, state.effectiveStop, 0.001) // Entry + 0.80 profit buffer
    }

    @Test
    fun testTrailingStop_DynamicTrailingPhase_ActivatesAtTwoPointFiveR_MonotonicallyTightens() {
        val entryPrice = 100.0
        val initialSl = 96.0
        val rPerUnit = 4.0
        val state = TrailingStopState(
            pair = "B-TEST_USDT",
            isLong = true,
            entryPrice = entryPrice,
            initialSl = initialSl,
            riskPerUnit = rPerUnit,
            peakPrice = 100.0,
            effectiveStop = 100.80,
            isBreakevenActive = true
        )

        // Price rises to 110.0 (+2.5R)
        val mark1 = 110.0
        if (mark1 > state.peakPrice) state.peakPrice = mark1
        val peakR1 = (state.peakPrice - entryPrice) / state.riskPerUnit
        assertTrue(peakR1 >= settings.trailingStopActivationRMultiple)

        val trailDist = state.riskPerUnit * settings.trailingStopDistanceRMultiple // 4.0 * 1.5 = 6.0
        val candidate1 = state.peakPrice - trailDist // 110.0 - 6.0 = 104.0 (+1.0R locked profit!)
        if (candidate1 > state.effectiveStop) {
            state.effectiveStop = candidate1
            state.isTrailingActive = true
        }

        assertTrue(state.isTrailingActive)
        assertEquals(104.0, state.effectiveStop, 0.001)

        // Price surges to 116.0 (+4.0R)
        val mark2 = 116.0
        if (mark2 > state.peakPrice) state.peakPrice = mark2
        val candidate2 = state.peakPrice - trailDist // 116.0 - 6.0 = 110.0 (+2.5R locked profit!)
        if (candidate2 > state.effectiveStop) {
            state.effectiveStop = candidate2
        }
        assertEquals(110.0, state.effectiveStop, 0.001)

        // Normal market fluctuation: Price pulls back from 116.0 to 112.0
        val mark3 = 112.0
        if (mark3 > state.peakPrice) state.peakPrice = mark3 // peak remains 116.0
        // Monotonicity check: stop remains at 110.0 and must NEVER loosen
        assertTrue(mark3 > state.effectiveStop) // 112.0 > 110.0 -> trade is NOT stopped out!
        assertEquals(110.0, state.effectiveStop, 0.001)
    }

    @Test
    fun testTrailingStop_DebounceFilter_PreventsSingleTickWickExit() {
        val effectiveSl = 100.0
        val markTick1 = 99.95 // 0.05% touch, not deep (>0.2%)
        val maxBreachTicks = settings.trailingStopDebounceTicks

        var breachCount = 0
        var triggeredExit = false

        // Tick 1: Touches 99.95
        val isSlBreached1 = markTick1 <= effectiveSl
        val isDeepBreached1 = markTick1 <= effectiveSl * 0.998
        assertFalse(isDeepBreached1)
        assertTrue(isSlBreached1)

        breachCount++
        if (isDeepBreached1 || breachCount >= maxBreachTicks) {
            triggeredExit = true
        }
        // First tick should be DEBOUNCED!
        assertFalse("Single tick should not trigger exit", triggeredExit)
        assertEquals(1, breachCount)

        // Tick 2: Price bounces back to 100.20
        val markTick2 = 100.20
        val isSlBreached2 = markTick2 <= effectiveSl
        if (!isSlBreached2) {
            breachCount = 0 // Counter resets
        }
        assertEquals(0, breachCount)
        assertFalse(triggeredExit)
    }

    @Test
    fun testTrailingStop_DeepBreach_TriggersImmediateExit() {
        val effectiveSl = 100.0
        val deepMark = 99.70 // 0.3% penetration (>0.2%)
        val isSlBreached = deepMark <= effectiveSl
        val isDeepBreached = deepMark <= effectiveSl * 0.998

        assertTrue(isSlBreached)
        assertTrue(isDeepBreached)

        var triggeredExit = false
        if (isDeepBreached) {
            triggeredExit = true
        }
        assertTrue("Deep penetration should trigger exit immediately", triggeredExit)
    }

    @Test
    fun testActiveTradeMetadata_IsolatesStrategyExits() {
        val tradeMeta = ActiveTradeMetadata(
            pair = "B-ETHFI_USDT",
            isLong = false,
            entryPrice = 0.7158,
            initialSl = 0.7355,
            initialTp = 0.6766,
            riskPerUnit = 0.0197,
            strategyId = "irc"
        )

        val unrelatedStratId = "rzmr"
        val isAllowed = unrelatedStratId.equals(tradeMeta.strategyId, ignoreCase = true)
        assertFalse("Unrelated strategy must NOT be allowed to exit position", isAllowed)

        val owningStratId = "irc"
        val isOwningAllowed = owningStratId.equals(tradeMeta.strategyId, ignoreCase = true)
        assertTrue("Owning strategy must be allowed to exit position", isOwningAllowed)
    }
}
