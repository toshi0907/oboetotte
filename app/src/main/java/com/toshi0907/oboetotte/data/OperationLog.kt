package com.toshi0907.oboetotte.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * タスクの追加・削除・完了や設定変更などの操作の履歴。[source]は操作の経路(アプリ/通知/
 * ウィジェット/共有/自動)、[action]は操作の種別(例: 「タスク追加」)、[detail]は対象や変更内容を
 * 表す表示用の文字列。いずれも記録時点の内容をそのまま文字列として保持する(後から対象が削除・
 * 改名されても記録当時の内容を表示できるようにするため)。保存期間は
 * [com.toshi0907.oboetotte.oplog.OperationLogger.RETENTION_MONTHS]ヶ月。
 *
 * [taskGroupId]は特定のタスクに対する操作の場合に、対象タスクの
 * [attachmentGroupId](繰り返しシリーズ全体で共通のID)を記録する。
 * タスクの詳細画面でそのタスク(繰り返しの場合はシリーズ全体)の操作ログだけを表示するために使う。
 * タスクに紐づかない操作(設定変更等)や、この列の追加前に記録されたログはnull。
 */
@Entity(
    tableName = "operation_logs",
    indices = [Index(value = ["timestamp"]), Index(value = ["taskGroupId"])]
)
data class OperationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val source: String,
    val action: String,
    val detail: String,
    val taskGroupId: Long? = null
)
