package com.coindcx.trading.data.db.dao

import androidx.room.*
import com.coindcx.trading.data.db.entities.OrderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders WHERE clientOrderId = :clientOrderId")
    suspend fun getByClientOrderId(clientOrderId: String): OrderEntity?

    @Query("SELECT * FROM orders WHERE status = 'UNKNOWN'")
    suspend fun getUnknownOrders(): List<OrderEntity>

    @Query("SELECT * FROM orders WHERE status = 'SUBMITTED' OR status = 'PENDING' ORDER BY createdAt DESC")
    fun getPendingOrdersFlow(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders ORDER BY createdAt DESC")
    fun getAllOrdersFlow(): Flow<List<OrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: OrderEntity)

    @Update
    suspend fun update(order: OrderEntity)
}
