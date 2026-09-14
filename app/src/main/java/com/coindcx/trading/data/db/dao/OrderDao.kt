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

    @Query("SELECT * FROM orders WHERE status = 'PENDING' OR status = 'open' OR status = 'partially_filled' OR status = 'SUBMITTED'")
    suspend fun getActiveOrders(): List<OrderEntity>

    @Query("SELECT * FROM orders WHERE status = 'SUBMITTED' OR status = 'PENDING' ORDER BY createdAt DESC")
    fun getPendingOrdersFlow(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders ORDER BY createdAt DESC")
    fun getAllOrdersFlow(): Flow<List<OrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: OrderEntity)

    @Update
    suspend fun update(order: OrderEntity)

    @Query("SELECT COUNT(*) FROM orders WHERE status NOT IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'open', 'partially_filled')")
    suspend fun getFinalizedOrdersCount(): Int

    @Query("SELECT COUNT(*) FROM orders WHERE status IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'open', 'partially_filled')")
    suspend fun getActiveOrdersCount(): Int

    @Query("DELETE FROM orders WHERE clientOrderId = :clientOrderId AND status NOT IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'open', 'partially_filled')")
    suspend fun deleteFinalizedOrderByClientOrderId(clientOrderId: String): Int

    @Query("DELETE FROM orders WHERE clientOrderId IN (:clientOrderIds) AND status NOT IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'open', 'partially_filled')")
    suspend fun deleteFinalizedOrdersByClientOrderIds(clientOrderIds: List<String>): Int

    @Query("DELETE FROM orders WHERE status NOT IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'open', 'partially_filled')")
    suspend fun deleteAllFinalizedOrders(): Int

    @Query("DELETE FROM orders WHERE status NOT IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'open', 'partially_filled') AND createdAt < :beforeTimestamp")
    suspend fun deleteFinalizedOrdersOlderThan(beforeTimestamp: Long): Int
}
