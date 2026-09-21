package com.toshi0907.oboetotte.widget

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.TaskDisplayFilter
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.formatDueAt
import com.toshi0907.oboetotte.matches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * ホーム画面ウィジェット。トップレベルの未完了タスクを、メイン画面と同じ並び順
 * (期限が近い順。[com.toshi0907.oboetotte.data.TaskDao.getAll]のクエリ順序をそのまま利用)で
 * 一覧表示する。各行はタイトルに加え、期限があれば期限日時(メイン画面と同じ
 * [com.toshi0907.oboetotte.formatDueAt]の書式)を表示する。URLがあれば、表示を
 * コンパクトに保つためURL文字列そのものではなく「[LINK_LABEL]」というラベルを表示し、
 * 期限がある場合は期限日時と同じ行に、無い場合は単独の行に表示する。
 * 行(タイトル・期限部分)をタップするとアプリ(MainActivity)を開き、リンク部分をタップすると
 * ブラウザ等でURLを直接開く。ウィジェット上での完了操作は行わない。表示内容はDB更新のたびに
 * 各所から呼ばれる[refreshTaskWidget]で再描画される。
 *
 * 背景色([WidgetBackground])・表示するリスト([LIST_FILTER_KEY])・表示フィルタ
 * ([DISPLAY_FILTER_KEY])は[TaskWidgetConfigureActivity]でウィジェットごとに選択でき、
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

            val listFilter = prefs[LIST_FILTER_KEY]
            val listFilteredTasks = when (listFilter) {
                null, ALL_LISTS_VALUE -> tasks
                UNASSIGNED_LIST_VALUE -> tasks.filter { it.listId == null }
                else -> tasks.filter { it.listId == listFilter.toLongOrNull() }
            }

            val activeDisplayFilters = (prefs[DISPLAY_FILTER_KEY] ?: emptySet())
                .mapNotNull { name -> TaskDisplayFilter.entries.find { it.name == name } }
                .toSet()
            val nowMillis = System.currentTimeMillis()
            val filteredTasks = if (activeDisplayFilters.isEmpty()) {
                listFilteredTasks
            } else {
                listFilteredTasks.filter { task ->
                    activeDisplayFilters.any { filter -> filter.matches(task, nowMillis) }
                }
            }

            var modifier = GlanceModifier.fillMaxSize().padding(8.dp)
            background.color?.let { modifier = modifier.background(it) }

            Column(modifier = modifier) {
                if (filteredTasks.isEmpty()) {
                    Text(text = "未完了タスクはありません", style = textStyle)
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(filteredTasks, itemId = { it.id }) { task ->
                            Column(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(actionStartActivity<MainActivity>())
                            ) {
                                Text(text = task.title, style = textStyle, modifier = GlanceModifier.fillMaxWidth())
                                val url = task.url?.takeIf { it.isNotBlank() }
                                val dueAt = task.dueAt
                                if (dueAt != null) {
                                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                                        Text(text = formatDueAt(dueAt), style = textStyle)
                                        if (url != null) {
                                            Text(
                                                text = " $LINK_LABEL",
                                                style = textStyle,
                                                modifier = GlanceModifier.clickable(
                                                    actionRunCallback<OpenTaskUrlAction>(
                                                        actionParametersOf(URL_PARAM_KEY to url)
                                                    )
                                                )
                                            )
                                        }
                                    }
                                } else if (url != null) {
                                    Text(
                                        text = LINK_LABEL,
                                        style = textStyle,
                                        modifier = GlanceModifier
                                            .fillMaxWidth()
                                            .clickable(
                                                actionRunCallback<OpenTaskUrlAction>(
                                                    actionParametersOf(URL_PARAM_KEY to url)
                                                )
                                            )
                                    )
                                }
                            }
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

        /**
         * 表示フィルタ([TaskDisplayFilter])の絞り込みを保存するPreferencesキー。値は選択中の
         * [TaskDisplayFilter.name]の集合。空集合・キー未設定はメイン画面と同じく「絞り込みなし
         * (すべて表示)」を意味する。複数選択時はメイン画面と同じくOR判定。
         */
        val DISPLAY_FILTER_KEY = stringSetPreferencesKey("display_filter")
    }
}

/** [OpenTaskUrlAction]に開くURLを渡すための[ActionParameters.Key]。 */
private val URL_PARAM_KEY = ActionParameters.Key<String>("task_url")

/** タスクにURLがある場合、URL文字列の代わりに表示するラベル。 */
private const val LINK_LABEL = "リンク"

/**
 * ウィジェットのリンク行をタップした際にタスクの[Task.url][com.toshi0907.oboetotte.data.Task.url]を
 * ブラウザ等で直接開く[ActionCallback]。`androidx.glance.action.actionStartActivity`には任意の
 * [Intent]を渡せるオーバーロードが無いため、`ActionCallback`経由で`Context.startActivity`を呼ぶ
 * (ウィジェットのプロセス外から起動するため`FLAG_ACTIVITY_NEW_TASK`を付与する)。開けるアプリが
 * 無い場合は`MainActivity`のURL項目タップ時と同じ文言を`Toast`で表示する
 * (`onAction`はメインスレッドで呼ばれるとは限らないため`Dispatchers.Main`に切り替える)。
 */
class OpenTaskUrlAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val url = parameters[URL_PARAM_KEY] ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "開けるアプリが見つかりません", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** [TaskWidget]をシステムに公開するための`AppWidgetProvider`。AndroidManifest.xmlに登録している。 */
class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}
