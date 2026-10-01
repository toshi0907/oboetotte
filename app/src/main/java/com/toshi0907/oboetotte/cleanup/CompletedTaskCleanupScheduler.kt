package com.toshi0907.oboetotte.cleanup

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * [CompletedTaskCleanupWorker]による1日1回の定期実行のスケジュール管理
 * (`update/AppUpdateCheckScheduler`と同様の構成)。[com.toshi0907.oboetotte.MainActivity]が
 * 起動のたびに[ensureScheduled]を呼ぶが、既に動作中なら何もしない([ExistingPeriodicWorkPolicy.KEEP])。
 * 自動削除の有効/無効はWorker側([CompletedTaskCleanup.run])で毎回確認するため、無効化しても
 * 定期実行自体は止めない(削除日数の単位が日のため、1日1回の空振り実行は負荷として無視できる)。
 */
object CompletedTaskCleanupScheduler {
    private const val WORK_NAME = "completed_task_cleanup_periodic"
    private const val INTERVAL_HOURS = 24L

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<CompletedTaskCleanupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
