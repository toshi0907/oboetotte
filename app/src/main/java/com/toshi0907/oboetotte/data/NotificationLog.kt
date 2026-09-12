package com.toshi0907.oboetotte.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * デバッグ用に、実際に発火した通知の履歴を記録する。[triggerCondition]は通知種別ごとに
 * 内容が異なる(期限到達/スヌーズ/位置情報の到着・離脱)ため、単一の文字列として保持する。
 */
@Entity(tableName = "notification_logs")
data class NotificationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggeredAt: Long,
    val taskTitle: String,
    val triggerCondition: String
)
