package com.coindcx.trading.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "system_logs")
data class SystemLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val level: String, // INFO, WARN, ERROR, RISK
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
