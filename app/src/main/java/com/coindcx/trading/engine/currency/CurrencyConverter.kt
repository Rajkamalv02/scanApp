package com.coindcx.trading.engine.currency

import com.coindcx.trading.data.api.CoinDCXApiService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CurrencyConverter(
    private val apiService: CoinDCXApiService
) {
    private var cachedRate: Double = 90.0 // Default conservative baseline
    private var lastFetchTime: Long = 0
    private val cacheDurationMs = 60_000L // 60 seconds cache
    private val mutex = Mutex()
    fun getCachedUsdtInrRate(): Double = cachedRate

    suspend fun getUsdtInrRate(): Double {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < cacheDurationMs && cachedRate > 0) {
            return cachedRate
        }

        return mutex.withLock {
            if (System.currentTimeMillis() - lastFetchTime < cacheDurationMs && cachedRate > 0) {
                return@withLock cachedRate
            }

            try {
                val resp = apiService.getTicker()
                if (resp.isSuccessful && resp.body() != null) {
                    val usdtItem = resp.body()!!.find { it["market"] == "USDTINR" }
                    val priceStr = usdtItem?.get("last_price")?.toString()
                    val parsed = priceStr?.toDoubleOrNull()
                    if (parsed != null && parsed > 50.0) {
                        cachedRate = parsed
                        lastFetchTime = System.currentTimeMillis()
                        com.coindcx.trading.util.AppLogManager.i(
                            "CURRENCY",
                            "Dynamic USDT/INR rate updated: ₹%.2f (Settlement rate: ₹%.2f, Min notional floor: ₹%.2f)"
                                .format(parsed, parsed * 1.03, 6.0 * parsed * 1.03)
                        )
                    }
                }
            } catch (e: Exception) {
                com.coindcx.trading.util.AppLogManager.w(
                    "CURRENCY",
                    "Failed fetching USDT/INR ticker: ${e.message}. Fallback to cached rate: ₹%.2f".format(cachedRate)
                )
            }
            cachedRate
        }
    }

    /**
     * Estimates CoinDCX internal derivative settlement rate (spot USDTINR + ~3% settlement margin buffer)
     * Matches live "settlement_currency_conversion_price" returned by CoinDCX futures order engine.
     */
    suspend fun getSettlementConversionRate(): Double {
        val spotRate = getUsdtInrRate()
        return spotRate * 1.03
    }

    /**
     * Explicit startup pre-flight to seed live USDT/INR rate before first trade.
     */
    suspend fun refreshRatesOnStartup() {
        getUsdtInrRate()
    }

    /**
     * Dynamically updates cached rate directly from exchange order responses
     * to eliminate buffer drift.
     */
    fun updateSettlementRateFromExchange(exchangeConversionRate: Double) {
        if (exchangeConversionRate > 50.0) {
            cachedRate = exchangeConversionRate / 1.03
            lastFetchTime = System.currentTimeMillis()
            com.coindcx.trading.util.AppLogManager.i(
                "CURRENCY",
                "Updated settlement rate directly from exchange: ₹%.2f".format(exchangeConversionRate)
            )
        }
    }

    /**
     * Dynamically computes the minimum order notional in INR required by CoinDCX for futures orders.
     * CoinDCX derivative futures mandates minimum 6.0 USDT notional for B-*_USDT pairs.
     */
    suspend fun getDynamicMinNotionalInr(minNotionalUsdt: Double = 6.0): Double {
        val settlementRate = getSettlementConversionRate()
        return minNotionalUsdt * settlementRate
    }

    suspend fun convertInrMarginToContractQuantity(
        marginInr: Double,
        leverage: Int,
        currentPriceUsdt: Double
    ): Double {
        if (currentPriceUsdt <= 0.0) return 0.0
        val rate = getUsdtInrRate()
        val notionalUsdt = (marginInr * leverage) / rate
        return notionalUsdt / currentPriceUsdt
    }

    suspend fun convertUsdtToInr(usdtAmount: Double): Double {
        return usdtAmount * getUsdtInrRate()
    }

    suspend fun convertInrToUsdt(inrAmount: Double): Double {
        val rate = getUsdtInrRate()
        return if (rate > 0) inrAmount / rate else 0.0
    }
}
