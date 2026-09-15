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
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.location.LocationManagerCompat
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // onLocationResultのたびに新しいコルーチンをlaunchして評価すると、位置情報の取得が短時間に
    // 連続した場合にコルーチンの開始順序がコールバックの到着順と入れ替わることがある
    // (scope.launch自体は即座に実行されるわけではないため)。Channelは送信順序を保持したまま
    // 単一のコンシューマーで順に取り出せるため、onLocationResult側は同期的なtrySend()で送る
    // だけにし、実際の評価(handleLocation)は下記のコンシューマーコルーチン(onCreateで起動)
    // 1つだけが順番に処理することで、到着順の処理を保証する。容量はCONFLATED(最新の1件のみ
    // 保持)にしており、評価(Room読み書き・ワーカー予約・ログ書き込みを含む)が設定間隔より
    // 時間がかかってコンシューマーが遅れても、未処理の位置情報が無制限に溜まり続けることはない。
    // 古い位置情報は破棄され、次に評価するのは常に最新の位置になる。
    private val locationChannel = Channel<Location>(Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // コンシューマーコルーチン1つだけがlocationChannelを消費する。onDestroyでchannelがcloseされ、
        // キュー済みの最後の要素(CONFLATEDのため高々1件)まで処理し終えるとfor文を抜けて正常終了する。
        // このJob自体をcompanionのconsumerJobへ保持しておき、stopAndAwaitCompletion()から直接
        // join()する(Jobの終了状態を保持しているJob.join()自体はコルーチンの正常終了・異常終了・
        // キャンセルのいずれでも、既に終了済みかどうかに関わらず即座または完了時に戻るため)。
        // 以前はCompletableDeferred+invokeOnCompletionで完了を通知する方式だったが、
        // stopAndAwaitCompletion()が呼ばれる前にhandleLocation内の例外でこのコルーチンが
        // 先に異常終了していた場合、invokeOnCompletionが発火した時点ではまだ誰も待っておらず、
        // 後から呼ばれたstopAndAwaitCompletion()が新しいCompletableDeferredを設定しても
        // (既に終了したJobからは二度とinvokeOnCompletionが呼ばれないため)永久に完了せず
        // completion.await()がハングする不具合があった。Job自体を直接join()する方式なら、
        // 呼び出しのタイミングに関わらず常に正しく完了を検知できる。
        consumerJob = scope.launch {
            for (location in locationChannel) {
                // handleLocation内(Room操作・GeofenceConfirmWorker.schedule等)が
                // CancellationException以外の例外を投げてこのループを抜けてしまうと、
                // SupervisorJobで親scopeは無事でもこのコンシューマー自身は終了し、以後
                // 受信する位置情報が一切評価されなくなる。1件の評価失敗でコンシューマー全体を
                // 道連れにしないよう、ここで捕捉してログに残し次の位置情報の処理を続ける。
                try {
                    handleLocation(location.latitude, location.longitude, location.accuracy)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "連続追跡の位置情報評価に失敗しました", e)
                }
            }
        }
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
        // 権限があっても端末側の位置情報サービス自体(設定アプリの「位置情報」トグル)が
        // オフの場合、Android 14以降はforegroundServiceType="location"のstartForegroundが
        // SecurityExceptionを投げる。権限確認だけでは検知できないため別途確認する。
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            Log.w(TAG, "連続追跡: 位置情報サービスが無効なため開始せず停止します")
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
        // stopLocationUpdatesで新規の位置情報受信を止め、locationChannel.close()でこれ以上
        // trySend()が積まれないようにする。ここでjobをcancelしてしまうと、コンシューマー
        // コルーチン(onCreateで起動)がまだキュー済みの最後の要素を評価している途中でも
        // 強制終了してしまい、stopAndAwaitCompletion()が「停止完了」を待つ意味が無くなる。
        // そのためjobのcancelは行わず、コンシューマーコルーチンが自然にfor文を抜けて完了する
        // のに任せる(stopAndAwaitCompletion()はそのJob自体をjoin()で待ち合わせる)。
        stopLocationUpdates()
        locationChannel.close()
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
                // trySendは非suspendで即座に列へ積むだけなので、この同期コールバック内から
                // 安全に呼べる(コールバック自体はメインスレッドで順番に呼ばれるため、
                // 送信順序=コールバックの到着順になる)。
                locationChannel.trySend(location)
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
        val stored = geofenceStateDao.get(task.id)
        // storedの位置・半径が現在のタスクの設定と一致する場合のみ、直前の圏内/圏外の基準値として使える。
        // LocationReminderManager.registerは位置・半径が変わった場合に基準値を削除するが、念のため
        // ここでも不一致なら初回評価扱いにする(万一削除されずに残っていた場合の保険)。
        val previous = stored?.takeIf { it.latitude == lat && it.longitude == lng && it.radiusMeters == radius }
        geofenceStateDao.upsert(
            GeofenceState(taskId = task.id, isInside = isInside, latitude = lat, longitude = lng, radiusMeters = radius)
        )

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

        // 現在動作中のサービスインスタンスのコンシューマーコルーチン(onCreateで起動、
        // locationChannelを消費してhandleLocationを順に呼ぶJob)への参照。stopAndAwaitCompletion()
        // がこのJob自体をjoin()で待ち合わせることで、「停止完了(位置情報の受信停止・キュー済みの
        // 評価まで含めたコンシューマーコルーチンの終了)」を検知する。呼び出しは
        // [LocationReminderManager]の[lifecycleMutex]で直列化されているため、同時に複数の
        // stopAndAwaitCompletion()呼び出しが競合することはない。
        @Volatile
        private var consumerJob: Job? = null

        /**
         * サービスの停止を要求し、停止完了(位置情報の受信停止・キュー済みの評価まで含めた
         * コンシューマーコルーチンの終了)まで中断して待つ「停止完了バリア」。
         * [LocationReminderManager.switchMode]が連続追跡方式から離れる際、この完了を待ってから
         * [com.toshi0907.oboetotte.data.GeofenceStateDao.deleteAll]を呼ぶことで、停止処理と
         * 競合して評価中・キュー済みだった書き込みが削除より後に発生し復活してしまう問題を防ぐ。
         * `Job.join()`は対象のJobが既に終了済み(正常・異常・キャンセルいずれの場合も)であれば
         * 即座に戻り、まだ実行中であれば終了まで中断して待つため、`stopAndAwaitCompletion()`が
         * 呼ばれるより前に`handleLocation`内の例外でコンシューマーコルーチンが先に異常終了して
         * いた場合でも取りこぼさず正しく完了を検知できる(以前は`CompletableDeferred`+
         * `invokeOnCompletion`方式だったが、`stopAndAwaitCompletion()`呼び出し前にJobが既に
         * 終了していると、後から設定した`CompletableDeferred`はJobから二度と`invokeOnCompletion`
         * が呼ばれず永久に完了しない不具合があった)。サービスが既に停止している場合は
         * [Context.stopService]がfalseを返すため即座に戻る。呼び出し元
         * ([LocationReminderManager.switchMode]、ひいては`TaskViewModel.viewModelScope`)が
         * 待機中にキャンセルされても、この停止要求と待ち合わせ自体は[NonCancellable]で
         * 保護しているため中断されない。ここで早期にキャンセルされてしまうと、コンシューマー
         * コルーチンがまだ稼働中のまま`lifecycleMutex`が解放され、後続のライフサイクル操作
         * (`register`等)と評価中の書き込みが重なって停止完了バリアの意味が失われるため。
         * `context.stopService()`の戻り値(`wasRunning`)は「サービスが元々動作していたか」しか
         * 示さず、そのタイミングでは判定しない。既に別経路(システムによる終了等)でサービスの
         * 停止処理が始まっていると`stopService()`が`false`を返す一方、`consumerJob`自体は
         * `handleLocation`の途中でまだ実行中のことがあり、ここで`join()`を省略すると
         * `evaluateTask()`が`GeofenceStateDao.upsert()`する前に呼び出し元(`switchMode`)が
         * `deleteAll()`してしまい、削除したはずの状態が復活する。そのため`wasRunning`の値に
         * 関わらず、保持している`consumerJob`が非nullなら必ず`join()`する。
         */
        suspend fun stopAndAwaitCompletion(context: Context): Unit = withContext(NonCancellable) {
            val job = consumerJob
            context.stopService(Intent(context, LocationTrackingService::class.java))
            job?.join()
        }

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
