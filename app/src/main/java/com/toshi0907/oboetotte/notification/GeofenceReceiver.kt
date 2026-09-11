package com.toshi0907.oboetotte.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.R
import com.toshi0907.oboetotte.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ジオフェンス(到着・離脱)のイベントを受け取り、対象タスクが未完了であれば通知を表示する。
 * 期限日時の[ReminderReceiver]とは別の通知チャンネル("location_reminders")を使うため、
 * ユーザーはシステム設定でそれぞれ個別にオン/オフできる。
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "ジオフェンスイベントがエラーを含んでいます: errorCode=${event.errorCode}")
            return
        }

        val transition = event.geofenceTransition
        val transitionName = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            else -> "transition=$transition"
        }
        val requestIds = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        Log.d(TAG, "ジオフェンスイベントを受信: $transitionName, requestIds=$requestIds")

        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            transition != Geofence.GEOFENCE_TRANSITION_EXIT
        ) {
            return
        }
        val taskIds = event.triggeringGeofences?.mapNotNull { it.requestId.toLongOrNull() }
        if (taskIds.isNullOrEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val taskDao = AppDatabase.getInstance(context).taskDao()
                taskIds.forEach { taskId ->
                    val task = taskDao.getById(taskId)
                    if (task == null) {
                        Log.w(TAG, "タスク${taskId}が見つからないため通知をスキップします")
                        return@forEach
                    }
                    if (task.isDone) {
                        Log.d(TAG, "タスク${taskId}は完了済みのため通知をスキップします")
                        return@forEach
                    }
                    val matches = when (transition) {
                        Geofence.GEOFENCE_TRANSITION_ENTER -> task.notifyOnArrival
                        Geofence.GEOFENCE_TRANSITION_EXIT -> task.notifyOnDeparture
                        else -> false
                    }
                    if (!matches) {
                        Log.d(TAG, "タスク${taskId}は$transitionName の通知を希望していないためスキップします")
                        return@forEach
                    }
                    val suffix = if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                        "に近づきました"
                    } else {
                        "から離れました"
                    }
                    Log.d(TAG, "タスク${taskId}の通知を表示します")
                    showNotification(context, taskId, "${task.title}${suffix}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, taskId: Long, text: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "位置リマインダー",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "登録した場所への到着・離脱の通知"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("位置リマインダー")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                R.drawable.ic_notification,
                "完了",
                ReminderScheduler.completePendingIntent(context, taskId)
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // 期限日時通知(ReminderReceiver)と同じ taskId.toInt() をIDに使うため、
        // 「完了」ボタン(CompleteReceiver)からはどちらの通知でも正しく消去できる。
        NotificationManagerCompat.from(context).notify(taskId.toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "location_reminders"
        private const val TAG = "LocationReminder"
    }
}
