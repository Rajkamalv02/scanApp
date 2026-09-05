package com.coindcx.trading.engine.scanner

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.config.TradingConfig
import com.coindcx.trading.engine.ExecutionEngine
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
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

        val concurrencySemaphore = Semaphore(4) // Max 4 concurrent network requests

        val deferredResults = pairsToScan.map { pair ->
            async {
                concurrencySemaphore.withPermit {
                    scanSinglePair(pair, config.timeframe, strategy, executionEngine)
                }
            }
        }

        deferredResults.awaitAll().filterNotNull()
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
                return null
            }

            val candles = candleResp.body()!!
            if (candles.size < strategy.requiredCandleCount) {
                return null
            }

            val latestCandle = candles.last()
            val currentPrice = latestCandle.close
            val activePosition = executionEngine.getActivePosition(pair)

            val signal = strategy.evaluate(candles, activePosition)

            // We only rank valid entry setups
            if (signal.action == SignalAction.ENTER_LONG || signal.action == SignalAction.ENTER_SHORT) {
                MarketOpportunity(
                    pair = pair,
                    signal = signal,
                    currentPrice = currentPrice,
                    confidenceScore = signal.confidenceScore,
                    lifecycleState = OpportunityLifecycle.SCANNED
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
