package com.toshi0907.oboetotte.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * [AppUpdateCheckWorker]による定期的な更新チェックのスケジュール管理
 * (`notification/LocationUpdateScheduler`と同様の構成)。[MainActivity]が起動のたびに
 * [ensureScheduled]を呼ぶが、既に動作中なら何もしない([ExistingPeriodicWorkPolicy.KEEP])。
 * 位置情報の定期取得とは異なり常時有効な機能のため、動的に停止する仕組みは持たない。
 * ネットワーク接続が無いタイミングでは[NetworkType.CONNECTED]制約によりWorkManager側が
 * 接続復帰まで実行を保留する。
 */
object AppUpdateCheckScheduler {
    private const val WORK_NAME = "app_update_check_periodic"

    // 更新チェックは時間的な緊急性が無いため、位置情報の定期取得(5分間隔)ほど頻繁には行わず、
    // 通信・バッテリー消費を抑えて6時間間隔とする。
    private const val INTERVAL_HOURS = 6L

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
