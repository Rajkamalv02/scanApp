package com.coindcx.trading.engine.scanner

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import kotlin.math.abs
import kotlin.math.max

enum class QualityCategory {
    REJECT,
    WATCH,
    ACCEPTABLE,
    PRIME
}

data class TradeQualityResult(
    val totalScore: Int, // strictly 0 to 100
    val category: QualityCategory,
    val isApproved: Boolean,
    val rejectionReason: String?,
    val htfScore: Int,
    val regimeScore: Int,
    val confluenceScore: Int,
    val volumeScore: Int,
    val rrScore: Int,
    val extensionScore: Int,
    val adxValue: Double,
    val netRiskRewardRatio: Double
)

/**
 * Institutional Trade Quality Scoring Engine (0-100 Matrix).
 * Evaluates candidate setups with non-overlapping gap-free rubrics,
 * fee-adjusted Net R:R, and strict clamping.
 */
object TradeQualityScorer {

    private const val FEE_FRICTION_PERCENT = 0.20 // 0.10% round-trip taker + 0.10% slippage

    fun evaluateQuality(
        candles: List<MarketCandle>,
        htfCandles: List<MarketCandle>?,
        signal: Signal,
        currentPrice: Double,
        pair: String = ""
    ): TradeQualityResult {
        val isBuy = signal.action == SignalAction.ENTER_LONG
        var fatalRejectionReason: String? = null

        // 1. Higher-Timeframe (1h) 50 EMA Alignment (Max 25 pts, Floor -15 pts)
        val htfPrices = htfCandles?.map { it.close } ?: candles.map { it.close }
        val htfEma50List = TechnicalIndicators.calculateEma(htfPrices, 50)
        val htfScore = if (htfEma50List.isNotEmpty()) {
            val htfEma50 = htfEma50List.last()
            if (isBuy) {
                if (currentPrice >= htfEma50) 25 else -15
            } else {
                if (currentPrice <= htfEma50) 25 else -15
            }
        } else {
            10 // Neutral fallback if not enough HTF bars
        }

        // 2. Market Regime Strength (ADX 14) (Max 20 pts)
        val adx = TechnicalIndicators.calculateAdx(candles, 14)
        val regimeScore = when {
            adx >= 25.0 -> 20
            adx >= 20.0 -> 12
            else -> 0 // Chop penalty
        }

        // 3. Strategy Confluence (Max 20 pts)
        val confluenceScore = when {
            signal.confidenceScore >= 75.0 -> 20
            signal.confidenceScore >= 60.0 -> 10
            else -> 0
        }

        // 4. Volume Participation (Max 15 pts) - Gap-free
        val volumeScore = if (candles.size >= 21) {
            val latestVol = candles.last().volume
            val avgVol20 = candles.dropLast(1).takeLast(20).map { it.volume }.average()
            val relVol = if (avgVol20 > 0) latestVol / avgVol20 else 1.0
            when {
                relVol >= 1.5 -> 15
                relVol >= 1.2 -> 10
                relVol >= 1.0 -> 5
                else -> 0
            }
        } else {
            5
        }

        // 5. Fee-Adjusted Net Risk-to-Reward (Max 10 pts)
        var netRr = 0.0
        val rrScore = if (signal.takeProfitPrice != null && signal.stopLossPrice != null && currentPrice > 0.0) {
            netRr = calculateNetRiskReward(currentPrice, signal.takeProfitPrice, signal.stopLossPrice)

            if (netRr < 1.5) {
                fatalRejectionReason = "Net R:R %.2f < 1.5 after fees".format(netRr)
                0
            } else when {
                netRr >= 2.0 -> 10
                else -> 6
            }
        } else {
            fatalRejectionReason = "Missing Stop-Loss or Take-Profit targets"
            0
        }

        // 6. Price Extension / Pullback Quality (Max 10 pts) - Gap-free
        val closes = candles.map { it.close }
        val ema20List = TechnicalIndicators.calculateEma(closes, 20)
        val atr = TechnicalIndicators.calculateAtr(candles, 14)
        val extensionScore = if (ema20List.isNotEmpty() && atr > 0.0) {
            val ema20 = ema20List.last()
            val dist = abs(currentPrice - ema20)
            val ratio = dist / atr
            when {
                ratio <= 1.2 -> 10 // Optimal pullback entry
                ratio <= 2.0 -> 5  // Moderate extension
                else -> 0         // Overextended / chasing
            }
        } else {
            5
        }

        // Raw Total & Strict [0, 100] Clamping
        val rawTotal = htfScore + regimeScore + confluenceScore + volumeScore + rrScore + extensionScore
        val clampedScore = rawTotal.coerceIn(0, 100)

        val category = when {
            clampedScore >= 80 -> QualityCategory.PRIME
            clampedScore >= 70 -> QualityCategory.ACCEPTABLE
            clampedScore >= 50 -> QualityCategory.WATCH
            else -> QualityCategory.REJECT
        }

        val isApproved = clampedScore >= 70 && fatalRejectionReason == null
        val rejectionReason = when {
            fatalRejectionReason != null -> fatalRejectionReason
            clampedScore < 70 -> "Score %d/100 < 70 threshold".format(clampedScore)
            else -> null
        }

        return TradeQualityResult(
            totalScore = clampedScore,
            category = category,
            isApproved = isApproved,
            rejectionReason = rejectionReason,
            htfScore = htfScore,
            regimeScore = regimeScore,
            confluenceScore = confluenceScore,
            volumeScore = volumeScore,
            rrScore = rrScore,
            extensionScore = extensionScore,
            adxValue = adx,
            netRiskRewardRatio = netRr
        )
    }

    fun calculateNetRiskReward(
        entryPrice: Double,
        takeProfit: Double,
        stopLoss: Double
    ): Double {
        if (entryPrice <= 0.0) return 0.0
        val grossTargetPct = (abs(takeProfit - entryPrice) / entryPrice) * 100.0
        val grossRiskPct = (abs(entryPrice - stopLoss) / entryPrice) * 100.0
        val netTargetPct = max(0.0, grossTargetPct - FEE_FRICTION_PERCENT)
        val netRiskPct = grossRiskPct + FEE_FRICTION_PERCENT
        return if (netRiskPct > 0) netTargetPct / netRiskPct else 0.0
    }
}
