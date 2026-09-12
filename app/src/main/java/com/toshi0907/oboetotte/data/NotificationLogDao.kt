package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationLogDao {
    @Insert
    suspend fun insert(log: NotificationLog)

    @Query("SELECT * FROM notification_logs ORDER BY triggeredAt DESC")
    fun getAll(): Flow<List<NotificationLog>>

    @Query(
        "DELETE FROM notification_logs WHERE id NOT IN " +
            "(SELECT id FROM notification_logs ORDER BY triggeredAt DESC LIMIT :limit)"
    )
    suspend fun trimToLimit(limit: Int)

    /** 記録のたびに呼び、直近[limit]件のみを残して古い履歴を自動削除する。 */
    @Transaction
    suspend fun insertAndTrim(log: NotificationLog, limit: Int = 200) {
        insert(log)
        trimToLimit(limit)
    }
}
