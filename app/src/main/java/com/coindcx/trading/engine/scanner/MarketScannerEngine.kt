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
    private val apiService: CoinDCXApiService
) {
    // High-volume, highly liquid futures instruments curated for market-wide scanning
    private val liquidMarketWidePairs = listOf(
        "B-BTC_USDT", "B-ETH_USDT", "B-SOL_USDT", "B-XRP_USDT", "B-DOGE_USDT",
        "B-ADA_USDT", "B-BNB_USDT", "B-AVAX_USDT", "B-LINK_USDT", "B-NEAR_USDT",
        "B-SUI_USDT", "B-APT_USDT", "B-MATIC_USDT", "B-PEPE_USDT", "B-SHIB_USDT",
        "B-ARB_USDT", "B-OP_USDT", "B-TIA_USDT", "B-RENDER_USDT", "B-INJ_USDT"
    )

    suspend fun scanMarket(
        config: TradingConfig,
        strategy: Strategy,
        executionEngine: ExecutionEngine
    ): List<MarketOpportunity> = withContext(Dispatchers.IO) {
        val pairsToScan = if (config.isMarketWideScan) {
            liquidMarketWidePairs
        } else {
            config.selectedPairs.ifEmpty { liquidMarketWidePairs.take(5) }
        }

        AppLogManager.scanner("Starting market scan cycle [${if (config.isMarketWideScan) "Market-Wide (${pairsToScan.size} pairs)" else "${pairsToScan.size} pairs"}] | Strategy: ${strategy.name} | TF: ${config.timeframe}")

        val concurrencySemaphore = Semaphore(2) // Max 2 concurrent network requests to prevent 429

        val deferredResults = pairsToScan.map { pair ->
            async {
                concurrencySemaphore.withPermit {
                    kotlinx.coroutines.delay(50)
                    scanSinglePair(pair, config.timeframe, strategy, executionEngine)
                }
            }
        }

        val results = deferredResults.awaitAll().filterNotNull()
        val actionable = results.count { it.signal.action != SignalAction.HOLD }
        AppLogManager.scanner("Scan finished. Scanned ${pairsToScan.size} pairs -> ${results.size} snapshots evaluated, $actionable actionable signals found.")
        results
    }

    private suspend fun scanSinglePair(
        pair: String,
        timeframe: String,
        strategy: Strategy,
        executionEngine: ExecutionEngine
    ): MarketOpportunity? {
        return try {
            val candleResp = apiService.getCandles(pair, timeframe)
            if (!candleResp.isSuccessful || candleResp.body().isNullOrEmpty()) {
                val err = if (!candleResp.isSuccessful) "HTTP ${candleResp.code()}: ${candleResp.message()}" else "Empty candle array"
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

            // Diagnostic trace for strategy evaluation
            val diag = signal.diagnostics
            if (signal.action != SignalAction.HOLD) {
                AppLogManager.trade("STRATEGY", "[$pair] >>> SIGNAL GENERATED: ${signal.action} @ $currentPrice | SL: ${signal.stopLossPrice} | TP: ${signal.takeProfitPrice} | Confidence: ${signal.confidenceScore}% | Reason: ${signal.reason}")
            } else {
                val diagInfo = if (diag != null) "[Stage: ${diag.stage}, FailedFilter: ${diag.failedFilter ?: "None"}]" else ""
                AppLogManager.d("STRATEGY", "[$pair] HOLD: ${signal.reason} $diagInfo")
            }

            // Fetch Higher-Timeframe (1h) candles for macro trend alignment
            val htfResp = if (timeframe != "1h" && timeframe != "1d") {
                try { 
                    apiService.getCandles(pair, "1h") 
                } catch (e: Exception) { 
                    AppLogManager.w("SCANNER", "[$pair] Failed fetching 1h HTF candles: ${e.message}")
                    null 
                }
            } else {
                candleResp
            }
            val htfCandles = if (htfResp?.isSuccessful == true) htfResp.body()?.sortedBy { it.time } else null

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
}
