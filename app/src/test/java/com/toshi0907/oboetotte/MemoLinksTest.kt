package com.toshi0907.oboetotte

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoLinksTest {
    private fun urls(text: String): List<String> = findMemoUrlRanges(text).map { text.substring(it) }

    @Test
    fun findsHttpAndHttpsUrls() {
        assertEquals(
            listOf("https://example.com/a?b=1#c", "http://example.org"),
            urls("参考 https://example.com/a?b=1#c と\nhttp://example.org")
        )
    }

    @Test
    fun ignoresUrlsWithoutScheme() {
        assertEquals(emptyList<String>(), urls("www.example.com や example.com は対象外"))
    }

    @Test
    fun stopsAtJapaneseAndFullWidthCharacters() {
        assertEquals(listOf("https://example.com/a"), urls("詳細はhttps://example.com/aを参照"))
        assertEquals(listOf("https://example.com"), urls("(https://example.com)。"))
        assertEquals(listOf("https://example.com"), urls("「https://example.com」、"))
    }

    @Test
    fun trimsTrailingAsciiPunctuation() {
        assertEquals(listOf("https://example.com/a"), urls("See https://example.com/a."))
        assertEquals(listOf("https://example.com"), urls("(see https://example.com), ok"))
    }

    @Test
    fun keepsBalancedClosingParenthesis() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            urls("https://en.wikipedia.org/wiki/Foo_(bar)")
        )
    }

    @Test
    fun ignoresSchemeOnly() {
        assertEquals(emptyList<String>(), urls("https:// だけ"))
    }
}
