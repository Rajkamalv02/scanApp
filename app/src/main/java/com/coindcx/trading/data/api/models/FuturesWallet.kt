package com.coindcx.trading.data.api.models

import com.google.gson.annotations.SerializedName

/**
 * Verified CoinDCX Futures Wallet Schema
 * Sourced from live GET /exchange/v1/derivatives/futures/wallets response.
 */
data class FuturesWallet(
    @SerializedName("id")
    val id: String,

    @SerializedName("currency_short_name")
    val currencyShortName: String,

    @SerializedName("balance")
    val balance: String,

    @SerializedName("locked_balance")
    val lockedBalance: String,

    @SerializedName("cross_order_margin")
    val crossOrderMargin: String,

    @SerializedName("cross_user_margin")
    val crossUserMargin: String
) {
    /**
     * In CoinDCX Futures, `balance` is ALREADY the unencumbered available cash margin.
     * `locked_balance` is the margin currently locked in active positions or open orders.
     * Total wallet cash = balance + lockedBalance.
     */
    val availableBalance: Double
        get() = balance.toDoubleOrNull() ?: 0.0

    val lockedMargin: Double
        get() = lockedBalance.toDoubleOrNull() ?: 0.0

    val totalWalletBalance: Double
        get() = availableBalance + lockedMargin
}
