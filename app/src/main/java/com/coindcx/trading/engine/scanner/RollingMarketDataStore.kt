package com.coindcx.trading.engine.scanner

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * In-memory time-series store maintaining a rolling ring buffer of ticker snapshots
 * for all active futures pairs on CoinDCX.
 *
 * Granularity: 1 snapshot every ~2 minutes.
 * Capacity: Up to 60 snapshots (2 hours of history) per pair.
 * Memory footprint: ~300 pairs * 60 snapshots * ~48 bytes ≈ 860 KB.
 *
 * Implements per-metric warm-up windows with matched denominators to guarantee
 * graceful degradation during cold start without denominator mismatches or false spikes.
 */
class RollingMarketDataStore(
    private val maxSnapshotsPerPair: Int = 60
) {
    data class TickerSnapshot(
        val timestampMs: Long,
        val lastPrice: Double,
        val baseVolume: Double,
        val quoteVolumeUsdt: Double,
        val high24h: Double,
        val low24h: Double,
        val bid: Double,
        val ask: Double,
        val change24h: Double
    )

    data class RvolResult(
        val rvol: Double,
        val isWarmed: Boolean,
        val elapsedMinutes: Double,
        val confidenceWeight: Double, // 0.0 (unwarmed) to 1.0 (fully warmed)
        val observedVolumeDelta: Double,
        val expectedVolumeDelta: Double
    )

    data class VelocityResult(
        val v15m: Double,
        val v1h: Double,
        val isWarmed15m: Boolean,
        val isWarmed1h: Boolean,
        val effectiveVelocity: Double
    )

    private val store = ConcurrentHashMap<String, ArrayDeque<TickerSnapshot>>()

    /**
     * Records a new snapshot for the given pair.
     * Enforces the ring buffer capacity (pops oldest when exceeding maxSnapshotsPerPair).
     */
    fun addSnapshot(pair: String, snapshot: TickerSnapshot) {
        val deque = store.computeIfAbsent(pair) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(snapshot)
            while (deque.size > maxSnapshotsPerPair) {
                deque.removeFirst()
            }
        }
    }

    /**
     * Clears all stored data (primarily for testing or service restart).
     */
    fun clear() {
        store.clear()
    }

    /**
     * Returns the number of snapshots stored for a given pair.
     */
    fun getSnapshotCount(pair: String): Int {
        val deque = store[pair] ?: return 0
        return synchronized(deque) { deque.size }
    }

    /**
     * Computes True Relative Volume (RVOL) with per-metric warm-up and matched denominators.
     *
     * Rules:
     * - deltaT < 10 minutes (< 5 snapshots): UNWARMED. Neutral rvol = 1.0, confidenceWeight = 0.0.
     * - deltaT in [10m, 30m) (5 to 15 snapshots): PARTIAL FILL. Denominator strictly matches
     *   observed deltaT: V_expected,deltaT = (V_24h / 1440) * deltaT_min.
     *   confidenceWeight scales linearly from 0.0 at 10m to 1.0 at 30m.
     * - deltaT >= 30m (>= 15 snapshots): FULLY WARMED. Evaluated over 30-minute window against V_expected,30m.
     */
    fun calculateRvol(pair: String): RvolResult {
        val deque = store[pair]
        if (deque == null) {
            return RvolResult(1.0, isWarmed = false, elapsedMinutes = 0.0, confidenceWeight = 0.0, 0.0, 0.0)
        }

        val snapshots: List<TickerSnapshot>
        synchronized(deque) {
            if (deque.size < 2) {
                return RvolResult(1.0, isWarmed = false, elapsedMinutes = 0.0, confidenceWeight = 0.0, 0.0, 0.0)
            }
            snapshots = deque.toList()
        }

        val newest = snapshots.last()
        val oldest = snapshots.first()
        val totalElapsedMinutes = (newest.timestampMs - oldest.timestampMs) / 60_000.0

        if (totalElapsedMinutes < 10.0) {
            return RvolResult(
                rvol = 1.0,
                isWarmed = false,
                elapsedMinutes = totalElapsedMinutes,
                confidenceWeight = 0.0,
                observedVolumeDelta = 0.0,
                expectedVolumeDelta = 0.0
            )
        }

        // Target a 30-minute lookback window
        val targetWindowMs = 30 * 60_000L
        val targetTimestamp = newest.timestampMs - targetWindowMs

        // Find snapshot closest to targetTimestamp
        var baseline = oldest
        var minDiff = abs(oldest.timestampMs - targetTimestamp)
        for (s in snapshots) {
            val diff = abs(s.timestampMs - targetTimestamp)
            if (diff < minDiff) {
                minDiff = diff
                baseline = s
            }
        }

        val observedElapsedMinutes = (newest.timestampMs - baseline.timestampMs) / 60_000.0
        if (observedElapsedMinutes < 10.0) {
            return RvolResult(1.0, isWarmed = false, elapsedMinutes = observedElapsedMinutes, confidenceWeight = 0.0, 0.0, 0.0)
        }

        // Difference between two 24h rolling volume snapshots is: deltaV24h = V_actual,deltaT - V_24h_ago,deltaT
        // Assuming historical baseline V_24h_ago,deltaT ≈ V_expected,deltaT:
        // V_actual,deltaT ≈ V_expected,deltaT + deltaV24h
        // True RVOL = V_actual / V_expected = 1.0 + (deltaV24h / expectedDeltaVolume)
        val deltaV24h = newest.quoteVolumeUsdt - baseline.quoteVolumeUsdt
        // V_expected matches EXACTLY the observed elapsed minutes: (V_24h / 1440 min) * observedElapsedMinutes
        val expectedDeltaVolume = (newest.quoteVolumeUsdt / 1440.0) * observedElapsedMinutes
        val observedDeltaVolume = (expectedDeltaVolume + deltaV24h).coerceAtLeast(0.0)

        val rawRvol = if (expectedDeltaVolume > 0.0) {
            (observedDeltaVolume / expectedDeltaVolume).coerceAtLeast(0.0)
        } else {
            1.0
        }

        val isWarmed = observedElapsedMinutes >= 28.0 // ~30m with 2m discretization
        val confidenceWeight = if (isWarmed) {
            1.0
        } else {
            // Linear ramp between 10m (0.0) and 30m (1.0)
            ((observedElapsedMinutes - 10.0) / 20.0).coerceIn(0.0, 1.0)
        }

        return RvolResult(
            rvol = rawRvol,
            isWarmed = isWarmed,
            elapsedMinutes = observedElapsedMinutes,
            confidenceWeight = confidenceWeight,
            observedVolumeDelta = observedDeltaVolume,
            expectedVolumeDelta = expectedDeltaVolume
        )
    }

    /**
     * Computes Short-Term Price Velocity (15m and 1h) with per-metric warm-up.
     *
     * Rules:
     * - v15m: Requires >= 14 min (8 snapshots). If unwarmed, falls back to normalized 24h change / 96.
     * - v1h: Requires >= 58 min (30 snapshots). If between 14m and 58m, scales observed velocity
     *   to hourly rate: v_obs * (60 / deltaT), clamped to max 3.0x scale factor.
     *   If < 14m, falls back to normalized 24h change / 24.
     */
    fun calculateVelocity(pair: String): VelocityResult {
        val deque = store[pair]
        if (deque == null) {
            return fallbackVelocity(0.0)
        }

        val snapshots: List<TickerSnapshot>
        synchronized(deque) {
            if (deque.size < 2) {
                val single = deque.lastOrNull()
                return fallbackVelocity(single?.change24h ?: 0.0)
            }
            snapshots = deque.toList()
        }

        val newest = snapshots.last()
        val oldest = snapshots.first()
        val totalElapsedMinutes = (newest.timestampMs - oldest.timestampMs) / 60_000.0

        if (totalElapsedMinutes < 14.0) {
            return fallbackVelocity(newest.change24h)
        }

        // 1. Calculate v15m (target 15 minutes lookback)
        val target15mMs = newest.timestampMs - (15 * 60_000L)
        val snap15m = findClosestSnapshot(snapshots, target15mMs)
        val elapsed15m = (newest.timestampMs - snap15m.timestampMs) / 60_000.0
        val isWarmed15m = elapsed15m >= 13.0

        val v15m = if (isWarmed15m && snap15m.lastPrice > 0.0) {
            ((newest.lastPrice - snap15m.lastPrice) / snap15m.lastPrice) * 100.0
        } else {
            newest.change24h / 96.0 // 15m slice of 24h change
        }

        // 2. Calculate v1h (target 60 minutes lookback)
        val target1hMs = newest.timestampMs - (60 * 60_000L)
        val snap1h = findClosestSnapshot(snapshots, target1hMs)
        val elapsed1h = (newest.timestampMs - snap1h.timestampMs) / 60_000.0
        val isWarmed1h = elapsed1h >= 56.0

        val v1h = when {
            isWarmed1h && snap1h.lastPrice > 0.0 -> {
                ((newest.lastPrice - snap1h.lastPrice) / snap1h.lastPrice) * 100.0
            }
            elapsed1h >= 14.0 && snap1h.lastPrice > 0.0 -> {
                // Partial fill extrapolation: scale observed velocity to 1h rate (clamped to max 3.0x scale)
                val rawObserved = ((newest.lastPrice - snap1h.lastPrice) / snap1h.lastPrice) * 100.0
                val scaleFactor = (60.0 / elapsed1h).coerceAtMost(3.0)
                rawObserved * scaleFactor
            }
            else -> {
                newest.change24h / 24.0
            }
        }

        val effectiveVelocity = maxOf(abs(v15m) * 2.0, abs(v1h))

        return VelocityResult(
            v15m = v15m,
            v1h = v1h,
            isWarmed15m = isWarmed15m,
            isWarmed1h = isWarmed1h,
            effectiveVelocity = effectiveVelocity
        )
    }

    private fun findClosestSnapshot(snapshots: List<TickerSnapshot>, targetMs: Long): TickerSnapshot {
        var closest = snapshots.first()
        var minDiff = abs(closest.timestampMs - targetMs)
        for (s in snapshots) {
            val diff = abs(s.timestampMs - targetMs)
            if (diff < minDiff) {
                minDiff = diff
                closest = s
            }
        }
        return closest
    }

    private fun fallbackVelocity(change24h: Double): VelocityResult {
        val est15m = change24h / 96.0
        val est1h = change24h / 24.0
        val effective = abs(change24h) / 12.0
        return VelocityResult(
            v15m = est15m,
            v1h = est1h,
            isWarmed15m = false,
            isWarmed1h = false,
            effectiveVelocity = effective
        )
    }
}
