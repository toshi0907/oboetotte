package com.toshi0907.oboetotte.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface GeofenceStateDao {
    @Query("SELECT * FROM geofence_states WHERE taskId = :taskId LIMIT 1")
    suspend fun get(taskId: Long): GeofenceState?

    @Upsert
    suspend fun upsert(state: GeofenceState)

    @Query("DELETE FROM geofence_states WHERE taskId = :taskId")
    suspend fun delete(taskId: Long)

    @Query("DELETE FROM geofence_states")
    suspend fun deleteAll()
}
