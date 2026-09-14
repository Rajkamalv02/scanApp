package com.coindcx.trading

import com.coindcx.trading.engine.scanner.RollingMarketDataStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RollingMarketDataStoreTest {

    private lateinit var store: RollingMarketDataStore

    @Before
    fun setup() {
        store = RollingMarketDataStore(maxSnapshotsPerPair = 60)
    }

    private fun createSnapshot(
        timestampMs: Long,
        price: Double = 100.0,
        volume: Double = 10_000.0,
        quoteVolume: Double = 1_000_000.0,
        high: Double = 105.0,
        low: Double = 95.0,
        bid: Double = 99.95,
        ask: Double = 100.05,
        change24h: Double = 2.5
    ): RollingMarketDataStore.TickerSnapshot {
        return RollingMarketDataStore.TickerSnapshot(
            timestampMs = timestampMs,
            lastPrice = price,
            baseVolume = volume,
            quoteVolumeUsdt = quoteVolume,
            high24h = high,
            low24h = low,
            bid = bid,
            ask = ask,
            change24h = change24h
        )
    }

    @Test
    fun testColdStartFewerThanTwoSnapshots() {
        val rvolEmpty = store.calculateRvol("B-BTC_USDT")
        assertFalse(rvolEmpty.isWarmed)
        assertEquals(0.0, rvolEmpty.confidenceWeight, 0.0001)
        assertEquals(1.0, rvolEmpty.rvol, 0.0001)

        val t0 = 1_000_000_000L
        store.addSnapshot("B-BTC_USDT", createSnapshot(t0))

        val rvolSingle = store.calculateRvol("B-BTC_USDT")
        assertFalse(rvolSingle.isWarmed)
        assertEquals(0.0, rvolSingle.confidenceWeight, 0.0001)
        assertEquals(1.0, rvolSingle.rvol, 0.0001)

        val velSingle = store.calculateVelocity("B-BTC_USDT")
        assertFalse(velSingle.isWarmed15m)
        assertFalse(velSingle.isWarmed1h)
    }

    @Test
    fun testPartialFillBelowTenMinutes() {
        val t0 = 1_000_000_000L
        // Add 3 snapshots at 0m, 2m, 4m (elapsed 4 minutes)
        store.addSnapshot("B-BTC_USDT", createSnapshot(t0, quoteVolume = 1_000_000.0))
        store.addSnapshot("B-BTC_USDT", createSnapshot(t0 + 2 * 60_000L, quoteVolume = 1_010_000.0))
        store.addSnapshot("B-BTC_USDT", createSnapshot(t0 + 4 * 60_000L, quoteVolume = 1_020_000.0))

        val rvol = store.calculateRvol("B-BTC_USDT")
        assertFalse("RVOL must be unwarmed under 10 minutes", rvol.isWarmed)
        assertEquals(0.0, rvol.confidenceWeight, 0.0001)
        assertEquals("Unwarmed RVOL must return neutral 1.0", 1.0, rvol.rvol, 0.0001)

        val vel = store.calculateVelocity("B-BTC_USDT")
        assertFalse(vel.isWarmed15m)
        assertFalse(vel.isWarmed1h)
    }

    @Test
    fun testSteadyNormalVolumeYieldsOnePointZeroRvol() {
        val t0 = 1_000_000_000L
        val baseQuoteVol = 14_400_000.0 // Exactly $10,000 expected per minute
        // Add 16 snapshots with zero deltaV (steady 24h rolling volume)
        for (i in 0..15) {
            val elapsedMs = i * 2 * 60_000L
            store.addSnapshot("B-STEADY_USDT", createSnapshot(t0 + elapsedMs, quoteVolume = baseQuoteVol))
        }

        val rvol = store.calculateRvol("B-STEADY_USDT")
        assertTrue("RVOL must be fully warmed at 30 minutes", rvol.isWarmed)
        assertEquals("Steady volume with 0 rolling delta must yield RVOL = 1.0x baseline", 1.0, rvol.rvol, 0.05)
    }

    @Test
    fun testPartialFillMatchedDenominatorInTransitionZone() {
        val t0 = 1_000_000_000L
        val baseQuoteVol = 1_440_000.0 // Exactly $1,000 expected volume per minute!

        // Add 8 snapshots: 0m to 14m (elapsed 14 minutes)
        for (i in 0..7) {
            val elapsedMs = i * 2 * 60_000L
            // Simulate 24h volume expansion of $1,000/min above baseline -> +$14,000 deltaV24h over 14 min
            val currentQuoteVol = baseQuoteVol + (i * 2 * 1000.0)
            store.addSnapshot("B-SOL_USDT", createSnapshot(t0 + elapsedMs, quoteVolume = currentQuoteVol))
        }

        val rvol = store.calculateRvol("B-SOL_USDT")
        assertFalse("At 14m, RVOL is in transition zone, not fully warmed", rvol.isWarmed)
        assertEquals(0.20, rvol.confidenceWeight, 0.01)

        // Denominator must match the 14-minute elapsed window, NOT 30 minutes!
        // Expected over 14m = (currentQuoteVol / 1440) * 14m ≈ $14,000
        // deltaV24h = $14,000
        // observedDeltaVolume = $14,000 + $14,000 = $28,000
        // RVOL = 28,000 / 14,000 = 2.0x
        assertTrue("Expected delta volume must match ~14m (~14,000), not 30m (~30,000)", rvol.expectedVolumeDelta in 13_500.0..15_000.0)
        assertEquals(2.0, rvol.rvol, 0.1)
    }

    @Test
    fun testFullyWarmedRvolAtThirtyMinutes() {
        val t0 = 1_000_000_000L
        val baseQuoteVol = 14_400_000.0 // $10,000 expected per minute ($300,000 expected over 30m)

        // Add 16 snapshots: 0m to 30m (every 2m)
        for (i in 0..15) {
            val elapsedMs = i * 2 * 60_000L
            // Simulate volume expansion: +$20,000/min -> deltaV24h = +$600,000 over 30m
            // RVOL = 1.0 + (600,000 / 300,000) = 3.0x
            val currentQuoteVol = baseQuoteVol + (i * 2 * 20_000.0)
            store.addSnapshot("B-ETH_USDT", createSnapshot(t0 + elapsedMs, quoteVolume = currentQuoteVol))
        }

        val rvol = store.calculateRvol("B-ETH_USDT")
        assertTrue("RVOL must be fully warmed at 30 minutes", rvol.isWarmed)
        assertEquals(1.0, rvol.confidenceWeight, 0.0001)
        assertEquals(30.0, rvol.elapsedMinutes, 0.5)

        // RVOL ≈ 3.0x
        assertTrue("RVOL should reflect ~3.0x surge", rvol.rvol in 2.8..3.2)
    }

    @Test
    fun testVelocityScalingAndFullWarmup() {
        val t0 = 1_000_000_000L

        // 1. Warm up 16 minutes (9 snapshots) -> v15m should be warmed, v1h scaled
        var price = 100.0
        for (i in 0..8) {
            val elapsedMs = i * 2 * 60_000L
            price += 0.5 // Rises 4.0% over 16 minutes (from 100 to 104)
            store.addSnapshot("B-ADA_USDT", createSnapshot(t0 + elapsedMs, price = price))
        }

        val vel16m = store.calculateVelocity("B-ADA_USDT")
        assertTrue("v15m should be warmed at 16 minutes", vel16m.isWarmed15m)
        assertFalse("v1h should NOT be fully warmed at 16 minutes", vel16m.isWarmed1h)
        assertTrue("15m velocity should be ~3.5% to 4.0%", vel16m.v15m in 3.5..4.5)
        // Scaled 1h velocity: clamped to at most 3.0x scale
        assertTrue("Scaled 1h velocity should be positive and amplified", vel16m.v1h > vel16m.v15m)

        // 2. Add up to 31 snapshots (60 minutes) -> v1h should be fully warmed
        for (i in 9..30) {
            val elapsedMs = i * 2 * 60_000L
            price += 0.2 // Continues rising to 108.4 (+8.4% over 60 min)
            store.addSnapshot("B-ADA_USDT", createSnapshot(t0 + elapsedMs, price = price))
        }

        val vel60m = store.calculateVelocity("B-ADA_USDT")
        assertTrue("v15m should be warmed", vel60m.isWarmed15m)
        assertTrue("v1h should be warmed at 60 minutes", vel60m.isWarmed1h)
        assertTrue("60m velocity should be around 8.0% to 9.0%", vel60m.v1h in 7.5..9.5)
    }

    @Test
    fun testRingBufferCapacityLimit() {
        val t0 = 1_000_000_000L
        for (i in 1..75) {
            store.addSnapshot("B-DOGE_USDT", createSnapshot(t0 + i * 60_000L))
        }
        assertEquals("Ring buffer must be strictly capped at 60 snapshots", 60, store.getSnapshotCount("B-DOGE_USDT"))
    }
}
