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
import com.toshi0907.oboetotte.TaskCompletion
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.NotificationLog
import com.toshi0907.oboetotte.data.VibrationPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_TEST, false)) {
            showNotification(context, TEST_NOTIFICATION_ID, "テスト通知です。これが届けば設定は正しく動作しています。", showTaskActions = false, url = null, dueAt = null, vibrationPattern = null)
            return
        }

        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (taskId == -1L) return
        val isSnooze = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_SNOOZE, false)
        val isAutoSnooze = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_AUTO_SNOOZE, false)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val task = db.taskDao().getById(taskId)
                if (task != null && !task.isDone) {
                    // パターンが削除済み等で見つからない場合は、通常のチャンネル(標準バイブレーション)で通知する。
                    val vibrationPattern = task.vibrationPatternId?.let { db.vibrationPatternDao().getById(it) }
                    val posted = showNotification(
                        context,
                        taskId,
                        task.title,
                        showTaskActions = !task.notifyOnlyMode,
                        url = task.url,
                        dueAt = task.dueAt,
                        vibrationPattern = vibrationPattern
                    )
                    if (posted) {
                        val notifyOnlySuffix = if (task.notifyOnlyMode) "(通知のみ・自動完了)" else ""
                        val condition = when {
                            isAutoSnooze -> "オートスヌーズ経由の再通知"
                            isSnooze -> "スヌーズ経由の再通知"
                            else -> "期限到達"
                        } + notifyOnlySuffix
                        db.notificationLogDao().insertAndTrim(
                            NotificationLog(
                                triggeredAt = System.currentTimeMillis(),
                                taskTitle = task.title,
                                triggerCondition = condition
                            )
                        )
                        if (task.notifyOnlyMode) {
                            // 通知が実際に表示できた時点でのみ完了扱いにする(権限が無く表示できなかった
                            // 場合は完了させず、通知されないままタスクが消えてしまうことを避ける)。
                            // 繰り返しタスクの次回分生成・アラーム再スケジュールもTaskCompletion.completeに含まれる。
                            TaskCompletion.complete(context, task)
                        } else {
                            // オートスヌーズが設定されたタスクは、未完了のまま指定間隔が経過するたびに
                            // 再通知を繰り返す(上限なし)。手動スヌーズ・アプリ側の編集等でこのアラームの
                            // 枠(taskIdをrequestCodeとするPendingIntent)が上書きされれば、その時点を
                            // 基準に次回分が計算し直される。タスク完了時はReminderScheduler.cancelで
                            // このアラームごと止まる。
                            val autoSnoozeMinutes = task.autoSnoozeMinutes
                            if (autoSnoozeMinutes != null && autoSnoozeMinutes > 0) {
                                ReminderScheduler.scheduleAutoSnooze(context, taskId, autoSnoozeMinutes)
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * @return 実際に[NotificationManagerCompat.notify]を呼んだかどうか。権限が無い、通知が
     * 無効化されている、このチャンネルの重要度が`IMPORTANCE_NONE`のいずれかであれば`false`。
     */
    private fun showNotification(
        context: Context,
        notificationId: Long,
        title: String,
        showTaskActions: Boolean,
        url: String?,
        dueAt: Long?,
        vibrationPattern: VibrationPattern?
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
                description = "タスクの期限リマインダー通知"
            }
            notificationManager.createNotificationChannel(channel)
            if (vibrationPattern != null) {
                // チャンネル標準のバイブレーションとパターンが重ならないよう、バイブ無効の専用チャンネルに投稿する。
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
            notificationId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("リマインダー")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (showTaskActions && dueAt != null) {
            // dueAtはスケジュール済みのアラームが発火した場合は常に非nullのはず
            // (ReminderScheduler.scheduleはdueAtがnullなら通知自体をスケジュールしない)だが、
            // 万一nullであれば「完了」ボタンの期限突き合わせができないため、安全側に倒して
            // このボタン自体を出さない(スヌーズボタンは引き続き表示する)。
            builder.addAction(
                R.drawable.ic_notification,
                "完了",
                ReminderScheduler.completePendingIntent(context, notificationId, dueAt)
            )
        }
        if (showTaskActions) {
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
            return false
        }
        // POST_NOTIFICATIONS権限があっても、通知自体が無効化されている、またはこのチャンネルの
        // 重要度がIMPORTANCE_NONEの場合はnotify()を呼んでも実際には表示されない
        // (update/AppUpdateNotifier.showNotificationと同じ確認方法)。通知のみタスクは
        // この戻り値を見て実際に表示できた場合のみ自動完了するため、ここで確実に弾く。
        val notifier = NotificationManagerCompat.from(context)
        // パターン用の専用チャンネルに投稿する場合も、ユーザーが元のチャンネル(リマインダー)を
        // 無効化していれば、その設定を尊重して表示しない。
        val channelBlocked = listOf(CHANNEL_ID, channelId).distinct().any {
            notificationManager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
        }
        if (!notifier.areNotificationsEnabled() || channelBlocked) {
            return false
        }
        notifier.notify(ReminderScheduler.NOTIFICATION_TAG_DUE, notificationId.toInt(), notification)
        vibrationPattern?.let { VibrationPatternPlayer.play(context, it) }
        return true
    }

    companion object {
        const val CHANNEL_ID = "task_reminders"
        private const val CHANNEL_NAME = "リマインダー"
        private const val TEST_NOTIFICATION_ID = -1L
    }
}
