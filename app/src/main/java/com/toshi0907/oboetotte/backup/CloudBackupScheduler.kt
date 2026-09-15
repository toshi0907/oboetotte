package com.toshi0907.oboetotte.backup

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * [CloudBackupWorker]による毎日1回のクラウド自動バックアップのスケジュール管理
 * (`update/AppUpdateCheckScheduler`と同様の構成)。保存先はSAFで選択したフォルダで、書き込みの
 * 実際のクラウド同期はそのフォルダを提供するアプリ(Dropbox等)自身が担うため、この定期実行
 * 自体はネットワーク接続を制約条件にしない。[TaskViewModel.setCloudBackupEnabled]が有効化時に
 * [ensureScheduled]を、無効化時に[cancel]を呼ぶ。
 */
object CloudBackupScheduler {
    private const val WORK_NAME = "cloud_backup_periodic"
    private const val INTERVAL_HOURS = 24L

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
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
