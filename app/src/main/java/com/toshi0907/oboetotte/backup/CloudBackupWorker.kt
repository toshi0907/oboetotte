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
        return when {
            succeeded -> Result.success()
            // 保存先のプロバイダやネットワークの一時的な不調で失敗した場合に、次の定期実行
            // (24時間後)まで待たずにWorkManagerのバックオフ(CloudBackupSchedulerで指定)で
            // 時間を置いて再試行する。恒久的な失敗(権限の失効等)で無限に再試行し続けないよう、
            // 回数に上限を設ける。
            runAttemptCount < MAX_RETRY_COUNT -> Result.retry()
            else -> Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRY_COUNT = 3
    }
}
