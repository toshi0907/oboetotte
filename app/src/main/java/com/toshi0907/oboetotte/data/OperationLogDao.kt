package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationLogDao {
    @Insert
    suspend fun insert(log: OperationLog)

    @Query("SELECT * FROM operation_logs ORDER BY timestamp DESC, id DESC")
    fun getAll(): Flow<List<OperationLog>>

    @Query("DELETE FROM operation_logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM operation_logs")
    suspend fun deleteAll()

    /** 記録のたびに呼び、[cutoff]より古い履歴を自動削除する。 */
    @Transaction
    suspend fun insertAndPurge(log: OperationLog, cutoff: Long) {
        insert(log)
        deleteOlderThan(cutoff)
    }
}
