package com.toshi0907.oboetotte.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 位置情報デバッグ用の定期的な現在地取得([LocationUpdateWorker])のスケジュール管理。
 * WorkManagerの[androidx.work.PeriodicWorkRequest]は最短でも15分間隔までしか指定できないため、
 * より短い間隔([INTERVAL_MINUTES])で記録できるよう、[androidx.work.OneTimeWorkRequest]を
 * [LocationUpdateWorker]自身が実行のたびに[scheduleNext]で次回分を予約する形の自己連鎖にしている。
 * [LocationReminderManager.register]が位置情報タスクを登録するたびに[ensureScheduled]を
 * 呼ぶため、既に連鎖が動作中なら何もしない([ExistingWorkPolicy.KEEP])。位置情報を使う
 * 未完了タスクが無くなった場合は、[LocationUpdateWorker]が次回分を予約せずに終わることで
 * 連鎖が自然に停止する(最大[INTERVAL_MINUTES]分の遅延がある)。
 */
object LocationUpdateScheduler {
    private const val WORK_NAME = "location_update_periodic"
    private const val INTERVAL_MINUTES = 5L

    fun ensureScheduled(context: Context) {
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    /** [LocationUpdateWorker]自身が実行完了時に呼び、次回分を予約する。 */
    fun scheduleNext(context: Context) {
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<LocationUpdateWorker>()
            .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
