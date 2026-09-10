package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: Task)

    @Query("SELECT * FROM tasks ORDER BY id DESC")
    fun getAll(): Flow<List<Task>>

    @Query("UPDATE tasks SET isDone = :isDone WHERE id = :taskId")
    suspend fun setDone(taskId: Long, isDone: Boolean)

    @Query("UPDATE tasks SET title = :title WHERE id = :taskId")
    suspend fun updateTitle(taskId: Long, title: String)

    @Delete
    suspend fun delete(task: Task)
}
