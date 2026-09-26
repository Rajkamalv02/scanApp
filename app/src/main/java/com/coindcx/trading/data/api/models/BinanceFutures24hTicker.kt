package com.coindcx.trading.data.api.models

import com.google.gson.annotations.SerializedName

/**
 * 24-hour rolling window ticker statistics for a Crypto Futures contract from Binance USDT-M Futures.
 * All CoinDCX futures contracts (prefixed with B-) mirror Binance Futures instruments.
 */
data class BinanceFutures24hTicker(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("lastPrice") val lastPrice: String,
    @SerializedName("priceChangePercent") val priceChangePercent: String,
    @SerializedName("highPrice") val highPrice: String,
    @SerializedName("lowPrice") val lowPrice: String,
    @SerializedName("volume") val volume: String,
    @SerializedName("quoteVolume") val quoteVolume: String,
    @SerializedName("openTime") val openTime: Long = 0L,
    @SerializedName("closeTime") val closeTime: Long = 0L
)
