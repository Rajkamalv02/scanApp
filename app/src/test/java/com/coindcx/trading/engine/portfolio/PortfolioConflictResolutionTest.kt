package com.coindcx.trading.engine.portfolio

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.scanner.StrategyFamily
import com.coindcx.trading.engine.telemetry.RejectionCode
import org.junit.Assert.*
import org.junit.Test

class PortfolioConflictResolutionTest {

    private fun dummySignal(
        symbol: String,
        strategyId: String,
        direction: SignalDirection
    ): Signal {
        return Signal(
            symbol = symbol,
            strategyId = strategyId,
            direction = direction,
            entryRef = 100.0,
            stopLoss = if (direction == SignalDirection.LONG) 95.0 else 105.0,
            target = Target.Fixed(tp1 = if (direction == SignalDirection.LONG) 110.0 else 90.0, plannedRR = 2.0),
            riskDistance = 5.0,
            riskPct = 5.0,
            regimeTag = RegimeTag.TREND_UP
        )
    }

    @Test
    fun `test PositionRegistry enforces single position per symbol`() {
        val registry = PositionRegistry()
        val pos = PortfolioPosition(
            symbol = "B-BTC_USDT",
            strategyId = "pbc",
            family = StrategyFamily.TREND,
            direction = SignalDirection.LONG,
            entryTimeUtc = 1_700_000_000_000L,
            entryPrice = 40000.0
        )
        registry.recordPosition(pos)

        val newSignal = dummySignal("B-BTC_USDT", "vceb", SignalDirection.LONG)
        val result = registry.evaluateSignal(newSignal)

        assertTrue("Duplicate symbol must be rejected", result is PortfolioGateResult.Rejected)
        val rejected = result as PortfolioGateResult.Rejected
        assertEquals(RejectionCode.CONFLICT_MUTUAL_EXCLUSION, rejected.code)
    }

    @Test
    fun `test PositionRegistry enforces global position concurrency cap`() {
        val limits = ExposureLimits(maxGlobalPositions = 3)
        val registry = PositionRegistry(limits)

        registry.recordPosition(PortfolioPosition("B-BTC_USDT", "pbc", StrategyFamily.TREND, SignalDirection.LONG, 1L, 100.0))
        registry.recordPosition(PortfolioPosition("B-ETH_USDT", "vceb", StrategyFamily.BREAKOUT, SignalDirection.LONG, 1L, 100.0))
        registry.recordPosition(PortfolioPosition("B-SOL_USDT", "rzmr", StrategyFamily.MEANREV, SignalDirection.SHORT, 1L, 100.0))

        assertEquals(3, registry.activeCount)

        val fourthSignal = dummySignal("B-AVAX_USDT", "sbob", SignalDirection.LONG)
        val result = registry.evaluateSignal(fourthSignal)

        assertTrue(result is PortfolioGateResult.Rejected)
        val rejected = result as PortfolioGateResult.Rejected
        assertEquals(RejectionCode.EXPOSURE_CAP_REACHED, rejected.code)
        assertTrue(rejected.reason.contains("Global position cap reached"))
    }

    @Test
    fun `test PositionRegistry enforces family concentration caps`() {
        val limits = ExposureLimits(
            maxGlobalPositions = 10,
            maxTrendPositions = 2,
            maxBreakoutPositions = 2
        )
        val registry = PositionRegistry(limits)

        // Fill 2 TREND slots (e.g. S1 PBC, S10 EDTM)
        registry.recordPosition(PortfolioPosition("B-BTC_USDT", "pbc", StrategyFamily.TREND, SignalDirection.LONG, 1L, 100.0))
        registry.recordPosition(PortfolioPosition("B-ETH_USDT", "edtm", StrategyFamily.TREND, SignalDirection.LONG, 1L, 100.0))

        // 3rd TREND signal should be rejected
        val thirdTrendSignal = dummySignal("B-SOL_USDT", "pbc", SignalDirection.LONG)
        val trendResult = registry.evaluateSignal(thirdTrendSignal)
        assertTrue("3rd trend signal must be rejected for family cap", trendResult is PortfolioGateResult.Rejected)
        assertEquals(RejectionCode.EXPOSURE_CAP_REACHED, (trendResult as PortfolioGateResult.Rejected).code)
        assertTrue(trendResult.reason.contains("Family TREND exposure cap reached"))

        // BREAKOUT signal should be permitted
        val breakoutSignal = dummySignal("B-SOL_USDT", "vceb", SignalDirection.LONG)
        val breakoutResult = registry.evaluateSignal(breakoutSignal)
        assertEquals(PortfolioGateResult.Pass, breakoutResult)
    }

    @Test
    fun `test PositionRegistry enforces directional imbalance guard`() {
        val limits = ExposureLimits(
            maxGlobalPositions = 10,
            maxDirectionalImbalance = 2 // Net Long - Net Short <= 2
        )
        val registry = PositionRegistry(limits)

        // 2 Longs open -> Net = +2
        registry.recordPosition(PortfolioPosition("B-BTC_USDT", "pbc", StrategyFamily.TREND, SignalDirection.LONG, 1L, 100.0))
        registry.recordPosition(PortfolioPosition("B-ETH_USDT", "vceb", StrategyFamily.BREAKOUT, SignalDirection.LONG, 1L, 100.0))

        // 3rd Long would push net imbalance to 3 > 2 -> Reject!
        val thirdLong = dummySignal("B-SOL_USDT", "sbob", SignalDirection.LONG)
        val longResult = registry.evaluateSignal(thirdLong)
        assertTrue(longResult is PortfolioGateResult.Rejected)
        assertEquals(RejectionCode.EXPOSURE_CAP_REACHED, (longResult as PortfolioGateResult.Rejected).code)
        assertTrue((longResult).reason.contains("Directional imbalance cap reached"))

        // A Short signal brings net imbalance to +1 <= 2 -> Accept!
        val shortSignal = dummySignal("B-SOL_USDT", "rzmr", SignalDirection.SHORT)
        val shortResult = registry.evaluateSignal(shortSignal)
        assertEquals(PortfolioGateResult.Pass, shortResult)
    }

    @Test
    fun `test StrategyRegistry holds all institutional strategies and respects runtime modes`() {
        // Verify all 10 strategies are present
        val registeredIds = StrategyRegistry.availableStrategies.map { it.id.lowercase() }
        assertTrue("Must contain S1 pbc", registeredIds.contains("pbc"))
        assertTrue("Must contain S2 vceb", registeredIds.contains("vceb"))
        assertTrue("Must contain S3 lsr", registeredIds.contains("lsr"))
        assertTrue("Must contain S4 sorm", registeredIds.contains("sorm"))
        assertTrue("Must contain S7 rzmr", registeredIds.contains("rzmr"))
        assertTrue("Must contain S8 irc", registeredIds.contains("irc"))
        assertTrue("Must contain S9 sbob", registeredIds.contains("sbob"))
        assertTrue("Must contain S10 edtm", registeredIds.contains("edtm"))

        // Universe strategies
        val universeIds = StrategyRegistry.availableUniverseStrategies.map { it.id.lowercase() }
        assertTrue("Must contain S5 xrs universe strategy", universeIds.contains("xrs"))

        // Test Mode transitions
        StrategyRegistry.setStrategyMode("vceb", StrategyMode.DISABLED)
        assertEquals(StrategyMode.DISABLED, StrategyRegistry.getStrategyMode("vceb"))
        assertFalse("Disabled strategy must not be in scanning strategies",
            StrategyRegistry.getScanningStrategies().any { it.id.equals("vceb", ignoreCase = true) }
        )

        StrategyRegistry.setStrategyMode("vceb", StrategyMode.LIVE)
        assertEquals(StrategyMode.LIVE, StrategyRegistry.getStrategyMode("vceb"))
        assertTrue("LIVE strategy must be in live strategies",
            StrategyRegistry.getLiveStrategies().any { it.id.equals("vceb", ignoreCase = true) }
        )

        StrategyRegistry.setStrategyMode("vceb", StrategyMode.SHADOW)
        assertEquals(StrategyMode.SHADOW, StrategyRegistry.getStrategyMode("vceb"))
        assertTrue("SHADOW strategy must be in shadow strategies",
            StrategyRegistry.getShadowStrategies().any { it.id.equals("vceb", ignoreCase = true) }
        )
        assertTrue("SHADOW strategy must still be in scanning strategies for telemetry",
            StrategyRegistry.getScanningStrategies().any { it.id.equals("vceb", ignoreCase = true) }
        )

        // Restore to LIVE
        StrategyRegistry.setStrategyMode("vceb", StrategyMode.LIVE)
    }
}
