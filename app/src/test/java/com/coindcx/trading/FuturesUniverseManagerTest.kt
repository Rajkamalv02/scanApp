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

        // Since only 10 passed primary (< 50 threshold), adaptive fallback should activate
        // and include fallback pairs up to the pool limit
        assertTrue("Adaptive fallback must include fallback pairs", universe.any { it.startsWith("B-FALLBACK") })
        assertTrue("Hard ceiling <= 50 pairs must be strictly enforced", universe.size <= FuturesUniverseManager.HARD_CEILING_TOTAL_POOL)
    }

    @Test
    fun testStrictHardCeilingWithManyCandidates() = runTest {
        val fakeApi = FakeApiService()
        val activeList = mutableListOf<String>()
        val detailsList = mutableListOf<Map<String, Any>>()
        val tickerList = mutableListOf<Map<String, Any>>()

        // Create 70 high-volume pairs with tight spreads
        for (i in 1..70) {
            val pair = "B-COIN${i}_USDT"
            val mkt = "COIN${i}USDT"
            activeList.add(pair)
            detailsList.add(buildMarketDetail(pair, mkt, "C$i"))
            val vol = (80 - i) * 10_000.0
            tickerList.add(buildTicker(mkt, 100.0, vol, 99.97, 100.03))
        }

        fakeApi.activeInstruments = activeList
        fakeApi.marketsDetails = detailsList
        fakeApi.ticker = tickerList

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        // Must be capped strictly at HARD_CEILING_TOTAL_POOL (50)
        assertEquals(FuturesUniverseManager.HARD_CEILING_TOTAL_POOL, universe.size)
        assertEquals(50, universe.size)
    }

    @Test
    fun testPinnedOpenPositionsPreservedUnderHardCeiling() = runTest {
        val fakeApi = FakeApiService()
        val activeList = mutableListOf<String>()
        val detailsList = mutableListOf<Map<String, Any>>()
        val tickerList = mutableListOf<Map<String, Any>>()

        for (i in 1..60) {
            val pair = "B-COIN${i}_USDT"
            val mkt = "COIN${i}USDT"
            activeList.add(pair)
            detailsList.add(buildMarketDetail(pair, mkt, "C$i"))
            tickerList.add(buildTicker(mkt, 100.0, 10_000.0, 99.97, 100.03))
        }

        // Add a low-ranked open position pair
        val openPair = "B-COIN59_USDT"

        fakeApi.activeInstruments = activeList
        fakeApi.marketsDetails = detailsList
        fakeApi.ticker = tickerList

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true, openPositionPairs = listOf(openPair))

        // Open position must be pinned in universe
        assertTrue("Pinned open position must be retained", universe.contains(openPair))
        assertTrue("Hard ceiling must remain <= 50", universe.size <= FuturesUniverseManager.HARD_CEILING_TOTAL_POOL)
    }

    @Test
    fun testContractAffordabilityFiltering() = runTest {
        val fakeApi = FakeApiService()
        val manager = FuturesUniverseManager(fakeApi)

        // Small live account: ₹986 INR available, 2x leverage, 30% max single exposure (₹295.80 margin limit)
        // Rate: 90 INR/USDT. Max single exposure in USDT margin = 295.8 / 90 = ~3.28 USDT margin -> ~6.57 USDT notional
        val constraints = FuturesUniverseManager.AccountConstraints(
            availableBalanceInr = 986.0,
            leverage = 2,
            riskPerTradePercent = 1.0,
            maxSingleExposurePercent = 30.0,
            usdtInrRate = 90.0,
            isLiveTrading = true
        )

        // Contract 1: AAVE ($143.62, minQty 0.1 -> minNotional = $14.36 USDT -> margin at 2x = $7.18 USDT = ₹646.20 INR)
        // ₹646.20 > ₹295.80 maxExposure -> MUST BE REJECTED
        val aaveSpec = FuturesUniverseManager.InstrumentSpec(
            pair = "B-AAVE_USDT",
            step = 0.1,
            minQuantity = 0.1,
            targetCurrencyPrecision = 1,
            minNotionalUsdt = 6.0
        )
        val isAaveAffordable = manager.isContractAffordable("B-AAVE_USDT", 143.62, aaveSpec, constraints)
        assertFalse("AAVE min order margin (₹646) exceeds small account exposure limit (₹295) and must be rejected", isAaveAffordable)

        // Contract 2: DOGE ($0.20, minQty 1.0, minNotional = 6.0 USDT -> margin at 2x = $3.0 USDT = ₹270.0 INR)
        // ₹270.0 <= ₹295.80 maxExposure and <= ₹986 balance -> MUST BE AFFORDABLE
        val dogeSpec = FuturesUniverseManager.InstrumentSpec(
            pair = "B-DOGE_USDT",
            step = 1.0,
            minQuantity = 1.0,
            targetCurrencyPrecision = 0,
            minNotionalUsdt = 6.0
        )
        val isDogeAffordable = manager.isContractAffordable("B-DOGE_USDT", 0.20, dogeSpec, constraints)
        assertTrue("DOGE min order margin (₹270) is within small account limit (₹295) and must be accepted", isDogeAffordable)

        // Paper trading bypass: All contracts affordable in paper mode
        val paperConstraints = constraints.copy(isLiveTrading = false)
        assertTrue("Paper trading bypasses live affordability filter", manager.isContractAffordable("B-AAVE_USDT", 143.62, aaveSpec, paperConstraints))
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
