package com.toshi0907.oboetotte.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.data.AppDatabase
import kotlinx.coroutines.flow.first

/**
 * ホーム画面ウィジェット。全リスト横断でトップレベルの未完了タスクを、メイン画面と同じ
 * 並び順(期限が近い順。[com.toshi0907.oboetotte.data.TaskDao.getAll]のクエリ順序を
 * そのまま利用)で一覧表示する。タップするとアプリ(MainActivity)を開くのみで、
 * ウィジェット上での完了操作は行わない。表示内容はDB更新のたびに各所から呼ばれる
 * [refreshTaskWidget]で再描画される。
 *
 * 背景色は[WidgetBackground]から[TaskWidgetConfigureActivity]でウィジェットごとに選択でき、
 * [PreferencesGlanceStateDefinition]によりウィジェットインスタンス単位で永続化される
 * (`stateDefinition`を指定すると、Glanceが[GlanceId]ごとに`Preferences`のDataStoreを
 * 自動的に用意してくれる)。
 */
class TaskWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val tasks = AppDatabase.getInstance(context).taskDao().getAll().first()
            .filter { it.parentTaskId == null && !it.isDone }

        provideContent {
            val prefs = currentState<Preferences>()
            val background = WidgetBackground.fromName(prefs[BACKGROUND_KEY])
            val textStyle = background.textColor?.let { TextStyle(color = ColorProvider(it)) } ?: TextStyle()

            var modifier = GlanceModifier.fillMaxSize().padding(8.dp)
            background.color?.let { modifier = modifier.background(it) }

            Column(modifier = modifier) {
                if (tasks.isEmpty()) {
                    Text(text = "未完了タスクはありません", style = textStyle)
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(tasks, itemId = { it.id }) { task ->
                            Text(
                                text = task.title,
                                style = textStyle,
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(actionStartActivity<MainActivity>())
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** [WidgetBackground.name]を文字列として保存するPreferencesキー。 */
        val BACKGROUND_KEY = stringPreferencesKey("background")
    }
}

/** [TaskWidget]をシステムに公開するための`AppWidgetProvider`。AndroidManifest.xmlに登録している。 */
class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}
