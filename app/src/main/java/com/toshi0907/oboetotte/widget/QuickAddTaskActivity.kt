package com.toshi0907.oboetotte.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.lifecycleScope
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.TaskViewModel
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.attachmentGroupId
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.oplog.OperationLogSource
import com.toshi0907.oboetotte.oplog.OperationLogger
import com.toshi0907.oboetotte.oplog.logLabel
import com.toshi0907.oboetotte.ui.theme.OboetotteTheme
import kotlinx.coroutines.launch

/**
 * ホーム画面ウィジェット上部の「＋ 追加」から起動する、タスクのタイトル入力専用の透明な画面
 * ([Theme.Oboetotte.SnoozeDialog]でダイアログ以外の領域を透過)。ウィジェット(RemoteViews)には
 * 文字を直接入力できる部品を置けないため、ここでダイアログとして入力させる。
 *
 * 「追加」確定時は共有によるタスク追加と同じ初期値(期限は[TaskViewModel.DEFAULT_DUE_DELAY_MILLIS]後、
 * リスト未登録)で新規タスクを作成し、[MainActivity.openTaskIntent]でアプリを開いてそのタスクの
 * 詳細画面を表示してから自身はfinishする。
 */
class QuickAddTaskActivity : ComponentActivity() {
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OboetotteTheme {
                var title by rememberSaveable { mutableStateOf("") }
                val focusRequester = remember { FocusRequester() }
                val canAdd = title.isNotBlank()
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("タスクを追加") },
                    text = {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("タイトル") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (canAdd) addAndOpen(title) }),
                            modifier = Modifier.focusRequester(focusRequester)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { addAndOpen(title) }, enabled = canAdd) {
                            Text("追加")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { finish() }) {
                            Text("キャンセル")
                        }
                    }
                )
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            }
        }
    }

    private fun addAndOpen(rawTitle: String) {
        val title = rawTitle.trim()
        // 「追加」ボタンとキーボードの確定キーの連打で同じタスクが二重登録されないようにする。
        if (title.isEmpty() || saving) return
        saving = true
        lifecycleScope.launch {
            val task = Task(
                title = title,
                dueAt = System.currentTimeMillis() + TaskViewModel.DEFAULT_DUE_DELAY_MILLIS
            )
            val id = AppDatabase.getInstance(applicationContext).taskDao().insert(task)
            val inserted = task.copy(id = id)
            ReminderScheduler.schedule(applicationContext, inserted)
            OperationLogger.logAsync(
                applicationContext,
                OperationLogSource.WIDGET,
                "タスク追加",
                inserted.logLabel(),
                inserted.attachmentGroupId()
            )
            refreshTaskWidget(applicationContext)
            startActivity(MainActivity.openTaskIntent(applicationContext, id))
            finish()
        }
    }
}
