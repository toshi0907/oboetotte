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
import com.toshi0907.oboetotte.data.VibrationPattern

/**
 * 位置情報リマインダー(到着・離脱)の通知チャンネル・通知そのものの組み立てを担う。
 * [GeofenceConfirmWorker](デバウンス確定後)から呼ばれる。
 */
object LocationReminderNotifier {
    const val CHANNEL_ID = "location_reminders"
    private const val CHANNEL_NAME = "位置リマインダー"

    /**
     * @param vibrationPattern タスクに設定されたバイブレーションパターン。非nullならバイブ無効の
     * 専用チャンネルに投稿し、[VibrationPatternPlayer]でパターンどおりに振動させる。
     * @return 実際に[NotificationManagerCompat.notify]を呼んだかどうか(権限が無い、通知が無効化されている、
     * 投稿先チャンネルがブロックされている場合はfalse)。
     */
    fun showNotification(
        context: Context,
        taskId: Long,
        title: String,
        url: String?,
        vibrationPattern: VibrationPattern? = null
    ): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "登録した場所への到着・離脱の通知"
            }
            notificationManager.createNotificationChannel(channel)
            if (vibrationPattern != null) {
                channelId = VibrationPatternPlayer.ensureCustomVibrationChannel(
                    notificationManager,
                    CHANNEL_ID,
                    CHANNEL_NAME
                )
            }
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

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("位置リマインダー")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                R.drawable.ic_notification,
                "完了",
                ReminderScheduler.locationCompletePendingIntent(context, taskId)
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
        // 通知自体が無効化されている、または投稿先チャンネルの重要度がIMPORTANCE_NONEの場合は
        // notify()しても表示されない。表示されないのにパターン振動だけ鳴る(通知履歴にも残る)
        // ことを避けるため、ReminderReceiverと同様にここで弾く。
        val notifier = NotificationManagerCompat.from(context)
        // パターン用の専用チャンネルに投稿する場合も、ユーザーが元のチャンネル(位置リマインダー)を
        // 無効化していれば、その設定を尊重して表示しない。
        val channelBlocked = listOf(CHANNEL_ID, channelId).distinct().any {
            notificationManager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
        }
        if (!notifier.areNotificationsEnabled() || channelBlocked) {
            return false
        }
        // 期限日時通知(ReminderReceiver)と同じ taskId.toInt() をIDに使うため、
        // 「完了」ボタン(CompleteReceiver)からはどちらの通知でも正しく消去できる。
        // タグは期限日時通知と分けており、両方のリマインダーが発火しても片方がもう片方を
        // 上書きせず別々の通知として表示される。
        notifier.notify(ReminderScheduler.NOTIFICATION_TAG_LOCATION, taskId.toInt(), notification)
        vibrationPattern?.let { VibrationPatternPlayer.play(context, it) }
        return true
    }
}
