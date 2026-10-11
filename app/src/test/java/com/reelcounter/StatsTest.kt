package com.reelcounter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The numbers behind Progress. */
class StatsTest {

    @Test
    fun daysUnderCountsOnlyDaysBelowTheLimit() {
        assertEquals(2, Stats.daysUnder(listOf(10, 30, 29, 31), limit = 30))
        assertEquals(0, Stats.daysUnder(emptyList(), limit = 30))
    }

    @Test
    fun changePercentRoundsAndNeedsSomethingToCompareWith() {
        assertEquals(-18, Stats.changePercent(24.6, 30.0))
        assertEquals(50, Stats.changePercent(15.0, 10.0))
        assertEquals(0, Stats.changePercent(10.0, 10.0))
        assertNull(Stats.changePercent(10.0, 0.0))
    }

    @Test
    fun heaviestWindowFindsTheBusiestThreeHours() {
        val hours = IntArray(24).apply {
            this[20] = 8
            this[21] = 9
            this[22] = 6
            this[12] = 4
        }
        assertEquals(20, Stats.heaviestWindow(hours))
    }

    @Test
    fun heaviestWindowWrapsPastMidnight() {
        val hours = IntArray(24).apply {
            this[23] = 5
            this[0] = 5
            this[1] = 5
            this[10] = 6
        }
        assertEquals(23, Stats.heaviestWindow(hours))
    }

    @Test
    fun heaviestWindowIsNullWithoutReels() {
        assertNull(Stats.heaviestWindow(IntArray(24)))
    }

    @Test
    fun heaviestStretchLeavesOffEmptyHours() {
        // Every reel in one hour: "12 am to 1 am", not "12 am to 3 am" (seen on the phone).
        assertEquals(0 to 1, Stats.heaviestStretch(IntArray(24).apply { this[0] = 47 }))
        assertEquals(20 to 2, Stats.heaviestStretch(IntArray(24).apply { this[20] = 5; this[21] = 6 }))
        assertEquals(20 to 3, Stats.heaviestStretch(IntArray(24).apply { this[20] = 5; this[21] = 1; this[22] = 4 }))
        assertEquals(23 to 2, Stats.heaviestStretch(IntArray(24).apply { this[23] = 5; this[0] = 5 }))
        assertNull(Stats.heaviestStretch(IntArray(24)))
    }

    @Test
    fun runsEndAtTheLastDayAndRememberTheBest() {
        assertEquals(2 to 3, Stats.runs(listOf(true, true, true, false, true, true)))
        assertEquals(0 to 1, Stats.runs(listOf(true, false)))
        assertEquals(0 to 0, Stats.runs(emptyList()))
    }

    @Test
    fun minutesSavedIsNeverNegative() {
        assertEquals(70L, Stats.minutesSaved(currentPerDay = 20.0, previousPerDay = 30.0, days = 7))
        assertEquals(0L, Stats.minutesSaved(currentPerDay = 40.0, previousPerDay = 30.0, days = 7))
    }

    @Test
    fun daysIncludesBothEnds() {
        val d = Stats.days(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2))
        assertEquals(listOf(29, 30, 1, 2), d.map { it.dayOfMonth })
        assertEquals(emptyList<LocalDate>(), Stats.days(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun moodRampMatchesTheDesign() {
        assertEquals(0, MoodColors.index(0f))
        assertEquals(0, MoodColors.index(0.29f))
        assertEquals(1, MoodColors.index(0.3f))
        assertEquals(2, MoodColors.index(0.6f))
        assertEquals(3, MoodColors.index(0.85f))
        assertEquals(4, MoodColors.index(1f))
        assertEquals(4, MoodColors.index(3f))
        // The number gets louder: 600 when calm, 800 at the limit.
        assertEquals(600, MoodColors.heroWeight(0f))
        assertEquals(800, MoodColors.heroWeight(1.2f))
    }
}
