package com.coindcx.trading.engine.scanner

import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketScannerMultiStrategyTest {

    private val ranker = OpportunityRanker()

    @org.junit.Before
    fun setUp() {
        SignalDedupRegistry.default.clear()
    }

    private fun createOpportunity(
        pair: String,
        action: SignalAction,
        qualityScore: Int,
        confidenceScore: Double,
        netRr: Double = 2.0,
        strategyId: String = "ema_crossover",
        strategyName: String = "EMA Crossover"
    ): MarketOpportunity {
        val signal = Signal(
            action = action,
            confidenceScore = confidenceScore,
            strategyId = strategyId,
            strategyName = strategyName
        )
        return MarketOpportunity(
            pair = pair,
            signal = signal,
            currentPrice = 100.0,
            confidenceScore = confidenceScore,
            qualityScore = qualityScore,
            qualityCategory = if (qualityScore >= 75) QualityCategory.PRIME else QualityCategory.ACCEPTABLE,
            netRiskRewardRatio = netRr,
            isApproved = qualityScore >= 60,
            strategyId = strategyId,
            strategyName = strategyName
        )
    }

    @Test
    fun testGlobalRankingSelectsTop5DistinctInstruments() {
        val candidates = listOf(
            createOpportunity("B-BTC_USDT", SignalAction.ENTER_LONG, qualityScore = 90, confidenceScore = 95.0, strategyId = "confluence", strategyName = "Confluence"),
            createOpportunity("B-BTC_USDT", SignalAction.ENTER_LONG, qualityScore = 85, confidenceScore = 80.0, strategyId = "ema", strategyName = "EMA"), // Duplicate pair from other strategy
            createOpportunity("B-ETH_USDT", SignalAction.ENTER_LONG, qualityScore = 82, confidenceScore = 85.0, strategyId = "ema", strategyName = "EMA"),
            createOpportunity("B-SOL_USDT", SignalAction.ENTER_SHORT, qualityScore = 80, confidenceScore = 88.0, strategyId = "confluence", strategyName = "Confluence"),
            createOpportunity("B-XRP_USDT", SignalAction.ENTER_LONG, qualityScore = 75, confidenceScore = 80.0, strategyId = "ema", strategyName = "EMA"),
            createOpportunity("B-ADA_USDT", SignalAction.ENTER_LONG, qualityScore = 70, confidenceScore = 75.0, strategyId = "confluence", strategyName = "Confluence"),
            createOpportunity("B-DOGE_USDT", SignalAction.ENTER_SHORT, qualityScore = 65, confidenceScore = 70.0, strategyId = "ema", strategyName = "EMA")
        )

        val rankedTop5 = ranker.rankOpportunities(candidates)

        // Must extract exactly 5 unique instruments
        assertEquals(5, rankedTop5.size)

        val pairs = rankedTop5.map { it.pair }
        assertEquals(5, pairs.distinct().size)

        // Verify ordering: BTC (score 90) -> ETH (score 82) -> SOL (score 80) -> XRP (score 75) -> ADA (score 70)
        assertEquals("B-BTC_USDT", rankedTop5[0].pair)
        assertEquals(1, rankedTop5[0].rank)
        assertEquals("confluence", rankedTop5[0].strategyId)

        assertEquals("B-ETH_USDT", rankedTop5[1].pair)
        assertEquals(2, rankedTop5[1].rank)
        assertEquals("ema", rankedTop5[1].strategyId)

        assertEquals("B-SOL_USDT", rankedTop5[2].pair)
        assertEquals(3, rankedTop5[2].rank)
        assertEquals("confluence", rankedTop5[2].strategyId)

        assertEquals("B-XRP_USDT", rankedTop5[3].pair)
        assertEquals(4, rankedTop5[3].rank)

        assertEquals("B-ADA_USDT", rankedTop5[4].pair)
        assertEquals(5, rankedTop5[4].rank)

        // DOGE should be excluded because only top 5 distinct pairs are selected
        assertTrue(pairs.none { it == "B-DOGE_USDT" })
    }

    @Test
    fun testActionableEntriesRankAboveHoldWatchesGlobally() {
        val candidates = listOf(
            createOpportunity("B-NEAR_USDT", SignalAction.HOLD, qualityScore = 95, confidenceScore = 50.0, strategyId = "confluence"),
            createOpportunity("B-BTC_USDT", SignalAction.ENTER_LONG, qualityScore = 75, confidenceScore = 80.0, strategyId = "ema"),
            createOpportunity("B-ETH_USDT", SignalAction.ENTER_SHORT, qualityScore = 80, confidenceScore = 85.0, strategyId = "confluence")
        )

        val ranked = ranker.rankOpportunities(candidates)
        assertEquals(3, ranked.size)

        // Actionable entries come first even if HOLD has higher non-actionable quality
        assertTrue(ranked[0].isEntry)
        assertTrue(ranked[1].isEntry)
        assertEquals("B-NEAR_USDT", ranked[2].pair)
    }

    @Test
    fun testMultiStrategyAttributionPreservedInRankedOutput() {
        val candidates = listOf(
            createOpportunity("B-BTC_USDT", SignalAction.ENTER_LONG, qualityScore = 88, confidenceScore = 90.0, strategyId = "confluence", strategyName = "Confluence Engine Strategy"),
            createOpportunity("B-ETH_USDT", SignalAction.ENTER_LONG, qualityScore = 80, confidenceScore = 80.0, strategyId = "ema_crossover", strategyName = "EMA Crossover Strategy")
        )

        val ranked = ranker.rankOpportunities(candidates)
        assertEquals(2, ranked.size)

        assertEquals("confluence", ranked[0].strategyId)
        assertEquals("Confluence Engine Strategy", ranked[0].strategyName)
        assertTrue(ranked[0].statusMessage.contains("[CONFLUENCE]"))

        assertEquals("ema_crossover", ranked[1].strategyId)
        assertEquals("EMA Crossover Strategy", ranked[1].strategyName)
        assertTrue(ranked[1].statusMessage.contains("[EMA_CROSSOVER]"))
    }

    private fun createDummyApiService(candlesProvider: ((String, String) -> retrofit2.Response<List<com.coindcx.trading.data.api.models.MarketCandle>>)? = null): com.coindcx.trading.data.api.CoinDCXApiService {
        return java.lang.reflect.Proxy.newProxyInstance(
            com.coindcx.trading.data.api.CoinDCXApiService::class.java.classLoader,
            arrayOf(com.coindcx.trading.data.api.CoinDCXApiService::class.java)
        ) { _, method, args ->
            if (method.name == "getCandles" && candlesProvider != null) {
                val pair = args[0] as String
                val interval = args[1] as String
                candlesProvider(pair, interval)
            } else {
                null
            }
        } as com.coindcx.trading.data.api.CoinDCXApiService
    }

    @Test
    fun testCombineAndDeduplicateBoostsAgreementAndPreservesSaferStopLoss() {
        val dummyApi = createDummyApiService()
        val scanner = MarketScannerEngine(dummyApi)

        val oppEma = MarketOpportunity(
            pair = "B-BTC_USDT",
            signal = Signal(
                action = SignalAction.ENTER_LONG,
                confidenceScore = 80.0,
                stopLossPrice = 64000.0,
                strategyId = "ema_crossover",
                strategyName = "EMA Crossover Strategy"
            ),
            currentPrice = 65000.0,
            confidenceScore = 80.0,
            qualityScore = 82,
            strategyId = "ema_crossover",
            strategyName = "EMA Crossover Strategy"
        )

        val oppConfluence = MarketOpportunity(
            pair = "B-BTC_USDT",
            signal = Signal(
                action = SignalAction.ENTER_LONG,
                confidenceScore = 85.0,
                stopLossPrice = 63500.0, // Safer, lower stop-loss
                strategyId = "confluence",
                strategyName = "Confluence Engine Strategy"
            ),
            currentPrice = 65000.0,
            confidenceScore = 85.0,
            qualityScore = 88,
            strategyId = "confluence",
            strategyName = "Confluence Engine Strategy"
        )

        val resolved = scanner.combineAndDeduplicate(listOf(oppEma, oppConfluence))

        assertEquals(1, resolved.size)
        val winner = resolved.first()

        // Attribution merged
        assertTrue(winner.strategyName.contains("Confluence Engine Strategy"))
        assertTrue(winner.strategyName.contains("EMA Crossover Strategy"))
        // Confidence boosted by +5.0 (85.0 + 5.0 = 90.0)
        assertEquals(90.0, winner.confidenceScore, 0.001)
        // Safer stop loss selected (63500.0 is lower/safer than 64000.0 for Long)
        assertEquals(63500.0, winner.signal.stopLossPrice!!, 0.001)
    }

    @Test
    fun testCombineAndDeduplicatePrioritizesExitOverHold() {
        val dummyApi = createDummyApiService()
        val scanner = MarketScannerEngine(dummyApi)

        val oppHold = MarketOpportunity(
            pair = "B-BTC_USDT",
            signal = Signal(
                action = SignalAction.HOLD,
                confidenceScore = 70.0,
                reason = "Waiting for setup",
                strategyId = "confluence"
            ),
            currentPrice = 65000.0,
            confidenceScore = 70.0,
            strategyId = "confluence"
        )

        val oppExit = MarketOpportunity(
            pair = "B-BTC_USDT",
            signal = Signal(
                action = SignalAction.EXIT,
                confidenceScore = 90.0,
                reason = "Bearish cross exit",
                strategyId = "ema_crossover"
            ),
            currentPrice = 65000.0,
            confidenceScore = 90.0,
            strategyId = "ema_crossover"
        )

        val resolved = scanner.combineAndDeduplicate(listOf(oppHold, oppExit))

        assertEquals(1, resolved.size)
        assertEquals(SignalAction.EXIT, resolved.first().signal.action)
        assertEquals("EXIT", resolved.first().actionLabel)
        assertEquals("ema_crossover", resolved.first().strategyId)
    }

    @Test
    fun testCombineAndDeduplicateResolvesDirectionalConflictByQualityScore() {
        val dummyApi = createDummyApiService()
        val scanner = MarketScannerEngine(dummyApi)

        val oppLong = MarketOpportunity(
            pair = "B-ETH_USDT",
            signal = Signal(action = SignalAction.ENTER_LONG, confidenceScore = 80.0, strategyId = "ema_crossover"),
            currentPrice = 3000.0,
            confidenceScore = 80.0,
            qualityScore = 75,
            strategyId = "ema_crossover"
        )

        val oppShort = MarketOpportunity(
            pair = "B-ETH_USDT",
            signal = Signal(action = SignalAction.ENTER_SHORT, confidenceScore = 85.0, strategyId = "confluence"),
            currentPrice = 3000.0,
            confidenceScore = 85.0,
            qualityScore = 85, // Higher quality score
            strategyId = "confluence"
        )

        val resolved = scanner.combineAndDeduplicate(listOf(oppLong, oppShort))

        assertEquals(1, resolved.size)
        // Higher quality score candidate wins
        assertEquals(SignalAction.ENTER_SHORT, resolved.first().signal.action)
        assertEquals("confluence", resolved.first().strategyId)
    }

    @Test
    fun testCombineAndDeduplicateEnforcesMutualExclusionS2vsS3() {
        val dummyApi = createDummyApiService()
        val scanner = MarketScannerEngine(dummyApi)

        val oppVceb = MarketOpportunity(
            pair = "B-SOL_USDT",
            signal = Signal(action = SignalAction.ENTER_LONG, confidenceScore = 85.0, strategyId = "vceb"),
            currentPrice = 140.0,
            confidenceScore = 85.0,
            qualityScore = 82,
            strategyId = "vceb"
        )

        val oppLsr = MarketOpportunity(
            pair = "B-SOL_USDT",
            signal = Signal(action = SignalAction.ENTER_SHORT, confidenceScore = 82.0, strategyId = "lsr"),
            currentPrice = 140.0,
            confidenceScore = 82.0,
            qualityScore = 80,
            strategyId = "lsr"
        )

        // Per §11.2: If both S2 and S3 fire on the same bar, take NEITHER (market is ambiguous)
        val resolved = scanner.combineAndDeduplicate(listOf(oppVceb, oppLsr))
        assertTrue("Both S2 and S3 must be discarded when co-occurring on the same bar", resolved.isEmpty())
    }

    @Test
    fun testCombineAndDeduplicatePrefersS4OverS2() {
        val dummyApi = createDummyApiService()
        val scanner = MarketScannerEngine(dummyApi)

        val oppVceb = MarketOpportunity(
            pair = "B-AVAX_USDT",
            signal = Signal(action = SignalAction.ENTER_LONG, confidenceScore = 80.0, strategyId = "vceb"),
            currentPrice = 25.0,
            confidenceScore = 80.0,
            qualityScore = 80,
            strategyId = "vceb"
        )

        val oppSorm = MarketOpportunity(
            pair = "B-AVAX_USDT",
            signal = Signal(action = SignalAction.ENTER_LONG, confidenceScore = 85.0, strategyId = "sorm"),
            currentPrice = 25.0,
            confidenceScore = 85.0,
            qualityScore = 78, // Slightly lower quality, but SORM is preferred per §11.2 session context
            strategyId = "sorm"
        )

        val resolved = scanner.combineAndDeduplicate(listOf(oppVceb, oppSorm))
        assertEquals(1, resolved.size)
        assertEquals("sorm", resolved.first().strategyId)
    }

    @Test
    fun testCombineAndDeduplicateEnforcesMutualExclusionS5vsS6() {
        val dummyApi = createDummyApiService()
        val scanner = MarketScannerEngine(dummyApi)

        val oppXrs = MarketOpportunity(
            pair = "B-NEAR_USDT",
            signal = Signal(action = SignalAction.ENTER_LONG, confidenceScore = 88.0, strategyId = "xrs"),
            currentPrice = 5.0,
            confidenceScore = 88.0,
            qualityScore = 85,
            strategyId = "xrs"
        )

        val oppFpx = MarketOpportunity(
            pair = "B-NEAR_USDT",
            signal = Signal(action = SignalAction.ENTER_SHORT, confidenceScore = 82.0, strategyId = "fpx"),
            currentPrice = 5.0,
            confidenceScore = 82.0,
            qualityScore = 88,
            strategyId = "fpx"
        )

        // Per §11.2: Directional conflict between S5 (momentum long) and S6 (counter-trend fade). S6 must be dropped.
        val resolved = scanner.combineAndDeduplicate(listOf(oppXrs, oppFpx))
        assertEquals(1, resolved.size)
        assertEquals("xrs", resolved.first().strategyId)
    }

    @Test
    fun testCycleCandleCacheDeduplicatesConcurrentRequests() = kotlinx.coroutines.test.runTest {
        val fetchCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val dummyApi = createDummyApiService { _, _ ->
            fetchCounter.incrementAndGet()
            val fakeCandles = listOf(
                com.coindcx.trading.data.api.models.MarketCandle(
                    time = 1000L,
                    open = 100.0,
                    high = 105.0,
                    low = 99.0,
                    close = 104.0,
                    volume = 500.0
                )
            )
            retrofit2.Response.success(fakeCandles)
        }

        val cache = CycleCandleCache(dummyApi, kotlinx.coroutines.sync.Semaphore(6))

        // Concurrently request identical pair:timeframe from 2 coroutines
        val job1 = async(kotlinx.coroutines.Dispatchers.IO) {
            cache.getCandles("B-BTC_USDT", "15m", this)
        }
        val job2 = async(kotlinx.coroutines.Dispatchers.IO) {
            cache.getCandles("B-BTC_USDT", "15m", this)
        }

        val res1 = job1.await()
        val res2 = job2.await()

        // Both received valid candle lists
        org.junit.Assert.assertNotNull(res1)
        org.junit.Assert.assertNotNull(res2)
        assertEquals(res1!!.size, res2!!.size)

        // The HTTP API service was invoked strictly ONCE!
        assertEquals(1, fetchCounter.get())
        assertEquals(1, cache.fetchCount.get())
        assertEquals(1, cache.cacheHitCount.get())
    }

    @Test
    fun testEntrySignalThrottlingSuppressesDuplicateOnSameCandleTimestamp() = kotlinx.coroutines.test.runTest {
        val candleList = (1..60).map { i ->
            com.coindcx.trading.data.api.models.MarketCandle(
                time = 1000L + (i * 60_000L),
                open = 100.0 + i,
                high = 102.0 + i,
                low = 99.0 + i,
                close = 101.0 + i,
                volume = 1000.0
            )
        }
        val dummyApi = createDummyApiService { _, _ ->
            retrofit2.Response.success(candleList)
        }
        val scanner = MarketScannerEngine(dummyApi)

        val testStrategy = object : com.coindcx.trading.engine.Strategy {
            override val id = "test_strat"
            override val name = "Test Strat"
            override val description = "Test"
            override val defaultTimeframe = "15m"
            override val requiredCandleCount = 10
            override val parametersSummary = ""

            override fun evaluate(
                candles: List<com.coindcx.trading.data.api.models.MarketCandle>,
                activePosition: com.coindcx.trading.data.api.models.FuturesPosition?,
                pair: String
            ): Signal {
                val lastClose = candles.lastOrNull()?.close ?: 100.0
                return Signal(
                    action = SignalAction.ENTER_LONG,
                    confidenceScore = 85.0,
                    stopLossPrice = lastClose * 0.98,
                    takeProfitPrice = lastClose * 1.05,
                    reason = "Test Long Signal",
                    strategyId = id,
                    strategyName = name
                )
            }
        }

        val dummyExecutionEngine = object : com.coindcx.trading.engine.ExecutionEngine {
            override val isPaperTrading = true
            override var onTradeClosed: ((pair: String, pnl: Double) -> Unit)? = null
            override suspend fun getAvailableBalanceInr(): Double = 10000.0
            override suspend fun getActivePosition(pair: String): com.coindcx.trading.data.api.models.FuturesPosition? = null
            override suspend fun getAllOpenPositions(): List<com.coindcx.trading.data.api.models.FuturesPosition> = emptyList()
            override suspend fun refreshExchangeState(): Result<com.coindcx.trading.engine.ExchangeStateSnapshot> =
                Result.success(com.coindcx.trading.engine.ExchangeStateSnapshot(10000.0, emptyList()))
            override suspend fun executeSignal(signal: Signal, pair: String, currentPrice: Double, marginInr: Double, leverage: Int, tradeId: String): com.coindcx.trading.engine.ExecutionResult =
                com.coindcx.trading.engine.ExecutionResult.Success("id", "ok")
            override suspend fun exitPosition(pair: String, currentPrice: Double, reason: String, tradeId: String?): com.coindcx.trading.engine.ExecutionResult =
                com.coindcx.trading.engine.ExecutionResult.Success("id", "ok")
        }

        val config = com.coindcx.trading.data.config.TradingConfig(
            timeframe = "15m",
            isMarketWideScan = false,
            selectedPairs = listOf("B-BTC_USDT")
        )

        // Cycle 1: First evaluation produces the actionable entry
        val resultsCycle1 = scanner.scanMarket(config, testStrategy, dummyExecutionEngine)
        assertEquals(1, resultsCycle1.size)
        assertEquals(SignalAction.ENTER_LONG, resultsCycle1.first().signal.action)

        // Cycle 2: Candle timestamp has NOT advanced -> duplicate is suppressed
        val resultsCycle2 = scanner.scanMarket(config, testStrategy, dummyExecutionEngine)
        assertEquals(0, resultsCycle2.size)
    }
}
