package com.toshi0907.oboetotte.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException

/**
 * タスクに変更があるたびに[TaskWidget]の表示を更新するための共通の呼び出し口
 * ([TaskViewModel][com.toshi0907.oboetotte.TaskViewModel]の各操作・
 * [TaskCompletion][com.toshi0907.oboetotte.TaskCompletion.complete]の末尾から呼ぶ)。
 * `updateAll`が例外を投げても、タスク自体の操作(DB書き込み)は既に完了しているため
 * 巻き戻す必要は無く、ウィジェットの再描画失敗だけで呼び出し元の処理全体を失敗させたくない。
 * そのためここで捕捉し、原因調査用にログ(タグ`TaskWidget`)へ残すだけに留める。
 */
suspend fun refreshTaskWidget(context: Context) {
    try {
        TaskWidget().updateAll(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("TaskWidget", "ウィジェットの更新に失敗しました", e)
    }
}
