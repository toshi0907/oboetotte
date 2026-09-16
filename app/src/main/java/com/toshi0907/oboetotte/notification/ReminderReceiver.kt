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
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.R
import com.toshi0907.oboetotte.ai.GeminiClient
import com.toshi0907.oboetotte.ai.GeminiSettings
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.NotificationLog
import com.toshi0907.oboetotte.data.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_TEST, false)) {
            showNotification(context, TEST_NOTIFICATION_ID, "テスト通知です。これが届けば設定は正しく動作しています。", showTaskActions = false, url = null, aiOutcome = null)
            return
        }

        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (taskId == -1L) return
        val isSnooze = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_SNOOZE, false)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val task = db.taskDao().getById(taskId)
                if (task != null && !task.isDone) {
                    val aiOutcome = resolveAiOutcome(context, db, task, isSnooze)
                    val posted = showNotification(context, taskId, task.title, showTaskActions = true, url = task.url, aiOutcome = aiOutcome)
                    if (posted) {
                        val aiSuffix = when (aiOutcome) {
                            null -> ""
                            is AiOutcome.Success -> "(AI要約: 成功)"
                            AiOutcome.Failure -> "(AI要約: 失敗)"
                        }
                        val condition = (if (isSnooze) "スヌーズ経由の再通知" else "期限到達") + aiSuffix
                        db.notificationLogDao().insertAndTrim(
                            NotificationLog(
                                triggeredAt = System.currentTimeMillis(),
                                taskTitle = task.title,
                                triggerCondition = condition
                            )
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private sealed interface AiOutcome {
        data class Success(val text: String) : AiOutcome
        data object Failure : AiOutcome
    }

    /**
     * [task.aiPrompt]が設定され、かつAPIキーが設定されている場合のみGeminiへ問い合わせる。
     * 新規にAPIを呼び出すのは通常の期限到達時のみで、結果を[Task.aiCachedResponse]へキャッシュする。
     * スヌーズ経由の再通知([isSnooze])は常にこのキャッシュを再利用し(初回呼び出しが失敗して
     * キャッシュが無い場合を含め)、APIを再度呼び出さない(通知のたびにAPIを呼び直さないため)。
     * タイムアウト・エラー時は[AiOutcome.Failure]を返し、呼び出し元は通常の通知にフォールバックする
     * ([GeminiClient.NOTIFICATION_TIMEOUT_MILLIS]はBroadcastReceiverのgoAsyncの制限時間内に
     * 収まるよう短めに設定している)。
     */
    private suspend fun resolveAiOutcome(context: Context, db: AppDatabase, task: Task, isSnooze: Boolean): AiOutcome? {
        val prompt = task.aiPrompt?.takeIf { it.isNotBlank() } ?: return null
        val apiKey = GeminiSettings.getApiKey(context) ?: return null

        if (isSnooze) {
            return task.aiCachedResponse?.takeIf { it.isNotBlank() }?.let { AiOutcome.Success(it) }
        }

        val model = GeminiSettings.getModel(context)
        return when (
            val result = GeminiClient.generateContent(apiKey, model.apiName, prompt, GeminiClient.NOTIFICATION_TIMEOUT_MILLIS)
        ) {
            is GeminiClient.Result.Success -> {
                // キャッシュの保存に失敗しても、今回取得できたAI要約自体は破棄せず通知に使う
                // (キャッシュ書き込みの失敗によって通知そのものが表示されなくなることを避ける)。
                try {
                    db.taskDao().updateAiCachedResponse(task.id, result.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "AI応答のキャッシュ保存に失敗しました", e)
                }
                AiOutcome.Success(result.text)
            }
            is GeminiClient.Result.Failure -> AiOutcome.Failure
        }
    }

    /** @return 実際に[NotificationManagerCompat.notify]を呼んだかどうか(権限が無ければfalse)。 */
    private fun showNotification(
        context: Context,
        notificationId: Long,
        title: String,
        showTaskActions: Boolean,
        url: String?,
        aiOutcome: AiOutcome?
    ): Boolean {
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
        if (aiOutcome != null) {
            val summary = when (aiOutcome) {
                is AiOutcome.Success -> aiOutcome.text
                AiOutcome.Failure -> "取得に失敗しました"
            }
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText("$title\n\nAI要約: $summary")
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
        NotificationManagerCompat.from(context)
            .notify(ReminderScheduler.NOTIFICATION_TAG_DUE, notificationId.toInt(), notification)
        return true
    }

    companion object {
        const val CHANNEL_ID = "task_reminders"
        private const val TEST_NOTIFICATION_ID = -1L
        private const val TAG = "ReminderReceiver"
    }
}
