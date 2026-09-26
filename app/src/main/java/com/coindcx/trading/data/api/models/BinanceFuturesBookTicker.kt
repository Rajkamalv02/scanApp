package com.coindcx.trading.data.api.models

import com.google.gson.annotations.SerializedName

/**
 * Best Bid/Ask price and quantity for a Crypto Futures contract.
 */
data class BinanceFuturesBookTicker(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("bidPrice") val bidPrice: String,
    @SerializedName("bidQty") val bidQty: String? = null,
    @SerializedName("askPrice") val askPrice: String,
    @SerializedName("askQty") val askQty: String? = null,
    @SerializedName("time") val time: Long = 0L
)
