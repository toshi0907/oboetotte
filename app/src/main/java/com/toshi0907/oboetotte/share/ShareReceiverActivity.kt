package com.toshi0907.oboetotte.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.toshi0907.oboetotte.TaskViewModel
import com.toshi0907.oboetotte.attachment.AttachmentStorage
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskAttachment
import com.toshi0907.oboetotte.normalizeUrl
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.oplog.OperationLogSource
import com.toshi0907.oboetotte.oplog.OperationLogger
import com.toshi0907.oboetotte.widget.refreshTaskWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 他のアプリからの共有(ACTION_SEND/ACTION_SEND_MULTIPLE、mimeTypeはワイルドカード指定で何でも受け付ける)を
 * 受け取り、確認画面を挟まず「タスク(yyyy/MM/dd HH:mm)」という名前の新規タスクを即座に作成する。
 * 共有されたファイルは複数選択されていても1つのタスクにまとめて添付ファイルとして保存する
 * ([com.toshi0907.oboetotte.attachment.AttachmentStorage]経由で端末内にコピー、他の添付ファイル
 * 追加と同じ扱い)。共有内容にテキスト(`EXTRA_TEXT`)が含まれる場合、trim後の文字列全体が
 * [Patterns.WEB_URL]に完全一致すればURL共有とみなし[Task.url]に、それ以外はテキスト共有とみなし
 * [Task.memo]に格納する(ファイル添付と同時に指定されていても両方保存する)。期限は他のタスク登録と
 * 同様デフォルトで1時間後([TaskViewModel.DEFAULT_DUE_DELAY_MILLIS])を設定する。共有ファイルのUriは
 * [AttachmentStorage.copyToStorage]側で検証され(`content://`以外や自アプリのProviderを指すものは拒否)、
 * 拒否・コピー失敗したファイルはスキップしてToastに失敗件数を表示する(取り込めるファイルもテキストも
 * 無い場合はタスクを作成しない)。画面には何も表示せず
 * (`Theme.Oboetotte.SnoozeDialog`の透過テーマを流用)、処理完了後にToastで結果を通知してfinishする。
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = extractUris(intent)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
        if (uris.isEmpty() && sharedText == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            // Patterns.WEB_URLの正規表現マッチングは巨大な入力で重くなりうるため、メインスレッドを避ける。
            val isSharedUrl = sharedText != null && withContext(Dispatchers.Default) {
                Patterns.WEB_URL.matcher(sharedText).matches()
            }

            val database = AppDatabase.getInstance(applicationContext)
            val taskDao = database.taskDao()
            val taskAttachmentDao = database.taskAttachmentDao()

            // 共有元は任意のアプリのため、先にファイルをコピーして検証する。受け付けられないUri(nullが返る)や
            // コピー中の例外はそのファイルだけスキップし、残りのファイルの処理は続行する。
            val copiedFiles = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        AttachmentStorage.copyToStorage(applicationContext, uri)
                    } catch (e: Exception) {
                        Log.w(TAG, "共有ファイルのコピーに失敗しました", e)
                        null
                    }
                }
            }
            val failedCount = uris.size - copiedFiles.size
            // 取り込めるものが何も無い共有(拒否されたUriのみ等)では、空のタスクを作らない
            // (他アプリから無操作で空タスク・リマインダーを量産されないようにする)。
            if (copiedFiles.isEmpty() && sharedText == null) {
                Toast.makeText(
                    applicationContext,
                    "共有されたファイルを取り込めませんでした",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            val title = "タスク(${TITLE_DATE_FORMAT.format(LocalDateTime.now())})"
            val task = Task(
                title = title,
                dueAt = System.currentTimeMillis() + TaskViewModel.DEFAULT_DUE_DELAY_MILLIS,
                url = if (isSharedUrl) normalizeUrl(sharedText!!) else null,
                memo = if (!isSharedUrl) sharedText else null
            )
            val taskId = taskDao.insert(task)
            ReminderScheduler.schedule(applicationContext, task.copy(id = taskId))

            copiedFiles.forEach { copied ->
                taskAttachmentDao.insert(
                    TaskAttachment(
                        taskId = taskId,
                        fileName = copied.fileName,
                        storedFileName = copied.storedFileName,
                        mimeType = copied.mimeType,
                        sizeBytes = copied.sizeBytes
                    )
                )
            }
            val attachedCount = copiedFiles.size
            refreshTaskWidget(applicationContext)

            val resultParts = mutableListOf<String>()
            if (attachedCount > 0) resultParts.add("添付${attachedCount}件")
            if (isSharedUrl) resultParts.add("URL") else if (sharedText != null) resultParts.add("メモ")
            if (failedCount > 0) resultParts.add("添付失敗${failedCount}件")
            val suffix = if (resultParts.isEmpty()) "" else "(${resultParts.joinToString("、")})"
            OperationLogger.logAsync(
                applicationContext,
                OperationLogSource.SHARE,
                "タスク追加",
                "「$title」$suffix",
                taskGroupId = taskId
            )
            Toast.makeText(
                applicationContext,
                "「$title」を追加しました$suffix",
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
        private const val TAG = "ShareReceiverActivity"
        private val TITLE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    }
}
