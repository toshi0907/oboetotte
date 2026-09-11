package com.toshi0907.oboetotte

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.toshi0907.oboetotte.attachment.AttachmentStorage
import com.toshi0907.oboetotte.backup.BackupManager
import com.toshi0907.oboetotte.data.SavedLocation
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskAttachment
import com.toshi0907.oboetotte.data.TaskList
import com.toshi0907.oboetotte.notification.LocationReminderManager
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.ui.theme.OboetotteTheme
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [MainActivity]がナビゲーションライブラリ無しで切り替える2画面。 */
private enum class MainScreen { Tasks, Settings }

class MainActivity : ComponentActivity() {
    private val taskViewModel: TaskViewModel by viewModels()

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒否されてもアプリは通常通り使えるため、明示的なハンドリングは不要 */ }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    BackupManager.export(this@MainActivity, uri)
                    Toast.makeText(this@MainActivity, "エクスポートしました", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "エクスポートに失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    BackupManager.import(this@MainActivity, uri)
                    Toast.makeText(this@MainActivity, "インポートしました", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "インポートに失敗しました。ファイル形式を確認してください", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            OboetotteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val tasks by taskViewModel.tasks.collectAsState()
                    val allTasks by taskViewModel.allTasks.collectAsState()
                    val lists by taskViewModel.lists.collectAsState()
                    val savedLocations by taskViewModel.savedLocations.collectAsState()
                    val attachments by taskViewModel.attachments.collectAsState()
                    val selectedListId by taskViewModel.selectedListId.collectAsState()
                    val showCompleted by taskViewModel.showCompleted.collectAsState()
                    val context = LocalContext.current
                    val lifecycleOwner = LocalLifecycleOwner.current
                    var currentScreen by remember { mutableStateOf(MainScreen.Tasks) }
                    var exactAlarmPermissionGranted by remember {
                        mutableStateOf(ReminderScheduler.canScheduleExactAlarms(context))
                    }
                    var locationPermissionGranted by remember {
                        mutableStateOf(LocationReminderManager.hasLocationPermission(context))
                    }
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                exactAlarmPermissionGranted =
                                    ReminderScheduler.canScheduleExactAlarms(context)
                                locationPermissionGranted =
                                    LocationReminderManager.hasLocationPermission(context)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }
                    when (currentScreen) {
                        MainScreen.Tasks -> TaskScreen(
                            tasks = tasks,
                            allTasks = allTasks,
                            lists = lists,
                            selectedListId = selectedListId,
                            onSelectList = taskViewModel::selectList,
                            showCompleted = showCompleted,
                            onSetShowCompleted = taskViewModel::setShowCompleted,
                            onAddTask = taskViewModel::addTask,
                            onToggleDone = taskViewModel::toggleDone,
                            onUpdateTask = taskViewModel::updateTask,
                            onDeleteTask = taskViewModel::deleteTask,
                            onAddSubtask = taskViewModel::addSubtask,
                            savedLocations = savedLocations,
                            allAttachments = attachments,
                            onAddAttachment = taskViewModel::addAttachment,
                            onDeleteAttachment = taskViewModel::deleteAttachment,
                            showExactAlarmBanner = !exactAlarmPermissionGranted,
                            onRequestExactAlarmPermission = {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            },
                            locationPermissionGranted = locationPermissionGranted,
                            onRequestLocationSettings = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            },
                            onOpenSettings = { currentScreen = MainScreen.Settings },
                            modifier = Modifier.padding(innerPadding)
                        )
                        MainScreen.Settings -> SettingsScreen(
                            lists = lists,
                            onAddList = taskViewModel::addList,
                            onRenameList = taskViewModel::renameList,
                            onDeleteList = taskViewModel::deleteList,
                            savedLocations = savedLocations,
                            onAddSavedLocation = taskViewModel::addSavedLocation,
                            onUpdateSavedLocation = taskViewModel::updateSavedLocation,
                            onDeleteSavedLocation = taskViewModel::deleteSavedLocation,
                            allTasks = allTasks,
                            onSendTestNotification = {
                                val scheduled = ReminderScheduler.scheduleTestNotification(context)
                                val message = if (scheduled) {
                                    "${ReminderScheduler.TEST_DELAY_SECONDS}秒後にテスト通知が届きます"
                                } else {
                                    "「アラームとリマインダー」の権限が無いため送信できません"
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            },
                            onExportRequested = {
                                val zoned = ZonedDateTime.now()
                                val fileName = "oboetotte_backup_%04d%02d%02d_%02d%02d%02d.zip".format(
                                    zoned.year, zoned.monthValue, zoned.dayOfMonth,
                                    zoned.hour, zoned.minute, zoned.second
                                )
                                exportBackupLauncher.launch(fileName)
                            },
                            onImportRequested = {
                                // ZIP(添付あり)・旧形式のプレーンJSON(添付なし)の両方を受け付ける。
                                // 実際の形式判定はBackupManager.import側でマジックナンバーを見て行う。
                                importBackupLauncher.launch(arrayOf("*/*"))
                            },
                            onBack = { currentScreen = MainScreen.Tasks },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}

private fun combineDateAndTime(dateMillisUtc: Long, hour: Int, minute: Int): Long {
    val localDate = Instant.ofEpochMilli(dateMillisUtc).atZone(ZoneId.of("UTC")).toLocalDate()
    return localDate.atTime(LocalTime.of(hour, minute))
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

// DatePickerの`initialSelectedDateMillis`はUTC 0時基準の値を期待するため、
// 端末のローカルタイムゾーンでの「今日」をそのままepoch millisにするとズレる。
private fun todayAsDatePickerMillis(): Long {
    return LocalDate.now(ZoneId.systemDefault())
        .atStartOfDay(ZoneId.of("UTC"))
        .toInstant()
        .toEpochMilli()
}

private fun formatDueAt(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return "%04d/%02d/%02d %02d:%02d".format(
        zoned.year,
        zoned.monthValue,
        zoned.dayOfMonth,
        zoned.hour,
        zoned.minute
    )
}

private val WEEKDAY_LABELS = listOf(1 to "月", 2 to "火", 3 to "水", 4 to "木", 5 to "金", 6 to "土", 7 to "日")

/** [EditTaskDialog]の期限クイック選択チップ用。ラベルと現在時刻からの分数のペア。 */
private val DUE_QUICK_OPTIONS = listOf(
    "5分後" to 5L,
    "15分後" to 15L,
    "30分後" to 30L,
    "1時間後" to 60L,
    "3時間後" to 180L,
    "6時間後" to 360L
)

private fun radiusLabel(meters: Int): String =
    if (meters >= 1000) "${meters / 1000}km" else "${meters}m"

private fun locationLabel(task: Task): String? {
    val name = task.locationName ?: return null
    val radius = task.radiusMeters ?: return null
    val timing = when {
        task.notifyOnArrival && task.notifyOnDeparture -> "到着/離脱"
        task.notifyOnArrival -> "到着時"
        task.notifyOnDeparture -> "離脱時"
        else -> return null
    }
    return "📍$name ${radiusLabel(radius)}・$timing"
}

private fun repeatRuleLabel(task: Task): String? {
    val rule = task.repeatRule ?: return null
    return when (rule) {
        RepeatRule.DAILY -> "毎日"
        RepeatRule.WEEKLY -> "毎週"
        RepeatRule.WEEKLY_DAYS -> {
            val days = RepeatRule.parseDaysOfWeek(task.repeatDaysOfWeek)
            val labels = WEEKDAY_LABELS.filter { it.first in days }.joinToString("・") { it.second }
            "毎週($labels)"
        }
        RepeatRule.MONTHLY -> "毎月"
        else -> rule
    }
}

/** スキーマ(http(s)://)が省略された入力(例: "example.com")でも通知から正しく開けるよう補う。 */
private fun normalizeUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskScreen(
    tasks: List<Task>,
    allTasks: List<Task>,
    lists: List<TaskList>,
    selectedListId: Long?,
    onSelectList: (Long?) -> Unit,
    showCompleted: Boolean,
    onSetShowCompleted: (Boolean) -> Unit,
    onAddTask: (String) -> Unit,
    onToggleDone: (Task) -> Unit,
    onUpdateTask: (Task, TaskEdits) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onAddSubtask: (Task, String) -> Unit,
    savedLocations: List<SavedLocation> = emptyList(),
    allAttachments: List<TaskAttachment> = emptyList(),
    onAddAttachment: (Task, Uri) -> Unit = { _, _ -> },
    onDeleteAttachment: (TaskAttachment) -> Unit = {},
    showExactAlarmBanner: Boolean = false,
    onRequestExactAlarmPermission: () -> Unit = {},
    locationPermissionGranted: Boolean = true,
    onRequestLocationSettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var viewingTask by remember { mutableStateOf<Task?>(null) }
    var deletingTask by remember { mutableStateOf<Task?>(null) }
    val hasLocationTasks = allTasks.any {
        !it.isDone && it.latitude != null && it.longitude != null && it.radiusMeters != null &&
            (it.notifyOnArrival || it.notifyOnDeparture)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(text = "Oboetotte", style = MaterialTheme.typography.headlineMedium)

            if (showExactAlarmBanner) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "リマインダー通知を正確な時刻に届けるには、「アラームとリマインダー」の権限が必要です。",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = onRequestExactAlarmPermission) {
                            Text("設定を開く")
                        }
                    }
                }
            }

            if (hasLocationTasks && !locationPermissionGranted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "位置による通知を届けるには、位置情報の権限(常に許可)が必要です。",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = onRequestLocationSettings) {
                            Text("設定を開く")
                        }
                    }
                }
            }

            Text(
                text = "リスト",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedListId == null,
                        onClick = { onSelectList(null) },
                        label = { Text("すべて") }
                    )
                }
                items(lists, key = { it.id }) { list ->
                    FilterChip(
                        selected = selectedListId == list.id,
                        onClick = { onSelectList(list.id) },
                        label = { Text(list.name) }
                    )
                }
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = showCompleted,
                        onClick = { onSetShowCompleted(!showCompleted) },
                        label = { Text("完了済みを表示") }
                    )
                }
                item {
                    AssistChip(
                        onClick = onOpenSettings,
                        label = { Text("設定") }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("タスクを入力") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        onAddTask(input)
                        input = ""
                    })
                )
                Button(
                    onClick = {
                        onAddTask(input)
                        input = ""
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("追加")
                }
            }

            LazyColumn {
                items(tasks, key = { it.id }) { task ->
                    TaskTreeRow(
                        task = task,
                        allTasks = allTasks,
                        depth = 0,
                        onToggleDone = onToggleDone,
                        onViewTask = { viewingTask = it },
                        onDeleteTask = { deletingTask = it }
                    )
                }
            }
        }
    }

    viewingTask?.let { task ->
        TaskDetailDialog(
            task = task,
            allTasks = allTasks,
            lists = lists,
            savedLocations = savedLocations,
            allAttachments = allAttachments,
            onToggleDone = onToggleDone,
            onUpdateTask = onUpdateTask,
            onAddSubtask = onAddSubtask,
            onAddAttachment = onAddAttachment,
            onDeleteAttachment = onDeleteAttachment,
            onDismiss = { viewingTask = null }
        )
    }

    deletingTask?.let { task ->
        DeleteTaskDialog(
            task = task,
            onConfirm = {
                onDeleteTask(task)
                deletingTask = null
            },
            onDismiss = { deletingTask = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskTreeRow(
    task: Task,
    allTasks: List<Task>,
    depth: Int,
    onToggleDone: (Task) -> Unit,
    onViewTask: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit
) {
    val children = allTasks.filter { it.parentTaskId == task.id }
    val isOverdue = task.dueAt != null &&
        !task.isDone &&
        task.dueAt < System.currentTimeMillis()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onViewTask(task) },
                    onLongClick = { onDeleteTask(task) }
                )
                .padding(start = (depth * 20).dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = task.isDone,
                onCheckedChange = { onToggleDone(task) }
            )
            Column {
                Text(
                    text = task.title,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                    color = if (task.isDone) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (task.dueAt != null || task.repeatRule != null || locationLabel(task) != null) {
                    Text(
                        text = listOfNotNull(
                            task.dueAt?.let { formatDueAt(it) },
                            repeatRuleLabel(task),
                            locationLabel(task)
                        ).joinToString(" ・ "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOverdue) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (children.isNotEmpty()) {
                    Text(
                        text = "${children.count { it.isDone }}/${children.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        children.forEach { child ->
            TaskTreeRow(
                task = child,
                allTasks = allTasks,
                depth = depth + 1,
                onToggleDone = onToggleDone,
                onViewTask = onViewTask,
                onDeleteTask = onDeleteTask
            )
        }
    }
}

/**
 * タスク行タップで最初に開く読み取り専用の確認ダイアログ。[EditTaskDialog]と同じ項目
 * (タイトル・リスト・URL・期限・繰り返し・位置情報・メモ・添付ファイル・サブタスク一覧)を
 * すべて表示するが、値を書き換える操作は上部の「編集」ボタンから[EditTaskDialog]を開いた
 * 先でのみ行う(添付ファイルの追加・削除、サブタスクの追加もここには置かない)。チェックボックスに
 * よる完了/未完了の切り替えと添付ファイルを開く操作は、一覧行と同様にここでも直接行える。
 * サブタスク行をタップすると、そのサブタスクの[TaskDetailDialog]がこの上に重ねて開く
 * ([EditTaskDialog]の`nestedTask`と同じ、自分自身を再帰的に呼び出す入れ子構造)。
 * 表示中に編集が保存されても`task`引数自体は古いスナップショットのままなので、
 * 常に`allTasks`から最新の値(`current`)を探し直して表示する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailDialog(
    task: Task,
    allTasks: List<Task>,
    lists: List<TaskList> = emptyList(),
    savedLocations: List<SavedLocation> = emptyList(),
    allAttachments: List<TaskAttachment> = emptyList(),
    onToggleDone: (Task) -> Unit,
    onUpdateTask: (Task, TaskEdits) -> Unit,
    onAddSubtask: (Task, String) -> Unit,
    onAddAttachment: (Task, Uri) -> Unit = { _, _ -> },
    onDeleteAttachment: (TaskAttachment) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val current = allTasks.find { it.id == task.id } ?: task
    var editing by remember(task.id) { mutableStateOf(false) }
    var nestedViewTask by remember { mutableStateOf<Task?>(null) }
    val subtasks = allTasks.filter { it.parentTaskId == current.id }
    val attachments = allAttachments.filter { it.taskId == current.id }
    val listName = lists.find { it.id == current.listId }?.name

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("タスクの詳細")
                TextButton(onClick = { editing = true }) {
                    Text("編集")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = current.isDone,
                        onCheckedChange = { onToggleDone(current) }
                    )
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (current.isDone) TextDecoration.LineThrough else null
                    )
                }
                if (listName != null) {
                    Text(text = "リスト: $listName", style = MaterialTheme.typography.bodyMedium)
                }
                current.url?.let { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "開けるアプリが見つかりません", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                Text(text = current.dueAt?.let { formatDueAt(it) } ?: "期限なし")
                repeatRuleLabel(current)?.let { Text(text = "繰り返し: $it") }
                locationLabel(current)?.let { Text(text = it) }
                if (!current.memo.isNullOrBlank()) {
                    Text(text = "メモ", style = MaterialTheme.typography.titleSmall)
                    Text(text = current.memo)
                }
                if (attachments.isNotEmpty()) {
                    Text(text = "添付ファイル", style = MaterialTheme.typography.titleSmall)
                    attachments.forEach { attachment ->
                        AttachmentRow(
                            attachment = attachment,
                            onOpen = {
                                try {
                                    context.startActivity(AttachmentStorage.openIntent(context, attachment))
                                } catch (e: ActivityNotFoundException) {
                                    Toast.makeText(context, "開けるアプリが見つかりません", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = {},
                            showDelete = false
                        )
                        AttachmentPreview(attachment)
                    }
                }
                if (subtasks.isNotEmpty()) {
                    Text(text = "サブタスク", style = MaterialTheme.typography.titleSmall)
                    subtasks.forEach { subtask ->
                        SubtaskTreeRow(
                            task = subtask,
                            allTasks = allTasks,
                            depth = 0,
                            onToggleDone = onToggleDone,
                            onOpenTask = { nestedViewTask = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )

    if (editing) {
        EditTaskDialog(
            task = current,
            allTasks = allTasks,
            lists = lists,
            savedLocations = savedLocations,
            allAttachments = allAttachments,
            onConfirm = { t, edits ->
                onUpdateTask(t, edits)
                editing = false
            },
            onAddSubtask = onAddSubtask,
            onToggleDone = onToggleDone,
            onAddAttachment = onAddAttachment,
            onDeleteAttachment = onDeleteAttachment,
            onDismiss = { editing = false }
        )
    }

    nestedViewTask?.let { nested ->
        TaskDetailDialog(
            task = nested,
            allTasks = allTasks,
            lists = lists,
            savedLocations = savedLocations,
            allAttachments = allAttachments,
            onToggleDone = onToggleDone,
            onUpdateTask = onUpdateTask,
            onAddSubtask = onAddSubtask,
            onAddAttachment = onAddAttachment,
            onDeleteAttachment = onDeleteAttachment,
            onDismiss = { nestedViewTask = null }
        )
    }
}

/**
 * メイン画面(タスク一覧)を置き換える形で表示する設定用の全画面。ナビゲーション用のライブラリは
 * 導入せず、[MainActivity]側の`currentScreen`状態で[TaskScreen]とどちらを描画するか切り替える
 * だけのシンプルな構成。[BackHandler]でシステムの「戻る」操作もタスク一覧への復帰として扱う。
 * リスト編集・場所編集・テスト通知・位置情報デバッグなど、以前メイン画面のチップに散らばっていた
 * 補助的な操作をここに集約している。
 */
@Composable
fun SettingsScreen(
    lists: List<TaskList>,
    onAddList: (String) -> Unit,
    onRenameList: (TaskList, String) -> Unit,
    onDeleteList: (TaskList) -> Unit,
    savedLocations: List<SavedLocation>,
    onAddSavedLocation: (String, Double, Double, Int) -> Unit,
    onUpdateSavedLocation: (SavedLocation, String, Int) -> Unit,
    onDeleteSavedLocation: (SavedLocation) -> Unit,
    allTasks: List<Task>,
    onSendTestNotification: () -> Unit,
    onExportRequested: () -> Unit,
    onImportRequested: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showManageLists by remember { mutableStateOf(false) }
    var showManageLocations by remember { mutableStateOf(false) }
    var showLocationDebug by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← 戻る")
                }
                Text(text = "設定", style = MaterialTheme.typography.headlineMedium)
            }

            Text(
                text = "リスト",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
            TextButton(onClick = { showManageLists = true }) {
                Text("リストを編集")
            }

            Text(
                text = "場所",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = { showManageLocations = true }) {
                Text("場所を編集")
            }

            Text(
                text = "バックアップ",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "タスクデータをファイルにバックアップしたり、バックアップから復元したりできます。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "インポートすると、現在のタスクデータはすべてインポートしたファイルの内容に置き換わります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row {
                TextButton(onClick = onExportRequested) {
                    Text("エクスポート")
                }
                TextButton(onClick = onImportRequested) {
                    Text("インポート")
                }
            }

            Text(
                text = "デバッグ",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = onSendTestNotification) {
                Text("テスト通知")
            }
            TextButton(onClick = { showLocationDebug = true }) {
                Text("位置情報デバッグ")
            }
        }
    }

    if (showManageLists) {
        ManageListsDialog(
            lists = lists,
            onAddList = onAddList,
            onRenameList = onRenameList,
            onDeleteList = onDeleteList,
            onDismiss = { showManageLists = false }
        )
    }

    if (showManageLocations) {
        ManageLocationsDialog(
            savedLocations = savedLocations,
            onAddSavedLocation = onAddSavedLocation,
            onUpdateSavedLocation = onUpdateSavedLocation,
            onDeleteSavedLocation = onDeleteSavedLocation,
            onDismiss = { showManageLocations = false }
        )
    }

    if (showLocationDebug) {
        LocationDebugDialog(
            allTasks = allTasks,
            onDismiss = { showLocationDebug = false }
        )
    }
}

@Composable
fun LocationDebugDialog(
    allTasks: List<Task>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentLocation by remember { mutableStateOf<GeocodeResult?>(null) }
    var isLocating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val foregroundGranted = LocationReminderManager.hasForegroundPermission(context)
    val backgroundGranted = LocationReminderManager.hasBackgroundPermission(context)
    val locationTasks = allTasks.filter {
        !it.isDone && it.latitude != null && it.longitude != null && it.radiusMeters != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("位置情報デバッグ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "権限: 前面(${if (foregroundGranted) "許可" else "未許可"}) / " +
                        "常に許可(${if (backgroundGranted) "許可" else "未許可"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (foregroundGranted && backgroundGranted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                if (!backgroundGranted) {
                    Text(
                        text = "「常に許可」が無いと、アプリを閉じていてもジオフェンスが発火しません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                TextButton(onClick = {
                    isLocating = true
                    errorText = null
                    coroutineScope.launch {
                        val result = getCurrentLocationResult(context)
                        isLocating = false
                        if (result != null) {
                            currentLocation = result
                        } else {
                            errorText = "現在地を取得できませんでした"
                        }
                    }
                }) {
                    Text(if (isLocating) "取得中…" else "現在地を取得")
                }
                currentLocation?.let { location ->
                    Text(
                        text = "現在地: %.5f, %.5f".format(location.latitude, location.longitude),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                errorText?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Text(text = "位置情報を設定したタスク", style = MaterialTheme.typography.titleSmall)
                if (locationTasks.isEmpty()) {
                    Text(
                        "位置情報が設定された未完了タスクはありません",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    locationTasks.forEach { task ->
                        val lat = task.latitude!!
                        val lng = task.longitude!!
                        val radius = task.radiusMeters!!
                        val timing = listOfNotNull(
                            if (task.notifyOnArrival) "到着時" else null,
                            if (task.notifyOnDeparture) "離脱時" else null
                        ).joinToString("/")
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            Text(task.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "登録座標: %.5f, %.5f ・ 半径%s ・ %s".format(
                                    lat,
                                    lng,
                                    radiusLabel(radius),
                                    timing
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            currentLocation?.let { current ->
                                val results = FloatArray(1)
                                Location.distanceBetween(current.latitude, current.longitude, lat, lng, results)
                                val distance = results[0]
                                val inside = distance <= radius
                                Text(
                                    text = "現在地からの距離: ${distance.toInt()}m (${if (inside) "圏内" else "圏外"})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (inside) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

@Composable
fun ManageListsDialog(
    lists: List<TaskList>,
    onAddList: (String) -> Unit,
    onRenameList: (TaskList, String) -> Unit,
    onDeleteList: (TaskList) -> Unit,
    onDismiss: () -> Unit
) {
    var newListName by remember { mutableStateOf("") }
    var renamingListId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リストを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lists.forEach { list ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (renamingListId == list.id) {
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            TextButton(onClick = {
                                onRenameList(list, renameText)
                                renamingListId = null
                            }) {
                                Text("保存")
                            }
                        } else {
                            Text(
                                text = list.name,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        renamingListId = list.id
                                        renameText = list.name
                                    }
                            )
                            TextButton(onClick = { onDeleteList(list) }) {
                                Text("削除")
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("新しいリスト名") },
                        singleLine = true
                    )
                    TextButton(onClick = {
                        onAddList(newListName)
                        newListName = ""
                    }) {
                        Text("追加")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

@Composable
fun SubtaskTreeRow(
    task: Task,
    allTasks: List<Task>,
    depth: Int,
    onToggleDone: (Task) -> Unit,
    onOpenTask: (Task) -> Unit
) {
    val children = allTasks.filter { it.parentTaskId == task.id }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 20).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Checkbox(
                checked = task.isDone,
                onCheckedChange = { onToggleDone(task) }
            )
            Column(modifier = Modifier.clickable { onOpenTask(task) }) {
                Text(
                    text = task.title,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null
                )
                if (children.isNotEmpty()) {
                    Text(
                        text = "${children.count { it.isDone }}/${children.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        children.forEach { child ->
            SubtaskTreeRow(
                task = child,
                allTasks = allTasks,
                depth = depth + 1,
                onToggleDone = onToggleDone,
                onOpenTask = onOpenTask
            )
        }
    }
}

private enum class LocationSourceTab { ADDRESS, CURRENT, SAVED }

private enum class LocationInputMode { ADDRESS, CURRENT }

/**
 * 住所検索または現在地取得による位置の入力UI。[EditTaskDialog]と[ManageLocationsDialog]の
 * 両方から使う共通部品(登録済みの場所から選ぶタブは呼び出し側でそれぞれ個別に描画する)。
 */
@Composable
private fun LocationPicker(
    mode: LocationInputMode,
    addressInput: String,
    onAddressInputChange: (String) -> Unit,
    onResolved: (GeocodeResult) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isLocating by remember { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            isLocating = true
            coroutineScope.launch {
                val result = getCurrentLocationResult(context)
                isLocating = false
                if (result != null) onResolved(result) else onError("現在地を取得できませんでした")
            }
        }
    }

    when (mode) {
        LocationInputMode.ADDRESS -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = addressInput,
                onValueChange = onAddressInputChange,
                modifier = Modifier.weight(1f),
                label = { Text("住所または場所名") },
                singleLine = true
            )
            TextButton(onClick = {
                val query = addressInput
                coroutineScope.launch {
                    val result = geocodeAddress(context, query)
                    if (result != null) {
                        onResolved(result)
                    } else {
                        onError("住所が見つかりませんでした。もう少し詳しい住所を入力してください。")
                    }
                }
            }) {
                Text("検索")
            }
        }
        LocationInputMode.CURRENT -> TextButton(onClick = {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                isLocating = true
                coroutineScope.launch {
                    val result = getCurrentLocationResult(context)
                    isLocating = false
                    if (result != null) onResolved(result) else onError("現在地を取得できませんでした")
                }
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }) {
            Text(if (isLocating) "取得中…" else "📍 現在地を取得")
        }
    }
}

@Composable
fun ManageLocationsDialog(
    savedLocations: List<SavedLocation>,
    onAddSavedLocation: (String, Double, Double, Int) -> Unit,
    onUpdateSavedLocation: (SavedLocation, String, Int) -> Unit,
    onDeleteSavedLocation: (SavedLocation) -> Unit,
    onDismiss: () -> Unit
) {
    var locationTab by remember { mutableStateOf(LocationInputMode.ADDRESS) }
    var addressInput by remember { mutableStateOf("") }
    var resolvedLocation by remember { mutableStateOf<GeocodeResult?>(null) }
    var locationSearchError by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var radiusMeters by remember { mutableStateOf(300) }
    var editingLocationId by remember { mutableStateOf<Long?>(null) }
    var editName by remember { mutableStateOf("") }
    var editRadiusMeters by remember { mutableStateOf(300) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("場所を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                savedLocations.forEach { location ->
                    if (editingLocationId == location.id) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(100, 300, 500, 1000).forEach { meters ->
                                    FilterChip(
                                        selected = editRadiusMeters == meters,
                                        onClick = { editRadiusMeters = meters },
                                        label = { Text(radiusLabel(meters)) }
                                    )
                                }
                            }
                            TextButton(onClick = {
                                onUpdateSavedLocation(location, editName, editRadiusMeters)
                                editingLocationId = null
                            }) {
                                Text("保存")
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        editingLocationId = location.id
                                        editName = location.name
                                        editRadiusMeters = location.radiusMeters
                                    }
                            ) {
                                Text(location.name)
                                Text(
                                    text = "%.5f, %.5f ・ %s".format(
                                        location.latitude,
                                        location.longitude,
                                        radiusLabel(location.radiusMeters)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onDeleteSavedLocation(location) }) {
                                Text("削除")
                            }
                        }
                    }
                }

                Text(text = "新しい場所を追加", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = locationTab == LocationInputMode.ADDRESS,
                        onClick = {
                            locationTab = LocationInputMode.ADDRESS
                            resolvedLocation = null
                            locationSearchError = null
                        },
                        label = { Text("住所で指定") }
                    )
                    FilterChip(
                        selected = locationTab == LocationInputMode.CURRENT,
                        onClick = {
                            locationTab = LocationInputMode.CURRENT
                            resolvedLocation = null
                            locationSearchError = null
                        },
                        label = { Text("現在地を使う") }
                    )
                }
                LocationPicker(
                    mode = locationTab,
                    addressInput = addressInput,
                    onAddressInputChange = { addressInput = it },
                    onResolved = {
                        resolvedLocation = it
                        newName = it.name
                        locationSearchError = null
                    },
                    onError = {
                        resolvedLocation = null
                        locationSearchError = it
                    }
                )
                resolvedLocation?.let { location ->
                    Text(
                        text = "✓ %.5f, %.5f".format(location.latitude, location.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                locationSearchError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (resolvedLocation != null) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("名称(例: 自宅・職場)") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(100, 300, 500, 1000).forEach { meters ->
                            FilterChip(
                                selected = radiusMeters == meters,
                                onClick = { radiusMeters = meters },
                                label = { Text(radiusLabel(meters)) }
                            )
                        }
                    }
                    TextButton(onClick = {
                        val location = resolvedLocation ?: return@TextButton
                        onAddSavedLocation(newName, location.latitude, location.longitude, radiusMeters)
                        resolvedLocation = null
                        addressInput = ""
                        newName = ""
                    }) {
                        Text("この場所を登録")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTaskDialog(
    task: Task,
    allTasks: List<Task>,
    lists: List<TaskList> = emptyList(),
    savedLocations: List<SavedLocation> = emptyList(),
    allAttachments: List<TaskAttachment> = emptyList(),
    onConfirm: (task: Task, edits: TaskEdits) -> Unit,
    onAddSubtask: (Task, String) -> Unit,
    onToggleDone: (Task) -> Unit,
    onAddAttachment: (Task, Uri) -> Unit = { _, _ -> },
    onDeleteAttachment: (TaskAttachment) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val attachments = allAttachments.filter { it.taskId == task.id }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> uris.forEach { onAddAttachment(task, it) } }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> uris.forEach { onAddAttachment(task, it) } }
    var showAttachmentSourceDialog by remember { mutableStateOf(false) }
    var title by remember(task.id) { mutableStateOf(task.title) }
    var listId by remember(task.id) { mutableStateOf(task.listId) }
    var url by remember(task.id) { mutableStateOf(task.url ?: "") }
    var memo by remember(task.id) { mutableStateOf(task.memo ?: "") }
    var dueAt by remember(task.id) { mutableStateOf(task.dueAt) }
    var repeatRule by remember(task.id) { mutableStateOf(task.repeatRule) }
    var selectedDays by remember(task.id) {
        mutableStateOf(RepeatRule.parseDaysOfWeek(task.repeatDaysOfWeek))
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }
    var subtaskInput by remember(task.id) { mutableStateOf("") }
    var nestedTask by remember { mutableStateOf<Task?>(null) }
    val subtasks = allTasks.filter { it.parentTaskId == task.id }

    var locationTab by remember(task.id) {
        val matchesSavedLocation = task.latitude != null && task.longitude != null &&
            savedLocations.any { it.latitude == task.latitude && it.longitude == task.longitude }
        mutableStateOf(if (matchesSavedLocation) LocationSourceTab.SAVED else LocationSourceTab.ADDRESS)
    }
    var addressInput by remember(task.id) { mutableStateOf(task.locationName ?: "") }
    var resolvedLocation by remember(task.id) {
        mutableStateOf(
            if (task.latitude != null && task.longitude != null) {
                GeocodeResult(task.locationName ?: "", task.latitude, task.longitude)
            } else {
                null
            }
        )
    }
    var locationSearchError by remember(task.id) { mutableStateOf<String?>(null) }
    var radiusMeters by remember(task.id) { mutableStateOf(task.radiusMeters ?: 300) }
    var notifyOnArrival by remember(task.id) { mutableStateOf(task.notifyOnArrival) }
    var notifyOnDeparture by remember(task.id) { mutableStateOf(task.notifyOnDeparture) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タスクを編集") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true
                )
                Text(text = "リスト", style = MaterialTheme.typography.titleSmall)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        FilterChip(
                            selected = listId == null,
                            onClick = { listId = null },
                            label = { Text("なし") }
                        )
                    }
                    items(lists, key = { it.id }) { list ->
                        FilterChip(
                            selected = listId == list.id,
                            onClick = { listId = list.id },
                            label = { Text(list.name) }
                        )
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL(任意)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = dueAt?.let { formatDueAt(it) } ?: "期限なし",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showDatePicker = true }) {
                        Text("設定")
                    }
                    if (dueAt != null) {
                        TextButton(onClick = { dueAt = null }) {
                            Text("クリア")
                        }
                    }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(DUE_QUICK_OPTIONS) { (label, minutes) ->
                        AssistChip(
                            onClick = { dueAt = System.currentTimeMillis() + minutes * 60_000 },
                            label = { Text(label) }
                        )
                    }
                }

                Text(text = "繰り返し", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        null to "なし",
                        RepeatRule.DAILY to "毎日",
                        RepeatRule.WEEKLY to "毎週",
                        RepeatRule.WEEKLY_DAYS to "毎週(曜日)",
                        RepeatRule.MONTHLY to "毎月"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = repeatRule == value,
                            onClick = { repeatRule = value },
                            label = { Text(label) }
                        )
                    }
                }
                if (repeatRule == RepeatRule.WEEKLY_DAYS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        WEEKDAY_LABELS.forEach { (value, label) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Checkbox(
                                    checked = value in selectedDays,
                                    onCheckedChange = { checked ->
                                        selectedDays = if (checked) {
                                            selectedDays + value
                                        } else {
                                            selectedDays - value
                                        }
                                    }
                                )
                                Text(text = label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (selectedDays.isEmpty()) {
                        Text(
                            text = "曜日を1つ以上選択してください",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Text(text = "位置", style = MaterialTheme.typography.titleSmall)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        FilterChip(
                            selected = locationTab == LocationSourceTab.ADDRESS,
                            onClick = {
                                locationTab = LocationSourceTab.ADDRESS
                                resolvedLocation = null
                                locationSearchError = null
                            },
                            label = { Text("住所で指定") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = locationTab == LocationSourceTab.CURRENT,
                            onClick = {
                                locationTab = LocationSourceTab.CURRENT
                                resolvedLocation = null
                                locationSearchError = null
                            },
                            label = { Text("現在地を使う") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = locationTab == LocationSourceTab.SAVED,
                            onClick = {
                                locationTab = LocationSourceTab.SAVED
                                resolvedLocation = null
                                locationSearchError = null
                            },
                            label = { Text("登録済みから選択") }
                        )
                    }
                }

                when (locationTab) {
                    LocationSourceTab.ADDRESS -> LocationPicker(
                        mode = LocationInputMode.ADDRESS,
                        addressInput = addressInput,
                        onAddressInputChange = { addressInput = it },
                        onResolved = {
                            resolvedLocation = it
                            locationSearchError = null
                        },
                        onError = {
                            resolvedLocation = null
                            locationSearchError = it
                        }
                    )
                    LocationSourceTab.CURRENT -> LocationPicker(
                        mode = LocationInputMode.CURRENT,
                        addressInput = addressInput,
                        onAddressInputChange = { addressInput = it },
                        onResolved = {
                            resolvedLocation = it
                            locationSearchError = null
                        },
                        onError = {
                            locationSearchError = it
                        }
                    )
                    LocationSourceTab.SAVED -> {
                        if (savedLocations.isEmpty()) {
                            Text(
                                text = "登録済みの場所がありません。「場所を編集」から追加できます。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(savedLocations, key = { it.id }) { location ->
                                    FilterChip(
                                        selected = resolvedLocation?.name == location.name &&
                                            resolvedLocation?.latitude == location.latitude &&
                                            resolvedLocation?.longitude == location.longitude,
                                        onClick = {
                                            resolvedLocation = GeocodeResult(
                                                location.name,
                                                location.latitude,
                                                location.longitude
                                            )
                                            radiusMeters = location.radiusMeters
                                            locationSearchError = null
                                        },
                                        label = { Text(location.name) }
                                    )
                                }
                            }
                        }
                    }
                }

                resolvedLocation?.let { location ->
                    Text(
                        text = "✓ ${location.name.ifBlank { "選択した場所" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                locationSearchError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (resolvedLocation != null) {
                    Text(text = "半径", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(100, 300, 500, 1000).forEach { meters ->
                            FilterChip(
                                selected = radiusMeters == meters,
                                onClick = { radiusMeters = meters },
                                label = { Text(radiusLabel(meters)) }
                            )
                        }
                    }

                    Text(text = "通知タイミング", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = notifyOnArrival,
                            onClick = { notifyOnArrival = !notifyOnArrival },
                            label = { Text("到着時") }
                        )
                        FilterChip(
                            selected = notifyOnDeparture,
                            onClick = { notifyOnDeparture = !notifyOnDeparture },
                            label = { Text("離脱時") }
                        )
                    }
                    if (!notifyOnArrival && !notifyOnDeparture) {
                        Text(
                            text = "通知タイミングを1つ以上選択してください",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    TextButton(onClick = {
                        resolvedLocation = null
                        addressInput = ""
                        notifyOnArrival = false
                        notifyOnDeparture = false
                        locationSearchError = null
                    }) {
                        Text("位置をクリア")
                    }
                }

                Text(text = "メモ", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                Text(text = "添付ファイル", style = MaterialTheme.typography.titleSmall)
                attachments.forEach { attachment ->
                    AttachmentRow(
                        attachment = attachment,
                        onOpen = {
                            try {
                                context.startActivity(AttachmentStorage.openIntent(context, attachment))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "開けるアプリが見つかりません", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { onDeleteAttachment(attachment) }
                    )
                }
                TextButton(onClick = { showAttachmentSourceDialog = true }) {
                    Text("ファイルを追加")
                }

                Text(text = "サブタスク", style = MaterialTheme.typography.titleSmall)
                subtasks.forEach { subtask ->
                    SubtaskTreeRow(
                        task = subtask,
                        allTasks = allTasks,
                        depth = 0,
                        onToggleDone = onToggleDone,
                        onOpenTask = { nestedTask = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = subtaskInput,
                        onValueChange = { subtaskInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("サブタスクを追加") },
                        singleLine = true
                    )
                    TextButton(onClick = {
                        onAddSubtask(task, subtaskInput)
                        subtaskInput = ""
                    }) {
                        Text("追加")
                    }
                }
            }
        },
        confirmButton = {
            val repeatValid = repeatRule != RepeatRule.WEEKLY_DAYS || selectedDays.isNotEmpty()
            val locationValid = resolvedLocation == null || notifyOnArrival || notifyOnDeparture
            val canSave = repeatValid && locationValid
            TextButton(
                enabled = canSave,
                onClick = {
                    val daysOfWeek = if (repeatRule == RepeatRule.WEEKLY_DAYS) {
                        RepeatRule.formatDaysOfWeek(selectedDays)
                    } else {
                        null
                    }
                    val location = resolvedLocation
                    onConfirm(
                        task,
                        TaskEdits(
                            title = title,
                            listId = listId,
                            dueAt = dueAt,
                            repeatRule = repeatRule,
                            repeatDaysOfWeek = daysOfWeek,
                            locationName = location?.name,
                            latitude = location?.latitude,
                            longitude = location?.longitude,
                            radiusMeters = if (location != null) radiusMeters else null,
                            notifyOnArrival = location != null && notifyOnArrival,
                            notifyOnDeparture = location != null && notifyOnDeparture,
                            url = normalizeUrl(url),
                            memo = memo.trim().ifBlank { null }
                        )
                    )
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = dueAt ?: todayAsDatePickerMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { pendingDateMillis = it }
                    showDatePicker = false
                }) {
                    Text("次へ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("キャンセル")
                }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    pendingDateMillis?.let { dateMillis ->
        val defaultTime = dueAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }
            ?: LocalTime.now()
        val timeState = rememberTimePickerState(
            initialHour = defaultTime.hour,
            initialMinute = defaultTime.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { pendingDateMillis = null },
            title = { Text("時刻を選択") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    dueAt = combineDateAndTime(dateMillis, timeState.hour, timeState.minute)
                    pendingDateMillis = null
                }) {
                    Text("設定")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDateMillis = null }) {
                    Text("キャンセル")
                }
            }
        )
    }

    nestedTask?.let { nested ->
        EditTaskDialog(
            task = nested,
            allTasks = allTasks,
            lists = lists,
            savedLocations = savedLocations,
            allAttachments = allAttachments,
            onConfirm = { t, edits ->
                onConfirm(t, edits)
                nestedTask = null
            },
            onAddSubtask = onAddSubtask,
            onToggleDone = onToggleDone,
            onAddAttachment = onAddAttachment,
            onDeleteAttachment = onDeleteAttachment,
            onDismiss = { nestedTask = null }
        )
    }

    if (showAttachmentSourceDialog) {
        AlertDialog(
            onDismissRequest = { showAttachmentSourceDialog = false },
            title = { Text("添付ファイルの選択方法") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        showAttachmentSourceDialog = false
                        attachmentPicker.launch(arrayOf("*/*"))
                    }) {
                        Text("全ファイルから選択")
                    }
                    TextButton(onClick = {
                        showAttachmentSourceDialog = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Text("写真から選択")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAttachmentSourceDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

/** 画像添付のサムネイル用に、大きすぎないサイズへダウンサンプリングしてデコードする。 */
private fun decodeThumbnail(file: File, maxSize: Int = 200): Bitmap? {
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxSize || bounds.outHeight / sampleSize > maxSize) {
            sampleSize *= 2
        }
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun AttachmentThumbnail(attachment: TaskAttachment) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, attachment.storedFileName) {
        value = withContext(Dispatchers.IO) {
            decodeThumbnail(AttachmentStorage.file(context, attachment.storedFileName))
        }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
    } else {
        Text(text = "📎")
    }
}

/**
 * [attachment]が画像またはPDFであれば、確認画面にインラインでプレビュー表示する。
 * それ以外の形式はプレビュー非対応のため何も表示しない([AttachmentRow]のタップで
 * 外部アプリを開く導線のみとなる)。
 */
@Composable
private fun AttachmentPreview(attachment: TaskAttachment) {
    val context = LocalContext.current
    when {
        attachment.mimeType?.startsWith("image/") == true -> {
            val bitmap by produceState<Bitmap?>(initialValue = null, attachment.storedFileName) {
                value = withContext(Dispatchers.IO) {
                    decodeThumbnail(AttachmentStorage.file(context, attachment.storedFileName), maxSize = 1080)
                }
            }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = attachment.fileName,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        attachment.mimeType == "application/pdf" -> {
            val pages by produceState<List<Bitmap>>(initialValue = emptyList(), attachment.storedFileName) {
                value = withContext(Dispatchers.IO) {
                    renderPdfPages(AttachmentStorage.file(context, attachment.storedFileName))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pages.forEach { page ->
                    Image(
                        bitmap = page.asImageBitmap(),
                        contentDescription = attachment.fileName,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** PDFの各ページを画面幅相当の解像度でBitmap化する。1ページずつ開いて閉じるため大きなPDFでも安全。 */
private fun renderPdfPages(file: File, targetWidthPx: Int = 1080): List<Bitmap> {
    if (!file.exists()) return emptyList()
    return try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        val scale = targetWidthPx.toFloat() / page.width
                        val bitmap = Bitmap.createBitmap(
                            targetWidthPx,
                            (page.height * scale).toInt(),
                            Bitmap.Config.ARGB_8888
                        )
                        val matrix = Matrix().apply { setScale(scale, scale) }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
private fun AttachmentRow(
    attachment: TaskAttachment,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    showDelete: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (attachment.mimeType?.startsWith("image/") == true) {
            AttachmentThumbnail(attachment)
        } else {
            Text(text = "📎")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = attachment.fileName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatFileSize(attachment.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showDelete) {
            TextButton(onClick = onDelete) {
                Text("削除")
            }
        }
    }
}

@Composable
fun DeleteTaskDialog(
    task: Task,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タスクを削除") },
        text = { Text("「${task.title}」を削除しますか?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("削除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun TaskScreenPreview() {
    OboetotteTheme {
        val previewTasks = listOf(
            Task(id = 1, title = "牛乳を買う", dueAt = System.currentTimeMillis() + 86_400_000),
            Task(id = 2, title = "掃除機をかける", isDone = true)
        )
        TaskScreen(
            tasks = previewTasks,
            allTasks = previewTasks,
            lists = listOf(TaskList(id = 1, name = "買い物")),
            selectedListId = null,
            onSelectList = {},
            showCompleted = true,
            onSetShowCompleted = {},
            onAddTask = {},
            onToggleDone = {},
            onUpdateTask = { _, _ -> },
            onDeleteTask = {},
            onAddSubtask = { _, _ -> }
        )
    }
}
