package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.MarketCandle

/**
 * Data Freshness Gate
 * Blocks stale market data from triggering execution logic.
 */
class DataFreshnessGate(
    private val maxAgeMillis: Long = 60_000L // Default 60 seconds freshness window
) {
    fun isFresh(timestamp: Long): Boolean {
        val age = System.currentTimeMillis() - timestamp
        return age in 0..maxAgeMillis
    }

    fun isCandleFresh(candle: MarketCandle): Boolean {
        return isFresh(candle.time)
    }
}
