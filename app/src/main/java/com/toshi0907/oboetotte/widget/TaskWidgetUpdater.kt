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
 * [TaskWidget.LAST_UPDATED_AT_KEY]は「最後に成功した再描画の時刻」を表すべきなので、
 * まず1回目の`updateAll`でタスク一覧の再描画を行い、それが成功した場合に限り
 * 既存の全ウィジェットインスタンスの[TaskWidget.LAST_UPDATED_AT_KEY]を現在時刻に更新した上で、
 * その値を画面に反映させるため2回目の`updateAll`を呼ぶ(Issue #111)。1回目の`updateAll`が
 * 失敗した場合は最終更新日時を更新せず、ウィジェットには前回成功時点の日時が表示され続ける
 * (再描画に失敗しているのに更新されたように見えることを防ぐ)。
 * いずれの段階の例外も、タスク自体の操作(DB書き込み)は既に完了しているため巻き戻す必要は無く、
 * ウィジェットの再描画・状態更新の失敗だけで呼び出し元の処理全体を失敗させたくないため、
 * ここで捕捉し原因調査用にログ(タグ`TaskWidget`)へ残すだけに留める。複数インスタンスがある
 * 場合に1つの書き込み失敗で残りのインスタンスの更新まで巻き込まれないよう、`glanceIds`の
 * 各要素ごとにも個別に捕捉して処理を継続する。長寿命な処理になりうるため、呼び出し元から
 * 渡された[Context]がActivity等ライフサイクルに紐づいていてもリークしないよう
 * [Context.getApplicationContext]を使う。
 */
suspend fun refreshTaskWidget(context: Context) {
    val appContext = context.applicationContext
    try {
        TaskWidget().updateAll(appContext)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("TaskWidget", "ウィジェットの更新に失敗しました", e)
        return
    }

    val now = System.currentTimeMillis()
    val glanceIds = try {
        GlanceAppWidgetManager(appContext).getGlanceIds(TaskWidget::class.java)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("TaskWidget", "ウィジェットインスタンスの取得に失敗しました", e)
        emptyList()
    }
    glanceIds.forEach { glanceId ->
        try {
            updateAppWidgetState(appContext, glanceId) { prefs ->
                prefs[TaskWidget.LAST_UPDATED_AT_KEY] = now
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("TaskWidget", "最終更新日時の更新に失敗しました", e)
        }
    }

    try {
        TaskWidget().updateAll(appContext)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("TaskWidget", "最終更新日時の再描画に失敗しました", e)
    }
}
