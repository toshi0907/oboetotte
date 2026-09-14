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
 * バックグラウンドでの定期的な現在地取得(5分間隔、
 * [com.toshi0907.oboetotte.notification.LocationUpdateWorker])の両方を記録する。
 * [taskTitle]はENTER/EXITの場合のみ設定され、PERIODICの場合はnull。
 * [distanceMeters]・[radiusMeters]もENTER/EXITの場合のみ設定され(タスクに登録された座標から
 * イベント発生時の位置までの距離、およびそのタスクに設定されている半径)、
 * 例えば「距離8m / 半径15m」であれば実際には圏内のままGPS誤差でEXITが誤検知されたと直接判別できる。
 * タスクが見つからない・座標未登録の場合はいずれもnull。
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
    val detail: String?,
    val distanceMeters: Float? = null,
    val radiusMeters: Int? = null
)
