package com.toshi0907.oboetotte

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.attachmentGroupId
import com.toshi0907.oboetotte.notification.LocationReminderManager
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.widget.refreshTaskWidget
import java.time.Instant
import java.time.ZoneId

/**
 * タスクを完了にする処理の実体。アプリ内(TaskViewModel)と通知の「完了」アクション
 * (notification/CompleteReceiver)の両方から共通で呼び出し、繰り返しタスクの
 * 次回分生成ロジックを一箇所にまとめている。
 */
object TaskCompletion {
    suspend fun complete(context: Context, task: Task) {
        // アプリ内のチェックボックスや「通知のみタスク」の自動完了など、通知の「完了」ボタン
        // (notification/CompleteReceiver、既にここへ来る前に自身の通知を消去済み)以外の経路で
        // 完了させた場合、そのタスクの期限日時・位置情報の通知が表示されたまま残ってしまう
        // (アラームのキャンセルとは別に、表示済みの通知自体は明示的にcancelしないと消えない)。
        // 完了処理の入口をここに集約しているため、ここで両タグとも消去しておけば経路によらず
        // 常に同期する。
        NotificationManagerCompat.from(context).apply {
            cancel(ReminderScheduler.NOTIFICATION_TAG_DUE, task.id.toInt())
            cancel(ReminderScheduler.NOTIFICATION_TAG_LOCATION, task.id.toInt())
        }

        val taskDao = AppDatabase.getInstance(context).taskDao()
        // taskDao.setDoneはisDone != trueの行にしか一致しないため、同じタスクに対して
        // ほぼ同時に呼ばれた複数回のcomplete呼び出し(例: 通知のみタスクの自動完了と
        // アプリ内の手動完了が競合した場合)のうち、実際に状態を遷移させた1回だけが
        // 0件超の更新件数を得る。他方はここで早期returnし、繰り返しタスクの次回分が
        // 重複生成されるのを防ぐ(呼び出し元から渡されたtaskスナップショットのisDoneは
        // 古い可能性があるため、DBへの書き込み結果そのものを正としてチェックする)。
        val updatedRows = taskDao.setDone(task.id, true)
        if (updatedRows == 0) return
        ReminderScheduler.cancel(context, task.id)
        LocationReminderManager.unregister(context, task.id)

        val rule = task.repeatRule
        val dueAt = task.dueAt
        val daysOfWeek = RepeatRule.parseDaysOfWeek(task.repeatDaysOfWeek)
        if (rule != null && dueAt != null && (rule != RepeatRule.WEEKLY_DAYS || daysOfWeek.isNotEmpty())) {
            // seriesIdを引き継ぐことで、次回分のタスクも同じattachmentGroupId()を持ち、
            // 添付ファイルが繰り返しシリーズ全体で共有され続ける(完了時に消えない)。
            val nextTask = task.copy(
                id = 0,
                isDone = false,
                dueAt = nextDueAt(dueAt, rule, daysOfWeek),
                seriesId = task.attachmentGroupId()
            )
            val newId = taskDao.insert(nextTask)
            val inserted = nextTask.copy(id = newId)
            ReminderScheduler.schedule(context, inserted)
            LocationReminderManager.register(context, inserted)
        }
        refreshTaskWidget(context)
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
