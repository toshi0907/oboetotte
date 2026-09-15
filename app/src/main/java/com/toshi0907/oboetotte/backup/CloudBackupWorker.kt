package com.toshi0907.oboetotte.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * [CloudBackupScheduler]から毎日1回起動され、[CloudBackupRunner]でクラウド自動バックアップを
 * 実行する。設定が無効化されている場合は何もしない(スケジュール自体は[CloudBackupScheduler.cancel]
 * で止めるが、無効化直後にキューに残っていた実行が来てしまうケースの保険)。
 */
class CloudBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!CloudBackupSettings.isEnabled(applicationContext)) return Result.success()
        val succeeded = CloudBackupRunner.run(applicationContext)
        return if (succeeded) Result.success() else Result.failure()
    }
}
