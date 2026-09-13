package com.toshi0907.oboetotte.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.toshi0907.oboetotte.TaskViewModel
import com.toshi0907.oboetotte.attachment.AttachmentStorage
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskAttachment
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.widget.refreshTaskWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 他のアプリからの共有(ACTION_SEND/ACTION_SEND_MULTIPLE、mimeTypeは`*​/*`で何でも受け付ける)を
 * 受け取り、確認画面を挟まず「タスク(yyyy/MM/dd HH:mm)」という名前の新規タスクを即座に作成する。
 * 共有されたファイルは複数選択されていても1つのタスクにまとめて添付ファイルとして保存する
 * ([com.toshi0907.oboetotte.attachment.AttachmentStorage]経由で端末内にコピー、他の添付ファイル
 * 追加と同じ扱い)。期限は他のタスク登録と同様デフォルトで1時間後
 * ([TaskViewModel.DEFAULT_DUE_DELAY_MILLIS])を設定する。画面には何も表示せず
 * (`Theme.Oboetotte.SnoozeDialog`の透過テーマを流用)、処理完了後にToastで結果を通知してfinishする。
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val database = AppDatabase.getInstance(applicationContext)
            val taskDao = database.taskDao()
            val taskAttachmentDao = database.taskAttachmentDao()

            val title = "タスク(${TITLE_DATE_FORMAT.format(LocalDateTime.now())})"
            val task = Task(
                title = title,
                dueAt = System.currentTimeMillis() + TaskViewModel.DEFAULT_DUE_DELAY_MILLIS
            )
            val taskId = taskDao.insert(task)
            ReminderScheduler.schedule(applicationContext, task.copy(id = taskId))

            var attachedCount = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val copied = AttachmentStorage.copyToStorage(applicationContext, uri) ?: return@forEach
                    taskAttachmentDao.insert(
                        TaskAttachment(
                            taskId = taskId,
                            fileName = copied.fileName,
                            storedFileName = copied.storedFileName,
                            mimeType = copied.mimeType,
                            sizeBytes = copied.sizeBytes
                        )
                    )
                    attachedCount++
                }
            }
            refreshTaskWidget(applicationContext)

            Toast.makeText(
                applicationContext,
                "「$title」を追加しました(添付${attachedCount}件)",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
        Intent.ACTION_SEND_MULTIPLE -> intent.streamArrayListExtra().orEmpty()
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun Intent.streamArrayListExtra(): List<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }

    companion object {
        private val TITLE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    }
}
