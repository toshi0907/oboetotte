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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.R
import com.toshi0907.oboetotte.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_TEST, false)) {
            showNotification(context, TEST_NOTIFICATION_ID, "テスト通知です。これが届けば設定は正しく動作しています。", showTaskActions = false, url = null)
            return
        }

        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (taskId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = AppDatabase.getInstance(context).taskDao().getById(taskId)
                if (task != null && !task.isDone) {
                    showNotification(context, taskId, task.title, showTaskActions = true, url = task.url)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(
        context: Context,
        notificationId: Long,
        title: String,
        showTaskActions: Boolean,
        url: String?
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "リマインダー",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "タスクの期限リマインダー通知"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("リマインダー")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (showTaskActions) {
            builder.addAction(
                R.drawable.ic_notification,
                "完了",
                ReminderScheduler.completePendingIntent(context, notificationId)
            )
            builder.addAction(
                R.drawable.ic_notification,
                "スヌーズ",
                ReminderScheduler.snoozePickerPendingIntent(context, notificationId)
            )
        }
        if (!url.isNullOrBlank()) {
            builder.addAction(
                R.drawable.ic_notification,
                "リンクを開く",
                ReminderScheduler.openUrlPendingIntent(context, notificationId, url)
            )
        }

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context)
            .notify(ReminderScheduler.NOTIFICATION_TAG_DUE, notificationId.toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "task_reminders"
        private const val TEST_NOTIFICATION_ID = -1L
    }
}
