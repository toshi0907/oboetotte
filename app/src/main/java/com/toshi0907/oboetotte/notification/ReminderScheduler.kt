package com.toshi0907.oboetotte.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    /**
     * スヌーズ用。タスクの`dueAt`は書き換えず、通知だけを「タップ時刻+[minutes]分後」に
     * 再スケジュールする。アラーム本体と同じ[pendingIntentFor]を使うため、次に
     * [schedule]/[cancel]が呼ばれれば通常どおり上書き・キャンセルされる。
     */
    fun scheduleSnooze(context: Context, taskId: Long, minutes: Long): Boolean {
        if (!canScheduleExactAlarms(context)) return false

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + minutes * 60_000,
            pendingIntentFor(context, taskId)
        )
        return true
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

    /** 通知の「完了」ボタン用。[CompleteReceiver]宛で、[SnoozeReceiver]とは別コンポーネントのため衝突しない。 */
    fun completePendingIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, CompleteReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知の「リンクを開く」ボタン用。タスクの[url]をブラウザ等で開くACTION_VIEWの[PendingIntent]。
     * リクエストコードは[snoozePendingIntent]([SNOOZE_OPTIONS]は最大5件、インデックス0〜4)と
     * 衝突しないよう`taskId.toInt() * 10 + 9`を使う。
     */
    fun openUrlPendingIntent(context: Context, taskId: Long, url: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            taskId.toInt() * 10 + OPEN_URL_REQUEST_CODE_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val OPEN_URL_REQUEST_CODE_OFFSET = 9
}
