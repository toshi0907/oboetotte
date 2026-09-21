package com.toshi0907.oboetotte.widget

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.graphics.Color
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
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.TaskDisplayFilter
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.formatDueAt
import com.toshi0907.oboetotte.isDueToday
import com.toshi0907.oboetotte.isOverdue
import com.toshi0907.oboetotte.matches
import com.toshi0907.oboetotte.ui.theme.Green40
import com.toshi0907.oboetotte.ui.theme.Green80
import com.toshi0907.oboetotte.ui.theme.Purple40
import com.toshi0907.oboetotte.ui.theme.Purple80
import com.toshi0907.oboetotte.ui.theme.Red40
import com.toshi0907.oboetotte.ui.theme.Red80
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * ホーム画面ウィジェット。トップレベルの未完了タスクを、メイン画面と同じ並び順
 * (期限が近い順。[com.toshi0907.oboetotte.data.TaskDao.getAll]のクエリ順序をそのまま利用)で
 * 一覧表示する。各行はタイトルに加え、期限があれば期限日時(メイン画面と同じ
 * [com.toshi0907.oboetotte.formatDueAt]の書式)を表示し、メイン画面([com.toshi0907.oboetotte.TaskTreeRow])
 * と同じく期限切れは赤系・当日期限は緑系で色分けする([dueTextColor])。URLがあれば、表示を
 * コンパクトに保つためURL文字列そのものではなく「[LINK_LABEL]」というラベルを、メイン画面の
 * URLリンク表示([com.toshi0907.oboetotte.MainActivity]内のタスク詳細と同じ`colorScheme.primary`相当)
 * にならいリンク色+下線のテキストとして表示し(背景・枠線・角丸は持たない)、期限がある場合は
 * 期限日時と同じ行に、無い場合は単独の行に表示する。リンクラベルはタップ領域確保のため上下に
 * パディングを持つため、同じ行に並ぶ期限日時にも同じ縦パディングを付けて表示位置を揃えている。
 * 行(タイトル・期限部分)をタップするとアプリ(MainActivity)を開き、リンクラベルをタップすると
 * ブラウザ等でURLを直接開く。
 * ウィジェット上での完了操作は行わない。表示内容はDB更新のたびに
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

    /** ウィジェットの表示内容を組み立てる。詳細はクラスのKDocを参照。 */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val tasks = AppDatabase.getInstance(context).taskDao().getAll().first()
            .filter { it.parentTaskId == null && !it.isDone }
        val isDarkTheme = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        provideContent {
            val prefs = currentState<Preferences>()
            val background = WidgetBackground.fromName(prefs[BACKGROUND_KEY])
            val textStyle = background.textColor?.let { TextStyle(color = ColorProvider(it)) } ?: TextStyle()
            val linkTextStyle = TextStyle(
                color = ColorProvider(linkTextColor(background, isDarkTheme)),
                textDecoration = TextDecoration.Underline
            )

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
                        items(filteredTasks, itemId = { it.widgetItemId(nowMillis) }) { task ->
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
                                    val dueStyle = dueTextColor(
                                        background = background,
                                        isOverdue = task.isOverdue(nowMillis),
                                        isDueToday = task.isDueToday(nowMillis),
                                        isDarkTheme = isDarkTheme
                                    )?.let { TextStyle(color = ColorProvider(it)) } ?: textStyle
                                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                                        Text(
                                            text = formatDueAt(dueAt),
                                            style = dueStyle,
                                            modifier = GlanceModifier.padding(vertical = 6.dp)
                                        )
                                        if (url != null) {
                                            Text(
                                                text = LINK_LABEL,
                                                style = linkTextStyle,
                                                modifier = GlanceModifier
                                                    .padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                                                    .clickable(
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
                                        style = linkTextStyle,
                                        modifier = GlanceModifier
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
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

/**
 * [LazyColumn]の`itemId`に使う、タスクIDと表示内容(タイトル・期限・URL・期限の色分け状態)を
 * 組み合わせた複合キー。Jetpack Glanceの`LazyColumn`には、`itemId`が変わらないまま一部フィールドだけを
 * 更新した場合に再描画が反映されないという既知の不具合がある(Google Issue Tracker #240300611)。タスクIDのみを
 * `itemId`にすると、タイトル変更では新しいitemとして再描画される一方、期限のみの変更では同じ
 * itemIdのままとなりこの不具合を踏んでしまう(Issue #105)。そのため上位32bitにタスクID、下位32bitに
 * 表示内容のハッシュ値を詰めた値を`itemId`とし、表示内容が変わった場合は常に別itemとして扱わせる
 * (異なるタスクID同士は上位32bitの時点で必ず別の値になるため衝突しない)。[dueTextColor]が参照する
 * [Task.isOverdue]/[Task.isDueToday]は他のフィールドが変わらないまま時刻の経過だけで結果が変わるため、
 * それらもハッシュに含め、期限切れ・当日期限への切り替わり時にも同じ不具合で再描画が反映されない
 * ことがないようにする。
 */
private fun Task.widgetItemId(nowMillis: Long): Long {
    val contentHash = ((title.hashCode() * 31 + (dueAt?.hashCode() ?: 0)) * 31 + (url?.hashCode() ?: 0)) * 31 +
        (isOverdue(nowMillis).hashCode() * 31 + isDueToday(nowMillis).hashCode())
    return (id shl 32) or (contentHash.toLong() and 0xFFFFFFFFL)
}

/** [OpenTaskUrlAction]に開くURLを渡すための[ActionParameters.Key]。 */
private val URL_PARAM_KEY = ActionParameters.Key<String>("task_url")

/** タスクにURLがある場合、URL文字列の代わりに表示するラベル。 */
private const val LINK_LABEL = "リンク"

/**
 * [background]・[isDarkTheme]から、赤([Red40]/[Red80])・緑([Green40]/[Green80])・
 * リンク色([Purple40]/[Purple80])が濃淡どちらの色を使うべきかを判定する共通ロジック。
 * WHITE/BLACKは常にその配色向けの濃淡を、TRANSPARENTは端末のダークテーマ設定に従う。
 */
private fun useDarkPalette(background: WidgetBackground, isDarkTheme: Boolean): Boolean =
    when (background) {
        WidgetBackground.WHITE -> false
        WidgetBackground.BLACK -> true
        WidgetBackground.TRANSPARENT -> isDarkTheme
    }

/**
 * 期限表示の文字色。メイン画面([com.toshi0907.oboetotte.TaskTreeRow])と同じく、期限切れは赤系
 * ([Red40]/[Red80])、当日期限は緑系([Green40]/[Green80])で強調し、それ以外は[textStyle]どおりの
 * 既定色(`null`)とする。ウィジェットではメイン画面のように`MaterialTheme.colorScheme`を参照できない
 * ため、赤・緑とも固定値を使う。どちらの濃淡を使うかは[useDarkPalette]で判定する。
 */
private fun dueTextColor(
    background: WidgetBackground,
    isOverdue: Boolean,
    isDueToday: Boolean,
    isDarkTheme: Boolean
): Color? {
    val darkPalette = useDarkPalette(background, isDarkTheme)
    return when {
        isOverdue -> if (darkPalette) Red80 else Red40
        isDueToday -> if (darkPalette) Green80 else Green40
        else -> null
    }
}

/**
 * 「[LINK_LABEL]」の文字色。メイン画面(タスク詳細の`current.url`表示)と同じくテーマの
 * プライマリカラー相当([Purple40]/[Purple80]。`ui/theme/Theme.kt`の`LightColorScheme`/
 * `DarkColorScheme`の`primary`と同じ値)を使い、下線([TextDecoration.Underline])と合わせて
 * リンクであることを示す。ウィジェットでは`MaterialTheme.colorScheme.primary`を参照できないため
 * 固定値を使い、どちらの濃淡を使うかは[useDarkPalette]で判定する。
 */
private fun linkTextColor(background: WidgetBackground, isDarkTheme: Boolean): Color =
    if (useDarkPalette(background, isDarkTheme)) Purple80 else Purple40

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
