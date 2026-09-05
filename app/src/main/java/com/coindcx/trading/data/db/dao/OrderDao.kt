package com.coindcx.trading.data.db.dao

import androidx.room.*
import com.coindcx.trading.data.db.entities.OrderEntity

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders WHERE clientOrderId = :clientOrderId")
    suspend fun getByClientOrderId(clientOrderId: String): OrderEntity?

    @Query("SELECT * FROM orders WHERE status = 'UNKNOWN'")
    suspend fun getUnknownOrders(): List<OrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: OrderEntity)

    @Update
    suspend fun update(order: OrderEntity)
}
