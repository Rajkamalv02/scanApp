package com.coindcx.trading.engine.scanner

import com.coindcx.trading.data.api.CoinDCXApiService
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
    private val apiService: CoinDCXApiService
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
        const val DAILY_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 Hours daily market recalculation
        const val CACHE_TTL_MS = DAILY_CACHE_TTL_MS

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
            if (minMarginRequiredInr > maxExposureInr) {
                AppLogManager.d("UNIVERSE", "[$pair] Excluded by Single Exposure Cap: Min order margin ₹%.2f > single position limit ₹%.2f (%.0f%% of ₹%.2f)"
                    .format(minMarginRequiredInr, maxExposureInr, constraints.maxSingleExposurePercent, constraints.availableBalanceInr))
                return false
            }
        }

        return true
    }

    /**
     * Lock-free read of universe with transparent background daily refresh if expired.
     */
    suspend fun getOrRefreshUniverse(
        forceRefresh: Boolean = false,
        openPositionPairs: List<String> = emptyList(),
        accountConstraints: AccountConstraints? = null
    ): List<String> {
        val now = System.currentTimeMillis()
        val currentUtcDay = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.DAY_OF_YEAR)
        val isExpired = (now - lastRefreshTimestamp) >= DAILY_CACHE_TTL_MS || (lastDailyRefreshUtcDay != -1 && currentUtcDay != lastDailyRefreshUtcDay)
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
                val basePrecision = futuresSpec?.baseCurrencyPrecision ?: item["base_currency_precision"]?.toString()?.toDoubleOrNull()?.toInt() ?: 4
                specsMap[pair] = InstrumentSpec(pair, step, minQty, precision, minNotional, basePrecision)
            }
            if (specsMap.isNotEmpty()) {
                specsRef.set(specsMap)
            }

            // 3. Fetch 24h Ticker data (volume, last_price, bid, ask, high, low, change_24_hour)
            val tickerResp = apiService.getTicker()
            if (!tickerResp.isSuccessful || tickerResp.body().isNullOrEmpty()) {
                AppLogManager.w("SCANNER", "Failed fetching ticker data: HTTP ${tickerResp.code()}. Keeping cached universe.")
                return
            }

            val tickerMap = mutableMapOf<String, Map<String, Any>>()
            for (t in tickerResp.body()!!) {
                val market = t["market"]?.toString() ?: continue
                tickerMap[market] = t
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

            for ((coindcxName, pair) in nameToPair) {
                // Must be in CoinDCX active derivatives instruments
                if (!activeSet.contains(pair)) continue

                // Market details status must be active
                val details = pairToDetails[pair] ?: continue
                if (!details.first.equals("active", ignoreCase = true)) continue

                // Exclude pegged stablecoins
                if (PEGGED_STABLECOINS.contains(details.second)) continue

                val t = tickerMap[coindcxName] ?: continue
                val lastPrice = (t["last_price"]?.toString()?.toDoubleOrNull()) ?: continue
                val baseVolume = (t["volume"]?.toString()?.toDoubleOrNull()) ?: continue
                if (lastPrice <= 0.0 || baseVolume <= 0.0) continue

                val quoteVolumeUsdt = baseVolume * lastPrice
                val high24h = (t["high"]?.toString()?.toDoubleOrNull()) ?: lastPrice
                val low24h = (t["low"]?.toString()?.toDoubleOrNull()) ?: lastPrice
                val bid = (t["bid"]?.toString()?.toDoubleOrNull()) ?: 0.0
                val ask = (t["ask"]?.toString()?.toDoubleOrNull()) ?: 0.0
                val change24h = (t["change_24_hour"]?.toString()?.toDoubleOrNull()) ?: 0.0

                val spreadPct = if (lastPrice > 0 && ask >= bid && bid > 0) {
                    ((ask - bid) / lastPrice) * 100.0
                } else {
                    999.0 // Invalid book
                }

                // Ingest into RollingMarketDataStore for rolling RVOL and velocity calculations
                rollingStore.addSnapshot(
                    pair,
                    RollingMarketDataStore.TickerSnapshot(
                        timestampMs = nowMs,
                        lastPrice = lastPrice,
                        baseVolume = baseVolume,
                        quoteVolumeUsdt = quoteVolumeUsdt,
                        high24h = high24h,
                        low24h = low24h,
                        bid = bid,
                        ask = ask,
                        change24h = change24h
                    )
                )

                candidateList.add(
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
                )
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

            // Rank Stage 1 passing pairs by MAS score descending
            val rankedByMas = affordableCandidates.mapNotNull { masScoresMap[it.pair] }
                .sortedByDescending { it.totalScore }
            val pairToMasRank = rankedByMas.mapIndexed { index, score -> score.pair to (index + 1) }.toMap()

            // 6. Stage 3: Dynamic 50 Movers with Schmitt-Trigger Hysteresis
            val pinnedSet = openPositionPairs.filter { activeSet.contains(it) }.toSet()

            // Dynamic mover capacity up to TARGET_DYNAMIC_MOVERS (50)
            val maxDynamicMovers = (HARD_CEILING_TOTAL_POOL - pinnedSet.size).coerceAtLeast(0)

            val qualifiedDynamicCandidates = mutableListOf<String>()
            val passingPairSet = affordableCandidates.map { it.pair }.toSet()

            // A. Existing pool members evaluated against exit criteria
            val existingCandidates = activePoolHistory.keys.toList()
            for (pair in existingCandidates) {
                if (pinnedSet.contains(pair)) continue
                val record = activePoolHistory[pair] ?: continue
                val rank = pairToMasRank[pair] ?: 999
                val score = masScoresMap[pair]?.totalScore ?: 0.0
                val dwellCycles = currentCycleCount - record.entryCycle

                // Exit Condition: Drops if not in Stage 1, OR (rank > EXIT_RANK_FLOOR OR score < EXIT_MIN_MAS_SCORE) AFTER dwell cycles
                val shouldEvict = !passingPairSet.contains(pair) || (dwellCycles >= MIN_DWELL_CYCLES && (rank > EXIT_RANK_FLOOR || score < EXIT_MIN_MAS_SCORE))

                if (!shouldEvict) {
                    record.lastSeenCycle = currentCycleCount
                    record.lastMasScore = score
                    qualifiedDynamicCandidates.add(pair)
                } else {
                    activePoolHistory.remove(pair)
                }
            }

            // B. Newly qualifying pairs: Rank <= ENTRY_RANK_CEILING (50)
            for (ranked in rankedByMas) {
                val pair = ranked.pair
                if (pinnedSet.contains(pair)) continue
                val rank = pairToMasRank[pair] ?: 999
                if (rank <= ENTRY_RANK_CEILING && !qualifiedDynamicCandidates.contains(pair)) {
                    qualifiedDynamicCandidates.add(pair)
                    activePoolHistory[pair] = ActiveCandidateRecord(
                        pair = pair,
                        entryCycle = currentCycleCount,
                        lastSeenCycle = currentCycleCount,
                        lastMasScore = ranked.totalScore
                    )
                }
            }

            // C. Enforce Strict Hard Ceiling on dynamic movers
            val selectedDynamicMovers = if (qualifiedDynamicCandidates.size > maxDynamicMovers) {
                qualifiedDynamicCandidates.sortedWith(
                    compareByDescending<String> { masScoresMap[it]?.totalScore ?: 0.0 }
                        .thenBy { activePoolHistory[it]?.entryCycle ?: Long.MAX_VALUE }
                ).take(maxDynamicMovers)
            } else if (qualifiedDynamicCandidates.size < maxDynamicMovers) {
                val backfilled = qualifiedDynamicCandidates.toMutableList()
                for (ranked in rankedByMas) {
                    if (backfilled.size >= maxDynamicMovers) break
                    val pair = ranked.pair
                    if (!pinnedSet.contains(pair) && !backfilled.contains(pair)) {
                        backfilled.add(pair)
                        activePoolHistory[pair] = ActiveCandidateRecord(
                            pair = pair,
                            entryCycle = currentCycleCount,
                            lastSeenCycle = currentCycleCount,
                            lastMasScore = ranked.totalScore
                        )
                    }
                }
                backfilled
            } else {
                qualifiedDynamicCandidates
            }

            // Clean up evicted pairs from history
            val selectedSet = selectedDynamicMovers.toSet()
            for (pair in activePoolHistory.keys) {
                if (!selectedSet.contains(pair)) {
                    activePoolHistory.remove(pair)
                }
            }

            // D. Assemble final universe: Pinned Positions + Dynamic Movers (strictly data-driven)
            val finalUniverse = (pinnedSet + selectedDynamicMovers).distinct().take(HARD_CEILING_TOTAL_POOL + pinnedSet.size)

            universeRef.set(finalUniverse)
            majorsRef.set(finalUniverse.take(10).ifEmpty { CORE_ANCHORS })
            masScoresRef.set(masScoresMap)
            lastRefreshTimestamp = System.currentTimeMillis()
            lastDailyRefreshUtcDay = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.DAY_OF_YEAR)

            val avgMas = finalUniverse.mapNotNull { masScoresMap[it]?.totalScore }.average().let { if (it.isNaN()) 0.0 else it }
            val topMas = finalUniverse.mapNotNull { masScoresMap[it]?.totalScore }.maxOrNull() ?: 0.0

            AppLogManager.scanner(
                "Dynamic Universe Updated: %d pairs (%d Dynamic Movers, %d Pinned | Top MAS: %.1f, Avg MAS: %.1f | Daily Ceiling: %d)."
                    .format(finalUniverse.size, selectedDynamicMovers.size, pinnedSet.size, topMas, avgMas, HARD_CEILING_TOTAL_POOL)
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
