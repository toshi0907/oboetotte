package com.toshi0907.oboetotte.notification

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.toshi0907.oboetotte.TaskCompletion
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.futureDueConfirmationMessage
import com.toshi0907.oboetotte.isDueAfterToday
import com.toshi0907.oboetotte.oplog.OperationLogSource
import com.toshi0907.oboetotte.ui.theme.OboetotteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 期日が翌日以降のタスクの通知で「完了」ボタンを押した際に起動する、確認ダイアログ専用の透明な画面
 * ([SnoozePickerActivity]と同じ[Theme.Oboetotte.SnoozeDialog])。BroadcastReceiverからは
 * (Android 10以降のバックグラウンドからのActivity起動制限・Android 12以降の通知トランポリン制限により)
 * 画面を出せないため、通知を組み立てる時点で期日が翌日以降なら「完了」ボタンの宛先を
 * [CompleteReceiver]ではなくこの画面にする([ReminderScheduler.completePendingIntent]等)。
 * 押された時点で改めてタスクを読み直し、既に翌日以降でなくなっていれば確認せずにそのまま完了させる。
 * キャンセルした場合は通知に触れず、元の通知がそのまま残る(スヌーズ画面と同じ誤タップ対策)。
 */
class CompleteConfirmActivity : ComponentActivity() {
    private var confirmingTask by mutableStateOf<Task?>(null)
    private var hasNotifiedDueAt = false
    private var notifiedDueAt = -1L
    private var tappedTag = ReminderScheduler.NOTIFICATION_TAG_LOCATION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (taskId == -1L) {
            finish()
            return
        }
        hasNotifiedDueAt = intent.hasExtra(ReminderScheduler.EXTRA_DUE_AT)
        notifiedDueAt = intent.getLongExtra(ReminderScheduler.EXTRA_DUE_AT, -1L)
        tappedTag = if (hasNotifiedDueAt) {
            ReminderScheduler.NOTIFICATION_TAG_DUE
        } else {
            ReminderScheduler.NOTIFICATION_TAG_LOCATION
        }

        setContent {
            OboetotteTheme {
                confirmingTask?.let { task ->
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text("タスクを完了") },
                        text = { Text("「${task.title}」\n" + futureDueConfirmationMessage(task.dueAt ?: 0L)) },
                        confirmButton = {
                            TextButton(onClick = { completeAndFinish(task) }) {
                                Text("完了にする")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) {
                                Text("キャンセル")
                            }
                        }
                    )
                }
            }
        }

        lifecycleScope.launch {
            val task = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(applicationContext).taskDao().getById(taskId)
            }
            if (task == null || task.isDone ||
                !CompleteReceiver.isCompletableFromNotification(task, hasNotifiedDueAt, notifiedDueAt)
            ) {
                // 既に完了・削除済み、または期限が変わっていて完了させない場合は、CompleteReceiverと
                // 同様にタップされた側の通知だけを消して終了する。
                NotificationManagerCompat.from(this@CompleteConfirmActivity).cancel(tappedTag, taskId.toInt())
                finish()
            } else if (!isDueAfterToday(task.dueAt)) {
                completeAndFinish(task)
            } else {
                confirmingTask = task
            }
        }
    }

    private fun completeAndFinish(task: Task) {
        confirmingTask = null
        lifecycleScope.launch {
            // 完了処理の途中で画面が破棄(回転等)されても、完了状態への遷移と繰り返しタスクの次回分生成が
            // 中途半端に終わらないようNonCancellableで最後まで実行してからfinishする。
            withContext(NonCancellable + Dispatchers.IO) {
                // ダイアログ表示中にアプリ側で編集された可能性があるため、完了直前にDBから読み直し、
                // 期限の突き合わせもやり直す(繰り返しの次回分を古い期限・設定から作らないため)。
                val latest = AppDatabase.getInstance(applicationContext).taskDao().getById(task.id)
                if (latest != null &&
                    CompleteReceiver.isCompletableFromNotification(latest, hasNotifiedDueAt, notifiedDueAt)
                ) {
                    TaskCompletion.complete(applicationContext, latest, OperationLogSource.NOTIFICATION)
                } else {
                    NotificationManagerCompat.from(applicationContext).cancel(tappedTag, task.id.toInt())
                }
            }
            finish()
        }
    }
}
