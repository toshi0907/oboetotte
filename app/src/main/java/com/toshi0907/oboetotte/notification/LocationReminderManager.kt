package com.toshi0907.oboetotte.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 位置情報リマインダー(ジオフェンス)の登録・解除を担当する。時刻ベースの[ReminderScheduler]と
 * 同じ考え方で、TaskViewModel・TaskCompletion・BootReceiverの各操作から呼び出される。
 * [LocationTrackingSettings.getMode]が[LocationTrackingMode.GEOFENCING_API]ならGoogle Play services
 * のGeofencing APIへ登録し(ジオフェンスのrequestIdにはタスクIDの文字列表現をそのまま使う)、
 * [LocationTrackingMode.CONTINUOUS_TRACKING]なら[LocationTrackingService]を起動して自前の
 * 連続追跡に切り替える。どちらの方式でも、遷移を検知した後の処理([GeofenceConfirmWorker]による
 * 3分デバウンス)は共通。
 *
 * [register]/[unregister]/[switchMode]/[reconcileAll]/[updateContinuousTrackingInterval]はいずれも
 * [lifecycleMutex]で直列化している。これらを別々の非同期コルーチンから並行に呼ぶと、例えば確認方式を
 * 連続追跡→Geofencing APIへ切り替えている最中に古い[updateContinuousTrackingInterval]の呼び出しが
 * 完了して連続追跡サービスを再起動してしまう、といった順序崩れが起こりうるため。
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

    @SuppressLint("MissingPermission")
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

        when (LocationTrackingSettings.getMode(context)) {
            LocationTrackingMode.GEOFENCING_API -> registerGeofencingApi(context, task, lat, lng, radius)
            LocationTrackingMode.CONTINUOUS_TRACKING -> {
                // 位置・半径を変更した既存タスクの再登録では、古い圏内/圏外の基準値が残ったままだと
                // 実際には移動していないのに新しい設定との比較で誤って遷移が検知されてしまうため、
                // 基準値をリセットする(Geofencing APIモードでの再登録がINITIAL_TRIGGER_ENTERとして
                // 扱われるのと同じ考え方)。一方、タイトルなど位置と無関係な項目だけの編集や、
                // 起動時・権限許可時の再確認([reconcileAll])では毎回呼ばれるため、記録済みの
                // ジオフェンス定義(位置・半径)がタスクの現在の設定と一致する場合は基準値をそのまま
                // 保持し、無駄な初回評価扱い(既に圏内なら到着通知の再スケジュール)を避ける。
                val geofenceStateDao = AppDatabase.getInstance(context).geofenceStateDao()
                val existing = geofenceStateDao.get(task.id)
                val definitionChanged = existing == null ||
                    existing.latitude != lat || existing.longitude != lng || existing.radiusMeters != radius
                if (definitionChanged) {
                    geofenceStateDao.delete(task.id)
                }
                LocationTrackingService.start(context)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofencingApi(context: Context, task: Task, lat: Double, lng: Double, radius: Int) {
        val transitionTypes = (if (task.notifyOnArrival) Geofence.GEOFENCE_TRANSITION_ENTER else 0) or
            (if (task.notifyOnDeparture) Geofence.GEOFENCE_TRANSITION_EXIT else 0)

        val geofence = Geofence.Builder()
            .setRequestId(task.id.toString())
            .setCircularRegion(lat, lng, radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitionTypes)
            // デフォルトの応答性(数分単位)だとエリア再突入の検知が遅れたり
            // 漏れたりしやすいため、短めに指定して繰り返しの発火を検知しやすくする。
            .setNotificationResponsiveness(NOTIFICATION_RESPONSIVENESS_MILLIS)
            .build()

        // 到着時通知が有効な場合、登録した時点で既に圏内にいればすぐに通知が届く
        // (INITIAL_TRIGGER_ENTER)。動作確認のしやすさを優先している。
        val initialTrigger = if (task.notifyOnArrival) {
            GeofencingRequest.INITIAL_TRIGGER_ENTER
        } else {
            0
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(initialTrigger)
            .addGeofence(geofence)
            .build()

        try {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, geofencePendingIntent(context))
                .addOnSuccessListener {
                    Log.d(TAG, "タスク${task.id}のジオフェンスを登録しました")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "タスク${task.id}のジオフェンス登録に失敗しました", e)
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "タスク${task.id}のジオフェンス登録で権限エラーが発生しました", e)
        }
    }

    suspend fun unregister(context: Context, taskId: Long) = lifecycleMutex.withLock {
        unregisterLocked(context, taskId)
    }

    private suspend fun unregisterLocked(context: Context, taskId: Long) {
        when (LocationTrackingSettings.getMode(context)) {
            LocationTrackingMode.GEOFENCING_API -> {
                LocationServices.getGeofencingClient(context).removeGeofences(listOf(taskId.toString()))
            }
            LocationTrackingMode.CONTINUOUS_TRACKING -> {
                AppDatabase.getInstance(context).geofenceStateDao().delete(taskId)
                if (AppDatabase.getInstance(context).taskDao().getPendingWithLocation().isEmpty()) {
                    LocationTrackingService.stop(context)
                }
            }
        }
    }

    /**
     * 確認方式([LocationTrackingMode])を切り替える際に呼ぶ。切り替え前の方式で使っていたリソース
     * (Geofencing APIへの登録、または連続追跡サービス・保持していた圏内/圏外の基準値)をすべて
     * 解除してから、新しい方式で位置情報を使う未完了タスクを[reconcileAll]で登録し直す。
     */
    suspend fun switchMode(context: Context, newMode: LocationTrackingMode): Unit = lifecycleMutex.withLock {
        val oldMode = LocationTrackingSettings.getMode(context)
        if (oldMode == newMode) return@withLock
        when (oldMode) {
            LocationTrackingMode.GEOFENCING_API -> {
                // 個々のrequestIdが分からなくても、共有しているPendingIntent単位で
                // 登録済みの全ジオフェンスをまとめて解除できる。
                LocationServices.getGeofencingClient(context)
                    .removeGeofences(geofencePendingIntent(context))
                    .addOnSuccessListener {
                        Log.d(TAG, "確認方式の切り替えに伴いジオフェンスを一括解除しました")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "確認方式の切り替えに伴うジオフェンス一括解除に失敗しました", e)
                    }
            }
            LocationTrackingMode.CONTINUOUS_TRACKING -> {
                LocationTrackingService.stop(context)
                AppDatabase.getInstance(context).geofenceStateDao().deleteAll()
            }
        }
        LocationTrackingSettings.setMode(context, newMode)
        reconcileAllLocked(context)
    }

    /**
     * 連続追跡方式の更新頻度を変更する。既にサービスが動作中であれば再起動し、
     * 新しい間隔での位置情報リクエストにすぐ切り替える。位置情報を使う未完了タスクが
     * 無い場合は([register]と同様に)サービスを起動しない。
     */
    suspend fun updateContinuousTrackingInterval(context: Context, minutes: Int): Unit = lifecycleMutex.withLock {
        LocationTrackingSettings.setIntervalMinutes(context, minutes)
        if (LocationTrackingSettings.getMode(context) != LocationTrackingMode.CONTINUOUS_TRACKING) return@withLock
        if (AppDatabase.getInstance(context).taskDao().getPendingWithLocation().isNotEmpty()) {
            LocationTrackingService.restart(context)
        }
    }

    /**
     * 位置情報の権限が新たに許可された時・アプリ起動時など、[register]が権限不足で
     * スキップされていたかもしれないタイミングで呼ぶ。位置情報を使う未完了タスクを
     * まとめて[register]し直すことで、ジオフェンス登録・[LocationUpdateScheduler]経由の
     * 定期取得ジョブの起動をやり直す。
     */
    suspend fun reconcileAll(context: Context) = lifecycleMutex.withLock {
        reconcileAllLocked(context)
    }

    private suspend fun reconcileAllLocked(context: Context) {
        AppDatabase.getInstance(context).taskDao().getPendingWithLocation()
            .forEach { task -> registerLocked(context, task) }
    }

    /** アプリ全体で1つのPendingIntentを共有する(発火時のGeofencingEventにどのジオフェンスかが含まれるため)。 */
    private fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private const val TAG = "LocationReminder"

    /** ジオフェンスの通知応答性(ミリ秒)。短くするほど再突入の検知が速くなる代わりに電池消費が増える。 */
    private const val NOTIFICATION_RESPONSIVENESS_MILLIS = 60_000
}
