package com.toshi0907.oboetotte.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.toshi0907.oboetotte.ui.theme.OboetotteTheme
import kotlinx.coroutines.launch

/**
 * ホーム画面にウィジェットを追加する際にシステムから起動される設定画面
 * (`task_widget_info.xml`の`android:configure`で指定)。[WidgetBackground]をウィジェット
 * インスタンスごとに選択させ、確定すると[androidx.glance.appwidget.state.PreferencesGlanceStateDefinition]
 * 経由でこのウィジェットIDに紐づく設定として保存し、即座に再描画してから
 * RESULT_OKで終了する。何も選ばず閉じた場合はRESULT_CANCELEDのままとなり、
 * システムがウィジェットの追加自体を取り消す。
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
                var selected by remember { mutableStateOf(WidgetBackground.DEFAULT) }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("ウィジェットの背景色") },
                    text = {
                        Column {
                            WidgetBackground.entries.forEach { option ->
                                FilterChip(
                                    selected = selected == option,
                                    onClick = { selected = option },
                                    label = { Text(option.label) }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { confirmAndFinish(selected) }) {
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

    private fun confirmAndFinish(background: WidgetBackground) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@TaskWidgetConfigureActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@TaskWidgetConfigureActivity, glanceId) { prefs ->
                prefs[TaskWidget.BACKGROUND_KEY] = background.name
            }
            TaskWidget().updateAll(this@TaskWidgetConfigureActivity)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}
