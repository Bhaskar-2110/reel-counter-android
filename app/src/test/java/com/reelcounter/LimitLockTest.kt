package com.reelcounter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitLockTest {

    @Test
    fun digitsAndValueRoundTripForEveryLimit() {
        for (v in 0..999) {
            val d = LimitLock.digits(v)
            assertEquals(v, LimitLock.value(d[0], d[1], d[2]))
        }
        assertArrayEquals(intArrayOf(1, 0, 0), LimitLock.digits(100))
        assertArrayEquals(intArrayOf(0, 0, 7), LimitLock.digits(7))
        // Out of range is clamped, never wrapped into a different limit.
        assertArrayEquals(intArrayOf(9, 9, 9), LimitLock.digits(5000))
        assertArrayEquals(intArrayOf(0, 0, 0), LimitLock.digits(-3))
    }

    @Test
    fun dialsLoopSoAnyPositionReadsAsADigit() {
        assertEquals(0, LimitLock.value(10, 10, 10))
        assertEquals(999, LimitLock.value(-1, -1, -1))
        assertEquals(123, LimitLock.value(11, 22, 33))
    }

    @Test
    fun zeroIsNotALimitButOneTo999Are() {
        assertFalse(LimitLock.isValid(0))
        assertTrue(LimitLock.isValid(1))
        assertTrue(LimitLock.isValid(999))
        assertFalse(LimitLock.isValid(1000))
        assertTrue(LimitLock.isValid(LimitLock.DEFAULT))
        assertTrue(LimitLock.QUICK_PICKS.all(LimitLock::isValid))
    }

    @Test
    fun aReelIsTwentySeconds() {
        assertEquals(20, LimitLock.SECONDS_PER_REEL)
        assertEquals(33, LimitLock.minutes(100)) // 33.3
        assertEquals(17, LimitLock.minutes(50)) // 16.7 rounds up
        assertEquals(67, LimitLock.minutes(200)) // 66.7
        assertEquals(34, LimitLock.minutes(101)) // 33.7
        assertEquals(333, LimitLock.minutes(999))
        assertEquals(1, LimitLock.minutes(1)) // 20 s: never shown as "0 min"
        assertEquals(1, LimitLock.minutes(2))
        assertEquals(0, LimitLock.minutes(0))
        assertEquals(0, LimitLock.minutes(-5))
    }

    @Test
    fun mostPickIsTheDefault() {
        assertEquals(100, LimitLock.DEFAULT)
        assertEquals(CountStore.LIMIT_DEFAULT, LimitLock.DEFAULT)
        assertTrue(LimitLock.DEFAULT in LimitLock.QUICK_PICKS)
    }

    @Test
    fun colourLevelsChangeAtTheDesignThresholds() {
        val cases = mapOf(1 to 0, 100 to 0, 101 to 1, 200 to 1, 201 to 2, 400 to 2, 401 to 3, 700 to 3, 701 to 4, 999 to 4)
        cases.forEach { (v, level) -> assertEquals("limit $v", level, LimitLock.level(v)) }
    }

    @Test
    fun quickPicksRollTheShortWayRound() {
        assertEquals(1, LimitLock.shortestRoll(0, 1))
        assertEquals(-1, LimitLock.shortestRoll(0, 9))
        assertEquals(5, LimitLock.shortestRoll(0, 5))
        assertEquals(-4, LimitLock.shortestRoll(5, 1))
        assertEquals(0, LimitLock.shortestRoll(7, 7))
        for (from in 0..9) for (to in 0..9) {
            val d = LimitLock.shortestRoll(from, to)
            assertTrue(d in -4..5)
            assertEquals(to, (from + d).mod(10))
        }
    }

    @Test
    fun oathWordsLightUpAsTheButtonFills() {
        val words = 9 // "I promise to keep it to 100 reels a day."
        assertEquals(0, LimitLock.litWords(0f, words))
        assertEquals(words, LimitLock.litWords(1f, words))
        assertEquals(4, LimitLock.litWords(0.5f, words))
        var last = 0
        for (i in 0..100) {
            val n = LimitLock.litWords(i / 100f, words)
            assertTrue(n >= last)
            last = n
        }
        assertEquals(words, LimitLock.litWords(2f, words))
        assertEquals(0, LimitLock.litWords(-1f, words))
    }

    @Test
    fun holdPulsesSpeedUp() {
        assertEquals(260L, LimitLock.pulseIntervalMs(0f))
        assertEquals(60L, LimitLock.pulseIntervalMs(1f))
        assertTrue(LimitLock.pulseIntervalMs(0.5f) in 61L..259L)
        assertEquals(2500L, LimitLock.HOLD_MS)
    }
}
