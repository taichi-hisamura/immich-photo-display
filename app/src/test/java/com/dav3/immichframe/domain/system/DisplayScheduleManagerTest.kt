package com.dav3.immichframe.domain.system

import com.dav3.immichframe.domain.model.SlideshowSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DisplayScheduleManagerTest {
    @Test
    fun `screen schedule is active across midnight`() {
        val settings = SlideshowSettings(
            screenScheduleEnabled = true,
            screenScheduleOffTime = 22 * 60,
            screenScheduleOnTime = 7 * 60,
        )

        assertTrue(settings.isScreenScheduleActive(23 * 60))
        assertTrue(settings.isScreenScheduleActive(6 * 60 + 59))
        assertFalse(settings.isScreenScheduleActive(7 * 60))
        assertFalse(settings.isScreenScheduleActive(12 * 60))
    }

    @Test
    fun `next occurrence advances to tomorrow when time has passed`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = ZonedDateTime.of(2026, 8, 24, 8, 0, 0, 0, zone)

        val next = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(nextOccurrenceMillis(7 * 60, now)),
            zone,
        )

        assertEquals(25, next.dayOfMonth)
        assertEquals(7, next.hour)
        assertEquals(0, next.minute)
    }
}
