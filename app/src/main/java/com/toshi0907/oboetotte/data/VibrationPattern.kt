package com.toshi0907.oboetotte.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 通知時のバイブレーションパターン。「オン[onMs]ms振動→オフ[offMs]ms停止」を[durationMs]msの間
 * 繰り返す(最後の周期は[durationMs]で打ち切る)。タスクの[Task.vibrationPatternId]から参照する。
 */
@Entity(tableName = "vibration_patterns")
data class VibrationPattern(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val onMs: Long,
    val offMs: Long,
    val durationMs: Long
)
