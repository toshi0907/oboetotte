package com.toshi0907.oboetotte.notification

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.ui.theme.OboetotteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通知の「スヌーズ」ボタンから起動する、分数選択専用の透明な画面([Theme.Oboetotte.SnoozeDialog]で
 * ダイアログ以外の領域を透過)。Androidの通知は表示できるアクションボタンが最大3個程度に制限される
 * ため、[ReminderScheduler.SNOOZE_OPTIONS]の件数分ボタンを通知に直接並べる代わりにここで選ばせる。
 */
class SnoozePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (taskId == -1L) {
            finish()
            return
        }

        setContent {
            OboetotteTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("スヌーズ") },
                    text = {
                        Column {
                            ReminderScheduler.SNOOZE_OPTIONS.forEach { option ->
                                TextButton(onClick = { snoozeAndFinish(taskId, option.minutes) }) {
                                    Text(option.label)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { finish() }) {
                            Text("キャンセル")
                        }
                    }
                )
            }
        }
    }

    private fun snoozeAndFinish(taskId: Long, minutes: Long) {
        lifecycleScope.launch {
            val task = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(applicationContext).taskDao().getById(taskId)
            }
            // 選択が確定した時点でのみ現在の通知を消す。ダイアログを何も選ばず閉じた場合は
            // 通知に触れず、元のリマインダー通知がそのまま残る(誤タップ対策)。
            NotificationManagerCompat.from(this@SnoozePickerActivity)
                .cancel(ReminderScheduler.NOTIFICATION_TAG_DUE, taskId.toInt())
            if (task != null && !task.isDone) {
                ReminderScheduler.scheduleSnooze(applicationContext, taskId, minutes)
            }
            finish()
        }
    }
}
