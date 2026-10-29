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

    /** タスクの詳細画面用。[taskGroupId](対象タスクのattachmentGroupId)に紐づくログを新しい順に返す。 */
    @Query("SELECT * FROM operation_logs WHERE taskGroupId = :taskGroupId ORDER BY timestamp DESC, id DESC")
    fun getForTaskGroup(taskGroupId: Long): Flow<List<OperationLog>>

    /** インポートでタスクを入れ替えた際に、既存ログとタスクの紐づけを解除する。 */
    @Query("UPDATE operation_logs SET taskGroupId = NULL WHERE taskGroupId IS NOT NULL")
    suspend fun clearTaskGroupIds()

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
