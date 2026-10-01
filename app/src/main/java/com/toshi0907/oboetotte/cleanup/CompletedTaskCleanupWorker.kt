package com.toshi0907.oboetotte.cleanup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * [CompletedTaskCleanupScheduler]から1日1回起動され、[CompletedTaskCleanup]で完了から設定日数を
 * 経過した完了済みタスクを削除する(アプリを開いていない間も削除が進むようにするため)。
 */
class CompletedTaskCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        CompletedTaskCleanup.run(applicationContext)
        return Result.success()
    }
}
