package com.reelcounter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class LockSoundsTest {

    @Test
    fun aClickIs40msAtTheRequestedVolume() {
        val c = LockSounds.click(2300f, 0.45f)
        assertEquals((44100 * 0.04).toInt(), c.size)
        assertEquals(0.45f, c.maxOf { abs(it) }, 1e-4f)
        // It fades out (design envelope (1 − t)^4).
        val head = c.take(200).maxOf { abs(it) }
        val tail = c.takeLast(200).maxOf { abs(it) }
        assertTrue(tail < head / 10)
    }

    @Test
    fun clicksAreTheSameEveryTime() {
        assertArrayEquals(LockSounds.click(1500f, .45f), LockSounds.click(1500f, .45f), 0f)
    }

    @Test
    fun theBandPassKeepsItsPitchAndCutsOthers() {
        fun tone(f: Double) = FloatArray(4410) { kotlin.math.sin(2 * Math.PI * f * it / 44100).toFloat() }
        fun level(x: FloatArray) = x.drop(2000).maxOf { abs(it) }
        val at = level(LockSounds.bandPass(tone(3200.0), 3200f, 6f))
        val off = level(LockSounds.bandPass(tone(300.0), 3200f, 6f))
        assertTrue("passes its own pitch: $at", at > 0.9f)
        assertTrue("cuts far pitches: $off", off < 0.1f)
    }

    @Test
    fun theThunkStaysInRangeAndFades() {
        val t = LockSounds.thunk()
        assertTrue(t.all { it in -1f..1f })
        assertTrue(t.takeLast(500).maxOf { abs(it) } < 0.01f)
    }

    @Test
    fun wavHeaderIs16BitMono44k() {
        val bytes = LockSounds.wavBytes(floatArrayOf(0f, 1f, -1f))
        assertEquals(44 + 6, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(44100, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(6, b.getInt(40))
        assertEquals(Short.MAX_VALUE, b.getShort(46))
    }
}
