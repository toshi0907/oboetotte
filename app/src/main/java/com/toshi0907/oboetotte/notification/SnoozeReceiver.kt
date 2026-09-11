package com.toshi0907.oboetotte.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.toshi0907.oboetotte.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        val snoozeMinutes = intent.getLongExtra(ReminderScheduler.EXTRA_SNOOZE_MINUTES, -1L)
        if (taskId == -1L || snoozeMinutes <= 0) return

        // スヌーズボタンは期限日時通知にのみ付与されるため、そのタグの通知だけを消去する
        // (位置情報通知が同時に表示されていても消さない)。
        NotificationManagerCompat.from(context).cancel(ReminderScheduler.NOTIFICATION_TAG_DUE, taskId.toInt())

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = AppDatabase.getInstance(context).taskDao().getById(taskId)
                if (task != null && !task.isDone) {
                    ReminderScheduler.scheduleSnooze(context, taskId, snoozeMinutes)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
