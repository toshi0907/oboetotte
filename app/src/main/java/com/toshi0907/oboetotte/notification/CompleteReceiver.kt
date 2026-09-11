package com.toshi0907.oboetotte.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.toshi0907.oboetotte.TaskCompletion
import com.toshi0907.oboetotte.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (taskId == -1L) return

        // タスク完了時は、期限日時・位置情報どちらの通知が表示中でも両方消去する。
        NotificationManagerCompat.from(context).apply {
            cancel(ReminderScheduler.NOTIFICATION_TAG_DUE, taskId.toInt())
            cancel(ReminderScheduler.NOTIFICATION_TAG_LOCATION, taskId.toInt())
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = AppDatabase.getInstance(context).taskDao().getById(taskId)
                if (task != null) {
                    TaskCompletion.complete(context, task)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
