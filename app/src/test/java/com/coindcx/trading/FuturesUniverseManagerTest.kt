package com.coindcx.trading

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.models.*
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
        override suspend fun createOrder(request: CreateOrderRequest): Response<FuturesOrder> = throw NotImplementedError()
        override suspend fun cancelOrder(body: Map<String, Any>): Response<Map<String, Any>> = Response.success(emptyMap())
    }

    private fun buildMarketDetail(pair: String, coindcxName: String, targetCurrency: String, status: String = "active"): Map<String, Any> {
        return mapOf(
            "pair" to pair,
            "coindcx_name" to coindcxName,
            "target_currency_short_name" to targetCurrency,
            "status" to status
        )
    }

    private fun buildTicker(market: String, lastPrice: Double, volume: Double, bid: Double, ask: Double): Map<String, Any> {
        return mapOf(
            "market" to market,
            "last_price" to lastPrice.toString(),
            "volume" to volume.toString(),
            "bid" to bid.toString(),
            "ask" to ask.toString()
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

        assertEquals(1, universe.size)
        assertEquals("B-BTC_USDT", universe[0])
        assertFalse(universe.contains("B-USDC_USDT"))
        assertFalse(universe.contains("B-EUR_USDT"))
    }

    @Test
    fun testSpreadConstraintFilter() = runTest {
        val fakeApi = FakeApiService()
        fakeApi.activeInstruments = listOf("B-TIGHT_USDT", "B-WIDE_USDT")
        fakeApi.marketsDetails = listOf(
            buildMarketDetail("B-TIGHT_USDT", "TIGHTUSDT", "TIGHT"),
            buildMarketDetail("B-WIDE_USDT", "WIDEUSDT", "WIDE")
        )
        fakeApi.ticker = listOf(
            // Tight spread: bid 99.9, ask 100.1 on 100.0 price -> spread 0.2% <= 0.35% -> PASS
            buildTicker("TIGHTUSDT", 100.0, 10000.0, 99.9, 100.1), // $1M vol
            // Wide spread: bid 99.0, ask 100.0 on 100.0 price -> spread 1.0% > 0.35% -> REJECT
            buildTicker("WIDEUSDT", 100.0, 10000.0, 99.0, 100.0) // $1M vol
        )

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        assertEquals(1, universe.size)
        assertEquals("B-TIGHT_USDT", universe[0])
        assertFalse(universe.contains("B-WIDE_USDT"))
    }

    @Test
    fun testVolumeFloorThreshold() = runTest {
        val fakeApi = FakeApiService()
        fakeApi.activeInstruments = listOf("B-HIGHVOL_USDT", "B-LOWVOL_USDT")
        fakeApi.marketsDetails = listOf(
            buildMarketDetail("B-HIGHVOL_USDT", "HIGHVOLUSDT", "HIGHVOL"),
            buildMarketDetail("B-LOWVOL_USDT", "LOWVOLUSDT", "LOWVOL")
        )
        fakeApi.ticker = listOf(
            // High vol: 5,000 * $100 = $500k >= $250k floor -> PASS
            buildTicker("HIGHVOLUSDT", 100.0, 5000.0, 99.95, 100.05),
            // Low vol: 1,500 * $100 = $150k < $250k floor -> REJECT
            buildTicker("LOWVOLUSDT", 100.0, 1500.0, 99.95, 100.05)
        )

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        assertEquals(1, universe.size)
        assertEquals("B-HIGHVOL_USDT", universe[0])
    }

    @Test
    fun testStrictCeilingAndTwoTierSeparation() = runTest {
        val fakeApi = FakeApiService()
        val activeList = mutableListOf<String>()
        val detailsList = mutableListOf<Map<String, Any>>()
        val tickerList = mutableListOf<Map<String, Any>>()

        // Generate 95 qualified pairs with varying volumes
        for (i in 1..95) {
            val pair = "B-COIN${i}_USDT"
            val mkt = "COIN${i}USDT"
            activeList.add(pair)
            detailsList.add(buildMarketDetail(pair, mkt, "COIN$i"))
            // Volume descending from $95M down to $1M (all >= $250k)
            val quoteVol = (96 - i) * 1_000_000.0
            tickerList.add(buildTicker(mkt, 10.0, quoteVol / 10.0, 9.99, 10.01))
        }

        fakeApi.activeInstruments = activeList
        fakeApi.marketsDetails = detailsList
        fakeApi.ticker = tickerList

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        // Strict ceiling: Exactly 85, NOT 95
        assertEquals(85, universe.size)

        // Top pair should be B-COIN1_USDT ($95M volume)
        assertEquals("B-COIN1_USDT", universe.first())
        assertEquals("B-COIN85_USDT", universe.last())

        // Top 25 are Tier-1 Majors
        val majors = manager.getMajorUniverse()
        assertEquals(25, majors.size)
        assertTrue(manager.isTier1Major("B-COIN1_USDT"))
        assertTrue(manager.isTier1Major("B-COIN25_USDT"))

        // Rank 26 to 85 are Tier-2 (NOT majors)
        assertFalse(manager.isTier1Major("B-COIN26_USDT"))
        assertFalse(manager.isTier1Major("B-COIN85_USDT"))
    }

    @Test
    fun testNoBackfillingWhenFewerThanCeiling() = runTest {
        val fakeApi = FakeApiService()
        fakeApi.activeInstruments = listOf("B-SOL_USDT", "B-ETH_USDT", "B-BTC_USDT")
        fakeApi.marketsDetails = listOf(
            buildMarketDetail("B-SOL_USDT", "SOLUSDT", "SOL"),
            buildMarketDetail("B-ETH_USDT", "ETHUSDT", "ETH"),
            buildMarketDetail("B-BTC_USDT", "BTCUSDT", "BTC")
        )
        fakeApi.ticker = listOf(
            buildTicker("BTCUSDT", 60000.0, 100.0, 59990.0, 60010.0),
            buildTicker("ETHUSDT", 3000.0, 1000.0, 2999.0, 3001.0),
            buildTicker("SOLUSDT", 150.0, 10000.0, 149.9, 150.1)
        )

        val manager = FuturesUniverseManager(fakeApi)
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        // Only 3 qualify, so universe must have EXACTLY 3 (no backfilling up to 85!)
        assertEquals(3, universe.size)
        assertEquals(3, manager.getMajorUniverse().size)
    }

    @Test
    fun testNetworkFailureGracefulFallback() = runTest {
        val fakeApi = FakeApiService()
        fakeApi.shouldFail = true

        val manager = FuturesUniverseManager(fakeApi)
        // Refresh should gracefully fail without exception, keeping fallback list
        val universe = manager.getOrRefreshUniverse(forceRefresh = true)

        assertNotNull(universe)
        assertTrue(universe.isNotEmpty())
        assertTrue(universe.contains("B-BTC_USDT"))
    }
}
