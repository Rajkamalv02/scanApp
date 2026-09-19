package com.coindcx.trading.engine.scanner

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
        maxRetries: Int = 3
    ): List<MarketCandle>? {
        var backoffMs = 1000L
        for (attempt in 1..maxRetries) {
            try {
                val resp = apiService.getCandles(pair, timeframe)
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
    val universeManager: FuturesUniverseManager = FuturesUniverseManager(apiService)
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

        for (pair in allPairsToFetch) {
            val rawCandles = primaryCandlesDeferred[pair]?.await()
            if (rawCandles.isNullOrEmpty() || rawCandles.size < 30) {
                AppLogManager.d("SCANNER", "[$pair] Missing or insufficient candles (${rawCandles?.size ?: 0} < 30) on $primaryTf")
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
                        strategyActionableCounts.compute(strategy.id) { _, cur -> (cur ?: 0) + 1 }
                        pairEvaluations.add(
                            StrategyEvaluation(
                                strategyId = strategy.id,
                                strategyName = strategy.name,
                                family = SignalDedupRegistry.getFamilyForStrategy(strategy.id),
                                action = rawSignal.action,
                                direction = if (rawSignal.action == SignalAction.ENTER_LONG) SignalDirection.LONG else SignalDirection.SHORT,
                                confidence = rawSignal.confidenceScore,
                                entryPrice = if (rawSignal.entryPrice > 0.0) rawSignal.entryPrice else currentPrice,
                                stopLossPrice = rawSignal.stopLossPrice ?: (if (rawSignal.action == SignalAction.ENTER_LONG) currentPrice * 0.98 else currentPrice * 1.02),
                                takeProfitPrice = rawSignal.takeProfitPrice ?: (if (rawSignal.action == SignalAction.ENTER_LONG) currentPrice * 1.04 else currentPrice * 0.96),
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
                val candidate = StrategyAggregator.aggregate(pair, pairEvaluations, currentPrice)
                if (candidate != null) {
                    // Suppress duplicate bar entries
                    val signalKey = "$pair:${candidate.anchorStrategy.strategyId}:$primaryTf"
                    val lastCandleTime = lastProcessedEntryCandleTime[signalKey]
                    val currentBarTime = primarySeries.openTime(0)
                    if (lastCandleTime != null && lastCandleTime == currentBarTime) {
                        AppLogManager.d("SCANNER", "[$pair] Duplicate entry signal suppressed for bar timestamp $currentBarTime")
                        continue
                    }
                    lastProcessedEntryCandleTime[signalKey] = currentBarTime

                    val signalAction = if (candidate.direction == SignalDirection.LONG) SignalAction.ENTER_LONG else SignalAction.ENTER_SHORT
                    val signal = Signal(
                        symbol = pair,
                        action = signalAction,
                        confidenceScore = candidate.aggregatedConfidence,
                        entryPrice = candidate.reconciledEntry,
                        stopLossPrice = candidate.reconciledStopLoss,
                        takeProfitPrice = candidate.reconciledTakeProfit,
                        riskRewardRatio = candidate.netRiskReward,
                        strategyId = candidate.anchorStrategy.strategyId,
                        strategyName = candidate.anchorStrategy.strategyName,
                        reason = candidate.selectionReason
                    )
                    SignalDedupRegistry.default.recordSignal(signal, clock)

                    val masScore = universeManager.getMasScore(pair)?.totalScore ?: 0.0
                    val opp = MarketOpportunity(
                        pair = pair,
                        signal = signal,
                        currentPrice = currentPrice,
                        confidenceScore = candidate.aggregatedConfidence,
                        lifecycleState = OpportunityLifecycle.SCANNED,
                        qualityScore = candidate.aggregatedConfidence.toInt(),
                        qualityCategory = if (candidate.aggregatedConfidence >= 80.0) QualityCategory.PRIME else if (candidate.aggregatedConfidence >= 65.0) QualityCategory.ACCEPTABLE else QualityCategory.WATCH,
                        netRiskRewardRatio = candidate.netRiskReward,
                        isApproved = true,
                        strategyId = candidate.anchorStrategy.strategyId,
                        strategyName = candidate.anchorStrategy.strategyName,
                        marketActivityScore = masScore,
                        contributingStrategies = candidate.contributingStrategies,
                        selectionReason = candidate.selectionReason,
                        statusMessage = candidate.detectedConflicts
                    )
                    aggregatedOpportunities.add(opp)
                    AppLogManager.trade("SCANNER", "[$pair] Aggregated Candidate Approved: ${signal.action} @ ${candidate.reconciledEntry} | SL: ${candidate.reconciledStopLoss} | TP: ${candidate.reconciledTakeProfit} | Conf: ${"%.1f".format(candidate.aggregatedConfidence)}% | Net R:R: ${"%.2f".format(candidate.netRiskReward)}")
                }
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

    private suspend fun scanUniverseForStrategy(
        strategy: Strategy,
        pairs: List<String>,
        executionEngine: ExecutionEngine,
        candleCache: CycleCandleCache,
        scope: CoroutineScope
    ): List<MarketOpportunity> {
        val deferredPairScans = pairs.map { pair ->
            scope.async(Dispatchers.IO) {
                scanSinglePair(pair, strategy, executionEngine, candleCache, scope)
            }
        }
        return deferredPairScans.awaitAll().filterNotNull()
    }

    private suspend fun scanSinglePair(
        pair: String,
        strategy: Strategy,
        executionEngine: ExecutionEngine,
        candleCache: CycleCandleCache,
        scope: CoroutineScope
    ): MarketOpportunity? {
        val pairStartTime = System.currentTimeMillis()
        return try {
            val clock = TradingClock.SYSTEM
            val primaryInterval = strategy.primaryInterval
            val primaryTf = primaryInterval.label

            // 1. Universal Symbol Gate (§0.4 G1, G2, G5)
            val ticker = universeManager.getLatestTicker(pair)
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
                    return null
                }
            }

            // 2. Cooldown Gate (§0.4 G7)
            val cooldownRes = SignalDedupRegistry.default.evaluate(pair, strategy.id, clock)
            if (!cooldownRes.isAllowed) {
                AppLogManager.d("SCANNER", "[$pair] Rejected by G7 CooldownGate: ${cooldownRes.reason}")
                return null
            }

            // 3. Fetch Primary Candles using the strategy's designated interval
            val candles = candleCache.getCandles(pair, primaryTf, scope)
            if (candles.isNullOrEmpty()) {
                AppLogManager.w("SCANNER", "[$pair] (${strategy.id}) Empty candle array or timeout on $primaryTf")
                return null
            }

            if (candles.size < strategy.requiredCandleCount) {
                AppLogManager.w("SCANNER", "[$pair] Insufficient candles: ${candles.size}/${strategy.requiredCandleCount} required for ${strategy.name}")
                return null
            }

            val primarySeries = CandleSeries.fromApi(candles, primaryInterval, clock, pair)
            if (primarySeries.isEmpty()) {
                return null
            }

            val currentPrice = primarySeries.close(0)
            val atr14 = TechnicalIndicators.calculateAtr(primarySeries, 14, barIndex = 0)

            // 4. Universal Timeframe Gate (§0.4 G3, G4, G6)
            val tfGateRes = TimeframeGate.evaluate(
                series = primarySeries,
                atr14 = atr14,
                minHistoryBars = strategy.requiredCandleCount
            )
            if (!tfGateRes.isAllowed) {
                AppLogManager.d("SCANNER", "[$pair] Rejected by TimeframeGate: ${tfGateRes.reason}")
                return null
            }

            // 5. Higher-Timeframe Candle Fetching & Synthesis
            val htfSeriesMap = mutableMapOf<Interval, CandleSeries>()
            val needsHtf = strategy.requiredIntervals.any { it == Interval.H1 || it == Interval.H4 }
            var htfCandles: List<MarketCandle>? = null

            if (needsHtf || primaryInterval == Interval.M15) {
                val h1Raw = if (primaryInterval == Interval.H1) candles else candleCache.getCandles(pair, "1h", scope)
                if (!h1Raw.isNullOrEmpty()) {
                    val h1Series = CandleSeries.fromApi(h1Raw, Interval.H1, clock, pair)
                    if (h1Series.isNotEmpty()) {
                        htfSeriesMap[Interval.H1] = h1Series
                        if (strategy.requiredIntervals.contains(Interval.H4)) {
                            val h4Series = CandleSeries.synthesize4H(h1Series, clock)
                            if (h4Series.isNotEmpty()) {
                                htfSeriesMap[Interval.H4] = h4Series
                            }
                        }
                    }
                    htfCandles = h1Raw
                }
            }

            val activePosition = executionEngine.getActivePosition(pair)

            // 6. Build SymbolContext and Evaluate Strategy
            val ctx = SymbolContext(
                symbol = pair,
                primarySeries = primarySeries,
                htfSeries = htfSeriesMap,
                activePosition = activePosition,
                clock = clock,
                tickerLastPrice = currentPrice,
                tickerBid = ticker?.bid ?: 0.0,
                tickerAsk = ticker?.ask ?: 0.0,
                quoteVolume24h = ticker?.quoteVolumeUsdt ?: 0.0
            )

            val stratResult = strategy.evaluate(ctx, null)
            val rawSignal = stratResult.signal ?: strategy.evaluate(candles, activePosition, pair)
            val signal = rawSignal.copy(
                strategyId = rawSignal.strategyId.ifBlank { strategy.id },
                strategyName = rawSignal.strategyName.ifBlank { strategy.name }
            )

            val elapsedMs = System.currentTimeMillis() - pairStartTime
            val isEntry = signal.action == SignalAction.ENTER_LONG || signal.action == SignalAction.ENTER_SHORT

            if (isEntry && activePosition == null) {
                val signalKey = "$pair:${strategy.id}:$primaryTf"
                val lastCandleTime = lastProcessedEntryCandleTime[signalKey]
                val currentBarTime = primarySeries.openTime(0)
                if (lastCandleTime != null && lastCandleTime == currentBarTime) {
                    AppLogManager.d("STRATEGY", "[$pair] [${strategy.id.uppercase()}] Duplicate entry signal suppressed for bar timestamp $currentBarTime")
                    return null
                }
                lastProcessedEntryCandleTime[signalKey] = currentBarTime
                SignalDedupRegistry.default.recordSignal(signal, clock)
            }

            if (signal.action != SignalAction.HOLD) {
                AppLogManager.trade("STRATEGY", "[$pair] [${strategy.id.uppercase()}] (${elapsedMs}ms) >>> SIGNAL GENERATED: ${signal.action} @ $currentPrice | SL: ${signal.stopLossPrice} | TP: ${signal.takeProfitPrice} | Conf: ${signal.confidenceScore}% | Reason: ${signal.reason}")
            } else {
                AppLogManager.d("STRATEGY", "[$pair] [${strategy.id.uppercase()}] (${elapsedMs}ms) HOLD: ${signal.reason}")
            }

            val quality = TradeQualityScorer.evaluateQuality(
                candles = candles,
                htfCandles = htfCandles,
                signal = signal,
                currentPrice = currentPrice,
                pair = pair
            )

            val masScore = universeManager.getMasScore(pair)?.totalScore ?: 0.0

            if (signal.action != SignalAction.HOLD) {
                AppLogManager.quality("[$pair] [${strategy.id.uppercase()}] TQS: ${quality.totalScore}/100 (${quality.category}) | MAS: ${"%.1f".format(masScore)} | Net R:R: ${quality.netRiskRewardRatio} | HTF: ${quality.htfAlignment} | Approved: ${quality.isApproved}")
            }

            val stratContrib = StrategyContribution(
                strategyId = strategy.id,
                strategyName = strategy.name,
                action = signal.action,
                qualityScore = quality.totalScore,
                confidenceScore = signal.confidenceScore,
                netRiskRewardRatio = quality.netRiskRewardRatio,
                reason = signal.reason
            )

            MarketOpportunity(
                pair = pair,
                signal = signal,
                currentPrice = currentPrice,
                confidenceScore = signal.confidenceScore,
                lifecycleState = OpportunityLifecycle.SCANNED,
                qualityScore = quality.totalScore,
                qualityCategory = quality.category,
                netRiskRewardRatio = quality.netRiskRewardRatio,
                adxValue = quality.adxValue,
                rejectionReason = quality.rejectionReason,
                isApproved = quality.isApproved,
                htfAlignment = quality.htfAlignment,
                strategyId = strategy.id,
                strategyName = strategy.name,
                marketActivityScore = masScore,
                contributingStrategies = if (signal.action != SignalAction.HOLD) listOf(stratContrib) else emptyList(),
                selectionReason = if (signal.action != SignalAction.HOLD) "${strategy.name}: ${signal.reason}" else "Watching / Hold"
            )
        } catch (e: Exception) {
            AppLogManager.e("SCANNER", "[$pair] [${strategy.id}] Unhandled exception during pair scan: ${e.message}", e)
            null
        }
    }

    /**
     * Executes cross-sectional universe strategies (e.g. S5 XRS) across candidate pairs.
     */
    private suspend fun scanUniverseStrategy(
        strategy: UniverseStrategy,
        pairs: List<String>,
        executionEngine: ExecutionEngine,
        candleCache: CycleCandleCache,
        scope: CoroutineScope
    ): List<MarketOpportunity> {
        val btcPair = "B-BTC_USDT"
        val allPairs = (pairs + btcPair).distinct()
        val clock = TradingClock.SYSTEM

        // Fetch 1h candles and synthesize 4H series for all candidate pairs + BTC concurrently
        val deferredContexts = allPairs.map { pair ->
            scope.async(Dispatchers.IO) {
                try {
                    val h1Raw = candleCache.getCandles(pair, "1h", scope)
                    if (h1Raw.isNullOrEmpty()) return@async null
                    val h1Series = CandleSeries.fromApi(h1Raw, Interval.H1, clock, pair)
                    if (h1Series.isEmpty()) return@async null
                    val h4Series = CandleSeries.synthesize4H(h1Series, clock)
                    if (h4Series.isEmpty()) return@async null
                    val ticker = universeManager.getLatestTicker(pair)
                    val activePos = executionEngine.getActivePosition(pair)
                    val ctx = SymbolContext(
                        symbol = pair,
                        primarySeries = h4Series,
                        htfSeries = mapOf(Interval.H1 to h1Series, Interval.H4 to h4Series),
                        activePosition = activePos,
                        clock = clock,
                        tickerLastPrice = h4Series.close(0),
                        tickerBid = ticker?.bid ?: 0.0,
                        tickerAsk = ticker?.ask ?: 0.0,
                        quoteVolume24h = ticker?.quoteVolumeUsdt ?: 0.0
                    )
                    pair to ctx
                } catch (e: Exception) {
                    AppLogManager.w("SCANNER", "[$pair] Error preparing context for ${strategy.id}: ${e.message}")
                    null
                }
            }
        }

        val contexts = deferredContexts.awaitAll().filterNotNull().toMap()
        val btcContext = contexts[btcPair]
        if (btcContext == null) {
            AppLogManager.w("SCANNER", "[${strategy.id}] BTC benchmark context unavailable. Skipping universe evaluation.")
            return emptyList()
        }

        val universeMap = contexts.filterKeys { it != btcPair }
        val universeResult = strategy.evaluate(universeMap, btcContext, null)

        val opportunities = mutableListOf<MarketOpportunity>()
        for (signal in universeResult.signals) {
            val pair = signal.symbol
            val currentPrice = signal.entryPrice

            SignalDedupRegistry.default.recordSignal(signal, clock)
            val h1Candles = candleCache.getCandles(pair, "1h", scope)

            val quality = TradeQualityScorer.evaluateQuality(
                candles = h1Candles ?: emptyList(),
                htfCandles = h1Candles,
                signal = signal,
                currentPrice = currentPrice,
                pair = pair
            )

            val masScore = universeManager.getMasScore(pair)?.totalScore ?: 0.0
            AppLogManager.trade("STRATEGY", "[$pair] [${strategy.id.uppercase()}] UNIVERSE SIGNAL: ${signal.action} @ $currentPrice | SL: ${signal.stopLossPrice} | Conf: ${signal.confidenceScore}%")

            val stratContrib = StrategyContribution(
                strategyId = strategy.id,
                strategyName = strategy.name,
                action = signal.action,
                qualityScore = quality.totalScore,
                confidenceScore = signal.confidenceScore,
                netRiskRewardRatio = quality.netRiskRewardRatio,
                reason = signal.reason
            )

            opportunities.add(
                MarketOpportunity(
                    pair = pair,
                    signal = signal,
                    currentPrice = currentPrice,
                    confidenceScore = signal.confidenceScore,
                    lifecycleState = OpportunityLifecycle.SCANNED,
                    qualityScore = quality.totalScore,
                    qualityCategory = quality.category,
                    netRiskRewardRatio = quality.netRiskRewardRatio,
                    adxValue = quality.adxValue,
                    rejectionReason = quality.rejectionReason,
                    isApproved = quality.isApproved,
                    htfAlignment = quality.htfAlignment,
                    strategyId = strategy.id,
                    strategyName = strategy.name,
                    marketActivityScore = masScore,
                    contributingStrategies = listOf(stratContrib),
                    selectionReason = "Universe Strategy ${strategy.name}: ${signal.reason}"
                )
            )
        }

        return opportunities
    }

    /**
     * Combines candidates from all strategies and handles cross-strategy symbol deduplication.
     * Enforces §11.2 Mutual Exclusion and Preference Rules:
     * - S2 ↔ S3: Direct Conflict. If both fire on same bar, discard BOTH (market is ambiguous).
     * - S4 ↔ S2: Soft Preference. If both fire, prefer S4 (session context adds information).
     * - S5 ↔ S6: Directional Conflict. Discard S6 counter-trend fade against market momentum leader.
     * - Multi-Strategy Agreement: Merged with +5.0 confidence boost and dual attribution.
     * - Position Exits: Actionable EXIT signal takes precedence over HOLD.
     */
    internal fun combineAndDeduplicate(candidates: List<MarketOpportunity>): List<MarketOpportunity> {
        val groupedByPair = candidates.groupBy { it.pair }
        val resolvedList = mutableListOf<MarketOpportunity>()

        for ((pair, pairCandidates) in groupedByPair) {
            if (pairCandidates.size == 1) {
                resolvedList.add(pairCandidates.first())
                continue
            }

            // 1. Position Management Priority: If any strategy requested an EXIT on an active position, preserve it!
            val exitCandidate = pairCandidates.find { it.signal.action == SignalAction.EXIT }
            if (exitCandidate != null) {
                AppLogManager.trade("STRATEGY", "[$pair] Prioritizing EXIT signal from ${exitCandidate.strategyId.uppercase()}: ${exitCandidate.signal.reason}")
                resolvedList.add(exitCandidate)
                continue
            }

            val actionableCandidates = pairCandidates.filter { it.isEntry }.toMutableList()
            if (actionableCandidates.isEmpty()) {
                // If all are HOLD, pick the one with highest confidence
                resolvedList.add(pairCandidates.maxByOrNull { it.confidenceScore } ?: pairCandidates.first())
                continue
            }

            if (actionableCandidates.size == 1) {
                // Exactly 1 actionable entry among HOLDs
                resolvedList.add(actionableCandidates.first())
                continue
            }

            // §11.2 Mutual Exclusion Rule 1: S2 (VCEB) vs S3 (LSR) Direct Conflict
            // "If both fire the same bar, take neither — the market is ambiguous."
            val hasVceb = actionableCandidates.any { it.strategyId.equals("vceb", ignoreCase = true) }
            val hasLsr = actionableCandidates.any { it.strategyId.equals("lsr", ignoreCase = true) }
            if (hasVceb && hasLsr) {
                AppLogManager.scanner(
                    "[MUTUAL_EXCLUSION] [$pair] S2 (VCEB) and S3 (LSR) co-occurred on same bar. Discarding both per §11.2 (market is ambiguous)."
                )
                actionableCandidates.removeAll { it.strategyId.equals("vceb", ignoreCase = true) || it.strategyId.equals("lsr", ignoreCase = true) }
                if (actionableCandidates.isEmpty()) continue
            }

            // §11.2 Soft Preference Rule 2: S4 (SORM) vs S2 (VCEB)
            // "If both fire on the same symbol, prefer S4 (session context adds information S2 lacks)."
            val hasSorm = actionableCandidates.any { it.strategyId.equals("sorm", ignoreCase = true) }
            val remainingVceb = actionableCandidates.any { it.strategyId.equals("vceb", ignoreCase = true) }
            if (hasSorm && remainingVceb) {
                AppLogManager.scanner(
                    "[PREFERENCE] [$pair] S4 (SORM) and S2 (VCEB) both fired. Preferring S4 per §11.2 (session context)."
                )
                actionableCandidates.removeAll { it.strategyId.equals("vceb", ignoreCase = true) }
            }

            // §11.2 Mutual Exclusion Rule 3: S5 (XRS) vs S6 (FPX)
            // "Hard rule: block S6 short signals on any symbol currently held by S5, and vice versa."
            val xrsCandidate = actionableCandidates.find { it.strategyId.equals("xrs", ignoreCase = true) }
            val fpxCandidate = actionableCandidates.find { it.strategyId.equals("fpx", ignoreCase = true) }
            if (xrsCandidate != null && fpxCandidate != null && xrsCandidate.isBuy != fpxCandidate.isBuy) {
                AppLogManager.scanner(
                    "[MUTUAL_EXCLUSION] [$pair] S5 (XRS) and S6 (FPX) directional conflict. Discarding S6 fade against momentum leader per §11.2."
                )
                actionableCandidates.remove(fpxCandidate)
            }

            if (actionableCandidates.isEmpty()) continue
            if (actionableCandidates.size == 1) {
                resolvedList.add(actionableCandidates.first())
                continue
            }

            // Multiple strategies produced an actionable entry for the same pair!
            val longs = actionableCandidates.filter { it.isBuy }
            val shorts = actionableCandidates.filter { it.isSell }

            if (longs.size >= 2 || shorts.size >= 2) {
                // Same Direction Agreement -> Multi-Strategy Confluence Boost!
                val agreeingCandidates = if (longs.size >= 2) longs else shorts
                val winner = agreeingCandidates.maxByOrNull { it.qualityScore } ?: agreeingCandidates.first()
                val other = agreeingCandidates.first { it != winner }

                val isLong = winner.isBuy
                val saferStopLoss = if (isLong) {
                    val sl1 = winner.signal.stopLossPrice ?: Double.MAX_VALUE
                    val sl2 = other.signal.stopLossPrice ?: Double.MAX_VALUE
                    kotlin.math.min(sl1, sl2).takeIf { it != Double.MAX_VALUE } ?: winner.signal.stopLossPrice
                } else {
                    val sl1 = winner.signal.stopLossPrice ?: 0.0
                    val sl2 = other.signal.stopLossPrice ?: 0.0
                    kotlin.math.max(sl1, sl2).takeIf { it != 0.0 } ?: winner.signal.stopLossPrice
                }

                val boostedConfidence = min(100.0, winner.confidenceScore + 5.0)
                val dualStrategyName = "${winner.strategyName} + ${other.strategyName}"
                val dualStrategyId = "${winner.strategyId}+${other.strategyId}"

                val mergedSignal = winner.signal.copy(
                    confidenceScore = boostedConfidence,
                    stopLossPrice = saferStopLoss,
                    strategyId = dualStrategyId,
                    strategyName = dualStrategyName
                )

                val mergedContributions = (winner.contributingStrategies + other.contributingStrategies).distinctBy { it.strategyId }
                val mergedOpp = winner.copy(
                    signal = mergedSignal,
                    confidenceScore = boostedConfidence,
                    strategyId = dualStrategyId,
                    strategyName = dualStrategyName,
                    statusMessage = "Multi-Strategy Confluence: Both ${winner.strategyId.uppercase()} and ${other.strategyId.uppercase()} signaled ${winner.actionLabel}",
                    contributingStrategies = mergedContributions,
                    selectionReason = "Multi-strategy agreement: ${winner.strategyName} + ${other.strategyName} [Boosted Conf: %.1f%%]".format(boostedConfidence)
                )

                AppLogManager.scanner(
                    "[CONFLUENCE_BOOST] [$pair] Multi-strategy agreement: ${winner.strategyId.uppercase()} + ${other.strategyId.uppercase()} both signaled ${winner.actionLabel} -> Boosted confidence to %.1f%%".format(boostedConfidence)
                )
                resolvedList.add(mergedOpp)
            } else {
                // Directional Conflict (e.g. EMA says LONG, Confluence says SHORT)
                val winner = actionableCandidates.maxByOrNull { it.qualityScore } ?: actionableCandidates.first()
                val loser = actionableCandidates.first { it != winner }

                AppLogManager.scanner(
                    "[CONFLICT_RESOLVED] [$pair] Directional conflict: ${winner.strategyId.uppercase()} (${winner.actionLabel}, Score: ${winner.qualityScore}) vs ${loser.strategyId.uppercase()} (${loser.actionLabel}, Score: ${loser.qualityScore}) -> Retaining ${winner.strategyId.uppercase()}"
                )
                val resolvedOpp = winner.copy(
                    selectionReason = "${winner.strategyName} (${winner.actionLabel}, Score: ${winner.qualityScore}) selected over ${loser.strategyName} (${loser.actionLabel}, Score: ${loser.qualityScore})"
                )
                resolvedList.add(resolvedOpp)
            }
        }

        return resolvedList
    }
}
