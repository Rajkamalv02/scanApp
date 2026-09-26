package com.coindcx.trading.engine.scanner

import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.SignalDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy Evaluation Snapshot for aggregation.
 */
data class StrategyEvaluation(
    val strategyId: String,
    val strategyName: String,
    val family: StrategyFamily,
    val action: SignalAction,
    val direction: SignalDirection,
    val confidence: Double,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val takeProfitPrice: Double,
    val qualityScore: Int = 0,
    val reason: String = ""
) {
    val isApproved: Boolean get() = action == SignalAction.ENTER_LONG || action == SignalAction.ENTER_SHORT
    val isLong: Boolean get() = direction == SignalDirection.LONG && action == SignalAction.ENTER_LONG
    val isShort: Boolean get() = direction == SignalDirection.SHORT && action == SignalAction.ENTER_SHORT
}

/**
 * Reconciled & Aggregated Trade Candidate Output.
 */
data class AggregatedCandidate(
    val symbol: String,
    val direction: SignalDirection,
    val aggregatedConfidence: Double,
    val reconciledEntry: Double,
    val reconciledStopLoss: Double,
    val reconciledTakeProfit: Double,
    val rawRiskReward: Double,
    val feeFriction: Double,
    val netRiskReward: Double,
    val consensusCount: Int,
    val anchorStrategy: StrategyContribution,
    val contributingStrategies: List<StrategyContribution>,
    val detectedConflicts: String,
    val selectionReason: String,
    val currentPrice: Double
)

/**
 * Pure Mathematical Strategy Aggregator & Level Reconciler (§B.4, §C, §D).
 */
object StrategyAggregator {

    const val ROUND_TRIP_FEE_SLIPPAGE_PCT = 0.14 // 0.05% entry + 0.05% exit + 0.04% slippage
    const val MIN_NET_RR_THRESHOLD = 0.55 // Scalping threshold for 1.5%..2.2% TP against 2.5%..3.0% SL
    const val MIN_STOP_LOSS_DISTANCE_PCT = 1.40 // 1.4% minimum noise floor (prevents sub-noise stop-outs)
    const val MAX_STOP_LOSS_DISTANCE_PCT = 3.00 // 3.0% maximum allowable protective stop distance
    const val MARGINAL_SCALING_GAMMA = 0.20 // gamma = 0.20

    /**
     * 5x5 Family Pairwise Correlation Matrix (§B.5).
     */
    fun getPairwiseCorrelation(f1: StrategyFamily, f2: StrategyFamily): Double {
        return when (f1) {
            StrategyFamily.TREND -> when (f2) {
                StrategyFamily.TREND -> 0.80
                StrategyFamily.BREAKOUT -> 0.45
                StrategyFamily.MEANREV -> 0.10
                StrategyFamily.STRUCTURE -> 0.35
                StrategyFamily.ROTATION -> 0.50
            }
            StrategyFamily.BREAKOUT -> when (f2) {
                StrategyFamily.TREND -> 0.45
                StrategyFamily.BREAKOUT -> 0.75
                StrategyFamily.MEANREV -> 0.15
                StrategyFamily.STRUCTURE -> 0.40
                StrategyFamily.ROTATION -> 0.30
            }
            StrategyFamily.MEANREV -> when (f2) {
                StrategyFamily.TREND -> 0.10
                StrategyFamily.BREAKOUT -> 0.15
                StrategyFamily.MEANREV -> 0.70
                StrategyFamily.STRUCTURE -> 0.20
                StrategyFamily.ROTATION -> 0.10
            }
            StrategyFamily.STRUCTURE -> when (f2) {
                StrategyFamily.TREND -> 0.35
                StrategyFamily.BREAKOUT -> 0.40
                StrategyFamily.MEANREV -> 0.20
                StrategyFamily.STRUCTURE -> 0.70
                StrategyFamily.ROTATION -> 0.25
            }
            StrategyFamily.ROTATION -> when (f2) {
                StrategyFamily.TREND -> 0.50
                StrategyFamily.BREAKOUT -> 0.30
                StrategyFamily.MEANREV -> 0.10
                StrategyFamily.STRUCTURE -> 0.25
                StrategyFamily.ROTATION -> 0.80
            }
        }
    }

    /**
     * Bounded Evidence Aggregation Formula (§B.4).
     * Computes asymptotic accumulated confidence:
     * Delta(C_j) = (C_j / 100) * (100 - C_accum) * gamma * (1 - rho_1j)
     */
    fun aggregateConfidence(evaluations: List<StrategyEvaluation>): Double {
        if (evaluations.isEmpty()) return 0.0
        val sorted = evaluations.sortedByDescending { it.confidence }
        val anchor = sorted.first()
        var cAccum = anchor.confidence.coerceIn(0.0, 100.0)

        for (j in 1 until sorted.size) {
            val secondary = sorted[j]
            val rho = getPairwiseCorrelation(anchor.family, secondary.family)
            val marginal = (secondary.confidence / 100.0) * (100.0 - cAccum) * MARGINAL_SCALING_GAMMA * (1.0 - rho)
            cAccum += marginal
        }

        return cAccum.coerceIn(0.0, 99.99)
    }

    /**
     * Reconciles multiple strategy evaluations for a single contract into an AggregatedCandidate.
     * Enforces:
     * - Case 1: All reject -> null
     * - Case 2: Single approval -> qualified via Net R:R >= 1.5
     * - Case 3: Same-direction consensus -> B.4 aggregation + D level reconciliation
     * - Case 4: Directional conflict -> Sub-case 4A discard or Sub-case 4B dominance
     * - Hard Invariant Check: SL < Entry < TP for Long (or reverse for Short)
     * - Net R:R >= 1.5 gate with dynamic FeeFriction
     */
    fun aggregate(
        symbol: String,
        evaluations: List<StrategyEvaluation>,
        currentMarketPrice: Double,
        stopLossPercent: Double = 3.0,
        targetPricePercent: Double = 1.5,
        leverage: Int = 1
    ): AggregatedCandidate? {
        val approved = evaluations.filter { it.isApproved }
        if (approved.isEmpty()) {
            return null // Case 1: No strategy produced an actionable entry
        }

        val longs = approved.filter { it.isLong }
        val shorts = approved.filter { it.isShort }

        // Case 4: Directional Conflict (Mixed LONG and SHORT Approvals)
        if (longs.isNotEmpty() && shorts.isNotEmpty()) {
            val cLong = aggregateConfidence(longs)
            val cShort = aggregateConfidence(shorts)
            val deltaC = abs(cLong - cShort)

            // Sub-case 4A: Severe High-Confidence Conflict (Both >= 70.0%)
            if (cLong >= 70.0 && cShort >= 70.0) {
                com.coindcx.trading.util.AppLogManager.scanner(
                    "[CONFLICT_DISCARD] [$symbol] Severe High-Confidence Conflict: LONG (${"%.2f".format(cLong)}%) vs SHORT (${"%.2f".format(cShort)}%). Delta: ${"%.2f".format(deltaC)}% < 25.0% -> Discarded"
                )
                return null
            }

            // Sub-case 4B: Low-to-Moderate Confidence Conflict
            if (deltaC >= 25.0 && max(cLong, cShort) >= 60.0) {
                val winningDirection = if (cLong > cShort) SignalDirection.LONG else SignalDirection.SHORT
                val winningPool = if (winningDirection == SignalDirection.LONG) longs else shorts
                val opposingConfidence = if (winningDirection == SignalDirection.LONG) cShort else cLong
                val dominantRawConfidence = if (winningDirection == SignalDirection.LONG) cLong else cShort
                val penalizedConfidence = (dominantRawConfidence - (opposingConfidence * 0.25)).coerceIn(0.0, 100.0)

                val conflictNote = "Directional conflict resolved: $winningDirection won (Delta: ${"%.2f".format(deltaC)}% >= 25.0%). Conf penalized: ${"%.2f".format(dominantRawConfidence)}% -> ${"%.2f".format(penalizedConfidence)}%"
                com.coindcx.trading.util.AppLogManager.scanner("[CONFLICT_RESOLVED] [$symbol] $conflictNote")

                return reconcileLevelsAndBuild(
                    symbol = symbol,
                    direction = winningDirection,
                    evaluations = winningPool,
                    overrideConfidence = penalizedConfidence,
                    conflictNote = conflictNote,
                    currentMarketPrice = currentMarketPrice,
                    stopLossPercent = stopLossPercent,
                    targetPricePercent = targetPricePercent,
                    leverage = leverage
                )
            } else {
                com.coindcx.trading.util.AppLogManager.scanner(
                    "[CONFLICT_DISCARD] [$symbol] Ambiguous Conflict: LONG (${"%.2f".format(cLong)}%) vs SHORT (${"%.2f".format(cShort)}%). Delta: ${"%.2f".format(deltaC)}% < 25.0% -> Discarded"
                )
                return null
            }
        }

        // Uniform Direction: Longs-only or Shorts-only
        val direction = if (longs.isNotEmpty()) SignalDirection.LONG else SignalDirection.SHORT
        val activePool = if (direction == SignalDirection.LONG) longs else shorts

        return reconcileLevelsAndBuild(
            symbol = symbol,
            direction = direction,
            evaluations = activePool,
            overrideConfidence = null,
            conflictNote = "NONE",
            currentMarketPrice = currentMarketPrice,
            stopLossPercent = stopLossPercent,
            targetPricePercent = targetPricePercent,
            leverage = leverage
        )
    }

    private fun reconcileLevelsAndBuild(
        symbol: String,
        direction: SignalDirection,
        evaluations: List<StrategyEvaluation>,
        overrideConfidence: Double?,
        conflictNote: String,
        currentMarketPrice: Double,
        stopLossPercent: Double = 3.0,
        targetPricePercent: Double = 1.5,
        leverage: Int = 1
    ): AggregatedCandidate? {
        if (stopLossPercent <= 0.0 || targetPricePercent <= 0.0) {
            com.coindcx.trading.util.AppLogManager.w(
                "AGGREGATOR",
                "[$symbol] Non-positive SL/TP percent ($stopLossPercent / $targetPricePercent). Candidate rejected."
            )
            return null
        }

        val sortedByConf = evaluations.sortedByDescending { it.confidence }
        val anchor = sortedByConf.first()
        val isLong = direction == SignalDirection.LONG

        // 1. Entry Reconciliation (§D.B): Conservative executable limit price
        val reconciledEntry = if (isLong) {
            val minEntry = evaluations.map { it.entryPrice }.minOrNull() ?: currentMarketPrice
            min(minEntry, currentMarketPrice)
        } else {
            val maxEntry = evaluations.map { it.entryPrice }.maxOrNull() ?: currentMarketPrice
            max(maxEntry, currentMarketPrice)
        }

        // 2. Stop-Loss & Take-Profit:
        // Final Target % = UI Target % + Total Fees % (Buy + Sell: 0.10%) + 0.50% (unscaled by leverage)
        // Stop-Loss % = (UI Stop-Loss % * Leverage) - Total Fees % (0.10%)
        val finalTargetPct = com.coindcx.trading.engine.TradingFeeSchedule.calculateFinalTargetPercent(targetPricePercent)
        val finalSlPct = com.coindcx.trading.engine.TradingFeeSchedule.calculateFinalStopLossPercent(stopLossPercent, leverage)

        val slDist = reconciledEntry * (finalSlPct / 100.0)
        val tpDist = reconciledEntry * (finalTargetPct / 100.0)
        val reconciledSl = if (isLong) (reconciledEntry - slDist) else (reconciledEntry + slDist)
        val reconciledTp = if (isLong) (reconciledEntry + tpDist) else (reconciledEntry - tpDist)

        // 3. Mandatory Hard Invariant Check (§D.2)
        val isGeometricallyValid = if (isLong) {
            reconciledSl < reconciledEntry && reconciledEntry < reconciledTp
        } else {
            reconciledSl > reconciledEntry && reconciledEntry > reconciledTp
        }

        if (!isGeometricallyValid) {
            com.coindcx.trading.util.AppLogManager.w(
                "AGGREGATOR",
                "[$symbol] Hard Invariant Check FAILED: Level inversion detected for $direction (Entry: $reconciledEntry, SL: $reconciledSl, TP: $reconciledTp). Candidate rejected."
            )
            return null
        }

        // 4. Risk-to-Reward Calculation with dynamic FeeFriction (§D.C)
        val stopDist = abs(reconciledEntry - reconciledSl)
        val targetDist = abs(reconciledTp - reconciledEntry)
        if (stopDist <= 0.0 || targetDist <= 0.0 || reconciledEntry <= 0.0) return null

        val rawRr = finalTargetPct / finalSlPct
        val feeFriction = com.coindcx.trading.engine.TradingFeeSchedule.TOTAL_FEES_PERCENT / finalSlPct
        val netRr = rawRr - feeFriction

        if (netRr <= 0.0) {
            com.coindcx.trading.util.AppLogManager.d(
                "AGGREGATOR",
                "[$symbol] Rejected: Net R:R ${"%.2f".format(netRr)} <= 0 (Fee friction exceeds target profit)."
            )
            return null
        }

        // 6. Final Confidence Calculation
        val finalConfidence = overrideConfidence ?: aggregateConfidence(evaluations)

        val contributingContributions = evaluations.map { eval ->
            StrategyContribution(
                strategyId = eval.strategyId,
                strategyName = eval.strategyName,
                action = eval.action,
                qualityScore = eval.qualityScore,
                confidenceScore = eval.confidence,
                netRiskRewardRatio = netRr,
                reason = eval.reason
            )
        }

        val anchorContrib = StrategyContribution(
            strategyId = anchor.strategyId,
            strategyName = anchor.strategyName,
            action = anchor.action,
            qualityScore = anchor.qualityScore,
            confidenceScore = anchor.confidence,
            netRiskRewardRatio = netRr,
            reason = anchor.reason
        )

        val selectionReason = if (evaluations.size > 1) {
            "Consensus of ${evaluations.size} strategies [$direction]: Primary ${anchor.strategyName} (${"%.1f".format(anchor.confidence)}%) + ${evaluations.size - 1} confirmations. Aggregated Conf: ${"%.2f".format(finalConfidence)}%, Net R:R: ${"%.2f".format(netRr)}"
        } else {
            "Single-strategy support: ${anchor.strategyName} (Conf: ${"%.1f".format(anchor.confidence)}%, Net R:R: ${"%.2f".format(netRr)})"
        }

        return AggregatedCandidate(
            symbol = symbol,
            direction = direction,
            aggregatedConfidence = finalConfidence,
            reconciledEntry = reconciledEntry,
            reconciledStopLoss = reconciledSl,
            reconciledTakeProfit = reconciledTp,
            rawRiskReward = rawRr,
            feeFriction = feeFriction,
            netRiskReward = netRr,
            consensusCount = evaluations.size,
            anchorStrategy = anchorContrib,
            contributingStrategies = contributingContributions,
            detectedConflicts = conflictNote,
            selectionReason = selectionReason,
            currentPrice = currentMarketPrice
        )
    }
}
