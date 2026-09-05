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

    @Query("SELECT * FROM trades WHERE status = 'CLOSED' ORDER BY exitTime DESC")
    fun getClosedTradesFlow(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM trades WHERE sessionId = :sessionId AND status = 'CLOSED' ORDER BY exitTime DESC")
    fun getClosedTradesForSessionFlow(sessionId: String): Flow<List<TradeEntity>>

    @Query("SELECT * FROM trades WHERE sessionId = :sessionId")
    suspend fun getTradesForSession(sessionId: String): List<TradeEntity>

    @Query("SELECT * FROM trades WHERE sessionId = :sessionId AND status = 'CLOSED'")
    suspend fun getClosedTradesForSession(sessionId: String): List<TradeEntity>

    @Query("SELECT * FROM trades WHERE id = :id")
    suspend fun getTradeById(id: Long): TradeEntity?

    @Query("SELECT * FROM trades WHERE entryTime >= :startTimeMillis")
    suspend fun getTradesSince(startTimeMillis: Long): List<TradeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trade: TradeEntity): Long

    @Update
    suspend fun update(trade: TradeEntity)
}
