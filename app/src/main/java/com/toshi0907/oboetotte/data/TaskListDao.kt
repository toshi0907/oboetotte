package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskListDao {
    @Insert
    suspend fun insert(taskList: TaskList): Long

    @Query("SELECT * FROM task_lists ORDER BY id ASC")
    fun getAll(): Flow<List<TaskList>>

    @Update
    suspend fun update(taskList: TaskList)

    @Delete
    suspend fun delete(taskList: TaskList)

    @Insert
    suspend fun insertAll(taskLists: List<TaskList>)

    @Query("DELETE FROM task_lists")
    suspend fun deleteAll()
}
