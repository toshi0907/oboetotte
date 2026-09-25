package com.toshi0907.oboetotte.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * [CloudBackupWorker]による毎日1回のクラウド自動バックアップのスケジュール管理
 * (`update/AppUpdateCheckScheduler`と同様の構成)。保存先はSAFで選択したフォルダで、書き込みの
 * 実際のクラウド同期はそのフォルダを提供するアプリ(Dropbox等)自身が担うが、pCloudのように
 * 書き込み時にネットワークを必要とするプロバイダもあるため、ネットワーク接続を実行条件にする。
 * 失敗時は[CloudBackupWorker]がバックオフ付きで数回まで再試行する。[TaskViewModel.setCloudBackupEnabled]が有効化時に
 * [reschedule]を、無効化時に[cancel]を呼ぶ。[CloudBackupSettings]の保存時刻設定
 * (`getBackupHour`/`getBackupMinute`)から計算した初回実行までの遅延を[PeriodicWorkRequest]の
 * 初回遅延として指定することで、毎日おおむね指定した時刻に実行されるようにする(WorkManagerの
 * 定期実行は正確なアラームではないため、実際の発火時刻が多少前後することはある)。
 */
object CloudBackupScheduler {
    private const val WORK_NAME = "cloud_backup_periodic"
    private const val INTERVAL_HOURS = 24L

    // CloudBackupWorkerが失敗時にResult.retry()を返した際の再試行までの初回待機時間(指数的に延びる)。
    private const val RETRY_BACKOFF_MINUTES = 10L

    // pCloud等、書き込み時に直接クラウドへアップロードするタイプのプロバイダはネットワークが
    // 無いと書き込みに失敗する。深夜の定時実行はDoze中でネットワークが使えない状態のまま
    // 起動されうるため、ネットワーク接続を実行条件にする(Dropbox等オフラインでも書き込める
    // プロバイダにとっては実行が接続回復まで遅れるだけで害は無い)。
    private fun buildRequest(context: Context): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<CloudBackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMillis(context), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

    // buildRequestの実行条件・再試行方針を変更した際に上げる。KEEPで登録済みの既存の定期実行は
    // そのままだと古い設定のまま動き続けるため、ensureScheduledがこの版の違いを検知して
    // 一度だけREPLACEで登録し直す(2: ネットワーク接続条件と失敗時の再試行を追加)。
    private const val SCHEDULE_VERSION = 2

    /**
     * 既存の定期実行が無い場合にのみ、現在の保存時刻設定を初回遅延として登録する。
     * アプリ起動のたびに呼ばれる保険的な呼び出し(`MainActivity`)を想定しており、
     * 既に登録済みであれば何もしない。ただし既存の定期実行が古い版の設定で登録されている
     * 場合は、新しい設定を反映するため[reschedule]で登録し直す。
     */
    fun ensureScheduled(context: Context) {
        if (CloudBackupSettings.getScheduleVersion(context) < SCHEDULE_VERSION) {
            reschedule(context)
            return
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildRequest(context)
        )
    }

    /**
     * 現在の保存時刻設定に基づいて初回遅延を再計算し、既存の定期実行を置き換える。
     * 自動バックアップの有効化時、および有効化中に保存時刻設定を変更した際に呼ぶ。
     */
    fun reschedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            buildRequest(context)
        )
        CloudBackupSettings.setScheduleVersion(context, SCHEDULE_VERSION)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** 現在時刻から見て、次に訪れる保存時刻(時:分)までのミリ秒数。既に過ぎていれば翌日分。 */
    private fun initialDelayMillis(context: Context): Long {
        val hour = CloudBackupSettings.getBackupHour(context)
        val minute = CloudBackupSettings.getBackupMinute(context)
        val now = ZonedDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return Duration.between(now, target).toMillis()
    }
}
