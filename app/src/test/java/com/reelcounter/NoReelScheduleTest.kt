package com.reelcounter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NoReelScheduleTest {
    // 2026-10-05 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)

    private val bedtime = NoReelSchedule(1, "Bedtime", 23 * 60, 7 * 60, NoReelSchedule.EVERY_DAY, true)
    private val study = NoReelSchedule(3, "Study", 14 * 60, 16 * 60, NoReelSchedule.WEEKDAYS, true)

    @Test
    fun overnightScheduleCoversBothSidesOfMidnight() {
        assertTrue(bedtime.isActive(at(5, 23, 30)))
        assertTrue(bedtime.isActive(at(6, 6, 59)))
        assertFalse(bedtime.isActive(at(6, 7, 0)))
        assertFalse(bedtime.isActive(at(5, 22, 59)))
    }

    @Test
    fun morningPartBelongsToTheDayItStarted() {
        // Only Sunday night is ticked: Sunday 23:30 and Monday 03:00 are in, Monday 23:30 isn't.
        val sundayOnly = bedtime.copy(days = 1 shl 6)
        assertTrue(sundayOnly.isActive(at(4, 23, 30)))
        assertTrue(sundayOnly.isActive(at(5, 3, 0)))
        assertFalse(sundayOnly.isActive(at(5, 23, 30)))
    }

    @Test
    fun weekdayScheduleSkipsWeekends() {
        assertTrue(study.isActive(at(7, 15, 0)))   // Wednesday
        assertFalse(study.isActive(at(10, 15, 0))) // Saturday
        assertFalse(study.isActive(at(7, 16, 0)))  // ends at 16:00
    }

    @Test
    fun disabledScheduleIsNeverActive() {
        assertFalse(bedtime.copy(enabled = false).isActive(at(5, 23, 30)))
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        assertEquals(bedtime, NoReelSchedule.decode(bedtime.encode()))
        val work = study.copy(apps = NoReelSchedule.appsMask(listOf(Platform.INSTAGRAM, Platform.TIKTOK)))
        assertEquals(work, NoReelSchedule.decode(work.encode()))
    }

    @Test
    fun schedulesSavedBeforeAppPicksLockEveryApp() {
        val old = NoReelSchedule.decode("1;Bedtime;1380;420;127;1")!!
        assertEquals(NoReelSchedule.ALL_APPS, old.apps)
        Platform.entries.forEach { assertTrue(old.locks(it)) }
    }

    @Test
    fun focusHoursLockOnlyTheChosenApps() {
        val work = study.copy(apps = NoReelSchedule.appsMask(listOf(Platform.INSTAGRAM, Platform.YOUTUBE)))
        assertTrue(work.locks(Platform.INSTAGRAM))
        assertTrue(work.locks(Platform.YOUTUBE))
        assertFalse(work.locks(Platform.SNAPCHAT))
        assertFalse(work.locks(Platform.TIKTOK))
    }

    @Test
    fun brokenSavedSchedulesAreIgnored() {
        assertEquals(null, NoReelSchedule.decode("1;Bedtime;1380"))
        assertEquals(null, NoReelSchedule.decode("x;Bedtime;1380;420;127;1"))
        assertEquals(null, NoReelSchedule.decode("1;Bedtime;9999;420;127;1"))
        assertEquals(null, NoReelSchedule.decode("1;Bedtime;-5;420;127;1"))
    }

    @Test
    fun namesCantBreakTheSavedFormat() {
        val tricky = bedtime.copy(name = "Late;night|scroll")
        val back = NoReelSchedule.decode(tricky.encode())!!
        assertEquals("Late night scroll", back.name)
        assertEquals(tricky.startMinute, back.startMinute)
    }
}
