package com.toshi0907.oboetotte.notification

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.LocationUpdateLog
import com.toshi0907.oboetotte.data.LocationUpdateType
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 位置情報デバッグ用に、位置情報を使う未完了タスクがある間だけ[LocationUpdateScheduler]から
 * 15分間隔で起動され、現在地を1回取得して[LocationUpdateLog]に記録する。タスクが1件も無い
 * 場合は取得を行わず、[LocationUpdateScheduler.cancel]で自身の定期実行を停止する。
 */
class LocationUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        if (db.taskDao().getPendingWithLocation().isEmpty()) {
            LocationUpdateScheduler.cancel(applicationContext)
            return Result.success()
        }
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 権限不足でスキップしたことも記録しておかないと、デバッグ画面から見たときに
            // 「Workerが動いていない」のか「動いたが権限が無かった」のか区別できない。
            db.locationUpdateLogDao().insertAndTrim(
                LocationUpdateLog(
                    timestamp = System.currentTimeMillis(),
                    type = LocationUpdateType.PERIODIC,
                    taskTitle = null,
                    latitude = null,
                    longitude = null,
                    accuracy = null,
                    detail = "権限不足のためスキップ"
                )
            )
            return Result.success()
        }

        val location = fetchCurrentLocation()
        db.locationUpdateLogDao().insertAndTrim(
            LocationUpdateLog(
                timestamp = System.currentTimeMillis(),
                type = LocationUpdateType.PERIODIC,
                taskTitle = null,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracy = location?.accuracy,
                detail = if (location == null) "取得失敗" else null
            )
        )
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchCurrentLocation(): Location? {
        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        val cancellationTokenSource = CancellationTokenSource()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancellationTokenSource.cancel() }
            try {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationTokenSource.token)
                    .addOnSuccessListener { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
