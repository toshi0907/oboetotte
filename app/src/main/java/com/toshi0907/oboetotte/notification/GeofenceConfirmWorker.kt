package com.toshi0907.oboetotte.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.NotificationLog
import java.util.concurrent.TimeUnit

/**
 * ジオフェンスのENTER/EXITイベントを受けてすぐに通知するのではなく、[DEBOUNCE_DELAY_MINUTES]分
 * 経っても状態が変わらなければ(反対方向のイベントで上書きされなければ)初めて通知するデバウンス
 * 処理の実体。GPS/ネットワーク測位の精度が低いタイミングでは、実際には動いていなくても
 * ジオフェンスの内外判定がわずかな誤差で反転してしまうことがあり、これによる一瞬だけの
 * ENTER⇔EXITの反転で毎回通知が飛ぶのを防ぐ。
 *
 * [GeofenceReceiver]はイベントを受け取るたびに[schedule]を呼び、[WorkManager.enqueueUniqueWork]を
 * [ExistingWorkPolicy.REPLACE]で実行する。そのため同じタスクに対してデバウンス時間内に別の
 * イベント(同方向・反対方向を問わず)が届くと、以前の[GeofenceConfirmWorker]の実行はキャンセルされ
 * 新しいイベントのタイマーに置き換わる。つまり実際に[doWork]が動くのは「最後に受信したイベントから
 * [DEBOUNCE_DELAY_MINUTES]分間、後続のイベントが来なかった」場合のみで、その時点のタスクの状態
 * (完了済みでないか・通知タイミングを希望しているか)を再確認してから通知するかどうかを最終判定する。
 */
class GeofenceConfirmWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId < 0) return Result.success()
        val isEnter = inputData.getBoolean(KEY_IS_ENTER, false)

        val db = AppDatabase.getInstance(applicationContext)
        val task = db.taskDao().getById(taskId) ?: return Result.success()
        if (task.isDone) return Result.success()

        val matches = if (isEnter) task.notifyOnArrival else task.notifyOnDeparture
        if (!matches) return Result.success()

        val posted = LocationReminderNotifier.showNotification(applicationContext, taskId, task.title, task.url)
        if (posted) {
            val transitionLabel = if (isEnter) "到着" else "離脱"
            val locationLabel = task.locationName?.let { "$it・" } ?: ""
            db.notificationLogDao().insertAndTrim(
                NotificationLog(
                    triggeredAt = System.currentTimeMillis(),
                    taskTitle = task.title,
                    triggerCondition = "位置情報$transitionLabel(${locationLabel}半径${task.radiusMeters}m)"
                )
            )
        }
        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_IS_ENTER = "is_enter"

        /** この時間だけ後続のジオフェンスイベントが来なければ通知を確定する。 */
        const val DEBOUNCE_DELAY_MINUTES = 3L
        private const val WORK_NAME_PREFIX = "geofence_confirm_"

        fun schedule(context: Context, taskId: Long, isEnter: Boolean) {
            val data = Data.Builder()
                .putLong(KEY_TASK_ID, taskId)
                .putBoolean(KEY_IS_ENTER, isEnter)
                .build()
            val request = OneTimeWorkRequestBuilder<GeofenceConfirmWorker>()
                .setInitialDelay(DEBOUNCE_DELAY_MINUTES, TimeUnit.MINUTES)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME_PREFIX$taskId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
