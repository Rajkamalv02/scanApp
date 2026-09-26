package com.coindcx.trading.engine.scanner

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.api.BinanceFuturesApiService
import com.coindcx.trading.data.api.ApiClient
import com.coindcx.trading.engine.MarketRegimePreference
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.UniverseStrategy
import com.coindcx.trading.util.AppLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min

/**
 * Dynamic Futures Universe Engine (v3).
 *
 * Intelligently discovers, qualifies, and ranks CoinDCX futures contracts using
 * multi-factor Market Activity Scoring (MAS) combined with anti-churn Schmitt-trigger
 * hysteresis and a strict hard ceiling (<= 23 active pairs).
 *
 * Architecture:
 * - Zero extra discovery requests: Driven by a single /exchange/ticker snapshot every 2 min.
 * - RollingMarketDataStore maintains 2-hour ring buffers for true RVOL and short-term velocity.
 * - Stage 1 Adaptive Pre-filter: Primary ($400k vol, 0.25% spread) with automatic fallback ($250k, 0.35%).
 * - Stage 2 MAS Scoring: True RVOL (25), Velocity (20), Range (20), Spread (20), Liquidity (15).
 * - Stage 3 Schmitt-Trigger Hysteresis: Enter at Rank <= 20, Exit at Rank > 28 or MAS < 40 (2-cycle dwell).
 * - Strict Hard Ceiling: Pool strictly capped at <= 23 pairs (3 Core Anchors + Dynamic Movers + Open Positions).
 *   Hard Ceiling supersedes dwell time during churn to guarantee cycle latency and candle budget.
 * - Stage 4 Soft Regime Routing: Provides strategy-specific candidate prioritization.
 */
class FuturesUniverseManager(
    private val apiService: CoinDCXApiService,
    private val futuresApiService: BinanceFuturesApiService? = null
) {
    companion object {
        const val PRIMARY_MIN_QUOTE_VOLUME_USDT = 400_000.0 // $400k daily turnover primary floor
        const val PRIMARY_MAX_BBO_SPREAD_PCT = 0.25 // Max 0.25% spread primary ceiling
        const val FALLBACK_MIN_QUOTE_VOLUME_USDT = 250_000.0 // $250k fallback floor
        const val FALLBACK_MAX_BBO_SPREAD_PCT = 0.35 // Max 0.35% fallback ceiling

        const val TARGET_DYNAMIC_MOVERS = 50
        const val ENTRY_RANK_CEILING = 50
        const val EXIT_RANK_FLOOR = 65
        const val EXIT_MIN_MAS_SCORE = 40.0
        const val MIN_DWELL_CYCLES = 2
        const val HARD_CEILING_TOTAL_POOL = 50
        const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 Minutes dynamic market recalculation
        const val DAILY_CACHE_TTL_MS = CACHE_TTL_MS

        val CORE_ANCHORS = listOf("B-BTC_USDT", "B-ETH_USDT", "B-SOL_USDT")

        val PEGGED_STABLECOINS = setOf(
            "USDC", "USD", "FDUSD", "TUSD", "EUR", "USDP", "BUSD", "DAI", "USDT"
        )

        val FALLBACK_MAJORS = listOf(
            "B-BTC_USDT", "B-ETH_USDT", "B-SOL_USDT", "B-XRP_USDT", "B-DOGE_USDT",
            "B-ADA_USDT", "B-BNB_USDT", "B-AVAX_USDT", "B-LINK_USDT", "B-NEAR_USDT",
            "B-SUI_USDT", "B-APT_USDT", "B-POL_USDT", "B-PEPE_USDT", "B-SHIB_USDT",
            "B-ARB_USDT", "B-OP_USDT", "B-TIA_USDT", "B-RENDER_USDT", "B-INJ_USDT",
            "B-AAVE_USDT", "B-LTC_USDT", "B-UNI_USDT", "B-DOT_USDT", "B-RUNE_USDT",
            "B-TRUMP_USDT", "B-FIL_USDT", "B-QNT_USDT", "B-PENDLE_USDT", "B-ZEC_USDT",
            "B-DASH_USDT", "B-ONDO_USDT", "B-ORDI_USDT", "B-MORPHO_USDT", "B-PROVE_USDT"
        )
    }

    data class AccountConstraints(
        val availableBalanceInr: Double,
        val leverage: Int,
        val riskPerTradePercent: Double = 1.0,
        val maxSingleExposurePercent: Double = 30.0,
        val usdtInrRate: Double = 90.0,
        val isLiveTrading: Boolean = true
    )

    data class ActiveCandidateRecord(
        val pair: String,
        val entryCycle: Long,
        var lastSeenCycle: Long,
        var lastMasScore: Double
    )

    data class CandidateMetrics(
        val pair: String,
        val quoteVolumeUsdt: Double,
        val lastPrice: Double,
        val spreadPct: Double
    )

    data class FuturesTickerData(
        val symbol: String,
        val lastPrice: Double,
        val change24hPercent: Double,
        val quoteVolumeUsdt: Double,
        val high24h: Double,
        val low24h: Double,
        val bid: Double,
        val ask: Double
    )

    data class InstrumentSpec(
        val pair: String,
        val step: Double,
        val minQuantity: Double,
        val targetCurrencyPrecision: Int,
        val minNotionalUsdt: Double,
        val baseCurrencyPrecision: Int = 4
    )

    val rollingStore = RollingMarketDataStore(maxSnapshotsPerPair = 60)

    private val universeRef = AtomicReference<List<String>>(FALLBACK_MAJORS.take(50))
    private val majorsRef = AtomicReference<List<String>>(CORE_ANCHORS)
    private val specsRef = AtomicReference<Map<String, InstrumentSpec>>(emptyMap())
    private val masScoresRef = AtomicReference<Map<String, MarketActivityScorer.MarketActivityScore>>(emptyMap())
    private val activePoolHistory = ConcurrentHashMap<String, ActiveCandidateRecord>()

    private val refreshMutex = Mutex()

    @Volatile
    private var lastRefreshTimestamp: Long = 0L

    @Volatile
    private var lastDailyRefreshUtcDay: Int = -1

    @Volatile
    private var currentCycleCount: Long = 0L

    fun getActiveUniverse(): List<String> = universeRef.get()
    fun getMajorUniverse(): List<String> = majorsRef.get()
    fun isTier1Major(pair: String): Boolean = CORE_ANCHORS.contains(pair) || majorsRef.get().contains(pair)
    fun getInstrumentSpec(pair: String): InstrumentSpec? = specsRef.get()[pair] ?: FuturesContractRegistry.getFuturesSpec(pair)
    fun getMasScore(pair: String): MarketActivityScorer.MarketActivityScore? = masScoresRef.get()[pair]
    fun getAllMasScores(): Map<String, MarketActivityScorer.MarketActivityScore> = masScoresRef.get()
    fun getLatestTicker(pair: String): RollingMarketDataStore.TickerSnapshot? = rollingStore.getLatestSnapshot(pair)
    fun getCurrentCycle(): Long = currentCycleCount

    /**
     * Determines whether a futures contract is affordable and tradable given live account constraints.
     */
    fun isContractAffordable(
        pair: String,
        lastPrice: Double,
        spec: InstrumentSpec?,
        constraints: AccountConstraints?
    ): Boolean {
        if (constraints == null || !constraints.isLiveTrading || constraints.availableBalanceInr <= 0.0) {
            return true
        }
        if (lastPrice <= 0.0) return false

        val futuresSpec = spec ?: FuturesContractRegistry.getFuturesSpec(pair)
        val minQty = futuresSpec?.minQuantity ?: 0.001
        val step = futuresSpec?.step ?: minQty
        val minNotionalUsdt = kotlin.math.max(6.0, futuresSpec?.minNotionalUsdt ?: 5.0)

        val minQtyForNotional = if (step > 0) kotlin.math.ceil(minNotionalUsdt / (step * lastPrice)) * step else minQty
        val minOrderQty = kotlin.math.max(minQty, minQtyForNotional)
        val minOrderNotionalUsdt = minOrderQty * lastPrice
        val minMarginRequiredInr = (minOrderNotionalUsdt * constraints.usdtInrRate) / constraints.leverage

        if (minMarginRequiredInr > constraints.availableBalanceInr) {
            AppLogManager.d("UNIVERSE", "[$pair] Excluded by Affordability: Min order requires ₹%.2f margin ($%.2f USDT, qty=%.4f @ $%.2f), exceeding balance ₹%.2f (Lev: %dx)"
                .format(minMarginRequiredInr, minOrderNotionalUsdt, minOrderQty, lastPrice, constraints.availableBalanceInr, constraints.leverage))
            return false
        }

        if (constraints.maxSingleExposurePercent in 1.0..99.0) {
            val maxExposureInr = constraints.availableBalanceInr * (constraints.maxSingleExposurePercent / 100.0)
            // On micro-accounts, the physical exchange notional floor ($6 USDT ~ ₹270 @ 2x) may naturally exceed
            // a strict % cap (e.g. 30% of ₹990 = ₹297). Allow single minimum contract lot tolerance if it
            // stays within safe micro-account ceiling (up to 50% of available cash).
            val isMicroAccount = constraints.availableBalanceInr < 5000.0
            val effectiveExposureCap = if (isMicroAccount) {
                kotlin.math.max(maxExposureInr, constraints.availableBalanceInr * 0.50)
            } else {
                maxExposureInr
            }

            if (minMarginRequiredInr > effectiveExposureCap) {
                AppLogManager.d("UNIVERSE", "[$pair] Excluded by Single Exposure Cap: Min order margin ₹%.2f > single position limit ₹%.2f (Base cap: ₹%.2f, %.0f%% of ₹%.2f)"
                    .format(minMarginRequiredInr, effectiveExposureCap, maxExposureInr, constraints.maxSingleExposurePercent, constraints.availableBalanceInr))
                return false
            }
        }

        return true
    }

    /**
     * Lock-free read of universe with transparent background 5-minute refresh if expired.
     */
    suspend fun getOrRefreshUniverse(
        forceRefresh: Boolean = false,
        openPositionPairs: List<String> = emptyList(),
        accountConstraints: AccountConstraints? = null
    ): List<String> {
        val now = System.currentTimeMillis()
        val isExpired = (now - lastRefreshTimestamp) >= CACHE_TTL_MS
        val currentList = universeRef.get()

        if ((isExpired || forceRefresh || currentList.isEmpty()) && refreshMutex.tryLock()) {
            try {
                withContext(Dispatchers.IO) {
                    refreshUniverseInternal(openPositionPairs, accountConstraints)
                }
            } finally {
                refreshMutex.unlock()
            }
        }
        return universeRef.get()
    }

    /**
     * Strategy-specific candidate prioritization.
     * Applies a soft regime multiplier (0.8x to 1.2x) to MAS scores based on strategy edge,
     * while guaranteeing open positions and core anchors are prioritized.
     */
    fun getStrategyCandidates(
        strategy: Strategy,
        openPositionPairs: List<String> = emptyList()
    ): List<String> {
        val universe = universeRef.get()
        val scores = masScoresRef.get()
        val pinned = openPositionPairs.toSet()

        return universe.sortedWith(
            compareByDescending<String> { pinned.contains(it) } // Open positions evaluated first
                .thenByDescending { pair ->
                    val mas = scores[pair]
                    if (mas == null) {
                        50.0
                    } else {
                        val multiplier = when (strategy.preferredRegime) {
                            MarketRegimePreference.TRENDING_MOMENTUM -> {
                                when {
                                    abs(mas.v1h) >= 1.5 && mas.rangePct >= 5.0 -> 1.2
                                    abs(mas.v1h) < 0.5 -> 0.8
                                    else -> 1.0
                                }
                            }
                            MarketRegimePreference.MEAN_REVERTING_RANGE -> {
                                when {
                                    mas.rangePct in 4.0..12.0 && abs(mas.v15m) <= 1.0 -> 1.2
                                    abs(mas.v1h) >= 4.0 -> 0.8
                                    else -> 1.0
                                }
                            }
                            MarketRegimePreference.ANY -> 1.0
                        }
                        mas.totalScore * multiplier
                    }
                }
        )
    }

    /**
     * Cross-sectional UniverseStrategy candidate prioritization.
     */
    fun getStrategyCandidates(
        strategy: UniverseStrategy,
        openPositionPairs: List<String> = emptyList()
    ): List<String> = getStrategyCandidates(
        object : Strategy {
            override val id: String = strategy.id
            override val name: String = strategy.name
            override val description: String = strategy.description
            override val parametersSummary: String = strategy.parametersSummary
            override val requiredCandleCount: Int = strategy.requiredCandleCount
            override val preferredRegime: MarketRegimePreference = strategy.preferredRegime
        },
        openPositionPairs
    )

    private suspend fun refreshUniverseInternal(
        openPositionPairs: List<String> = emptyList(),
        accountConstraints: AccountConstraints? = null
    ) {
        try {
            currentCycleCount++
            AppLogManager.scanner("Refreshing dynamic futures universe (Cycle #$currentCycleCount, Daily TTL: 24h)...")

            // 1. Fetch active futures instruments (e.g. 500+ active perpetuals)
            val activeResp = apiService.getActiveInstruments()
            if (!activeResp.isSuccessful || activeResp.body().isNullOrEmpty()) {
                AppLogManager.w("SCANNER", "Failed fetching active_instruments: HTTP ${activeResp.code()}. Keeping cached universe.")
                return
            }
            val activeSet = activeResp.body()!!.toSet()

            // 2. Fetch markets details (370+ active futures markets with specs)
            val detailsResp = apiService.getMarketsDetails()
            if (!detailsResp.isSuccessful || detailsResp.body().isNullOrEmpty()) {
                AppLogManager.w("SCANNER", "Failed fetching markets_details: HTTP ${detailsResp.code()}. Keeping cached universe.")
                return
            }

            val pairToDetails = mutableMapOf<String, Pair<String, String>>() // pair -> (status, targetCurrency)
            val nameToPair = mutableMapOf<String, String>() // coindcx_name -> pair
            val specsMap = mutableMapOf<String, InstrumentSpec>()

            for (item in detailsResp.body()!!) {
                val pair = item["pair"]?.toString() ?: continue
                if (!pair.startsWith("B-")) continue

                val status = item["status"]?.toString() ?: "inactive"
                val targetCurrency = item["target_currency_short_name"]?.toString()?.uppercase(Locale.US) ?: ""
                val coindcxName = item["coindcx_name"]?.toString() ?: ""

                pairToDetails[pair] = Pair(status, targetCurrency)
                if (coindcxName.isNotBlank()) {
                    nameToPair[coindcxName] = pair
                }

                // FuturesContractRegistry provides authoritative derivatives lot sizes (e.g. 0.1 for AAVE vs 0.001 spot)
                val futuresSpec = FuturesContractRegistry.getFuturesSpec(pair)
                val step = futuresSpec?.step ?: item["step"]?.toString()?.toDoubleOrNull() ?: 0.001
                val minQty = futuresSpec?.minQuantity ?: item["min_quantity"]?.toString()?.toDoubleOrNull() ?: 0.001
                val precision = futuresSpec?.targetCurrencyPrecision ?: item["target_currency_precision"]?.toString()?.toDoubleOrNull()?.toInt() ?: 3
                val minNotional = kotlin.math.max(6.0, futuresSpec?.minNotionalUsdt ?: item["min_notional"]?.toString()?.toDoubleOrNull() ?: 5.0)
                val basePrecision = item["base_currency_precision"]?.toString()?.toDoubleOrNull()?.toInt() ?: futuresSpec?.baseCurrencyPrecision ?: 4
                specsMap[pair] = InstrumentSpec(pair, step, minQty, precision, minNotional, basePrecision)
            }
            if (specsMap.isNotEmpty()) {
                specsRef.set(specsMap)
            }

            // 3. Fetch 24h Crypto Futures Ticker data (price, change24h, quoteVolume, high, low, BBO)
            val futuresApi = futuresApiService
            val futuresTickersMap: Map<String, FuturesTickerData>? = if (futuresApi != null) {
                try {
                    val resp24h = kotlinx.coroutines.withTimeoutOrNull(6000L) { futuresApi.get24hTickers() }
                    if (resp24h != null && resp24h.isSuccessful && !resp24h.body().isNullOrEmpty()) {
                        val bookMap = try {
                            val respBook = kotlinx.coroutines.withTimeoutOrNull(4000L) { futuresApi.getBookTickers() }
                            if (respBook != null && respBook.isSuccessful && respBook.body() != null) {
                                respBook.body()!!.associateBy { it.symbol }
                            } else emptyMap()
                        } catch (_: Exception) {
                            emptyMap()
                        }

                        val map = mutableMapOf<String, FuturesTickerData>()
                        for (t in resp24h.body()!!) {
                            val lastPrice = t.lastPrice.toDoubleOrNull() ?: continue
                            val change24h = t.priceChangePercent.toDoubleOrNull() ?: 0.0
                            val quoteVol = t.quoteVolume.toDoubleOrNull() ?: 0.0
                            val high = t.highPrice.toDoubleOrNull() ?: lastPrice
                            val low = t.lowPrice.toDoubleOrNull() ?: lastPrice
                            val book = bookMap[t.symbol]
                            val bid = book?.bidPrice?.toDoubleOrNull() ?: (lastPrice * 0.9998)
                            val ask = book?.askPrice?.toDoubleOrNull() ?: (lastPrice * 1.0002)

                            map[t.symbol] = FuturesTickerData(
                                symbol = t.symbol,
                                lastPrice = lastPrice,
                                change24hPercent = change24h,
                                quoteVolumeUsdt = quoteVol,
                                high24h = high,
                                low24h = low,
                                bid = bid,
                                ask = ask
                            )
                        }
                        map
                    } else null
                } catch (e: Exception) {
                    AppLogManager.w("SCANNER", "Failed fetching Crypto Futures 24h tickers: ${e.message}")
                    null
                }
            } else null

            // Fallback: Fetch CoinDCX ticker if futures API did not return data
            val spotTickerMap = if (futuresTickersMap == null || futuresTickersMap.isEmpty()) {
                val tickerResp = apiService.getTicker()
                if (tickerResp.isSuccessful && !tickerResp.body().isNullOrEmpty()) {
                    val m = mutableMapOf<String, Map<String, Any>>()
                    for (t in tickerResp.body()!!) {
                        val market = t["market"]?.toString() ?: continue
                        m[market] = t
                    }
                    m
                } else emptyMap()
            } else emptyMap()

            if (futuresTickersMap.isNullOrEmpty() && spotTickerMap.isEmpty()) {
                AppLogManager.w("SCANNER", "Failed fetching market ticker data. Keeping cached universe.")
                return
            }

            if (!futuresTickersMap.isNullOrEmpty()) {
                AppLogManager.scanner("Loaded real-time Crypto Futures 24h statistics (${futuresTickersMap.size} contracts from Binance Futures venue).")
            }

            val nowMs = System.currentTimeMillis()

            data class RawMarketData(
                val pair: String,
                val quoteVolumeUsdt: Double,
                val lastPrice: Double,
                val high24h: Double,
                val low24h: Double,
                val bid: Double,
                val ask: Double,
                val change24h: Double,
                val spreadPct: Double
            )

            val candidateList = mutableListOf<RawMarketData>()

            for (pair in activeSet) {
                if (!pair.startsWith("B-")) continue

                // Check active status if present in pairToDetails
                val details = pairToDetails[pair]
                if (details != null && !details.first.equals("active", ignoreCase = true)) continue

                // Exclude pegged stablecoins
                val targetCurrency = details?.second?.ifBlank { null }
                    ?: pair.removePrefix("B-").split("_").firstOrNull()?.uppercase(Locale.US)
                    ?: ""
                if (PEGGED_STABLECOINS.contains(targetCurrency)) continue

                val futuresSymbol = pair.removePrefix("B-").replace("_", "")

                val rawData: RawMarketData? = if (!futuresTickersMap.isNullOrEmpty()) {
                    val ft = futuresTickersMap[futuresSymbol]
                    if (ft != null && ft.lastPrice > 0.0) {
                        val spread = if (ft.ask >= ft.bid && ft.bid > 0) {
                            ((ft.ask - ft.bid) / ft.lastPrice) * 100.0
                        } else {
                            0.05 // Default tight spread for liquid crypto futures contracts
                        }
                        RawMarketData(
                            pair = pair,
                            quoteVolumeUsdt = ft.quoteVolumeUsdt,
                            lastPrice = ft.lastPrice,
                            high24h = ft.high24h,
                            low24h = ft.low24h,
                            bid = ft.bid,
                            ask = ft.ask,
                            change24h = ft.change24hPercent,
                            spreadPct = spread
                        )
                    } else null
                } else null

                val finalMarketData = rawData ?: run {
                    val coindcxName = nameToPair.entries.find { it.value == pair }?.key ?: futuresSymbol
                    val t = spotTickerMap[coindcxName] ?: return@run null
                    val lastPrice = (t["last_price"]?.toString()?.toDoubleOrNull()) ?: return@run null
                    val baseVolume = (t["volume"]?.toString()?.toDoubleOrNull()) ?: return@run null
                    if (lastPrice <= 0.0 || baseVolume <= 0.0) return@run null

                    val quoteVolumeUsdt = baseVolume * lastPrice
                    val high24h = (t["high"]?.toString()?.toDoubleOrNull()) ?: lastPrice
                    val low24h = (t["low"]?.toString()?.toDoubleOrNull()) ?: lastPrice
                    val bid = (t["bid"]?.toString()?.toDoubleOrNull()) ?: 0.0
                    val ask = (t["ask"]?.toString()?.toDoubleOrNull()) ?: 0.0
                    val change24h = (t["change_24_hour"]?.toString()?.toDoubleOrNull()) ?: 0.0
                    val spreadPct = if (lastPrice > 0 && ask >= bid && bid > 0) {
                        ((ask - bid) / lastPrice) * 100.0
                    } else {
                        999.0
                    }
                    RawMarketData(
                        pair = pair,
                        quoteVolumeUsdt = quoteVolumeUsdt,
                        lastPrice = lastPrice,
                        high24h = high24h,
                        low24h = low24h,
                        bid = bid,
                        ask = ask,
                        change24h = change24h,
                        spreadPct = spreadPct
                    )
                } ?: continue

                // Ingest into RollingMarketDataStore for rolling RVOL and velocity calculations
                val baseVol = if (finalMarketData.lastPrice > 0) finalMarketData.quoteVolumeUsdt / finalMarketData.lastPrice else 0.0
                rollingStore.addSnapshot(
                    pair,
                    RollingMarketDataStore.TickerSnapshot(
                        timestampMs = nowMs,
                        lastPrice = finalMarketData.lastPrice,
                        baseVolume = baseVol,
                        quoteVolumeUsdt = finalMarketData.quoteVolumeUsdt,
                        high24h = finalMarketData.high24h,
                        low24h = finalMarketData.low24h,
                        bid = finalMarketData.bid,
                        ask = finalMarketData.ask,
                        change24h = finalMarketData.change24h
                    )
                )

                candidateList.add(finalMarketData)
            }

            // 4. Stage 1: Adaptive Pre-Filter (Daily Market Evaluation independent of strategy timeframe)
            var stage1Passing = candidateList.filter {
                it.quoteVolumeUsdt >= PRIMARY_MIN_QUOTE_VOLUME_USDT && it.spreadPct <= PRIMARY_MAX_BBO_SPREAD_PCT
            }

            val isFallbackActive = stage1Passing.size < TARGET_DYNAMIC_MOVERS
            if (isFallbackActive) {
                stage1Passing = candidateList.filter {
                    it.quoteVolumeUsdt >= FALLBACK_MIN_QUOTE_VOLUME_USDT && it.spreadPct <= FALLBACK_MAX_BBO_SPREAD_PCT
                }
                AppLogManager.w("SCANNER", "Adaptive Fallback Activated: Relaxed filters to $250k vol and 0.35% spread (${stage1Passing.size} pairs qualified).")
            }

            if (stage1Passing.isEmpty()) {
                AppLogManager.w("SCANNER", "Universe discovery produced 0 qualifying pairs. Keeping cached universe.")
                return
            }

            // Apply Live-Trading Account Configuration & Affordability Pre-Filter
            val affordableCandidates = if (accountConstraints != null && accountConstraints.isLiveTrading && accountConstraints.availableBalanceInr > 0.0) {
                val passingAffordable = stage1Passing.filter { cand ->
                    isContractAffordable(cand.pair, cand.lastPrice, specsMap[cand.pair], accountConstraints)
                }
                AppLogManager.scanner(
                    "Account Affordability Pre-Filter: %d of %d qualified pairs affordable with balance ₹%.2f @ %dx leverage"
                        .format(passingAffordable.size, stage1Passing.size, accountConstraints.availableBalanceInr, accountConstraints.leverage)
                )
                if (passingAffordable.isNotEmpty()) passingAffordable else stage1Passing
            } else {
                stage1Passing
            }

            // 5. Stage 2: MAS Scoring on all affordable Stage 1 passing pairs
            val masScoresMap = mutableMapOf<String, MarketActivityScorer.MarketActivityScore>()
            for (cand in affordableCandidates) {
                val rvolResult = rollingStore.calculateRvol(cand.pair)
                val velocityResult = rollingStore.calculateVelocity(cand.pair)

                val mas = MarketActivityScorer.calculateScore(
                    pair = cand.pair,
                    quoteVolumeUsdt = cand.quoteVolumeUsdt,
                    lastPrice = cand.lastPrice,
                    high24h = cand.high24h,
                    low24h = cand.low24h,
                    bid = cand.bid,
                    ask = cand.ask,
                    change24h = cand.change24h,
                    rvolResult = rvolResult,
                    velocityResult = velocityResult
                )
                masScoresMap[cand.pair] = mas
            }

            // 6. Stage 3: Dynamic 50 Selection Prioritizing Top CoinDCX 24h Movers
            val pinnedSet = openPositionPairs.filter { activeSet.contains(it) }.toSet()
            val maxDynamicMovers = (HARD_CEILING_TOTAL_POOL - pinnedSet.size).coerceAtLeast(0)

            // A. Rank candidates by absolute 24h change magnitude (Top Gainers & Losers on CoinDCX)
            val top24hMovers = affordableCandidates
                .sortedByDescending { abs(it.change24h) }
                .map { it.pair }

            // B. Also rank by MAS score (liquidity, spread, technical strength)
            val rankedByMas = affordableCandidates.sortedByDescending { masScoresMap[it.pair]?.totalScore ?: 0.0 }
            val rankedByMasPairs: List<String> = rankedByMas.map { it.pair }

            // Reserve up to 35 slots for top 24h movers from CoinDCX
            val moverSlots = min(35, maxDynamicMovers)
            val selected24hMovers = top24hMovers.filter { !pinnedSet.contains(it) }.take(moverSlots)

            // Fill remaining dynamic slots with highest MAS / volume leaders (e.g. BTC, ETH, SOL, etc.)
            val remainingSlots = maxDynamicMovers - selected24hMovers.size
            val backfillLeaders: List<String> = (rankedByMasPairs + CORE_ANCHORS)
                .filter { candidatePair -> !pinnedSet.contains(candidatePair) && !selected24hMovers.contains(candidatePair) }
                .take(remainingSlots)

            val selectedDynamicMovers: List<String> = (selected24hMovers + backfillLeaders).take(maxDynamicMovers)

            // Record in activePoolHistory for telemetry & tracking
            selectedDynamicMovers.forEach { pair: String ->
                activePoolHistory.compute(pair) { _, existing ->
                    existing?.apply {
                        lastSeenCycle = currentCycleCount
                        lastMasScore = masScoresMap[pair]?.totalScore ?: 0.0
                    } ?: ActiveCandidateRecord(
                        pair = pair,
                        entryCycle = currentCycleCount,
                        lastSeenCycle = currentCycleCount,
                        lastMasScore = masScoresMap[pair]?.totalScore ?: 0.0
                    )
                }
            }

            // Clean up evicted pairs from history
            val selectedSet = selectedDynamicMovers.toSet()
            for (pair in activePoolHistory.keys) {
                if (!selectedSet.contains(pair)) {
                    activePoolHistory.remove(pair)
                }
            }

            // D. Assemble final universe: Pinned Positions + Dynamic Movers (strictly data-driven)
            val finalUniverse: List<String> = (pinnedSet.toList() + selectedDynamicMovers).distinct().take(HARD_CEILING_TOTAL_POOL + pinnedSet.size)

            universeRef.set(finalUniverse)
            majorsRef.set(finalUniverse.take(10).ifEmpty { CORE_ANCHORS })
            masScoresRef.set(masScoresMap)
            lastRefreshTimestamp = System.currentTimeMillis()
            lastDailyRefreshUtcDay = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.DAY_OF_YEAR)

            val masScoresList = finalUniverse.mapNotNull { masScoresMap[it]?.totalScore }
            val avgMas = if (masScoresList.isNotEmpty()) masScoresList.average() else 0.0
            val topMas = masScoresList.maxOrNull() ?: 0.0

            AppLogManager.scanner(
                "Dynamic Universe Updated: %d pairs (%d 24h Movers / Dynamic Leaders, %d Pinned | Top MAS: %.1f, Avg MAS: %.1f | TTL: 5 min)."
                    .format(finalUniverse.size, selectedDynamicMovers.size, pinnedSet.size, topMas, avgMas)
            )

            MarketScanState.updateUniverseSummary(
                MarketScanState.UniverseDiscoverySummary(
                    totalActivePoolSize = finalUniverse.size,
                    anchorCount = 0,
                    dynamicMoverCount = selectedDynamicMovers.size,
                    pinnedCount = pinnedSet.size,
                    topMasScore = topMas,
                    avgMasScore = avgMas,
                    isFallbackActive = isFallbackActive
                )
            )
        } catch (e: Exception) {
            AppLogManager.e("SCANNER", "Exception during dynamic universe refresh: ${e.message}", e)
        }
    }
}
