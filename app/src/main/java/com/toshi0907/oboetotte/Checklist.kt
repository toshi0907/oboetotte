package com.toshi0907.oboetotte

/** タスクのチェックリストの1項目。[text]は改行を含まない1行の文字列。 */
data class ChecklistItem(val text: String, val checked: Boolean = false)

/**
 * [com.toshi0907.oboetotte.data.Task.checklist]の文字列表現と[ChecklistItem]の一覧を相互変換する。
 * 1項目を1行とし、行頭の`[x] `(チェック済み)/`[ ] `(未チェック)で状態を表す。項目のテキストは
 * 改行を含まないよう正規化してから保存するため、行単位で安全に分割できる。
 * 詳細はdocs/architecture/task-checklist.md。
 */
object Checklist {
    private const val CHECKED_PREFIX = "[x] "
    private const val UNCHECKED_PREFIX = "[ ] "

    fun parse(value: String?): List<ChecklistItem> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.lines().mapNotNull { line ->
            when {
                line.startsWith(CHECKED_PREFIX) -> ChecklistItem(line.removePrefix(CHECKED_PREFIX), true)
                line.startsWith(UNCHECKED_PREFIX) -> ChecklistItem(line.removePrefix(UNCHECKED_PREFIX), false)
                // 想定外の形式の行(手で編集されたバックアップ等)は未チェックの項目として扱う。
                else -> ChecklistItem(line, false)
            }.let { item -> item.copy(text = normalizeText(item.text)) }.takeIf { it.text.isNotEmpty() }
        }
    }

    /** 空の項目を除いて文字列化する。項目が1つも無ければnull(カラムをNULLにする)を返す。 */
    fun format(items: List<ChecklistItem>): String? {
        val lines = items.mapNotNull { item ->
            val text = normalizeText(item.text)
            if (text.isEmpty()) null else (if (item.checked) CHECKED_PREFIX else UNCHECKED_PREFIX) + text
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    /** 追加用の入力欄の内容を、1行1項目(未チェック)として分割する。空行は無視する。 */
    fun itemsFromInput(input: String): List<ChecklistItem> =
        input.lines().map { normalizeText(it) }.filter { it.isNotEmpty() }.map { ChecklistItem(it) }

    /** 繰り返しタスクの次回分用に、項目はそのままですべて未チェックに戻す。 */
    fun resetChecks(value: String?): String? = format(parse(value).map { it.copy(checked = false) })

    /** 改行等を空白にまとめ、前後の空白を除く。 */
    fun normalizeText(text: String): String = text.replace(Regex("[\\r\\n]+"), " ").trim()
}
