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
 */
@Entity(tableName = "operation_logs", indices = [Index(value = ["timestamp"])])
data class OperationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val source: String,
    val action: String,
    val detail: String
)
