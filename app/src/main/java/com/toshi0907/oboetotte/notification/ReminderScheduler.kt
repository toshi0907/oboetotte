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
    const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    private const val TEST_REQUEST_CODE = -1
    const val TEST_DELAY_SECONDS = 5L

    data class SnoozeOption(val label: String, val minutes: Long)

    val SNOOZE_OPTIONS = listOf(
        SnoozeOption("15分後", 15),
        SnoozeOption("30分後", 30),
        SnoozeOption("1時間後", 60),
        SnoozeOption("3時間後", 180),
        SnoozeOption("1日後", 24 * 60)
    )

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

    /** 通知のスヌーズボタン用。[optionIndex]は[SNOOZE_OPTIONS]内のインデックス(リクエストコードの重複回避用)。 */
    fun snoozePendingIntent(
        context: Context,
        taskId: Long,
        option: SnoozeOption,
        optionIndex: Int
    ): PendingIntent {
        val intent = Intent(context, SnoozeReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_SNOOZE_MINUTES, option.minutes)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt() * 10 + optionIndex,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
