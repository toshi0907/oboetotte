package com.toshi0907.oboetotte.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.toshi0907.oboetotte.data.Task

object ReminderScheduler {
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_IS_TEST = "is_test"
    private const val TEST_REQUEST_CODE = -1
    const val TEST_DELAY_SECONDS = 5L

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    fun schedule(context: Context, task: Task) {
        val dueAt = task.dueAt
        if (dueAt == null || task.isDone || dueAt <= System.currentTimeMillis()) {
            cancel(context, task.id)
            return
        }
        if (!canScheduleExactAlarms(context)) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            dueAt,
            pendingIntentFor(context, task.id)
        )
    }

    fun cancel(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntentFor(context, taskId))
    }

    /** [TEST_DELAY_SECONDS]秒後にテスト通知を発火させる。本番と同じAlarmManager経由の経路を検証する。 */
    fun scheduleTestNotification(context: Context): Boolean {
        if (!canScheduleExactAlarms(context)) return false

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_IS_TEST, true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            TEST_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + TEST_DELAY_SECONDS * 1000,
            pendingIntent
        )
        return true
    }

    private fun pendingIntentFor(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
