package com.coindcx.trading.engine.scanner

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.data.config.TradingConfig
import com.coindcx.trading.engine.ExecutionEngine
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.util.AppLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
        executionEngine: ExecutionEngine
    ): List<MarketOpportunity> = scanMarket(config, listOf(strategy), executionEngine)

    /**
     * Multi-Strategy Parallel Market Scanner.
     * Concurrently evaluates all provided strategies across the futures universe.
     * Uses CycleCandleCache to eliminate duplicate network calls and prevent rate limiting.
     * Isolates failures so one strategy error never brings down the complete market scan.
     */
    suspend fun scanMarket(
        config: TradingConfig,
        strategies: List<Strategy>,
        executionEngine: ExecutionEngine
    ): List<MarketOpportunity> = withContext(Dispatchers.IO) {
        val scanStartTime = System.currentTimeMillis()

        // Include pairs of all active open positions so position-management exits are never orphaned
        val openPositionPairs = executionEngine.getAllOpenPositions().map { it.pair }

        val dynamicUniverse = if (config.isMarketWideScan) {
            universeManager.getOrRefreshUniverse(openPositionPairs = openPositionPairs)
        } else {
            config.selectedPairs.ifEmpty { universeManager.getMajorUniverse() }
        }

        val pairsToScan = (dynamicUniverse + openPositionPairs).distinct()

        val concurrencySemaphore = Semaphore(6)
        val candleCache = CycleCandleCache(apiService, concurrencySemaphore)

        val strategyTimings = ConcurrentHashMap<String, Long>()
        val strategyCandidateCounts = ConcurrentHashMap<String, Int>()
        val strategyErrorCounts = ConcurrentHashMap<String, Int>()

        AppLogManager.scanner(
            "================ MULTI-STRATEGY SCAN START ================\n" +
            "Universe: ${pairsToScan.size} pairs | Active Strategies: ${strategies.map { it.name }.joinToString(", ")} | TF: ${config.timeframe}"
        )

        // Execute all strategies concurrently
        val strategyJobs = strategies.map { strategy ->
            async(Dispatchers.IO) {
                val stratStart = System.currentTimeMillis()
                AppLogManager.scanner("Strategy scan started: ${strategy.name} (${strategy.id})")
                try {
                    // Dynamic strategy parameter configuration
                    if (strategy is com.coindcx.trading.engine.strategies.EmaCrossoverStrategy) {
                        strategy.configure(
                            fast = config.fastEmaPeriod,
                            slow = config.slowEmaPeriod,
                            atrMult = config.atrMultiplier
                        )
                    }

                    val candidatePairs = if (config.isMarketWideScan) {
                        universeManager.getStrategyCandidates(strategy, openPositionPairs)
                    } else {
                        pairsToScan
                    }

                    val results = scanUniverseForStrategy(
                        strategy = strategy,
                        pairs = candidatePairs,
                        timeframe = config.timeframe,
                        executionEngine = executionEngine,
                        candleCache = candleCache,
                        scope = this
                    )
                    val duration = System.currentTimeMillis() - stratStart
                    strategyTimings[strategy.id] = duration
                    strategyCandidateCounts[strategy.id] = results.count { it.signal.action != SignalAction.HOLD }
                    strategyErrorCounts[strategy.id] = 0

                    AppLogManager.scanner(
                        "Strategy scan completed: ${strategy.name} in ${duration}ms (${results.size} evaluated, ${strategyCandidateCounts[strategy.id]} actionable)"
                    )
                    results
                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - stratStart
                    strategyTimings[strategy.id] = duration
                    strategyCandidateCounts[strategy.id] = 0
                    strategyErrorCounts[strategy.id] = 1
                    AppLogManager.e("SCANNER", "[STRATEGY_ERROR] Failure in strategy ${strategy.name}: ${e.message}", e)
                    emptyList<MarketOpportunity>() // Isolated failure: other strategies proceed unaffected!
                }
            }
        }

        val allStrategyResults = strategyJobs.awaitAll().flatten()

        // Combine and resolve duplicates/conflicts across strategies
        val deduplicationStartTime = System.currentTimeMillis()
        val combinedCandidates = combineAndDeduplicate(allStrategyResults)
        val rankingDurationMs = System.currentTimeMillis() - deduplicationStartTime

        val totalDurationMs = System.currentTimeMillis() - scanStartTime
        val actionableCount = combinedCandidates.count { it.signal.action != SignalAction.HOLD }

        // Structured Performance & Lifecycle Benchmark Log
        val stratSummary = strategies.joinToString("\n") { strat ->
            "  Strategy [${strat.id.uppercase()}]: %d ms | Actionable: %d | Errors: %d".format(
                strategyTimings[strat.id] ?: 0,
                strategyCandidateCounts[strat.id] ?: 0,
                strategyErrorCounts[strat.id] ?: 0
            )
        }

        AppLogManager.scanner(
            "================ MULTI-STRATEGY SCAN BENCHMARK ================\n" +
            "Total Scan Duration:      ${totalDurationMs} ms\n" +
            "Candle Fetch Time (Agg):  ${candleCache.totalFetchTimeMs.get()} ms (HTTP Calls: ${candleCache.fetchCount.get()}, Cache Hits: ${candleCache.cacheHitCount.get()})\n" +
            stratSummary + "\n" +
            "Combined Pool:            $actionableCount actionable / ${combinedCandidates.size} total snapshots\n" +
            "Ranking/Dedupe Duration:  ${rankingDurationMs} ms\n" +
            "================================================================"
        )

        combinedCandidates
    }

    private suspend fun scanUniverseForStrategy(
        strategy: Strategy,
        pairs: List<String>,
        timeframe: String,
        executionEngine: ExecutionEngine,
        candleCache: CycleCandleCache,
        scope: CoroutineScope
    ): List<MarketOpportunity> {
        val deferredPairScans = pairs.map { pair ->
            scope.async(Dispatchers.IO) {
                scanSinglePair(pair, timeframe, strategy, executionEngine, candleCache, scope)
            }
        }
        return deferredPairScans.awaitAll().filterNotNull()
    }

    private suspend fun scanSinglePair(
        pair: String,
        timeframe: String,
        strategy: Strategy,
        executionEngine: ExecutionEngine,
        candleCache: CycleCandleCache,
        scope: CoroutineScope
    ): MarketOpportunity? {
        val pairStartTime = System.currentTimeMillis()
        return try {
            val candles = candleCache.getCandles(pair, timeframe, scope)
            if (candles.isNullOrEmpty()) {
                AppLogManager.w("SCANNER", "[$pair] (${strategy.id}) Empty candle array or timeout on $timeframe")
                return null
            }

            if (candles.size < strategy.requiredCandleCount) {
                AppLogManager.w("SCANNER", "[$pair] Insufficient candles: ${candles.size}/${strategy.requiredCandleCount} required for ${strategy.name}")
                return null
            }

            val latestCandle = candles.last()
            val currentPrice = latestCandle.close
            val activePosition = executionEngine.getActivePosition(pair)

            val rawSignal = strategy.evaluate(candles, activePosition, pair)
            val signal = rawSignal.copy(
                strategyId = rawSignal.strategyId.ifBlank { strategy.id },
                strategyName = rawSignal.strategyName.ifBlank { strategy.name }
            )

            val elapsedMs = System.currentTimeMillis() - pairStartTime
            val isEntry = signal.action == SignalAction.ENTER_LONG || signal.action == SignalAction.ENTER_SHORT
            if (isEntry && activePosition == null) {
                val signalKey = "$pair:${strategy.id}:$timeframe"
                val lastCandleTime = lastProcessedEntryCandleTime[signalKey]
                if (lastCandleTime != null && lastCandleTime == latestCandle.time) {
                    AppLogManager.d("STRATEGY", "[$pair] [${strategy.id.uppercase()}] Duplicate entry signal suppressed for bar timestamp ${latestCandle.time}")
                    return null
                }
                lastProcessedEntryCandleTime[signalKey] = latestCandle.time
            }

            if (signal.action != SignalAction.HOLD) {
                AppLogManager.trade("STRATEGY", "[$pair] [${strategy.id.uppercase()}] (${elapsedMs}ms) >>> SIGNAL GENERATED: ${signal.action} @ $currentPrice | SL: ${signal.stopLossPrice} | TP: ${signal.takeProfitPrice} | Conf: ${signal.confidenceScore}% | Reason: ${signal.reason}")
            } else {
                AppLogManager.d("STRATEGY", "[$pair] [${strategy.id.uppercase()}] (${elapsedMs}ms) HOLD: ${signal.reason}")
            }

            // JIT fetch Higher-Timeframe (1h) candles only when signal is actionable
            val htfCandles = if (signal.action != SignalAction.HOLD) {
                if (timeframe != "1h" && timeframe != "1d") {
                    try {
                        candleCache.getCandles(pair, "1h", scope)
                    } catch (e: Exception) {
                        AppLogManager.w("SCANNER", "[$pair] Failed fetching 1h HTF candles: ${e.message}")
                        null
                    }
                } else {
                    candles
                }
            } else {
                null
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
                marketActivityScore = masScore
            )
        } catch (e: Exception) {
            AppLogManager.e("SCANNER", "[$pair] [${strategy.id}] Unhandled exception during pair scan: ${e.message}", e)
            null
        }
    }

    /**
     * Combines candidates from all strategies and handles cross-strategy symbol deduplication.
     * - Multi-Strategy Agreement (e.g. both EMA and Confluence signal LONG): Merged with +5.0 confidence boost and dual attribution.
     * - Directional Conflict (one LONG, one SHORT): Candidate with higher quality score prevails.
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

            val actionableCandidates = pairCandidates.filter { it.isEntry }
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

                val mergedOpp = winner.copy(
                    signal = mergedSignal,
                    confidenceScore = boostedConfidence,
                    strategyId = dualStrategyId,
                    strategyName = dualStrategyName,
                    statusMessage = "Multi-Strategy Confluence: Both ${winner.strategyId.uppercase()} and ${other.strategyId.uppercase()} signaled ${winner.actionLabel}"
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
                resolvedList.add(winner)
            }
        }

        return resolvedList
    }
}
