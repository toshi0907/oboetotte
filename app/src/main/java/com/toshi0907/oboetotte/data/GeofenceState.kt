package com.toshi0907.oboetotte.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 連続追跡方式([com.toshi0907.oboetotte.notification.LocationTrackingMode.CONTINUOUS_TRACKING])で、
 * タスクごとに直前評価時点の圏内/圏外を保持するための状態。Geofencing APIを使う場合はPlay services
 * 側が内部で同等の状態を保持しているため、このテーブルは連続追跡方式選択時のみ使用する。
 */
@Entity(tableName = "geofence_states")
data class GeofenceState(
    @PrimaryKey val taskId: Long,
    val isInside: Boolean
)
