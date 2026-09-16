package com.toshi0907.oboetotte.backup

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * [CloudBackupWorker]による毎日1回のクラウド自動バックアップのスケジュール管理
 * (`update/AppUpdateCheckScheduler`と同様の構成)。保存先はSAFで選択したフォルダで、書き込みの
 * 実際のクラウド同期はそのフォルダを提供するアプリ(Dropbox等)自身が担うため、この定期実行
 * 自体はネットワーク接続を制約条件にしない。[TaskViewModel.setCloudBackupEnabled]が有効化時に
 * [ensureScheduled]を、無効化時に[cancel]を呼ぶ。[CloudBackupSettings]に保存された時刻
 * ([CloudBackupSettings.getBackupHour]/[CloudBackupSettings.getBackupMinute])を初回実行までの
 * 遅延([PeriodicWorkRequestBuilder.setInitialDelay])として計算することで、毎日おおよそその時刻に
 * 実行されるようにする。ただしWorkManagerの2回目以降の実行は、Dozeモード等の端末側の都合で
 * 前回実行の完了時刻を起点に間隔を計るため、指定時刻から多少前後することがある
 * (`notification/LocationTrackingService`の更新間隔と同様、あくまで希望値)。
 */
object CloudBackupScheduler {
    private const val WORK_NAME = "cloud_backup_periodic"
    private const val INTERVAL_HOURS = 24L

    /** 既にスケジュール済みなら何もしない(次回実行時刻はそのまま)。アプリ起動時の保険的な呼び出し用。 */
    fun ensureScheduled(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildRequest(context)
        )
    }

    /**
     * 保存時刻の変更を即座に反映するため、次回実行までの遅延を再計算してスケジュールし直す。
     * [TaskViewModel.setCloudBackupTime]が呼び出し元で、自動バックアップが有効な場合のみ呼ぶ。
     */
    fun reschedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            buildRequest(context)
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun buildRequest(context: Context): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<CloudBackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMillis(context), TimeUnit.MILLISECONDS)
            .build()

    /** 現在時刻から、保存されている時刻(次に迎える方、今日または明日)までのミリ秒を計算する。 */
    private fun initialDelayMillis(context: Context): Long {
        val hour = CloudBackupSettings.getBackupHour(context)
        val minute = CloudBackupSettings.getBackupMinute(context)
        val now = LocalDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return Duration.between(now, target).toMillis()
    }
}
