package com.coindcx.trading.data.db.dao

import androidx.room.*
import com.coindcx.trading.data.db.entities.SystemLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SystemLogDao {
    @Query("SELECT * FROM system_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogsFlow(): Flow<List<SystemLogEntity>>

    @Insert
    suspend fun insert(log: SystemLogEntity)

    @Query("DELETE FROM system_logs WHERE timestamp < :beforeTimestamp")
    suspend fun pruneOldLogs(beforeTimestamp: Long)
}
