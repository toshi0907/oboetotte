package com.toshi0907.oboetotte

import android.app.Application
import android.net.Uri
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toshi0907.oboetotte.attachment.AttachmentStorage
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.LocationUpdateLog
import com.toshi0907.oboetotte.data.NotificationLog
import com.toshi0907.oboetotte.data.SavedLocation
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskAttachment
import com.toshi0907.oboetotte.data.TaskList
import com.toshi0907.oboetotte.notification.LocationReminderManager
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.widget.TaskWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RepeatRule {
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val WEEKLY_DAYS = "WEEKLY_DAYS"
    const val MONTHLY = "MONTHLY"

    // 曜日はISO-8601に合わせて月曜=1〜日曜=7の数値をカンマ区切りで保存する。
    fun parseDaysOfWeek(value: String?): Set<Int> {
        if (value.isNullOrBlank()) return emptySet()
        return value.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    fun formatDaysOfWeek(days: Set<Int>): String = days.sorted().joinToString(",")
}

/** [EditTaskDialog]で編集可能な項目をまとめたもの。[TaskViewModel.updateTask]に渡す。 */
data class TaskEdits(
    val title: String,
    val listId: Long?,
    val dueAt: Long?,
    val repeatRule: String?,
    val repeatDaysOfWeek: String?,
    val locationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val radiusMeters: Int?,
    val notifyOnArrival: Boolean,
    val notifyOnDeparture: Boolean,
    val url: String?,
    val memo: String?
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application
    private val taskDao = AppDatabase.getInstance(application).taskDao()
    private val taskListDao = AppDatabase.getInstance(application).taskListDao()
    private val savedLocationDao = AppDatabase.getInstance(application).savedLocationDao()
    private val taskAttachmentDao = AppDatabase.getInstance(application).taskAttachmentDao()
    private val notificationLogDao = AppDatabase.getInstance(application).notificationLogDao()
    private val locationUpdateLogDao = AppDatabase.getInstance(application).locationUpdateLogDao()

    val lists: StateFlow<List<TaskList>> = taskListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedLocations: StateFlow<List<SavedLocation>> = savedLocationDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val attachments: StateFlow<List<TaskAttachment>> = taskAttachmentDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notificationLogs: StateFlow<List<NotificationLog>> = notificationLogDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val locationUpdateLogs: StateFlow<List<LocationUpdateLog>> = locationUpdateLogDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTasks: StateFlow<List<Task>> = taskDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedListId = MutableStateFlow<Long?>(null)
    val selectedListId: StateFlow<Long?> = _selectedListId

    private val _showCompleted = MutableStateFlow(false)
    val showCompleted: StateFlow<Boolean> = _showCompleted

    val tasks: StateFlow<List<Task>> = combine(
        allTasks,
        _selectedListId,
        _showCompleted
    ) { tasks, listId, showCompleted ->
        val topLevel = tasks.filter { it.parentTaskId == null }
        val byList = when (listId) {
            null -> topLevel
            UNASSIGNED_LIST_ID -> topLevel.filter { it.listId == null }
            else -> topLevel.filter { it.listId == listId }
        }
        if (showCompleted) byList else byList.filter { !it.isDone }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectList(listId: Long?) {
        _selectedListId.value = listId
    }

    fun setShowCompleted(show: Boolean) {
        _showCompleted.value = show
    }

    fun addTask(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // 「リスト未登録」フィルタ選択中の追加は、実在しないリストID(UNASSIGNED_LIST_ID)
            // ではなくlistId = nullとして登録する。
            val listId = _selectedListId.value.takeUnless { it == UNASSIGNED_LIST_ID }
            val newTask = Task(
                title = trimmed,
                listId = listId,
                dueAt = System.currentTimeMillis() + DEFAULT_DUE_DELAY_MILLIS
            )
            val id = taskDao.insert(newTask)
            ReminderScheduler.schedule(appContext, newTask.copy(id = id))
            TaskWidget().updateAll(appContext)
        }
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            if (task.isDone) {
                taskDao.setDone(task.id, false)
                val updated = task.copy(isDone = false)
                ReminderScheduler.schedule(appContext, updated)
                LocationReminderManager.register(appContext, updated)
                TaskWidget().updateAll(appContext)
            } else {
                // TaskCompletion.completeが自身でウィジェットの再描画までまとめて行う。
                TaskCompletion.complete(appContext, task)
            }
        }
    }

    fun updateTask(task: Task, edits: TaskEdits) {
        val trimmed = edits.title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val updated = task.copy(
                title = trimmed,
                listId = edits.listId,
                dueAt = edits.dueAt,
                repeatRule = edits.repeatRule,
                repeatDaysOfWeek = edits.repeatDaysOfWeek,
                locationName = edits.locationName,
                latitude = edits.latitude,
                longitude = edits.longitude,
                radiusMeters = edits.radiusMeters,
                notifyOnArrival = edits.notifyOnArrival,
                notifyOnDeparture = edits.notifyOnDeparture,
                url = edits.url,
                memo = edits.memo
            )
            taskDao.update(updated)
            ReminderScheduler.schedule(appContext, updated)
            LocationReminderManager.register(appContext, updated)
            TaskWidget().updateAll(appContext)
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            val attachmentsToDelete = taskAttachmentDao.getForTask(task.id)
            taskDao.delete(task)
            taskAttachmentDao.deleteForTask(task.id)
            ReminderScheduler.cancel(appContext, task.id)
            LocationReminderManager.unregister(appContext, task.id)
            withContext(Dispatchers.IO) {
                attachmentsToDelete.forEach { AttachmentStorage.delete(appContext, it.storedFileName) }
            }
            TaskWidget().updateAll(appContext)
        }
    }

    /** [uri]の内容を端末内にコピーし、[task]の添付ファイルとして登録する。 */
    fun addAttachment(task: Task, uri: Uri) {
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) {
                AttachmentStorage.copyToStorage(appContext, uri)
            } ?: return@launch
            taskAttachmentDao.insert(
                TaskAttachment(
                    taskId = task.id,
                    fileName = copied.fileName,
                    storedFileName = copied.storedFileName,
                    mimeType = copied.mimeType,
                    sizeBytes = copied.sizeBytes
                )
            )
        }
    }

    fun deleteAttachment(attachment: TaskAttachment) {
        viewModelScope.launch {
            taskAttachmentDao.delete(attachment)
            withContext(Dispatchers.IO) {
                AttachmentStorage.delete(appContext, attachment.storedFileName)
            }
        }
    }

    fun addSubtask(parent: Task, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val newSubtask = Task(
                title = trimmed,
                parentTaskId = parent.id,
                dueAt = System.currentTimeMillis() + DEFAULT_DUE_DELAY_MILLIS
            )
            val id = taskDao.insert(newSubtask)
            ReminderScheduler.schedule(appContext, newSubtask.copy(id = id))
            TaskWidget().updateAll(appContext)
        }
    }

    fun addList(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            taskListDao.insert(TaskList(name = trimmed))
        }
    }

    fun renameList(list: TaskList, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            taskListDao.update(list.copy(name = trimmed))
        }
    }

    fun deleteList(list: TaskList) {
        viewModelScope.launch {
            taskDao.clearListId(list.id)
            taskListDao.delete(list)
            if (_selectedListId.value == list.id) {
                _selectedListId.value = null
            }
        }
    }

    fun addSavedLocation(name: String, latitude: Double, longitude: Double, radiusMeters: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            savedLocationDao.insert(
                SavedLocation(name = trimmed, latitude = latitude, longitude = longitude, radiusMeters = radiusMeters)
            )
        }
    }

    fun updateSavedLocation(location: SavedLocation, name: String, radiusMeters: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            savedLocationDao.update(location.copy(name = trimmed, radiusMeters = radiusMeters))
        }
    }

    fun deleteSavedLocation(location: SavedLocation) {
        viewModelScope.launch {
            savedLocationDao.delete(location)
        }
    }

    companion object {
        /**
         * [selectedListId]に渡すと「リスト未登録」(`listId == null`のタスクのみ)を表す特別な値。
         * Roomの`TaskList.id`は`autoGenerate`で1から始まるため、負の値であれば実際のリストIDと
         * 衝突しない。`null`は引き続き「すべて」を表す。
         */
        const val UNASSIGNED_LIST_ID = -1L

        /** タスク・サブタスク登録時にデフォルトで設定する期限までの猶予(1時間)。 */
        private const val DEFAULT_DUE_DELAY_MILLIS = 60 * 60 * 1000L
    }
}
