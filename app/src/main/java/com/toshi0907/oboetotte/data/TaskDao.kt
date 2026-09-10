package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: Task)

    @Query("SELECT * FROM tasks ORDER BY isDone ASC, dueAt IS NULL ASC, dueAt ASC, id DESC")
    fun getAll(): Flow<List<Task>>

    @Query("UPDATE tasks SET isDone = :isDone WHERE id = :taskId")
    suspend fun setDone(taskId: Long, isDone: Boolean)

    @Update
    suspend fun update(task: Task)

    @Query("UPDATE tasks SET listId = NULL WHERE listId = :listId")
    suspend fun clearListId(listId: Long)

    @Delete
    suspend fun delete(task: Task)
}
