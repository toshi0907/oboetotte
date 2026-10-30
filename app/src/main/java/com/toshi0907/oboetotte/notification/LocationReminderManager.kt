package com.toshi0907.oboetotte.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task as GmsTask
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 位置情報リマインダーの登録・解除を担当する。時刻ベースの[ReminderScheduler]と
 * 同じ考え方で、TaskViewModel・TaskCompletion・BootReceiverの各操作から呼び出される。
 * 位置情報を使う未完了タスクが1件以上あれば[LocationTrackingService]を起動して連続追跡で
 * 圏内/圏外の遷移を検知し、遷移を検知した後は[GeofenceConfirmWorker]による3分デバウンスを経て通知する。
 *
 * [register]/[unregister]/[reconcileAll]/[updateContinuousTrackingInterval]はいずれも
 * [lifecycleMutex]で直列化している。これらを別々の非同期コルーチンから並行に呼ぶと、例えば
 * 最後の位置情報タスクの解除でサービスを停止した直後に古い[updateContinuousTrackingInterval]の
 * 呼び出しが完了してサービスを再起動してしまう、といった順序崩れが起こりうるため。
 */
object LocationReminderManager {

    private val lifecycleMutex = Mutex()

    fun hasForegroundPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(context: Context): Boolean =
        hasForegroundPermission(context) && hasBackgroundPermission(context)

    suspend fun register(context: Context, task: Task) = lifecycleMutex.withLock {
        registerLocked(context, task)
    }

    private suspend fun registerLocked(context: Context, task: Task) {
        val lat = task.latitude
        val lng = task.longitude
        val radius = task.radiusMeters
        val wantsTrigger = task.notifyOnArrival || task.notifyOnDeparture
        if (task.isDone || lat == null || lng == null || radius == null || !wantsTrigger) {
            unregisterLocked(context, task.id)
            return
        }
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "位置情報の権限が不足しているためタスク${task.id}の位置情報リマインダー登録をスキップします")
            return
        }
        // 位置情報デバッグ用の定期的な現在地取得(LocationUpdateWorker)も、
        // 位置情報タスクが1件以上ある間だけ動作するようここで併せて起動しておく。
        LocationUpdateScheduler.ensureScheduled(context)

        // 位置・半径を変更した既存タスクの再登録では、古い圏内/圏外の基準値が残ったままだと
        // 実際には移動していないのに新しい設定との比較で誤って遷移が検知されてしまうため、
        // 基準値をリセットして次回評価を初回評価扱いにする。一方、タイトルなど位置と無関係な
        // 項目だけの編集や、起動時・権限許可時の再確認([reconcileAll])では毎回呼ばれるため、
        // 記録済みのジオフェンス定義(位置・半径)がタスクの現在の設定と一致する場合は基準値を
        // そのまま保持し、無駄な初回評価扱い(既に圏内なら到着通知の再スケジュール)を避ける。
        val geofenceStateDao = AppDatabase.getInstance(context).geofenceStateDao()
        val existing = geofenceStateDao.get(task.id)
        val definitionChanged = existing == null ||
            existing.latitude != lat || existing.longitude != lng || existing.radiusMeters != radius
        if (definitionChanged) {
            geofenceStateDao.delete(task.id)
        }
        LocationTrackingService.start(context)
    }

    suspend fun unregister(context: Context, taskId: Long) = lifecycleMutex.withLock {
        unregisterLocked(context, taskId)
    }

    private suspend fun unregisterLocked(context: Context, taskId: Long) {
        val db = AppDatabase.getInstance(context)
        db.geofenceStateDao().delete(taskId)
        if (db.taskDao().getPendingWithLocation().isEmpty()) {
            LocationTrackingService.stop(context)
        }
    }

    /**
     * 連続追跡方式の更新頻度を変更する。既にサービスが動作中であれば再起動し、
     * 新しい間隔での位置情報リクエストにすぐ切り替える。位置情報を使う未完了タスクが
     * 無い場合は([register]と同様に)サービスを起動しない。
     */
    suspend fun updateContinuousTrackingInterval(context: Context, minutes: Int): Unit = lifecycleMutex.withLock {
        LocationTrackingSettings.setIntervalMinutes(context, minutes)
        if (AppDatabase.getInstance(context).taskDao().getPendingWithLocation().isNotEmpty()) {
            LocationTrackingService.restart(context)
        }
    }

    /**
     * 位置情報の権限が新たに許可された時・アプリ起動時など、[register]が権限不足で
     * スキップされていたかもしれないタイミングで呼ぶ。位置情報を使う未完了タスクを
     * まとめて[register]し直すことで、連続追跡サービス・[LocationUpdateScheduler]経由の
     * 定期取得ジョブの起動をやり直す。
     */
    suspend fun reconcileAll(context: Context) = lifecycleMutex.withLock {
        reconcileAllLocked(context)
    }

    private suspend fun reconcileAllLocked(context: Context) {
        removeLegacyGeofencesLocked(context)
        AppDatabase.getInstance(context).taskDao().getPendingWithLocation()
            .forEach { task -> registerLocked(context, task) }
    }

    /**
     * 旧バージョンではGoogle Play servicesのGeofencing APIへもジオフェンスを登録できたため、
     * アップデート後に端末側へ残っている登録を一度だけ一括解除する(連続追跡方式のみに一本化したため)。
     * 旧バージョンは全ジオフェンスで同じPendingIntent(削除済みの`GeofenceReceiver`宛て)を共有して
     * いたので、それと同一視される[PendingIntent]を[PendingIntent.FLAG_NO_CREATE]で探し、
     * 見つかればその単位でまとめて解除する(見つからなければ端末側に登録は残っていない)。
     * 解除に失敗した場合は完了扱いにせず、次回の[reconcileAll]で再試行する。
     */
    private suspend fun removeLegacyGeofencesLocked(context: Context) {
        if (LocationTrackingSettings.isLegacyGeofencesRemoved(context)) return
        val intent = Intent().setClassName(context, LEGACY_GEOFENCE_RECEIVER_CLASS)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE
        )
        if (pendingIntent == null) {
            LocationTrackingSettings.markLegacyGeofencesRemoved(context)
            return
        }
        try {
            val removeTask = LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent)
            awaitCompletion(removeTask)
            if (removeTask.isSuccessful) {
                pendingIntent.cancel()
                LocationTrackingSettings.markLegacyGeofencesRemoved(context)
                Log.d(TAG, "旧バージョンで登録したジオフェンスを一括解除しました")
            } else {
                Log.e(TAG, "旧バージョンで登録したジオフェンスの一括解除に失敗しました", removeTask.exception)
            }
        } catch (e: Exception) {
            Log.e(TAG, "旧バージョンで登録したジオフェンスの一括解除に失敗しました", e)
        }
    }

    /**
     * Play servicesの[GmsTask]が完了(成功/失敗/キャンセルのいずれか)するまで、ブロッキングせずに中断する。
     * 呼び出し元のコルーチンが途中でキャンセルされても、既に発行済みの`removeGeofences`自体は
     * 取り消せないため、[NonCancellable]でラップしてこの待ち合わせ(=lifecycleMutexの保持)だけは
     * キャンセルの影響を受けないようにする。
     */
    private suspend fun awaitCompletion(gmsTask: GmsTask<*>): Unit = withContext(NonCancellable) {
        if (gmsTask.isComplete) return@withContext
        suspendCancellableCoroutine { cont ->
            gmsTask.addOnCompleteListener {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private const val TAG = "LocationReminder"

    private const val LEGACY_GEOFENCE_RECEIVER_CLASS =
        "com.toshi0907.oboetotte.notification.GeofenceReceiver"
}
