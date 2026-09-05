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
                    }
                }
            } catch (_: Exception) {
                // Keep existing cachedRate on network fluctuation
            }
            cachedRate
        }
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
