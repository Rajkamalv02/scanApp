package com.coindcx.trading

import com.coindcx.trading.engine.scanner.MarketActivityScorer
import com.coindcx.trading.engine.scanner.RollingMarketDataStore
import org.junit.Assert.*
import org.junit.Test

class MarketActivityScorerTest {

    private fun mockRvol(
        rvol: Double,
        isWarmed: Boolean = true,
        confidenceWeight: Double = 1.0
    ): RollingMarketDataStore.RvolResult {
        return RollingMarketDataStore.RvolResult(
            rvol = rvol,
            isWarmed = isWarmed,
            elapsedMinutes = if (isWarmed) 30.0 else 15.0,
            confidenceWeight = confidenceWeight,
            observedVolumeDelta = 100_000.0,
            expectedVolumeDelta = 50_000.0
        )
    }

    private fun mockVelocity(
        v15m: Double = 0.0,
        v1h: Double = 0.0,
        effectiveVelocity: Double = 0.0,
        isWarmed15m: Boolean = true,
        isWarmed1h: Boolean = true
    ): RollingMarketDataStore.VelocityResult {
        return RollingMarketDataStore.VelocityResult(
            v15m = v15m,
            v1h = v1h,
            isWarmed15m = isWarmed15m,
            isWarmed1h = isWarmed1h,
            effectiveVelocity = effectiveVelocity
        )
    }

    @Test
    fun testLiquidityClampingAndScaling() {
        val rvol = mockRvol(1.0)
        val vel = mockVelocity()

        // Below $250k floor -> must clamp strictly to 0.0 pts
        val scoreBelowFloor = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 100_000.0,
            lastPrice = 100.0,
            high24h = 105.0,
            low24h = 95.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 1.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(0.0, scoreBelowFloor.liquidityScore, 0.001)

        // At $400k (primary gate floor) -> ~1.18 pts
        val score400k = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 400_000.0,
            lastPrice = 100.0,
            high24h = 105.0,
            low24h = 95.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 1.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertTrue("Score at $400k should be ~1.18", score400k.liquidityScore in 1.15..1.22)

        // At $10M -> ~9.24 pts
        val score10M = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 10_000_000.0,
            lastPrice = 100.0,
            high24h = 105.0,
            low24h = 95.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 1.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertTrue("Score at $10M should be ~9.24", score10M.liquidityScore in 9.20..9.30)

        // Above $100M -> clamped strictly to 15.0 pts
        val scoreSuperHigh = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 250_000_000.0,
            lastPrice = 100.0,
            high24h = 105.0,
            low24h = 95.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 1.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(15.0, scoreSuperHigh.liquidityScore, 0.001)
    }

    @Test
    fun testSpreadClampingAndScaling() {
        val rvol = mockRvol(1.0)
        val vel = mockVelocity()

        // Very tight spread 0.04% <= 0.06% -> must receive maximum 20.0 pts
        val scoreTight = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 105.0,
            low24h = 95.0,
            bid = 99.98,
            ask = 100.02, // 0.04%
            change24h = 1.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(20.0, scoreTight.spreadScore, 0.001)

        // Spread 0.25% (primary threshold) -> ~6.89 pts
        val scoreMid = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 105.0,
            low24h = 95.0,
            bid = 99.875,
            ask = 100.125, // 0.25%
            change24h = 1.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertTrue("Spread 0.25% should score ~6.89 pts", scoreMid.spreadScore in 6.8..7.0)

        // Spread 0.35% (adaptive ceiling) -> 0.0 pts
        val scoreAtCeiling = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 105.0,
            low24h = 95.0,
            bid = 99.825,
            ask = 100.175, // 0.35%
            change24h = 1.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(0.0, scoreAtCeiling.spreadScore, 0.001)

        // Spread 0.50% (past ceiling) -> clamped to 0.0 pts
        val scoreWide = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 105.0,
            low24h = 95.0,
            bid = 99.75,
            ask = 100.25, // 0.50%
            change24h = 1.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(0.0, scoreWide.spreadScore, 0.001)
    }

    @Test
    fun testRangeAmplitudePiecewiseBanding() {
        val rvol = mockRvol(1.0)
        val vel = mockVelocity()

        // Dead market: range < 2.5% -> 0.0 pts
        val scoreDead = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 101.0,
            low24h = 100.0, // 1.0% range
            bid = 99.95,
            ask = 100.05,
            change24h = 0.5,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(0.0, scoreDead.rangeScore, 0.001)

        // Ramp zone: 3.25% -> exactly 10.0 pts (halfway between 2.5% and 4.0%)
        val scoreRamp = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 103.25,
            low24h = 100.0, // 3.25% range
            bid = 99.95,
            ask = 100.05,
            change24h = 0.5,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(10.0, scoreRamp.rangeScore, 0.001)

        // Sweet spot: 8.0% range (between 4% and 15%) -> 20.0 pts
        val scoreSweet = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 108.0,
            low24h = 100.0, // 8.0% range
            bid = 99.95,
            ask = 100.05,
            change24h = 2.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(20.0, scoreSweet.rangeScore, 0.001)

        // High volatility: 20.0% range -> 14.0 pts
        val scoreHighVol = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 120.0,
            low24h = 100.0, // 20.0% range
            bid = 99.95,
            ask = 100.05,
            change24h = 5.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(14.0, scoreHighVol.rangeScore, 0.001)

        // Extreme wick risk: 30.0% range -> 8.0 pts
        val scoreExtreme = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 130.0,
            low24h = 100.0, // 30.0% range
            bid = 99.95,
            ask = 100.05,
            change24h = 10.0,
            rvolResult = rvol,
            velocityResult = vel
        )
        assertEquals(8.0, scoreExtreme.rangeScore, 0.001)
    }

    @Test
    fun testVelocityPiecewiseBandingAndExhaustionPenalty() {
        val rvol = mockRvol(1.0)

        // 1. Strong breakout impulse: V_eff >= 4.0% (14 pts) + aligned direction (+6 pts) = 20.0 pts
        val velBreakout = mockVelocity(v15m = 2.2, v1h = 4.5, effectiveVelocity = 4.5)
        val scoreBreakout = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 108.0,
            low24h = 100.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 6.0,
            rvolResult = rvol,
            velocityResult = velBreakout
        )
        assertEquals(20.0, scoreBreakout.velocityScore, 0.001)

        // 2. Moderate move: V_eff = 1.8% (8 pts) + neutral drift (+3 pts) = 11.0 pts
        val velModerate = mockVelocity(v15m = 0.4, v1h = 1.8, effectiveVelocity = 1.8)
        val scoreModerate = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 108.0,
            low24h = 100.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 2.0,
            rvolResult = rvol,
            velocityResult = velModerate
        )
        assertEquals(11.0, scoreModerate.velocityScore, 0.001)

        // 3. Post-pump exhaustion penalty: 24h change = +20% but 1h velocity = -1.5% (dumping)
        // V_eff = 1.8% (8 pts) + exhaustion penalty (0 pts) = 8.0 pts
        val velExhaustion = mockVelocity(v15m = -0.8, v1h = -1.5, effectiveVelocity = 1.8)
        val scoreExhaustion = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 125.0,
            low24h = 100.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 20.0, // Large 24h pump
            rvolResult = rvol,
            velocityResult = velExhaustion
        )
        assertEquals("Post-pump exhaustion must trigger 0 alignment points", 8.0, scoreExhaustion.velocityScore, 0.001)
    }

    @Test
    fun testRvolBandingAndUnwarmedBlending() {
        val vel = mockVelocity()

        // 1. Unwarmed RVOL -> neutral 10.0 pts
        val rvolUnwarmed = mockRvol(rvol = 5.0, isWarmed = false, confidenceWeight = 0.0)
        val scoreUnwarmed = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 108.0,
            low24h = 100.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 1.0,
            rvolResult = rvolUnwarmed,
            velocityResult = vel
        )
        assertEquals(10.0, scoreUnwarmed.rvolScore, 0.001)

        // 2. Fully warmed: >= 3.0x -> 25.0 pts
        val rvolSurge = mockRvol(rvol = 3.5, isWarmed = true, confidenceWeight = 1.0)
        val scoreSurge = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 108.0,
            low24h = 100.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 1.0,
            rvolResult = rvolSurge,
            velocityResult = vel
        )
        assertEquals(25.0, scoreSurge.rvolScore, 0.001)

        // 3. Fully warmed: low volume < 0.8x -> 0.0 pts
        val rvolQuiet = mockRvol(rvol = 0.5, isWarmed = true, confidenceWeight = 1.0)
        val scoreQuiet = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 108.0,
            low24h = 100.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 1.0,
            rvolResult = rvolQuiet,
            velocityResult = vel
        )
        assertEquals(0.0, scoreQuiet.rvolScore, 0.001)

        // 4. Partial fill transition (confidenceWeight = 0.5):
        // RVOL 3.5 (raw 25 pts). Blended = (0.5 * 10) + (0.5 * 25) = 17.5 pts
        val rvolPartial = mockRvol(rvol = 3.5, isWarmed = false, confidenceWeight = 0.5)
        val scorePartial = MarketActivityScorer.calculateScore(
            pair = "TEST",
            quoteVolumeUsdt = 1_000_000.0,
            lastPrice = 100.0,
            high24h = 108.0,
            low24h = 100.0,
            bid = 99.95,
            ask = 100.05,
            change24h = 1.0,
            rvolResult = rvolPartial,
            velocityResult = vel
        )
        assertEquals(17.5, scorePartial.rvolScore, 0.001)
    }
}
