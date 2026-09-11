package com.toshi0907.oboetotte

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.toshi0907.oboetotte.backup.BackupManager
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.data.TaskList
import com.toshi0907.oboetotte.notification.LocationReminderManager
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.ui.theme.OboetotteTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val taskViewModel: TaskViewModel by viewModels()

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒否されてもアプリは通常通り使えるため、明示的なハンドリングは不要 */ }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
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
                    val selectedListId by taskViewModel.selectedListId.collectAsState()
                    val showCompleted by taskViewModel.showCompleted.collectAsState()
                    val context = LocalContext.current
                    val lifecycleOwner = LocalLifecycleOwner.current
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
                    TaskScreen(
                        tasks = tasks,
                        allTasks = allTasks,
                        lists = lists,
                        selectedListId = selectedListId,
                        onSelectList = taskViewModel::selectList,
                        showCompleted = showCompleted,
                        onSetShowCompleted = taskViewModel::setShowCompleted,
                        onAddList = taskViewModel::addList,
                        onRenameList = taskViewModel::renameList,
                        onDeleteList = taskViewModel::deleteList,
                        onAddTask = taskViewModel::addTask,
                        onToggleDone = taskViewModel::toggleDone,
                        onUpdateTask = taskViewModel::updateTask,
                        onDeleteTask = taskViewModel::deleteTask,
                        onAddSubtask = taskViewModel::addSubtask,
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
                            val fileName = "oboetotte_backup_%04d%02d%02d_%02d%02d%02d.json".format(
                                zoned.year, zoned.monthValue, zoned.dayOfMonth,
                                zoned.hour, zoned.minute, zoned.second
                            )
                            exportBackupLauncher.launch(fileName)
                        },
                        onImportRequested = {
                            importBackupLauncher.launch(arrayOf("application/json"))
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
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
    onAddList: (String) -> Unit,
    onRenameList: (TaskList, String) -> Unit,
    onDeleteList: (TaskList) -> Unit,
    onAddTask: (String) -> Unit,
    onToggleDone: (Task) -> Unit,
    onUpdateTask: (Task, TaskEdits) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onAddSubtask: (Task, String) -> Unit,
    showExactAlarmBanner: Boolean = false,
    onRequestExactAlarmPermission: () -> Unit = {},
    locationPermissionGranted: Boolean = true,
    onRequestLocationSettings: () -> Unit = {},
    onSendTestNotification: () -> Unit = {},
    onExportRequested: () -> Unit = {},
    onImportRequested: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var deletingTask by remember { mutableStateOf<Task?>(null) }
    var showManageLists by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
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
                        onClick = { showManageLists = true },
                        label = { Text("リストを編集") }
                    )
                }
                item {
                    AssistChip(
                        onClick = onSendTestNotification,
                        label = { Text("テスト通知") }
                    )
                }
                item {
                    AssistChip(
                        onClick = { showSettings = true },
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
                    label = { Text("タスクを入力") }
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
                        onEditTask = { editingTask = it },
                        onDeleteTask = { deletingTask = it }
                    )
                }
            }
        }
    }

    editingTask?.let { task ->
        EditTaskDialog(
            task = task,
            allTasks = allTasks,
            onConfirm = { t, edits ->
                onUpdateTask(t, edits)
                editingTask = null
            },
            onAddSubtask = onAddSubtask,
            onToggleDone = onToggleDone,
            onDismiss = { editingTask = null }
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

    if (showManageLists) {
        ManageListsDialog(
            lists = lists,
            onAddList = onAddList,
            onRenameList = onRenameList,
            onDeleteList = onDeleteList,
            onDismiss = { showManageLists = false }
        )
    }

    if (showSettings) {
        SettingsDialog(
            onExport = {
                onExportRequested()
                showSettings = false
            },
            onImport = {
                onImportRequested()
                showSettings = false
            },
            onDismiss = { showSettings = false }
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
    onEditTask: (Task) -> Unit,
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
                    onClick = { onEditTask(task) },
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
                onEditTask = onEditTask,
                onDeleteTask = onDeleteTask
            )
        }
    }
}

@Composable
fun SettingsDialog(
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("タスクデータをファイルにバックアップしたり、バックアップから復元したりできます。")
                Text(
                    text = "インポートすると、現在のタスクデータはすべてインポートしたファイルの内容に置き換わります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onExport) {
                Text("エクスポート")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onImport) {
                    Text("インポート")
                }
                TextButton(onClick = onDismiss) {
                    Text("閉じる")
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTaskDialog(
    task: Task,
    allTasks: List<Task>,
    onConfirm: (task: Task, edits: TaskEdits) -> Unit,
    onAddSubtask: (Task, String) -> Unit,
    onToggleDone: (Task) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
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

    var useCurrentLocationTab by remember(task.id) { mutableStateOf(false) }
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
    var isLocating by remember(task.id) { mutableStateOf(false) }
    var radiusMeters by remember(task.id) { mutableStateOf(task.radiusMeters ?: 300) }
    var notifyOnArrival by remember(task.id) { mutableStateOf(task.notifyOnArrival) }
    var notifyOnDeparture by remember(task.id) { mutableStateOf(task.notifyOnDeparture) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            isLocating = true
            coroutineScope.launch {
                val result = getCurrentLocationResult(context)
                isLocating = false
                if (result != null) {
                    resolvedLocation = result
                    locationSearchError = null
                } else {
                    locationSearchError = "現在地を取得できませんでした"
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タスクを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = !useCurrentLocationTab,
                        onClick = { useCurrentLocationTab = false },
                        label = { Text("住所で指定") }
                    )
                    FilterChip(
                        selected = useCurrentLocationTab,
                        onClick = {
                            useCurrentLocationTab = true
                            resolvedLocation = null
                            locationSearchError = null
                        },
                        label = { Text("現在地を使う") }
                    )
                }

                if (!useCurrentLocationTab) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedTextField(
                            value = addressInput,
                            onValueChange = { addressInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("住所または場所名") },
                            singleLine = true
                        )
                        TextButton(onClick = {
                            val query = addressInput
                            coroutineScope.launch {
                                val result = geocodeAddress(context, query)
                                if (result != null) {
                                    resolvedLocation = result
                                    locationSearchError = null
                                } else {
                                    resolvedLocation = null
                                    locationSearchError = "住所が見つかりませんでした。もう少し詳しい住所を入力してください。"
                                }
                            }
                        }) {
                            Text("検索")
                        }
                    }
                } else {
                    TextButton(onClick = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            isLocating = true
                            coroutineScope.launch {
                                val result = getCurrentLocationResult(context)
                                isLocating = false
                                if (result != null) {
                                    resolvedLocation = result
                                    locationSearchError = null
                                } else {
                                    locationSearchError = "現在地を取得できませんでした"
                                }
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
                            dueAt = dueAt,
                            repeatRule = repeatRule,
                            repeatDaysOfWeek = daysOfWeek,
                            locationName = location?.name,
                            latitude = location?.latitude,
                            longitude = location?.longitude,
                            radiusMeters = if (location != null) radiusMeters else null,
                            notifyOnArrival = location != null && notifyOnArrival,
                            notifyOnDeparture = location != null && notifyOnDeparture
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
            onConfirm = { t, edits ->
                onConfirm(t, edits)
                nestedTask = null
            },
            onAddSubtask = onAddSubtask,
            onToggleDone = onToggleDone,
            onDismiss = { nestedTask = null }
        )
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
            onAddList = {},
            onRenameList = { _, _ -> },
            onDeleteList = {},
            onAddTask = {},
            onToggleDone = {},
            onUpdateTask = { _, _ -> },
            onDeleteTask = {},
            onAddSubtask = { _, _ -> }
        )
    }
}
