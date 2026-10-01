package com.toshi0907.oboetotte.cleanup

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.toshi0907.oboetotte.TaskDeletion
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.widget.refreshTaskWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * 完了から設定日数([CompletedTaskCleanupSettings.getRetentionDays])以上経過した完了済みタスクを
 * 削除する。トップレベルタスク・サブタスクとも個別に完了日時([com.toshi0907.oboetotte.data.Task.completedAt])
 * で判定し、削除対象になったタスクの配下のサブタスク(孫以下も含む)は、未完了のものも含めて一緒に削除する
 * (親を失ったサブタスクが一覧から辿れない状態で残らないようにするため)。
 *
 * アプリ起動時・設定変更時([com.toshi0907.oboetotte.TaskViewModel])と、1日1回の定期実行
 * ([CompletedTaskCleanupWorker])から呼ばれる。同じプロセス内で同時に呼ばれても二重に削除処理が
 * 走らないよう[mutex]で直列化する。
 */
object CompletedTaskCleanup {
    private val mutex = Mutex()

    /** 削除したタスクの件数を返す(自動削除が無効な場合は何もせず0)。 */
    suspend fun run(context: Context): Int = mutex.withLock {
        if (!CompletedTaskCleanupSettings.isEnabled(context)) return@withLock 0
        val retentionDays = CompletedTaskCleanupSettings.getRetentionDays(context)
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())

        val taskDao = AppDatabase.getInstance(context).taskDao()
        val expired = taskDao.getCompletedBefore(cutoff)
        if (expired.isEmpty()) return@withLock 0

        val notificationManager = NotificationManagerCompat.from(context)
        val deletedIds = HashSet<Long>()
        expired.forEach { root ->
            if (root.id in deletedIds) return@forEach
            // 削除処理の途中でユーザーが未完了に戻した等、最初の抽出以降に状態が変わったタスクを
            // 誤って削除しないよう、削除直前にDBから読み直して削除対象のままか確認する。
            val current = taskDao.getById(root.id) ?: return@forEach
            val completedAt = current.completedAt
            if (!current.isDone || completedAt == null || completedAt >= cutoff) return@forEach

            // 配下のサブタスク(孫以下も含む)を、削除直前の最新の状態から求める。
            val childrenByParent = taskDao.getAll().first()
                .filter { it.parentTaskId != null }
                .groupBy { it.parentTaskId }
            val queue = ArrayDeque(listOf(current))
            while (queue.isNotEmpty()) {
                val task = queue.removeFirst()
                if (!deletedIds.add(task.id)) continue
                childrenByParent[task.id]?.let { queue.addAll(it) }
                // 一緒に削除される未完了のサブタスクの通知が表示されたまま残らないよう消去しておく。
                notificationManager.cancel(ReminderScheduler.NOTIFICATION_TAG_DUE, task.id.toInt())
                notificationManager.cancel(ReminderScheduler.NOTIFICATION_TAG_LOCATION, task.id.toInt())
                TaskDeletion.delete(context, task)
            }
        }
        if (deletedIds.isNotEmpty()) refreshTaskWidget(context)
        deletedIds.size
    }
}
