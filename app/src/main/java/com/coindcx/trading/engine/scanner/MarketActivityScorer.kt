package com.coindcx.trading.engine.scanner

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sign

/**
 * Pure, deterministic Market Activity Scorer (MAS).
 *
 * Evaluates candidate crypto futures contracts on a standardized 0–100 scale:
 * - True RVOL (25 pts)
 * - Short-Term Price Velocity & Momentum Alignment (20 pts)
 * - 24h High-Low Range Amplitude (20 pts)
 * - Top-of-Book (BBO) Spread Tightness (20 pts)
 * - Base Liquidity Depth (15 pts)
 *
 * Guaranteed strict mathematical clamping on all components, deterministic piecewise
 * banding, and graceful warm-up blending.
 */
object MarketActivityScorer {

    data class MarketActivityScore(
        val pair: String,
        val totalScore: Double,
        val rvolScore: Double,
        val velocityScore: Double,
        val rangeScore: Double,
        val spreadScore: Double,
        val liquidityScore: Double,
        val rvol: Double,
        val v15m: Double,
        val v1h: Double,
        val rangePct: Double,
        val spreadPct: Double,
        val quoteVolumeUsdt: Double,
        val isWarmed: Boolean
    )

    private const val LOG_MIN_VOLUME = 5.3979400086720375 // log10(250,000)
    private const val LOG_MAX_VOLUME = 8.0 // log10(100,000,000)
    private const val LOG_VOLUME_RANGE = LOG_MAX_VOLUME - LOG_MIN_VOLUME // ~2.60206

    /**
     * Calculates the complete Market Activity Score (0–100) for a given pair.
     */
    fun calculateScore(
        pair: String,
        quoteVolumeUsdt: Double,
        lastPrice: Double,
        high24h: Double,
        low24h: Double,
        bid: Double,
        ask: Double,
        change24h: Double,
        rvolResult: RollingMarketDataStore.RvolResult,
        velocityResult: RollingMarketDataStore.VelocityResult
    ): MarketActivityScore {
        // 1. True Relative Volume (RVOL) [0, 25 pts]
        val rawRvolScore = when {
            rvolResult.rvol >= 3.0 -> 25.0
            rvolResult.rvol >= 2.0 -> 18.0
            rvolResult.rvol >= 1.2 -> 10.0
            rvolResult.rvol >= 0.8 -> 5.0
            else -> 0.0
        }

        val rvolScore = when {
            rvolResult.isWarmed -> rawRvolScore
            rvolResult.confidenceWeight <= 0.0 -> 10.0 // Neutral unwarmed baseline
            else -> {
                // Partial fill: blend neutral baseline (10.0) with observed RVOL score
                val w = rvolResult.confidenceWeight.coerceIn(0.0, 1.0)
                ((1.0 - w) * 10.0) + (w * rawRvolScore)
            }
        }.coerceIn(0.0, 25.0)

        // 2. Short-Term Velocity & Momentum [0, 20 pts]
        val vEff = velocityResult.effectiveVelocity
        val sMag = when {
            vEff >= 4.0 -> 14.0
            vEff >= 2.5 -> 11.0
            vEff >= 1.2 -> 8.0
            vEff >= 0.5 -> 4.0
            else -> 1.0
        }

        val sAlign = when {
            !velocityResult.isWarmed15m && !velocityResult.isWarmed1h -> {
                3.0 // Unwarmed neutral drift
            }
            // Exhaustion penalty: strong 24h pump/dump opposing 1h velocity
            abs(change24h) >= 10.0 && ((change24h > 0 && velocityResult.v1h <= -1.0) || (change24h < 0 && velocityResult.v1h >= 1.0)) -> {
                0.0 // Severe penalty for post-pump exhaustion
            }
            // Fresh acceleration / breakout: same direction or sharp 15m impulsive surge
            (sign(velocityResult.v15m) == sign(velocityResult.v1h) && abs(velocityResult.v15m) >= 0.5) || abs(velocityResult.v15m) >= 2.0 -> {
                6.0
            }
            else -> {
                3.0 // Neutral drift
            }
        }
        val velocityScore = (sMag + sAlign).coerceIn(0.0, 20.0)

        // 3. 24h High-Low Range Amplitude % [0, 20 pts]
        val rangePct = if (low24h > 0.0 && high24h >= low24h) {
            ((high24h - low24h) / low24h) * 100.0
        } else {
            0.0
        }

        val rangeScore = when {
            rangePct in 4.0..15.0 -> 20.0 // Sweet spot
            rangePct in 15.0..25.0 -> 14.0 // High volatility
            rangePct in 25.0..35.0 -> 8.0 // Elevated wick risk
            rangePct > 35.0 -> 4.0 // Super-extreme
            rangePct < 2.5 -> 0.0 // Compressed / dead
            else -> {
                // Smooth linear ramp between 2.5% (0 pts) and 4.0% (20 pts)
                (20.0 * ((rangePct - 2.5) / 1.5)).coerceIn(0.0, 20.0)
            }
        }.coerceIn(0.0, 20.0)

        // 4. Top-of-Book (BBO) Spread Tightness [0, 20 pts]
        val spreadPct = if (lastPrice > 0.0 && ask >= bid && bid > 0.0) {
            ((ask - bid) / lastPrice) * 100.0
        } else {
            999.0 // Invalid book
        }

        val spreadScore = (20.0 * ((0.35 - spreadPct) / (0.35 - 0.06))).coerceIn(0.0, 20.0)

        // 5. Base Liquidity Depth [0, 15 pts]
        val liquidityScore = if (quoteVolumeUsdt > 0.0) {
            val logVol = log10(quoteVolumeUsdt)
            (15.0 * ((logVol - LOG_MIN_VOLUME) / LOG_VOLUME_RANGE)).coerceIn(0.0, 15.0)
        } else {
            0.0
        }

        val totalScore = (rvolScore + velocityScore + rangeScore + spreadScore + liquidityScore).coerceIn(0.0, 100.0)

        return MarketActivityScore(
            pair = pair,
            totalScore = totalScore,
            rvolScore = rvolScore,
            velocityScore = velocityScore,
            rangeScore = rangeScore,
            spreadScore = spreadScore,
            liquidityScore = liquidityScore,
            rvol = rvolResult.rvol,
            v15m = velocityResult.v15m,
            v1h = velocityResult.v1h,
            rangePct = rangePct,
            spreadPct = spreadPct,
            quoteVolumeUsdt = quoteVolumeUsdt,
            isWarmed = rvolResult.isWarmed && velocityResult.isWarmed1h
        )
    }
}
