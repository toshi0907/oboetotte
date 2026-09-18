package com.toshi0907.oboetotte.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AppUpdateCheckScheduler]から定期的に起動され、[AppUpdateChecker]でバックグラウンドの
 * 更新チェックを行う。新しいビルドが見つかり、かつ同じビルド番号へまだ通知していなければ
 * [AppUpdateNotifier]で通知する(起動時にアプリ内で行う自動チェックとは独立しており、
 * アプリを開いていない間もこのWorkerが定期的にチェックする)。
 */
class AppUpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = withContext(Dispatchers.IO) { AppUpdateChecker.check() }
        AppUpdateCheckSettings.recordCheckedAt(applicationContext, System.currentTimeMillis())
        if (result is AppUpdateChecker.Result.UpdateAvailable &&
            AppUpdateNotifier.shouldNotify(applicationContext, result.buildNumber)
        ) {
            AppUpdateNotifier.showNotification(applicationContext, result.buildNumber)
        }
        return Result.success()
    }
}
