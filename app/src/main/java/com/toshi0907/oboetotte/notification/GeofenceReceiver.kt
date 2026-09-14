package com.toshi0907.oboetotte.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.LocationUpdateLog
import com.toshi0907.oboetotte.data.LocationUpdateType
import com.toshi0907.oboetotte.data.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ジオフェンス(到着・離脱)のイベントを受け取る。GPS/ネットワーク測位の精度が低いタイミングでは
 * 実際には動いていなくてもジオフェンスの内外判定が一瞬だけ反転することがあるため、ここでは
 * 即座に通知せず[GeofenceConfirmWorker.schedule]でデバウンス(既定[GeofenceConfirmWorker.DEBOUNCE_DELAY_MINUTES]分、
 * その間に別のイベントが来れば置き換わり実行されない)し、実際の通知表示・[com.toshi0907.oboetotte.data.NotificationLog]
 * への記録は同Workerが確定時に行う。期限日時の[ReminderReceiver]とは別の通知チャンネル
 * ("location_reminders")を使うため、ユーザーはシステム設定でそれぞれ個別にオン/オフできる。
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "ジオフェンスイベントがエラーを含んでいます: errorCode=${event.errorCode}")
            return
        }

        val transition = event.geofenceTransition
        val transitionName = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            else -> "transition=$transition"
        }
        val requestIds = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        Log.d(TAG, "ジオフェンスイベントを受信: $transitionName, requestIds=$requestIds")

        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            transition != Geofence.GEOFENCE_TRANSITION_EXIT
        ) {
            return
        }
        val taskIds = event.triggeringGeofences?.mapNotNull { it.requestId.toLongOrNull() }
        if (taskIds.isNullOrEmpty()) return
        val triggeringLocation = event.triggeringLocation
        val isEnter = transition == Geofence.GEOFENCE_TRANSITION_ENTER
        val updateType = if (isEnter) LocationUpdateType.ENTER else LocationUpdateType.EXIT

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val taskDao = db.taskDao()
                taskIds.forEach { taskId ->
                    val task = taskDao.getById(taskId)
                    val detail: String
                    if (task == null) {
                        Log.w(TAG, "タスク${taskId}が見つからないため通知をスキップします")
                        detail = "タスクが見つかりません"
                    } else if (task.isDone) {
                        Log.d(TAG, "タスク${taskId}は完了済みのため通知をスキップします")
                        detail = "スキップ(完了済み)"
                    } else {
                        val matches = if (isEnter) task.notifyOnArrival else task.notifyOnDeparture
                        if (!matches) {
                            Log.d(TAG, "タスク${taskId}は$transitionName の通知を希望していないためスキップします")
                            detail = "スキップ(通知タイミング未選択)"
                        } else {
                            Log.d(
                                TAG,
                                "タスク${taskId}: ${GeofenceConfirmWorker.DEBOUNCE_DELAY_MINUTES}分後も状態が変わらなければ通知します"
                            )
                            GeofenceConfirmWorker.schedule(context, taskId, isEnter)
                            detail = "受信(${GeofenceConfirmWorker.DEBOUNCE_DELAY_MINUTES}分後も継続していれば通知)"
                        }
                    }
                    // ジオフェンスの受信自体は、通知の表示可否に関わらず位置情報デバッグ用に記録する。
                    // これにより「イベント自体が来ていないのか」「来ているが条件で弾かれているのか」を
                    // アプリ内(位置情報デバッグ画面)から切り分けられる。実際に通知したかどうかは
                    // デバウンス確定後にGeofenceConfirmWorkerが通知履歴(NotificationLog)へ記録する。
                    // distanceMeters/radiusMetersも併せて記録することで、例えば「距離8m/半径15m」の
                    // EXITのように、実際には圏内のままGPS誤差で誤検知されたケースを後から直接判別できる。
                    db.locationUpdateLogDao().insertAndTrim(
                        LocationUpdateLog(
                            timestamp = System.currentTimeMillis(),
                            type = updateType,
                            taskTitle = task?.title,
                            latitude = triggeringLocation?.latitude,
                            longitude = triggeringLocation?.longitude,
                            accuracy = triggeringLocation?.accuracy,
                            detail = detail,
                            distanceMeters = distanceToTask(triggeringLocation, task),
                            radiusMeters = task?.radiusMeters
                        )
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** [triggeringLocation]から[task]に登録された座標までの距離(メートル)。いずれかが無ければnull。 */
    private fun distanceToTask(triggeringLocation: Location?, task: Task?): Float? {
        val lat = task?.latitude
        val lng = task?.longitude
        if (triggeringLocation == null || lat == null || lng == null) return null
        val results = FloatArray(1)
        Location.distanceBetween(triggeringLocation.latitude, triggeringLocation.longitude, lat, lng, results)
        return results[0]
    }

    companion object {
        private const val TAG = "LocationReminder"
    }
}
