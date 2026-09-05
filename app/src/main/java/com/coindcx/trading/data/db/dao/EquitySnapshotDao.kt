package com.coindcx.trading.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coindcx.trading.data.db.entities.EquitySnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EquitySnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: EquitySnapshotEntity): Long

    @Query("SELECT * FROM equity_snapshots WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSnapshotsForSession(sessionId: String): List<EquitySnapshotEntity>

    @Query("SELECT * FROM equity_snapshots WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSnapshot(sessionId: String): EquitySnapshotEntity?

    @Query("SELECT * FROM equity_snapshots WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getSnapshotsFlow(sessionId: String): Flow<List<EquitySnapshotEntity>>

    @Query("DELETE FROM equity_snapshots WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}
