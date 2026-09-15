package com.toshi0907.oboetotte.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.R
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.GeofenceState
import com.toshi0907.oboetotte.data.GeofenceStateDao
import com.toshi0907.oboetotte.data.LocationUpdateLog
import com.toshi0907.oboetotte.data.LocationUpdateLogDao
import com.toshi0907.oboetotte.data.LocationUpdateType
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.metersBetween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 位置情報の確認方式が[LocationTrackingMode.CONTINUOUS_TRACKING]の場合にのみ動作するフォアグラウンド
 * サービス。Google Play servicesのGeofencing APIに内外判定を任せず、
 * [LocationTrackingSettings.getIntervalMinutes]の間隔で自前に位置を取得し続け、位置情報を設定した
 * 各タスクとの距離を[metersBetween]で計算して圏内/圏外の遷移を検知する。前回評価時点の状態は
 * [GeofenceStateDao]に永続化し(プロセス再起動を挟んでも基準を見失わないため)、遷移を検知したら
 * [GeofenceReceiver]と同じ経路([GeofenceConfirmWorker.schedule]による3分デバウンス)へ合流させる。
 * 開始・停止は[LocationReminderManager]がタスクの登録・解除に合わせて行う。
 */
class LocationTrackingService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14以降、FOREGROUND_SERVICE_TYPE_LOCATIONを指定したstartForegroundは
        // 位置情報の権限が無い状態で呼ぶとSecurityExceptionでクラッシュする。START_STICKYによる
        // プロセス再生成やrestart()はLocationReminderManager.registerの権限確認を経ずに
        // ここへ到達しうるため、startForegroundより前に必ず自前で確認する。
        if (!LocationReminderManager.hasLocationPermission(this)) {
            Log.w(TAG, "連続追跡: 位置情報の権限が無いため開始せず停止します")
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // LocationReminderManager.registerはタスクの編集(位置情報と無関係な項目の変更を含む)
        // のたびに呼ばれ、その都度start()経由でここへ到達しうる。位置情報リクエストをすでに
        // 開始済みなら購読を維持したままにし(そうしないと更新間隔のタイマーが毎回リセットされ、
        // 間隔が経過する前に位置が取得できなくなってしまう)、更新頻度の設定変更を反映するための
        // 再起動(LocationReminderManager.updateContinuousTrackingInterval経由のrestart())の
        // 場合のみ、EXTRA_FORCE_RESTARTを見て購読を一度止めてから最新の間隔で開始し直す。
        val forceRestart = intent?.getBooleanExtra(EXTRA_FORCE_RESTART, false) == true
        if (locationCallback == null || forceRestart) {
            stopLocationUpdates()
            startLocationUpdates()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        job.cancel()
        super.onDestroy()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "連続追跡: 位置情報の権限が無いため停止します")
            stopSelf()
            return
        }
        val intervalMillis = LocationTrackingSettings.getIntervalMinutes(this) * 60_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                scope.launch { handleLocation(location.latitude, location.longitude, location.accuracy) }
            }
        }
        locationCallback = callback
        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "連続追跡の位置情報取得で権限エラーが発生しました", e)
            stopSelf()
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private suspend fun handleLocation(latitude: Double, longitude: Double, accuracy: Float) {
        val db = AppDatabase.getInstance(applicationContext)
        val tasks = db.taskDao().getPendingWithLocation()
        if (tasks.isEmpty()) {
            // 位置情報タスクが無くなった場合は自身を停止する(LocationUpdateWorkerと同様の考え方)。
            stopSelf()
            return
        }
        val geofenceStateDao = db.geofenceStateDao()
        val locationUpdateLogDao = db.locationUpdateLogDao()
        tasks.forEach { task ->
            evaluateTask(task, latitude, longitude, accuracy, geofenceStateDao, locationUpdateLogDao)
        }
    }

    private suspend fun evaluateTask(
        task: Task,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        geofenceStateDao: GeofenceStateDao,
        locationUpdateLogDao: LocationUpdateLogDao
    ) {
        val lat = task.latitude ?: return
        val lng = task.longitude ?: return
        val radius = task.radiusMeters ?: return
        val distance = metersBetween(latitude, longitude, lat, lng)
        val isInside = distance <= radius
        val previous = geofenceStateDao.get(task.id)
        geofenceStateDao.upsert(GeofenceState(taskId = task.id, isInside = isInside))

        if (previous == null) {
            // 初回評価。Geofencing APIのINITIAL_TRIGGER_ENTERと同様、到着通知を希望していて
            // 既に圏内の場合のみ即座にデバウンスへ回す。それ以外は基準値を記録するだけに留める。
            if (isInside && task.notifyOnArrival) {
                scheduleTransition(task, isEnter = true, latitude, longitude, accuracy, distance, locationUpdateLogDao)
            }
            return
        }
        if (previous.isInside == isInside) return

        val isEnter = isInside
        val wants = if (isEnter) task.notifyOnArrival else task.notifyOnDeparture
        if (wants) {
            scheduleTransition(task, isEnter, latitude, longitude, accuracy, distance, locationUpdateLogDao)
        }
    }

    private suspend fun scheduleTransition(
        task: Task,
        isEnter: Boolean,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        distance: Float,
        locationUpdateLogDao: LocationUpdateLogDao
    ) {
        GeofenceConfirmWorker.schedule(applicationContext, task.id, isEnter)
        locationUpdateLogDao.insertAndTrim(
            LocationUpdateLog(
                timestamp = System.currentTimeMillis(),
                type = if (isEnter) LocationUpdateType.ENTER else LocationUpdateType.EXIT,
                taskTitle = task.title,
                latitude = latitude,
                longitude = longitude,
                accuracy = accuracy,
                detail = "受信(${GeofenceConfirmWorker.DEBOUNCE_DELAY_MINUTES}分後も継続していれば通知)〈連続追跡〉",
                distanceMeters = distance,
                radiusMeters = task.radiusMeters
            )
        )
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "位置情報の連続追跡",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "位置情報リマインダーを連続追跡方式で確認している間、表示され続けます"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("位置情報を連続で確認中")
            .setContentText("設定 → 位置情報 から確認方式を変更できます")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "LocationReminder"
        private const val CHANNEL_ID = "location_tracking_service"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_FORCE_RESTART = "force_restart"

        /** サービスが未起動なら起動し、既に起動済みなら位置情報の購読はそのまま維持する。 */
        fun start(context: Context) {
            startInternal(context, forceRestart = false)
        }

        /** 更新頻度の設定変更後に呼ぶ。既に起動済みでも位置情報の購読を最新の間隔で開始し直す。 */
        fun restart(context: Context) {
            startInternal(context, forceRestart = true)
        }

        private fun startInternal(context: Context, forceRestart: Boolean) {
            val intent = Intent(context, LocationTrackingService::class.java)
                .putExtra(EXTRA_FORCE_RESTART, forceRestart)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // Android 12以降、アプリがバックグラウンドにいる間はForegroundServiceStartNotAllowedException
                // (IllegalStateExceptionのサブクラス)でサービスの起動自体が拒否されることがある。
                // register/reconcileAll/updateContinuousTrackingIntervalはいずれも独立したコルーチンから
                // 呼ばれうるため、ここで捕捉せず伝播させるとプロセスをクラッシュさせかねない。
                Log.w(TAG, "連続追跡サービスの開始に失敗しました", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationTrackingService::class.java))
        }
    }
}
