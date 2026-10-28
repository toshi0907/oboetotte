package com.toshi0907.oboetotte

import org.junit.Assert.assertEquals
import org.junit.Test
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
}
