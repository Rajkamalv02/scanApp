package com.coindcx.trading

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.*
import com.coindcx.trading.engine.MarketRegimePreference
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.scanner.FuturesUniverseManager
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class FuturesUniverseManagerTest {

    private class FakeApiService : CoinDCXApiService {
        var activeInstruments: List<String> = emptyList()
        var marketsDetails: List<Map<String, Any>> = emptyList()
        var ticker: List<Map<String, Any>> = emptyList()
        var shouldFail: Boolean = false

        override suspend fun getActiveInstruments(): Response<List<String>> {
            if (shouldFail) return Response.error(500, "".toResponseBody())
            return Response.success(activeInstruments)
        }

        override suspend fun getMarketsDetails(): Response<List<Map<String, Any>>> {
            if (shouldFail) return Response.error(500, "".toResponseBody())
            return Response.success(marketsDetails)
        }

        override suspend fun getTicker(): Response<List<Map<String, Any>>> {
            if (shouldFail) return Response.error(500, "".toResponseBody())
            return Response.success(ticker)
        }

        override suspend fun getCandles(pair: String, interval: String): Response<List<MarketCandle>> = Response.success(emptyList())
        override suspend fun getFuturesWallets(timestamp: Long): Response<List<FuturesWallet>> = Response.success(emptyList())
        override suspend fun getPositions(body: Map<String, Any>): Response<List<FuturesPosition>> = Response.success(emptyList())
        override suspend fun getOpenOrders(body: Map<String, Any>): Response<List<FuturesOrder>> = Response.success(emptyList())
        override suspend fun createOrder(request: CreateOrderRequest): Response<List<FuturesOrder>> = throw NotImplementedError()
        override suspend fun cancelOrder(body: Map<String, Any>): Response<Map<String, Any>> = Response.success(emptyMap())
    }

    private class DummyStrategy(
        override val id: String,
        override val name: String,
        override val preferredRegime: MarketRegimePreference
    ) : Strategy {
        override val description: String = ""
        override val parametersSummary: String = ""
        override val requiredCandleCount: Int = 10
        override val defaultTimeframe: String = "15m"
        override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?, pair: String): Signal =
            Signal(action = SignalAction.HOLD)
    }

    private fun buildMarketDetail(pair: String, coindcxName: String, targetCurrency: String, status: String = "active"): Map<String, Any> {
        return mapOf(
            "pair" to pair,
            "coindcx_name" to coindcxName,
            "target_currency_short_name" to targetCurrency,
            "status" to status
        )
    }

    private fun buildTicker(
        market: String,
        lastPrice: Double,
        volume: Double,
        bid: Double,
        ask: Double,
        high: Double = lastPrice * 1.05,
        low: Double = lastPrice * 0.95,
        change24h: Double = 2.0
    ): Map<String, Any> {
        return mapOf(
            "market" to market,
            "last_price" to lastPrice.toString(),
            "volume" to volume.toString(),
            "bid" to bid.toString(),
            "ask" to ask.toString(),
            "high" to high.toString(),
            "low" to low.toString(),
            "change_24_hour" to change24h.toString()
        )
    }

    @Test
    fun testStablecoinExclusion() = runTest {
        val fakeApi = FakeApiService()
        fakeApi.activeInstruments = listOf("B-BTC_USDT", "B-USDC_USDT", "B-EUR_USDT")
        fakeApi.marketsDetails = listOf(
            buildMarketDetail("B-BTC_USDT", "BTCUSDT", "BTC"),
            buildMarketDetail("B-USDC_USDT", "USDCUSDT", "USDC"),
            buildMarketDetail("B-EUR_USDT", "EURUSDT", "EUR")
        )
        fakeApi.ticker = listOf(
            buildTicker("BTCUSDT", 60000.0, 100.0, 59990.0, 60010.0), // $6M vol, ~0.03% spread
            buildTicker("USDCUSDT", 1.0, 50000000.0, 0.9999, 1.0001), // $50M vol -> STABLECOIN
            buildTicker("EURUSDT", 1.08, 10000000.0, 1.0799, 1.0801) // $10.8M vol -> STABLECOIN
        )

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        assertTrue(universe.contains("B-BTC_USDT"))
        assertFalse(universe.contains("B-USDC_USDT"))
        assertFalse(universe.contains("B-EUR_USDT"))
    }

    @Test
    fun testAdaptiveFallbackWhenFewerThan20PassPrimary() = runTest {
        val fakeApi = FakeApiService()
        val activeList = mutableListOf<String>()
        val detailsList = mutableListOf<Map<String, Any>>()
        val tickerList = mutableListOf<Map<String, Any>>()

        // Create 10 pairs that pass primary ($500k vol, 0.15% spread)
        for (i in 1..10) {
            val pair = "B-PRIMARY${i}_USDT"
            val mkt = "PRIMARY${i}USDT"
            activeList.add(pair)
            detailsList.add(buildMarketDetail(pair, mkt, "PRI$i"))
            tickerList.add(buildTicker(mkt, 100.0, 5000.0, 99.92, 100.08)) // $500k vol, 0.16% spread
        }

        // Create 15 pairs that ONLY pass fallback ($300k vol, 0.30% spread - fails primary 0.25% limit)
        for (i in 1..15) {
            val pair = "B-FALLBACK${i}_USDT"
            val mkt = "FALLBACK${i}USDT"
            activeList.add(pair)
            detailsList.add(buildMarketDetail(pair, mkt, "FALL$i"))
            tickerList.add(buildTicker(mkt, 100.0, 3000.0, 99.85, 100.15)) // $300k vol, 0.30% spread
        }

        fakeApi.activeInstruments = activeList
        fakeApi.marketsDetails = detailsList
        fakeApi.ticker = tickerList

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        // Since only 10 passed primary (< 20 threshold), adaptive fallback should activate
        // and include fallback pairs up to the pool limit
        assertTrue("Adaptive fallback must include fallback pairs", universe.any { it.startsWith("B-FALLBACK") })
        assertTrue("Core anchors must always be present", universe.contains("B-BTC_USDT"))
        assertTrue("Hard ceiling <= 23 pairs must be strictly enforced", universe.size <= FuturesUniverseManager.HARD_CEILING_TOTAL_POOL)
    }

    @Test
    fun testStrictHardCeilingWithManyCandidates() = runTest {
        val fakeApi = FakeApiService()
        val activeList = mutableListOf<String>()
        val detailsList = mutableListOf<Map<String, Any>>()
        val tickerList = mutableListOf<Map<String, Any>>()

        // Create 50 high-volume pairs with tight spreads
        for (i in 1..50) {
            val pair = "B-COIN${i}_USDT"
            val mkt = "COIN${i}USDT"
            activeList.add(pair)
            detailsList.add(buildMarketDetail(pair, mkt, "C$i"))
            val vol = (60 - i) * 10_000.0
            tickerList.add(buildTicker(mkt, 100.0, vol, 99.97, 100.03))
        }

        fakeApi.activeInstruments = activeList
        fakeApi.marketsDetails = detailsList
        fakeApi.ticker = tickerList

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        // Must be capped strictly at HARD_CEILING_TOTAL_POOL (23)
        assertEquals(FuturesUniverseManager.HARD_CEILING_TOTAL_POOL, universe.size)
        // Core Anchors must be present
        assertTrue(universe.contains("B-BTC_USDT"))
        assertTrue(universe.contains("B-ETH_USDT"))
        assertTrue(universe.contains("B-SOL_USDT"))
    }

    @Test
    fun testPinnedOpenPositionsPreservedUnderHardCeiling() = runTest {
        val fakeApi = FakeApiService()
        val activeList = mutableListOf<String>()
        val detailsList = mutableListOf<Map<String, Any>>()
        val tickerList = mutableListOf<Map<String, Any>>()

        for (i in 1..30) {
            val pair = "B-COIN${i}_USDT"
            val mkt = "COIN${i}USDT"
            activeList.add(pair)
            detailsList.add(buildMarketDetail(pair, mkt, "C$i"))
            tickerList.add(buildTicker(mkt, 100.0, 10_000.0, 99.97, 100.03))
        }

        // Add a low-ranked open position pair
        val openPair = "B-COIN29_USDT"

        fakeApi.activeInstruments = activeList
        fakeApi.marketsDetails = detailsList
        fakeApi.ticker = tickerList

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true, openPositionPairs = listOf(openPair))

        // Open position must be pinned in universe
        assertTrue("Pinned open position must be retained", universe.contains(openPair))
        assertTrue("Hard ceiling must remain <= 23", universe.size <= FuturesUniverseManager.HARD_CEILING_TOTAL_POOL)
    }

    @Test
    fun testStrategyCandidateRoutingSoftBias() = runTest {
        val fakeApi = FakeApiService()
        val activeList = mutableListOf("B-TREND_USDT", "B-RANGE_USDT")
        val detailsList = mutableListOf(
            buildMarketDetail("B-TREND_USDT", "TRENDUSDT", "TREND"),
            buildMarketDetail("B-RANGE_USDT", "RANGEUSDT", "RANGE")
        )
        // TREND: high 24h change + high range
        // RANGE: low range (8%), mild change
        val tickerList = mutableListOf(
            buildTicker("TRENDUSDT", 100.0, 10_000.0, 99.97, 100.03, high = 120.0, low = 100.0, change24h = 15.0),
            buildTicker("RANGEUSDT", 100.0, 10_000.0, 99.97, 100.03, high = 107.0, low = 100.0, change24h = 2.0)
        )

        fakeApi.activeInstruments = activeList
        fakeApi.marketsDetails = detailsList
        fakeApi.ticker = tickerList

        val manager = FuturesUniverseManager(fakeApi)
        manager.getOrRefreshUniverse(forceRefresh = true)

        val trendStrategy = DummyStrategy("ema_cross", "EMA Cross", MarketRegimePreference.TRENDING_MOMENTUM)
        val rangeStrategy = DummyStrategy("confluence", "Confluence", MarketRegimePreference.MEAN_REVERTING_RANGE)

        val trendCandidates = manager.getStrategyCandidates(trendStrategy)
        val rangeCandidates = manager.getStrategyCandidates(rangeStrategy)

        assertNotNull(trendCandidates)
        assertNotNull(rangeCandidates)
        assertTrue(trendCandidates.isNotEmpty())
        assertTrue(rangeCandidates.isNotEmpty())
    }

    @Test
    fun testNetworkFailureGracefulFallback() = runTest {
        val fakeApi = FakeApiService()
        fakeApi.shouldFail = true

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        assertNotNull(universe)
        assertTrue(universe.isNotEmpty())
        assertTrue(universe.contains("B-BTC_USDT"))
    }
}
