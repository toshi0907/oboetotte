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
 * ホーム画面ウィジェット。トップレベルの未完了タスクを、メイン画面と同じ並び順
 * (期限が近い順。[com.toshi0907.oboetotte.data.TaskDao.getAll]のクエリ順序をそのまま利用)で
 * 一覧表示する。タップするとアプリ(MainActivity)を開くのみで、ウィジェット上での完了操作は
 * 行わない。表示内容はDB更新のたびに各所から呼ばれる[refreshTaskWidget]で再描画される。
 *
 * 背景色([WidgetBackground])・表示するリスト([LIST_FILTER_KEY])は[TaskWidgetConfigureActivity]で
 * ウィジェットごとに選択でき、[PreferencesGlanceStateDefinition]によりウィジェットインスタンス
 * 単位で永続化される(`stateDefinition`を指定すると、Glanceが[GlanceId]ごとに`Preferences`の
 * DataStoreを自動的に用意してくれる)。
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

            val listFilter = prefs[LIST_FILTER_KEY]
            val filteredTasks = when (listFilter) {
                null, ALL_LISTS_VALUE -> tasks
                UNASSIGNED_LIST_VALUE -> tasks.filter { it.listId == null }
                else -> tasks.filter { it.listId == listFilter.toLongOrNull() }
            }

            var modifier = GlanceModifier.fillMaxSize().padding(8.dp)
            background.color?.let { modifier = modifier.background(it) }

            Column(modifier = modifier) {
                if (filteredTasks.isEmpty()) {
                    Text(text = "未完了タスクはありません", style = textStyle)
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(filteredTasks, itemId = { it.id }) { task ->
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

        /**
         * 表示するリストの絞り込みを保存するPreferencesキー。値は[ALL_LISTS_VALUE]・
         * [UNASSIGNED_LIST_VALUE]、またはタスクの`listId`を文字列化したものを取る。
         * キー未設定(`null`)は[ALL_LISTS_VALUE]と同じ扱い(すべて表示)。
         */
        val LIST_FILTER_KEY = stringPreferencesKey("list_filter")

        /** [LIST_FILTER_KEY]で「すべて」(リスト横断)を表す値。 */
        const val ALL_LISTS_VALUE = "ALL"

        /** [LIST_FILTER_KEY]で「リスト未登録」(`listId == null`のタスクのみ)を表す値。 */
        const val UNASSIGNED_LIST_VALUE = "UNASSIGNED"
    }
}

/** [TaskWidget]をシステムに公開するための`AppWidgetProvider`。AndroidManifest.xmlに登録している。 */
class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}
