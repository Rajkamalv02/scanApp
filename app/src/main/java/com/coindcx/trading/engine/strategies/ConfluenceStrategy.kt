package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.StrategyDiagnostics
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.util.AppLogManager
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure, Institutional Confluence Engine Strategy.
 *
 * Direct Kotlin implementation of the Pine Script confluence_engine_v0_2_2.
 * Combines 4 analytical layers:
 * 1. Market Structure (Pivots, Swings, Regime, BOS / CHOCH).
 * 2. Liquidity Sweeps (Wick breaks swing point, close rejects inside).
 * 3. Supply & Demand Order Block Zones (Anchored to last opposite candle before confirmed break).
 * 4. Impulse Engine (Volatility-normalized price change with exponential decay).
 *
 * Two-Stage Reversal State Machine:
 * - Stage 1 (ARM): Liquidity sweep into an active same-direction zone.
 * - Stage 2 (CONFIRM): Confirmed same-direction BOS or CHOCH within [confirmWindow] bars.
 * - Cancellation: Active zone mitigated prior to confirmation.
 * - Expiry: Setup expires silently if confirmation window elapses.
 */
class ConfluenceStrategy(
    val swingLen: Int = 5,
    val maxLookback: Int = 30,
    val confirmWindow: Int = 20,
    val impulseLen: Int = 5,
    val madLen: Int = 20,
    val impulseThresh: Double = 1.0,
    val decayRate: Double = 0.9,
    val freshThresh: Double = 0.5,
    val riskRewardRatio: Double = 0.75,
    val atrMultiplier: Double = 1.5,
    val stopLossPercent: Double = 3.0,
    val targetPricePercent: Double = 1.5
) : Strategy {

    override val id: String = "confluence"
    override val name: String = "Confluence Engine Strategy"
    override val description: String = "Smart Money Concepts reversal strategy combining Liquidity Sweeps, Supply/Demand Zones, Structure Breaks, and Impulse Volatility Normalization."
    override val defaultTimeframe: String = "15m"
    override val requiredCandleCount: Int = 100
    override val preferredRegime: com.coindcx.trading.engine.MarketRegimePreference = com.coindcx.trading.engine.MarketRegimePreference.MEAN_REVERTING_RANGE

    override val parametersSummary: String
        get() = "Swing: $swingLen, ZoneLookback: $maxLookback, ConfirmWindow: ${confirmWindow}b, ImpulseMAD: $madLen, R:R: 1:${riskRewardRatio.toInt()}"

    init {
        AppLogManager.i("STRATEGY", "Initialized $name: $parametersSummary | Default TF=$defaultTimeframe")
    }

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?, pair: String): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Insufficient candles: ${candles.size}/$requiredCandleCount required for warmup",
                confidenceScore = 0.0,
                strategyId = id,
                strategyName = name
            )
        }

        val sortedCandles = if (candles.size > 1 && candles[0].time > candles.last().time) {
            candles.sortedBy { it.time }
        } else {
            candles
        }

        // Evaluate state machine on confirmed (closed) bars only.
        // Index size - 1 is the live forming bar.
        val confirmedCandles = sortedCandles.dropLast(1)
        if (confirmedCandles.size < requiredCandleCount - 1) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Confirmed candle series warming up",
                confidenceScore = 0.0,
                strategyId = id,
                strategyName = name
            )
        }

        val currentPrice = sortedCandles.last().close
        val atr = TechnicalIndicators.calculateAtr(sortedCandles, 14).coerceAtLeast(0.0001)

        // --- State Machine Variables ---
        var lastSwingHigh: Double? = null
        var previousSwingHigh: Double? = null
        var lastSwingLow: Double? = null
        var previousSwingLow: Double? = null
        var structureState = 0 // 1: Bullish, -1: Bearish, 2: Transition, 0: Undefined

        var highSwept = false
        var lowSwept = false

        var demandZoneTop: Double? = null
        var demandZoneBottom: Double? = null
        var demandZoneActive = false
        var demandZoneTapped = false

        var supplyZoneTop: Double? = null
        var supplyZoneBottom: Double? = null
        var supplyZoneActive = false
        var supplyZoneTapped = false

        var bullishArmed = false
        var bearishArmed = false
        var bullishArmedBar = -1
        var bearishArmedBar = -1
        var bullishArmedSweepDirection = 0
        var bearishArmedSweepDirection = 0
        var bullishArmedTwoSidedSweep = false
        var bearishArmedTwoSidedSweep = false

        // Impulse Engine State
        var impulse = 0.0
        var impulseDir = 0
        var impulseAboveThresh = false
        var impulseAge: Int? = null

        // Confirmed Signal Feature Snapshot
        var lastSignalDirection = 0
        var lastSignalBar = -1
        var lastSignalStructureEvent = 0
        var lastSignalSweepDirection = 0
        var lastSignalTwoSidedSweep = false
        var lastSignalImpulseDirection = 0
        var lastSignalImpulseMagnitude = 0.0
        var lastSignalImpulseFreshness = 0.0
        var lastSignalImpulseAge: Int? = null
        var lastSignalImpulseAligned = false
        var lastSignalBarsFromArm: Int? = null

        var latestConfluenceEvent = 0
        var latestStructureEvent = 0

        // Pre-extract primitive arrays to eliminate per-bar heap allocations and unlock raw CPU caching
        val n = confirmedCandles.size
        val closes = DoubleArray(n)
        val highs = DoubleArray(n)
        val lows = DoubleArray(n)
        val opens = DoubleArray(n)
        for (idx in 0 until n) {
            val c = confirmedCandles[idx]
            closes[idx] = c.close
            highs[idx] = c.high
            lows[idx] = c.low
            opens[idx] = c.open
        }

        // Process bars chronologically
        for (i in 0 until n) {
            val currClose = closes[i]
            val barHigh = highs[i]
            val barLow = lows[i]
            val prevClose = if (i > 0) closes[i - 1] else currClose

            // 1. Pivot Detection (swingLen = 5)
            // A candle at index k = i - swingLen is confirmed as a pivot at bar i.
            if (i >= 2 * swingLen) {
                val pivotIdx = i - swingLen
                val candPivotHigh = highs[pivotIdx]
                val candPivotLow = lows[pivotIdx]

                // Check Pivot High
                var isPivotHigh = true
                for (j in 1..swingLen) {
                    if (candPivotHigh <= highs[pivotIdx - j] || candPivotHigh < highs[pivotIdx + j]) {
                        isPivotHigh = false
                        break
                    }
                }
                if (isPivotHigh) {
                    previousSwingHigh = lastSwingHigh
                    lastSwingHigh = candPivotHigh
                    highSwept = false
                }

                // Check Pivot Low
                var isPivotLow = true
                for (j in 1..swingLen) {
                    if (candPivotLow >= lows[pivotIdx - j] || candPivotLow > lows[pivotIdx + j]) {
                        isPivotLow = false
                        break
                    }
                }
                if (isPivotLow) {
                    previousSwingLow = lastSwingLow
                    lastSwingLow = candPivotLow
                    lowSwept = false
                }
            }

            // Snapshot current swings and zones as immutable local vals for compiler smart-casting
            val curHigh = lastSwingHigh
            val prevHigh = previousSwingHigh
            val curLow = lastSwingLow
            val prevLow = previousSwingLow

            // 2. Structure Regime Classification
            val hasHighPair = curHigh != null && prevHigh != null
            val hasLowPair = curLow != null && prevLow != null
            val higherHigh = curHigh != null && prevHigh != null && curHigh > prevHigh
            val lowerHigh = curHigh != null && prevHigh != null && curHigh < prevHigh
            val higherLow = curLow != null && prevLow != null && curLow > prevLow
            val lowerLow = curLow != null && prevLow != null && curLow < prevLow

            if (higherHigh && higherLow) {
                structureState = 1
            } else if (lowerHigh && lowerLow) {
                structureState = -1
            } else if (hasHighPair && hasLowPair) {
                structureState = 2
            }

            // 3. Structure Break Detection (BOS / CHOCH)
            val bullishBreak = curHigh != null && currClose > curHigh && prevClose <= curHigh
            val bearishBreak = curLow != null && currClose < curLow && prevClose >= curLow
            val stateBeforeBreak = structureState
            var structureEvent = 0

            if (bullishBreak) {
                structureEvent = when (stateBeforeBreak) {
                    -1 -> 2 // Bullish CHOCH
                    1 -> 1  // Bullish BOS
                    else -> 3 // Bullish Structural Break
                }
                structureState = 1
            } else if (bearishBreak) {
                structureEvent = when (stateBeforeBreak) {
                    1 -> -2 // Bearish CHOCH
                    -1 -> -1 // Bearish BOS
                    else -> -3 // Bearish Structural Break
                }
                structureState = -1
            }
            if (i == n - 1) {
                latestStructureEvent = structureEvent
            }

            // 4. Liquidity Sweep Detection
            val bearishSweep = curHigh != null && !highSwept && barHigh > curHigh && currClose <= curHigh
            val bullishSweep = curLow != null && !lowSwept && barLow < curLow && currClose >= curLow

            if (bearishSweep) highSwept = true
            if (bullishSweep) lowSwept = true

            // Snapshot active zone bounds
            val curDTop = demandZoneTop
            val curDBottom = demandZoneBottom
            val curSTop = supplyZoneTop
            val curSBottom = supplyZoneBottom

            // 5. Zone Mitigation (Independent variables to prevent mitigation clobbering)
            var demandZoneEvent = 0
            var supplyZoneEvent = 0

            if (demandZoneActive && curDBottom != null && currClose < curDBottom) {
                demandZoneActive = false
                demandZoneEvent = 3 // Mitigated
            }
            if (supplyZoneActive && curSTop != null && currClose > curSTop) {
                supplyZoneActive = false
                supplyZoneEvent = -3 // Mitigated
            }

            // 6. Zone Tap Detection
            if (demandZoneActive && !demandZoneTapped && curDTop != null && curDBottom != null &&
                barLow <= curDTop && barHigh >= curDBottom
            ) {
                demandZoneTapped = true
                demandZoneEvent = 2
            }
            if (supplyZoneActive && !supplyZoneTapped && curSTop != null && curSBottom != null &&
                barLow <= curSTop && barHigh >= curSBottom
            ) {
                supplyZoneTapped = true
                supplyZoneEvent = -2
            }

            // 7. Zone Creation on Confirmed Break
            if (structureEvent == 1 || structureEvent == 2) {
                var demandOffset: Int? = null
                for (offset in 1..maxLookback) {
                    val idx = i - offset
                    if (idx >= 0 && closes[idx] < opens[idx]) {
                        demandOffset = offset
                        break // Anchor found, early exit
                    }
                }
                if (demandOffset != null) {
                    val anchorIdx = i - demandOffset
                    demandZoneTop = highs[anchorIdx]
                    demandZoneBottom = lows[anchorIdx]
                    demandZoneActive = true
                    demandZoneTapped = false
                    demandZoneEvent = 1
                }
            }

            if (structureEvent == -1 || structureEvent == -2) {
                var supplyOffset: Int? = null
                for (offset in 1..maxLookback) {
                    val idx = i - offset
                    if (idx >= 0 && closes[idx] > opens[idx]) {
                        supplyOffset = offset
                        break // Anchor found, early exit
                    }
                }
                if (supplyOffset != null) {
                    val anchorIdx = i - supplyOffset
                    supplyZoneTop = highs[anchorIdx]
                    supplyZoneBottom = lows[anchorIdx]
                    supplyZoneActive = true
                    supplyZoneTapped = false
                    supplyZoneEvent = -1
                }
            }

            // 8. Impulse Engine (Zero-allocation primitive MAD-normalized price velocity)
            if (i >= madLen) {
                val startIdx = i - madLen + 1
                var sum = 0.0
                for (k in startIdx..i) {
                    sum += closes[k]
                }
                val impulseMean = sum / madLen

                var madSum = 0.0
                for (k in startIdx..i) {
                    madSum += abs(closes[k] - impulseMean)
                }
                val impulseMad = madSum / madLen
                val pastClose = if (i >= impulseLen) closes[i - impulseLen] else currClose
                val rawImpulse = if (impulseMad > 0) (currClose - pastClose) / impulseMad else 0.0
                val absImpulse = abs(rawImpulse)
                val currentImpulseDir = if (rawImpulse > 0) 1 else if (rawImpulse < 0) -1 else 0

                val nowAboveThresh = absImpulse > impulseThresh
                impulseAboveThresh = nowAboveThresh

                if (nowAboveThresh) {
                    impulse = absImpulse
                    impulseDir = currentImpulseDir
                    impulseAge = 0
                } else {
                    impulse *= decayRate
                    impulseAge = if (impulse > 0) ((impulseAge ?: 0) + 1) else null
                }
            }
            val impulseFreshness = if (impulseThresh > 0) min(impulse / impulseThresh, 1.0) else 0.0

            // 9. Confluence Logic (2-Stage Reversal State Machine)
            var confluenceEvent = 0

            // Stage 2: Confirmation
            if (bullishArmed && (structureEvent == 1 || structureEvent == 2) && (i - bullishArmedBar) <= confirmWindow) {
                val barsFromArm = i - bullishArmedBar
                bullishArmed = false
                confluenceEvent = 2 // Bullish Signal Confirmed!

                lastSignalDirection = 1
                lastSignalBar = i
                lastSignalStructureEvent = structureEvent
                lastSignalSweepDirection = bullishArmedSweepDirection
                lastSignalTwoSidedSweep = bullishArmedTwoSidedSweep
                lastSignalImpulseDirection = impulseDir
                lastSignalImpulseMagnitude = impulse
                lastSignalImpulseFreshness = impulseFreshness
                lastSignalImpulseAge = impulseAge
                lastSignalImpulseAligned = (impulseDir == 1)
                lastSignalBarsFromArm = barsFromArm
            }

            if (bearishArmed && (structureEvent == -1 || structureEvent == -2) && (i - bearishArmedBar) <= confirmWindow) {
                val barsFromArm = i - bearishArmedBar
                bearishArmed = false
                confluenceEvent = -2 // Bearish Signal Confirmed!

                lastSignalDirection = -1
                lastSignalBar = i
                lastSignalStructureEvent = structureEvent
                lastSignalSweepDirection = bearishArmedSweepDirection
                lastSignalTwoSidedSweep = bearishArmedTwoSidedSweep
                lastSignalImpulseDirection = impulseDir
                lastSignalImpulseMagnitude = impulse
                lastSignalImpulseFreshness = impulseFreshness
                lastSignalImpulseAge = impulseAge
                lastSignalImpulseAligned = (impulseDir == -1)
                lastSignalBarsFromArm = barsFromArm
            }

            // Cancellation (Mitigation of armed zone)
            if (bullishArmed && demandZoneEvent == 3) {
                bullishArmed = false
                confluenceEvent = 3
            }
            if (bearishArmed && supplyZoneEvent == -3) {
                bearishArmed = false
                confluenceEvent = -3
            }

            // Expiry
            if (bullishArmed && (i - bullishArmedBar) > confirmWindow) {
                bullishArmed = false
            }
            if (bearishArmed && (i - bearishArmedBar) > confirmWindow) {
                bearishArmed = false
            }

            // Stage 1: Arming (Directional sweep overlapping active same-side zone)
            if (!bullishArmed && bullishSweep && demandZoneActive && demandZoneTop != null && demandZoneBottom != null &&
                barLow <= demandZoneTop && barHigh >= demandZoneBottom
            ) {
                bullishArmed = true
                bullishArmedBar = i
                bullishArmedSweepDirection = 1
                bullishArmedTwoSidedSweep = bullishSweep && bearishSweep
                confluenceEvent = 1
            }

            if (!bearishArmed && bearishSweep && supplyZoneActive && supplyZoneTop != null && supplyZoneBottom != null &&
                barLow <= supplyZoneTop && barHigh >= supplyZoneBottom
            ) {
                bearishArmed = true
                bearishArmedBar = i
                bearishArmedSweepDirection = -1
                bearishArmedTwoSidedSweep = bullishSweep && bearishSweep
                confluenceEvent = -1
            }

            if (i == confirmedCandles.lastIndex) {
                latestConfluenceEvent = confluenceEvent
            }
        }

        val impulseFreshness = if (impulseThresh > 0) min(impulse / impulseThresh, 1.0) else 0.0

        val diag = StrategyDiagnostics(
            stage = when {
                latestConfluenceEvent == 2 || latestConfluenceEvent == -2 -> "STAGE_2_CONFIRMED"
                bullishArmed || bearishArmed -> "STAGE_1_ARMED"
                demandZoneActive || supplyZoneActive -> "ZONE_ACTIVE"
                else -> "REGIME_MONITORING"
            },
            indicators = buildMap {
                demandZoneTop?.let { put("demandZoneTop", it) }
                demandZoneBottom?.let { put("demandZoneBottom", it) }
                supplyZoneTop?.let { put("supplyZoneTop", it) }
                supplyZoneBottom?.let { put("supplyZoneBottom", it) }
                lastSwingHigh?.let { put("lastSwingHigh", it) }
                lastSwingLow?.let { put("lastSwingLow", it) }
                put("impulseMagnitude", impulse)
                put("impulseFreshness", impulseFreshness)
                impulseAge?.let { put("impulseAge", it.toDouble()) }
                put("atr", atr)
                put("lastSignalDirection", lastSignalDirection.toDouble())
                put("lastSignalBar", lastSignalBar.toDouble())
            },
            flags = mapOf(
                "bullishArmed" to bullishArmed,
                "bearishArmed" to bearishArmed,
                "demandZoneActive" to demandZoneActive,
                "supplyZoneActive" to supplyZoneActive,
                "impulseAboveThresh" to impulseAboveThresh,
                "impulseAligned" to lastSignalImpulseAligned,
                "impulseFresh" to (impulseFreshness >= freshThresh)
            ),
            mathDetails = mapOf(
                "structureState" to when (structureState) { 1 -> "BULLISH"; -1 -> "BEARISH"; 2 -> "TRANSITION"; else -> "UNDEFINED" },
                "latestStructureEvent" to when (latestStructureEvent) {
                    1 -> "BULLISH_BOS"; 2 -> "BULLISH_CHOCH"; 3 -> "BULLISH_STRUCTURAL"
                    -1 -> "BEARISH_BOS"; -2 -> "BEARISH_CHOCH"; -3 -> "BEARISH_STRUCTURAL"
                    else -> "NONE"
                },
                "latestConfluenceEvent" to when (latestConfluenceEvent) {
                    1 -> "BULLISH_ARMED"; 2 -> "BULLISH_CONFIRMED"; 3 -> "BULLISH_CANCELLED"
                    -1 -> "BEARISH_ARMED"; -2 -> "BEARISH_CONFIRMED"; -3 -> "BEARISH_CANCELLED"
                    else -> "NONE"
                }
            )
        )

        // --- Active Position Management ---
        if (activePosition != null && activePosition.isOpen) {
            if (activePosition.isLong) {
                if (latestConfluenceEvent == -2) {
                    AppLogManager.trade("STRATEGY", "Long position exit triggered by confirmed Bearish Confluence Signal on $pair @ %.4f".format(currentPrice))
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Bearish Confluence Reversal: Short signal confirmed. Trend reversal exit.",
                        confidenceScore = 90.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                if (activePosition.takeProfitTrigger != null && currentPrice >= activePosition.takeProfitTrigger) {
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Take Profit target filled @ %.4f".format(currentPrice),
                        confidenceScore = 95.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                if (activePosition.stopLossTrigger != null && currentPrice <= activePosition.stopLossTrigger) {
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Stop Loss trigger hit @ %.4f".format(currentPrice),
                        confidenceScore = 95.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                return Signal(
                    action = SignalAction.HOLD,
                    reason = "Holding Long position. Structural Demand: %.4f - %.4f".format(demandZoneBottom ?: 0.0, demandZoneTop ?: 0.0),
                    confidenceScore = 50.0,
                    diagnostics = diag,
                    strategyId = id,
                    strategyName = name
                )
            } else if (activePosition.isShort) {
                if (latestConfluenceEvent == 2) {
                    AppLogManager.trade("STRATEGY", "Short position exit triggered by confirmed Bullish Confluence Signal on $pair @ %.4f".format(currentPrice))
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Bullish Confluence Reversal: Long signal confirmed. Trend reversal exit.",
                        confidenceScore = 90.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                if (activePosition.takeProfitTrigger != null && currentPrice <= activePosition.takeProfitTrigger) {
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Take Profit target filled @ %.4f".format(currentPrice),
                        confidenceScore = 95.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                if (activePosition.stopLossTrigger != null && currentPrice >= activePosition.stopLossTrigger) {
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Stop Loss trigger hit @ %.4f".format(currentPrice),
                        confidenceScore = 95.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                return Signal(
                    action = SignalAction.HOLD,
                    reason = "Holding Short position. Structural Supply: %.4f - %.4f".format(supplyZoneBottom ?: 0.0, supplyZoneTop ?: 0.0),
                    confidenceScore = 50.0,
                    diagnostics = diag,
                    strategyId = id,
                    strategyName = name
                )
            }
        }

        // --- New Entry Signal Generation on Fresh Confirmed Reversal ---
        if (latestConfluenceEvent == 2) {
            val tradeId = AppLogManager.TradeIdGenerator.generate(pair.ifEmpty { "SMC" })
            val riskDistance = currentPrice * (stopLossPercent / 100.0)
            val stopLossPrice = currentPrice - riskDistance
            val clampedTpDist = currentPrice * (targetPricePercent / 100.0)
            val takeProfitPrice = currentPrice + clampedTpDist
            val slDistPct = stopLossPercent
            val tpDistPct = targetPricePercent
            val actualRR = targetPricePercent / stopLossPercent

            var confidence = 80.0
            if (lastSignalImpulseAligned) confidence += 10.0
            if (lastSignalImpulseFreshness >= freshThresh) confidence += 5.0
            if (lastSignalTwoSidedSweep) confidence += 3.0
            confidence = confidence.coerceAtMost(98.0)

            AppLogManager.tradeLifecycle(
                event = "SIGNAL_GENERATED",
                tradeId = tradeId,
                symbol = pair.ifEmpty { "FUTURES" },
                mode = "EVAL",
                attributes = mapOf(
                    "side" to "LONG",
                    "strategy" to id,
                    "entry_price" to "%.4f".format(currentPrice),
                    "structure_event" to when (lastSignalStructureEvent) { 1 -> "BOS"; 2 -> "CHOCH"; else -> "STRUCTURAL" },
                    "bars_from_arm" to (lastSignalBarsFromArm ?: 0),
                    "sweep_direction" to lastSignalSweepDirection,
                    "two_sided_sweep" to lastSignalTwoSidedSweep,
                    "impulse_direction" to lastSignalImpulseDirection,
                    "impulse_magnitude" to "%.4f".format(lastSignalImpulseMagnitude),
                    "impulse_freshness" to "%.2f".format(lastSignalImpulseFreshness),
                    "impulse_age" to (lastSignalImpulseAge ?: 0),
                    "impulse_aligned" to lastSignalImpulseAligned,
                    "demand_zone_top" to "%.4f".format(demandZoneTop ?: 0.0),
                    "demand_zone_bottom" to "%.4f".format(demandZoneBottom ?: 0.0),
                    "stop_loss" to "%.4f".format(stopLossPrice),
                    "target" to "%.4f".format(takeProfitPrice),
                    "sl_dist_pct" to "%.2f%%".format(slDistPct),
                    "tp_dist_pct" to "%.2f%%".format(tpDistPct),
                    "rr_ratio" to "1:%.2f".format(actualRR),
                    "confidence" to confidence
                ),
                narrative = "Bullish Confluence SIGNAL confirmed: Sweep into Demand Zone (%.4f-%.4f) followed by %s break after %d bars | Impulse Aligned: %s (Freshness: %.2f) -> Entry=%.4f, SL=%.4f (dist: %.4f), TP=%.4f (dist: %.4f)"
                    .format(demandZoneBottom ?: 0.0, demandZoneTop ?: 0.0, when (lastSignalStructureEvent) { 1 -> "BOS"; 2 -> "CHOCH"; else -> "BREAK" }, lastSignalBarsFromArm ?: 0, lastSignalImpulseAligned, lastSignalImpulseFreshness, currentPrice, stopLossPrice, riskDistance, takeProfitPrice, takeProfitPrice - currentPrice)
            )

            return Signal(
                action = SignalAction.ENTER_LONG,
                tradeId = tradeId,
                entryPrice = currentPrice,
                atr = atr,
                atrMultiplier = atrMultiplier,
                riskDistance = riskDistance,
                riskRewardRatio = actualRR,
                stopLossPrice = stopLossPrice,
                takeProfitPrice = takeProfitPrice,
                confidenceScore = confidence,
                reason = "Bullish Confluence Reversal: Liquidity sweep into Demand Zone confirmed by ${if (lastSignalStructureEvent == 2) "CHOCH" else "BOS"}",
                diagnostics = diag,
                strategyId = id,
                strategyName = name
            )
        }

        if (latestConfluenceEvent == -2) {
            val tradeId = AppLogManager.TradeIdGenerator.generate(pair.ifEmpty { "SMC" })
            val riskDistance = currentPrice * (stopLossPercent / 100.0)
            val stopLossPrice = currentPrice + riskDistance
            val clampedTpDist = currentPrice * (targetPricePercent / 100.0)
            val takeProfitPrice = currentPrice - clampedTpDist
            val slDistPct = stopLossPercent
            val tpDistPct = targetPricePercent
            val actualRR = targetPricePercent / stopLossPercent

            var confidence = 80.0
            if (lastSignalImpulseAligned) confidence += 10.0
            if (lastSignalImpulseFreshness >= freshThresh) confidence += 5.0
            if (lastSignalTwoSidedSweep) confidence += 3.0
            confidence = confidence.coerceAtMost(98.0)

            AppLogManager.tradeLifecycle(
                event = "SIGNAL_GENERATED",
                tradeId = tradeId,
                symbol = pair.ifEmpty { "FUTURES" },
                mode = "EVAL",
                attributes = mapOf(
                    "side" to "SHORT",
                    "strategy" to id,
                    "entry_price" to "%.4f".format(currentPrice),
                    "structure_event" to when (lastSignalStructureEvent) { -1 -> "BOS"; -2 -> "CHOCH"; else -> "STRUCTURAL" },
                    "bars_from_arm" to (lastSignalBarsFromArm ?: 0),
                    "sweep_direction" to lastSignalSweepDirection,
                    "two_sided_sweep" to lastSignalTwoSidedSweep,
                    "impulse_direction" to lastSignalImpulseDirection,
                    "impulse_magnitude" to "%.4f".format(lastSignalImpulseMagnitude),
                    "impulse_freshness" to "%.2f".format(lastSignalImpulseFreshness),
                    "impulse_age" to (lastSignalImpulseAge ?: 0),
                    "impulse_aligned" to lastSignalImpulseAligned,
                    "supply_zone_top" to "%.4f".format(supplyZoneTop ?: 0.0),
                    "supply_zone_bottom" to "%.4f".format(supplyZoneBottom ?: 0.0),
                    "stop_loss" to "%.4f".format(stopLossPrice),
                    "target" to "%.4f".format(takeProfitPrice),
                    "sl_dist_pct" to "%.2f%%".format(slDistPct),
                    "tp_dist_pct" to "%.2f%%".format(tpDistPct),
                    "rr_ratio" to "1:%.2f".format(actualRR),
                    "confidence" to confidence
                ),
                narrative = "Bearish Confluence SIGNAL confirmed: Sweep into Supply Zone (%.4f-%.4f) followed by %s break after %d bars | Impulse Aligned: %s (Freshness: %.2f) -> Entry=%.4f, SL=%.4f (dist: %.4f), TP=%.4f (dist: %.4f)"
                    .format(supplyZoneBottom ?: 0.0, supplyZoneTop ?: 0.0, when (lastSignalStructureEvent) { -1 -> "BOS"; -2 -> "CHOCH"; else -> "BREAK" }, lastSignalBarsFromArm ?: 0, lastSignalImpulseAligned, lastSignalImpulseFreshness, currentPrice, stopLossPrice, riskDistance, takeProfitPrice, currentPrice - takeProfitPrice)
            )

            return Signal(
                action = SignalAction.ENTER_SHORT,
                tradeId = tradeId,
                entryPrice = currentPrice,
                atr = atr,
                atrMultiplier = atrMultiplier,
                riskDistance = riskDistance,
                riskRewardRatio = actualRR,
                stopLossPrice = stopLossPrice,
                takeProfitPrice = takeProfitPrice,
                confidenceScore = confidence,
                reason = "Bearish Confluence Reversal: Liquidity sweep into Supply Zone confirmed by ${if (lastSignalStructureEvent == -2) "CHOCH" else "BOS"}",
                diagnostics = diag,
                strategyId = id,
                strategyName = name
            )
        }

        // --- Regime Monitoring / In-Progress Setup (Hold) ---
        val statusText = when {
            bullishArmed -> "Bullish Setup ARMED (${confirmedCandles.lastIndex - bullishArmedBar}/$confirmWindow bars). Awaiting confirmed BOS/CHOCH."
            bearishArmed -> "Bearish Setup ARMED (${confirmedCandles.lastIndex - bearishArmedBar}/$confirmWindow bars). Awaiting confirmed BOS/CHOCH."
            demandZoneActive -> "Demand Zone active (%.4f - %.4f). Awaiting sweep into zone.".format(demandZoneBottom ?: 0.0, demandZoneTop ?: 0.0)
            supplyZoneActive -> "Supply Zone active (%.4f - %.4f). Awaiting sweep into zone.".format(supplyZoneBottom ?: 0.0, supplyZoneTop ?: 0.0)
            else -> "Market structure: %s. Awaiting structural setup.".format(if (structureState == 1) "Bullish" else if (structureState == -1) "Bearish" else "Mixed")
        }

        return Signal(
            action = SignalAction.HOLD,
            reason = statusText,
            confidenceScore = if (bullishArmed || bearishArmed) 60.0 else 30.0,
            diagnostics = diag,
            strategyId = id,
            strategyName = name
        )
    }
}
