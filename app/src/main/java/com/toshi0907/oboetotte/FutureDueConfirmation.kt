package com.toshi0907.oboetotte

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 期日が翌日以降(端末のタイムゾーンでの日付が今日より後)かどうか。翌日以降が期日のタスクを
 * 完了しようとした場合は、アプリ内(TaskScreen)・通知の「完了」ボタン
 * (notification/CompleteConfirmActivity)のいずれでも、確認ダイアログを経てから完了させる。
 * 期日が無いタスクは常にfalse。
 */
internal fun isDueAfterToday(
    dueAt: Long?,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): Boolean {
    if (dueAt == null) return false
    val dueDate = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return dueDate.isAfter(today)
}

/** 確認ダイアログの本文。アプリ内・通知経由で文言を揃える。 */
internal fun futureDueConfirmationMessage(dueAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val dueDate: LocalDate = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
    return "期日が翌日以降(${dueDate.monthValue}月${dueDate.dayOfMonth}日)のタスクです。完了にしてよろしいですか？"
}
