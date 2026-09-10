package com.toshi0907.oboetotte

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskList
import com.toshi0907.oboetotte.notification.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

object RepeatRule {
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
}

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application
    private val taskDao = AppDatabase.getInstance(application).taskDao()
    private val taskListDao = AppDatabase.getInstance(application).taskListDao()

    val lists: StateFlow<List<TaskList>> = taskListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTasks: StateFlow<List<Task>> = taskDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedListId = MutableStateFlow<Long?>(null)
    val selectedListId: StateFlow<Long?> = _selectedListId

    val tasks: StateFlow<List<Task>> = combine(allTasks, _selectedListId) { tasks, listId ->
        val topLevel = tasks.filter { it.parentTaskId == null }
        if (listId == null) topLevel else topLevel.filter { it.listId == listId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectList(listId: Long?) {
        _selectedListId.value = listId
    }

    fun addTask(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            taskDao.insert(Task(title = trimmed, listId = _selectedListId.value))
        }
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            val newDone = !task.isDone
            taskDao.setDone(task.id, newDone)
            if (newDone) {
                ReminderScheduler.cancel(appContext, task.id)
            } else {
                ReminderScheduler.schedule(appContext, task.copy(isDone = false))
            }
            val rule = task.repeatRule
            val dueAt = task.dueAt
            if (newDone && rule != null && dueAt != null) {
                val nextTask = task.copy(
                    id = 0,
                    isDone = false,
                    dueAt = nextDueAt(dueAt, rule)
                )
                val newId = taskDao.insert(nextTask)
                ReminderScheduler.schedule(appContext, nextTask.copy(id = newId))
            }
        }
    }

    fun updateTask(task: Task, newTitle: String, dueAt: Long?, repeatRule: String?) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val updated = task.copy(title = trimmed, dueAt = dueAt, repeatRule = repeatRule)
            taskDao.update(updated)
            ReminderScheduler.schedule(appContext, updated)
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            taskDao.delete(task)
            ReminderScheduler.cancel(appContext, task.id)
        }
    }

    fun addSubtask(parent: Task, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            taskDao.insert(Task(title = trimmed, parentTaskId = parent.id))
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

    private fun nextDueAt(current: Long, rule: String): Long {
        val zoned = Instant.ofEpochMilli(current).atZone(ZoneId.systemDefault())
        val next = when (rule) {
            RepeatRule.DAILY -> zoned.plusDays(1)
            RepeatRule.WEEKLY -> zoned.plusWeeks(1)
            RepeatRule.MONTHLY -> zoned.plusMonths(1)
            else -> zoned
        }
        return next.toInstant().toEpochMilli()
    }
}
