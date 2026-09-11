package com.toshi0907.oboetotte

import android.content.Context
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.notification.LocationReminderManager
import com.toshi0907.oboetotte.notification.ReminderScheduler
import java.time.Instant
import java.time.ZoneId

/**
 * タスクを完了にする処理の実体。アプリ内(TaskViewModel)と通知の「完了」アクション
 * (notification/CompleteReceiver)の両方から共通で呼び出し、繰り返しタスクの
 * 次回分生成ロジックを一箇所にまとめている。
 */
object TaskCompletion {
    suspend fun complete(context: Context, task: Task) {
        if (task.isDone) return

        val taskDao = AppDatabase.getInstance(context).taskDao()
        taskDao.setDone(task.id, true)
        ReminderScheduler.cancel(context, task.id)
        LocationReminderManager.unregister(context, task.id)

        val rule = task.repeatRule
        val dueAt = task.dueAt
        val daysOfWeek = RepeatRule.parseDaysOfWeek(task.repeatDaysOfWeek)
        if (rule != null && dueAt != null && (rule != RepeatRule.WEEKLY_DAYS || daysOfWeek.isNotEmpty())) {
            val nextTask = task.copy(id = 0, isDone = false, dueAt = nextDueAt(dueAt, rule, daysOfWeek))
            val newId = taskDao.insert(nextTask)
            val inserted = nextTask.copy(id = newId)
            ReminderScheduler.schedule(context, inserted)
            LocationReminderManager.register(context, inserted)
        }
    }

    private fun nextDueAt(current: Long, rule: String, daysOfWeek: Set<Int>): Long {
        val zoned = Instant.ofEpochMilli(current).atZone(ZoneId.systemDefault())
        val next = when (rule) {
            RepeatRule.DAILY -> zoned.plusDays(1)
            RepeatRule.WEEKLY -> zoned.plusWeeks(1)
            RepeatRule.WEEKLY_DAYS -> (1..7)
                .map { zoned.plusDays(it.toLong()) }
                .first { it.dayOfWeek.value in daysOfWeek }
            RepeatRule.MONTHLY -> zoned.plusMonths(1)
            else -> zoned
        }
        return next.toInstant().toEpochMilli()
    }
}
