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
    suspend fun insert(task: Task): Long

    @Query("SELECT * FROM tasks ORDER BY isDone ASC, dueAt IS NULL ASC, dueAt ASC, id DESC")
    fun getAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getById(taskId: Long): Task?

    @Query("SELECT * FROM tasks WHERE dueAt IS NOT NULL AND isDone = 0")
    suspend fun getPendingWithDueDate(): List<Task>

    @Query(
        "SELECT * FROM tasks WHERE latitude IS NOT NULL AND longitude IS NOT NULL " +
            "AND radiusMeters IS NOT NULL AND isDone = 0"
    )
    suspend fun getPendingWithLocation(): List<Task>

    @Query("UPDATE tasks SET isDone = :isDone WHERE id = :taskId")
    suspend fun setDone(taskId: Long, isDone: Boolean)

    @Update
    suspend fun update(task: Task)

    @Query("UPDATE tasks SET listId = NULL WHERE listId = :listId")
    suspend fun clearListId(listId: Long)

    @Delete
    suspend fun delete(task: Task)

    @Insert
    suspend fun insertAll(tasks: List<Task>)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}
