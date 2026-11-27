package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodeTimingStatsTest {
    @Test
    fun `counts over-budget frames overall and within the early window`() {
        val s = EncodeTimingStats(budgetNs = 10_000_000L, earlyFrames = 2)
        s.record(15_000_000L) // early, slow
        s.record(5_000_000L) // early, fine
        s.record(12_000_000L) // late, slow
        assertEquals("n=3 avg=10.7ms max=15.0ms over10ms=2 (first2=1)", s.summary())
    }

    @Test
    fun `reset clears the burst`() {
        val s = EncodeTimingStats()
        s.record(20_000_000L)
        s.reset()
        assertEquals("n=0", s.summary())
    }
}
