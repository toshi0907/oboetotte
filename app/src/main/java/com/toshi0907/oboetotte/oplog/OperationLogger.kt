package com.toshi0907.oboetotte.oplog

import android.content.Context
import android.util.Log
import com.toshi0907.oboetotte.data.AppDatabase
import com.toshi0907.oboetotte.data.OperationLog
import com.toshi0907.oboetotte.data.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** 操作ログの[OperationLog.source](操作の経路)に記録する値。 */
object OperationLogSource {
    const val APP = "アプリ"
    const val NOTIFICATION = "通知"
    const val WIDGET = "ウィジェット"
    const val SHARE = "共有"
    const val AUTO = "自動"
}

/**
 * 操作ログ([OperationLog])の記録・削除をまとめたもの。記録の失敗(DBエラー等)で本来の操作自体が
 * 失敗しないよう、例外はここで握りつぶしてログ出力のみ行う。記録のたびに保存期間
 * ([RETENTION_MONTHS]ヶ月)を過ぎた古いログを削除する。
 */
object OperationLogger {
    private const val TAG = "OperationLogger"

    /** 操作ログの保存期間(月)。これより古いログは自動削除する。 */
    const val RETENTION_MONTHS = 1L

    // ViewModelのクリアやActivityの終了で記録が途中キャンセルされないよう、呼び出し元の
    // スコープとは独立したプロセス全体のスコープで書き込む(logAsync用)。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun log(context: Context, source: String, action: String, detail: String = "") {
        try {
            AppDatabase.getInstance(context).operationLogDao().insertAndPurge(
                OperationLog(
                    timestamp = System.currentTimeMillis(),
                    source = source,
                    action = action,
                    detail = detail
                ),
                retentionCutoff()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "操作ログの記録に失敗しました: $action", e)
        }
    }

    /** suspend関数から呼べない箇所(設定変更のコールバック等)向けに、非同期で[log]する。 */
    fun logAsync(context: Context, source: String, action: String, detail: String = "") {
        val appContext = context.applicationContext
        scope.launch { log(appContext, source, action, detail) }
    }

    /** 保存期間を過ぎたログを削除する。アプリ起動時に呼ぶ(しばらく操作が無かった場合の保険)。 */
    suspend fun purgeExpired(context: Context) {
        try {
            AppDatabase.getInstance(context).operationLogDao().deleteOlderThan(retentionCutoff())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "古い操作ログの削除に失敗しました", e)
        }
    }

    /** ログ画面の「ログを消去」ボタンから呼ぶ。全件削除した後、消去した操作自体を記録する。 */
    suspend fun clearAll(context: Context) {
        try {
            AppDatabase.getInstance(context).operationLogDao().deleteAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "操作ログの消去に失敗しました", e)
            return
        }
        log(context, OperationLogSource.APP, "操作ログを消去")
    }

    private fun retentionCutoff(): Long =
        ZonedDateTime.now().minusMonths(RETENTION_MONTHS).toInstant().toEpochMilli()
}

/** 操作ログの[OperationLog.detail]に記録するタスクの表示名。サブタスクはその旨を併記する。 */
fun Task.logLabel(): String = if (parentTaskId != null) "「$title」(サブタスク)" else "「$title」"
