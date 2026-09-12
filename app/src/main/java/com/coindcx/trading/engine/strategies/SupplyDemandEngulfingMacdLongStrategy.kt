package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.StrategyDiagnostics
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Institutional Long Trading Strategy
 * Confluence: Demand Zones + Bullish Engulfing Reversal + Contemporaneous MACD Momentum Confirmation.
 * Pure, deterministic strategy with zero look-ahead bias, fee-adjusted Net R:R, and structural supply clearance.
 */
class SupplyDemandEngulfingMacdLongStrategy(
    private val fastMacd: Int = 12,
    private val slowMacd: Int = 26,
    private val signalMacd: Int = 9,
    private val adxPeriod: Int = 14,
    private val adxMin: Double = 18.0,
    private val emaTrendPeriod: Int = 200,
    private val atrPeriod: Int = 14,
    private val defaultLeverage: Int = 2,
    private val maxZoneAge: Int = 300
) : Strategy {

    override val id: String = "supply_demand_engulfing_macd_long"
    override val name: String = "Supply & Demand Long"
    override val description: String = "Institutional long strategy combining Demand Zone bounces, Bullish Engulfing candlestick patterns, and MACD bullish momentum confirmation with strict supply zone clearance."
    override val parametersSummary: String = "MACD: $fastMacd/$slowMacd/$signalMacd | ADX Min: $adxMin | Trend: ${emaTrendPeriod}EMA | SL: Demand Low - 0.3x ATR"
    override val requiredCandleCount: Int = 215
    override val defaultTimeframe: String = "15m"

    enum class ZoneType { SUPPLY, DEMAND }

    data class Zone(
        val id: Int,
        val type: ZoneType,
        val high: Double,
        val low: Double,
        val createdIndex: Int,
        var touchCount: Int = 0,
        var invalidated: Boolean = false
    )

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Insufficient candle history (${candles.size}/$requiredCandleCount)",
                confidenceScore = 0.0
            )
        }

        val sortedCandles = if (candles.size >= 2 && candles[0].time > candles[1].time) {
            candles.sortedBy { it.time }
        } else {
            candles
        }

        val lastIndex = sortedCandles.size - 1
        val currentCandle = sortedCandles.last()
        val currentPrice = currentCandle.close
        val closePrices = sortedCandles.map { it.close }

        // 1. Calculate Technical Indicators (with contemporaneous local rolling ATR series)
        val atrSeries = TechnicalIndicators.calculateAtrSeries(sortedCandles, atrPeriod)
        val atr = atrSeries.lastOrNull() ?: 0.0
        if (atr <= 0.0) {
            return Signal(SignalAction.HOLD, reason = "Invalid ATR value", confidenceScore = 0.0)
        }

        val adx = TechnicalIndicators.calculateAdx(sortedCandles, adxPeriod)
        val ema200List = TechnicalIndicators.calculateEma(closePrices, emaTrendPeriod)
        if (ema200List.size < 6) {
            return Signal(SignalAction.HOLD, reason = "EMA 200 warming up", confidenceScore = 0.0)
        }

        val macdPoints = TechnicalIndicators.calculateMacd(closePrices, fastMacd, slowMacd, signalMacd)
        if (macdPoints.size < 5) {
            return Signal(SignalAction.HOLD, reason = "MACD calculation warming up", confidenceScore = 0.0)
        }

        // 2. Compute Active Supply & Demand Zones (using contemporaneous rolling ATR series)
        val (supplyZones, demandZones) = detectZones(sortedCandles, atrSeries)

        // 3. Active Position Management (Exit rules)
        if (activePosition != null && activePosition.isOpen && !activePosition.isShort) {
            // A. Target Check: Price reaching nearest active Supply Zone bottom
            val nearestSupply = findNearestSupplyAbove(supplyZones, currentPrice)
            if (nearestSupply != null && currentCandle.high >= (nearestSupply.low - 0.1 * atr)) {
                return Signal(
                    action = SignalAction.EXIT,
                    reason = "Supply Zone reached (${String.format("%.2f", nearestSupply.low)}). Target 1 filled.",
                    confidenceScore = 90.0
                )
            }

            // B. Momentum Invalidation: Bearish MACD crossover exit
            val latestMacd = macdPoints.last()
            val prevMacd = macdPoints[macdPoints.size - 2]
            if (prevMacd.histogram >= 0.0 && latestMacd.histogram < 0.0) {
                return Signal(
                    action = SignalAction.EXIT,
                    reason = "Bearish MACD momentum reversal exit",
                    confidenceScore = 85.0
                )
            }

            return Signal(
                action = SignalAction.HOLD,
                reason = "Active Long running; Demand zone structure intact",
                confidenceScore = 60.0
            )
        }

        // 4. Regime & Choppiness Filter: Suppress signals during sideways chop
        if (adx < adxMin) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "ADX (${String.format("%.1f", adx)}) < $adxMin: Market in low-volatility consolidation",
                confidenceScore = 20.0,
                diagnostics = StrategyDiagnostics(
                    stage = "REGIME_FILTER",
                    failedFilter = "CHOP_FILTER_ACTIVE",
                    indicators = mapOf("adx" to adx, "adxMin" to adxMin, "atr" to atr),
                    mathDetails = mapOf("adx" to String.format("%.1f", adx), "adxMin" to "$adxMin")
                )
            )
        }

        // 5. Dual Trend Regime Filter
        val currEma200 = ema200List.last()
        val prevEma200 = ema200List[ema200List.size - 6]
        val isTrendContinuation = currentPrice > currEma200 && currEma200 >= prevEma200
        val isExhaustionBottom = currentPrice <= currEma200 && (currEma200 - currentPrice) >= (2.5 * atr)

        if (!isTrendContinuation && !isExhaustionBottom) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Trend filter: Not in upward trend continuation nor 2.5x ATR exhaustion bottom",
                confidenceScore = 30.0,
                diagnostics = StrategyDiagnostics(
                    stage = "TREND_FILTER",
                    failedFilter = "NOT_TREND_OR_EXHAUSTION",
                    indicators = mapOf("currentPrice" to currentPrice, "ema200" to currEma200, "prevEma200" to prevEma200, "atr" to atr),
                    flags = mapOf("isTrendContinuation" to isTrendContinuation, "isExhaustionBottom" to isExhaustionBottom)
                )
            )
        }

        // 6. Signal Candle Trigger: Bullish Engulfing
        val prevCandle = sortedCandles[lastIndex - 1]
        val isEngulfing = isValidBullishEngulfing(currentCandle, prevCandle, atr)
        if (!isEngulfing) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "No bullish engulfing reversal candle detected at bar close",
                confidenceScore = 40.0,
                diagnostics = StrategyDiagnostics(
                    stage = "CANDLE_TRIGGER",
                    failedFilter = "NOT_BULLISH_ENGULFING",
                    indicators = mapOf("currentPrice" to currentPrice, "atr" to atr),
                    mathDetails = mapOf("open" to "${currentCandle.open}", "close" to "${currentCandle.close}", "high" to "${currentCandle.high}", "low" to "${currentCandle.low}")
                )
            )
        }

        val activeDemand = findDemandZoneProbed(demandZones, currentCandle)
        if (activeDemand == null) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Bullish engulfing candle did not probe an active Demand Zone",
                confidenceScore = 35.0,
                diagnostics = StrategyDiagnostics(
                    stage = "ZONE_PROBE",
                    failedFilter = "NO_ACTIVE_DEMAND_PROBED",
                    indicators = mapOf("activeDemandCount" to demandZones.size.toDouble(), "activeSupplyCount" to supplyZones.size.toDouble()),
                    mathDetails = mapOf("candleLow" to "${currentCandle.low}", "candleClose" to "${currentCandle.close}")
                )
            )
        }

        // Demand zone must be fresh (max 1 prior test)
        if (activeDemand.touchCount > 1) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Demand zone mitigated (touch count ${activeDemand.touchCount} > 1)",
                confidenceScore = 25.0,
                diagnostics = StrategyDiagnostics(
                    stage = "ZONE_FRESHNESS",
                    failedFilter = "DEMAND_ZONE_MITIGATED",
                    indicators = mapOf("touchCount" to activeDemand.touchCount.toDouble())
                )
            )
        }

        // Price Extension Gate: prevent longing if price has already extended > 2.5 ATR above demand
        if ((currentPrice - activeDemand.high) > (2.5 * atr)) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Price extended > 2.5x ATR from demand zone high",
                confidenceScore = 20.0,
                diagnostics = StrategyDiagnostics(
                    stage = "PRICE_EXTENSION",
                    failedFilter = "EXTENDED_FROM_DEMAND",
                    indicators = mapOf("extensionAtr" to (currentPrice - activeDemand.high) / atr, "maxAllowed" to 2.5)
                )
            )
        }

        // Stop loss placement: Zone Low - ATR Buffer
        val stopLoss = min(currentCandle.low, activeDemand.low) - (0.3 * atr)
        val riskDist = currentPrice - stopLoss

        if (riskDist <= 0.0 || riskDist > (3.0 * atr)) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Risk distance (${String.format("%.2f", riskDist)}) out of bounds (<= 0 or > 3.0x ATR)",
                confidenceScore = 20.0,
                diagnostics = StrategyDiagnostics(
                    stage = "RISK_BOUNDS",
                    failedFilter = "RISK_OUT_OF_BOUNDS",
                    indicators = mapOf("riskDist" to riskDist, "atr" to atr, "riskInAtr" to riskDist / atr)
                )
            )
        }

        // 7. Fee-Aware Net Target Calculation (Clearing Net R:R >= 1.80 after 0.20% friction)
        val grossRiskPct = (riskDist / currentPrice) * 100.0
        val reqTargetPct = 1.80 * (grossRiskPct + 0.20) + 0.20
        val reqRewardDist = currentPrice * (reqTargetPct / 100.0)
        val rewardDist = max(2.0 * riskDist, reqRewardDist)

        val nearestSupply = findNearestSupplyAbove(supplyZones, currentPrice)
        if (nearestSupply != null) {
            val potentialReward = nearestSupply.low - (currentPrice + 0.2 * atr)
            if (potentialReward < rewardDist) {
                return Signal(
                    action = SignalAction.HOLD,
                    reason = "Vetoed: Reward clearance (${String.format("%.2f", potentialReward / riskDist)}R) < required net fee-adjusted threshold (${String.format("%.2f", rewardDist / riskDist)}R)",
                    confidenceScore = 20.0,
                    diagnostics = StrategyDiagnostics(
                        stage = "NET_RR_CLEARANCE",
                        failedFilter = "INSUFFICIENT_REWARD_CLEARANCE",
                        indicators = mapOf("potentialReward" to potentialReward, "rewardDistRequired" to rewardDist, "riskDist" to riskDist),
                        mathDetails = mapOf("clearanceR" to String.format("%.2f", potentialReward / riskDist), "requiredR" to String.format("%.2f", rewardDist / riskDist))
                    )
                )
            }
        }

        val clearanceDistance = if (nearestSupply != null) nearestSupply.low - currentPrice else Double.MAX_VALUE
        if (clearanceDistance < rewardDist || clearanceDistance < (1.0 * atr)) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Vetoed: Too close to unmitigated Supply Zone at ${String.format("%.2f", nearestSupply?.low ?: 0.0)}",
                confidenceScore = 15.0,
                diagnostics = StrategyDiagnostics(
                    stage = "ZONE_CLEARANCE",
                    failedFilter = "TOO_CLOSE_TO_SUPPLY",
                    indicators = mapOf("clearanceDist" to clearanceDistance, "requiredDist" to rewardDist)
                )
            )
        }

        // 8. Contemporaneous Momentum Condition
        val latestMacd = macdPoints.last()
        val prevMacd = macdPoints[macdPoints.size - 2]
        val isExpandingUpward = latestMacd.histogram >= prevMacd.histogram
        val isBullishState = latestMacd.macd > latestMacd.signal

        if (!isExpandingUpward && !isBullishState) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "MACD momentum expanding downward at signal candle",
                confidenceScore = 30.0,
                diagnostics = StrategyDiagnostics(
                    stage = "MOMENTUM_FILTER",
                    failedFilter = "MACD_EXPANDING_DOWNWARD",
                    indicators = mapOf("macd" to latestMacd.macd, "signal" to latestMacd.signal, "hist" to latestMacd.histogram, "prevHist" to prevMacd.histogram),
                    flags = mapOf("isExpandingUpward" to isExpandingUpward, "isBullishState" to isBullishState)
                )
            )
        }

        // Structural Target calculation: fee-aware target
        val target1 = currentPrice + rewardDist

        // Confidence scoring
        var score = 65.0
        if (activeDemand.touchCount == 0) score += 15.0 // Virgin demand zone
        if (adx >= 25.0) score += 10.0                 // Strong directional movement
        if (isTrendContinuation) score += 10.0         // Macro trend alignment
        val finalScore = score.coerceIn(70.0, 98.0)

        val branchTag = if (isTrendContinuation) "TREND_CONTINUATION" else "EXHAUSTION_BOTTOM"

        return Signal(
            action = SignalAction.ENTER_LONG,
            suggestedLeverage = defaultLeverage,
            stopLossPrice = stopLoss,
            takeProfitPrice = target1,
            reason = "[$branchTag] Demand Zone Bounce (${String.format("%.2f", activeDemand.low)}-${String.format("%.2f", activeDemand.high)}) + Bullish Engulfing + Contemporaneous MACD",
            confidenceScore = finalScore,
            diagnostics = StrategyDiagnostics(
                stage = "ACTIONABLE_SIGNAL",
                indicators = mapOf("entryPrice" to currentPrice, "stopLoss" to stopLoss, "target1" to target1, "adx" to adx, "atr" to atr, "confidence" to finalScore),
                flags = mapOf("isTrendContinuation" to isTrendContinuation, "isVirginZone" to (activeDemand.touchCount == 0)),
                mathDetails = mapOf("branch" to branchTag, "riskPct" to String.format("%.2f", grossRiskPct), "netRR" to ">=1.80")
            )
        )
    }

    /**
     * Pure algorithmic detection of Supply & Demand zones with contemporaneous local rolling ATR,
     * structure-break filter (K=5), and zone width constraints.
     */
    private fun detectZones(candles: List<MarketCandle>, atrSeries: List<Double>): Pair<List<Zone>, List<Zone>> {
        val supplyZones = ArrayList<Zone>()
        val demandZones = ArrayList<Zone>()
        var zoneIdCounter = 1

        val volSmaPeriod = 20
        if (candles.size < volSmaPeriod + 5) return Pair(supplyZones, demandZones)

        for (i in volSmaPeriod until candles.size - 1) {
            val curr = candles[i]
            val localAtr = atrSeries.getOrElse(i) { atrSeries.lastOrNull() ?: 0.0 }
            if (localAtr <= 0.0) continue

            val avgVol = candles.subList(i - volSmaPeriod, i).map { it.volume }.average()

            // Supply Zone (Displacement Drop)
            val isBearishDisplacement = (curr.open - curr.close >= 1.5 * localAtr) && (curr.volume >= 1.3 * avgVol)
            if (isBearishDisplacement) {
                if (i >= 5) {
                    val prior5Lows = (1..5).map { candles[i - it].low }
                    val minPriorLow = prior5Lows.minOrNull() ?: Double.MAX_VALUE
                    if (curr.close >= minPriorLow) {
                        continue
                    }
                }

                val baseBars = ArrayList<MarketCandle>()
                for (bIdx in 1..3) {
                    if (i - bIdx < 0) break
                    val b = candles[i - bIdx]
                    if (abs(b.close - b.open) <= 1.0 * localAtr) {
                        baseBars.add(b)
                    } else {
                        break
                    }
                }

                if (baseBars.isNotEmpty()) {
                    val zHigh = baseBars.maxOf { it.high }
                    val zLow = baseBars.maxOf { max(it.open, it.close) }
                    if ((zHigh - zLow) <= 2.5 * localAtr) {
                        supplyZones.add(Zone(zoneIdCounter++, ZoneType.SUPPLY, zHigh, zLow, i))
                    }
                }
            }

            // Demand Zone (Displacement Rally)
            val isBullishDisplacement = (curr.close - curr.open >= 1.5 * localAtr) && (curr.volume >= 1.3 * avgVol)
            if (isBullishDisplacement) {
                if (i >= 5) {
                    val prior5Highs = (1..5).map { candles[i - it].high }
                    val maxPriorHigh = prior5Highs.maxOrNull() ?: 0.0
                    if (curr.close <= maxPriorHigh) {
                        continue
                    }
                }

                val baseBars = ArrayList<MarketCandle>()
                for (bIdx in 1..3) {
                    if (i - bIdx < 0) break
                    val b = candles[i - bIdx]
                    if (abs(b.close - b.open) <= 1.0 * localAtr) {
                        baseBars.add(b)
                    } else {
                        break
                    }
                }

                if (baseBars.isNotEmpty()) {
                    val zLow = baseBars.minOf { it.low }
                    val zHigh = baseBars.minOf { min(it.open, it.close) }
                    if ((zHigh - zLow) <= 2.5 * localAtr) {
                        demandZones.add(Zone(zoneIdCounter++, ZoneType.DEMAND, zHigh, zLow, i))
                    }
                }
            }
        }

        val lastIndex = candles.size - 1

        // Invalidation and touch tracking
        for (sz in supplyZones) {
            var inVisit = false
            for (i in (sz.createdIndex + 1)..lastIndex) {
                val c = candles[i]
                val localAtr = atrSeries.getOrElse(i) { atrSeries.lastOrNull() ?: 0.0 }
                if (c.close > sz.high) {
                    sz.invalidated = true
                    break
                }
                if (c.high >= sz.low && c.close <= sz.high) {
                    if (!inVisit) {
                        sz.touchCount++
                        inVisit = true
                    }
                } else if (c.high < (sz.low - 1.0 * localAtr)) {
                    inVisit = false
                }
            }
            if ((lastIndex - sz.createdIndex) > maxZoneAge) {
                sz.invalidated = true
            }
        }

        for (dz in demandZones) {
            var inVisit = false
            for (i in (dz.createdIndex + 1)..lastIndex) {
                val c = candles[i]
                val localAtr = atrSeries.getOrElse(i) { atrSeries.lastOrNull() ?: 0.0 }
                if (c.close < dz.low) {
                    dz.invalidated = true
                    break
                }
                if (c.low <= dz.high && c.close >= dz.low) {
                    if (!inVisit) {
                        dz.touchCount++
                        inVisit = true
                    }
                } else if (c.low > (dz.high + 1.0 * localAtr)) {
                    inVisit = false
                }
            }
            if ((lastIndex - dz.createdIndex) > maxZoneAge) {
                dz.invalidated = true
            }
        }

        val activeSupply = supplyZones.filter { !it.invalidated }
        val activeDemand = demandZones.filter { !it.invalidated }

        return Pair(activeSupply, activeDemand)
    }

    private fun isValidBullishEngulfing(curr: MarketCandle, prev: MarketCandle, atr: Double): Boolean {
        if (prev.close >= prev.open || curr.close <= curr.open) return false

        val bodyEngulfed = (curr.open <= prev.close + 0.05 * atr) && (curr.close > prev.open)
        if (!bodyEngulfed) return false

        val currRange = curr.high - curr.low
        val currBody = curr.close - curr.open
        val upperWick = curr.high - curr.close

        if (currRange <= 0.0) return false
        if ((currBody / currRange) < 0.60) return false
        if (upperWick > (0.25 * currRange)) return false
        if (currRange < (0.75 * atr) || currRange > (2.5 * atr)) return false

        return true
    }

    private fun findDemandZoneProbed(zones: List<Zone>, candle: MarketCandle): Zone? {
        return zones.lastOrNull { dz ->
            !dz.invalidated && (candle.low <= dz.high && candle.close >= dz.low)
        }
    }

    private fun findNearestSupplyAbove(zones: List<Zone>, price: Double): Zone? {
        return zones.filter { !it.invalidated && it.low > price }.minByOrNull { it.low }
    }
}
