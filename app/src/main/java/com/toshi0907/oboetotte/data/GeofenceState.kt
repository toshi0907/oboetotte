package com.toshi0907.oboetotte.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 連続追跡方式([com.toshi0907.oboetotte.notification.LocationTrackingMode.CONTINUOUS_TRACKING])で、
 * タスクごとに直前評価時点の圏内/圏外を保持するための状態。Geofencing APIを使う場合はPlay services
 * 側が内部で同等の状態を保持しているため、このテーブルは連続追跡方式選択時のみ使用する。
 * [latitude]/[longitude]/[radiusMeters]は、この[isInside]がどのジオフェンス定義に基づく評価結果かを
 * 記録するためのもの。タスクの編集で位置・半径が変わった場合と、タイトルなど無関係な項目が変わった
 * 場合を区別するために使う(詳細は[com.toshi0907.oboetotte.notification.LocationReminderManager.register]参照)。
 */
@Entity(tableName = "geofence_states")
data class GeofenceState(
    @PrimaryKey val taskId: Long,
    val isInside: Boolean,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int
)
