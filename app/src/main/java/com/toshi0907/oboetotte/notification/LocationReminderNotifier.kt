package com.toshi0907.oboetotte.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.R

/**
 * 位置情報リマインダー(到着・離脱)の通知チャンネル・通知そのものの組み立てを担う。
 * [GeofenceConfirmWorker](デバウンス確定後)から呼ばれる。
 */
object LocationReminderNotifier {
    const val CHANNEL_ID = "location_reminders"

    /** @return 実際に[NotificationManagerCompat.notify]を呼んだかどうか(権限が無ければfalse)。 */
    fun showNotification(context: Context, taskId: Long, title: String, url: String?): Boolean {
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("位置リマインダー")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                R.drawable.ic_notification,
                "完了",
                ReminderScheduler.completePendingIntent(context, taskId)
            )
        if (!url.isNullOrBlank()) {
            builder.addAction(
                R.drawable.ic_notification,
                "リンクを開く",
                ReminderScheduler.openUrlPendingIntent(context, taskId, url)
            )
        }
        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        // 期限日時通知(ReminderReceiver)と同じ taskId.toInt() をIDに使うため、
        // 「完了」ボタン(CompleteReceiver)からはどちらの通知でも正しく消去できる。
        // タグは期限日時通知と分けており、両方のリマインダーが発火しても片方がもう片方を
        // 上書きせず別々の通知として表示される。
        NotificationManagerCompat.from(context)
            .notify(ReminderScheduler.NOTIFICATION_TAG_LOCATION, taskId.toInt(), notification)
        return true
    }
}
