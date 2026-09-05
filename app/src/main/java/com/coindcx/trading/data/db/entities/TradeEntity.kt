package com.coindcx.trading.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trades")
data class TradeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val pair: String,
    val side: String, // "LONG" or "SHORT"
    val entryPrice: Double,
    val exitPrice: Double? = null,
    val quantity: Double,
    val leverage: Int,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val fees: Double = 0.0,
    val realizedPnl: Double? = null,
    val unrealizedPnl: Double? = null,
    val exchangePositionId: String? = null,
    val clientOrderId: String,
    val status: String = "OPEN", // "OPEN", "CLOSED"
    val entryTime: Long = System.currentTimeMillis(),
    val exitTime: Long? = null,
    val strategyName: String,
    val exitReason: String? = null
)
