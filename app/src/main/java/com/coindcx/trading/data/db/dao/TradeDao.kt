package com.coindcx.trading.data.db.dao

import androidx.room.*
import com.coindcx.trading.data.db.entities.TradeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeDao {
    @Query("SELECT * FROM trades ORDER BY entryTime DESC")
    fun getAllTradesFlow(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM trades WHERE status = 'OPEN'")
    suspend fun getOpenTrades(): List<TradeEntity>

    @Query("SELECT * FROM trades WHERE status = 'OPEN'")
    fun getOpenTradesFlow(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM trades WHERE entryTime >= :startTimeMillis")
    suspend fun getTradesSince(startTimeMillis: Long): List<TradeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trade: TradeEntity): Long

    @Update
    suspend fun update(trade: TradeEntity)
}
