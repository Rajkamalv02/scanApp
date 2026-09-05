package com.coindcx.trading.data.api.models

import com.google.gson.annotations.SerializedName

/**
 * Verified CoinDCX Candlestick Schema
 * Sourced from live GET /market_data/candles response.
 */
data class MarketCandle(
    @SerializedName("open")
    val open: Double,

    @SerializedName("high")
    val high: Double,

    @SerializedName("low")
    val low: Double,

    @SerializedName("close")
    val close: Double,

    @SerializedName("volume")
    val volume: Double,

    @SerializedName("time")
    val time: Long
)
