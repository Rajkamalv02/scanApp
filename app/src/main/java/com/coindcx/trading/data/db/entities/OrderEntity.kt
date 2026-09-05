package com.coindcx.trading.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey
    val clientOrderId: String,

    val exchangeOrderId: String? = null,
    val pair: String,
    val side: String,
    val orderType: String,
    val price: Double?,
    val totalQuantity: Double,
    val filledQuantity: Double = 0.0,
    val status: String, // PENDING, SUBMITTED, FILLED, CANCELLED, REJECTED
    val rejectionReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
