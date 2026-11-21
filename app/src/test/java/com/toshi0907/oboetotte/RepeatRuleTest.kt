package com.toshi0907.oboetotte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RepeatRuleTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun millis(dateTime: String, zone: ZoneId = tokyo): Long =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun afterCompletion_usesCompletionDateAndKeepsDueTime() {
        val next = RepeatRule.nextDueAtAfterCompletion(
            currentDueAt = millis("2026-01-10T09:00"),
            completedAt = millis("2026-01-05T22:30"),
            intervalDays = 30,
            zone = tokyo
        )
        assertEquals(millis("2026-02-04T09:00"), next)
    }

    @Test
    fun afterCompletion_completedAfterDue_startsFromActualCompletionDate() {
        val next = RepeatRule.nextDueAtAfterCompletion(
            currentDueAt = millis("2026-01-10T09:00"),
            completedAt = millis("2026-01-20T15:00"),
            intervalDays = 30,
            zone = tokyo
        )
        assertEquals(millis("2026-02-19T09:00"), next)
    }

    @Test
    fun afterCompletion_keepsLocalTimeAcrossDstChange() {
        val newYork = ZoneId.of("America/New_York")
        val next = RepeatRule.nextDueAtAfterCompletion(
            currentDueAt = millis("2026-03-01T09:00", newYork),
            completedAt = millis("2026-03-01T10:00", newYork),
            intervalDays = 14,
            zone = newYork
        )
        assertEquals(millis("2026-03-15T09:00", newYork), next)
    }

    private fun dates(vararg values: String): List<LocalDate> = values.map { LocalDate.parse(it) }

    @Test
    fun parseDates_sortsDedupesAndDropsInvalid() {
        assertEquals(
            dates("2026-12-01", "2026-12-15"),
            RepeatRule.parseDates("2026-12-15, 2026-12-01,invalid,2026-12-15,2026-13-40")
        )
        assertEquals(emptyList<LocalDate>(), RepeatRule.parseDates(null))
        assertEquals(emptyList<LocalDate>(), RepeatRule.parseDates(""))
    }

    @Test
    fun formatDates_roundTripsAndReturnsNullWhenEmpty() {
        assertEquals("2026-12-01,2026-12-15", RepeatRule.formatDates(dates("2026-12-15", "2026-12-01")))
        assertNull(RepeatRule.formatDates(emptyList()))
    }

    @Test
    fun customDates_nextIsNearestLaterDateWithSameTime() {
        val next = RepeatRule.nextDueAtCustomDates(
            current = millis("2026-12-01T09:30"),
            dates = dates("2026-11-20", "2026-12-01", "2026-12-24", "2026-12-10"),
            zone = tokyo
        )
        assertEquals(millis("2026-12-10T09:30"), next)
    }

    @Test
    fun customDates_returnsNullAfterLastDate() {
        val next = RepeatRule.nextDueAtCustomDates(
            current = millis("2026-12-24T09:30"),
            dates = dates("2026-12-01", "2026-12-24"),
            zone = tokyo
        )
        assertNull(next)
    }

    @Test
    fun nextDueAt_customDatesReturnsCurrentWhenNoneLeft() {
        val current = millis("2026-12-24T09:30")
        assertEquals(current, RepeatRule.nextDueAt(current, RepeatRule.CUSTOM_DATES, emptySet(), dates("2026-12-01")))
    }

    @Test
    fun alignCustomDates_dropsPastDatesAndAlignsDueToEarliest() {
        val aligned = RepeatRule.alignCustomDates(
            dueAt = millis("2026-11-25T18:00"),
            dates = setOf(LocalDate.parse("2026-12-05"), LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-21")),
            today = LocalDate.parse("2026-11-21"),
            zone = tokyo
        )
        assertEquals(millis("2026-11-21T18:00") to dates("2026-11-21", "2026-12-05"), aligned)
    }

    @Test
    fun alignCustomDates_returnsNullWhenOnlyPastDates() {
        val aligned = RepeatRule.alignCustomDates(
            dueAt = millis("2026-11-25T18:00"),
            dates = dates("2026-11-01"),
            today = LocalDate.parse("2026-11-21"),
            zone = tokyo
        )
        assertNull(aligned)
    }

    @Test
    fun alignCustomDates_capsAtMaxCount() {
        val start = LocalDate.parse("2027-01-01")
        val many = (0 until 150).map { start.plusDays(it.toLong()) }
        val aligned = RepeatRule.alignCustomDates(millis("2026-11-25T18:00"), many, LocalDate.parse("2026-11-21"), tokyo)
        assertEquals(RepeatRule.MAX_CUSTOM_DATES, aligned?.second?.size)
    }
}
