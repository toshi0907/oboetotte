package com.toshi0907.oboetotte.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
        if (!hasLocationPermission(context)) return

        val transitionTypes = (if (task.notifyOnArrival) Geofence.GEOFENCE_TRANSITION_ENTER else 0) or
            (if (task.notifyOnDeparture) Geofence.GEOFENCE_TRANSITION_EXIT else 0)

        val geofence = Geofence.Builder()
            .setRequestId(task.id.toString())
            .setCircularRegion(lat, lng, radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitionTypes)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()

        try {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, geofencePendingIntent(context))
        } catch (e: SecurityException) {
            // 権限が無い場合は登録をスキップする
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
}
