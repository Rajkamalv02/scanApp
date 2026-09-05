package com.coindcx.trading.data.api.models

import com.google.gson.annotations.SerializedName

/**
 * Verified CoinDCX Futures Position Schema
 * Sourced from live POST /exchange/v1/derivatives/futures/positions response.
 */
data class FuturesPosition(
    @SerializedName("id")
    val id: String,

    @SerializedName("pair")
    val pair: String,

    @SerializedName("active_pos")
    val activePos: Double,

    @SerializedName("inactive_pos_buy")
    val inactivePosBuy: Double,

    @SerializedName("inactive_pos_sell")
    val inactivePosSell: Double,

    @SerializedName("avg_price")
    val avgPrice: Double,

    @SerializedName("liquidation_price")
    val liquidationPrice: Double,

    @SerializedName("locked_margin")
    val lockedMargin: Double,

    @SerializedName("locked_user_margin")
    val lockedUserMargin: Double,

    @SerializedName("locked_order_margin")
    val lockedOrderMargin: Double,

    @SerializedName("take_profit_trigger")
    val takeProfitTrigger: Double?,

    @SerializedName("stop_loss_trigger")
    val stopLossTrigger: Double?,

    @SerializedName("leverage")
    val leverage: Double,

    @SerializedName("maintenance_margin")
    val maintenanceMargin: Double?,

    @SerializedName("mark_price")
    val markPrice: Double?,

    @SerializedName("margin_type")
    val marginType: String?,

    @SerializedName("settlement_currency_avg_price")
    val settlementCurrencyAvgPrice: Double?,

    @SerializedName("cumulative_funding_fee")
    val cumulativeFundingFee: Double?,

    @SerializedName("margin_currency_short_name")
    val marginCurrencyShortName: String?,

    @SerializedName("updated_at")
    val updatedAt: Long
) {
    val isOpen: Boolean get() = activePos != 0.0
    val isLong: Boolean get() = activePos > 0.0
    val isShort: Boolean get() = activePos < 0.0
}
