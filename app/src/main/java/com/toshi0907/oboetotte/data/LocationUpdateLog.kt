package com.toshi0907.oboetotte.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** [LocationUpdateLog.type]に入れる文字列定数。 */
object LocationUpdateType {
    const val ENTER = "ENTER"
    const val EXIT = "EXIT"
    const val PERIODIC = "PERIODIC"
}

/**
 * 位置情報デバッグ用に、ジオフェンスのENTER/EXITイベント受信(通知の表示可否に関わらず記録)と、
 * バックグラウンドでの定期的な現在地取得(15分間隔、
 * [com.toshi0907.oboetotte.notification.LocationUpdateWorker])の両方を記録する。
 * [taskTitle]はENTER/EXITの場合のみ設定され、PERIODICの場合はnull。
 */
@Entity(tableName = "location_update_logs")
data class LocationUpdateLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    val taskTitle: String?,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val detail: String?
)
