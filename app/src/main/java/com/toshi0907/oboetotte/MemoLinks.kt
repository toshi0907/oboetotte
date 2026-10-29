package com.toshi0907.oboetotte

/**
 * メモ本文中のURL(`http://`/`https://`で始まるもののみ)の位置を返す。
 *
 * URLとして扱うのは空白・`"`・`<`・`>`以外のASCII印字可能文字の連続で、日本語や全角記号
 * (`。`/`、`/`)`/`」`等)が続いた時点でURLの終わりとみなす。末尾の半角句読点(`.`/`,`等)と、
 * 対応する`(`/`[`/`{`がURL内に無い閉じ括弧は文章側の記号とみなしてURLから除く。
 */
fun findMemoUrlRanges(text: String): List<IntRange> =
    URL_REGEX.findAll(text).mapNotNull { match ->
        var end = match.range.last
        while (end > match.range.first) {
            val c = text[end]
            val trim = when (c) {
                in TRAILING_PUNCTUATION -> true
                ')' -> isUnbalancedClose(text, match.range.first, end, '(', ')')
                ']' -> isUnbalancedClose(text, match.range.first, end, '[', ']')
                '}' -> isUnbalancedClose(text, match.range.first, end, '{', '}')
                else -> false
            }
            if (!trim) break
            end--
        }
        val range = match.range.first..end
        // スキームだけ("https://")しか残らなかった場合はURLとみなさない
        if (text.substring(range).substringAfter("://").isEmpty()) null else range
    }.toList()

private val URL_REGEX = Regex("""https?://[\x21\x23-\x3B\x3D\x3F-\x7E]+""", RegexOption.IGNORE_CASE)

private const val TRAILING_PUNCTUATION = ".,;:!?'"

private fun isUnbalancedClose(text: String, start: Int, end: Int, open: Char, close: Char): Boolean {
    val segment = text.substring(start, end + 1)
    return segment.count { it == close } > segment.count { it == open }
}
