package com.toshi0907.oboetotte.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException

/**
 * タスクに変更があるたびに[TaskWidget]の表示を更新するための共通の呼び出し口
 * ([TaskViewModel][com.toshi0907.oboetotte.TaskViewModel]の各操作・
 * [TaskCompletion][com.toshi0907.oboetotte.TaskCompletion.complete]の末尾、および
 * ウィジェットの「更新」ボタン([RefreshTaskWidgetAction])から呼ぶ)。
 * `updateAll`の前に、既存の全ウィジェットインスタンスの[TaskWidget.LAST_UPDATED_AT_KEY]を
 * 現在時刻に更新することで、最終更新日時をウィジェットインスタンスごとに保持する(Issue #111)。
 * この最終更新日時の更新が失敗しても(例:一部インスタンスのDataStore書き込みエラー)
 * `updateAll`本来のタスク一覧再描画は必ず実行されるよう、別の`try`で囲み独立して捕捉する。
 * さらに、インスタンスが複数ある場合に1つの書き込み失敗で残りのインスタンスの更新まで
 * 巻き込まれないよう、`glanceIds`の各要素ごとに個別に捕捉して処理を継続する。
 * `updateAll`が例外を投げても、タスク自体の操作(DB書き込み)は既に完了しているため
 * 巻き戻す必要は無く、ウィジェットの再描画失敗だけで呼び出し元の処理全体を失敗させたくない。
 * そのためここで捕捉し、原因調査用にログ(タグ`TaskWidget`)へ残すだけに留める。
 */
suspend fun refreshTaskWidget(context: Context) {
    val now = System.currentTimeMillis()
    val glanceIds = try {
        GlanceAppWidgetManager(context).getGlanceIds(TaskWidget::class.java)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("TaskWidget", "ウィジェットインスタンスの取得に失敗しました", e)
        emptyList()
    }
    glanceIds.forEach { glanceId ->
        try {
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[TaskWidget.LAST_UPDATED_AT_KEY] = now
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("TaskWidget", "最終更新日時の更新に失敗しました", e)
        }
    }

    try {
        TaskWidget().updateAll(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("TaskWidget", "ウィジェットの更新に失敗しました", e)
    }
}
