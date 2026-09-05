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
    val fees: Double = 0.0, // in INR
    val realizedPnl: Double? = null, // in INR
    val unrealizedPnl: Double? = null, // in INR
    val allocatedMarginInr: Double = 0.0,
    val exchangePositionId: String? = null,
    val clientOrderId: String,
    val status: String = "OPEN", // "OPEN", "CLOSED"
    val entryTime: Long = System.currentTimeMillis(),
    val exitTime: Long? = null,
    val strategyName: String,
    val exitReason: String? = null,

    // Institutional Paper Simulation Lifecycle Fields
    val signalPrice: Double = 0.0,
    val orderPrice: Double? = null,
    val currentPrice: Double? = null,
    val notionalValueInr: Double = 0.0,
    val estimatedLiquidationPrice: Double? = null,
    val grossPnl: Double? = null,
    val fundingFees: Double = 0.0,
    val slippageRate: Double = 0.0005,
    val roiPercent: Double? = null,
    val durationMillis: Long? = null,
    val timeframe: String = "15m",
    val tradeResult: String? = null, // "WIN", "LOSS", "BREAKEVEN"
    val sessionId: String = "session_default"
)
