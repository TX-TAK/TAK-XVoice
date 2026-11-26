package com.atakmap.android.xv.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LowLatencyModeTest {
    @Test
    fun `hold phrase matches the hold constant`() {
        assertEquals("1 minute", LowLatencyMode.holdPhrase(60_000L))
        assertEquals("2 minutes", LowLatencyMode.holdPhrase(120_000L))
        assertEquals("30 seconds", LowLatencyMode.holdPhrase(30_000L))
        assertEquals(LowLatencyMode.holdPhrase(), LowLatencyMode.holdPhrase(LowLatencyMode.HOLD_MS))
    }
}
