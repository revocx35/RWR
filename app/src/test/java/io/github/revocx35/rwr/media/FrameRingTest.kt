package io.github.revocx35.rwr.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRingTest {
    private fun frame(seq: Int, ts: Long, size: Int = 10) = EncodedFrame(seq, ts, ByteArray(size), 270)

    @Test fun keepsOnlyTheLastSeconds() {
        val ring = FrameRing(seconds = 2)
        (0..50).forEach { ring.add(frame(it, it * 100L)) }
        assertEquals(3000L, ring.since(0).first().tsMs)
        assertEquals(listOf(48, 49, 50), ring.since(4800).map { it.seq })
        assertEquals(42, ring.bySeq(42)!!.seq)
        assertNull(ring.bySeq(5))
    }

    @Test fun capsMemory() {
        val ring = FrameRing(seconds = 100, maxBytes = 1000)
        (0..20).forEach { ring.add(frame(it, it * 100L, size = 100)) }
        assertEquals(10, ring.size)
    }

    @Test fun clipTimingMatchesServer() {
        // Same cases as server/tests/test_logic.py
        assertEquals(listOf(0, 1, 1, 1, 2), ClipTiming.resample(listOf(0L, 100L, 400L), 10))
        assertEquals(5, ClipTiming.fps(listOf(0L, 200L, 400L, 600L)))
        assertEquals(1, ClipTiming.fps(listOf(5L)))
    }
}
