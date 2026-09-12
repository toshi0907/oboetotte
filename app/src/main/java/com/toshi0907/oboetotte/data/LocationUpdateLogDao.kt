package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationUpdateLogDao {
    @Insert
    suspend fun insert(log: LocationUpdateLog)

    @Query("SELECT * FROM location_update_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<LocationUpdateLog>>

    @Query("DELETE FROM location_update_logs WHERE timestamp < :cutoff")
    suspend fun trimOlderThan(cutoff: Long)

    /** 記録のたびに呼び、直近[retentionMillis]以内のみを残して古い履歴を自動削除する。 */
    @Transaction
    suspend fun insertAndTrim(log: LocationUpdateLog, retentionMillis: Long = RETENTION_MILLIS) {
        insert(log)
        trimOlderThan(System.currentTimeMillis() - retentionMillis)
    }

    companion object {
        private const val RETENTION_MILLIS = 6 * 60 * 60 * 1000L
    }
}
