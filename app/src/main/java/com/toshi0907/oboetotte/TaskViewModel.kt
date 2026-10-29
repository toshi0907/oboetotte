package com.toshi0907.oboetotte

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toshi0907.oboetotte.attachment.AttachmentStorage
import com.toshi0907.oboetotte.attachment.StorageLimitException
import com.toshi0907.oboetotte.backup.CloudBackupResult
import com.toshi0907.oboetotte.backup.CloudBackupRunner
import com.toshi0907.oboetotte.backup.CloudBackupScheduler
import com.toshi0907.oboetotte.backup.CloudBackupSettings
import com.toshi0907.oboetotte.cleanup.CompletedTaskCleanup
import com.toshi0907.oboetotte.cleanup.CompletedTaskCleanupSettings
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.LocationUpdateLog
import com.toshi0907.oboetotte.data.NotificationLog
import com.toshi0907.oboetotte.data.OperationLog
import com.toshi0907.oboetotte.data.SavedLocation
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskAttachment
import com.toshi0907.oboetotte.data.TaskList
import com.toshi0907.oboetotte.data.VibrationPattern
import com.toshi0907.oboetotte.data.attachmentGroupId
import com.toshi0907.oboetotte.notification.LocationReminderManager
import com.toshi0907.oboetotte.notification.LocationTrackingMode
import com.toshi0907.oboetotte.notification.LocationTrackingSettings
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.notification.VibrationPatternPlayer
import com.toshi0907.oboetotte.oplog.OperationLogSource
import com.toshi0907.oboetotte.oplog.OperationLogger
import com.toshi0907.oboetotte.oplog.describeTaskChanges
import com.toshi0907.oboetotte.oplog.logLabel
import com.toshi0907.oboetotte.update.AppUpdateCheckSettings
import com.toshi0907.oboetotte.widget.refreshTaskWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

object RepeatRule {
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val WEEKLY_DAYS = "WEEKLY_DAYS"
    const val MONTHLY = "MONTHLY"
    // 期限ではなく完了日を起点に、Task.repeatIntervalDays日後を次回の期限にする。次回の期限は
    // 完了するまで決まらないため、カレンダー表示での将来の予定の計算(nextDueAt)には含めない。
    const val AFTER_COMPLETION = "AFTER_COMPLETION"

    /** [AFTER_COMPLETION]の間隔として受け付ける日数の範囲。 */
    val INTERVAL_DAYS_RANGE = 1..999

    // 曜日はISO-8601に合わせて月曜=1〜日曜=7の数値をカンマ区切りで保存する。
    // バックアップの破損等で範囲外の値が混ざっていても、呼び出し側の`isNotEmpty()`チェックが
    // 有効な曜日の有無として正しく働くよう、1〜7以外は捨てる。
    fun parseDaysOfWeek(value: String?): Set<Int> {
        if (value.isNullOrBlank()) return emptySet()
        return value.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
    }

    fun formatDaysOfWeek(days: Set<Int>): String = days.sorted().joinToString(",")

    /**
     * [current]の次回の期限(ローカルタイムゾーン基準)。繰り返しタスクを完了した際の次回分生成
     * ([TaskCompletion.complete])と、カレンダー表示での将来の予定の計算の両方から使い、
     * 両者の日付が必ず一致するようにしている。[WEEKLY_DAYS]は[daysOfWeek]が空でないこと、
     * それ以外の未知のルールは呼び出し側で除外しておくこと(いずれも[current]をそのまま返す)。
     */
    fun nextDueAt(current: Long, rule: String, daysOfWeek: Set<Int>): Long {
        val zoned = Instant.ofEpochMilli(current).atZone(ZoneId.systemDefault())
        val next = when (rule) {
            DAILY -> zoned.plusDays(1)
            WEEKLY -> zoned.plusWeeks(1)
            // 一致する曜日が無い(daysOfWeekが空・範囲外のみ)場合も例外にせず、currentをそのまま返す。
            WEEKLY_DAYS -> (1..7)
                .map { zoned.plusDays(it.toLong()) }
                .firstOrNull { it.dayOfWeek.value in daysOfWeek }
                ?: zoned
            MONTHLY -> zoned.plusMonths(1)
            else -> zoned
        }
        return next.toInstant().toEpochMilli()
    }

    /**
     * [AFTER_COMPLETION]の次回の期限。[completedAt]の日付(ローカルタイムゾーン)に[intervalDays]日を
     * 加え、時刻は[currentDueAt](元の期限)の時刻をそのまま引き継ぐ。期限を過ぎてから完了した場合も
     * 実際の完了日を起点にする。
     */
    fun nextDueAtAfterCompletion(
        currentDueAt: Long,
        completedAt: Long,
        intervalDays: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val dueTime = Instant.ofEpochMilli(currentDueAt).atZone(zone).toLocalTime()
        val completedDate = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()
        return completedDate.plusDays(intervalDays.toLong())
            .atTime(dueTime)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }
}

/** [EditTaskDialog]で編集可能な項目をまとめたもの。[TaskViewModel.updateTask]に渡す。 */
data class TaskEdits(
    val title: String,
    val listId: Long?,
    val dueAt: Long?,
    val repeatRule: String?,
    val repeatDaysOfWeek: String?,
    val repeatIntervalDays: Int?,
    val locationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val radiusMeters: Int?,
    val notifyOnArrival: Boolean,
    val notifyOnDeparture: Boolean,
    val url: String?,
    val memo: String?,
    val autoSnoozeMinutes: Long?,
    val notifyOnlyMode: Boolean,
    val vibrationPatternId: Long?
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application
    private val taskDao = AppDatabase.getInstance(application).taskDao()
    private val taskListDao = AppDatabase.getInstance(application).taskListDao()
    private val savedLocationDao = AppDatabase.getInstance(application).savedLocationDao()
    private val taskAttachmentDao = AppDatabase.getInstance(application).taskAttachmentDao()
    private val notificationLogDao = AppDatabase.getInstance(application).notificationLogDao()
    private val locationUpdateLogDao = AppDatabase.getInstance(application).locationUpdateLogDao()
    private val vibrationPatternDao = AppDatabase.getInstance(application).vibrationPatternDao()
    private val operationLogDao = AppDatabase.getInstance(application).operationLogDao()

    val lists: StateFlow<List<TaskList>> = taskListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedLocations: StateFlow<List<SavedLocation>> = savedLocationDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vibrationPatterns: StateFlow<List<VibrationPattern>> = vibrationPatternDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val attachments: StateFlow<List<TaskAttachment>> = taskAttachmentDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notificationLogs: StateFlow<List<NotificationLog>> = notificationLogDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val locationUpdateLogs: StateFlow<List<LocationUpdateLog>> = locationUpdateLogDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val operationLogs: StateFlow<List<OperationLog>> = operationLogDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** タスクの詳細画面の「操作ログ」用。[taskGroupId]はタスクの[attachmentGroupId]。 */
    fun operationLogsForTaskGroup(taskGroupId: Long): Flow<List<OperationLog>> =
        operationLogDao.getForTaskGroup(taskGroupId)

    /** 操作ログ画面の「ログを消去」ボタン用。 */
    fun clearOperationLogs() {
        viewModelScope.launch { OperationLogger.clearAll(appContext) }
    }

    /** [taskGroupId](対象タスクの[attachmentGroupId])を指定すると、そのタスクの詳細画面の操作ログにも表示される。 */
    private fun logOperation(action: String, detail: String = "", taskGroupId: Long? = null) {
        OperationLogger.logAsync(appContext, OperationLogSource.APP, action, detail, taskGroupId)
    }

    private val _locationTrackingMode =
        MutableStateFlow(LocationTrackingSettings.getMode(application))
    val locationTrackingMode: StateFlow<LocationTrackingMode> = _locationTrackingMode

    private val _locationTrackingIntervalMinutes =
        MutableStateFlow(LocationTrackingSettings.getIntervalMinutes(application))
    val locationTrackingIntervalMinutes: StateFlow<Int> = _locationTrackingIntervalMinutes

    private val _completedTaskCleanupEnabled =
        MutableStateFlow(CompletedTaskCleanupSettings.isEnabled(application))
    val completedTaskCleanupEnabled: StateFlow<Boolean> = _completedTaskCleanupEnabled

    private val _completedTaskCleanupRetentionDays =
        MutableStateFlow(CompletedTaskCleanupSettings.getRetentionDays(application))
    val completedTaskCleanupRetentionDays: StateFlow<Int> = _completedTaskCleanupRetentionDays

    private val _dueTimePresets = MutableStateFlow(DueTimePresetSettings.getAll(application))
    /** 期限クイック選択「当日朝/昼/夕/夜」の時刻(0時からの分数)。 */
    val dueTimePresets: StateFlow<Map<DueTimeSlot, Int>> = _dueTimePresets

    private val _cloudBackupEnabled = MutableStateFlow(CloudBackupSettings.isEnabled(application))
    val cloudBackupEnabled: StateFlow<Boolean> = _cloudBackupEnabled

    private val _cloudBackupFolderUri = MutableStateFlow(CloudBackupSettings.getFolderUri(application))
    val cloudBackupFolderUri: StateFlow<Uri?> = _cloudBackupFolderUri

    private val _cloudBackupRetentionCount =
        MutableStateFlow(CloudBackupSettings.getRetentionCount(application))
    val cloudBackupRetentionCount: StateFlow<Int> = _cloudBackupRetentionCount

    private val _cloudBackupHour = MutableStateFlow(CloudBackupSettings.getBackupHour(application))
    val cloudBackupHour: StateFlow<Int> = _cloudBackupHour

    private val _cloudBackupMinute = MutableStateFlow(CloudBackupSettings.getBackupMinute(application))
    val cloudBackupMinute: StateFlow<Int> = _cloudBackupMinute

    private val _cloudBackupLastBackupAt = MutableStateFlow(CloudBackupSettings.getLastBackupAt(application))
    val cloudBackupLastBackupAt: StateFlow<Long?> = _cloudBackupLastBackupAt

    private val _cloudBackupLastResult = MutableStateFlow(CloudBackupSettings.getLastBackupResult(application))
    val cloudBackupLastResult: StateFlow<CloudBackupResult?> = _cloudBackupLastResult

    private val _cloudBackupLastError = MutableStateFlow(CloudBackupSettings.getLastBackupError(application))
    val cloudBackupLastError: StateFlow<String?> = _cloudBackupLastError

    // 「今すぐバックアップ」の実行中かどうか。保存先への書き込みは失敗時に待機を挟んで再試行する
    // ため完了まで数十秒かかることがあり、その間ボタンを無効化して実行中であることを表示する。
    private val _cloudBackupRunning = MutableStateFlow(false)
    val cloudBackupRunning: StateFlow<Boolean> = _cloudBackupRunning

    private val _updateLastCheckedAt = MutableStateFlow(AppUpdateCheckSettings.getLastCheckedAt(application))
    val updateLastCheckedAt: StateFlow<Long?> = _updateLastCheckedAt

    // CloudBackupWorker(定期実行)がrecordResultでSharedPreferencesを更新しても、
    // アプリのプロセスが生きている間はcloudBackupLastBackupAt/cloudBackupLastResultの
    // StateFlowがそれだけでは更新されない(runCloudBackupNowによる明示的な再読込でのみ
    // 更新される)ため、SharedPreferences側の変更を直接購読して同期する。SharedPreferences
    // はリスナーをWeakReferenceでしか保持しないため、フィールドとして保持し続ける必要があり、
    // initブロックで登録・onClearedで解除する。
    private val cloudBackupPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            _cloudBackupLastBackupAt.value = CloudBackupSettings.getLastBackupAt(appContext)
            _cloudBackupLastResult.value = CloudBackupSettings.getLastBackupResult(appContext)
            _cloudBackupLastError.value = CloudBackupSettings.getLastBackupError(appContext)
        }

    // AppUpdateCheckWorker(定期実行)がrecordCheckedAtでSharedPreferencesを更新しても、
    // アプリのプロセスが生きている間はupdateLastCheckedAtのStateFlowがそれだけでは更新されない
    // ため、cloudBackupPrefsListenerと同じ考え方でSharedPreferences側の変更を直接購読して同期する。
    private val updateCheckPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            _updateLastCheckedAt.value = AppUpdateCheckSettings.getLastCheckedAt(appContext)
        }

    init {
        CloudBackupSettings.addLastResultChangeListener(appContext, cloudBackupPrefsListener)
        AppUpdateCheckSettings.addLastCheckedAtChangeListener(appContext, updateCheckPrefsListener)
    }

    override fun onCleared() {
        CloudBackupSettings.removeLastResultChangeListener(appContext, cloudBackupPrefsListener)
        AppUpdateCheckSettings.removeLastCheckedAtChangeListener(appContext, updateCheckPrefsListener)
    }

    val allTasks: StateFlow<List<Task>> = taskDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedListId = MutableStateFlow<Long?>(null)
    val selectedListId: StateFlow<Long?> = _selectedListId

    private val _showCompleted = MutableStateFlow(false)
    val showCompleted: StateFlow<Boolean> = _showCompleted

    private val _activeDisplayFilters = MutableStateFlow<Set<TaskDisplayFilter>>(emptySet())
    val activeDisplayFilters: StateFlow<Set<TaskDisplayFilter>> = _activeDisplayFilters

    fun toggleDisplayFilter(filter: TaskDisplayFilter) {
        _activeDisplayFilters.value = _activeDisplayFilters.value.let {
            if (filter in it) it - filter else it + filter
        }
    }

    val tasks: StateFlow<List<Task>> = combine(
        allTasks,
        _selectedListId,
        _showCompleted
    ) { tasks, listId, showCompleted ->
        val byList = tasks.filter { it.parentTaskId == null && it.matchesListFilter(listId) }
        if (showCompleted) byList else byList.filter { !it.isDone }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectList(listId: Long?) {
        _selectedListId.value = listId
    }

    fun setShowCompleted(show: Boolean) {
        _showCompleted.value = show
    }

    /**
     * タスクを登録する。登録完了後(IDが確定した状態)のタスクを[onAdded]でメインスレッドに返す。
     * タスク一覧画面はこれを受けて、追加したタスクの編集ダイアログを開く。
     */
    fun addTask(title: String, onAdded: (Task) -> Unit = {}) {
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
            val inserted = newTask.copy(id = id)
            ReminderScheduler.schedule(appContext, inserted)
            logOperation("タスク追加", inserted.logLabel(), inserted.attachmentGroupId())
            refreshTaskWidget(appContext)
            onAdded(inserted)
        }
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            if (task.isDone) {
                val updatedRows = taskDao.setDone(task.id, false, null)
                val updated = task.copy(isDone = false, completedAt = null)
                ReminderScheduler.schedule(appContext, updated)
                LocationReminderManager.register(appContext, updated)
                // 既に未完了に戻っていた・削除済み等で実際には状態が変わらなかった場合は記録しない。
                if (updatedRows > 0) logOperation("タスクを未完了に戻す", task.logLabel(), task.attachmentGroupId())
                refreshTaskWidget(appContext)
            } else {
                // TaskCompletion.completeが自身でウィジェットの再描画・操作ログの記録までまとめて行う。
                TaskCompletion.complete(appContext, task, OperationLogSource.APP)
            }
        }
    }

    fun updateTask(task: Task, edits: TaskEdits) {
        val trimmed = edits.title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // 編集ダイアログを開いたまま保持しているtaskスナップショットは、裏で(通知のみタスクの
            // 自動完了・別画面からの操作等により)既に完了・繰り返しの次回分生成が済んでいる可能性が
            // ある。ここでDBから最新の状態を読み直し、それを土台にすることでisDoneを含む状態を
            // 誤って古い値へ巻き戻さない(特にisDoneをtrue→falseへ戻すと、既にTaskCompletion.complete
            // が生成した次回分と合わせてアクティブなインスタンスが重複してしまう)。タスクが
            // 既に削除されていた場合(current == null)も何もせず終了する。
            val current = taskDao.getById(task.id) ?: return@launch
            val updated = current.copy(
                title = trimmed,
                listId = edits.listId,
                dueAt = edits.dueAt,
                repeatRule = edits.repeatRule,
                repeatDaysOfWeek = edits.repeatDaysOfWeek,
                // 間隔は「完了後に繰り返す」の場合のみ保持し、他のルールへ切り替えたら破棄する。
                repeatIntervalDays = edits.repeatIntervalDays
                    .takeIf { edits.repeatRule == RepeatRule.AFTER_COMPLETION },
                locationName = edits.locationName,
                latitude = edits.latitude,
                longitude = edits.longitude,
                radiusMeters = edits.radiusMeters,
                notifyOnArrival = edits.notifyOnArrival,
                notifyOnDeparture = edits.notifyOnDeparture,
                url = edits.url,
                memo = edits.memo,
                // 通知のみタスクはスヌーズ機能(手動・オート)を併用しないため、有効な場合は
                // オートスヌーズの間隔設定を強制的にクリアする(EditTaskDialog側で選択UIを
                // 隠していても、ここで正規化することでデータの整合性を保証する)。
                autoSnoozeMinutes = if (edits.notifyOnlyMode) null else edits.autoSnoozeMinutes,
                notifyOnlyMode = edits.notifyOnlyMode,
                vibrationPatternId = edits.vibrationPatternId
            )
            taskDao.update(updated)
            ReminderScheduler.schedule(appContext, updated)
            LocationReminderManager.register(appContext, updated)
            val changes = describeTaskChanges(
                before = current,
                after = updated,
                // 名前の引き当てが必要な場合(リスト・バイブレーションパターンを変更した場合)のみ読み込む。
                listNames = if (current.listId != updated.listId) {
                    taskListDao.getAll().first().associate { it.id to it.name }
                } else {
                    emptyMap()
                },
                patternNames = if (current.vibrationPatternId != updated.vibrationPatternId) {
                    vibrationPatternDao.getAll().first().associate { it.id to it.name }
                } else {
                    emptyMap()
                }
            )
            // 何も変更せずに保存した場合は記録しない。
            if (changes.isNotEmpty()) {
                logOperation("タスク編集", "${current.logLabel()} ${changes.joinToString("、")}", current.attachmentGroupId())
            }
            refreshTaskWidget(appContext)
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            TaskDeletion.delete(appContext, task, OperationLogSource.APP)
            refreshTaskWidget(appContext)
        }
    }

    /**
     * [uri]の内容を端末内にコピーし、[task]の添付ファイルとして登録する。繰り返しタスクの場合、
     * [Task.attachmentGroupId]を使って同じ繰り返しシリーズの全インスタンスと共有する。
     */
    fun addAttachment(task: Task, uri: Uri) {
        viewModelScope.launch {
            val copied = try {
                withContext(Dispatchers.IO) {
                    AttachmentStorage.copyToStorage(appContext, uri)
                }
            } catch (e: StorageLimitException) {
                Toast.makeText(appContext, "ファイルを添付できませんでした。${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            } ?: return@launch
            taskAttachmentDao.insert(
                TaskAttachment(
                    taskId = task.attachmentGroupId(),
                    fileName = copied.fileName,
                    storedFileName = copied.storedFileName,
                    mimeType = copied.mimeType,
                    sizeBytes = copied.sizeBytes
                )
            )
            logOperation("添付ファイル追加", "${task.logLabel()} ${copied.fileName}", task.attachmentGroupId())
        }
    }

    fun deleteAttachment(attachment: TaskAttachment) {
        viewModelScope.launch {
            taskAttachmentDao.delete(attachment)
            withContext(Dispatchers.IO) {
                AttachmentStorage.delete(appContext, attachment.storedFileName)
            }
            // 添付ファイルのtaskIdは添付先タスクのattachmentGroupId()。
            logOperation("添付ファイル削除", attachment.fileName, attachment.taskId)
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
            logOperation("サブタスク追加", "${parent.logLabel()}に「$trimmed」", parent.attachmentGroupId())
            refreshTaskWidget(appContext)
        }
    }

    fun addList(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            taskListDao.insert(TaskList(name = trimmed))
            logOperation("リスト追加", "「$trimmed」")
        }
    }

    fun renameList(list: TaskList, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            taskListDao.update(list.copy(name = trimmed))
            logOperation("リスト名変更", "「${list.name}」→「$trimmed」")
        }
    }

    fun deleteList(list: TaskList) {
        viewModelScope.launch {
            taskDao.clearListId(list.id)
            taskListDao.delete(list)
            if (_selectedListId.value == list.id) {
                _selectedListId.value = null
            }
            logOperation("リスト削除", "「${list.name}」")
        }
    }

    fun addSavedLocation(name: String, latitude: Double, longitude: Double, radiusMeters: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            savedLocationDao.insert(
                SavedLocation(name = trimmed, latitude = latitude, longitude = longitude, radiusMeters = radiusMeters)
            )
            logOperation("場所追加", "「$trimmed」半径${radiusMeters}m")
        }
    }

    fun updateSavedLocation(location: SavedLocation, name: String, radiusMeters: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            savedLocationDao.update(location.copy(name = trimmed, radiusMeters = radiusMeters))
            logOperation(
                "場所編集",
                "「${location.name}」→「$trimmed」半径${location.radiusMeters}m→${radiusMeters}m"
            )
        }
    }

    fun deleteSavedLocation(location: SavedLocation) {
        viewModelScope.launch {
            savedLocationDao.delete(location)
            logOperation("場所削除", "「${location.name}」")
        }
    }

    fun addVibrationPattern(name: String, onMs: Long, offMs: Long, durationMs: Long) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !VibrationPatternPlayer.isValid(onMs, offMs, durationMs)) return
        viewModelScope.launch {
            vibrationPatternDao.insert(
                VibrationPattern(name = trimmed, onMs = onMs, offMs = offMs, durationMs = durationMs)
            )
            logOperation("バイブレーションパターン追加", "「$trimmed」${vibrationSummary(onMs, offMs, durationMs)}")
        }
    }

    fun updateVibrationPattern(pattern: VibrationPattern, name: String, onMs: Long, offMs: Long, durationMs: Long) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !VibrationPatternPlayer.isValid(onMs, offMs, durationMs)) return
        viewModelScope.launch {
            vibrationPatternDao.update(
                pattern.copy(name = trimmed, onMs = onMs, offMs = offMs, durationMs = durationMs)
            )
            logOperation(
                "バイブレーションパターン編集",
                "「${pattern.name}」→「$trimmed」" +
                    "${vibrationSummary(pattern.onMs, pattern.offMs, pattern.durationMs)}→" +
                    vibrationSummary(onMs, offMs, durationMs)
            )
        }
    }

    /** パターンを削除し、それを使っていたタスクはパターン未使用(標準のバイブレーション)に戻す。 */
    fun deleteVibrationPattern(pattern: VibrationPattern) {
        viewModelScope.launch {
            taskDao.clearVibrationPatternId(pattern.id)
            vibrationPatternDao.delete(pattern)
            logOperation("バイブレーションパターン削除", "「${pattern.name}」")
        }
    }

    /** 設定画面の「試す」ボタン用。保存前の入力値でもそのまま再生できるよう値で受け取る。 */
    fun previewVibrationPattern(onMs: Long, offMs: Long, durationMs: Long) {
        if (!VibrationPatternPlayer.isValid(onMs, offMs, durationMs)) return
        VibrationPatternPlayer.play(
            appContext,
            VibrationPattern(name = "", onMs = onMs, offMs = offMs, durationMs = durationMs)
        )
    }

    /** 位置情報リマインダーの確認方式を切り替える(Geofencing API/連続追跡方式)。 */
    fun setLocationTrackingMode(mode: LocationTrackingMode) {
        viewModelScope.launch {
            val changed = _locationTrackingMode.value != mode
            LocationReminderManager.switchMode(appContext, mode)
            _locationTrackingMode.value = mode
            if (changed) logOperation("設定変更", "位置情報の確認方式: ${mode.logLabel()}")
        }
    }

    /** 連続追跡方式の更新頻度(分)を変更する。1〜60分の範囲に丸められる。 */
    fun setLocationTrackingIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            LocationReminderManager.updateContinuousTrackingInterval(appContext, minutes)
            _locationTrackingIntervalMinutes.value = LocationTrackingSettings.getIntervalMinutes(appContext)
            logOperation("設定変更", "連続追跡の更新頻度: ${_locationTrackingIntervalMinutes.value}分")
        }
    }

    /**
     * 完了済みタスクの自動削除の有効/無効を切り替える。有効にした場合は、その時点で既に削除日数を
     * 経過している完了済みタスクを(次回の定期実行を待たずに)すぐ削除する。
     */
    fun setCompletedTaskCleanupEnabled(enabled: Boolean) {
        CompletedTaskCleanupSettings.setEnabled(appContext, enabled)
        _completedTaskCleanupEnabled.value = enabled
        logOperation("設定変更", "完了タスクの自動削除: ${if (enabled) "有効" else "無効"}")
        viewModelScope.launch { CompletedTaskCleanup.run(appContext) }
    }

    /**
     * 完了から自動削除までの日数を変更する。1〜3650日の範囲に丸められる。日数を短くした場合に
     * 新たに対象となった完了済みタスクは、次回の定期実行を待たずにすぐ削除する。
     */
    fun setCompletedTaskCleanupRetentionDays(days: Int) {
        CompletedTaskCleanupSettings.setRetentionDays(appContext, days)
        _completedTaskCleanupRetentionDays.value = CompletedTaskCleanupSettings.getRetentionDays(appContext)
        logOperation("設定変更", "完了タスクの自動削除までの日数: ${_completedTaskCleanupRetentionDays.value}日")
        viewModelScope.launch { CompletedTaskCleanup.run(appContext) }
    }

    /**
     * クラウド自動バックアップの保存先フォルダを設定する。takePersistableUriPermissionは
     * SAFプロバイダが永続権限を提供しない場合等にSecurityExceptionを送出しうるため、ここで
     * 捕捉し戻り値のfalseで呼び出し元(UI)に伝える。例外を外へ伝播させたままだと、フォルダ選択
     * 結果のコールバックからそのままアプリがクラッシュしてしまうため。
     */
    fun setCloudBackupFolder(uri: Uri): Boolean {
        val previousUri = CloudBackupSettings.getFolderUri(appContext)
        // 先に新しいURIの権限を取得・保存する。失敗した場合は以降の設定保存・旧URIの権限解放は
        // 行わず、既存の保存先(previousUri)は使える状態のまま保たれる。
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            return false
        }
        CloudBackupSettings.setFolderUri(appContext, uri)
        _cloudBackupFolderUri.value = uri
        logOperation("設定変更", "クラウドバックアップの保存先: ${Uri.decode(uri.lastPathSegment ?: uri.toString())}")
        // 新しい権限の取得・保存が成功した後にのみ、以前選択していたフォルダの権限を解放する。
        // 解放せずに保存先を切り替え続けると、永続化されたURI権限が増え続け、Android側の上限
        // (端末により異なるが歴史的に128件)に達して以降のtakePersistableUriPermissionが
        // SecurityExceptionで失敗するようになるため。
        if (previousUri != null && previousUri != uri) {
            try {
                appContext.contentResolver.releasePersistableUriPermission(
                    previousUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 既に解放済み・提供元アプリのアンインストール等で権限が残っていない場合。
            }
        }
        return true
    }

    /** クラウド自動バックアップの有効/無効を切り替える。保存先フォルダが未設定の場合は有効化できない。 */
    fun setCloudBackupEnabled(enabled: Boolean) {
        if (enabled && CloudBackupSettings.getFolderUri(appContext) == null) return
        CloudBackupSettings.setEnabled(appContext, enabled)
        _cloudBackupEnabled.value = enabled
        logOperation("設定変更", "クラウド自動バックアップ: ${if (enabled) "有効" else "無効"}")
        if (enabled) {
            // 有効化のたびに現在の保存時刻設定で初回遅延を計算し直す(ensureScheduledのKEEPだと
            // 過去に別の時刻設定で登録済みの定期実行が残っていた場合に反映されないため)。
            CloudBackupScheduler.reschedule(appContext)
        } else {
            CloudBackupScheduler.cancel(appContext)
        }
    }

    /** クラウド自動バックアップの保持件数を変更する。1〜90件の範囲に丸められる。 */
    fun setCloudBackupRetentionCount(count: Int) {
        CloudBackupSettings.setRetentionCount(appContext, count)
        _cloudBackupRetentionCount.value = CloudBackupSettings.getRetentionCount(appContext)
        logOperation("設定変更", "クラウドバックアップの保持件数: ${_cloudBackupRetentionCount.value}件")
    }

    /** 期限クイック選択「当日朝/昼/夕/夜」のうち[slot]の時刻を変更する。 */
    fun setDueTimePreset(slot: DueTimeSlot, hour: Int, minute: Int) {
        DueTimePresetSettings.setTime(appContext, slot, hour, minute)
        _dueTimePresets.value = DueTimePresetSettings.getAll(appContext)
        logOperation(
            "設定変更",
            "期限のクイック選択(${slot.label}): ${_dueTimePresets.value[slot]?.let(::formatMinuteOfDay).orEmpty()}"
        )
    }

    /**
     * クラウド自動バックアップの保存時刻(時:分)を変更する。有効化中であれば、次回実行時刻を
     * 新しい設定に基づいてすぐに再計算する([CloudBackupScheduler.reschedule])。無効化中は
     * 設定を保存するだけで、実際のスケジュール登録は次回有効化時に行われる。
     */
    fun setCloudBackupTime(hour: Int, minute: Int) {
        CloudBackupSettings.setBackupTime(appContext, hour, minute)
        _cloudBackupHour.value = CloudBackupSettings.getBackupHour(appContext)
        _cloudBackupMinute.value = CloudBackupSettings.getBackupMinute(appContext)
        logOperation(
            "設定変更",
            "クラウドバックアップの保存時刻: %02d:%02d".format(_cloudBackupHour.value, _cloudBackupMinute.value)
        )
        if (_cloudBackupEnabled.value) {
            CloudBackupScheduler.reschedule(appContext)
        }
    }

    /**
     * 設定画面の「今すぐバックアップ」ボタンから呼ぶ。定期実行([CloudBackupWorker])と同じ処理を
     * 即座に1回行う。実行結果の[cloudBackupLastBackupAt]/[cloudBackupLastResult]への反映は
     * [cloudBackupPrefsListener]がSharedPreferencesの変更を検知して自動的に行う。
     */
    fun runCloudBackupNow(onResult: (Boolean) -> Unit = {}) {
        if (_cloudBackupRunning.value) return
        _cloudBackupRunning.value = true
        viewModelScope.launch {
            try {
                val success = CloudBackupRunner.run(appContext, OperationLogSource.APP)
                onResult(success)
            } finally {
                _cloudBackupRunning.value = false
            }
        }
    }

    companion object {
        /**
         * [selectedListId]に渡すと「リスト未登録」(`listId == null`のタスクのみ)を表す特別な値。
         * Roomの`TaskList.id`は`autoGenerate`で1から始まるため、負の値であれば実際のリストIDと
         * 衝突しない。`null`は引き続き「すべて」を表す。
         */
        const val UNASSIGNED_LIST_ID = -1L

        /**
         * タスク・サブタスク登録時にデフォルトで設定する期限までの猶予(1時間)。
         * [com.toshi0907.oboetotte.share.ShareReceiverActivity]も共有によるタスク追加時に
         * 同じデフォルト値を使うため公開している。
         */
        const val DEFAULT_DUE_DELAY_MILLIS = 60 * 60 * 1000L
    }
}

private fun vibrationSummary(onMs: Long, offMs: Long, durationMs: Long): String =
    "(オン${onMs}ms・オフ${offMs}ms・継続${durationMs}ms)"

private fun LocationTrackingMode.logLabel(): String = when (this) {
    LocationTrackingMode.GEOFENCING_API -> "Geofencing API"
    LocationTrackingMode.CONTINUOUS_TRACKING -> "連続追跡方式"
}

/**
 * タスク一覧の表示フィルタ。複数選択時はOR条件(いずれか1つでも合致すれば表示)。
 * トップレベルのタスクにのみ適用し、判定結果に応じてサブタスクツリーごと表示/非表示を切り替える
 * (`selectedListId`/`showCompleted`と同じ考え方。サブタスク単体の合致では表示されない)。
 */
enum class TaskDisplayFilter {
    /** URLが設定されている項目 */
    HAS_URL,

    /** 未完了かつ期限が今日、または期限切れの項目 */
    DUE_TODAY_OR_OVERDUE
}

/**
 * [task]が表示フィルタ([this])に合致するか。メイン画面(`TaskScreen`)とホーム画面ウィジェットの
 * 両方から共通で使う判定ロジック。[nowMillis]は[DUE_TODAY_OR_OVERDUE]の判定基準となる
 * 「現在時刻」で、呼び出し側が用意する([Task.isOverdue]/[Task.isDueToday]と同じ考え方)。
 */
fun TaskDisplayFilter.matches(task: Task, nowMillis: Long): Boolean = when (this) {
    TaskDisplayFilter.HAS_URL -> !task.url.isNullOrBlank()
    TaskDisplayFilter.DUE_TODAY_OR_OVERDUE -> task.isOverdue(nowMillis) || task.isDueToday(nowMillis)
}

/**
 * [task]がリストの絞り込み([listId]。`null`は「すべて」、[TaskViewModel.UNASSIGNED_LIST_ID]は
 * 「リスト未登録」)に合致するか。メイン画面の一覧とカレンダー表示の両方から共通で使う。
 */
fun Task.matchesListFilter(listId: Long?): Boolean = when (listId) {
    null -> true
    TaskViewModel.UNASSIGNED_LIST_ID -> this.listId == null
    else -> this.listId == listId
}
