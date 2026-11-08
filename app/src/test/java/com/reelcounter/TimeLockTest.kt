package com.reelcounter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeLockTest {

    @Test
    fun everyQuarterHourRoundTripsThroughTheDials() {
        for (m in 0..CountStore.TIME_LIMIT_MAX step 15) {
            val d = TimeLock.dials(m)
            assertEquals("$m min", m, TimeLock.value(d[0], d[1]))
        }
        assertArrayEquals(intArrayOf(2, 0), TimeLock.dials(120))
        assertArrayEquals(intArrayOf(2, 2), TimeLock.dials(150))
        assertArrayEquals(intArrayOf(0, 1), TimeLock.dials(15))
    }

    @Test
    fun typedMinutesRoundToTheNearestQuarterAndStayInRange() {
        assertEquals(135, TimeLock.dials(140).let { TimeLock.value(it[0], it[1]) })
        assertEquals(150, TimeLock.dials(143).let { TimeLock.value(it[0], it[1]) })
        assertEquals(CountStore.TIME_LIMIT_MAX, TimeLock.dials(5000).let { TimeLock.value(it[0], it[1]) })
        assertEquals(0, TimeLock.dials(-30).let { TimeLock.value(it[0], it[1]) })
    }

    @Test
    fun theMinutesDrumLoopsThroughFourQuartersTwice() {
        assertEquals(listOf(0, 15, 30, 45, 0, 15, 30, 45), TimeLock.MINUTE_FACES)
        // Face 5 shows 15, the same value as face 1; the dials loop either way.
        assertEquals(TimeLock.value(1, 1), TimeLock.value(1, 5))
        assertEquals(TimeLock.value(9, 3), TimeLock.value(-1, -1))
        // Rolling on a 4-value dial takes the short way round.
        assertEquals(-1, LimitLock.shortestRoll(0, 3, 4))
        assertEquals(2, LimitLock.shortestRoll(0, 2, 4))
    }

    @Test
    fun zeroIsNotATimeLimit() {
        assertFalse(TimeLock.isValid(0))
        assertTrue(TimeLock.isValid(15))
        assertTrue(TimeLock.isValid(9 * 60 + 45))
        assertFalse(TimeLock.isValid(10 * 60))
        assertEquals(120, TimeLock.DEFAULT)
        assertTrue(TimeLock.DEFAULT in TimeLock.QUICK_PICKS)
        assertTrue(TimeLock.QUICK_PICKS.all(TimeLock::isValid))
    }

    @Test
    fun colourLevelsByHours() {
        mapOf(15 to 0, 60 to 0, 75 to 1, 120 to 1, 135 to 2, 180 to 2, 195 to 3, 300 to 3, 315 to 4, 585 to 4)
            .forEach { (m, level) -> assertEquals("$m min", level, TimeLock.level(m)) }
    }

    @Test
    fun aWeekOfIt() {
        assertEquals(14, TimeLock.weekHours(120))
        assertEquals(18, TimeLock.weekHours(150)) // 17.5 rounds up
        assertEquals(2 to 30, TimeLock.split(150))
    }

    @Test
    fun timeOnlyAppsNeverCountReels() {
        val timeOnly = Platform.entries.filter { it.timeOnly }
        assertEquals(setOf(Platform.FACEBOOK, Platform.X, Platform.REDDIT), timeOnly.toSet())
        timeOnly.forEach {
            assertFalse("$it must not be a whole-app reel feed", it.isWholeApp)
            assertFalse(it.countsReels)
            assertTrue(it.progressBarId == null && it.signatureRootId == null)
        }
        // Reel apps are unchanged: TikTok is still a whole-app feed.
        assertTrue(Platform.TIKTOK.isWholeApp)
        assertTrue(Platform.INSTAGRAM.countsReels)
    }

    @Test
    fun everyPackageBelongsToOneApp() {
        val all = Platform.entries.flatMap { it.packages }
        assertEquals(all.size, all.toSet().size)
        assertEquals(Platform.FACEBOOK, Platform.forPackage("com.facebook.lite"))
        assertEquals(Platform.X, Platform.forPackage("com.twitter.android"))
        assertEquals(Platform.REDDIT, Platform.forPackage("com.reddit.frontpage"))
    }

    @Test
    fun focusHoursForAllAppsIncludeTheNewOnes() {
        val all = NoReelSchedule.decode("2;Sleep;1380;420;127;1;*")!!
        assertTrue(all.locks(Platform.FACEBOOK) && all.locks(Platform.REDDIT))
        // An old save of "every app" (63) means every app, the new ones too.
        assertTrue(NoReelSchedule.decode("2;Sleep;1380;420;127;1;63")!!.locks(Platform.X))
        // An old save of only Instagram stays only Instagram.
        val ig = NoReelSchedule.decode("1;Work;600;1080;31;1;1")!!
        assertTrue(ig.locks(Platform.INSTAGRAM))
        assertFalse(ig.locks(Platform.FACEBOOK))
    }
}
