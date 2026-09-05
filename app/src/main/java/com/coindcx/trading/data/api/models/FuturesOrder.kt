package com.coindcx.trading.data.api.models

import com.google.gson.annotations.SerializedName

/**
 * Verified CoinDCX Futures Order Schema
 * Used for listing open orders and tracking order lifecycles.
 */
data class FuturesOrder(
    @SerializedName("id")
    val id: String,

    @SerializedName("client_order_id")
    val clientOrderId: String?,

    @SerializedName("pair")
    val pair: String,

    @SerializedName("side")
    val side: String,

    @SerializedName("order_type")
    val orderType: String,

    @SerializedName("price")
    val price: Double?,

    @SerializedName("total_quantity")
    val totalQuantity: Double,

    @SerializedName("remaining_quantity")
    val remainingQuantity: Double?,

    @SerializedName("status")
    val status: String, // open, filled, partially_filled, cancelled, rejected

    @SerializedName("leverage")
    val leverage: Double?,

    @SerializedName("fee")
    val fee: Double?,

    @SerializedName("created_at")
    val createdAt: Long?,

    @SerializedName("updated_at")
    val updatedAt: Long?
) {
    val isPending: Boolean get() = status == "open" || status == "partially_filled"
    val isFilled: Boolean get() = status == "filled"
    val isClosed: Boolean get() = status == "cancelled" || status == "rejected" || status == "filled"
}
