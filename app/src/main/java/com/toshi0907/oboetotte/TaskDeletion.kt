package com.toshi0907.oboetotte

import android.content.Context
import com.toshi0907.oboetotte.attachment.AttachmentStorage
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.attachmentGroupId
import com.toshi0907.oboetotte.notification.LocationReminderManager
import com.toshi0907.oboetotte.notification.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * タスク1件を削除する処理の実体。アプリ内の手動削除(TaskViewModel.deleteTask)と完了済みタスクの
 * 自動削除([com.toshi0907.oboetotte.cleanup.CompletedTaskCleanup])の両方から共通で呼び出す。
 * ウィジェットの再描画は呼び出し元で行う(自動削除では複数件まとめて削除した後に1回だけ行うため)。
 */
object TaskDeletion {
    suspend fun delete(context: Context, task: Task) {
        val db = AppDatabase.getInstance(context)
        val taskDao = db.taskDao()
        val taskAttachmentDao = db.taskAttachmentDao()
        val groupId = task.attachmentGroupId()
        taskDao.delete(task)
        ReminderScheduler.cancel(context, task.id)
        LocationReminderManager.unregister(context, task.id)
        // 添付ファイルは繰り返しシリーズ全体で共有しているため、同じシリーズの他のインスタンスが
        // まだ残っている場合は削除しない(まだ参照されているため)。
        if (taskDao.countByAttachmentGroup(groupId) == 0) {
            val attachmentsToDelete = taskAttachmentDao.getForTask(groupId)
            taskAttachmentDao.deleteForTask(groupId)
            withContext(Dispatchers.IO) {
                attachmentsToDelete.forEach { AttachmentStorage.delete(context, it.storedFileName) }
            }
        }
    }
}
