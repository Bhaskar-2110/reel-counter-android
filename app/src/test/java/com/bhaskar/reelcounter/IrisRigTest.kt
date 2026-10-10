package com.bhaskar.reelcounter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The checks from IRIS_EYES_SPEC.md that don't need a screen. */
class IrisRigTest {

    private val frame = 1f / 60f

    private fun IrisRig.run(seconds: Float) = repeat((seconds / frame).toInt()) { update(frame) }

    private fun IrisRig.lids() = eyes.flatMap { e -> listOf(e.open.x, e.tilt.x, e.low.x, e.lowC.x, e.pup.x) }

    @Test
    fun slidingFromZeroToTheLimitNeverJumps() {
        val rig = IrisRig(reduceMotion = true)
        rig.run(1f)
        var worst = 0f
        for (step in 0..600) {
            rig.progress = step / 600f
            val before = rig.lids()
            rig.update(frame)
            val after = rig.lids()
            // Blinks move the lids on purpose; only compare open-eye frames.
            if (rig.blink == 0f) worst = maxOf(worst, before.zip(after).maxOf { (a, b) -> abs(a - b) })
        }
        assertTrue("largest lid change in one frame was $worst", worst < 0.05f)
    }

    @Test
    fun rampNamesFollowTheBands() {
        val rig = IrisRig(reduceMotion = true)
        fun moodAt(p: Float): Mood {
            rig.progress = p
            rig.update(frame)
            return rig.mood
        }
        assertEquals(Mood.HAPPY, moodAt(0.1f))
        assertEquals(Mood.CURIOUS, moodAt(0.45f))
        assertEquals(Mood.WORRIED, moodAt(0.7f))
        assertEquals(Mood.SAD, moodAt(0.9f))
        assertEquals(Mood.ANGRY, moodAt(1f))
    }

    @Test
    fun threeQuickReelsElectrocuteThenSettle() {
        val rig = IrisRig(reduceMotion = true)
        rig.run(0.5f)
        rig.onReel(0.03f); rig.run(0.4f)
        rig.onReel(0.06f); rig.run(0.4f)
        rig.onReel(0.1f); rig.update(frame)
        assertEquals(Mood.ELECTRIC, rig.mood)
        rig.run(2.5f)
        assertEquals(Mood.HAPPY, rig.mood)
    }

    @Test
    fun crossingABandSurprises() {
        val rig = IrisRig(reduceMotion = true)
        rig.progress = 0.28f
        rig.run(1f)
        rig.onReel(0.31f)
        rig.update(frame)
        assertEquals(Mood.SURPRISED, rig.mood)
        rig.run(1f)
        // Past halfway from the Happy anchor (0.18) to Curious (0.42).
        assertEquals(Mood.CURIOUS, rig.mood)
    }

    @Test
    fun reachingTheLimitIsSurpriseThenAnger() {
        val rig = IrisRig(reduceMotion = true)
        rig.progress = 0.97f
        rig.run(1f)
        rig.onReel(1f)
        rig.update(frame)
        assertEquals(Mood.SURPRISED, rig.mood)
        rig.run(1f)
        assertEquals(Mood.ANGRY, rig.mood)
    }

    @Test
    fun sleepyWakesWithAStartleOnTheNextReel() {
        val rig = IrisRig(reduceMotion = true)
        rig.trigger(Mood.SLEEPY)
        rig.run(5f)
        assertEquals(Mood.SLEEPY, rig.mood)
        rig.onReel(0.05f)
        rig.update(frame)
        assertEquals(Mood.SURPRISED, rig.mood)
        assertTrue(rig.fy.v < 0f)
        rig.run(1f)
        assertEquals(Mood.HAPPY, rig.mood)
    }

    @Test
    fun springsSettleOnTheMood() {
        val rig = IrisRig(reduceMotion = true)
        rig.locked = Mood.SAD
        rig.run(4f)
        val left = rig.eyes[0]
        assertEquals(Mood.SAD.tilt, left.tilt.x, 0.01f)
        assertEquals(Mood.SAD.fs, rig.fs.x, 0.01f)
        assertEquals(Mood.SAD.color, rig.color)
    }

    @Test
    fun pastTheLimitAngerSettlesIntoSadnessAndFlaresOnAnotherReel() {
        val rig = IrisRig(reduceMotion = true)
        rig.progress = 0.97f
        rig.run(1f)
        rig.onReel(1f)
        rig.run(9f)
        assertEquals(Mood.SAD, rig.mood)
        rig.onReel(1.03f)
        rig.update(frame)
        assertEquals(Mood.ANGRY, rig.mood)
        rig.run(3.5f)
        assertEquals(Mood.SAD, rig.mood)
    }

    @Test
    fun aHopJigglesLikeJellyThenSettles() {
        val rig = IrisRig(reduceMotion = true)
        rig.run(2f)
        rig.onReel(0.05f)
        var flips = 0
        var last = rig.sq.x
        repeat(60) {
            rig.update(frame)
            if ((rig.sq.x > 0f) != (last > 0f) && abs(rig.sq.x) > 0.002f) flips++
            if (abs(rig.sq.x) > 0.002f) last = rig.sq.x
        }
        assertTrue("squash changed direction only $flips times in 1 s", flips >= 3)
        rig.run(3f)
        assertTrue("still squashed by ${rig.sq.x} after 4 s", abs(rig.sq.x) < 0.01f)
    }

    /** Counts giggle bursts (big squishes at least 1.5 s apart) over [seconds]. */
    private fun giggleBursts(mood: Mood, reduceMotion: Boolean = false, seconds: Float = 30f): Int {
        val rig = IrisRig(reduceMotion)
        rig.locked = mood
        var bursts = 0
        var last = -10f
        repeat((seconds / frame).toInt()) {
            rig.update(frame)
            if (abs(rig.sq.x) > 0.05f && rig.t - last > 1.5f) {
                bursts++
                last = rig.t
            }
        }
        return bursts
    }

    @Test
    fun happyCuriousAndWorriedGiggleSadAndSleepyDont() {
        assertTrue("happy giggled ${giggleBursts(Mood.HAPPY)} times in 30 s", giggleBursts(Mood.HAPPY) >= 4)
        assertTrue("curious giggled ${giggleBursts(Mood.CURIOUS)} times in 30 s", giggleBursts(Mood.CURIOUS) >= 2)
        assertTrue(giggleBursts(Mood.WORRIED) >= 1)
        // (Angry squishes too, but those are its huffs.)
        assertEquals(0, giggleBursts(Mood.SAD))
        assertEquals(0, giggleBursts(Mood.SLEEPY))
    }

    @Test
    fun reduceMotionStopsGiggles() {
        assertEquals(0, giggleBursts(Mood.HAPPY, reduceMotion = true))
    }
}
