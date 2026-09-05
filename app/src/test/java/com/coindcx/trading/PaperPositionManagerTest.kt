package com.coindcx.trading

import com.coindcx.trading.engine.paper.PaperPositionManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PaperPositionManagerTest {

    @Test
    fun testTieredMaintenanceMargin() {
        // Schedule: <=5x: 1.0%, 6x-10x: 1.5%, 11x-20x: 2.5%
        assertEquals(0.010, PaperPositionManager.getMaintenanceMargin(1), 0.0001)
        assertEquals(0.010, PaperPositionManager.getMaintenanceMargin(3), 0.0001)
        assertEquals(0.010, PaperPositionManager.getMaintenanceMargin(5), 0.0001)

        assertEquals(0.015, PaperPositionManager.getMaintenanceMargin(6), 0.0001)
        assertEquals(0.015, PaperPositionManager.getMaintenanceMargin(10), 0.0001)

        assertEquals(0.025, PaperPositionManager.getMaintenanceMargin(11), 0.0001)
        assertEquals(0.025, PaperPositionManager.getMaintenanceMargin(15), 0.0001)
        assertEquals(0.025, PaperPositionManager.getMaintenanceMargin(20), 0.0001)
    }

    @Test
    fun testEstimatedLiquidationFormulas() {
        // Long 10x @ 60,000:
        // factor = 1 - 1/10 + 0.015 = 1 - 0.10 + 0.015 = 0.915
        // EstLiq = 60,000 * 0.915 = 54,900
        val long10x = PaperPositionManager.calculateEstimatedLiquidation("LONG", 60000.0, 10)
        assertEquals(54900.0, long10x, 0.01)

        // Short 10x @ 60,000:
        // factor = 1 + 1/10 - 0.015 = 1 + 0.10 - 0.015 = 1.085
        // EstLiq = 60,000 * 1.085 = 65,100
        val short10x = PaperPositionManager.calculateEstimatedLiquidation("SHORT", 60000.0, 10)
        assertEquals(65100.0, short10x, 0.01)

        // Long 5x @ 100:
        // factor = 1 - 1/5 + 0.010 = 1 - 0.20 + 0.010 = 0.810
        // EstLiq = 81.0
        val long5x = PaperPositionManager.calculateEstimatedLiquidation("LONG", 100.0, 5)
        assertEquals(81.0, long5x, 0.01)

        // Short 5x @ 100:
        // factor = 1 + 1/5 - 0.010 = 1 + 0.20 - 0.010 = 1.190
        // EstLiq = 119.0
        val short5x = PaperPositionManager.calculateEstimatedLiquidation("SHORT", 100.0, 5)
        assertEquals(119.0, short5x, 0.01)
    }

    @Test
    fun testUtcFundingIntervalsCrossed() {
        val intervalMs = 8 * 3600 * 1000L
        val baseTime = 1725500000000L

        // Same timestamp -> 0 intervals
        assertEquals(0, PaperPositionManager.countFundingIntervalsCrossed(baseTime, baseTime))

        // 4 hours later within same interval -> 0 intervals
        assertEquals(0, PaperPositionManager.countFundingIntervalsCrossed(baseTime, baseTime + (4 * 3600 * 1000L)))

        // Exactly crosses one 8-hour boundary -> 1 interval
        val startInInterval0 = 1000L
        val endInInterval1 = intervalMs + 1000L
        assertEquals(1, PaperPositionManager.countFundingIntervalsCrossed(startInInterval0, endInInterval1))

        // 24 hours later (crosses 3 8-hour funding settlements) -> 3 intervals
        val endInInterval3 = (3 * intervalMs) + 1000L
        assertEquals(3, PaperPositionManager.countFundingIntervalsCrossed(startInInterval0, endInInterval3))
    }
}
