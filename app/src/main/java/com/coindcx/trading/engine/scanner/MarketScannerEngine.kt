package com.coindcx.trading.engine.scanner

import com.coindcx.trading.data.api.ApiClient
import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.data.config.TradingConfig
import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.scanner.gate.SymbolGate
import com.coindcx.trading.engine.scanner.gate.TimeframeGate
import com.coindcx.trading.engine.time.TradingClock
import com.coindcx.trading.util.AppLogManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cycle-Scoped Candle Cache.
 * Deduplicates candle HTTP requests across concurrent strategies during a single scan cycle.
 * Respects CoinDCX API rate limits using Semaphore(6) and 15ms pacing.
 */
class CycleCandleCache(
    private val apiService: CoinDCXApiService,
    private val concurrencySemaphore: Semaphore
) {
    private val cache = ConcurrentHashMap<String, Deferred<List<MarketCandle>?>>()
    val fetchCount = AtomicInteger(0)
    val cacheHitCount = AtomicInteger(0)
    val totalFetchTimeMs = AtomicLong(0)

    suspend fun getCandles(pair: String, timeframe: String, scope: CoroutineScope): List<MarketCandle>? {
        val key = "$pair:$timeframe"
        var wasCached = true
        val deferred = cache.computeIfAbsent(key) {
            wasCached = false
            scope.async(Dispatchers.IO) {
                concurrencySemaphore.withPermit {
                    delay(15)
                    val start = System.currentTimeMillis()
                    val result = fetchCandlesWithBackoffInternal(pair, timeframe)
                    totalFetchTimeMs.addAndGet(System.currentTimeMillis() - start)
                    fetchCount.incrementAndGet()
                    result
                }
            }
        }
        if (wasCached) {
            cacheHitCount.incrementAndGet()
        }
        return deferred.await()
    }

    private suspend fun fetchCandlesWithBackoffInternal(
        pair: String,
        timeframe: String,
        maxRetries: Int = 2
    ): List<MarketCandle>? {
        var backoffMs = 1000L
        for (attempt in 1..maxRetries) {
            try {
                val resp = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    apiService.getCandles(pair, timeframe)
                }
                if (resp == null) {
                    AppLogManager.w("SCANNER", "[$pair] Timeout (10s) fetching $timeframe candles (Attempt $attempt/$maxRetries)")
                    if (attempt == maxRetries) return null
                    delay(backoffMs)
                    backoffMs *= 2
                    continue
                }

                if (resp.code() == 429) {
                    AppLogManager.w("SCANNER", "[$pair] HTTP 429 rate limited on $timeframe candles (Attempt $attempt/$maxRetries). Backing off for ${backoffMs}ms...")
                    delay(backoffMs)
                    backoffMs *= 2
                    continue
                }
                if (resp.isSuccessful && !resp.body().isNullOrEmpty()) {
                    return resp.body()!!.sortedBy { it.time }
                } else {
                    return null
                }
            } catch (e: Exception) {
                if (attempt == maxRetries) {
                    AppLogManager.w("SCANNER", "[$pair] Failed fetching $timeframe candles after $maxRetries attempts: ${e.message}")
                    return null
                }
                delay(backoffMs)
                backoffMs *= 2
            }
        }
        return null
    }
}

class MarketScannerEngine(
    private val apiService: CoinDCXApiService,
    val universeManager: FuturesUniverseManager = FuturesUniverseManager(apiService, ApiClient.binanceFuturesApiService)
) {
    private val lastProcessedEntryCandleTime = ConcurrentHashMap<String, Long>()

    /**
     * Single-strategy entry point (Backward-compatible overload).
     */
    suspend fun scanMarket(
        config: TradingConfig,
        strategy: Strategy,
        executionEngine: ExecutionEngine,
        accountConstraints: FuturesUniverseManager.AccountConstraints? = null
    ): List<MarketOpportunity> = scanMarket(config, listOf(strategy), emptyList(), executionEngine, accountConstraints)

    /**
     * Multi-Strategy Parallel Market Scanner (Backward-compatible overload).
     */
    suspend fun scanMarket(
        config: TradingConfig,
        strategies: List<Strategy>,
        executionEngine: ExecutionEngine,
        accountConstraints: FuturesUniverseManager.AccountConstraints? = null
    ): List<MarketOpportunity> = scanMarket(
        config = config,
        strategies = strategies,
        universeStrategies = StrategyRegistry.getScanningUniverseStrategies(),
        executionEngine = executionEngine,
        accountConstraints = accountConstraints
    )

    /**
     * Multi-Strategy Parallel Market Scanner with Cross-Sectional Universe Strategy support.
     * Concurrently evaluates all provided single-symbol and universe strategies across the futures universe.
     * Enforces Universal Gates (§0.4 G1-G7), synthesizes HTF 4H bars, and applies §11.2 mutual exclusion.
     */
    suspend fun scanMarket(
        config: TradingConfig,
        strategies: List<Strategy>,
        universeStrategies: List<UniverseStrategy>,
        executionEngine: ExecutionEngine,
        accountConstraints: FuturesUniverseManager.AccountConstraints? = null
    ): List<MarketOpportunity> = withContext(Dispatchers.IO) {
        val scanStartTime = System.currentTimeMillis()

        // Include pairs of all active open positions so position-management exits are never orphaned
        val openPositions = executionEngine.getAllOpenPositions().filter { it.isOpen }
        val openPositionPairs = openPositions.map { it.pair }

        val dynamicUniverse = if (config.isMarketWideScan) {
            universeManager.getOrRefreshUniverse(
                openPositionPairs = openPositionPairs,
                accountConstraints = accountConstraints
            )
        } else {
            config.selectedPairs.ifEmpty { universeManager.getMajorUniverse() }
        }

        val pairsToScan = (dynamicUniverse + openPositionPairs).distinct()
        val btcPair = "B-BTC_USDT"
        val allPairsToFetch = (pairsToScan + btcPair).distinct()

        val concurrencySemaphore = Semaphore(6)
        val candleCache = CycleCandleCache(apiService, concurrencySemaphore)
        val clock = TradingClock.SYSTEM
        val primaryInterval = try { Interval.fromLabel(config.timeframe) } catch (_: Exception) { Interval.M15 }
        val primaryTf = primaryInterval.label

        AppLogManager.scanner(
            "================ PAIR-FIRST MULTI-STRATEGY SCAN START ================\n" +
            "Universe: ${pairsToScan.size} pairs (+ BTC benchmark) | Active Strategies: ${strategies.size} | TF: $primaryTf"
        )

        // Dynamically configure strategies (e.g. EmaCrossover)
        strategies.forEach { strat ->
            if (strat is com.coindcx.trading.engine.strategies.EmaCrossoverStrategy) {
                strat.configure(
                    fast = config.fastEmaPeriod,
                    slow = config.slowEmaPeriod,
                    atrMult = config.atrMultiplier
                )
            }
        }

        // 1. Fetch Primary Candles on config.timeframe concurrently
        val primaryCandlesDeferred = allPairsToFetch.associateWith { pair ->
            async(Dispatchers.IO) {
                candleCache.getCandles(pair, primaryTf, this)
            }
        }

        // 2. Fetch HTF 1h Candles if needed for multi-timeframe confirmation
        val needsHtf = strategies.any { strat ->
            strat.requiredIntervals.any { it == Interval.H1 || it == Interval.H4 }
        } || primaryInterval == Interval.M15

        val htf1hDeferred = if (needsHtf && primaryInterval != Interval.H1 && primaryInterval != Interval.H4) {
            allPairsToFetch.associateWith { pair ->
                async(Dispatchers.IO) {
                    candleCache.getCandles(pair, "1h", this)
                }
            }
        } else emptyMap()

        // 3. Await and validate primary candles
        val rawCandlesMap = mutableMapOf<String, List<MarketCandle>>()
        val validPrimarySeriesMap = mutableMapOf<String, CandleSeries>()

        val minRequiredCandles = strategies.maxOfOrNull { it.requiredCandleCount }?.coerceAtLeast(50) ?: 50
        for (pair in allPairsToFetch) {
            val rawCandles = primaryCandlesDeferred[pair]?.await()
            if (rawCandles.isNullOrEmpty() || rawCandles.size < minRequiredCandles) {
                AppLogManager.d("SCANNER", "[$pair] Missing or insufficient candles (${rawCandles?.size ?: 0} < $minRequiredCandles) on $primaryTf")
                continue
            }
            val series = CandleSeries.fromApi(rawCandles, primaryInterval, clock, pair)
            if (series.isNotEmpty() && series.close(0) > 0.0) {
                rawCandlesMap[pair] = rawCandles
                validPrimarySeriesMap[pair] = series
            }
        }

        val btcSeries = validPrimarySeriesMap[btcPair]

        // 4. Cross-Sectional Pre-Pass (Section B.1 & Section F): Decile ranking vs BTC benchmark
        val relativeStrengthMap = mutableMapOf<String, Pair<Int, Int>>() // pair -> (decile [1..10], rank [1..N])
        if (btcSeries != null && btcSeries.size >= 25) {
            val btcClose0 = btcSeries.close(0)
            val btcClose24 = btcSeries.close(24)
            if (btcClose24 > 0.0) {
                val btcReturn24 = (btcClose0 - btcClose24) / btcClose24
                val returnPairs = validPrimarySeriesMap.filterKeys { it != btcPair && validPrimarySeriesMap[it]!!.size >= 25 }
                    .map { (pair, series) ->
                        val p0 = series.close(0)
                        val p24 = series.close(24)
                        val ret24 = if (p24 > 0.0) (p0 - p24) / p24 else 0.0
                        val alpha = ret24 - btcReturn24
                        Triple(pair, alpha, ret24)
                    }.sortedBy { it.second } // Sort ascending (worst to best)

                val n = returnPairs.size
                if (n > 0) {
                    returnPairs.forEachIndexed { index, (pair, _, _) ->
                        val rank = index + 1
                        val decile = (((index * 10) / n) + 1).coerceIn(1, 10)
                        relativeStrengthMap[pair] = Pair(decile, rank)
                    }
                }
            }
        }

        // 5. Build Synthesized Higher Timeframe Series (1H and 4H)
        val htfSeriesCache = mutableMapOf<String, Map<Interval, CandleSeries>>()
        for (pair in pairsToScan) {
            if (!validPrimarySeriesMap.containsKey(pair)) continue
            val htfMap = mutableMapOf<Interval, CandleSeries>()
            if (primaryInterval == Interval.H1) {
                val pSeries = validPrimarySeriesMap[pair]!!
                htfMap[Interval.H1] = pSeries
                val h4Series = CandleSeries.synthesize4H(pSeries, clock)
                if (h4Series.isNotEmpty()) htfMap[Interval.H4] = h4Series
            } else if (primaryInterval == Interval.H4) {
                htfMap[Interval.H4] = validPrimarySeriesMap[pair]!!
            } else if (htf1hDeferred.containsKey(pair)) {
                val raw1h = htf1hDeferred[pair]?.await()
                if (!raw1h.isNullOrEmpty()) {
                    val h1Series = CandleSeries.fromApi(raw1h, Interval.H1, clock, pair)
                    if (h1Series.isNotEmpty()) {
                        htfMap[Interval.H1] = h1Series
                        val h4Series = CandleSeries.synthesize4H(h1Series, clock)
                        if (h4Series.isNotEmpty()) htfMap[Interval.H4] = h4Series
                    }
                }
            }
            htfSeriesCache[pair] = htfMap
        }

        // 6. Fan-out Evaluation: For each mover, evaluate all active 10 + 2 strategies concurrently
        val aggregatedOpportunities = mutableListOf<MarketOpportunity>()
        val strategyTimings = ConcurrentHashMap<String, Long>()
        val strategyActionableCounts = ConcurrentHashMap<String, Int>()

        for (pair in pairsToScan) {
            val primarySeries = validPrimarySeriesMap[pair] ?: continue
            val currentPrice = primarySeries.close(0)
            val ticker = universeManager.getLatestTicker(pair)

            // Universal Symbol Gate (§0.4 G1, G2, G5)
            if (ticker != null) {
                val gateRes = SymbolGate.evaluate(
                    pair = pair,
                    isInUniverse = true,
                    quoteVolume24h = ticker.quoteVolumeUsdt,
                    bid = ticker.bid,
                    ask = ticker.ask,
                    lastPrice = ticker.lastPrice
                )
                if (!gateRes.isAllowed) {
                    AppLogManager.d("SCANNER", "[$pair] Rejected by SymbolGate: ${gateRes.reason}")
                    continue
                }
            }

            // Universal Timeframe Gate (§0.4 G3, G4, G6)
            val atr14 = TechnicalIndicators.calculateAtr(primarySeries, 14, barIndex = 0)
            val tfGateRes = TimeframeGate.evaluate(
                series = primarySeries,
                atr14 = atr14,
                minHistoryBars = 30
            )
            if (!tfGateRes.isAllowed) {
                AppLogManager.d("SCANNER", "[$pair] Rejected by TimeframeGate: ${tfGateRes.reason}")
                continue
            }

            val activePos = executionEngine.getActivePosition(pair)
            val (rsDecile, rsRank) = relativeStrengthMap[pair] ?: Pair(5, 0)
            val htfMap = htfSeriesCache[pair] ?: emptyMap()

            // Immutable SymbolContext passed identically to all strategies
            val ctx = SymbolContext(
                symbol = pair,
                primarySeries = primarySeries,
                htfSeries = htfMap,
                activePosition = activePos,
                clock = clock,
                tickerLastPrice = currentPrice,
                tickerBid = ticker?.bid ?: 0.0,
                tickerAsk = ticker?.ask ?: 0.0,
                quoteVolume24h = ticker?.quoteVolumeUsdt ?: 0.0,
                relativeStrengthDecile = rsDecile,
                relativeStrengthRank = rsRank
            )

            val pairEvaluations = mutableListOf<StrategyEvaluation>()
            var exitSignalOpportunity: MarketOpportunity? = null
            val rawCandles = rawCandlesMap[pair] ?: emptyList()

            for (strategy in strategies) {
                val startT = System.currentTimeMillis()
                try {
                    // Check Cooldown Gate (§0.4 G7)
                    val cooldownRes = SignalDedupRegistry.default.evaluate(pair, strategy.id, clock)
                    if (!cooldownRes.isAllowed) {
                        continue
                    }

                    val stratResult = strategy.evaluate(ctx, null)
                    val rawSignal = stratResult.signal ?: strategy.evaluate(rawCandles, activePos, pair)
                    val duration = System.currentTimeMillis() - startT
                    strategyTimings.compute(strategy.id) { _, cur -> (cur ?: 0L) + duration }

                    if (rawSignal.action == SignalAction.EXIT && activePos != null && activePos.isOpen) {
                        AppLogManager.trade("STRATEGY", "[$pair] [${strategy.id.uppercase()}] EXIT signal generated: ${rawSignal.reason}")
                        exitSignalOpportunity = MarketOpportunity(
                            pair = pair,
                            signal = rawSignal.copy(strategyId = strategy.id, strategyName = strategy.name),
                            currentPrice = currentPrice,
                            confidenceScore = 100.0,
                            lifecycleState = OpportunityLifecycle.SCANNED,
                            qualityScore = 100,
                            qualityCategory = QualityCategory.PRIME,
                            netRiskRewardRatio = 0.0,
                            isApproved = true,
                            strategyId = strategy.id,
                            strategyName = strategy.name,
                            selectionReason = "Position Exit Signal from ${strategy.name}: ${rawSignal.reason}"
                        )
                    } else if (rawSignal.action == SignalAction.ENTER_LONG || rawSignal.action == SignalAction.ENTER_SHORT) {
                        // Section 4 & 6: Reject stale strategy signals triggered on past candles (> 1 bar ago)
                        val rawSignalBar = rawSignal.barOpenTimeUtc
                        val latestClosedBar = primarySeries.openTime(0)
                        val prevClosedBar = if (primarySeries.size > 1) primarySeries.openTime(1) else 0L
                        if (rawSignalBar > 0L && rawSignalBar < prevClosedBar) {
                            AppLogManager.d("SCANNER", "[$pair] [${strategy.id}] Stale strategy setup discarded (signal bar $rawSignalBar < $prevClosedBar)")
                            continue
                        }

                        strategyActionableCounts.compute(strategy.id) { _, cur -> (cur ?: 0) + 1 }
                        val isLongAction = rawSignal.action == SignalAction.ENTER_LONG
                        val evalEntry = if (rawSignal.entryPrice > 0.0) rawSignal.entryPrice else currentPrice

                        // Exact user formulas:
                        // Final Target % = UI Target % + Total Fees % (Buy + Sell: 0.10%) + 0.50% (unscaled by leverage)
                        // Stop-Loss % = (UI Stop-Loss % * Leverage) - Total Fees % (0.10%)
                        val evalSl = com.coindcx.trading.engine.TradingFeeSchedule.calculateStopLossPrice(
                            entryPrice = evalEntry,
                            isBuy = isLongAction,
                            uiStopLossPercent = config.stopLossPercent,
                            leverage = config.leverage
                        )
                        val evalTp = com.coindcx.trading.engine.TradingFeeSchedule.calculateTakeProfitPrice(
                            entryPrice = evalEntry,
                            isBuy = isLongAction,
                            uiTargetPercent = config.targetPricePercent
                        )

                        pairEvaluations.add(
                            StrategyEvaluation(
                                strategyId = strategy.id,
                                strategyName = strategy.name,
                                family = SignalDedupRegistry.getFamilyForStrategy(strategy.id),
                                action = rawSignal.action,
                                direction = if (isLongAction) SignalDirection.LONG else SignalDirection.SHORT,
                                confidence = rawSignal.confidenceScore,
                                entryPrice = evalEntry,
                                stopLossPrice = evalSl,
                                takeProfitPrice = evalTp,
                                qualityScore = rawSignal.confidenceScore.toInt(),
                                reason = rawSignal.reason
                            )
                        )
                    }
                } catch (e: Exception) {
                    AppLogManager.w("SCANNER", "[$pair] Error evaluating strategy ${strategy.id}: ${e.message}")
                }
            }

            // Position Exit takes immediate precedence
            if (exitSignalOpportunity != null) {
                aggregatedOpportunities.add(exitSignalOpportunity)
                continue
            }

            // Aggregate strategy evidence for this pair
            if (pairEvaluations.isNotEmpty()) {
                val candidate = StrategyAggregator.aggregate(
                    symbol = pair,
                    evaluations = pairEvaluations,
                    currentMarketPrice = currentPrice,
                    stopLossPercent = config.stopLossPercent,
                    targetPricePercent = config.targetPricePercent,
                    leverage = config.leverage
                )
                if (candidate != null) {
                    // Suppress duplicate bar entries
                    val signalKey = "$pair:${candidate.anchorStrategy.strategyId}:$primaryTf"
                    val lastCandleTime = lastProcessedEntryCandleTime[signalKey]
                    val currentBarTime = primarySeries.openTime(0)
                    if (lastCandleTime != null && lastCandleTime == currentBarTime) {
                        AppLogManager.d("SCANNER", "[$pair] Duplicate entry signal suppressed for bar timestamp $currentBarTime")
                        continue
                    }

                    // Section 8: Structured Timeframe Integrity Debug Logging
                    val signalAgeSec = (clock.nowUtcMillis() - primarySeries.openTime(0)) / 1000
                    val rawLatestTime = rawCandles.maxOfOrNull { it.time } ?: 0L
                    val tfIntegrityLog = buildString {
                        appendLine("Selected Timeframe: ${config.timeframe}")
                        appendLine("Requested Timeframe: $primaryTf")
                        appendLine("Returned Candle Timeframe: ${primarySeries.interval.label}")
                        appendLine("Number of Candles: ${rawCandles.size} (raw), ${primarySeries.size} (closed)")
                        appendLine("Latest Candle Timestamp: $rawLatestTime")
                        appendLine("Latest Closed Candle Timestamp: ${primarySeries.openTime(0)}")
                        appendLine("Strategy Evaluation Candle: ${primarySeries.openTime(0)} (bar[0])")
                        appendLine("Signal Timestamp: ${primarySeries.openTime(0)}")
                        appendLine("Signal Age: ${signalAgeSec}s")
                    }
                    AppLogManager.d("TIMEFRAME_INTEGRITY", "[$pair]\n$tfIntegrityLog")

                    // Section 6: Candle Data Freshness Gate (Candle age <= 2.5 * interval duration)
                    val candleAgeMs = clock.nowUtcMillis() - primarySeries.openTime(0)
                    val maxAllowedAgeMs = (primaryInterval.durationMs * 2.5).toLong()
                    if (candleAgeMs > maxAllowedAgeMs) {
                        AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Stale market data (${candleAgeMs / 1000}s > ${maxAllowedAgeMs / 1000}s max)")
                        continue
                    }

                    // Section 4 & 6: Passed Opportunity & Price Drift Rejection
                    val isCandidateLong = candidate.direction == SignalDirection.LONG
                    if (isCandidateLong) {
                        if (currentPrice >= candidate.reconciledTakeProfit) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Target already reached ($currentPrice >= ${candidate.reconciledTakeProfit})")
                            continue
                        }
                        if (currentPrice <= candidate.reconciledStopLoss) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Stop-Loss already hit ($currentPrice <= ${candidate.reconciledStopLoss})")
                            continue
                        }
                        val targetDist = candidate.reconciledTakeProfit - candidate.reconciledEntry
                        val stopDist = candidate.reconciledEntry - candidate.reconciledStopLoss
                        if (targetDist > 0 && (currentPrice - candidate.reconciledEntry) > 0.40 * targetDist) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Price moved >40% towards target already. Opportunity passed.")
                            continue
                        }
                        if (stopDist > 0 && (candidate.reconciledEntry - currentPrice) > 0.50 * stopDist) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Adverse price drift >50% towards stop-loss. Setup invalidated.")
                            continue
                        }
                    } else {
                        if (currentPrice <= candidate.reconciledTakeProfit) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Target already reached ($currentPrice <= ${candidate.reconciledTakeProfit})")
                            continue
                        }
                        if (currentPrice >= candidate.reconciledStopLoss) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Stop-Loss already hit ($currentPrice >= ${candidate.reconciledStopLoss})")
                            continue
                        }
                        val targetDist = candidate.reconciledEntry - candidate.reconciledTakeProfit
                        val stopDist = candidate.reconciledStopLoss - candidate.reconciledEntry
                        if (targetDist > 0 && (candidate.reconciledEntry - currentPrice) > 0.40 * targetDist) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Price moved >40% towards target already. Opportunity passed.")
                            continue
                        }
                        if (stopDist > 0 && (currentPrice - candidate.reconciledEntry) > 0.50 * stopDist) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Adverse price drift >50% towards stop-loss. Setup invalidated.")
                            continue
                        }
                    }

                    // Section 5: Latest Candle Contradiction Rejection
                    val bar0Open = primarySeries.open(0)
                    val bar0Close = primarySeries.close(0)
                    val bar0High = primarySeries.high(0)
                    val bar0Low = primarySeries.low(0)
                    val bar0Range = (bar0High - bar0Low).coerceAtLeast(0.0001)

                    if (isCandidateLong) {
                        if (bar0Close < bar0Open && (bar0Open - bar0Close) > 1.2 * atr14) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Latest closed candle is heavy bearish dump bar (${"%.4f".format(bar0Open - bar0Close)} > 1.2*ATR)")
                            continue
                        }
                        if ((bar0Close - bar0Low) / bar0Range < 0.20) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Latest candle closed near dead bottom (<20% range). Buyers failed.")
                            continue
                        }
                    } else {
                        if (bar0Close > bar0Open && (bar0Close - bar0Open) > 1.2 * atr14) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Latest closed candle is heavy bullish pump bar (${"%.4f".format(bar0Close - bar0Open)} > 1.2*ATR)")
                            continue
                        }
                        if ((bar0High - bar0Close) / bar0Range < 0.20) {
                            AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Latest candle closed near top (<20% range). Sellers failed.")
                            continue
                        }
                    }

                    // Section 5: Calculate Dynamic Actionable Setup Confidence
                    val dynamicConfidence = computeActionableConfidence(
                        candidate = candidate,
                        series = primarySeries,
                        currentPrice = currentPrice,
                        htfMap = htfMap
                    )

                    if (dynamicConfidence < 65.0) {
                        AppLogManager.d("SCANNER", "[$pair] Candidate rejected: Dynamic confidence ${"%.1f".format(dynamicConfidence)}% < 65.0% threshold (lacks current setup strength)")
                        continue
                    }

                    lastProcessedEntryCandleTime[signalKey] = currentBarTime

                    val signalAction = if (candidate.direction == SignalDirection.LONG) SignalAction.ENTER_LONG else SignalAction.ENTER_SHORT
                    val signal = Signal(
                        symbol = pair,
                        action = signalAction,
                        confidenceScore = dynamicConfidence,
                        entryPrice = candidate.reconciledEntry,
                        stopLossPrice = candidate.reconciledStopLoss,
                        takeProfitPrice = candidate.reconciledTakeProfit,
                        riskRewardRatio = candidate.netRiskReward,
                        strategyId = candidate.anchorStrategy.strategyId,
                        strategyName = candidate.anchorStrategy.strategyName,
                        reason = "${candidate.selectionReason} | Dynamic Conf: ${"%.1f".format(dynamicConfidence)}%",
                        primaryInterval = primaryInterval,
                        barOpenTimeUtc = primarySeries.openTime(0)
                    )
                    SignalDedupRegistry.default.recordSignal(signal, clock)

                    val masScore = universeManager.getMasScore(pair)?.totalScore ?: 0.0
                    val chg24 = ticker?.change24h ?: 0.0
                    val opp = MarketOpportunity(
                        pair = pair,
                        signal = signal,
                        currentPrice = currentPrice,
                        confidenceScore = dynamicConfidence,
                        lifecycleState = OpportunityLifecycle.SCANNED,
                        qualityScore = dynamicConfidence.toInt(),
                        qualityCategory = if (dynamicConfidence >= 80.0) QualityCategory.PRIME else if (dynamicConfidence >= 65.0) QualityCategory.ACCEPTABLE else QualityCategory.WATCH,
                        netRiskRewardRatio = candidate.netRiskReward,
                        isApproved = true,
                        strategyId = candidate.anchorStrategy.strategyId,
                        strategyName = candidate.anchorStrategy.strategyName,
                        marketActivityScore = masScore,
                        change24hPercent = chg24,
                        contributingStrategies = candidate.contributingStrategies,
                        selectionReason = candidate.selectionReason,
                        statusMessage = candidate.detectedConflicts
                    )
                    aggregatedOpportunities.add(opp)
                    AppLogManager.trade("SCANNER", "[$pair] Aggregated Candidate Approved: ${signal.action} @ ${candidate.reconciledEntry} | SL: ${candidate.reconciledStopLoss} | TP: ${candidate.reconciledTakeProfit} | Conf: ${"%.1f".format(dynamicConfidence)}% | Net R:R: ${"%.2f".format(candidate.netRiskReward)}")
                } else {
                    AppLogManager.d("SCANNER", "[$pair] Strategies evaluated (${pairEvaluations.size} signals) -> Aggregation rejected -> no trade setup")
                }
            } else {
                AppLogManager.d("SCANNER", "[$pair] Strategies evaluated (0 signals) -> all rejected -> no trade setup")
            }
        }

        val totalDurationMs = System.currentTimeMillis() - scanStartTime
        val actionableCount = aggregatedOpportunities.count { it.signal.action != SignalAction.HOLD }

        val stratSummary = strategies.joinToString("\n") { strat ->
            "  Strategy [${strat.id.uppercase()}]: %d ms | Actionable: %d".format(
                strategyTimings[strat.id] ?: 0,
                strategyActionableCounts[strat.id] ?: 0
            )
        }

        AppLogManager.scanner(
            "================ PAIR-FIRST SCAN BENCHMARK ================\n" +
            "Total Scan Duration:      ${totalDurationMs} ms\n" +
            "Candle Fetch Time (Agg):  ${candleCache.totalFetchTimeMs.get()} ms (HTTP Calls: ${candleCache.fetchCount.get()}, Cache Hits: ${candleCache.cacheHitCount.get()})\n" +
            stratSummary + "\n" +
            "Aggregated Candidates:    $actionableCount actionable / ${aggregatedOpportunities.size} total\n" +
            "==========================================================="
        )

        aggregatedOpportunities
    }

    /**
     * Section 5: Dynamic Actionable Setup Confidence Calculation.
     * Computes genuine probability and trade quality based on:
     * - Recency (newest closed candle)
     * - Entry proximity (currentPrice vs reconciledEntry)
     * - Latest candle momentum & close location
     * - Volume confirmation (expansion vs anemic)
     * - Multi-strategy consensus
     * - Higher timeframe trend alignment
     */
    private fun computeActionableConfidence(
        candidate: AggregatedCandidate,
        series: CandleSeries,
        currentPrice: Double,
        htfMap: Map<Interval, CandleSeries>
    ): Double {
        var score = 55.0 // Institutional Base

        // 1. Recency Bonus: Triggered directly at latest closed bar[0]
        score += 10.0

        // 2. Entry Proximity: Distance between currentPrice and reconciledEntry
        val entryDiffPct = if (candidate.reconciledEntry > 0.0) {
            abs(currentPrice - candidate.reconciledEntry) / candidate.reconciledEntry * 100.0
        } else 0.0

        when {
            entryDiffPct <= 0.15 -> score += 10.0  // Executable directly at entry price
            entryDiffPct <= 0.35 -> score += 5.0   // Very close
            entryDiffPct <= 0.60 -> score += 0.0   // Acceptable
            else -> score -= 12.0                 // Drifted away
        }

        // 3. Latest Candle Direction & Structure Alignment
        if (series.isNotEmpty()) {
            val bar0Open = series.open(0)
            val bar0Close = series.close(0)
            val bar0High = series.high(0)
            val bar0Low = series.low(0)
            val bar0Range = (bar0High - bar0Low).coerceAtLeast(0.0001)

            if (candidate.direction == SignalDirection.LONG) {
                val isGreen = bar0Close >= bar0Open
                val closeLoc = (bar0Close - bar0Low) / bar0Range
                if (isGreen && closeLoc >= 0.55) {
                    score += 8.0 // Bullish close in upper half
                } else if (!isGreen) {
                    score -= 10.0 // Counter-trend red bar on a Long
                }
            } else {
                val isRed = bar0Close <= bar0Open
                val closeLoc = (bar0High - bar0Close) / bar0Range
                if (isRed && closeLoc >= 0.55) {
                    score += 8.0 // Bearish close in lower half
                } else if (!isRed) {
                    score -= 10.0 // Counter-trend green bar on a Short
                }
            }

            // 4. Volume Confirmation (bar[0] vs 20 SMA)
            val volSma20 = TechnicalIndicators.calculateVolumeSma(series, 20, 0)
            if (volSma20 > 0.0) {
                val volRatio = series.volume(0) / volSma20
                when {
                    volRatio >= 1.4 -> score += 7.0 // Institutional volume expansion
                    volRatio >= 1.0 -> score += 4.0 // Above average volume
                    volRatio < 0.6 -> score -= 8.0  // Low volume / fakeout risk
                }
            }
        }

        // 5. Multi-Strategy Confirmation Consensus
        if (candidate.consensusCount >= 3) {
            score += 10.0
        } else if (candidate.consensusCount == 2) {
            score += 5.0
        }

        // 6. Higher Timeframe (4H or 1H) Alignment
        val htfSeries = htfMap[Interval.H4] ?: htfMap[Interval.H1]
        if (htfSeries != null && htfSeries.size >= 50) {
            val htfEma50 = TechnicalIndicators.calculateEmaAt(htfSeries, 50, 0)
            val htfClose = htfSeries.close(0)
            if (candidate.direction == SignalDirection.LONG) {
                if (htfClose >= htfEma50) score += 6.0 else score -= 10.0
            } else {
                if (htfClose <= htfEma50) score += 6.0 else score -= 10.0
            }
        }

        return score.coerceIn(10.0, 95.0)
    }
}
