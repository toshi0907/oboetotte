package com.toshi0907.oboetotte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecklistTest {
    @Test
    fun formatAndParseRoundTrip() {
        val items = listOf(ChecklistItem("牛乳", true), ChecklistItem("卵"), ChecklistItem("[x] パン"))
        val formatted = Checklist.format(items)
        assertEquals("[x] 牛乳\n[ ] 卵\n[ ] [x] パン", formatted)
        assertEquals(items, Checklist.parse(formatted))
    }

    @Test
    fun formatDropsBlankItemsAndReturnsNullWhenEmpty() {
        assertNull(Checklist.format(emptyList()))
        assertNull(Checklist.format(listOf(ChecklistItem("  "), ChecklistItem(""))))
        assertEquals("[ ] a b", Checklist.format(listOf(ChecklistItem(" a\nb "), ChecklistItem(" "))))
    }

    @Test
    fun parseHandlesNullAndUnknownLines() {
        assertEquals(emptyList<ChecklistItem>(), Checklist.parse(null))
        assertEquals(emptyList<ChecklistItem>(), Checklist.parse(""))
        assertEquals(
            listOf(ChecklistItem("手書きの行"), ChecklistItem("done", true)),
            Checklist.parse("手書きの行\n\n[x] done\n[ ] ")
        )
    }

    @Test
    fun itemsFromInputSplitsLinesAndSkipsBlanks() {
        assertEquals(
            listOf(ChecklistItem("りんご"), ChecklistItem("みかん"), ChecklistItem("ぶどう")),
            Checklist.itemsFromInput(" りんご\r\n\nみかん\n  \nぶどう  ")
        )
        assertEquals(emptyList<ChecklistItem>(), Checklist.itemsFromInput("\n \n"))
    }

    @Test
    fun resetChecksUnchecksAllItems() {
        assertEquals("[ ] a\n[ ] b", Checklist.resetChecks("[x] a\n[ ] b"))
        assertNull(Checklist.resetChecks(null))
    }
}
