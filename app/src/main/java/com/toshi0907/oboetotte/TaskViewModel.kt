package com.toshi0907.oboetotte

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toshi0907.oboetotte.attachment.AttachmentStorage
import com.toshi0907.oboetotte.backup.CloudBackupResult
import com.toshi0907.oboetotte.backup.CloudBackupRunner
import com.toshi0907.oboetotte.backup.CloudBackupScheduler
import com.toshi0907.oboetotte.backup.CloudBackupSettings
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.LocationUpdateLog
import com.toshi0907.oboetotte.data.NotificationLog
import com.toshi0907.oboetotte.data.SavedLocation
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskAttachment
import com.toshi0907.oboetotte.data.TaskList
import com.toshi0907.oboetotte.data.attachmentGroupId
import com.toshi0907.oboetotte.notification.LocationReminderManager
import com.toshi0907.oboetotte.notification.LocationTrackingMode
import com.toshi0907.oboetotte.notification.LocationTrackingSettings
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.update.AppUpdateCheckSettings
import com.toshi0907.oboetotte.widget.refreshTaskWidget
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
    val memo: String?,
    val autoSnoozeMinutes: Long?,
    val notifyOnlyMode: Boolean
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

    private val _locationTrackingMode =
        MutableStateFlow(LocationTrackingSettings.getMode(application))
    val locationTrackingMode: StateFlow<LocationTrackingMode> = _locationTrackingMode

    private val _locationTrackingIntervalMinutes =
        MutableStateFlow(LocationTrackingSettings.getIntervalMinutes(application))
    val locationTrackingIntervalMinutes: StateFlow<Int> = _locationTrackingIntervalMinutes

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
            refreshTaskWidget(appContext)
        }
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            if (task.isDone) {
                taskDao.setDone(task.id, false)
                val updated = task.copy(isDone = false)
                ReminderScheduler.schedule(appContext, updated)
                LocationReminderManager.register(appContext, updated)
                refreshTaskWidget(appContext)
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
                notifyOnlyMode = edits.notifyOnlyMode
            )
            taskDao.update(updated)
            ReminderScheduler.schedule(appContext, updated)
            LocationReminderManager.register(appContext, updated)
            refreshTaskWidget(appContext)
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            val groupId = task.attachmentGroupId()
            taskDao.delete(task)
            ReminderScheduler.cancel(appContext, task.id)
            LocationReminderManager.unregister(appContext, task.id)
            // 添付ファイルは繰り返しシリーズ全体で共有しているため、同じシリーズの他のインスタンスが
            // まだ残っている場合は削除しない(まだ参照されているため)。
            if (taskDao.countByAttachmentGroup(groupId) == 0) {
                val attachmentsToDelete = taskAttachmentDao.getForTask(groupId)
                taskAttachmentDao.deleteForTask(groupId)
                withContext(Dispatchers.IO) {
                    attachmentsToDelete.forEach { AttachmentStorage.delete(appContext, it.storedFileName) }
                }
            }
            refreshTaskWidget(appContext)
        }
    }

    /**
     * [uri]の内容を端末内にコピーし、[task]の添付ファイルとして登録する。繰り返しタスクの場合、
     * [Task.attachmentGroupId]を使って同じ繰り返しシリーズの全インスタンスと共有する。
     */
    fun addAttachment(task: Task, uri: Uri) {
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) {
                AttachmentStorage.copyToStorage(appContext, uri)
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
            refreshTaskWidget(appContext)
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

    /** 位置情報リマインダーの確認方式を切り替える(Geofencing API/連続追跡方式)。 */
    fun setLocationTrackingMode(mode: LocationTrackingMode) {
        viewModelScope.launch {
            LocationReminderManager.switchMode(appContext, mode)
            _locationTrackingMode.value = mode
        }
    }

    /** 連続追跡方式の更新頻度(分)を変更する。1〜60分の範囲に丸められる。 */
    fun setLocationTrackingIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            LocationReminderManager.updateContinuousTrackingInterval(appContext, minutes)
            _locationTrackingIntervalMinutes.value = LocationTrackingSettings.getIntervalMinutes(appContext)
        }
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
        viewModelScope.launch {
            val success = CloudBackupRunner.run(appContext)
            onResult(success)
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
