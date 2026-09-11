package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedLocationDao {
    @Insert
    suspend fun insert(location: SavedLocation): Long

    @Query("SELECT * FROM saved_locations ORDER BY name ASC")
    fun getAll(): Flow<List<SavedLocation>>

    @Update
    suspend fun update(location: SavedLocation)

    @Delete
    suspend fun delete(location: SavedLocation)

    @Insert
    suspend fun insertAll(locations: List<SavedLocation>)

    @Query("DELETE FROM saved_locations")
    suspend fun deleteAll()
}
