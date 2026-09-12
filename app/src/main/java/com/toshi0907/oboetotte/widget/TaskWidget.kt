package com.toshi0907.oboetotte.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import com.toshi0907.oboetotte.MainActivity
import com.toshi0907.oboetotte.data.AppDatabase
import kotlinx.coroutines.flow.first

/**
 * ホーム画面ウィジェット。全リスト横断でトップレベルの未完了タスクを、メイン画面と同じ
 * 並び順(期限が近い順。[com.toshi0907.oboetotte.data.TaskDao.getAll]のクエリ順序を
 * そのまま利用)で一覧表示する。タップするとアプリ(MainActivity)を開くのみで、
 * ウィジェット上での完了操作は行わない。表示内容はDB更新のたびに各所から呼ばれる
 * `TaskWidget().updateAll(context)`で再描画される。
 */
class TaskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val tasks = AppDatabase.getInstance(context).taskDao().getAll().first()
            .filter { it.parentTaskId == null && !it.isDone }

        provideContent {
            Column(modifier = GlanceModifier.fillMaxSize().padding(8.dp)) {
                if (tasks.isEmpty()) {
                    Text(text = "未完了タスクはありません")
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(tasks, itemId = { it.id }) { task ->
                            Text(
                                text = task.title,
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
}

/** [TaskWidget]をシステムに公開するための`AppWidgetProvider`。AndroidManifest.xmlに登録している。 */
class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}
