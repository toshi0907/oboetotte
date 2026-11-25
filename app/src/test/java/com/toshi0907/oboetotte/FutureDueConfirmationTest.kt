package com.toshi0907.oboetotte

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FutureDueConfirmationTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun millis(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    private val now = millis(LocalDateTime.of(2026, 11, 25, 10, 0))

    @Test
    fun noDueAt_isFalse() {
        assertFalse(isDueAfterToday(null, now, zone))
    }

    @Test
    fun laterToday_isFalse() {
        assertFalse(isDueAfterToday(millis(LocalDateTime.of(2026, 11, 25, 23, 59)), now, zone))
    }

    @Test
    fun past_isFalse() {
        assertFalse(isDueAfterToday(millis(LocalDateTime.of(2026, 11, 24, 23, 59)), now, zone))
    }

    @Test
    fun tomorrowMidnight_isTrue() {
        assertTrue(isDueAfterToday(millis(LocalDateTime.of(2026, 11, 26, 0, 0)), now, zone))
    }

    @Test
    fun nextYear_isTrue() {
        assertTrue(isDueAfterToday(millis(LocalDateTime.of(2027, 1, 1, 9, 0)), now, zone))
    }
}
