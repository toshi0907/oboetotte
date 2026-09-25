package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VibrationPatternDao {
    @Insert
    suspend fun insert(pattern: VibrationPattern): Long

    @Query("SELECT * FROM vibration_patterns ORDER BY name ASC")
    fun getAll(): Flow<List<VibrationPattern>>

    @Query("SELECT * FROM vibration_patterns WHERE id = :id")
    suspend fun getById(id: Long): VibrationPattern?

    @Update
    suspend fun update(pattern: VibrationPattern)

    @Delete
    suspend fun delete(pattern: VibrationPattern)

    @Insert
    suspend fun insertAll(patterns: List<VibrationPattern>)

    @Query("DELETE FROM vibration_patterns")
    suspend fun deleteAll()
}
