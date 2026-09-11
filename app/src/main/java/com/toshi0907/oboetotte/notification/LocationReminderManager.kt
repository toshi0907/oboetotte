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
import com.toshi0907.oboetotte.data.Task

/**
 * 位置情報リマインダー(ジオフェンス)の登録・解除を担当する。時刻ベースの[ReminderScheduler]と
 * 同じ考え方で、TaskViewModel・TaskCompletion・BootReceiverの各操作から呼び出される。
 * ジオフェンスのrequestIdにはタスクIDの文字列表現をそのまま使う。
 */
object LocationReminderManager {

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

    @SuppressLint("MissingPermission")
    fun register(context: Context, task: Task) {
        val lat = task.latitude
        val lng = task.longitude
        val radius = task.radiusMeters
        val wantsTrigger = task.notifyOnArrival || task.notifyOnDeparture
        if (task.isDone || lat == null || lng == null || radius == null || !wantsTrigger) {
            unregister(context, task.id)
            return
        }
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "位置情報の権限が不足しているためタスク${task.id}のジオフェンス登録をスキップします")
            return
        }

        val transitionTypes = (if (task.notifyOnArrival) Geofence.GEOFENCE_TRANSITION_ENTER else 0) or
            (if (task.notifyOnDeparture) Geofence.GEOFENCE_TRANSITION_EXIT else 0)

        val geofence = Geofence.Builder()
            .setRequestId(task.id.toString())
            .setCircularRegion(lat, lng, radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitionTypes)
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
            // デフォルトの応答性(数分単位)だとエリア再突入の検知が遅れたり
            // 漏れたりしやすいため、短めに指定して繰り返しの発火を検知しやすくする。
            .setNotificationResponsiveness(NOTIFICATION_RESPONSIVENESS_MILLIS)
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

    fun unregister(context: Context, taskId: Long) {
        LocationServices.getGeofencingClient(context).removeGeofences(listOf(taskId.toString()))
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
