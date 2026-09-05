package com.coindcx.trading.data.api.models

import com.google.gson.annotations.SerializedName

/**
 * Verified CoinDCX Futures Order Creation Request
 * Tested live against POST /exchange/v1/derivatives/futures/orders/create.
 */
data class CreateOrderRequest(
    @SerializedName("timestamp")
    val timestamp: Long,

    @SerializedName("order")
    val order: OrderPayload
)

data class OrderPayload(
    @SerializedName("side")
    val side: String, // "buy" or "sell"

    @SerializedName("pair")
    val pair: String, // e.g. "B-BTC_USDT"

    @SerializedName("order_type")
    val orderType: String, // "limit_order" or "market_order"

    @SerializedName("price")
    val price: Double?, // Required for limit orders

    @SerializedName("total_quantity")
    val totalQuantity: Double,

    @SerializedName("leverage")
    val leverage: Int,

    @SerializedName("notification")
    val notification: String = "no_notification",

    @SerializedName("time_in_force")
    val timeInForce: String? = "good_till_cancel",

    @SerializedName("hidden")
    val hidden: Boolean = false,

    @SerializedName("post_only")
    val postOnly: Boolean = false,

    @SerializedName("client_order_id")
    val clientOrderId: String
)
