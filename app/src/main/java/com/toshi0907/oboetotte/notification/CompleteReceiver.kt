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
        val hasNotifiedDueAt = intent.hasExtra(ReminderScheduler.EXTRA_DUE_AT)
        val notifiedDueAt = intent.getLongExtra(ReminderScheduler.EXTRA_DUE_AT, -1L)

        // タップされた通知自体は、下記の期限不一致でタスクを完了させない場合でも既に古くなって
        // いるのでここで消去してよいが、消すのは「今回タップされた側」のタグ(期限日時通知経由なら
        // DUE、位置情報通知経由ならLOCATION)だけにする。両方消してしまうと、期限不一致で完了させ
        // ない場合に、まだ有効なもう片方の通知(例: 位置情報の方)まで誤って消してしまうため。
        // 完了処理を実際に行った場合の両タグの消去はTaskCompletion.completeが担う。
        val tappedTag = if (hasNotifiedDueAt) {
            ReminderScheduler.NOTIFICATION_TAG_DUE
        } else {
            ReminderScheduler.NOTIFICATION_TAG_LOCATION
        }
        NotificationManagerCompat.from(context).cancel(tappedTag, taskId.toInt())

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = AppDatabase.getInstance(context).taskDao().getById(taskId)
                // 期限日時通知([ReminderScheduler.EXTRA_DUE_AT]付き)の「完了」は、通知を表示した
                // 時点の期限と現在のタスクの期限が一致する場合のみ完了とする。繰り返しタスクが
                // アプリ内で先に完了し、次回分の期限に進んだ後にこの古い通知の「完了」を押しても、
                // (次回分は別IDのタスクのため通常は影響しないが、念のため)期限が変わっていれば
                // 完了処理を行わない。位置情報通知([ReminderScheduler.EXTRA_DUE_AT]無し)は従来通り
                // タスクが存在すれば完了扱いとする。
                if (task != null && (!hasNotifiedDueAt || task.dueAt == notifiedDueAt)) {
                    TaskCompletion.complete(context, task)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
