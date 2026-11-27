package com.atakmap.android.xv.service

import org.junit.Assert.assertTrue
import org.junit.Test

class CodecWarmupTest {
    @Test
    fun `runs real encode and decode over the requested frames`() {
        val summary = CodecWarmup.run(frames = 20)
        assertTrue(summary, summary.startsWith("20 frames in "))
    }
}
