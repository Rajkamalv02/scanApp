package com.coindcx.trading.engine.scanner

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.util.AppLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Dynamic Futures Universe Engine.
 *
 * Intelligently discovers, qualifies, and ranks a universe of up to 85 liquid crypto
 * futures contracts on CoinDCX based on real economic turnover (24h Quote Volume in USDT)
 * and Top-of-Book (BBO) spread constraints.
 *
 * Key Architecture Highlights:
 * - Metric: Standardized 24h Quote Volume (volume * last_price in USDT).
 * - Strict Ceiling: Cap at 85 contracts. Zero backfilling below threshold.
 * - Quality Filters: Quote volume >= $250,000 USDT, BBO Spread <= 0.35%.
 * - Stablecoin Pruning: Drops pegged fiat/stablecoin contracts (USDC, FDUSD, EUR, etc.).
 * - Concurrency: Lock-free reads via AtomicReference<List<String>>.
 * - Two-Tier Hierarchy: Tier-1 Majors (Top 25) vs Tier-2 Expanded Universe (Ranks 26-85).
 */
class FuturesUniverseManager(
    private val apiService: CoinDCXApiService
) {
    companion object {
        const val MAX_UNIVERSE_CEILING = 85
        const val TIER_1_MAJORS_COUNT = 25
        const val MIN_QUOTE_VOLUME_USDT = 250_000.0 // $250k daily turnover floor
        const val MAX_BBO_SPREAD_PCT = 0.35 // Max 0.35% bid-ask spread
        const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L // 2 Hours

        val PEGGED_STABLECOINS = setOf(
            "USDC", "USD", "FDUSD", "TUSD", "EUR", "USDP", "BUSD", "DAI", "USDT"
        )

        val FALLBACK_MAJORS = listOf(
            "B-BTC_USDT", "B-ETH_USDT", "B-SOL_USDT", "B-XRP_USDT", "B-DOGE_USDT",
            "B-ADA_USDT", "B-BNB_USDT", "B-AVAX_USDT", "B-LINK_USDT", "B-NEAR_USDT",
            "B-SUI_USDT", "B-APT_USDT", "B-POL_USDT", "B-PEPE_USDT", "B-SHIB_USDT",
            "B-ARB_USDT", "B-OP_USDT", "B-TIA_USDT", "B-RENDER_USDT", "B-INJ_USDT",
            "B-AAVE_USDT", "B-LTC_USDT", "B-UNI_USDT", "B-DOT_USDT", "B-RUNE_USDT"
        )
    }

    private val universeRef = AtomicReference<List<String>>(FALLBACK_MAJORS)
    private val majorsRef = AtomicReference<List<String>>(FALLBACK_MAJORS)
    private val refreshMutex = Mutex()

    @Volatile
    private var lastRefreshTimestamp: Long = 0L

    data class CandidateMetrics(
        val pair: String,
        val quoteVolumeUsdt: Double,
        val lastPrice: Double,
        val spreadPct: Double
    )

    fun getActiveUniverse(): List<String> = universeRef.get()
    fun getMajorUniverse(): List<String> = majorsRef.get()
    fun isTier1Major(pair: String): Boolean = majorsRef.get().contains(pair)

    /**
     * Lock-free read of universe with transparent background refresh if expired.
     */
    suspend fun getOrRefreshUniverse(forceRefresh: Boolean = false): List<String> {
        val now = System.currentTimeMillis()
        val isExpired = (now - lastRefreshTimestamp) >= CACHE_TTL_MS
        val currentList = universeRef.get()

        if ((isExpired || forceRefresh || currentList.isEmpty()) && refreshMutex.tryLock()) {
            try {
                withContext(Dispatchers.IO) {
                    refreshUniverseInternal()
                }
            } finally {
                refreshMutex.unlock()
            }
        }
        return universeRef.get()
    }

    private suspend fun refreshUniverseInternal() {
        try {
            AppLogManager.scanner("Refreshing dynamic futures universe from CoinDCX derivatives market data...")

            // 1. Fetch active futures instruments (e.g. 502 active perpetuals)
            val activeResp = apiService.getActiveInstruments()
            if (!activeResp.isSuccessful || activeResp.body().isNullOrEmpty()) {
                AppLogManager.w("SCANNER", "Failed fetching active_instruments: HTTP ${activeResp.code()}. Keeping cached universe.")
                return
            }
            val activeSet = activeResp.body()!!.toSet()

            // 2. Fetch markets details (377 active futures markets with specs)
            val detailsResp = apiService.getMarketsDetails()
            if (!detailsResp.isSuccessful || detailsResp.body().isNullOrEmpty()) {
                AppLogManager.w("SCANNER", "Failed fetching markets_details: HTTP ${detailsResp.code()}. Keeping cached universe.")
                return
            }

            // Map pair to status and target currency
            val pairToDetails = mutableMapOf<String, Pair<String, String>>() // pair -> (status, targetCurrency)
            val nameToPair = mutableMapOf<String, String>() // coindcx_name -> pair

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
            }

            // 3. Fetch 24h Ticker data (volume, last_price, bid, ask)
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

            // 4. Qualify and filter candidates
            val qualified = mutableListOf<CandidateMetrics>()

            for ((coindcxName, pair) in nameToPair) {
                // Gate A: Must be in CoinDCX active derivatives instruments
                if (!activeSet.contains(pair)) continue

                // Gate B: Market details status must be active
                val details = pairToDetails[pair] ?: continue
                if (!details.first.equals("active", ignoreCase = true)) continue

                // Gate C: Exclude pegged stablecoins
                if (PEGGED_STABLECOINS.contains(details.second)) continue

                // Gate D: Parse ticker metrics
                val t = tickerMap[coindcxName] ?: continue
                val lastPrice = (t["last_price"]?.toString()?.toDoubleOrNull()) ?: continue
                val baseVolume = (t["volume"]?.toString()?.toDoubleOrNull()) ?: continue
                if (lastPrice <= 0.0 || baseVolume <= 0.0) continue

                // Metric: Standardized 24h Quote Volume in USDT
                val quoteVolumeUsdt = baseVolume * lastPrice

                // Gate E: Minimum 24h Quote Volume floor
                if (quoteVolumeUsdt < MIN_QUOTE_VOLUME_USDT) continue

                // Gate F: Top-of-Book (BBO) spread limit
                val bid = (t["bid"]?.toString()?.toDoubleOrNull()) ?: 0.0
                val ask = (t["ask"]?.toString()?.toDoubleOrNull()) ?: 0.0
                val spreadPct = if (lastPrice > 0 && ask >= bid && bid > 0) {
                    ((ask - bid) / lastPrice) * 100.0
                } else {
                    999.0 // Invalid book
                }

                if (spreadPct > MAX_BBO_SPREAD_PCT) continue

                qualified.add(
                    CandidateMetrics(
                        pair = pair,
                        quoteVolumeUsdt = quoteVolumeUsdt,
                        lastPrice = lastPrice,
                        spreadPct = spreadPct
                    )
                )
            }

            if (qualified.isEmpty()) {
                AppLogManager.w("SCANNER", "Universe discovery produced 0 qualifying pairs. Keeping cached universe.")
                return
            }

            // 5. Rank by Quote Volume descending
            qualified.sortByDescending { it.quoteVolumeUsdt }

            // 6. Strict Ceiling (Take Top N <= 85. NO BACKFILLING below threshold!)
            val finalUniverseSize = qualified.size.coerceAtMost(MAX_UNIVERSE_CEILING)
            val selectedCandidates = qualified.take(finalUniverseSize)
            val selectedPairs = selectedCandidates.map { it.pair }

            val majorCount = selectedPairs.size.coerceAtMost(TIER_1_MAJORS_COUNT)
            val majorPairs = selectedPairs.take(majorCount)

            // 7. Atomic Swap: Thread-safe, non-blocking reference replacement
            universeRef.set(selectedPairs)
            majorsRef.set(majorPairs)
            lastRefreshTimestamp = System.currentTimeMillis()

            val topVol = selectedCandidates.firstOrNull()?.quoteVolumeUsdt ?: 0.0
            val floorVol = selectedCandidates.lastOrNull()?.quoteVolumeUsdt ?: 0.0
            val avgSpread = selectedCandidates.map { it.spreadPct }.average()

            AppLogManager.scanner(
                "Dynamic universe updated: %d futures qualified (Top Vol: $%.0f, Floor: $%.0f, Avg Spread: %.3f%%). Tier-1 Majors: %d."
                    .format(selectedPairs.size, topVol, floorVol, avgSpread, majorPairs.size)
            )
        } catch (e: Exception) {
            AppLogManager.e("SCANNER", "Exception during dynamic universe refresh: ${e.message}", e)
        }
    }
}
