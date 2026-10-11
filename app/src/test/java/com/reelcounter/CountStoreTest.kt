package com.reelcounter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The parts of CountStore that don't need a phone. */
class CountStoreTest {

    private val today = LocalDate.of(2026, 10, 10)

    @Test
    fun pruneDropsOldDetailButKeepsDailyTotals() {
        val old = today.minusDays(CountStore.KEEP_DETAIL_DAYS + 1)
        val recent = today.minusDays(CountStore.KEEP_DETAIL_DAYS - 1)
        val keys = listOf(
            "hour|$old|21", "app|$old|INSTAGRAM", "time|$old|YOUTUBE", "opens|$old|TIKTOK",
            "total|$old", "opens|$old", "hit|$old",
            "hour|$recent|21", "app|$recent|INSTAGRAM",
        )
        val stale = CountStore.staleKeys(keys, today)
        assertEquals(listOf("hour|$old|21", "app|$old|INSTAGRAM", "time|$old|YOUTUBE", "opens|$old|TIKTOK"), stale)
    }

    @Test
    fun pruneDropsEverythingDatedAfterTwoYears() {
        val ancient = today.minusDays(CountStore.KEEP_DAYS + 1)
        val stale = CountStore.staleKeys(listOf("total|$ancient", "hit|$ancient", "unlocks|$ancient", "total|$today"), today)
        assertEquals(listOf("total|$ancient", "hit|$ancient", "unlocks|$ancient"), stale)
    }

    @Test
    fun pruneLeavesSettingsAlone() {
        val keys = listOf(
            "daily_limit", "tracked|INSTAGRAM", "app_limit|YOUTUBE", "block_always|SNAPCHAT",
            "schedules", "first_day", "pruned_on", "theme_choice", "time_limit|TIKTOK",
        )
        assertTrue(CountStore.staleKeys(keys, today).isEmpty())
    }

    @Test
    fun pauseWaitsLongerAfterTheFreeOpens() {
        assertEquals(0, CountStore.pauseSecondsFor(5))
        assertEquals(3, CountStore.pauseSecondsFor(6))
        assertEquals(5, CountStore.pauseSecondsFor(7))
        assertEquals(30, CountStore.pauseSecondsFor(12))
        assertEquals(30, CountStore.pauseSecondsFor(40))
    }

    @Test
    fun pauseFollowsTheChosenNumberOfFreeOpens() {
        assertEquals(0, CountStore.pauseSecondsFor(3, free = 3))
        assertEquals(3, CountStore.pauseSecondsFor(4, free = 3))
        assertEquals(0, CountStore.pauseSecondsFor(10, free = 10))
        assertEquals(3, CountStore.pauseSecondsFor(11, free = 10))
    }
}
