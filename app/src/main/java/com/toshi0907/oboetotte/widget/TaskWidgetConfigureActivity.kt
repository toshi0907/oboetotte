package com.toshi0907.oboetotte.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.toshi0907.oboetotte.TaskDisplayFilter
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.TaskList
import com.toshi0907.oboetotte.ui.theme.OboetotteTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ホーム画面にウィジェットを追加する際にシステムから起動される設定画面
 * (`task_widget_info.xml`の`android:configure`で指定)。[WidgetBackground]・表示するリスト
 * ([TaskWidget.LIST_FILTER_KEY])・表示フィルタ([TaskWidget.DISPLAY_FILTER_KEY])を
 * ウィジェットインスタンスごとに選択させ、確定すると
 * [androidx.glance.state.PreferencesGlanceStateDefinition]経由でこのウィジェットIDに紐づく
 * 設定として保存し、即座に再描画してからRESULT_OKで終了する。何も選ばず閉じた場合は
 * RESULT_CANCELEDのままとなり、システムがウィジェットの追加自体を取り消す。
 */
class TaskWidgetConfigureActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            OboetotteTheme {
                var selectedBackground by remember { mutableStateOf(WidgetBackground.DEFAULT) }
                var selectedListFilter by remember { mutableStateOf(TaskWidget.ALL_LISTS_VALUE) }
                var selectedDisplayFilters by remember { mutableStateOf<Set<TaskDisplayFilter>>(emptySet()) }
                var lists by remember { mutableStateOf<List<TaskList>>(emptyList()) }

                LaunchedEffect(Unit) {
                    lists = AppDatabase.getInstance(this@TaskWidgetConfigureActivity)
                        .taskListDao().getAll().first()
                }

                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("ウィジェットの設定") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text("背景色")
                            Column {
                                WidgetBackground.entries.forEach { option ->
                                    FilterChip(
                                        selected = selectedBackground == option,
                                        onClick = { selectedBackground = option },
                                        label = { Text(option.label) }
                                    )
                                }
                            }
                            Text("表示するリスト")
                            Column {
                                FilterChip(
                                    selected = selectedListFilter == TaskWidget.ALL_LISTS_VALUE,
                                    onClick = { selectedListFilter = TaskWidget.ALL_LISTS_VALUE },
                                    label = { Text("すべて") }
                                )
                                FilterChip(
                                    selected = selectedListFilter == TaskWidget.UNASSIGNED_LIST_VALUE,
                                    onClick = { selectedListFilter = TaskWidget.UNASSIGNED_LIST_VALUE },
                                    label = { Text("リスト未登録") }
                                )
                                lists.forEach { list ->
                                    val value = list.id.toString()
                                    FilterChip(
                                        selected = selectedListFilter == value,
                                        onClick = { selectedListFilter = value },
                                        label = { Text(list.name) }
                                    )
                                }
                            }
                            Text("表示フィルタ")
                            Column {
                                FilterChip(
                                    selected = TaskDisplayFilter.HAS_URL in selectedDisplayFilters,
                                    onClick = {
                                        selectedDisplayFilters = selectedDisplayFilters.toggle(TaskDisplayFilter.HAS_URL)
                                    },
                                    label = { Text("リンクを含む") }
                                )
                                FilterChip(
                                    selected = TaskDisplayFilter.DUE_TODAY_OR_OVERDUE in selectedDisplayFilters,
                                    onClick = {
                                        selectedDisplayFilters =
                                            selectedDisplayFilters.toggle(TaskDisplayFilter.DUE_TODAY_OR_OVERDUE)
                                    },
                                    label = { Text("今日期限・期限切れ") }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmAndFinish(selectedBackground, selectedListFilter, selectedDisplayFilters)
                            }
                        ) {
                            Text("追加")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { finish() }) {
                            Text("キャンセル")
                        }
                    }
                )
            }
        }
    }

    private fun confirmAndFinish(
        background: WidgetBackground,
        listFilter: String,
        displayFilters: Set<TaskDisplayFilter>
    ) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@TaskWidgetConfigureActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@TaskWidgetConfigureActivity, glanceId) { prefs ->
                prefs[TaskWidget.BACKGROUND_KEY] = background.name
                prefs[TaskWidget.LIST_FILTER_KEY] = listFilter
                prefs[TaskWidget.DISPLAY_FILTER_KEY] = displayFilters.map { it.name }.toSet()
            }
            refreshTaskWidget(this@TaskWidgetConfigureActivity)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}

private fun Set<TaskDisplayFilter>.toggle(filter: TaskDisplayFilter): Set<TaskDisplayFilter> =
    if (filter in this) this - filter else this + filter
