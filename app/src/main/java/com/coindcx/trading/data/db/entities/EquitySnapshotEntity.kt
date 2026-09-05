package com.coindcx.trading.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "equity_snapshots")
data class EquitySnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val equityInr: Double,
    val availableBalanceInr: Double,
    val unrealizedPnlInr: Double
)
