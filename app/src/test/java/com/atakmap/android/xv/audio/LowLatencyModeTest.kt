package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LowLatencyModeTest {
    @Test
    fun `hold phrase matches the hold constant`() {
        assertEquals("1 minute", LowLatencyMode.holdPhrase(60_000L))
        assertEquals("2 minutes", LowLatencyMode.holdPhrase(120_000L))
        assertEquals("30 seconds", LowLatencyMode.holdPhrase(30_000L))
    }

    @Test
    fun `whole-channel hold reads as the whole time in a channel`() {
        assertEquals(LowLatencyMode.WHOLE_CHANNEL, LowLatencyMode.HOLD_MS)
        assertEquals("the whole time you're in a channel", LowLatencyMode.holdSentence())
        assertEquals("for 1 minute after you join a channel or talk", LowLatencyMode.holdSentence(60_000L))
    }
}
