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
                                stopLossPrice = rawSignal.stopLossPrice ?: (if (rawSignal.action == SignalAction.ENTER_LONG) currentPrice * (1.0 - config.stopLossPercent / 100.0) else currentPrice * (1.0 + config.stopLossPercent / 100.0)),
                                takeProfitPrice = rawSignal.takeProfitPrice ?: (if (rawSignal.action == SignalAction.ENTER_LONG) currentPrice * (1.0 + config.targetPricePercent / 100.0) else currentPrice * (1.0 - config.targetPricePercent / 100.0)),
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
                    targetPricePercent = config.targetPricePercent
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
                    val chg24 = ticker?.change24h ?: 0.0
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
                        change24hPercent = chg24,
                        contributingStrategies = candidate.contributingStrategies,
                        selectionReason = candidate.selectionReason,
                        statusMessage = candidate.detectedConflicts
                    )
                    aggregatedOpportunities.add(opp)
                    AppLogManager.trade("SCANNER", "[$pair] Aggregated Candidate Approved: ${signal.action} @ ${candidate.reconciledEntry} | SL: ${candidate.reconciledStopLoss} | TP: ${candidate.reconciledTakeProfit} | Conf: ${"%.1f".format(candidate.aggregatedConfidence)}% | Net R:R: ${"%.2f".format(candidate.netRiskReward)}")
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
}
