package com.toshi0907.oboetotte.oplog

import com.toshi0907.oboetotte.data.Task
import com.toshi0907.oboetotte.formatDueAt
import com.toshi0907.oboetotte.locationLabel
import com.toshi0907.oboetotte.notification.ReminderScheduler
import com.toshi0907.oboetotte.repeatRuleLabel

private const val NONE_LABEL = "なし"
private const val MAX_TEXT_LENGTH = 30

/**
 * タスク編集の操作ログ用に、[before]から[after]への変更内容を「項目: 変更前 → 変更後」の形式で
 * 列挙する。[listNames]/[patternNames]はリスト・バイブレーションパターンのIDから名前を引くための
 * もの(見つからない場合はIDを表示する)。変更が無い場合は空のリストを返す。
 */
fun describeTaskChanges(
    before: Task,
    after: Task,
    listNames: Map<Long, String>,
    patternNames: Map<Long, String>
): List<String> {
    val changes = mutableListOf<String>()
    fun add(label: String, old: String?, new: String?) {
        if (old != new) changes.add("$label: ${old ?: NONE_LABEL} → ${new ?: NONE_LABEL}")
    }
    // 長い文字列は切り詰めて表示するため、切り詰め後の表示が同じでも元の値が違えば変更として記録する。
    fun addText(label: String, old: String?, new: String?) {
        if (old != new) changes.add("$label: ${old?.ellipsize() ?: NONE_LABEL} → ${new?.ellipsize() ?: NONE_LABEL}")
    }
    addText("タイトル", before.title, after.title)
    add("リスト", before.listId?.let { listNames[it] ?: "ID $it" }, after.listId?.let { listNames[it] ?: "ID $it" })
    add("期限", before.dueAt?.let(::formatDueAt), after.dueAt?.let(::formatDueAt))
    add("繰り返し", repeatRuleLabel(before), repeatRuleLabel(after))
    val beforeLocation = locationLabel(before)
    val afterLocation = locationLabel(after)
    if (beforeLocation == afterLocation &&
        (before.latitude != after.latitude || before.longitude != after.longitude)
    ) {
        // 場所名・半径・タイミングが同じまま座標だけ変わった場合も変更として記録する。
        changes.add("場所: ${afterLocation ?: NONE_LABEL}(座標を変更)")
    } else {
        add("場所", beforeLocation, afterLocation)
    }
    addText("URL", before.url, after.url)
    addText("メモ", before.memo, after.memo)
    add("オートスヌーズ", autoSnoozeText(before), autoSnoozeText(after))
    add("通知のみ", onOffText(before.notifyOnlyMode), onOffText(after.notifyOnlyMode))
    add(
        "バイブレーション",
        before.vibrationPatternId?.let { patternNames[it] ?: "ID $it" },
        after.vibrationPatternId?.let { patternNames[it] ?: "ID $it" }
    )
    return changes
}

private fun autoSnoozeText(task: Task): String? {
    val minutes = task.autoSnoozeMinutes ?: return null
    return ReminderScheduler.SNOOZE_OPTIONS.find { it.minutes == minutes }?.label ?: "${minutes}分後"
}

private fun onOffText(value: Boolean): String = if (value) "オン" else "オフ"

/** 長いメモ・URLでログが読みづらくならないよう、改行を空白にし[MAX_TEXT_LENGTH]文字で切り詰める。 */
private fun String.ellipsize(): String {
    val singleLine = replace(Regex("\\s+"), " ").trim()
    return if (singleLine.length > MAX_TEXT_LENGTH) singleLine.take(MAX_TEXT_LENGTH) + "…" else singleLine
}
