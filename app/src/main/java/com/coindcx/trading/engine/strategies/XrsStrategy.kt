package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.state.CrossSectionalState
import com.coindcx.trading.engine.state.RelativeStrengthHolding
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.telemetry.RejectionCode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy 5: Cross-Sectional Relative Strength (XRS) (§1.5).
 *
 * Evidence Class: Cross-Sectional Momentum & Equity Curve Dispersion.
 * Evaluates the cross-section of traded futures assets relative to Bitcoin (BTC/USDT)
 * benchmark over a 30-day lookback window (180 4H bars / 720 1H bars).
 *
 * Computes beta-adjusted excess return (Alpha = R_i - Beta_i * R_BTC) using timestamp-aligned
 * covariance/variance, ranks the universe into percentiles, enters the top decile (Long) and
 * bottom decile (Short), and applies hysteresis retention until rank drops below the 70th/30th percentile.
 */
class XrsStrategy(
    val lookbackBars: Int = 180,
    val minUniverseSize: Int = 5,
    val topPercentileThreshold: Double = 90.0,
    val bottomPercentileThreshold: Double = 10.0,
    val longRetentionThreshold: Double = 70.0,
    val shortRetentionThreshold: Double = 30.0,
    val maxLongPositions: Int = 3,
    val maxShortPositions: Int = 3,
    val minAtrPct: Double = 0.5,
    val maxAtrPct: Double = 15.0,
    val stopAtrMultiplier: Double = 2.0,
    val plannedRR: Double = 2.0,
    val expiryBars: Int = 30,
    override val primaryInterval: Interval = Interval.H4
) : UniverseStrategy, Strategy {

    override val requiredIntervals: Set<Interval> = setOf(Interval.M15, Interval.H1, Interval.H4)

    override val id: String = "xrs"
    override val name: String = "Cross-Sectional Relative Strength Strategy"
    override val description: String = "Multi-asset relative momentum strategy trading beta-adjusted alpha leaders and laggards against BTC."
    override val requiredCandleCount: Int = lookbackBars + 1
    override val parametersSummary: String = "Lookback: ${lookbackBars}b, UniverseMin: $minUniverseSize, Deciles: [${bottomPercentileThreshold.toInt()}% / ${topPercentileThreshold.toInt()}%], Max Alloc: [L:$maxLongPositions / S:$maxShortPositions]"
    override val preferredRegime: MarketRegimePreference = MarketRegimePreference.TRENDING_MOMENTUM

    private data class CandidateMetrics(
        val symbol: String,
        val context: SymbolContext,
        val close: Double,
        val atr: Double,
        val totalReturn: Double,
        val beta: Double,
        val alpha: Double,
        var rank: Int = 0,
        var percentileRank: Double = 0.0
    )

    override fun evaluate(
        universe: Map<String, SymbolContext>,
        btcContext: SymbolContext,
        state: StrategyState?
    ): UniverseStrategyResult {
        val symbolRejections = mutableMapOf<String, MutableList<RejectionCode>>()

        // Helper to register rejection for a symbol
        fun reject(symbol: String, code: RejectionCode) {
            symbolRejections.getOrPut(symbol) { mutableListOf() }.add(code)
        }

        // 1. Verify BTC benchmark sufficiency
        val btcSeries = btcContext.primarySeries
        if (btcSeries.size < requiredCandleCount) {
            for (sym in universe.keys) {
                reject(sym, RejectionCode.S5_REGIME_BTC_INSUFFICIENT_HISTORY)
            }
            return UniverseStrategyResult(emptyList(), state, symbolRejections)
        }

        // Filter out BTC itself from tradeable candidate universe
        val candidateMap = universe.filter { it.key != btcContext.symbol }
        if (candidateMap.size < minUniverseSize) {
            for (sym in candidateMap.keys) {
                reject(sym, RejectionCode.S5_REGIME_INSUFFICIENT_UNIVERSE_SIZE)
            }
            return UniverseStrategyResult(emptyList(), state, symbolRejections)
        }

        val btcClose0 = btcSeries.close(0)
        val btcCloseLookback = btcSeries.close(lookbackBars)
        val rBtc = if (btcCloseLookback > 0.0) (btcClose0 - btcCloseLookback) / btcCloseLookback else 0.0

        // 2. Evaluate eligibility & compute alpha for all candidates
        val eligibleCandidates = mutableListOf<CandidateMetrics>()

        for ((symbol, ctx) in candidateMap) {
            val series = ctx.primarySeries
            if (series.size < requiredCandleCount) {
                reject(symbol, RejectionCode.S5_REGIME_INSUFFICIENT_HISTORY)
                continue
            }

            val atr = TechnicalIndicators.calculateAtr(series, 14, barIndex = 0)
            val currClose = series.close(0)
            val atrPct = if (currClose > 0.0) (atr / currClose) * 100.0 else 0.0

            if (atrPct < minAtrPct || atrPct > maxAtrPct) {
                reject(symbol, RejectionCode.S5_REGIME_ATR_BOUNDS)
                continue
            }

            // Timestamp-aligned returns for beta calculation
            val (retA, retB) = TechnicalIndicators.alignReturns(series, btcSeries, min(lookbackBars, 60))
            val beta = TechnicalIndicators.calculateBeta(retA, retB).coerceIn(-1.0, 3.0)

            val pastClose = series.close(lookbackBars)
            val rAsset = if (pastClose > 0.0) (currClose - pastClose) / pastClose else 0.0

            // Excess Return: Alpha = R_i - Beta_i * R_BTC
            val alpha = rAsset - (beta * rBtc)

            eligibleCandidates.add(
                CandidateMetrics(
                    symbol = symbol,
                    context = ctx,
                    close = currClose,
                    atr = atr,
                    totalReturn = rAsset,
                    beta = beta,
                    alpha = alpha
                )
            )
        }

        if (eligibleCandidates.size < minUniverseSize) {
            for (c in eligibleCandidates) {
                reject(c.symbol, RejectionCode.S5_REGIME_INSUFFICIENT_UNIVERSE_SIZE)
            }
            return UniverseStrategyResult(emptyList(), state, symbolRejections)
        }

        // 3. Sort by Alpha descending and calculate percentile ranks
        eligibleCandidates.sortByDescending { it.alpha }
        val m = eligibleCandidates.size
        for (i in 0 until m) {
            val c = eligibleCandidates[i]
            c.rank = i
            c.percentileRank = if (m > 1) {
                (1.0 - (i.toDouble() / (m - 1))) * 100.0
            } else {
                50.0
            }
        }

        val candidateBySymbol = eligibleCandidates.associateBy { it.symbol }
        val prevState = state as? CrossSectionalState
        val previousHoldings = prevState?.activeHoldings ?: emptyMap()
        val newHoldings = mutableMapOf<String, RelativeStrengthHolding>()

        // 4. Hysteresis Retention: Preserve active positions holding their percentile corridor
        for ((symbol, holding) in previousHoldings) {
            val metrics = candidateBySymbol[symbol]
            if (metrics != null) {
                when (holding.direction) {
                    SignalDirection.LONG -> {
                        // Long retained if rank stays in top 30% (>= 70th percentile)
                        if (metrics.percentileRank >= longRetentionThreshold) {
                            newHoldings[symbol] = holding.copy(alphaRankPct = metrics.percentileRank)
                            reject(symbol, RejectionCode.S5_HYSTERESIS_RETAINED)
                        }
                    }
                    SignalDirection.SHORT -> {
                        // Short retained if rank stays in bottom 30% (<= 30th percentile)
                        if (metrics.percentileRank <= shortRetentionThreshold) {
                            newHoldings[symbol] = holding.copy(alphaRankPct = metrics.percentileRank)
                            reject(symbol, RejectionCode.S5_HYSTERESIS_RETAINED)
                        }
                    }
                }
            }
        }

        // 5. Generate New Signals
        val signals = mutableListOf<Signal>()
        var currentLongs = newHoldings.count { it.value.direction == SignalDirection.LONG }
        var currentShorts = newHoldings.count { it.value.direction == SignalDirection.SHORT }

        // Top decile candidates (Long)
        for (candidate in eligibleCandidates) {
            if (candidate.percentileRank < topPercentileThreshold) break
            if (newHoldings.containsKey(candidate.symbol)) continue // already retained

            if (currentLongs >= maxLongPositions) {
                reject(candidate.symbol, RejectionCode.S5_PORTFOLIO_CONCURRENCY_CAP)
                continue
            }

            val currClose = candidate.close
            val stopDist = (stopAtrMultiplier * candidate.atr).coerceAtLeast(currClose * 0.005)
            val stopLoss = currClose - stopDist
            val takeProfit = currClose + (stopDist * plannedRR)

            val strengths = mapOf(
                "alphaRank" to (candidate.percentileRank / 100.0).coerceIn(0.0, 1.0),
                "beta" to ((candidate.beta + 1.0) / 4.0).coerceIn(0.0, 1.0),
                "excessReturn" to (candidate.alpha.coerceIn(-0.5, 0.5) + 0.5),
                "totalReturn" to (candidate.totalReturn.coerceIn(-0.5, 0.5) + 0.5)
            )

            val openTime = candidate.context.primarySeries.openTime(0)
            signals.add(
                Signal(
                    symbol = candidate.symbol,
                    strategyId = id,
                    direction = SignalDirection.LONG,
                    barOpenTimeUtc = openTime,
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = takeProfit, plannedRR = plannedRR),
                    riskDistance = stopDist,
                    riskPct = (stopDist / currClose) * 100.0,
                    regimeTag = RegimeTag.TREND_UP,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "XRS Long: Alpha leader (Alpha: %+.2f%%, Beta: %.2f, Rank: %.1f%%)".format(
                        candidate.alpha * 100.0, candidate.beta, candidate.percentileRank
                    ),
                    confidenceScore = 80.0 + min(15.0, candidate.alpha * 50.0)
                )
            )

            newHoldings[candidate.symbol] = RelativeStrengthHolding(
                symbol = candidate.symbol,
                direction = SignalDirection.LONG,
                entryBarOpenTime = openTime,
                alphaRankPct = candidate.percentileRank
            )
            currentLongs++
        }

        // Bottom decile candidates (Short) - evaluated lowest alpha first
        val bottomCandidates = eligibleCandidates.filter { it.percentileRank <= bottomPercentileThreshold }
            .sortedBy { it.alpha }

        for (candidate in bottomCandidates) {
            if (newHoldings.containsKey(candidate.symbol)) continue

            if (currentShorts >= maxShortPositions) {
                reject(candidate.symbol, RejectionCode.S5_PORTFOLIO_CONCURRENCY_CAP)
                continue
            }

            val currClose = candidate.close
            val stopDist = (stopAtrMultiplier * candidate.atr).coerceAtLeast(currClose * 0.005)
            val stopLoss = currClose + stopDist
            val takeProfit = currClose - (stopDist * plannedRR)

            val strengths = mapOf(
                "alphaRank" to ((100.0 - candidate.percentileRank) / 100.0).coerceIn(0.0, 1.0),
                "beta" to ((candidate.beta + 1.0) / 4.0).coerceIn(0.0, 1.0),
                "excessReturn" to (abs(candidate.alpha).coerceIn(0.0, 1.0)),
                "totalReturn" to ((1.0 - candidate.totalReturn.coerceIn(-0.5, 0.5)) * 0.5).coerceIn(0.0, 1.0)
            )

            val openTime = candidate.context.primarySeries.openTime(0)
            signals.add(
                Signal(
                    symbol = candidate.symbol,
                    strategyId = id,
                    direction = SignalDirection.SHORT,
                    barOpenTimeUtc = openTime,
                    entryRef = currClose,
                    stopLoss = stopLoss,
                    target = Target.Fixed(tp1 = takeProfit, plannedRR = plannedRR),
                    riskDistance = stopDist,
                    riskPct = (stopDist / currClose) * 100.0,
                    regimeTag = RegimeTag.TREND_DOWN,
                    strengths = strengths,
                    expiryBars = expiryBars,
                    primaryInterval = primaryInterval,
                    strategyName = name,
                    reason = "XRS Short: Alpha laggard (Alpha: %+.2f%%, Beta: %.2f, Rank: %.1f%%)".format(
                        candidate.alpha * 100.0, candidate.beta, candidate.percentileRank
                    ),
                    confidenceScore = 80.0 + min(15.0, abs(candidate.alpha) * 50.0)
                )
            )

            newHoldings[candidate.symbol] = RelativeStrengthHolding(
                symbol = candidate.symbol,
                direction = SignalDirection.SHORT,
                entryBarOpenTime = openTime,
                alphaRankPct = candidate.percentileRank
            )
            currentShorts++
        }

        // Register rejection for candidates in middle percentiles
        for (c in eligibleCandidates) {
            if (c.percentileRank > bottomPercentileThreshold && c.percentileRank < topPercentileThreshold) {
                if (!newHoldings.containsKey(c.symbol)) {
                    reject(c.symbol, RejectionCode.S5_ALPHA_RANK_BELOW_THRESHOLD)
                }
            }
        }

        val lastBarTime = btcSeries.openTime(0)
        val updatedState = CrossSectionalState(
            activeHoldings = newHoldings,
            lastUpdatedBarOpenTime = lastBarTime
        )

        return UniverseStrategyResult(
            signals = signals,
            newState = updatedState,
            symbolRejections = symbolRejections
        )
    }

    override fun evaluate(ctx: SymbolContext, state: StrategyState?): StrategyResult {
        val symbol = ctx.symbol
        val series = ctx.primarySeries
        if (series.size < 20) {
            return StrategyResult(null, state, listOf(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY))
        }

        val currClose = series.close(0)
        val atr = TechnicalIndicators.calculateAtr(series, 14, 0)
        val stopDistance = (atr * stopAtrMultiplier).coerceAtLeast(currClose * 0.005)

        val decile = ctx.relativeStrengthDecile
        if (decile >= 9) {
            val sl = currClose - stopDistance
            val tp = currClose + (stopDistance * plannedRR)
            val signal = Signal(
                symbol = symbol,
                strategyId = id,
                direction = SignalDirection.LONG,
                barOpenTimeUtc = series.openTime(0),
                entryRef = currClose,
                stopLoss = sl,
                target = Target.Fixed(tp, null, plannedRR),
                riskDistance = stopDistance,
                riskPct = if (currClose > 0) (stopDistance / currClose) * 100.0 else 0.0,
                regimeTag = RegimeTag.TREND_UP,
                strategyName = name,
                reason = "XRS Long: Cross-sectional top decile leader (Decile: $decile/10, Rank: #${ctx.relativeStrengthRank})",
                confidenceScore = 75.0 + (decile - 9) * 5.0,
                explicitAction = SignalAction.ENTER_LONG
            )
            return StrategyResult(signal, state, emptyList())
        } else if (decile <= 2 && decile > 0) {
            val sl = currClose + stopDistance
            val tp = currClose - (stopDistance * plannedRR)
            val signal = Signal(
                symbol = symbol,
                strategyId = id,
                direction = SignalDirection.SHORT,
                barOpenTimeUtc = series.openTime(0),
                entryRef = currClose,
                stopLoss = sl,
                target = Target.Fixed(tp, null, plannedRR),
                riskDistance = stopDistance,
                riskPct = if (currClose > 0) (stopDistance / currClose) * 100.0 else 0.0,
                regimeTag = RegimeTag.TREND_DOWN,
                strategyName = name,
                reason = "XRS Short: Cross-sectional bottom decile laggard (Decile: $decile/10, Rank: #${ctx.relativeStrengthRank})",
                confidenceScore = 75.0 + (2 - decile) * 5.0,
                explicitAction = SignalAction.ENTER_SHORT
            )
            return StrategyResult(signal, state, emptyList())
        }

        return StrategyResult(null, state, listOf(RejectionCode.S5_NOT_IN_TARGET_DECILE))
    }
}
