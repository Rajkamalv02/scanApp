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
 * Institutional Short Trading Strategy
 * Confluence: Supply & Demand Zones + Bearish Engulfing Reversal + MACD Momentum Confirmation.
 * Pure, deterministic strategy with zero look-ahead bias and mathematical risk management.
 */
class SupplyDemandEngulfingMacdStrategy(
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

    override val id: String = "supply_demand_engulfing_macd"
    override val name: String = "Supply & Demand Short"
    override val description: String = "Institutional short strategy combining Supply Zone rejections, Bearish Engulfing candlestick patterns, and MACD bearish momentum confirmation with strict demand zone clearance."
    override val parametersSummary: String = "MACD: $fastMacd/$slowMacd/$signalMacd | ADX Min: $adxMin | Trend: ${emaTrendPeriod}EMA | SL: Supply High + 0.3x ATR"
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
        if (activePosition != null && activePosition.isOpen && activePosition.isShort) {
            // A. Target Check: Price reaching nearest active Demand Zone top
            val nearestDemand = findNearestDemandBelow(demandZones, currentPrice)
            if (nearestDemand != null && currentCandle.low <= (nearestDemand.high + 0.1 * atr)) {
                return Signal(
                    action = SignalAction.EXIT,
                    reason = "Demand Zone reached (${String.format("%.2f", nearestDemand.high)}). Target 1 filled.",
                    confidenceScore = 90.0
                )
            }

            // B. Momentum Invalidation: Bullish MACD crossover exit
            val latestMacd = macdPoints.last()
            val prevMacd = macdPoints[macdPoints.size - 2]
            if (prevMacd.histogram <= 0.0 && latestMacd.histogram > 0.0) {
                return Signal(
                    action = SignalAction.EXIT,
                    reason = "Bullish MACD momentum reversal exit",
                    confidenceScore = 85.0
                )
            }

            return Signal(
                action = SignalAction.HOLD,
                reason = "Active Short running; Supply zone structure intact",
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
        val isTrendContinuation = currentPrice < currEma200 && currEma200 <= prevEma200
        val isExhaustionTop = currentPrice >= currEma200 && (currentPrice - currEma200) >= (2.5 * atr)

        if (!isTrendContinuation && !isExhaustionTop) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Trend filter: Not in downward trend continuation nor 2.5x ATR exhaustion top",
                confidenceScore = 30.0,
                diagnostics = StrategyDiagnostics(
                    stage = "TREND_FILTER",
                    failedFilter = "NOT_TREND_OR_EXHAUSTION",
                    indicators = mapOf("currentPrice" to currentPrice, "ema200" to currEma200, "prevEma200" to prevEma200, "atr" to atr),
                    flags = mapOf("isTrendContinuation" to isTrendContinuation, "isExhaustionTop" to isExhaustionTop)
                )
            )
        }

        // 6. Direct Rejection Execution: Check if the latest completed candle is a Bearish Engulfing pattern
        val engulfCandle = currentCandle
        val prevCandle = sortedCandles[lastIndex - 1]

        if (!isValidBearishEngulfing(engulfCandle, prevCandle, atr)) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "No valid bearish engulfing pattern on latest completed candle",
                confidenceScore = 35.0,
                diagnostics = StrategyDiagnostics(
                    stage = "CANDLE_TRIGGER",
                    failedFilter = "NOT_BEARISH_ENGULFING",
                    indicators = mapOf("currentPrice" to currentPrice, "atr" to atr),
                    mathDetails = mapOf("open" to "${engulfCandle.open}", "close" to "${engulfCandle.close}", "high" to "${engulfCandle.high}", "low" to "${engulfCandle.low}")
                )
            )
        }

        // Volume Participation Threshold (>= 1.0x VolSMA20)
        if (lastIndex >= 20) {
            val avgVol = sortedCandles.subList(lastIndex - 20, lastIndex).map { it.volume }.average()
            if (engulfCandle.volume < 1.0 * avgVol) {
                return Signal(
                    action = SignalAction.HOLD,
                    reason = "Engulfing volume (${String.format("%.1f", engulfCandle.volume)}) < 1.0x 20-bar volume SMA (${String.format("%.1f", avgVol)})",
                    confidenceScore = 30.0,
                    diagnostics = StrategyDiagnostics(
                        stage = "VOLUME_GATE",
                        failedFilter = "LOW_ENGULFING_VOLUME",
                        indicators = mapOf("volume" to engulfCandle.volume, "avgVolume20" to avgVol),
                        mathDetails = mapOf("volRatio" to String.format("%.2f", if (avgVol > 0) engulfCandle.volume / avgVol else 1.0))
                    )
                )
            }
        }

        // Check if engulfing candle probed an active Supply Zone
        val activeSupply = findSupplyZoneProbed(supplyZones, engulfCandle)
        if (activeSupply == null) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Bearish engulfing candle did not probe an active Supply Zone",
                confidenceScore = 35.0,
                diagnostics = StrategyDiagnostics(
                    stage = "ZONE_PROBE",
                    failedFilter = "NO_ACTIVE_SUPPLY_PROBED",
                    indicators = mapOf("activeSupplyCount" to supplyZones.size.toDouble(), "activeDemandCount" to demandZones.size.toDouble()),
                    mathDetails = mapOf("candleHigh" to "${engulfCandle.high}", "candleClose" to "${engulfCandle.close}")
                )
            )
        }

        // Supply zone must be fresh (max 1 prior test)
        if (activeSupply.touchCount > 1) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Supply zone mitigated (touch count ${activeSupply.touchCount} > 1)",
                confidenceScore = 25.0,
                diagnostics = StrategyDiagnostics(
                    stage = "ZONE_FRESHNESS",
                    failedFilter = "SUPPLY_ZONE_MITIGATED",
                    indicators = mapOf("touchCount" to activeSupply.touchCount.toDouble())
                )
            )
        }

        // Price Extension Gate: prevent shorting if price has already extended > 2.5 ATR from supply
        if ((activeSupply.low - currentPrice) > (2.5 * atr)) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Price extended > 2.5x ATR from supply zone low",
                confidenceScore = 20.0,
                diagnostics = StrategyDiagnostics(
                    stage = "PRICE_EXTENSION",
                    failedFilter = "EXTENDED_FROM_SUPPLY",
                    indicators = mapOf("extensionAtr" to (activeSupply.low - currentPrice) / atr, "maxAllowed" to 2.5)
                )
            )
        }

        // Stop loss placement: Zone High + ATR Buffer
        val stopLoss = max(engulfCandle.high, activeSupply.high) + (0.3 * atr)
        val riskDist = stopLoss - currentPrice

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

        val nearestDemand = findNearestDemandBelow(demandZones, currentPrice)
        if (nearestDemand != null) {
            val potentialReward = currentPrice - (nearestDemand.high + 0.2 * atr)
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

        val clearanceDistance = if (nearestDemand != null) currentPrice - nearestDemand.high else Double.MAX_VALUE
        if (clearanceDistance < rewardDist || clearanceDistance < (1.0 * atr)) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Vetoed: Too close to unmitigated Demand Zone at ${String.format("%.2f", nearestDemand?.high ?: 0.0)}",
                confidenceScore = 15.0,
                diagnostics = StrategyDiagnostics(
                    stage = "ZONE_CLEARANCE",
                    failedFilter = "TOO_CLOSE_TO_DEMAND",
                    indicators = mapOf("clearanceDist" to clearanceDistance, "requiredDist" to rewardDist)
                )
            )
        }

        // 8. Contemporaneous Momentum Condition (Eliminates 0.30R delayed-crossover penalty)
        val latestMacd = macdPoints.last()
        val prevMacd = macdPoints[macdPoints.size - 2]
        val isExpandingDownward = latestMacd.histogram <= prevMacd.histogram
        val isBearishState = latestMacd.macd < latestMacd.signal

        if (!isExpandingDownward && !isBearishState) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "MACD momentum expanding upward at signal candle",
                confidenceScore = 30.0,
                diagnostics = StrategyDiagnostics(
                    stage = "MOMENTUM_FILTER",
                    failedFilter = "MACD_EXPANDING_UPWARD",
                    indicators = mapOf("macd" to latestMacd.macd, "signal" to latestMacd.signal, "hist" to latestMacd.histogram, "prevHist" to prevMacd.histogram),
                    flags = mapOf("isExpandingDownward" to isExpandingDownward, "isBearishState" to isBearishState)
                )
            )
        }

        // Structural Target calculation: fee-aware target
        val target1 = currentPrice - rewardDist

        // Confidence scoring
        var score = 65.0
        if (activeSupply.touchCount == 0) score += 15.0 // Virgin supply zone
        if (adx >= 25.0) score += 10.0                 // Strong directional movement
        if (isTrendContinuation) score += 10.0         // Macro trend alignment
        val finalScore = score.coerceIn(70.0, 98.0)

        val branchTag = if (isTrendContinuation) "TREND_CONTINUATION" else "EXHAUSTION_TOP"

        return Signal(
            action = SignalAction.ENTER_SHORT,
            suggestedLeverage = defaultLeverage,
            stopLossPrice = stopLoss,
            takeProfitPrice = target1,
            reason = "[$branchTag] Supply Zone Rejection (${String.format("%.2f", activeSupply.low)}-${String.format("%.2f", activeSupply.high)}) + Bearish Engulfing + Contemporaneous MACD",
            confidenceScore = finalScore,
            diagnostics = StrategyDiagnostics(
                stage = "ACTIONABLE_SIGNAL",
                indicators = mapOf("entryPrice" to currentPrice, "stopLoss" to stopLoss, "target1" to target1, "adx" to adx, "atr" to atr, "confidence" to finalScore),
                flags = mapOf("isTrendContinuation" to isTrendContinuation, "isVirginZone" to (activeSupply.touchCount == 0)),
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
                // Downside Structure-Break Filter (Hypothesis 2 vetted: K=5 bars low break)
                if (i >= 5) {
                    val prior5Lows = (1..5).map { candles[i - it].low }
                    val minPriorLow = prior5Lows.minOrNull() ?: Double.MAX_VALUE
                    if (curr.close >= minPriorLow) {
                        // Disqualified: Did not break previous 5-bar structural low
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
                    // Zone Width Quality Filter (Hypothesis 1 vetted: width <= 2.5 local ATR)
                    if ((zHigh - zLow) <= 2.5 * localAtr) {
                        supplyZones.add(Zone(zoneIdCounter++, ZoneType.SUPPLY, zHigh, zLow, i))
                    }
                }
            }

            // Demand Zone (Displacement Rally)
            val isBullishDisplacement = (curr.close - curr.open >= 1.5 * localAtr) && (curr.volume >= 1.3 * avgVol)
            if (isBullishDisplacement) {
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

        // Invalidation and touch tracking (with visit hysteresis)
        for (sz in supplyZones) {
            var inVisit = false
            for (i in (sz.createdIndex + 1)..lastIndex) {
                val c = candles[i]
                val localAtr = atrSeries.getOrElse(i) { atrSeries.lastOrNull() ?: 0.0 }
                if (c.close > sz.high) {
                    sz.invalidated = true
                    break
                }
                // Probed the zone
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

    private fun isValidBearishEngulfing(curr: MarketCandle, prev: MarketCandle, atr: Double): Boolean {
        if (prev.close <= prev.open || curr.close >= curr.open) return false

        val bodyEngulfed = (curr.open >= prev.close - 0.05 * atr) && (curr.close < prev.open)
        if (!bodyEngulfed) return false

        val currRange = curr.high - curr.low
        val currBody = curr.open - curr.close
        val lowerWick = curr.close - curr.low

        if (currRange <= 0.0) return false
        if ((currBody / currRange) < 0.60) return false
        if (lowerWick > (0.25 * currRange)) return false
        if (currRange < (0.75 * atr) || currRange > (2.5 * atr)) return false

        return true
    }

    private fun findSupplyZoneProbed(zones: List<Zone>, candle: MarketCandle): Zone? {
        return zones.lastOrNull { sz ->
            !sz.invalidated && (candle.high >= sz.low && candle.close <= sz.high)
        }
    }

    private fun findNearestDemandBelow(zones: List<Zone>, price: Double): Zone? {
        return zones.filter { !it.invalidated && it.high < price }.maxByOrNull { it.high }
    }
}
