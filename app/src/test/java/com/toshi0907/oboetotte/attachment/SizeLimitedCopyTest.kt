package com.toshi0907.oboetotte.attachment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SizeLimitedCopyTest {
    @Test
    fun copyToWithLimit_copiesWhenWithinLimit() {
        val data = ByteArray(10_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val copied = ByteArrayInputStream(data).copyToWithLimit(out, data.size.toLong(), "over")
        assertEquals(data.size.toLong(), copied)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test(expected = SizeLimitExceededException::class)
    fun copyToWithLimit_throwsWhenOverLimit() {
        ByteArrayInputStream(ByteArray(10_001)).copyToWithLimit(ByteArrayOutputStream(), 10_000, "over")
    }

    @Test
    fun readBytesWithLimit_reportsLimitMessage() {
        val e = runCatching {
            ByteArrayInputStream(ByteArray(100)).readBytesWithLimit(99, "上限超過")
        }.exceptionOrNull()
        assertEquals("上限超過", (e as SizeLimitExceededException).message)
    }

    @Test
    fun formatLimitBytes_usesGbForWholeGigabytes() {
        assertEquals("100MB", formatLimitBytes(100L * 1024 * 1024))
        assertEquals("2GB", formatLimitBytes(2L * 1024 * 1024 * 1024))
        assertEquals("16MB", formatLimitBytes(16L * 1024 * 1024))
    }
}
