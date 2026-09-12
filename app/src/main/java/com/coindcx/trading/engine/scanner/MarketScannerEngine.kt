package com.coindcx.trading.engine.scanner

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.config.TradingConfig
import com.coindcx.trading.engine.ExecutionEngine
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.util.AppLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MarketScannerEngine(
    private val apiService: CoinDCXApiService,
    val universeManager: FuturesUniverseManager = FuturesUniverseManager(apiService)
) {
    suspend fun scanMarket(
        config: TradingConfig,
        strategy: Strategy,
        executionEngine: ExecutionEngine
    ): List<MarketOpportunity> = withContext(Dispatchers.IO) {
        val scanStartTime = System.currentTimeMillis()

        // 1. Dynamic strategy configuration from live TradingConfig parameters
        if (strategy is com.coindcx.trading.engine.strategies.EmaCrossoverStrategy) {
            strategy.configure(
                fast = config.fastEmaPeriod,
                slow = config.slowEmaPeriod,
                atrMult = config.atrMultiplier
            )
        }

        val dynamicUniverse = if (config.isMarketWideScan) {
            universeManager.getOrRefreshUniverse()
        } else {
            config.selectedPairs.ifEmpty { universeManager.getMajorUniverse() }
        }

        // Critical: Prevent open-position orphaning by including pairs of all active open positions
        // even if they temporarily fall outside the dynamic universe cutoff.
        val openPositionPairs = executionEngine.getAllOpenPositions().map { it.pair }
        val pairsToScan = (dynamicUniverse + openPositionPairs).distinct()

        AppLogManager.scanner(
            "Launching parallel evaluation of ${pairsToScan.size} futures symbols (Concurrency: 6) | Strategy: ${strategy.name} (${strategy.parametersSummary}) | TF: ${config.timeframe}"
        )

        // Concurrency controlled with Semaphore(6) and 15ms delay pacing to respect CoinDCX rate limits
        val concurrencySemaphore = Semaphore(6)

        val deferredResults = pairsToScan.map { pair ->
            async {
                concurrencySemaphore.withPermit {
                    kotlinx.coroutines.delay(15)
                    scanSinglePair(pair, config.timeframe, strategy, executionEngine)
                }
            }
        }

        val results = deferredResults.awaitAll().filterNotNull()
        val actionable = results.count { it.signal.action != SignalAction.HOLD }
        val totalDurationMs = System.currentTimeMillis() - scanStartTime

        AppLogManager.scanner(
            "Parallel scan finished in ${totalDurationMs}ms. Evaluated ${pairsToScan.size} pairs -> ${results.size} snapshots, $actionable actionable signals found."
        )
        results
    }

    private suspend fun scanSinglePair(
        pair: String,
        timeframe: String,
        strategy: Strategy,
        executionEngine: ExecutionEngine
    ): MarketOpportunity? {
        val pairStartTime = System.currentTimeMillis()
        return try {
            val candleResp = fetchCandlesWithBackoffInternal(pair, timeframe)
            if (candleResp == null || !candleResp.isSuccessful || candleResp.body().isNullOrEmpty()) {
                val err = if (candleResp != null && !candleResp.isSuccessful) "HTTP ${candleResp.code()}: ${candleResp.message()}" else "Empty candle array or timeout"
                AppLogManager.w("SCANNER", "[$pair] Failed fetching $timeframe candles: $err")
                return null
            }

            // CoinDCX returns candles in descending order (newest first).
            // We sort by timestamp ascending so candles.last() is guaranteed to be the live, current candle.
            val candles = candleResp.body()!!.sortedBy { it.time }
            if (candles.size < strategy.requiredCandleCount) {
                AppLogManager.w("SCANNER", "[$pair] Insufficient candles: ${candles.size}/${strategy.requiredCandleCount} required for ${strategy.name}")
                return null
            }

            val latestCandle = candles.last()
            val currentPrice = latestCandle.close
            val activePosition = executionEngine.getActivePosition(pair)

            val signal = strategy.evaluate(candles, activePosition)

            // Diagnostic trace for strategy evaluation with execution timing
            val elapsedMs = System.currentTimeMillis() - pairStartTime
            val diag = signal.diagnostics
            if (signal.action != SignalAction.HOLD) {
                AppLogManager.trade("STRATEGY", "[$pair] (${elapsedMs}ms) >>> SIGNAL GENERATED: ${signal.action} @ $currentPrice | SL: ${signal.stopLossPrice} | TP: ${signal.takeProfitPrice} | Confidence: ${signal.confidenceScore}% | Reason: ${signal.reason}")
            } else {
                val fastVal = diag?.indicators?.get("fastEma")
                val slowVal = diag?.indicators?.get("slowEma")
                val diagInfo = if (fastVal != null && slowVal != null) "[Fast: %.2f, Slow: %.2f]".format(fastVal, slowVal) else ""
                AppLogManager.d("STRATEGY", "[$pair] (${elapsedMs}ms) HOLD: ${signal.reason} $diagInfo")
            }

            // Optimization: Fetch Higher-Timeframe (1h) candles ONLY when signal is actionable (JIT fetch).
            // This reduces network traffic by ~50% per scan cycle when mostly HOLD signals occur.
            val htfCandles = if (signal.action != SignalAction.HOLD) {
                if (timeframe != "1h" && timeframe != "1d") {
                    try {
                        val htfResp = fetchCandlesWithBackoffInternal(pair, "1h", maxRetries = 2)
                        if (htfResp?.isSuccessful == true) htfResp.body()?.sortedBy { it.time } else null
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

            if (signal.action != SignalAction.HOLD) {
                AppLogManager.quality("[$pair] Quality Score: ${quality.totalScore}/100 (${quality.category}) | Net R:R: ${quality.netRiskRewardRatio} | HTF Align: ${quality.htfAlignment} | Approved: ${quality.isApproved} | Rejection: ${quality.rejectionReason ?: "None"}")
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
                htfAlignment = quality.htfAlignment
            )
        } catch (e: Exception) {
            AppLogManager.e("SCANNER", "[$pair] Unhandled exception during pair scan: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchCandlesWithBackoffInternal(
        pair: String,
        timeframe: String,
        maxRetries: Int = 3
    ): retrofit2.Response<List<com.coindcx.trading.data.api.models.MarketCandle>>? {
        var backoffMs = 1000L
        for (attempt in 1..maxRetries) {
            try {
                val resp = apiService.getCandles(pair, timeframe)
                if (resp.code() == 429) {
                    AppLogManager.w("SCANNER", "[$pair] HTTP 429 rate limited on $timeframe candles (Attempt $attempt/$maxRetries). Backing off for ${backoffMs}ms...")
                    kotlinx.coroutines.delay(backoffMs)
                    backoffMs *= 2
                    continue
                }
                return resp
            } catch (e: Exception) {
                if (attempt == maxRetries) {
                    AppLogManager.w("SCANNER", "[$pair] Failed fetching $timeframe candles after $maxRetries attempts: ${e.message}")
                    return null
                }
                kotlinx.coroutines.delay(backoffMs)
                backoffMs *= 2
            }
        }
        return null
    }
}
