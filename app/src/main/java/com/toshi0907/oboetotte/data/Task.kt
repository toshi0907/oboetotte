package com.toshi0907.oboetotte.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val isDone: Boolean = false,
    val dueAt: Long? = null,
    val listId: Long? = null,
    val parentTaskId: Long? = null,
    val repeatRule: String? = null,
    val repeatDaysOfWeek: String? = null,
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int? = null,
    val notifyOnArrival: Boolean = false,
    val notifyOnDeparture: Boolean = false
)
