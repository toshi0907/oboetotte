package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskAttachmentDao {
    @Insert
    suspend fun insert(attachment: TaskAttachment): Long

    @Insert
    suspend fun insertAll(attachments: List<TaskAttachment>)

    @Query("SELECT * FROM task_attachments ORDER BY createdAt ASC")
    fun getAll(): Flow<List<TaskAttachment>>

    @Query("SELECT * FROM task_attachments WHERE taskId = :taskId ORDER BY createdAt ASC")
    suspend fun getForTask(taskId: Long): List<TaskAttachment>

    @Delete
    suspend fun delete(attachment: TaskAttachment)

    @Query("DELETE FROM task_attachments WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)

    @Query("DELETE FROM task_attachments")
    suspend fun deleteAll()
}
