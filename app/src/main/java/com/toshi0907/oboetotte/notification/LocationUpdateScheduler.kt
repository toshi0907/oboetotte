package com.toshi0907.oboetotte.notification

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 位置情報デバッグ用の定期的な現在地取得([LocationUpdateWorker])のスケジュール管理。
 * [LocationReminderManager.register]が位置情報タスクを登録するたびに[ensureScheduled]を
 * 呼ぶため、既に動作中なら何もしない([ExistingPeriodicWorkPolicy.KEEP])。位置情報を使う
 * 未完了タスクが無くなった場合は、次にこのWorkerが実行された時点で自身が[cancel]を呼んで
 * 定期実行を停止する(最大[INTERVAL_MINUTES]分の遅延がある)。
 */
object LocationUpdateScheduler {
    private const val WORK_NAME = "location_update_periodic"
    private const val INTERVAL_MINUTES = 15L

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<LocationUpdateWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
